"""Real owned fixture children shut down even when their graceful protocols stall."""

import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import Mock, patch

from .editor import AndroidControls, editor
from .processes import close_child
from .stack import serve


def release_test_child(process):
    if process.poll() is None:
        process.terminate()
    process.wait(timeout=5)


@unittest.skipUnless(os.name == "posix", "The AOSP acceptance runner uses POSIX signals")
class OwnedChildTest(unittest.TestCase):
    def test_stalled_server_escalates_and_reaps_before_returning(self):
        script = (
            "from pathlib import Path\nimport signal, time\n"
            "signal.signal(signal.SIGINT, signal.SIG_IGN)\n"
            "Path('ready').touch()\ntime.sleep(60)"
        )
        with tempfile.TemporaryDirectory() as tmp, patch("verification.unifiedpush.stack.SERVICE_GRACE_SECONDS", .05), \
                patch("verification.unifiedpush.processes.ESCALATION_SECONDS", .2):
            directory = Path(tmp)
            with serve([sys.executable, "-c", script], directory) as child:
                self.addCleanup(release_test_child, child)
                limit = time.monotonic() + 5
                while not (directory / "ready").exists():
                    if time.monotonic() >= limit:
                        self.fail("Owned server did not start")
                    time.sleep(.01)
            self.assertEqual(child.returncode, -signal.SIGTERM)
            self.assertEqual(child.wait(timeout=0), -signal.SIGTERM)

    def test_stalled_observer_reconnects_closes_pipes_and_removes_its_jar(self):
        # An incomplete reply used to hang the finish handshake before its timeout could run.
        script = (
            "import time\nprint('HM_UI cmVhZHk=', flush=True)\n"
            "assert input() == '{\"action\":\"finish\"}'\n"
            "print('HM_UI ', end='', flush=True)\ntime.sleep(60)"
        )
        device = Mock(command=[sys.executable, "-u", "-c", script])
        with tempfile.TemporaryDirectory() as tmp, patch("verification.unifiedpush.editor.subprocess.run"), \
                patch("verification.unifiedpush.editor.OBSERVER_GRACE_SECONDS", .05):
            problem = ValueError("event did not arrive")
            with self.assertRaises(ValueError) as raised:
                with editor(device, Path(tmp)) as controls:
                    previous = controls.process
                    self.addCleanup(release_test_child, previous)
                    controls.reconnect()
                    current = controls.process
                    self.addCleanup(release_test_child, current)
                    raise problem
            self.assertIs(raised.exception, problem)
            jar = device.run.call_args.args[-1]
            device.shell.assert_called_with("rm", "-f", jar)
            self.assertIsNone(controls.process)
            for child in (previous, current):
                self.assertEqual(child.returncode, -signal.SIGTERM)
                self.assertTrue(child.stdin.closed and child.stdout.closed)

    def test_observer_ignoring_terminate_reports_failure_and_preserves_the_original_error(self):
        script = (
            "import signal, sys, time\nfrom pathlib import Path\n"
            "signal.signal(signal.SIGTERM, signal.SIG_IGN)\n"
            "print('HM_UI cmVhZHk=', flush=True)\n"
            "while not Path(sys.argv[1]).exists(): time.sleep(.01)"
        )
        with tempfile.TemporaryDirectory() as tmp, patch("verification.unifiedpush.editor.subprocess.run"), \
                patch("verification.unifiedpush.editor.OBSERVER_GRACE_SECONDS", .05), \
                patch("verification.unifiedpush.processes.ESCALATION_SECONDS", .1):
            directory = Path(tmp)
            release = directory / "release"
            device = Mock(command=[sys.executable, "-u", "-c", script, str(release)])
            original = ValueError("notification was missing")
            child = None
            try:
                with self.assertRaises(ValueError) as raised:
                    with editor(device, directory) as controls:
                        child = controls.process
                        started = time.monotonic()
                        raise original
                self.assertLess(time.monotonic() - started, 5)
                self.assertIs(raised.exception, original)
                self.assertIn(f"process {child.pid} is still running", str(original.__notes__))
                self.assertIn("after terminate()", str(original.__notes__))
                self.assertIsNone(child.poll())
                self.assertTrue(child.stdin.closed and child.stdout.closed)
                jar = device.run.call_args.args[-1]
                device.shell.assert_called_with("rm", "-f", jar)
                with self.assertRaises(ExceptionGroup) as standalone:
                    close_child(child, lambda: None)
                self.assertIn(f"process {child.pid} is still running", str(standalone.exception.exceptions[0]))
            finally:
                release.touch()
                if child is not None:
                    self.assertEqual(child.wait(timeout=5), 0)

    def test_graceful_observer_receives_finish_and_exits_successfully(self):
        script = (
            "import json, sys\nprint('HM_UI cmVhZHk=', flush=True)\n"
            "sys.exit(0 if json.loads(input()) == {'action':'finish'} else 1)"
        )
        controls = AndroidControls([sys.executable, "-u", "-c", script])
        controls.connect()
        child = controls.process
        self.addCleanup(release_test_child, child)
        controls.close()
        self.assertEqual(child.returncode, 0)
        self.assertTrue(child.stdin.closed and child.stdout.closed)

    def test_graceful_protocol_error_keeps_the_primary_exception(self):
        child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(60)"])
        self.addCleanup(release_test_child, child)
        problem = AssertionError("original check")
        with self.assertRaises(AssertionError) as raised:
            try:
                raise problem
            finally:
                close_child(child, Mock(side_effect=BrokenPipeError("observer input closed")))
        self.assertIs(raised.exception, problem)
        self.assertIn("observer input closed", str(raised.exception.__notes__))
        self.assertIsNotNone(child.returncode)

    def test_partial_stdout_line_does_not_bypass_answer_timeout(self):
        script = (
            "import sys, time\n"
            "print('HM_UI cmVhZHk=', flush=True)\n"
            "sys.stdout.write('.')\n"
            "sys.stdout.flush()\n"
            "time.sleep(60)\n"
        )
        controls = AndroidControls([sys.executable, "-u", "-c", script])
        with patch("verification.unifiedpush.editor.ANSWER_TIMEOUT_SECONDS", 0.2), patch(
            "verification.unifiedpush.editor.OBSERVER_GRACE_SECONDS", 0.05
        ):
            controls.connect()
            child = controls.process
            self.addCleanup(release_test_child, child)
            try:
                started = time.monotonic()
                with self.assertRaises(TimeoutError):
                    controls.answer()
                self.assertLess(time.monotonic() - started, 3.0)
            finally:
                controls.close()
        self.assertIsNotNone(child.returncode)

    def test_gateway_stop_terminates_stalled_process_and_clears_handle_even_if_marker_fails(self):
        from verification.gateway_fixture import Gateway

        gateway = object.__new__(Gateway)
        gateway.python = sys.executable
        gateway.root = Path(tempfile.gettempdir())
        gateway.env = dict(os.environ)
        child = subprocess.Popen(
            [sys.executable, "-c", "import time; time.sleep(60)"],
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
        )
        self.addCleanup(release_test_child, child)
        gateway.process = child
        with patch("verification.gateway_fixture.subprocess.run", side_effect=subprocess.TimeoutExpired("cmd", 20)):
            with self.assertRaises(RuntimeError):
                gateway.stop()
        self.assertIsNone(gateway.process)
        self.assertIsNotNone(child.poll())
        self.assertTrue(child.stdout.closed)

    def test_script_mode_imports_and_gateway_stop_work_without_repo_root_on_sys_path(self):
        verification_dir = str(Path(__file__).resolve().parents[1])
        probe = (
            "import os, subprocess, sys, tempfile\n"
            "from pathlib import Path\n"
            "from unittest.mock import patch\n"
            "import device.app_strings as app_strings\n"
            "import device.run as device_run\n"
            "import gateway_fixture\n"
            "import unifiedpush.processes as processes\n"
            "assert app_strings.APP == device_run.APP == 'org.llm4people.hermes'\n"
            "gw = object.__new__(gateway_fixture.Gateway)\n"
            "gw.python = sys.executable\n"
            "gw.root = Path(tempfile.gettempdir())\n"
            "gw.env = dict(os.environ)\n"
            "child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)'], "
            "stdout=subprocess.PIPE, stderr=subprocess.STDOUT)\n"
            "gw.process = child\n"
            "with patch('gateway_fixture.subprocess.run', side_effect=subprocess.TimeoutExpired('cmd', 1)):\n"
            "    try:\n"
            "        gw.stop()\n"
            "    except RuntimeError:\n"
            "        pass\n"
            "assert gw.process is None and child.poll() is not None and child.stdout.closed\n"
        )
        env = {k: v for k, v in os.environ.items() if k != "PYTHONPATH"}
        env["PYTHONPATH"] = verification_dir
        result = subprocess.run(
            [sys.executable, "-B", "-c", probe],
            cwd=tempfile.gettempdir(),
            env=env,
            capture_output=True,
            text=True,
            timeout=20,
        )
        self.assertEqual(result.returncode, 0, result.stderr)

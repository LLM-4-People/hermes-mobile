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

    def test_unused_loopback_port_stays_outside_linux_ephemeral_range_and_gateway_retries_port_collision(self):
        from types import SimpleNamespace
        from verification import gateway_fixture

        for _ in range(10):
            port = gateway_fixture.unused_loopback_port()
            self.assertGreaterEqual(port, gateway_fixture.PORT_RANGE_START)
            self.assertLess(port, gateway_fixture.PORT_RANGE_START + gateway_fixture.PORT_RANGE_SPAN)

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            hermes_home = root / "home" / ".hermes"
            hermes_home.mkdir(parents=True)
            env_file = hermes_home / ".env"
            env_file.write_text("API_SERVER_ENABLED=true\nAPI_SERVER_PORT=21000\n", encoding="utf-8")

            gw = object.__new__(gateway_fixture.Gateway)
            gw.python = sys.executable
            gw.root = root
            gw.port = 21000
            gw.endpoint = "http://127.0.0.1:21000"
            gw.env = {"HERMES_HOME": str(hermes_home)}
            gw.process = None

            first_proc = subprocess.Popen(
                [sys.executable, "-c", "import sys; print('api_server: Port 21000 already in use'); sys.exit(1)"],
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
            )
            second_proc = subprocess.Popen(
                [sys.executable, "-c", "import time; time.sleep(60)"],
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
            )
            self.addCleanup(release_test_child, first_proc)
            self.addCleanup(release_test_child, second_proc)

            spawned = []

            def fake_popen(cmd, **kwargs):
                log = kwargs["stdout"]
                if not spawned:
                    log.write(b"api_server: Port 21000 already in use\n")
                    log.flush()
                    spawned.append(first_proc)
                    return first_proc
                spawned.append(second_proc)
                return second_proc

            class FakePsutilError(Exception):
                pass

            fake_psutil = SimpleNamespace(
                Error=FakePsutilError,
                CONN_LISTEN="LISTEN",
                Process=lambda pid: SimpleNamespace(
                    net_connections=lambda kind="tcp": [
                        SimpleNamespace(status="LISTEN", laddr=(gateway_fixture.LOOPBACK, gw.port))
                    ]
                ),
            )
            with (
                patch.dict(sys.modules, {"psutil": fake_psutil}),
                patch("verification.gateway_fixture.subprocess.Popen", side_effect=fake_popen),
                patch("verification.gateway_fixture.unused_loopback_port", return_value=22000),
                patch.object(gw, "_capabilities_ready", return_value=True),
            ):
                gw.start()
            self.assertEqual(gw.port, 22000)
            self.assertEqual(gw.endpoint, "http://127.0.0.1:22000")
            self.assertIn("API_SERVER_PORT=22000", env_file.read_text(encoding="utf-8"))
            self.assertIs(gw.process, second_proc)

    def test_gateway_start_handles_psutil_no_such_process_and_zombie_on_port_collision(self):
        from types import SimpleNamespace
        from verification import gateway_fixture

        class FakePsutilError(Exception):
            pass

        class FakeNoSuchProcess(FakePsutilError):
            pass

        class FakeZombieProcess(FakePsutilError):
            pass

        for failure_mode in ("no_such_process", "zombie_process"):
            with self.subTest(failure_mode=failure_mode), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                hermes_home = root / "home" / ".hermes"
                hermes_home.mkdir(parents=True)
                env_file = hermes_home / ".env"
                env_file.write_text("API_SERVER_ENABLED=true\nAPI_SERVER_PORT=21000\n", encoding="utf-8")

                gw = object.__new__(gateway_fixture.Gateway)
                gw.python = sys.executable
                gw.root = root
                gw.port = 21000
                gw.endpoint = "http://127.0.0.1:21000"
                gw.env = {"HERMES_HOME": str(hermes_home)}
                gw.process = None

                # First process returns poll()=None on first check (unreaped), then exits with status 1
                # after logging port-in-use so psutil raises NoSuchProcess/ZombieProcess on the race window.
                log_path = root / "gateway.log"
                polls = iter([None, 1])

                class RacingProc:
                    pid = 99999

                    def poll(self):
                        val = next(polls, 1)
                        if val is not None:
                            with log_path.open("ab") as fh:
                                fh.write(b"api_server: Port 21000 already in use\n")
                        return val

                second_proc = SimpleNamespace(pid=88888, poll=lambda: None)
                spawned = []

                def fake_popen(cmd, **kwargs):
                    if not spawned:
                        proc = RacingProc()
                        spawned.append(proc)
                        return proc
                    spawned.append(second_proc)
                    return second_proc

                def fake_process(pid):
                    if pid == 99999:
                        if failure_mode == "no_such_process":
                            raise FakeNoSuchProcess(pid)
                        return SimpleNamespace(
                            net_connections=lambda kind="tcp": (_ for _ in ()).throw(FakeZombieProcess(pid))
                        )
                    return SimpleNamespace(
                        net_connections=lambda kind="tcp": [
                            SimpleNamespace(status="LISTEN", laddr=(gateway_fixture.LOOPBACK, gw.port))
                        ]
                    )

                fake_psutil = SimpleNamespace(
                    Error=FakePsutilError,
                    NoSuchProcess=FakeNoSuchProcess,
                    ZombieProcess=FakeZombieProcess,
                    CONN_LISTEN="LISTEN",
                    Process=fake_process,
                )
                with (
                    patch.dict(sys.modules, {"psutil": fake_psutil}),
                    patch("verification.gateway_fixture.subprocess.Popen", side_effect=fake_popen),
                    patch("verification.gateway_fixture.unused_loopback_port", return_value=22000),
                    patch("verification.gateway_fixture.time.sleep"),
                    patch.object(gw, "_capabilities_ready", return_value=True),
                ):
                    gw.start()
                self.assertEqual(gw.port, 22000)
                self.assertIs(gw.process, second_proc)

    def test_same_port_and_caddy_binary_cleanup_preserves_primary_error(self):
        from .prove import same_port
        from .stack import caddy_binary

        # same_port: primary error preserved with cleanup note when reverse --remove fails.
        device = Mock()
        device.run.side_effect = [b"", b"", RuntimeError("adb reverse remove failed")]
        primary = ValueError("body boom")
        with self.assertRaises(ValueError) as raised:
            with same_port(device, "http://127.0.0.1:18080"):
                raise primary
        self.assertIs(raised.exception, primary)
        self.assertIn("adb reverse remove failed", str(primary.__notes__))

        # same_port: clean exit propagates ExceptionGroup when teardown fails.
        device.run.side_effect = [b"", b"", RuntimeError("adb reverse remove failed")]
        with self.assertRaises(ExceptionGroup) as clean_err:
            with same_port(device, "http://127.0.0.1:18080"):
                pass
        self.assertIn("adb reverse remove failed", str(clean_err.exception.exceptions[0]))

        # caddy_binary: primary docker cp error preserved when docker rm fails.
        with tempfile.TemporaryDirectory() as tmp:
            cp_err = subprocess.CalledProcessError(1, ["docker", "cp"])
            rm_err = subprocess.CalledProcessError(1, ["docker", "rm"])
            with (
                patch("verification.unifiedpush.stack.subprocess.check_output", return_value="cid123\n"),
                patch("verification.unifiedpush.stack.subprocess.run", side_effect=[cp_err, rm_err]),
                self.assertRaises(subprocess.CalledProcessError) as raised_cp,
            ):
                caddy_binary(Path(tmp))
            self.assertIs(raised_cp.exception, cp_err)
            self.assertTrue(any("docker" in note for note in getattr(cp_err, "__notes__", [])))

            # caddy_binary: clean exit propagates ExceptionGroup when docker rm fails.
            with (
                patch("verification.unifiedpush.stack.subprocess.check_output", return_value="cid123\n"),
                patch(
                    "verification.unifiedpush.stack.subprocess.run",
                    side_effect=[subprocess.CompletedProcess([], 0), rm_err],
                ),
                self.assertRaises(ExceptionGroup),
            ):
                caddy_binary(Path(tmp))

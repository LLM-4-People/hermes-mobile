import contextlib
import io
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from native_tests import RUNNER, run, successful_cases


def status(method, code, count=1):
    return (f"INSTRUMENTATION_STATUS: class=app.FrameworkTest\nINSTRUMENTATION_STATUS: test={method}\n"
            f"INSTRUMENTATION_STATUS: id=InstrumentationTestRunner\nINSTRUMENTATION_STATUS: numtests={count}\n"
            f"INSTRUMENTATION_STATUS_CODE: {code}\n")


def report(method="testLaunch"):
    return (status(method, 1) + "INSTRUMENTATION_STATUS: stream=Frame timings\nINSTRUMENTATION_STATUS_CODE: 0\n"
            + status(method, 0) + "INSTRUMENTATION_RESULT: stream=\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n")


class FrameworkSuiteTest(unittest.TestCase):
    def test_only_identified_test_completions_count(self):
        self.assertEqual(successful_cases(report()), {("app.FrameworkTest", "testLaunch")})

    def test_failed_skipped_duplicate_incomplete_and_falsely_empty_reports(self):
        good = report()
        for bad in (good.replace(status("testLaunch", 0), status("testLaunch", -2)),
                    good.replace(status("testLaunch", 0), status("testLaunch", -3)),
                    good.replace(status("testLaunch", 0), ""),
                    good.replace(status("testLaunch", 1), ""),
                    good.replace("OK (1 test)", "OK (0 tests)"),
                    good.replace("numtests=1", "numtests=2"),
                    good.replace("INSTRUMENTATION_CODE: -1", "INSTRUMENTATION_CODE: 0"),
                    good.replace("INSTRUMENTATION_CODE: -1", "INSTRUMENTATION_RESULT: shortMsg=Process crashed."),
                    good + status("testLaunch", 0),
                    good + "INSTRUMENTATION_ABORTED: Process crashed.\n"):
            with self.subTest(output=bad), self.assertRaises(ValueError):
                successful_cases(bad)

    def test_complete_discovery_and_unfiltered_execution_pass(self):
        with tempfile.TemporaryDirectory() as temporary:
            commands = []
            def adb(command, *, stdout, stderr):
                commands.append(command)
                stdout.write(report())
                return subprocess.CompletedProcess(command, 0)
            with patch("native_tests.subprocess.run", adb):
                run("serial", Path(temporary))
            base = ["adb", "-s", "serial", "shell", "am", "instrument", "-w", "-r"]
            self.assertEqual(commands, [base + ["-e", "log", "true", RUNNER], base + [RUNNER]])

    def test_installed_discovery_and_actual_execution_must_match(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            def adb(command, *, stdout, stderr):
                self.assertEqual(command[:7], ["adb", "-s", "serial", "shell", "am", "instrument", "-w"])
                self.assertEqual(command[-1], RUNNER)
                self.assertEqual(stderr, subprocess.STDOUT)
                stdout.write(report("testDiscovery" if "log" in command else "testDifferent"))
                return subprocess.CompletedProcess(command, 0)
            with patch("native_tests.subprocess.run", adb), self.assertRaisesRegex(ValueError, "Suite changed"):
                run("serial", directory)
            self.assertEqual({p.name for p in directory.iterdir()}, {"discovery.txt", "execution.txt"})

    def test_adb_failure_stops_before_execution_and_retains_diagnostic(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            def adb(command, *, stdout, stderr):
                stdout.write("device disconnected")
                return subprocess.CompletedProcess(command, 17)
            log = io.StringIO()
            with (patch("native_tests.subprocess.run", adb), contextlib.redirect_stderr(log),
                  self.assertRaises(subprocess.CalledProcessError)):
                run("serial", directory)
            self.assertEqual(log.getvalue().strip(), "device disconnected")
            self.assertFalse((directory / "execution.txt").exists())

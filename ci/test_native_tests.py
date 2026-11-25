import contextlib
import io
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from native_tests import RUNNER, crash_summary, in_flight_cases, packaged_cases, run, shard_cases, successful_cases


def status(method, code, count=1):
    return (f"INSTRUMENTATION_STATUS: class=app.FrameworkTest\nINSTRUMENTATION_STATUS: test={method}\n"
            f"INSTRUMENTATION_STATUS: id=InstrumentationTestRunner\nINSTRUMENTATION_STATUS: numtests={count}\n"
            f"INSTRUMENTATION_STATUS_CODE: {code}\n")


def report(method="testLaunch"):
    return (status(method, 1) + "INSTRUMENTATION_STATUS: stream=Frame timings\nINSTRUMENTATION_STATUS_CODE: 0\n"
            + status(method, 0) + "INSTRUMENTATION_RESULT: stream=\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n")


class FrameworkSuiteTest(unittest.TestCase):
    expected = {("app.FrameworkTest", "testLaunch")}

    def test_crash_diagnostic_excludes_external_processes_and_exception_messages(self):
        crash = """FATAL EXCEPTION: main
Process: other.app, PID: 1
java.lang.OutOfMemoryError: other secret
    at other.app.Secret.run(Secret.java:1)
FATAL EXCEPTION: instrumentation
Process: org.llm4people.hermes, PID: 2
java.lang.NoClassDefFoundError: private content
    at android.test.Loader.load(Loader.java:2)
"""
        self.assertEqual(crash_summary(crash), "java.lang.NoClassDefFoundError\nandroid.test.Loader.load")

    def test_apk_inventory_includes_inheritance_and_excludes_non_tests(self):
        xml = """<api><package name="app">
          <class name="Base" extends="junit.framework.TestCase" abstract="true" visibility="public">
            <method name="testInherited" visibility="public" return="void" static="false"/>
          </class><class name="FrameworkTest" extends="app.Base" visibility="public">
            <method name="testLaunch" visibility="public" return="void" static="false"/>
            <method name="testWithArg" visibility="public" return="void" static="false"><parameter/></method>
          </class><class name="Helper" extends="java.lang.Object" visibility="public">
            <method name="testUnrelated" visibility="public" return="void" static="false"/>
          </class></package></api>"""
        self.assertEqual(packaged_cases(xml), self.expected | {("app.FrameworkTest", "testInherited")})
        with self.assertRaises(ValueError):
            packaged_cases("<api/>")

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

    def test_complete_packaged_discovery_and_execution_pass(self):
        with tempfile.TemporaryDirectory() as temporary:
            commands = []
            def adb(command, *, stdout, stderr, timeout=None):
                self.assertGreater(timeout, 0)
                commands.append(command)
                stdout.write(report())
                return subprocess.CompletedProcess(command, 0)
            with patch("native_tests.subprocess.run", adb):
                run("serial", Path(temporary), self.expected)
            base = ["adb", "-s", "serial", "shell", "am", "instrument", "-w", "-r", "-e", "class", "app.FrameworkTest"]
            self.assertEqual(commands, [base + ["-e", "log", "true", RUNNER], base + [RUNNER]])

    def test_installed_discovery_and_actual_execution_must_match(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            def adb(command, *, stdout, stderr, timeout=None):
                self.assertEqual(command[:7], ["adb", "-s", "serial", "shell", "am", "instrument", "-w"])
                self.assertEqual(command[-1], RUNNER)
                self.assertEqual(stderr, subprocess.STDOUT)
                self.assertGreater(timeout, 0)
                stdout.write(report("testLaunch" if "log" in command else "testDifferent"))
                return subprocess.CompletedProcess(command, 0)
            with patch("native_tests.subprocess.run", adb), contextlib.redirect_stderr(io.StringIO()), self.assertRaisesRegex(ValueError, "test sets differ"):
                run("serial", directory, self.expected)
            self.assertEqual({p.name for p in directory.iterdir()}, {"discovery.txt", "execution.txt"})

    def test_adb_failure_stops_before_execution_and_retains_diagnostic(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            def adb(command, *, stdout, stderr, timeout=None):
                self.assertGreater(timeout, 0)
                stdout.write("device disconnected")
                return subprocess.CompletedProcess(command, 17)
            log = io.StringIO()
            with (patch("native_tests.subprocess.run", adb), contextlib.redirect_stderr(log),
                  self.assertRaises(subprocess.CalledProcessError)):
                run("serial", directory, self.expected)
            self.assertEqual(log.getvalue().strip(), "device disconnected")
            self.assertFalse((directory / "execution.txt").exists())

    def test_timeout_reports_exact_in_flight_test_case(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            expected = {("app.FrameworkTest", "testPass"), ("app.FrameworkTest", "testHung")}
            def adb(command, *, stdout=None, stderr=None, capture_output=False, text=False, errors=None, timeout=None):
                if capture_output:
                    if command[3:6] == ["shell", "dumpsys", "window"]:
                        return subprocess.CompletedProcess(command, 0, "  mCurrentFocus=Window{9 u0 org.llm4people.hermes/.MainActivity}\n", "")
                    if command[3:6] == ["shell", "dumpsys", "activity"]:
                        return subprocess.CompletedProcess(command, 0, "  topResumedActivity: ActivityRecord{1 u0 org.llm4people.hermes/.MainActivity}\n", "")
                    return subprocess.CompletedProcess(command, 0, "", "")
                if "log" in command:
                    stdout.write(
                        status("testPass", 1, 2) + status("testPass", 0, 2)
                        + status("testHung", 1, 2) + status("testHung", 0, 2)
                        + "OK (2 tests)\nINSTRUMENTATION_CODE: -1\n"
                    )
                    return subprocess.CompletedProcess(command, 0)
                stdout.write(status("testPass", 1, 2) + status("testPass", 0, 2) + status("testHung", 1, 2))
                stdout.flush()
                raise subprocess.TimeoutExpired(command, timeout)
            log = io.StringIO()
            with (patch("native_tests.subprocess.run", adb), contextlib.redirect_stderr(log),
                  self.assertRaisesRegex(ValueError, r"timed out.*completed 1/2.*app\.FrameworkTest#testHung")):
                run("serial", directory, expected)
            self.assertIn("app.FrameworkTest#testHung", log.getvalue())
            diag_file = directory / "timeout-diag.txt"
            self.assertTrue(diag_file.is_file())
            diag_text = diag_file.read_text()
            self.assertIn("window_focus: mCurrentFocus=Window{9 u0 org.llm4people.hermes/.MainActivity}", diag_text)
            self.assertIn("resumed_activity: topResumedActivity: ActivityRecord{1 u0 org.llm4people.hermes/.MainActivity}", diag_text)
            self.assertEqual(
                in_flight_cases(status("testPass", 1, 2) + status("testPass", 0, 2) + status("testHung", 1, 2)),
                (1, [("app.FrameworkTest", "testHung")]),
            )

    def test_timeout_tolerates_truncated_status_code_line_and_still_collects_diagnostics(self):
        truncated = (
            status("testPass", 1, 2) + status("testPass", 0, 2)
            + status("testHung", 1, 2) + "INSTRUMENTATION_STATUS_CODE: \n"
            + "INSTRUMENTATION_STATUS_CODE: -\n"
        )
        self.assertEqual(in_flight_cases(truncated), (1, [("app.FrameworkTest", "testHung")]))
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            expected = {("app.FrameworkTest", "testPass"), ("app.FrameworkTest", "testHung")}
            def adb(command, *, stdout=None, stderr=None, capture_output=False, text=False, errors=None, timeout=None):
                if capture_output:
                    if command[3:6] == ["shell", "dumpsys", "window"]:
                        return subprocess.CompletedProcess(command, 0, "  mCurrentFocus=Window{9 u0 org.llm4people.hermes/.MainActivity}\n", "")
                    return subprocess.CompletedProcess(command, 0, "", "")
                if "log" in command:
                    stdout.write(
                        status("testPass", 1, 2) + status("testPass", 0, 2)
                        + status("testHung", 1, 2) + status("testHung", 0, 2)
                        + "OK (2 tests)\nINSTRUMENTATION_CODE: -1\n"
                    )
                    return subprocess.CompletedProcess(command, 0)
                stdout.write(truncated)
                stdout.flush()
                raise subprocess.TimeoutExpired(command, timeout)
            with (patch("native_tests.subprocess.run", adb), contextlib.redirect_stderr(io.StringIO()),
                  self.assertRaisesRegex(ValueError, r"timed out.*completed 1/2.*app\.FrameworkTest#testHung")):
                run("serial", directory, expected)
            self.assertTrue((directory / "timeout-diag.txt").is_file())

    def test_shard_cases_partitions_completely_disjointly_and_balances_load(self):
        cases = {
            ("app.BigTest", f"test{i}") for i in range(6)
        } | {
            ("app.MediumTest", f"test{i}") for i in range(4)
        } | {
            ("app.SmallATest", "testOne"),
            ("app.SmallATest", "testTwo"),
            ("app.SmallBTest", "testOne"),
        }
        self.assertEqual(shard_cases(cases, ""), cases)
        self.assertEqual(shard_cases(cases, "0/1"), cases)

        shard0 = shard_cases(cases, "0/2")
        shard1 = shard_cases(cases, "1/2")
        self.assertEqual(shard0 | shard1, cases)
        self.assertEqual(shard0 & shard1, set())
        # LPT balancing puts Big(6)+SmallB(1)=7 vs Medium(4)+SmallA(2)=6
        self.assertLessEqual(abs(len(shard0) - len(shard1)), 1)
        # Never splits a class across shards
        self.assertEqual({c for c, _ in shard0} & {c for c, _ in shard1}, set())

    def test_shard_cases_rejects_malformed_out_of_range_or_empty_shards(self):
        cases = {("app.OnlyTest", "testOne")}
        for bad in ("2/2", "-1/2", "0/0", "1/0", "abc", "0/2/3", "1/2"):
            with self.subTest(spec=bad), self.assertRaises(ValueError):
                shard_cases(cases, bad)

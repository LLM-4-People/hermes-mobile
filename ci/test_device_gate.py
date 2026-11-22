"""Exercise the required shell gate's order, shared inputs and failure propagation."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


GATE = Path(__file__).with_name("device-check.sh").resolve()
FRAMEWORK = "org.llm4people.hermes.test/android.test.InstrumentationTestRunner"
TEST_CLASS = "org.llm4people.hermes.thread.CodeExcerptCapture"
ADB = ["adb", "-s", "emulator-fixture"]
INSTRUMENT = [*ADB, "shell", "am", "instrument", "-w", "-r", "-e", "class", TEST_CLASS]
APP_APK = "app/build/outputs/apk/debug/app-debug.apk"
TEST_APK = "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
DEX_XML = """<api><package name="org.llm4people.hermes.thread">
  <class name="CodeExcerptCapture" extends="android.test.AndroidTestCase" visibility="public">
    <method name="testConversationAndOpenedSource" visibility="public" return="void" static="false"/>
  </class>
</package></api>"""

RECORDER = """#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
with open(os.environ["GATE_COMMANDS"], "a") as stream:
    print(json.dumps([Path(sys.argv[0]).name, *sys.argv[1:]]), file=stream)
if sys.argv[1:] == ["get-serialno"]:
    print("emulator-fixture")
if Path(sys.argv[0]).name == "dexdump":
    print(Path(sys.argv[3]).read_text())
if sys.argv[3:6] == ["shell", "am", "instrument"]:
    discovery = sys.argv[-4:-1] == ["-e", "log", "true"]
    print(Path("discovery.txt" if discovery else "execution.txt").read_text(), end="")
    if not discovery:
        sys.exit(int(os.environ["EXECUTION_STATUS"]))
if os.environ.get("FAIL_VERIFIER") in sys.argv[1:]:
    print("journey failed: " + os.environ["FAIL_VERIFIER"], file=sys.stderr)
    sys.exit(31)
"""

# Framework protocol for one discovered and executed test on the installed test APK.
def successful_report():
    fields = (f"INSTRUMENTATION_STATUS: class={TEST_CLASS}\n"
              "INSTRUMENTATION_STATUS: id=InstrumentationTestRunner\n"
              "INSTRUMENTATION_STATUS: numtests=1\n"
              "INSTRUMENTATION_STATUS: test=testConversationAndOpenedSource\n")
    return (fields + "INSTRUMENTATION_STATUS_CODE: 1\n" + fields + "INSTRUMENTATION_STATUS_CODE: 0\n"
            "INSTRUMENTATION_RESULT: stream=\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n")


class DeviceGateTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.runner = self.root / "runner temp"
        self.trace = self.root / "calls.jsonl"
        self.trace.touch()
        ci = self.root / "ci"
        ci.mkdir()
        shutil.copyfile(GATE.with_name("native_tests.py"), ci / "native_tests.py")
        apk = self.root / TEST_APK
        apk.parent.mkdir(parents=True)
        apk.write_text(DEX_XML)
        for phase in ("discovery", "execution"):
            (self.root / f"{phase}.txt").write_text(successful_report())
        self.build_tools = self.root / "android build tools"
        for command in (self.root / "bin/adb", self.build_tools / "dexdump", self.runner / "hermes-env/bin/python"):
            command.parent.mkdir(parents=True, exist_ok=True)
            command.write_text(RECORDER)
            command.chmod(0o755)

    def gate(self, fail="", execution_status=0):
        env = {**os.environ, "RUNNER_TEMP": str(self.runner), "GATE_COMMANDS": str(self.trace),
               "ANDROID_BUILD_TOOLS": str(self.build_tools),
               "PATH": str(self.root / "bin") + os.pathsep + os.environ["PATH"],
               "FAIL_VERIFIER": fail, "EXECUTION_STATUS": str(execution_status)}
        result = subprocess.run(["bash", GATE], cwd=self.root, env=env, text=True, capture_output=True)
        return result, [json.loads(line) for line in self.trace.read_text().splitlines()]

    def test_all_journeys_use_one_fork_serial_and_installed_apk_pair(self):
        result, calls = self.gate()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(calls[:8], [
            ["adb", "get-serialno"],
            [*ADB, "install", "-r", "-t", APP_APK], [*ADB, "install", "-r", "-t", TEST_APK],
            ["dexdump", "-l", "xml", TEST_APK],
            [*INSTRUMENT, "-e", "log", "true", FRAMEWORK], [*INSTRUMENT, FRAMEWORK],
            [*ADB, "shell", "pm", "clear", "org.llm4people.hermes.test"],
            [*ADB, "shell", "pm", "clear", "org.llm4people.hermes"],
        ])
        for phase in ("discovery", "execution"):
            saved = self.runner / "android-instrumentation" / f"{phase}.txt"
            self.assertEqual(saved.read_text(), (self.root / f"{phase}.txt").read_text())
        verifiers = calls[8:]
        self.assertEqual([cmd[:2] for cmd in verifiers], [["python", "-B"]] * 3)
        self.assertEqual([cmd[2] for cmd in verifiers], [
            "verification/live_api.py", "verification/conversation_cards.py", "verification/thread_instruments.py"])
        for cmd in verifiers:
            self.assertEqual(cmd[cmd.index("--source") + 1], ".fixture/hermes")
            self.assertEqual(cmd[cmd.index("--serial") + 1], "emulator-fixture")
        self.assertIn("--require-tool-controls", verifiers[0])
        self.assertIn("verification/device/run.py", verifiers[0])
        for cmd in verifiers[1:]:
            index = cmd.index("--verify-apks")
            self.assertEqual(cmd[index + 1:index + 3], [APP_APK, TEST_APK])
        self.assertEqual([cmd[-1] for cmd in verifiers], [str(self.runner / subdir) for subdir in (
            "hermes-device", "hermes-conversation-cards/android", "hermes-thread-instrument")])

    def test_each_journey_failure_stops_the_gate_with_its_diagnostic(self):
        journeys = ["verification/live_api.py", "verification/conversation_cards.py",
                    "verification/thread_instruments.py"]
        for index, failed in enumerate(journeys):
            with self.subTest(journey=failed):
                self.trace.write_text("")
                result, calls = self.gate(fail=failed)
                self.assertEqual(result.returncode, 31)
                self.assertIn("journey failed: " + failed, result.stderr)
                self.assertEqual([cmd[2] for cmd in calls if cmd[0] == "python"], journeys[:index + 1])
                if failed == "verification/thread_instruments.py":
                    self.assertIn("partial screenshots in android-thread-instruments", result.stderr)

    def test_incomplete_discovery_stops_before_execution_even_if_both_reports_match(self):
        apk = self.root / TEST_APK
        extra = '<method name="testOther" visibility="public" return="void" static="false"/>'
        apk.write_text(DEX_XML.replace("</class>", extra + "</class>"))
        result, calls = self.gate()
        self.assertEqual(result.returncode, 1)
        self.assertIn("Packaged and discovery test sets differ", result.stderr)
        self.assertIn("testOther", result.stderr)
        self.assertEqual(calls[-1], [*INSTRUMENT, "-e", "log", "true", FRAMEWORK])
        self.assertFalse((self.runner / "android-instrumentation/execution.txt").exists())

    def test_native_failures_stop_before_clearing_evidence_or_running_journeys(self):
        failed_case = successful_report().replace("INSTRUMENTATION_STATUS_CODE: 0", "INSTRUMENTATION_STATUS_CODE: -2")
        for output, adb_status, expected_status, diagnostic in (
                ("device disconnected", 17, 17, "device disconnected"),
                (failed_case, 0, 1, "unexpected instrumentation status -2")):
            with self.subTest(diagnostic=diagnostic):
                self.trace.write_text("")
                (self.root / "execution.txt").write_text(output)
                result, calls = self.gate(execution_status=adb_status)
                self.assertEqual(result.returncode, expected_status)
                self.assertIn(diagnostic, result.stderr)
                self.assertEqual(calls[-1], [*INSTRUMENT, FRAMEWORK])
                self.assertFalse(any("clear" in cmd or cmd[0] == "python" for cmd in calls))
                report = self.runner / "android-instrumentation/execution.txt"
                self.assertEqual(report.read_text(), output)

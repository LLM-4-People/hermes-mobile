"""Exercise the required shell gate's order, shared inputs and failure propagation."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


GATE = Path(__file__).with_name("device-check.sh").resolve()
RECORDER = """#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
with open(os.environ["GATE_COMMANDS"], "a") as stream:
    print(json.dumps([Path(sys.argv[0]).name, *sys.argv[1:]]), file=stream)
if sys.argv[1:] == ["get-serialno"]:
    print("emulator-fixture")
if Path(sys.argv[0]).name == "gradlew" and os.environ.get("FAIL_GRADLE") == "1":
    print("Native Android tests failed", file=sys.stderr)
    sys.exit(17)
if os.environ.get("FAIL_VERIFIER") in sys.argv[1:]:
    print("instrument-wide failed to show the pinned form", file=sys.stderr)
    sys.exit(31)
"""


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
        shutil.copyfile(GATE.with_name("gradle_check.py"), ci / "gradle_check.py")
        for command in (self.root / "gradlew", self.root / "bin/adb", self.runner / "hermes-env/bin/python"):
            command.parent.mkdir(parents=True, exist_ok=True)
            command.write_text(RECORDER)
            command.chmod(0o755)

    def gate(self, fail="", fail_gradle=False):
        env = {**os.environ, "RUNNER_TEMP": str(self.runner), "GATE_COMMANDS": str(self.trace),
               "PATH": str(self.root / "bin") + os.pathsep + os.environ["PATH"],
               "FAIL_VERIFIER": fail, "FAIL_GRADLE": "1" if fail_gradle else "0"}
        result = subprocess.run(["bash", GATE], cwd=self.root, env=env, text=True, capture_output=True)
        return result, [json.loads(line) for line in self.trace.read_text().splitlines()]

    def test_all_journeys_use_one_fork_serial_and_installed_apk_pair(self):
        result, calls = self.gate()
        self.assertEqual(result.returncode, 0, result.stderr)
        builds = [cmd for cmd in calls if cmd[0] == "gradlew"]
        self.assertEqual(len(builds), 1)
        self.assertIn(":app:connectedDebugAndroidTest", builds[0])
        installs = [cmd for cmd in calls if cmd[0] == "adb" and "install" in cmd]
        self.assertEqual(len(installs), 2)
        app, tests = [cmd[-1] for cmd in installs]
        for cmd in installs:
            self.assertEqual(cmd[1:-1], ["-s", "emulator-fixture", "install", "-r", "-t"])
        verifiers = [cmd for cmd in calls if cmd[0] == "python"]
        self.assertEqual([cmd[2] for cmd in verifiers], [
            "verification/live_api.py", "verification/conversation_cards.py", "verification/thread_instruments.py"])
        for cmd in verifiers:
            self.assertEqual(cmd[cmd.index("--source") + 1], ".fixture/hermes")
            self.assertEqual(cmd[cmd.index("--serial") + 1], "emulator-fixture")
        self.assertIn("--require-tool-controls", verifiers[0])
        self.assertIn("verification/device/run.py", verifiers[0])
        for cmd in verifiers[1:]:
            index = cmd.index("--verify-apks")
            self.assertEqual(cmd[index + 1:index + 3], [app, tests])
        self.assertEqual([cmd[-1] for cmd in verifiers], [str(self.runner / subdir) for subdir in (
            "hermes-device", "hermes-conversation-cards/android", "hermes-thread-instrument")])

    def test_thread_instrument_failure_fails_the_gate_with_its_diagnostic(self):
        result, _ = self.gate(fail="verification/thread_instruments.py")
        self.assertEqual(result.returncode, 31)
        self.assertIn("instrument-wide failed", result.stderr)
        self.assertIn("partial screenshots in android-thread-instruments", result.stderr)

    def test_native_test_failure_stops_before_device_setup(self):
        result, calls = self.gate(fail_gradle=True)
        self.assertEqual(result.returncode, 17)
        self.assertIn("Native Android tests failed", result.stdout)
        self.assertEqual(calls, [["gradlew", "--no-daemon", "--max-workers=2", ":app:connectedDebugAndroidTest"]])

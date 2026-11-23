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
HANDOFF = ["adb", "-s", "emulator-handoffs"]
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
name = Path(sys.argv[0]).name
if name == "curl":
    Path(sys.argv[-1]).write_bytes(b"calendar fixture")
if name == "sha256sum":
    Path("calendar-check.txt").write_text(sys.stdin.read())
    sys.exit(int(os.environ.get("CALENDAR_CHECK_STATUS", "0")))
if sys.argv[3:7] == ["shell", "pm", "list", "packages"]:
    print("package:com.google.android.calendar")
if "resolve-activity" in sys.argv:
    if "android.intent.action.INSERT" in sys.argv:
        # Android's package-manager shell resolves only an explicit MIME type.
        print("org.fossify.calendar/.EventActivity" if sys.argv[-2:] == ["-t", "vnd.android.cursor.dir/event"]
              else "No activity found")
    else:
        print(os.environ.get("MAP_COMPONENT", "com.google.android.apps.maps/.MapsActivity"))
if sys.argv[3:] == ["shell", "dumpsys", "connectivity"]:
    print("Active default network: none")
if sys.argv[3:] == ["shell", "settings", "get", "global", "airplane_mode_on"]:
    print("1")
if sys.argv[1:] == ["get-serialno"]:
    print(os.environ.get("FIXTURE_ADB_SERIAL", "emulator-fixture"))
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
if "verification.phone_actions" in sys.argv:
    directory = Path(sys.argv[sys.argv.index("--output") + 1]).parent
    for suffix in (".json", "-conversation.json", "-share_text.png", "-calendar_event.png", "-map_search.png"):
        if suffix != os.environ.get("MISSING_EVIDENCE"):
            (directory / ("phone-actions" + suffix)).write_bytes(b"recorded fixture")
if "verification.nearby_restaurants" in sys.argv:
    directory = Path(sys.argv[sys.argv.index("--output") + 1])
    directory.mkdir(parents=True, exist_ok=True)
    for filename in ("nearby.json", "guide.png", "comparison.png", "detail.png"):
        if filename != os.environ.get("MISSING_NEARBY_EVIDENCE"):
            (directory / filename).write_bytes(b"recorded fixture")
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
        for name in ("native_tests.py", "phone-action-check.sh", "nearby-device-check.sh"):
            shutil.copyfile(GATE.with_name(name), ci / name)
        apk = self.root / TEST_APK
        apk.parent.mkdir(parents=True)
        apk.write_text(DEX_XML)
        for phase in ("discovery", "execution"):
            (self.root / f"{phase}.txt").write_text(successful_report())
        self.build_tools = self.root / "android build tools"
        for command in (self.root / "bin/adb", self.root / "bin/curl", self.root / "bin/sha256sum",
                        self.build_tools / "dexdump", self.runner / "hermes-env/bin/python"):
            command.parent.mkdir(parents=True, exist_ok=True)
            command.write_text(RECORDER)
            command.chmod(0o755)

    def gate(self, fail="", execution_status=0, suite=None, include_handoff=True, **changes):
        env = {**os.environ, "RUNNER_TEMP": str(self.runner), "GATE_COMMANDS": str(self.trace),
               "ANDROID_BUILD_TOOLS": str(self.build_tools),
               "PATH": str(self.root / "bin") + os.pathsep + os.environ["PATH"],
               "FAIL_VERIFIER": fail, "EXECUTION_STATUS": str(execution_status), **changes}
        if suite is not None:
            env["HERMES_DEVICE_SUITE"] = suite
        result = subprocess.run(["bash", GATE], cwd=self.root, env=env, text=True, capture_output=True)
        if result.returncode == 0 and include_handoff:
            env["FIXTURE_ADB_SERIAL"] = HANDOFF[2]
            result = subprocess.run(["bash", GATE.with_name("phone-action-check.sh"), APP_APK],
                                    cwd=self.root, env=env, text=True, capture_output=True)
        return result, [json.loads(line) for line in self.trace.read_text().splitlines()]

    def handoff_gate(self, **changes):
        env = {**os.environ, "RUNNER_TEMP": str(self.runner), "GATE_COMMANDS": str(self.trace),
               "ANDROID_BUILD_TOOLS": str(self.build_tools),
               "PATH": str(self.root / "bin") + os.pathsep + os.environ["PATH"],
               "FIXTURE_ADB_SERIAL": HANDOFF[2], **changes}
        result = subprocess.run(["bash", GATE.with_name("phone-action-check.sh"), APP_APK],
                                cwd=self.root, env=env, text=True, capture_output=True)
        return result, [json.loads(line) for line in self.trace.read_text().splitlines()]

    def test_workflow_requires_parallel_fresh_emulators_and_concurrency(self):
        workflow = (GATE.parents[1] / ".github/workflows/checks.yml").read_text()
        self.assertIn("concurrency:", workflow)
        self.assertIn("cancel-in-progress: ${{ github.ref != 'refs/heads/main' && github.event_name != 'workflow_call' }}", workflow)
        device_section = workflow.split("\n  device:\n", 1)[1].split("\n  aosp:", 1)[0]
        self.assertIn("HERMES_DEVICE_SUITE: core", device_section)
        self.assertIn("HERMES_DEVICE_SUITE: journeys", device_section)
        self.assertIn("avd-name: hermes-journeys", device_section)
        self.assertIn("\n  device-journeys:\n", device_section)
        self.assertIn("\n  phone-handoffs:\n", device_section)
        self.assertIn("\n  nearby-guide:\n", device_section)

        handoffs = workflow.split("\n  phone-handoffs:\n", 1)[1].split("\n  nearby-guide:", 1)[0]
        self.assertIn("needs: package", handoffs)
        handoff_step = handoffs.split("      - name: Verify Android phone handoffs on a fresh emulator", 1)[1].split("      - name:", 1)[0]
        self.assertIn("ReactiveCircus/android-emulator-runner@", handoff_step)
        self.assertIn("target: google_apis", handoff_step)
        self.assertIn("avd-name: hermes-handoffs", handoff_step)
        self.assertIn("force-avd-creation: true", handoff_step)
        self.assertIn("script: bash ci/phone-action-check.sh " + APP_APK, handoff_step)
        self.assertNotIn("if:", handoff_step)
        self.assertNotIn("continue-on-error", handoff_step)

    def test_device_check_shards_cover_full_gate_without_gaps(self):
        core_res, core_calls = self.gate(suite="core", include_handoff=False)
        self.assertEqual(core_res.returncode, 0, core_res.stderr)
        core_verifiers = [cmd[2] for cmd in core_calls if cmd[0] == "python"]
        self.assertIn(["dexdump", "-l", "xml", TEST_APK], core_calls)
        self.assertEqual(core_verifiers, ["verification/live_api.py", "verification/conversation_cards.py"])

        self.trace.write_text("")
        journeys_res, journeys_calls = self.gate(suite="journeys", include_handoff=False)
        self.assertEqual(journeys_res.returncode, 0, journeys_res.stderr)
        journeys_verifiers = [cmd[2] for cmd in journeys_calls if cmd[0] == "python"]
        self.assertEqual(journeys_verifiers, [
            "verification/thread_instruments.py", "verification/discussion_photo.py", "verification/discussion_skills.py"])

        self.trace.write_text("")
        bad_res, _ = self.gate(suite="bogus", include_handoff=False)
        self.assertEqual(bad_res.returncode, 2)
        self.assertIn("Unknown HERMES_DEVICE_SUITE", bad_res.stderr)

    def test_journeys_use_the_packaged_apks_and_their_own_emulator(self):
        result, calls = self.gate()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(calls[:9], [
            ["adb", "get-serialno"],
            [*ADB, "install", "-r", "-t", APP_APK], [*ADB, "install", "-r", "-t", TEST_APK],
            ["dexdump", "-l", "xml", TEST_APK],
            [*INSTRUMENT, "-e", "log", "true", FRAMEWORK], [*INSTRUMENT, FRAMEWORK],
            [*ADB, "shell", "pm", "clear", "org.llm4people.hermes.test"],
            [*ADB, "shell", "pm", "clear", "org.llm4people.hermes"],
            [*ADB, "shell", "cmd", "locale", "set-app-locales", "org.llm4people.hermes", "--locales", "fr-FR"],
        ])
        for phase in ("discovery", "execution"):
            saved = self.runner / "android-instrumentation" / f"{phase}.txt"
            self.assertEqual(saved.read_text(), (self.root / f"{phase}.txt").read_text())
        verifiers = [cmd for cmd in calls[9:] if cmd[0] == "python"]
        self.assertEqual([cmd[:2] for cmd in verifiers], [["python", "-B"]] * 6)
        self.assertEqual([cmd[2] for cmd in verifiers], [
            "verification/live_api.py", "verification/conversation_cards.py", "verification/thread_instruments.py",
            "verification/discussion_photo.py", "verification/discussion_skills.py", "-m"])
        for cmd in verifiers:
            self.assertEqual(cmd[cmd.index("--source") + 1], ".fixture/hermes")
            self.assertEqual(cmd[cmd.index("--serial") + 1], HANDOFF[2] if cmd is verifiers[-1] else ADB[2])
        self.assertIn("--require-tool-controls", verifiers[0])
        self.assertIn("verification/device/run.py", verifiers[0])
        for cmd in verifiers[1:-1]:
            index = cmd.index("--verify-apks")
            self.assertEqual(cmd[index + 1:index + 3], [APP_APK, TEST_APK])
        self.assertEqual([cmd[-1] for cmd in verifiers], [str(self.runner / subdir) for subdir in (
            "hermes-device", "hermes-conversation-cards/android", "hermes-thread-instrument",
            "hermes-discussion-photo", "hermes-discussion-skills", "hermes-phone-actions/phone-actions.json")])
        self.assertEqual(verifiers[-1][3], "verification.phone_actions")
        self.assertEqual(verifiers[-1][verifiers[-1].index("--apk") + 1], APP_APK)
        self.assertIn("--inbound", verifiers[-1])
        self.assertIn([*HANDOFF, "install", "-r", "-t", APP_APK], calls)
        self.assertEqual(calls.count(["adb", "get-serialno"]), 2)
        self.assertIn([*HANDOFF, "shell", "cmd", "connectivity", "airplane-mode", "enable"], calls)
        self.assertIn([*HANDOFF, "shell", "svc", "wifi", "disable"], calls)
        self.assertIn([*HANDOFF, "shell", "pm", "disable-user", "--user", "0", "com.google.android.calendar"], calls)
        calendar = str(self.runner / "calendar-25-foss-release.apk")
        self.assertEqual((self.root / "calendar-check.txt").read_text().split(maxsplit=1), [
            "2825816d0a972bd85eb289e556221293fadecfd3179a4a2531bde6f89c6f0238", calendar + "\n"])
        self.assertLess(calls.index(["sha256sum", "--check"]), calls.index([*HANDOFF, "install", "-r", calendar]))

    def test_each_journey_failure_stops_the_gate_with_its_diagnostic(self):
        journeys = ["verification/live_api.py", "verification/conversation_cards.py",
                    "verification/thread_instruments.py", "verification/discussion_photo.py",
                    "verification/discussion_skills.py", "verification.phone_actions"]
        for index, failed in enumerate(journeys):
            with self.subTest(journey=failed):
                self.trace.write_text("")
                result, calls = self.gate(fail=failed)
                self.assertEqual(result.returncode, 31)
                self.assertIn("journey failed: " + failed, result.stderr)
                self.assertEqual([cmd[3] if cmd[2] == "-m" else cmd[2] for cmd in calls if cmd[0] == "python"],
                                 journeys[:index + 1])
                if failed == "verification/thread_instruments.py":
                    self.assertIn("partial screenshots in android-thread-instruments", result.stderr)

    def test_missing_expected_evidence_fails_even_when_verifier_exits_zero(self):
        result, calls = self.handoff_gate(MISSING_EVIDENCE="-map_search.png")
        self.assertEqual(result.returncode, 1)
        self.assertIn("Android handoff evidence missing: phone-actions-map_search.png", result.stderr)
        self.assertIn("verification.phone_actions", calls[-1])

    def test_unverified_calendar_stops_before_installation(self):
        result, calls = self.handoff_gate(CALENDAR_CHECK_STATUS="1")
        self.assertEqual(result.returncode, 1)
        self.assertEqual(calls[-1], ["sha256sum", "--check"])

    def test_unexpected_map_resolver_stops_before_handoff_proof(self):
        result, calls = self.handoff_gate(MAP_COMPONENT="android/.ResolverActivity")
        self.assertEqual(result.returncode, 1)
        self.assertIn("com.google.android.apps.maps", result.stderr)
        self.assertNotIn("verification.phone_actions", [arg for cmd in calls for arg in cmd])

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

    def nearby_gate(self, **changes):
        env = {**os.environ, "RUNNER_TEMP": str(self.runner), "GATE_COMMANDS": str(self.trace),
               "ANDROID_BUILD_TOOLS": str(self.build_tools),
               "PATH": str(self.root / "bin") + os.pathsep + os.environ["PATH"],
               "FIXTURE_ADB_SERIAL": "emulator-nearby", **changes}
        result = subprocess.run(["bash", GATE.with_name("nearby-device-check.sh")],
                                cwd=self.root, env=env, text=True, capture_output=True)
        return result, [json.loads(line) for line in self.trace.read_text().splitlines()]

    def test_workflow_requires_a_fresh_third_emulator_for_nearby_restaurants(self):
        workflow = (GATE.parents[1] / ".github/workflows/checks.yml").read_text()
        job = workflow.split("\n  nearby-guide:\n", 1)[1].split("\n  aosp:", 1)[0]
        self.assertIn("needs: package", job)
        _, nearby = job.split("      - name: Verify Android foreground location and restaurant guide on a fresh emulator", 1)
        nearby_step, rest = nearby.split("      - name:", 1)
        self.assertIn("ReactiveCircus/android-emulator-runner@", nearby_step)
        self.assertIn("target: google_apis", nearby_step)
        self.assertIn("avd-name: hermes-nearby", nearby_step)
        self.assertIn("force-avd-creation: true", nearby_step)
        self.assertIn("script: bash ci/nearby-device-check.sh", nearby_step)
        self.assertNotIn("if:", nearby_step)
        self.assertNotIn("continue-on-error", nearby_step)
        self.assertIn("name: android-nearby-restaurants", rest)
        self.assertIn("${{ runner.temp }}/hermes-nearby/nearby.json", rest)

    def test_nearby_gate_installs_verifies_offline_and_requires_all_evidence(self):
        result, calls = self.nearby_gate()
        self.assertEqual(result.returncode, 0, result.stderr)
        device = ["adb", "-s", "emulator-nearby"]
        self.assertIn([*device, "install", "-r", "-t", APP_APK], calls)
        self.assertIn([*device, "install", "-r", "-t", TEST_APK], calls)
        self.assertIn([*device, "shell", "cmd", "connectivity", "airplane-mode", "enable"], calls)
        self.assertIn([*device, "shell", "svc", "wifi", "disable"], calls)
        self.assertIn([*device, "shell", "svc", "data", "disable"], calls)
        self.assertGreaterEqual(calls.count([*device, "shell", "dumpsys", "connectivity"]), 2)
        self.assertGreaterEqual(calls.count([*device, "shell", "settings", "get", "global", "airplane_mode_on"]), 2)
        verifier = next(cmd for cmd in calls if cmd[0] == "python")
        self.assertEqual(verifier[:4], ["python", "-B", "-m", "verification.nearby_restaurants"])
        self.assertEqual(verifier[verifier.index("--verify-apks") + 1:verifier.index("--verify-apks") + 3], [APP_APK, TEST_APK])
        self.assertEqual(verifier[verifier.index("--output") + 1], str(self.runner / "hermes-nearby"))

    def test_nearby_gate_fails_on_missing_evidence_or_unexpected_map_resolver(self):
        result, calls = self.nearby_gate(MISSING_NEARBY_EVIDENCE="comparison.png")
        self.assertEqual(result.returncode, 1)
        self.assertIn("Missing Android nearby evidence: comparison.png", result.stderr)
        self.assertIn("verification.nearby_restaurants", calls[-1])

        self.trace.write_text("")
        result, calls = self.nearby_gate(MAP_COMPONENT="android/.ResolverActivity")
        self.assertEqual(result.returncode, 1)
        self.assertIn("com.google.android.apps.maps", result.stderr)
        self.assertNotIn("verification.nearby_restaurants", [arg for cmd in calls for arg in cmd])

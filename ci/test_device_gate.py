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
    seq_env = os.environ.get("FIXTURE_CONNECTIVITY_SEQ")
    if seq_env:
        counter = Path(os.environ["GATE_COMMANDS"]).with_suffix(".conn")
        idx = int(counter.read_text()) if counter.exists() else 0
        items = seq_env.split(",")
        counter.write_text(str(idx + 1))
        print(items[min(idx, len(items) - 1)])
    else:
        print(os.environ.get("FIXTURE_CONNECTIVITY", "Active default network: none"))
if sys.argv[3:] == ["shell", "settings", "get", "global", "airplane_mode_on"]:
    print(os.environ.get("FIXTURE_AIRPLANE_ON", "1"))
if sys.argv[3:] == ["shell", "settings", "get", "global", "wifi_on"]:
    print(os.environ.get("FIXTURE_WIFI_ON", "0"))
if sys.argv[3:] == ["shell", "getprop", "sys.boot_completed"]:
    seq_env = os.environ.get("FIXTURE_BOOT_SEQ")
    if seq_env:
        counter = Path(os.environ["GATE_COMMANDS"]).with_suffix(".boot")
        idx = int(counter.read_text()) if counter.exists() else 0
        items = seq_env.split(",")
        counter.write_text(str(idx + 1))
        print(items[min(idx, len(items) - 1)])
    else:
        print(os.environ.get("FIXTURE_BOOT_COMPLETED", "1"))
if sys.argv[3:] == ["shell", "pm", "path", "android"]:
    print(os.environ.get("FIXTURE_PM_ANDROID", "package:/system/framework/framework-res.apk"))
if sys.argv[3:] == ["shell", "dumpsys", "window"]:
    print(os.environ.get("FIXTURE_WINDOW_DUMP", "mCurrentFocus=Window{1 u0 com.android.launcher/.Launcher}"))
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
        for name in ("native_tests.py", "emulator-offline.sh", "emulator-ready.sh", "emulator-sdk.sh",
                     "phone-action-check.sh", "nearby-device-check.sh"):
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
               "EMULATOR_READY_POLL_SECONDS": "0",
               "EMULATOR_OFFLINE_POLL_SECONDS": "0",
               "EMULATOR_OFFLINE_REQUIRED_HITS": "2",
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
               "EMULATOR_READY_POLL_SECONDS": "0",
               "EMULATOR_OFFLINE_POLL_SECONDS": "0",
               "EMULATOR_OFFLINE_REQUIRED_HITS": "2",
               "FIXTURE_ADB_SERIAL": HANDOFF[2], **changes}
        result = subprocess.run(["bash", GATE.with_name("phone-action-check.sh"), APP_APK],
                                cwd=self.root, env=env, text=True, capture_output=True)
        return result, [json.loads(line) for line in self.trace.read_text().splitlines()]

    def test_workflow_requires_parallel_fresh_emulators_and_concurrency(self):
        workflow = (GATE.parents[1] / ".github/workflows/checks.yml").read_text()
        self.assertIn("concurrency:", workflow)
        self.assertIn(
            "cancel-in-progress: ${{ github.ref != 'refs/heads/main' && github.event_name != 'workflow_call' && github.event_name != 'merge_group' }}",
            workflow,
        )
        self.assertIn("\n  changes:\n", workflow)
        self.assertIn("\n  pages-check:\n", workflow)
        self.assertIn("\n  package-debug:\n", workflow)
        self.assertIn("\n  package:\n", workflow)
        device_section = workflow.split("\n  device:\n", 1)[1].split("\n  aosp:", 1)[0]
        self.assertIn("HERMES_DEVICE_SUITE: native", device_section)
        self.assertIn("HERMES_NATIVE_SHARD: 0/2", device_section)
        self.assertIn("HERMES_NATIVE_SHARD: 1/2", device_section)
        self.assertIn("HERMES_DEVICE_SUITE: live-core", device_section)
        self.assertIn("HERMES_DEVICE_SUITE: journeys", device_section)
        self.assertIn("uses: ./.github/actions/avd-cache", device_section)
        self.assertIn("\n  device-native-1:\n", device_section)
        self.assertIn("\n  device-live-core:\n", device_section)
        self.assertIn("\n  device-journeys:\n", device_section)
        self.assertIn("\n  phone-handoffs:\n", device_section)
        self.assertIn("\n  nearby-guide:\n", device_section)

        handoffs = workflow.split("\n  phone-handoffs:\n", 1)[1].split("\n  nearby-guide:", 1)[0]
        self.assertIn("needs: [changes, package-debug]", handoffs)
        handoff_step = handoffs.split("      - name: Verify Android phone handoffs on a fresh emulator", 1)[1].split("      - name:", 1)[0]
        self.assertIn("ReactiveCircus/android-emulator-runner@", handoff_step)
        self.assertIn("target: google_apis", handoff_step)
        self.assertIn("avd-name: hermes-ci", handoff_step)
        self.assertIn("-no-snapshot-save", handoff_step)
        self.assertIn("script: bash ci/phone-action-check.sh " + APP_APK, handoff_step)
        self.assertNotIn("if:", handoff_step)
        self.assertNotIn("continue-on-error", handoff_step)

    def test_device_check_shards_cover_full_gate_without_gaps(self):
        native_res, native_calls = self.gate(suite="native", include_handoff=False, HERMES_NATIVE_SHARD="0/1")
        self.assertEqual(native_res.returncode, 0, native_res.stderr)
        self.assertIn(["dexdump", "-l", "xml", TEST_APK], native_calls)
        self.assertEqual([cmd for cmd in native_calls if cmd[0] == "python"], [])

        self.trace.write_text("")
        live_res, live_calls = self.gate(suite="live-core", include_handoff=False)
        self.assertEqual(live_res.returncode, 0, live_res.stderr)
        self.assertNotIn(["dexdump", "-l", "xml", TEST_APK], live_calls)
        self.assertEqual([cmd[2] for cmd in live_calls if cmd[0] == "python"],
                         ["verification/live_api.py", "verification/conversation_cards.py"])

        self.trace.write_text("")
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
        install_idx = calls.index([*ADB, "install", "-r", "-t", APP_APK])
        self.assertEqual(calls[0], ["adb", "get-serialno"])
        self.assertIn([*ADB, "shell", "getprop", "sys.boot_completed"], calls[:install_idx])
        self.assertIn([*ADB, "shell", "wm", "dismiss-keyguard"], calls[:install_idx])
        self.assertEqual(calls[install_idx:install_idx + 7], [
            [*ADB, "install", "-r", "-t", APP_APK], [*ADB, "install", "-r", "-t", TEST_APK],
            ["dexdump", "-l", "xml", TEST_APK],
            [*INSTRUMENT, "-e", "log", "true", FRAMEWORK], [*INSTRUMENT, FRAMEWORK],
            [*ADB, "shell", "pm", "clear", "org.llm4people.hermes.test"],
            [*ADB, "shell", "pm", "clear", "org.llm4people.hermes"],
        ])
        self.assertFalse(any("set-app-locales" in cmd for cmd in calls),
                         "Device gates must not force a test locale via cmd locale set-app-locales")
        for phase in ("discovery", "execution"):
            saved = self.runner / "android-instrumentation" / f"{phase}.txt"
            self.assertEqual(saved.read_text(), (self.root / f"{phase}.txt").read_text())
        verifiers = [cmd for cmd in calls[install_idx + 7:] if cmd[0] == "python"]
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
               "EMULATOR_READY_POLL_SECONDS": "0",
               "EMULATOR_OFFLINE_POLL_SECONDS": "0",
               "EMULATOR_OFFLINE_REQUIRED_HITS": "2",
               "FIXTURE_ADB_SERIAL": "emulator-nearby", **changes}
        result = subprocess.run(["bash", GATE.with_name("nearby-device-check.sh")],
                                cwd=self.root, env=env, text=True, capture_output=True)
        return result, [json.loads(line) for line in self.trace.read_text().splitlines()]

    def test_workflow_requires_a_fresh_third_emulator_for_nearby_restaurants(self):
        workflow = (GATE.parents[1] / ".github/workflows/checks.yml").read_text()
        job = workflow.split("\n  nearby-guide:\n", 1)[1].split("\n  aosp:", 1)[0]
        self.assertIn("needs: [changes, package-debug]", job)
        self.assertIn("uses: ./.github/actions/avd-cache", job)
        _, nearby = job.split("      - name: Verify Android foreground location and restaurant guide on a fresh emulator", 1)
        nearby_step, rest = nearby.split("      - name:", 1)
        self.assertIn("ReactiveCircus/android-emulator-runner@", nearby_step)
        self.assertIn("target: google_apis", nearby_step)
        self.assertIn("avd-name: hermes-ci", nearby_step)
        self.assertIn("-no-snapshot-load -no-snapshot-save", nearby_step)
        self.assertIn("disable-spellchecker: true", nearby_step)
        self.assertIn("script: bash ci/nearby-device-check.sh", nearby_step)
        self.assertNotIn("if:", nearby_step)
        self.assertNotIn("continue-on-error", nearby_step)
        self.assertIn("name: android-nearby-restaurants", rest)
        self.assertIn("${{ runner.temp }}/hermes-nearby/nearby.json", rest)

        # Every emulator job in checks.yml must boot snapshot-free to avoid cross-VM Vulkan/snapshot corruption.
        self.assertEqual(workflow.count("-no-snapshot-load -no-snapshot-save"), 6)
        self.assertEqual(workflow.count("disable-spellchecker: true"), 6)

        avd_action = (GATE.parents[1] / ".github/actions/avd-cache/action.yml").read_text()
        self.assertIn("bash ci/emulator-sdk.sh", avd_action)
        self.assertIn("avd-v2-", avd_action)
        self.assertIn("rm -rf ~/.android/avd/*.avd/snapshots", avd_action)
        self.assertIn("actions/cache/restore@", avd_action)
        self.assertIn("actions/cache/save@", avd_action)
        self.assertLess(avd_action.index("Warm pristine AVD disk image"), avd_action.index("actions/cache/save@"))

        unified_push = (GATE.parents[1] / ".github/workflows/unified-push.yml").read_text()
        self.assertIn("bash ci/emulator-sdk.sh emulator platform-tools", unified_push)
        self.assertIn('"platforms;android-${{ steps.image.outputs.sdk }}"', unified_push)
        self.assertIn(
            '"system-images;android-${{ steps.image.outputs.sdk }};${{ steps.image.outputs.target }};${{ steps.image.outputs.arch }}"',
            unified_push,
        )
        self.assertIn("disable-spellchecker: true", unified_push)

        labeler = (GATE.parents[1] / ".github/workflows/pr-labeler.yml").read_text()
        self.assertIn("issues: write", labeler)
        self.assertIn("pull-requests: write", labeler)

    def test_validate_job_builds_stripped_actionlint_with_ephemeral_go_caches(self):
        workflow = (GATE.parents[1] / ".github/workflows/checks.yml").read_text()
        validate = workflow.split("\n  validate:\n", 1)[1].split("\n  pages-check:\n", 1)[0]
        self.assertIn("key: actionlint-${{ runner.os }}-v1.7.12-stripped", validate)
        self.assertIn("CGO_ENABLED=0", validate)
        self.assertIn("-trimpath", validate)
        self.assertIn('-ldflags="-s -w"', validate)
        self.assertIn('GOCACHE="$go_tmp/build"', validate)
        self.assertIn('GOMODCACHE="$go_tmp/mod"', validate)
        self.assertIn('GOTMPDIR="$go_tmp/tmp"', validate)
        self.assertIn('trap \'chmod -R u+w "$go_tmp" 2>/dev/null || true; rm -rf "$go_tmp"\' EXIT', validate)

    def test_nearby_gate_installs_verifies_offline_and_requires_all_evidence(self):
        result, calls = self.nearby_gate()
        self.assertEqual(result.returncode, 0, result.stderr)
        device = ["adb", "-s", "emulator-nearby"]
        self.assertIn([*device, "install", "-r", "-t", APP_APK], calls)
        self.assertIn([*device, "install", "-r", "-t", TEST_APK], calls)
        self.assertIn([*device, "shell", "cmd", "connectivity", "airplane-mode", "enable"], calls)
        self.assertIn([*device, "shell", "svc", "wifi", "disable"], calls)
        self.assertIn([*device, "shell", "svc", "data", "disable"], calls)
        self.assertIn([*device, "shell", "settings", "put", "global", "mobile_data", "0"], calls)
        # Early disable starts settling before resolve-activity runs.
        resolve_idx = next(i for i, cmd in enumerate(calls) if "resolve-activity" in cmd)
        self.assertLess(calls.index([*device, "shell", "cmd", "connectivity", "airplane-mode", "enable"]), resolve_idx)
        self.assertGreaterEqual(calls.count([*device, "shell", "dumpsys", "connectivity"]), 2)
        self.assertGreaterEqual(calls.count([*device, "shell", "settings", "get", "global", "airplane_mode_on"]), 2)
        self.assertGreaterEqual(calls.count([*device, "shell", "settings", "get", "global", "wifi_on"]), 2)
        verifier = next(cmd for cmd in calls if cmd[0] == "python")
        self.assertEqual(verifier[:4], ["python", "-B", "-m", "verification.nearby_restaurants"])
        self.assertEqual(verifier[verifier.index("--verify-apks") + 1:verifier.index("--verify-apks") + 3], [APP_APK, TEST_APK])
        self.assertEqual(verifier[verifier.index("--output") + 1], str(self.runner / "hermes-nearby"))

    def test_offline_helper_reasserts_on_radio_flap_and_requires_full_default_stability_window(self):
        # Simulate boot-time radio briefly coming up after 2 offline polls, then staying offline for 6 consecutive polls.
        seq = ",".join([
            "Active default network: none",
            "Active default network: none",
            "Active default network: 100",
            *["Active default network: none"] * 6,
        ])
        conn_counter = self.trace.with_suffix(".conn")
        if conn_counter.exists():
            conn_counter.unlink()
        env = {
            **os.environ,
            "RUNNER_TEMP": str(self.runner),
            "GATE_COMMANDS": str(self.trace),
            "ANDROID_BUILD_TOOLS": str(self.build_tools),
            "PATH": str(self.root / "bin") + os.pathsep + os.environ["PATH"],
            "EMULATOR_READY_POLL_SECONDS": "0",
            "EMULATOR_OFFLINE_POLL_SECONDS": "0",
            "FIXTURE_ADB_SERIAL": "emulator-nearby",
            "FIXTURE_CONNECTIVITY_SEQ": seq,
        }
        # Deliberately omit EMULATOR_OFFLINE_REQUIRED_HITS to verify default 6-hit window.
        env.pop("EMULATOR_OFFLINE_REQUIRED_HITS", None)
        result = subprocess.run(["bash", GATE.with_name("nearby-device-check.sh")],
                                cwd=self.root, env=env, text=True, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        calls = [json.loads(line) for line in self.trace.read_text().splitlines()]
        device = ["adb", "-s", "emulator-nearby"]
        # 2 initial offline + 1 flap + 6 consecutive settled = 9 connectivity checks.
        self.assertEqual(calls.count([*device, "shell", "dumpsys", "connectivity"]), 9)
        # Early disable + wait_emulator_offline initial disable + immediate re-assert on drop-back = 3 disables.
        self.assertEqual(calls.count([*device, "shell", "settings", "put", "global", "mobile_data", "0"]), 3)

    def test_both_offline_gates_enforce_wifi_off_airplane_on_and_fail_cleanly_when_network_stays_up(self):
        cases = [
            ("wifi_still_on", {"FIXTURE_WIFI_ON": "1", "FIXTURE_AIRPLANE_ON": "1", "FIXTURE_CONNECTIVITY": "Active default network: none"}),
            ("airplane_off", {"FIXTURE_WIFI_ON": "0", "FIXTURE_AIRPLANE_ON": "0", "FIXTURE_CONNECTIVITY": "Active default network: none"}),
            ("default_network_active", {"FIXTURE_WIFI_ON": "0", "FIXTURE_AIRPLANE_ON": "1", "FIXTURE_CONNECTIVITY": "Active default network: 100"}),
        ]
        fast_poll = {
            "EMULATOR_OFFLINE_MAX_ATTEMPTS": "3",
            "EMULATOR_OFFLINE_POLL_SECONDS": "0",
            "EMULATOR_OFFLINE_REQUIRED_HITS": "2",
            "EMULATOR_OFFLINE_REASSERT_EVERY": "2",
        }
        for gate_name, runner, serial in (
            ("handoff", self.handoff_gate, HANDOFF[2]),
            ("nearby", self.nearby_gate, "emulator-nearby"),
        ):
            for label, overrides in cases:
                with self.subTest(gate=gate_name, case=label):
                    self.trace.write_text("")
                    result, calls = runner(**fast_poll, **overrides)
                    self.assertEqual(result.returncode, 1)
                    self.assertIn("Emulator did not settle into offline airplane mode", result.stderr)
                    device = ["adb", "-s", serial]
                    self.assertGreaterEqual(calls.count([*device, "shell", "cmd", "connectivity", "airplane-mode", "enable"]), 3)
                    self.assertGreaterEqual(calls.count([*device, "shell", "svc", "wifi", "disable"]), 3)
                    self.assertGreaterEqual(calls.count([*device, "shell", "svc", "data", "disable"]), 3)
                    self.assertGreaterEqual(calls.count([*device, "shell", "settings", "put", "global", "mobile_data", "0"]), 3)
                    self.assertFalse(any(cmd[0] == "python" for cmd in calls))

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

    def test_emulator_ready_retries_until_boot_completes_and_fails_cleanly_on_timeout(self):
        result, calls = self.gate(
            suite="native",
            include_handoff=False,
            HERMES_NATIVE_SHARD="0/1",
            FIXTURE_BOOT_SEQ="0,,1",
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(calls.count([*ADB, "shell", "getprop", "sys.boot_completed"]), 3)
        self.assertIn([*ADB, "shell", "wm", "dismiss-keyguard"], calls)
        self.assertIn([*ADB, "shell", "settings", "put", "global", "anr_show_background", "0"], calls)

        self.trace.write_text("")
        failed, calls = self.gate(
            suite="native",
            include_handoff=False,
            FIXTURE_BOOT_COMPLETED="0",
            EMULATOR_READY_MAX_ATTEMPTS="3",
        )
        self.assertEqual(failed.returncode, 1)
        self.assertIn("did not become ready", failed.stderr)
        self.assertFalse(any("install" in cmd for cmd in calls))

        self.trace.write_text("")
        null_focus, calls = self.gate(
            suite="native",
            include_handoff=False,
            FIXTURE_WINDOW_DUMP="mCurrentFocus=null",
            EMULATOR_READY_MAX_ATTEMPTS="3",
        )
        self.assertEqual(null_focus.returncode, 1)
        self.assertIn("did not become ready", null_focus.stderr)
        self.assertFalse(any("install" in cmd for cmd in calls))

    def test_emulator_sdk_helper_retries_corrupt_zip_cleans_installer_and_fails_when_exhausted(self):
        sdk_root = self.root / "fake-sdk"
        nested_pkg = "system-images;android-36;google_apis;x86_64"
        (sdk_root / "emulator/.installer").mkdir(parents=True)
        (sdk_root / "system-images/android-36/google_apis/x86_64/.installer").mkdir(parents=True)
        (sdk_root / ".temp").mkdir(parents=True)
        counter = self.root / "sdk-attempts.txt"
        fake_sdkmanager = self.root / "bin/fake-sdkmanager"
        fake_sdkmanager.write_text(
            "#!/usr/bin/env python3\n"
            "import os, sys\n"
            "from pathlib import Path\n"
            "c = Path(os.environ['SDK_COUNTER'])\n"
            "n = int(c.read_text()) + 1 if c.exists() else 1\n"
            "c.write_text(str(n))\n"
            "fail_until = int(os.environ.get('SDK_FAIL_UNTIL', '1'))\n"
            "if n <= fail_until:\n"
            "    print('Warning: An error occurred while preparing SDK package Android Emulator: Error on ZipFile unknown archive.')\n"
            "    sys.exit(0)\n"
            "print('done')\n"
        )
        fake_sdkmanager.chmod(0o755)
        env = {
            **os.environ,
            "ANDROID_HOME": str(sdk_root),
            "HERMES_SDKMANAGER_BIN": str(fake_sdkmanager),
            "HERMES_SDK_INSTALL_RETRIES": "3",
            "HERMES_SDK_RETRY_DELAY_SECONDS": "0",
            "SDK_COUNTER": str(counter),
            "SDK_FAIL_UNTIL": "1",
        }
        ok = subprocess.run(
            ["bash", GATE.with_name("emulator-sdk.sh"), "emulator", nested_pkg],
            env=env, text=True, capture_output=True,
        )
        self.assertEqual(ok.returncode, 0, ok.stderr)
        self.assertEqual(counter.read_text(), "2")
        self.assertFalse((sdk_root / "emulator/.installer").exists())
        self.assertFalse((sdk_root / "system-images/android-36/google_apis/x86_64/.installer").exists())
        self.assertFalse((sdk_root / ".temp").exists())

        counter.unlink()
        env["SDK_FAIL_UNTIL"] = "5"
        exhausted = subprocess.run(["bash", GATE.with_name("emulator-sdk.sh"), "emulator"], env=env, text=True, capture_output=True)
        self.assertEqual(exhausted.returncode, 1)
        self.assertIn("failed after 3 attempts", exhausted.stderr)
        self.assertEqual(counter.read_text(), "3")

    def test_offline_helper_survives_transient_adb_errors_under_errexit_pipefail(self):
        counter = self.root / "offline-transient-count.txt"
        fake_adb_dir = self.root / "transient-bin"
        fake_adb_dir.mkdir()
        fake_adb = fake_adb_dir / "adb"
        fake_adb.write_text(
            "#!/usr/bin/env python3\n"
            "import os, sys\n"
            "from pathlib import Path\n"
            "args = sys.argv[3:]\n"
            "if args in (\n"
            "    ['shell', 'cmd', 'connectivity', 'airplane-mode', 'enable'],\n"
            "    ['shell', 'svc', 'wifi', 'disable'],\n"
            "    ['shell', 'svc', 'data', 'disable'],\n"
            "    ['shell', 'settings', 'put', 'global', 'mobile_data', '0'],\n"
            "):\n"
            "    c = Path(os.environ['OFFLINE_COUNTER'])\n"
            "    if c.exists() and int(c.read_text()) == 2:\n"
            "        print('error: device offline', file=sys.stderr)\n"
            "        sys.exit(1)\n"
            "elif args == ['shell', 'settings', 'get', 'global', 'airplane_mode_on']:\n"
            "    c = Path(os.environ['OFFLINE_COUNTER'])\n"
            "    n = int(c.read_text()) + 1 if c.exists() else 1\n"
            "    c.write_text(str(n))\n"
            "    if n == 2:\n"
            "        print('error: device offline', file=sys.stderr)\n"
            "        sys.exit(1)\n"
            "    print('1\\r')\n"
            "elif args == ['shell', 'settings', 'get', 'global', 'wifi_on']:\n"
            "    c = Path(os.environ['OFFLINE_COUNTER'])\n"
            "    if int(c.read_text()) == 3:\n"
            "        sys.exit(1)\n"
            "    print('0\\r')\n"
            "elif args == ['shell', 'dumpsys', 'connectivity']:\n"
            "    c = Path(os.environ['OFFLINE_COUNTER'])\n"
            "    if int(c.read_text()) == 4:\n"
            "        sys.exit(1)\n"
            "    print('Active default network: none')\n"
        )
        fake_adb.chmod(0o755)
        env = {
            **os.environ,
            "PATH": str(fake_adb_dir) + os.pathsep + os.environ["PATH"],
            "OFFLINE_COUNTER": str(counter),
            "EMULATOR_OFFLINE_POLL_SECONDS": "0",
            "EMULATOR_OFFLINE_REQUIRED_HITS": "2",
            "EMULATOR_OFFLINE_MAX_ATTEMPTS": "10",
        }
        offline_sh = GATE.with_name("emulator-offline.sh")
        result = subprocess.run(
            ["bash", "-c", f'set -euo pipefail; source "{offline_sh}"; wait_emulator_offline emulator-transient'],
            env=env, text=True, capture_output=True,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        # Attempt 1 succeeds (hit=1); attempts 2, 3, 4 fail on airplane/wifi/connectivity (resetting hit=0);
        # attempts 5 and 6 succeed consecutively (hit=2).
        self.assertEqual(int(counter.read_text()), 6)

        # R40.8.1: direct disable_emulator_network callers must also survive transient ADB errors under set -euo pipefail.
        counter.write_text("2")
        direct = subprocess.run(
            ["bash", "-c", f'set -euo pipefail; source "{offline_sh}"; disable_emulator_network emulator-transient'],
            env=env, text=True, capture_output=True,
        )
        self.assertEqual(direct.returncode, 0, direct.stderr)

    def test_workspace_job_runs_hermes_contract_tests_without_requiring_android_gate(self):
        workflow = (GATE.parents[1] / ".github/workflows/checks.yml").read_text()
        workspace_job = workflow.split("\n  workspace:\n", 1)[1].split("\n  package-debug:\n", 1)[0]
        self.assertIn("needs.changes.outputs.run_workspace == 'true'", workspace_job)
        self.assertNotIn("run_android", workspace_job)
        self.assertIn("uses: ./.github/actions/hermes-fixture", workspace_job)
        self.assertIn("HERMES_TEST_CHECKOUT: ${{ github.workspace }}/.fixture/hermes", workspace_job)
        self.assertIn('--workspace-only --server-python "$RUNNER_TEMP/hermes-env/bin/python"', workspace_job)

        live_core_job = workflow.split("\n  device-live-core:\n", 1)[1].split("\n  device-journeys:\n", 1)[0]
        self.assertNotIn("unittest discover -s server/tests", live_core_job)
        self.assertNotIn("-r server/requirements.txt", live_core_job)

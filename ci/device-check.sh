#!/usr/bin/env bash
# Exercise the packaged debug APKs on one disposable phone.
set -euo pipefail
device=$(adb get-serialno)
adb -s "$device" install -r -t app/build/outputs/apk/debug/app-debug.apk
test_apk=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s "$device" install -r -t "$test_apk"
python3 -B ci/native_tests.py "$device" "$RUNNER_TEMP/android-instrumentation" "$ANDROID_BUILD_TOOLS/dexdump" "$test_apk"
# Remove private test state before the independent two-account journey.
adb -s "$device" shell pm clear org.llm4people.hermes.test
adb -s "$device" shell pm clear org.llm4people.hermes
"$RUNNER_TEMP/hermes-env/bin/python" -B verification/live_api.py --source .fixture/hermes --require-tool-controls \
  --device-command python3 -B verification/device/run.py --serial "$device" --output "$RUNNER_TEMP/hermes-device"

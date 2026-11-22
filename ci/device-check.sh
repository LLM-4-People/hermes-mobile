#!/usr/bin/env bash
# Run framework tests, then install the same APKs for the two-account journey.
set -euo pipefail
# Release build workers before booting two gateway processes beside the emulator.
./gradlew --no-daemon --max-workers=2 :app:connectedDebugAndroidTest
device=$(adb get-serialno)
adb -s "$device" install -r -t app/build/outputs/apk/debug/app-debug.apk
adb -s "$device" install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
"$RUNNER_TEMP/hermes-env/bin/python" -B verification/live_api.py --source .fixture/hermes --require-tool-controls \
  --device-command python3 -B verification/device/run.py --serial "$device" --output "$RUNNER_TEMP/hermes-device"

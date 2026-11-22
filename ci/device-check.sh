#!/usr/bin/env bash
# Install the framework-tested APKs once for both live gateway journeys.
set -euo pipefail
# Release build workers before booting two gateway processes beside the emulator.
./gradlew --no-daemon --max-workers=2 :app:connectedDebugAndroidTest
device=$(adb get-serialno)
app_apk=app/build/outputs/apk/debug/app-debug.apk
test_apk=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s "$device" install -r -t "$app_apk"
adb -s "$device" install -r -t "$test_apk"
"$RUNNER_TEMP/hermes-env/bin/python" -B verification/live_api.py --source .fixture/hermes --require-tool-controls \
  --device-command python3 -B verification/device/run.py --serial "$device" --output "$RUNNER_TEMP/hermes-device"
"$RUNNER_TEMP/hermes-env/bin/python" -B verification/conversation_cards.py --source .fixture/hermes \
  --serial "$device" --verify-apks "$app_apk" "$test_apk" --output "$RUNNER_TEMP/hermes-conversation-cards/android"

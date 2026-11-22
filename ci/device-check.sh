#!/usr/bin/env bash
# Install the framework-tested APKs once for the live gateway journeys.
set -euo pipefail
# Release build workers before booting two gateway processes beside the emulator.
./gradlew --no-daemon --max-workers=2 :app:connectedDebugAndroidTest
device=$(adb get-serialno)
app_apk=app/build/outputs/apk/debug/app-debug.apk
test_apk=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s "$device" install -r -t "$app_apk"
adb -s "$device" install -r -t "$test_apk"
fixture_python="$RUNNER_TEMP/hermes-env/bin/python"
source_args=(--source .fixture/hermes)
phone_args=(--serial "$device" --verify-apks "$app_apk" "$test_apk")
"$fixture_python" -B verification/live_api.py "${source_args[@]}" --require-tool-controls \
  --device-command python3 -B verification/device/run.py --serial "$device" --output "$RUNNER_TEMP/hermes-device"
"$fixture_python" -B verification/conversation_cards.py "${source_args[@]}" "${phone_args[@]}" \
  --output "$RUNNER_TEMP/hermes-conversation-cards/android"
echo "Verifying thread instruments on compact and wide Android layouts"
"$fixture_python" -B verification/thread_instruments.py "${source_args[@]}" "${phone_args[@]}" \
  --output "$RUNNER_TEMP/hermes-thread-instrument" || {
  status=$?
  echo "::error title=Android thread instruments::Journey failed (exit $status). See the phase output and any partial screenshots in android-thread-instruments." >&2
  exit "$status"
}

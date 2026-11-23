#!/usr/bin/env bash
# Exercise the packaged framework suite and the live gateway journeys on one disposable phone.
# Set HERMES_DEVICE_SUITE=core|journeys|all (default: all) to shard across parallel CI emulators.
set -euo pipefail
suite=${HERMES_DEVICE_SUITE:-all}
case "$suite" in
  all|core|journeys) ;;
  *) echo "Unknown HERMES_DEVICE_SUITE: $suite (expected all, core, or journeys)" >&2; exit 2 ;;
esac
device=$(adb get-serialno)
app_apk=app/build/outputs/apk/debug/app-debug.apk
test_apk=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s "$device" install -r -t "$app_apk"
adb -s "$device" install -r -t "$test_apk"
if [[ "$suite" == all || "$suite" == core ]]; then
  python3 -B ci/native_tests.py "$device" "$RUNNER_TEMP/android-instrumentation" "$ANDROID_BUILD_TOOLS/dexdump" "$test_apk"
fi
# Remove private test state before the independent two-account journey.
adb -s "$device" shell pm clear org.llm4people.hermes.test
adb -s "$device" shell pm clear org.llm4people.hermes
adb -s "$device" shell cmd locale set-app-locales org.llm4people.hermes --locales fr-FR
fixture_python="$RUNNER_TEMP/hermes-env/bin/python"
source_args=(--source .fixture/hermes)
phone_args=(--serial "$device" --verify-apks "$app_apk" "$test_apk")
if [[ "$suite" == all || "$suite" == core ]]; then
  "$fixture_python" -B verification/live_api.py "${source_args[@]}" --require-tool-controls \
    --device-command python3 -B verification/device/run.py --serial "$device" --output "$RUNNER_TEMP/hermes-device"
  "$fixture_python" -B verification/conversation_cards.py "${source_args[@]}" "${phone_args[@]}" \
    --output "$RUNNER_TEMP/hermes-conversation-cards/android"
fi
if [[ "$suite" == all || "$suite" == journeys ]]; then
  echo "Verifying thread instruments on compact and wide Android layouts"
  "$fixture_python" -B verification/thread_instruments.py "${source_args[@]}" "${phone_args[@]}" \
    --output "$RUNNER_TEMP/hermes-thread-instrument" || {
    status=$?
    echo "::error title=Android thread instruments::Journey failed (exit $status). See the phase output and any partial screenshots in android-thread-instruments." >&2
    exit "$status"
  }
  "$fixture_python" -B verification/discussion_photo.py "${source_args[@]}" "${phone_args[@]}" \
    --output "$RUNNER_TEMP/hermes-discussion-photo"
  "$fixture_python" -B verification/discussion_skills.py "${source_args[@]}" "${phone_args[@]}" \
    --output "$RUNNER_TEMP/hermes-discussion-skills"
fi

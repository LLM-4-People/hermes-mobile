#!/usr/bin/env bash
# Android's runtime permission, foreground provider and map receiver on the disposable CI emulator.
set -euo pipefail
# shellcheck source=ci/emulator-offline.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/emulator-offline.sh"
serial=$(adb get-serialno)
app=app/build/outputs/apk/debug/app-debug.apk
tests=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
evidence="$RUNNER_TEMP/hermes-nearby"
adb -s "$serial" install -r -t "$app"
adb -s "$serial" install -r -t "$tests"
adb -s "$serial" shell pm clear org.llm4people.hermes.test
adb -s "$serial" shell pm clear org.llm4people.hermes
resolved=$(adb -s "$serial" shell cmd package resolve-activity --brief --user 0 \
  -a android.intent.action.VIEW -d 'geo:0,0?q=Montreal' | tail -n 1)
if [[ "$resolved" != com.google.android.apps.maps/* ]]; then
  echo "Expected Android to resolve geo intent to com.google.android.apps.maps. Received: $resolved" >&2
  exit 1
fi
wait_emulator_offline "$serial"
"$RUNNER_TEMP/hermes-env/bin/python" -B -m verification.nearby_restaurants --serial "$serial" \
  --source .fixture/hermes --verify-apks "$app" "$tests" --output "$evidence"
for file in nearby.json guide.png comparison.png detail.png; do
  test -s "$evidence/$file" || { echo "Missing Android nearby evidence: $file" >&2; exit 1; }
done

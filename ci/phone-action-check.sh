#!/usr/bin/env bash
# Run the handoff journey on its own freshly booted API 36 Google APIs device.
set -euo pipefail
serial=$(adb get-serialno)
app_apk=${1:?expected the packaged Hermes APK}
device=(adb -s "$serial")
calendar_apk="$RUNNER_TEMP/calendar-25-foss-release.apk"
proof="$RUNNER_TEMP/hermes-phone-actions"

# Use Fossify's published, independently inspectable offline editor.
curl --proto '=https' --proto-redir '=https' --fail --location --silent --show-error --retry 3 --connect-timeout 15 --max-time 90 \
  https://github.com/FossifyOrg/Calendar/releases/download/1.12.2/calendar-25-foss-release.apk --output "$calendar_apk"
echo "2825816d0a972bd85eb289e556221293fadecfd3179a4a2531bde6f89c6f0238  $calendar_apk" | sha256sum --check
"${device[@]}" install -r "$calendar_apk"
"${device[@]}" install -r -t "$app_apk"

# Keep the gateway on adb reverse. The external apps must have no default network or signed-in account.
"${device[@]}" shell cmd connectivity airplane-mode enable
"${device[@]}" shell svc wifi disable
"${device[@]}" shell svc data disable
enabled=$("${device[@]}" shell pm list packages -e com.google.android.calendar)
if grep -Fxq 'package:com.google.android.calendar' <<< "$enabled"; then
  "${device[@]}" shell pm disable-user --user 0 com.google.android.calendar
fi
"${device[@]}" shell pm clear org.llm4people.hermes

require_receiver() {
  local expected=$1 resolved
  shift
  resolved=$("${device[@]}" shell cmd package resolve-activity --brief --user 0 "$@" | tail -n 1)
  if [[ "$resolved" != "$expected/"* ]]; then
    echo "Expected Android to resolve $* to $expected. Received: $resolved" >&2
    return 1
  fi
}
# The package-manager shell matches the supplied type; normal app dispatch gets
# CalendarContract.Events.CONTENT_TYPE from the URI's ContentProvider.
require_receiver org.fossify.calendar -a android.intent.action.INSERT -d content://com.android.calendar/events \
  -t vnd.android.cursor.dir/event
require_receiver com.google.android.apps.maps -a android.intent.action.VIEW -d 'geo:0,0?q=Laval'

# Android disconnects asynchronously. Poll until airplane mode is on, Wi-Fi is off, and no default network remains across 2 consecutive checks.
offline_hits=0
airplane=""
wifi=""
for ((i = 0; i < 120; i++)); do
  airplane=$("${device[@]}" shell settings get global airplane_mode_on | tr -d '\r')
  wifi=$("${device[@]}" shell settings get global wifi_on | tr -d '\r')
  connectivity=$("${device[@]}" shell dumpsys connectivity)
  if [[ "$airplane" == "1" && "$wifi" == "0" && "$connectivity" == *'Active default network: none'* ]]; then
    offline_hits=$((offline_hits + 1))
    if (( offline_hits >= 2 )); then break; fi
  else
    offline_hits=0
    if (( i > 0 && i % 20 == 0 )); then
      "${device[@]}" shell cmd connectivity airplane-mode enable
      "${device[@]}" shell svc wifi disable
      "${device[@]}" shell svc data disable
    fi
  fi
  sleep 0.25
done
if (( offline_hits < 2 )); then
  echo "Emulator did not settle into offline airplane mode (airplane_mode_on=${airplane:-?}, wifi_on=${wifi:-?})." >&2
  exit 1
fi
mkdir "$proof"
"$RUNNER_TEMP/hermes-env/bin/python" -B -m verification.phone_actions --source .fixture/hermes \
  --serial "$serial" --apk "$app_apk" --inbound --output "$proof/phone-actions.json"
for name in phone-actions{,-conversation}.json phone-actions-{share_text,calendar_event,map_search}.png; do
  if [[ ! -s "$proof/$name" ]]; then
    echo "Android handoff evidence missing: $name" >&2
    exit 1
  fi
done

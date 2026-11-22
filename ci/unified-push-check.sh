#!/usr/bin/env bash
# Run on a clean hosted emulator. Only the anonymous assertion summary is exported.
set -euo pipefail
serial=$(adb get-serialno)
packages=$(adb -s "$serial" shell pm list packages | tr -d '\r')
if grep -Eq '^package:(com\.google\.android\.(gms|gsf)|com\.android\.vending)$' <<< "$packages"; then
  echo "Use the reviewed AOSP phone image for the Google-free delivery test." >&2
  exit 1
fi
"$RUNNER_TEMP/hermes-env/bin/python" -B -m verification.unifiedpush.prove \
  --source .fixture/hermes --serial "$serial" --apk app/build/outputs/apk/debug/app-debug.apk \
  --push-python "$RUNNER_TEMP/push-env/bin/python" --cache "$RUNNER_TEMP/ntfy" |
  jq -Recs '
    [split("\n")[] | fromjson? | select(type == "object" and .notification_event_matched == true)] |
    if length == 1 then .[0] |
      {hermes_revision, sdk, ntfy_server, distributor, absent_before_delivery, webpush_messages,
       notification_event_matched, owning_profile_unlocked, owning_thread_opened, revoked_delivery_suppressed}
    else error("Missing unique delivery proof") end
  ' > "$RUNNER_TEMP/unified-push-result.json"
cat "$RUNNER_TEMP/unified-push-result.json"

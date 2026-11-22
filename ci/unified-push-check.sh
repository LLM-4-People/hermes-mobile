#!/usr/bin/env bash
# Run on a clean hosted emulator. Only the anonymous assertion summary is exported.
set -euo pipefail
serial=$(adb get-serialno)
packages=$(adb -s "$serial" shell pm list packages | tr -d '\r')
if grep -Eq '^package:(com\.google\.android\.(gms|gsf)|com\.android\.vending)$' <<< "$packages"; then
  echo "Use the reviewed AOSP phone image for the Google-free delivery test." >&2
  exit 1
fi
: "${HERMES_FIXTURE_TLS_CERT:?}" "${HERMES_FIXTURE_TLS_KEY:?}"
input="$RUNNER_TEMP/android-release-input"
"$RUNNER_TEMP/hermes-env/bin/python" -B -m verification.unifiedpush.prove \
  --source .fixture/hermes --serial "$serial" --apk "$input/Hermes-Mobile.apk" \
  --tls-cert "$HERMES_FIXTURE_TLS_CERT" --tls-key "$HERMES_FIXTURE_TLS_KEY" \
  --push-python "$RUNNER_TEMP/push-env/bin/python" --cache "$RUNNER_TEMP/ntfy" |
  python3 -B ci/aosp_receipt.py "$input" > "$RUNNER_TEMP/unified-push-result.json"
cat "$RUNNER_TEMP/unified-push-result.json"

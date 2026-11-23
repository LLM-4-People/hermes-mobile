#!/usr/bin/env bash
# Run on a clean hosted emulator. Evidence contains only generic labels and synthetic controls.
set -euo pipefail
serial=$(adb get-serialno)
packages=$(adb -s "$serial" shell pm list packages | tr -d '\r')
if grep -Eq '^package:(com\.google\.android\.(gms|gsf)|com\.android\.vending)$' <<< "$packages"; then
  echo "Use the reviewed AOSP phone image for the Google-free delivery test." >&2
  exit 1
fi
: "${HERMES_FIXTURE_TLS_CERT:?}" "${HERMES_FIXTURE_TLS_KEY:?}"
input="$RUNNER_TEMP/android-release-input"
apk_name=$(python3 -B - "$input/release.json" <<'PY'
import json
from pathlib import Path
import sys
sys.path.insert(0, str(Path("ci").resolve()))
from publish_release import expected_apk_name
print(expected_apk_name(json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))))
PY
)
umask 077
proof=$(mktemp "$RUNNER_TEMP/aosp-result.XXXXXX")
trap 'rm -f -- "$proof"' EXIT
"$RUNNER_TEMP/hermes-env/bin/python" -B -m verification.unifiedpush.prove \
  --source .fixture/hermes --serial "$serial" --apk "$input/$apk_name" \
  --tls-cert "$HERMES_FIXTURE_TLS_CERT" --tls-key "$HERMES_FIXTURE_TLS_KEY" \
  --push-python "$RUNNER_TEMP/push-env/bin/python" --cache "$RUNNER_TEMP/ntfy" \
  --evidence-dir "$RUNNER_TEMP/aosp-notification" > "$proof"
python3 -B ci/aosp_receipt.py "$input" < "$proof" > "$RUNNER_TEMP/unified-push-result.json"
cat "$RUNNER_TEMP/unified-push-result.json"

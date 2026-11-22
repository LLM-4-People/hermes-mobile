#!/usr/bin/env bash
# Sign the verified input, validate its signer and emit the release metadata.
set -euo pipefail
umask 077
: "${ANDROID_BUILD_TOOLS:?}" "${ANDROID_SIGNING_KEYSTORE_BASE64:?}" "${ANDROID_SIGNING_ALIAS:?}"
: "${ANDROID_SIGNING_STORE_PASSWORD:?}" "${ANDROID_SIGNING_KEY_PASSWORD:?}" "${ANDROID_SIGNING_CERT_SHA256:?}"
: "${GITHUB_SHA:?}"
unsigned=${1:?Expected unsigned APK path}
out=${2:?Expected output directory}
name=Hermes-Mobile.apk
private=$(mktemp -d)
trap 'rm -rf "$private"' EXIT
mkdir -p "$out"
printf %s "$ANDROID_SIGNING_KEYSTORE_BASE64" | base64 --decode > "$private/keystore"
"$ANDROID_BUILD_TOOLS/zipalign" -c -P 16 4 "$unsigned"
"$ANDROID_BUILD_TOOLS/apksigner" sign --ks "$private/keystore" \
  --ks-key-alias "$ANDROID_SIGNING_ALIAS" --ks-pass env:ANDROID_SIGNING_STORE_PASSWORD \
  --key-pass env:ANDROID_SIGNING_KEY_PASSWORD --v4-signing-enabled false \
  --out "$private/$name" "$unsigned"
"$ANDROID_BUILD_TOOLS/zipalign" -c -P 16 4 "$private/$name"
"$ANDROID_BUILD_TOOLS/apksigner" verify --verbose --print-certs "$private/$name" > "$private/signers"
certificate=$(sed -n 's/^Signer #[0-9]* certificate SHA-256 digest: //p' "$private/signers")
expected=${ANDROID_SIGNING_CERT_SHA256//:/}
if [[ ! $expected =~ ^[[:xdigit:]]{64}$ || ${certificate,,} != "${expected,,}" ]]; then
  echo "The APK signing certificate differs from the configured SHA-256 fingerprint." >&2
  exit 1
fi
"$ANDROID_BUILD_TOOLS/aapt" dump badging "$private/$name" > "$private/badging"
python3 "$(dirname "$0")/release_metadata.py" "$private/badging" "$unsigned" \
  "$private/$name" "$certificate" "$GITHUB_SHA" > "$private/release.json"
(cd "$private" && sha256sum "$name" > SHA256SUMS)
mv "$private/$name" "$private/SHA256SUMS" "$private/release.json" "$out/"

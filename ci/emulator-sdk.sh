#!/usr/bin/env bash
# Shared Single Source of Truth helper for resilient Android SDK package installation.
# Retries transient CDN/archive corruption (e.g. "Error on ZipFile unknown archive")
# and removes partial .installer directories between attempts.
set -euo pipefail

install_sdk_packages() {
  local sdk_root=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
  if [[ -z "$sdk_root" ]]; then
    echo "ANDROID_HOME or ANDROID_SDK_ROOT must be set." >&2
    return 1
  fi
  local sdkmanager_bin=${HERMES_SDKMANAGER_BIN:-"$sdk_root/cmdline-tools/latest/bin/sdkmanager"}
  local max_attempts=${HERMES_SDK_INSTALL_RETRIES:-3}
  local retry_delay=${HERMES_SDK_RETRY_DELAY_SECONDS:-5}
  local packages=("$@")
  if (( ${#packages[@]} == 0 )); then
    echo "Expected at least one Android SDK package name." >&2
    return 2
  fi
  if ! [[ "$max_attempts" =~ ^[1-9][0-9]*$ ]]; then
    echo "HERMES_SDK_INSTALL_RETRIES must be a positive integer, got: $max_attempts" >&2
    return 2
  fi

  local attempt output status licenses pkg rel
  licenses=$(printf 'y\n%.0s' {1..64})
  for ((attempt = 1; attempt <= max_attempts; attempt++)); do
    set +e
    output=$("$sdkmanager_bin" --sdk_root="$sdk_root" --install "${packages[@]}" <<< "$licenses" 2>&1)
    status=$?
    set -e
    if (( status == 0 )) && ! grep -q "Warning: An error occurred while preparing SDK package" <<< "$output"; then
      return 0
    fi
    echo "sdkmanager install attempt ${attempt}/${max_attempts} failed for: ${packages[*]}" >&2
    printf '%s\n' "$output" | tail -n 20 >&2
    rm -rf "$sdk_root/.temp"
    for pkg in "${packages[@]}"; do
      rel=${pkg//;/'/'}
      if [[ -n "$rel" && "$rel" != /* && "$rel" != *".."* ]]; then
        rm -rf "$sdk_root/$rel/.installer"
      fi
    done
    if (( attempt < max_attempts )); then
      sleep "$retry_delay"
    fi
  done

  echo "Android SDK package installation failed after ${max_attempts} attempts: ${packages[*]}" >&2
  return 1
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  install_sdk_packages "$@"
fi

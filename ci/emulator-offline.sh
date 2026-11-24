# shellcheck shell=bash
# Shared Single Source of Truth helper for disabling emulator networking and
# waiting reactively until airplane mode, Wi-Fi off, and default network teardown settle.

disable_emulator_network() {
  local serial=${1:?expected adb serial}
  adb -s "$serial" shell cmd connectivity airplane-mode enable
  adb -s "$serial" shell svc wifi disable
  adb -s "$serial" shell svc data disable
  adb -s "$serial" shell settings put global mobile_data 0
}

wait_emulator_offline() {
  local serial=${1:?expected adb serial}
  local max_attempts=${EMULATOR_OFFLINE_MAX_ATTEMPTS:-120}
  local poll_interval=${EMULATOR_OFFLINE_POLL_SECONDS:-0.5}
  local required_hits=${EMULATOR_OFFLINE_REQUIRED_HITS:-6}
  local reassert_every=${EMULATOR_OFFLINE_REASSERT_EVERY:-10}
  local offline_hits=0
  local airplane=""
  local wifi=""
  local connectivity=""
  local i

  disable_emulator_network "$serial"

  for ((i = 0; i < max_attempts; i++)); do
    airplane=$(adb -s "$serial" shell settings get global airplane_mode_on | tr -d '\r')
    wifi=$(adb -s "$serial" shell settings get global wifi_on | tr -d '\r')
    connectivity=$(adb -s "$serial" shell dumpsys connectivity)
    if [[ "$airplane" == "1" && "$wifi" == "0" && "$connectivity" == *'Active default network: none'* ]]; then
      offline_hits=$((offline_hits + 1))
      if (( offline_hits >= required_hits )); then
        return 0
      fi
    else
      if (( offline_hits > 0 || (i > 0 && reassert_every > 0 && i % reassert_every == 0) )); then
        disable_emulator_network "$serial"
      fi
      offline_hits=0
    fi
    sleep "$poll_interval"
  done

  echo "Emulator did not settle into offline airplane mode (airplane_mode_on=${airplane:-?}, wifi_on=${wifi:-?}, required_hits=${required_hits})." >&2
  return 1
}

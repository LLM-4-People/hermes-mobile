# shellcheck shell=bash
# Shared Single Source of Truth helper for deterministic CI emulator readiness.
# Waits reactively for Android boot/package/window services, wakes and unlocks the
# screen, dismisses lingering system UI, and disables CI-disruptive popups/animations.

wait_emulator_ready() {
  local serial=${1:?expected adb serial}
  local max_attempts=${EMULATOR_READY_MAX_ATTEMPTS:-120}
  local poll_interval=${EMULATOR_READY_POLL_SECONDS:-0.5}
  local boot_completed=""
  local pm_ready=""
  local window_ready=""
  local i

  for ((i = 0; i < max_attempts; i++)); do
    boot_completed=$(adb -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)
    if [[ "$boot_completed" == "1" ]]; then
      pm_ready=$(adb -s "$serial" shell pm path android 2>/dev/null | tr -d '\r' || true)
      if [[ "$pm_ready" == package:* ]]; then
        window_ready=$(adb -s "$serial" shell dumpsys window 2>/dev/null || true)
        if grep -q "mCurrentFocus=" <<< "$window_ready"; then
          break
        fi
      fi
    fi
    sleep "$poll_interval"
  done

  if [[ "$boot_completed" != "1" || "$pm_ready" != package:* ]] || ! grep -q "mCurrentFocus=" <<< "$window_ready"; then
    echo "Emulator $serial did not become ready (boot_completed=${boot_completed:-?}, pm=${pm_ready:-?})." >&2
    return 1
  fi

  adb -s "$serial" shell svc power stayon true >/dev/null 2>&1 || true
  adb -s "$serial" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
  adb -s "$serial" shell wm dismiss-keyguard >/dev/null 2>&1 || true
  adb -s "$serial" shell cmd statusbar collapse >/dev/null 2>&1 || true
  adb -s "$serial" shell settings put global verifier_verify_adb_installs 0 >/dev/null 2>&1 || true
  adb -s "$serial" shell settings put global package_verifier_enable 0 >/dev/null 2>&1 || true
  adb -s "$serial" shell settings put global anr_show_background 0 >/dev/null 2>&1 || true
  adb -s "$serial" shell settings put global window_animation_scale 0 >/dev/null 2>&1 || true
  adb -s "$serial" shell settings put global transition_animation_scale 0 >/dev/null 2>&1 || true
  adb -s "$serial" shell settings put global animator_duration_scale 0 >/dev/null 2>&1 || true
}

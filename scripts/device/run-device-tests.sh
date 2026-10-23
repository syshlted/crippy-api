#!/usr/bin/env bash
# Runs the device tests (AGENTS.md L13) on one phone or emulator, and says whether they passed.
#
#   scripts/device/run-device-tests.sh <serial> [path to a crippy debug APK]
#
# With crippy installed, AgainstCrippyTest drives crippy's own screens to unlock the vault and let the
# test in, and it needs the vault's passphrase for that, in CRIPPY_PASSPHRASE. The screen must be on
# and not behind a secure keyguard. Without crippy, that class skips and the stand-in suite still runs.
#
# Not connectedDebugAndroidTest, for two reasons, both measured in crippy: it uninstalls the test APK
# when it finishes, which drops the grant crippy keeps for this install (crippy D120 keys a grant on
# the install), and over wireless adb its exit status says failed when every test passed. So the APK
# is installed with -r, which keeps the grant, and the runner's own summary decides.
#
# ADB overrides the adb command, e.g. ADB="adb -P 5038" for a phone on the host's USB (crippy's notes).
set -euo pipefail

serial=${1:?usage: run-device-tests.sh <serial> [crippy-debug.apk]}
crippy_apk=${2:-}
adb=(${ADB:-adb} -s "$serial")
root=$(cd "$(dirname "$0")/../.." && pwd)
apk="$root/crippy-client/build/outputs/apk/androidTest/debug/crippy-client-debug-androidTest.apk"
runner=com.systemhalted.crippy.client.test/androidx.test.runner.AndroidJUnitRunner

(cd "$root" && ./gradlew --quiet :crippy-client:assembleDebugAndroidTest)
if [ -n "$crippy_apk" ]; then "${adb[@]}" install -r "$crippy_apk" > /dev/null; fi
"${adb[@]}" install -r -t "$apk" > /dev/null
"${adb[@]}" shell input keyevent KEYCODE_WAKEUP

args=()
if [ -n "${CRIPPY_PASSPHRASE:-}" ]; then
  case "$CRIPPY_PASSPHRASE" in *\'*) echo "run-device-tests: a passphrase with a ' cannot be passed through adb shell" >&2; exit 2 ;; esac
  args=(-e crippyPassphrase "'$CRIPPY_PASSPHRASE'")
fi

report=$(mktemp)
trap 'rm -f "$report"' EXIT
status=0
for class in CrossProcessTest AgainstCrippyTest; do
  # One class per run: a comma-separated list of classes has been seen to run only the first (crippy's notes).
  "${adb[@]}" shell am instrument -w -r "${args[@]}" \
    -e class "com.systemhalted.crippy.client.device.$class" "$runner" > "$report" 2>&1 || true
  failed=$(grep -c 'INSTRUMENTATION_STATUS_CODE: -2' "$report" || true)
  skipped=$(grep -c 'INSTRUMENTATION_STATUS_CODE: -4' "$report" || true)
  if grep -q '^OK (' "$report"; then
    printf '%-18s %s, %s skipped\n' "$class" "$(grep '^OK (' "$report")" "$skipped"
    if [ "$skipped" != 0 ]; then grep -m1 'AssumptionViolatedException' "$report" | sed 's/^.*Exception: /  because: /'; fi
  else
    printf '%-18s FAILED: %s failed\n' "$class" "$failed"
    grep -E 'test=|stack=' "$report" | grep -B1 'stack=' | grep -v '^--' | head -20
    status=1
  fi
done
exit $status

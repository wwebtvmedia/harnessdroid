#!/usr/bin/env bash
# Full instrumented non-regression sweep for one connected device.
#
# Two kinds of suites:
#  - plain suites:      `am instrument -w -e class <cls>` runs them directly.
#  - accessibility UI suites (MultiAppScreenTest, SummarizeLastMailE2ETest):
#      `am instrument` force-stops the app at startup, which UNBINDS the
#      ScreenReaderService. Per the header of SummarizeLastMailE2ETest, the
#      secure-settings toggle only sticks when issued from adb AFTER the test
#      process is up — so these suites are started in the background and the
#      toggle is re-applied from here a few seconds into the run.
set -uo pipefail

SERIAL="${1:?usage: run_instrumented_nonreg.sh <device-serial>}"
PKG_TEST="com.ai.harnessdroid.test/androidx.test.runner.AndroidJUnitRunner"
TOGGLE="settings delete secure enabled_accessibility_services; \
settings put secure enabled_accessibility_services \
com.ai.harnessdroid/com.ai.harnessdroid.core.ScreenReaderService; \
settings put secure accessibility_enabled 1"

PLAIN_CLASSES=(
  com.ai.harnessdroid.PurgeStopInstrumentedTest
  com.ai.harnessdroid.core.AgentLoopTest
  com.ai.harnessdroid.IntegrationTest
  com.ai.harnessdroid.LaunchAppsInstrumentedTest
  com.ai.harnessdroid.ToolsUITest
)
UI_CLASSES=(
  com.ai.harnessdroid.MultiAppScreenTest
  com.ai.harnessdroid.SummarizeLastMailE2ETest
)

run_plain() {
  local cls="$1" out
  echo "===== $cls ====="
  out=$(timeout 420 adb -s "$SERIAL" shell am instrument -w -e class "$cls" "$PKG_TEST" 2>&1 | tail -3)
  echo "$out"
  echo "$out" | grep -qE "^OK \(|Failures: 0"
  return $?
}

run_ui() {
  local cls="$1" log
  echo "===== $cls (toggle re-applied mid-run) ====="
  log=$(mktemp)
  timeout 900 adb -s "$SERIAL" shell am instrument -w -e class "$cls" "$PKG_TEST" >"$log" 2>&1 &
  local pid=$!
  # Toggle only — the instrumentation process IS the app process here, a
  # force-stop would kill the run itself. On slow (USB) devices the test
  # process takes a while to come up, so re-apply the toggle until the OS
  # actually binds the service (empty Bound services list = not bound yet).
  sleep 6
  for _ in $(seq 1 12); do
    bound=$(adb -s "$SERIAL" shell dumpsys accessibility 2>/dev/null | grep -c "Bound services:{}" || true)
    [ "$bound" = "0" ] && break
    adb -s "$SERIAL" shell "$TOGGLE" >/dev/null 2>&1
    sleep 4
  done
  wait $pid
  local rc=$?
  tail -4 "$log"
  grep -qE "^OK \(|Failures: 0" "$log"
  rc=$?
  rm -f "$log"
  return $rc
}

has_google_account() {
  [ "$(adb -s "$SERIAL" shell dumpsys account 2>/dev/null | grep -cE 'Account \{name=.*type=com.google')" -gt 0 ]
}

FAILED=0
adb -s "$SERIAL" shell "input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard" >/dev/null 2>&1

for cls in "${PLAIN_CLASSES[@]}"; do run_plain "$cls" || FAILED=$((FAILED + 1)); done
for cls in "${UI_CLASSES[@]}"; do
  if [ "$cls" = "com.ai.harnessdroid.SummarizeLastMailE2ETest" ] && ! has_google_account; then
    echo "===== $cls ====="
    echo "SKIP: no Google account on $SERIAL — the Gmail inbox ground truth cannot exist."
    continue
  fi
  run_ui "$cls" || FAILED=$((FAILED + 1))
done

echo
if [ "$FAILED" -eq 0 ]; then
  echo "ALL SUITES GREEN on $SERIAL"
else
  echo "$FAILED suite(s) FAILED on $SERIAL"
fi
exit "$FAILED"

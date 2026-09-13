#!/bin/bash
# Runs the MultiAppScreenTest instrumented suite on the emulator.
#
# `am instrument` restarts the app process, and the OS then drops the
# accessibility service binding until the service is toggled again. This
# wrapper keeps toggling it from the outside while the tests run, so the
# in-test awaitScreenReader() helper only has to wait for the (re)bind.
set -u

DEVICE_ID="${DEVICE_ID:-emulator-5554}"
SERVICE="com.ai.harnessdroid/com.ai.harnessdroid.core.ScreenReaderService"

toggle_accessibility() {
    adb -s "$DEVICE_ID" shell settings delete secure enabled_accessibility_services >/dev/null 2>&1
    adb -s "$DEVICE_ID" shell settings put secure accessibility_enabled 0 >/dev/null 2>&1
    sleep 1
    adb -s "$DEVICE_ID" shell settings put secure enabled_accessibility_services "$SERVICE"
    adb -s "$DEVICE_ID" shell settings put secure accessibility_enabled 1
}

bound() {
    adb -s "$DEVICE_ID" shell dumpsys accessibility 2>/dev/null | grep -q "Bound services:{Service\[label=Harness Droid Screen Reader"
}

# Pre-arm the service so the first test starts with a bound reader.
toggle_accessibility
sleep 3

echo "Installing harnessDroid APKs..."
adb -s "$DEVICE_ID" install -r app/build/outputs/apk/debug/app-debug.apk | tail -1
adb -s "$DEVICE_ID" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk | tail -1
# Installing kills the app process and drops the accessibility binding: re-arm it.
toggle_accessibility
sleep 3

adb -s "$DEVICE_ID" shell am instrument -w \
    -e class com.ai.harnessdroid.MultiAppScreenTest \
    com.ai.harnessdroid.test/androidx.test.runner.AndroidJUnitRunner &
INSTRUMENT_PID=$!

# Keep the binding alive until the instrumentation process is done.
while kill -0 "$INSTRUMENT_PID" 2>/dev/null; do
    if ! bound; then
        toggle_accessibility
    fi
    sleep 4
done

wait "$INSTRUMENT_PID"
exit $?

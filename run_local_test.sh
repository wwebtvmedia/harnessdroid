#!/bin/bash
set -e

echo "=== Local Integration Test ==="

export ANDROID_HOME=$HOME/Android/Sdk
export PATH=$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator

# 1. Build the APKs
echo "Building LLMProvider APK..."
cd ../LLMProvider
./gradlew assembleDebug
cd ../harnessDroid

echo "Building harnessDroid APK and Test APK..."
./gradlew assembleDebug assembleDebugAndroidTest

# 2. Start Emulator
echo "Starting Emulator (opsec)..."
# We run it in the background
emulator -avd opsec -no-window -no-audio -gpu off &
EMULATOR_PID=$!

echo "Waiting for emulator to boot (this may take a minute)..."
adb wait-for-device
while [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" != "1" ]; do
    sleep 2
done
echo "Emulator fully booted!"

# 3. Install APKs
echo "Installing LLMProvider APK..."
adb install -r ../LLMProvider/app/build/outputs/apk/debug/app-debug.apk

echo "Installing harnessDroid APK..."
adb install -r app/build/outputs/apk/debug/app-debug.apk

echo "Installing harnessDroid Test APK..."
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

# 4. Run the Integration Test
echo "Running Integration Test..."
adb shell am instrument -w  com.ai.harnessdroid.test/androidx.test.runner.AndroidJUnitRunner

# 5. Cleanup
echo "Tests completed. Killing emulator..."
kill $EMULATOR_PID
echo "Done."

#!/bin/bash
set -e

DEVICE_ID="R52T408F3KV"

echo "=== Remote Integration Test on Tablet ($DEVICE_ID) ==="

echo "Installing LLMProvider APK..."
adb -s $DEVICE_ID install -r ../LLMProvider/app/build/outputs/apk/debug/app-debug.apk

echo "Installing harnessDroid APK..."
adb -s $DEVICE_ID install -r app/build/outputs/apk/debug/app-debug.apk

echo "Installing harnessDroid Test APK..."
adb -s $DEVICE_ID install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

echo "Running Integration Test on device..."
adb -s $DEVICE_ID shell am instrument -w  com.ai.harnessdroid.test/androidx.test.runner.AndroidJUnitRunner

echo "Remote test completed."

#!/bin/bash
set -euo pipefail

DEVICE_ID="${1:-}"
if [ -z "$DEVICE_ID" ]; then
    echo "Usage: ./run_tests.sh <DEVICE_ID>"
    echo "Available devices:"
    adb devices
    exit 1
fi

PROMPTS=(
  "What is the OS info of this device?"
  "List all harness intents"
  "Launch the calculator app"
  "List the installed apps"
  "Summarize emails"
)

echo "Building debug APK..."
./gradlew assembleDebug --no-daemon

echo "Installing APK to $DEVICE_ID..."
adb -s "$DEVICE_ID" install -r app/build/outputs/apk/debug/app-debug.apk

# Optional: Ensure LLMProvider is also installed if needed, but we assume it's already there.
# adb -s "$DEVICE_ID" install -r ../LLMProvider/app/build/outputs/apk/debug/app-debug.apk

echo "To ensure LLMConfig defaults to Tree4Five local provider, we'll launch without custom url."

for PROMPT in "${PROMPTS[@]}"; do
  echo "----------------------------------------"
  echo "Running test: '$PROMPT' on $DEVICE_ID"
  
  adb -s "$DEVICE_ID" shell am start -n com.ai.harnessdroid/.ui.MainActivity \
    --es "prompt" "'$PROMPT'" \
    --es "custom_url" "''" \
    --es "custom_api_key" "''" \
    --es "custom_api_type" "''"
    
  echo "Test launched! Wait a few seconds for the agent to process..."
  sleep 10
done

echo "All tests launched on $DEVICE_ID!"

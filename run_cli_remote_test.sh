#!/bin/bash
set -euo pipefail

DEVICE_ID="${DEVICE_ID:-R52T408F3KV}"
PROMPT="${1:-Summarize emails}"
CUSTOM_URL="${CUSTOM_URL:-http://localhost:8080}"
CUSTOM_API_KEY="${CUSTOM_API_KEY:-}"
CUSTOM_API_TYPE="${CUSTOM_API_TYPE:-OpenAI}"

./gradlew assembleDebug --no-daemon

adb -s "$DEVICE_ID" install -r app/build/outputs/apk/debug/app-debug.apk

adb -s "$DEVICE_ID" shell am start -n com.ai.harnessdroid/.ui.MainActivity \
  --es "prompt" "$PROMPT" \
  --es "custom_url" "$CUSTOM_URL" \
  --es "custom_api_key" "$CUSTOM_API_KEY" \
  --es "custom_api_type" "$CUSTOM_API_TYPE"

echo "CLI remote test launched on $DEVICE_ID"
echo "Prompt: $PROMPT"
echo "Custom URL: $CUSTOM_URL"

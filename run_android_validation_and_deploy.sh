#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APPS_DIR="$ROOT_DIR/app"
LLM_PROVIDER_DIR="${LLM_PROVIDER_DIR:-/home/pc/sby/LLMProvider}"

if [[ ! -d "$APPS_DIR" ]]; then
  echo "App project not found: $APPS_DIR" >&2
  exit 1
fi

if [[ -d "$LLM_PROVIDER_DIR" ]]; then
  echo "Using LLM provider project: $LLM_PROVIDER_DIR"
fi

cd "$ROOT_DIR"
./gradlew app:testDebugUnitTest --console=plain
./gradlew app:installDebug app:installDebugAndroidTest --console=plain

if [[ -d "$LLM_PROVIDER_DIR" ]]; then
  cd "$LLM_PROVIDER_DIR"
  if [[ -f "$LLM_PROVIDER_DIR/app/build/outputs/apk/debug/app-debug.apk" ]]; then
    echo "LLM provider APK found. Installing to devices..."
  else
    echo "LLM provider APK missing. Building it first..."
    ./gradlew app:installDebug --console=plain || true
  fi
  cd "$ROOT_DIR"
fi

adb devices | awk 'NR>1 && $2=="device" {print $1}' | while IFS= read -r serial; do
  echo "=== Processing device: $serial ==="

  adb -s "$serial" install -r "$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk" || true

  if [[ -f "$LLM_PROVIDER_DIR/app/build/outputs/apk/debug/app-debug.apk" ]]; then
    adb -s "$serial" install -r "$LLM_PROVIDER_DIR/app/build/outputs/apk/debug/app-debug.apk" || true
  fi

  adb -s "$serial" shell pm list packages | grep -Ei 'tree4five|harness|gguf' || true

  echo "Running RealLLME2ETest on $serial"
  adb -s "$serial" shell am instrument \
    -w \
    -e class com.ai.harnessdroid.RealLLME2ETest#testRealLLMAgentLoop \
    com.ai.harnessdroid.test/androidx.test.runner.AndroidJUnitRunner || true

  echo "Launching main activity on $serial"
  adb -s "$serial" shell am start -n com.ai.harnessdroid/.ui.MainActivity || true

  echo "--- Recent logcat for $serial ---"
  adb -s "$serial" logcat -d -s LLMClient ToolRegistry AgentLoop HarnessService com.tree4five.gguf 2>/dev/null | tail -n 40 || true
  echo
 done

serials=$(adb devices | awk 'NR>1 && $2=="device" {print $1}')
if [[ -z "$serials" ]]; then
  echo "No Android devices attached. Skipping device deployment."
  echo "If you want emulator-only validation, start a valid phone AVD and rerun this script."
fi

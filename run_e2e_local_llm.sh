#!/usr/bin/env bash
# E2E agent sweep against the LOCAL on-device LLM (Tree4Five AIDL provider).
#
# Forces the app's llm_config back to provider mode (use_tree4five=true), makes
# sure com.tree4five.gguf is installed and running, then runs the instrumented
# E2E suites. The Gmail suite is skipped when the device has no Google account.
#
# usage: run_e2e_local_llm.sh <device-serial>
set -uo pipefail

SERIAL="${1:?usage: run_e2e_local_llm.sh <device-serial>}"
PKG_TEST="com.ai.harnessdroid.test/androidx.test.runner.AndroidJUnitRunner"
PROVIDER_APK="${PROVIDER_APK:-/home/pc/sby/LLMProvider/app/build/outputs/apk/debug/app-debug.apk}"

adb -s "$SERIAL" get-state >/dev/null 2>&1 || { echo "Device $SERIAL not connected" >&2; exit 2; }

# 1. The local provider must be installed (and will be started by BIND_AUTO_CREATE
#    on first use, but a cold start here makes the first generateText deterministic).
if ! adb -s "$SERIAL" shell pm list packages 2>/dev/null | grep -q "package:com.tree4five.gguf"; then
  if [[ -f "$PROVIDER_APK" ]]; then
    echo "Installing local LLMProvider from $PROVIDER_APK"
    adb -s "$SERIAL" install -r "$PROVIDER_APK" >/dev/null
  else
    echo "FAIL: com.tree4five.gguf not installed and no provider APK at $PROVIDER_APK" >&2
    exit 2
  fi
fi

# 2. Provider mode ON: this is what distinguishes the local sweep.
cat > /tmp/llm_config_local.xml << 'EOF'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="use_tree4five" value="true" />
    <string name="custom_url"></string>
    <string name="custom_api_type">OpenAI</string>
    <string name="custom_model">custom-model</string>
    <string name="custom_api_key"></string>
</map>
EOF
adb -s "$SERIAL" shell "am force-stop com.ai.harnessdroid; am force-stop com.tree4five.gguf" 2>/dev/null
adb -s "$SERIAL" push /tmp/llm_config_local.xml /data/local/tmp/llm_config.xml >/dev/null
adb -s "$SERIAL" shell "run-as com.ai.harnessdroid mkdir -p shared_prefs; run-as com.ai.harnessdroid cp /data/local/tmp/llm_config.xml shared_prefs/llm_config.xml" 2>/dev/null
rm -f /tmp/llm_config_local.xml
echo "llm_config: provider mode (use_tree4five=true) — local AIDL generation."

FAILED=0
run() {
  local cls="$1" log
  echo "===== $cls (local LLM: Tree4Five provider) ====="
  log=$(mktemp)
  timeout 900 adb -s "$SERIAL" shell am instrument -w -e class "$cls" "$PKG_TEST" >"$log" 2>&1
  tail -3 "$log"
  grep -qE "^OK \(" "$log"
  rc=$?
  rm -f "$log"
  return $rc
}

run com.ai.harnessdroid.RealLLME2ETest || FAILED=$((FAILED + 1))

# The LLM must generate a simple Python plan and run it in the MicroPython VM.
run com.ai.harnessdroid.PythonPlanE2ETest || FAILED=$((FAILED + 1))

# The Gmail E2E needs a signed-in inbox to establish ground truth.
if [ "$(adb -s "$SERIAL" shell dumpsys account 2>/dev/null | grep -cE 'Account \{name=.*type=com.google')" -gt 0 ]; then
  run com.ai.harnessdroid.SummarizeLastMailE2ETest || FAILED=$((FAILED + 1))
else
  echo "===== com.ai.harnessdroid.SummarizeLastMailE2ETest ====="
  echo "SKIP: no Google account on $SERIAL."
fi

if [ "$FAILED" -eq 0 ]; then
  echo "LOCAL LLM E2E GREEN on $SERIAL"
else
  echo "$FAILED suite(s) FAILED (local LLM) on $SERIAL"
fi
exit "$FAILED"

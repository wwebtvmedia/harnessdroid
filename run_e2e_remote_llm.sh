#!/usr/bin/env bash
# E2E agent sweep against a REMOTE HTTP LLM (Ollama / OpenAI-compatible).
#
# Writes the app's llm_config into custom mode (use_tree4five=false) pointing
# at $REMOTE_URL with $REMOTE_MODEL, sanity-checks the endpoint from this host,
# then runs the instrumented E2E suites. The Gmail suite is skipped when the
# device has no Google account.
#
# Model sizing: LLMClient caps one HTTP answer at readTimeout=240s. Small and
# mid models (qwen2.5 7B class) answer well inside that; big "thinking" models
# (qwen3.8 27B) routinely exceed it, fall back to the local provider and skew
# the sweep — pick accordingly.
#
# usage: REMOTE_MODEL=fastmodel:latest run_e2e_remote_llm.sh <device-serial>
set -uo pipefail

SERIAL="${1:?usage: run_e2e_remote_llm.sh <device-serial>}"
PKG_TEST="com.ai.harnessdroid.test/androidx.test.runner.AndroidJUnitRunner"
REMOTE_URL="${REMOTE_URL:-http://192.168.1.194:11434}"
REMOTE_MODEL="${REMOTE_MODEL:-qwen-opti:latest}"

adb -s "$SERIAL" get-state >/dev/null 2>&1 || { echo "Device $SERIAL not connected" >&2; exit 2; }

# 1. The endpoint must answer an OpenAI-compatible chat completion BEFORE we
#    burn minutes on-device against a dead or overloaded server.
echo "Sanity check: $REMOTE_URL (model $REMOTE_MODEL)"
http_code=$(timeout 90 curl -s -o /tmp/remote_probe.json -w '%{http_code}' -m 85 \
  "$REMOTE_URL/v1/chat/completions" \
  -H "Content-Type: application/json" \
  -d "{\"model\":\"$REMOTE_MODEL\",\"temperature\":0,\"max_tokens\":16,\"messages\":[{\"role\":\"user\",\"content\":\"Say READY.\"}]}" || true)
if [[ "$http_code" != "200" ]]; then
  echo "FAIL: $REMOTE_URL returned HTTP ${http_code:-none} for model $REMOTE_MODEL" >&2
  exit 2
fi
grep -q '"choices"' /tmp/remote_probe.json || { echo "FAIL: unexpected payload" >&2; exit 2; }
rm -f /tmp/remote_probe.json
echo "Endpoint OK."

# 2. Custom mode ON: this is what distinguishes the remote sweep.
cat > /tmp/llm_config_remote.xml << EOF
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="use_tree4five" value="false" />
    <string name="custom_url">$REMOTE_URL</string>
    <string name="custom_api_type">OpenAI</string>
    <string name="custom_model">$REMOTE_MODEL</string>
    <string name="custom_api_key">${REMOTE_API_KEY:-}</string>
</map>
EOF
adb -s "$SERIAL" shell "am force-stop com.ai.harnessdroid" 2>/dev/null
adb -s "$SERIAL" push /tmp/llm_config_remote.xml /data/local/tmp/llm_config.xml >/dev/null
adb -s "$SERIAL" shell "run-as com.ai.harnessdroid mkdir -p shared_prefs; run-as com.ai.harnessdroid cp /data/local/tmp/llm_config.xml shared_prefs/llm_config.xml" 2>/dev/null
rm -f /tmp/llm_config_remote.xml
echo "llm_config: custom mode -> $REMOTE_URL model=$REMOTE_MODEL"

FAILED=0
run() {
  local cls="$1" log
  echo "===== $cls (remote LLM: $REMOTE_MODEL) ====="
  log=$(mktemp)
  timeout 900 adb -s "$SERIAL" shell am instrument -w -e class "$cls" "$PKG_TEST" >"$log" 2>&1
  local rc=$?
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
  echo "REMOTE LLM E2E GREEN on $SERIAL ($REMOTE_MODEL)"
else
  echo "$FAILED suite(s) FAILED (remote LLM) on $SERIAL"
fi
exit "$FAILED"

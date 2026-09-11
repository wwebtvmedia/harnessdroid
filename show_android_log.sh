#!/usr/bin/env bash
set -Eeuo pipefail

SERIAL="${1:-}"
FILTERS="${2:-LLMClient ToolRegistry AgentLoop HarnessService com.tree4five.gguf}"

if [[ -n "$SERIAL" ]]; then
  adb -s "$SERIAL" logcat -v time -T 1 -s $FILTERS
else
  adb logcat -v time -T 1 -s $FILTERS
fi

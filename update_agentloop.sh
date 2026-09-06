#!/bin/bash
sed -i 's/var toolChoice = llmClient.generateText(step1Prompt).trim()/val rawToolChoice = llmClient.generateText(step1Prompt).trim()\n            forensicLogger.logEvent("FSM_STATE_1_RESPONSE", "LLM replied: $rawToolChoice")\n            var toolChoice = rawToolChoice/g' app/src/main/java/com/ai/harnessdroid/core/AgentLoop.kt

sed -i 's/toolChoice = extractToolName(toolChoice, toolsArray)/toolChoice = extractToolName(toolChoice, toolsArray)/g' app/src/main/java/com/ai/harnessdroid/core/AgentLoop.kt


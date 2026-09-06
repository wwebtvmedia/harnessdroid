#!/bin/bash
cat << 'INNER_EOF' > app/src/main/java/com/ai/harnessdroid/core/AgentLoop.kt.tmp
INNER_EOF

# We will use sed to completely replace step1Prompt
sed -i '/val step1Prompt = """/,/""".trimIndent()/c\
            val step1Prompt = """\
<SYSTEM>\
You are HarnessDroid, an Android Agent on $osInfo.\
Your ONLY way to interact with the device is by outputting a TOOL NAME.\
\
AVAILABLE TOOLS:\
$toolSummaryList\
\
RULES:\
1. You MUST output EXACTLY ONE tool name from the list above.\
2. Do NOT output any other text. Do NOT explain.\
3. If the user wants to list tools, output: list_harness_intents\
4. If the user asks about OS/device, output: get_os_info\
5. If you absolutely do not need any tool, output: NONE\
\
EXAMPLE 1:\
User: "please list all tools accessible"\
Assistant: list_harness_intents\
\
EXAMPLE 2:\
User: "what os is this?"\
Assistant: get_os_info\
\
EXAMPLE 3:\
User: "turn on the lights"\
Assistant: SmartHomeTool\
</SYSTEM>\
\
CONVERSATION HISTORY:\
$contextStr\
\
User request requires a tool. Which tool do you choose?\
Assistant:"""\
.trimIndent()' app/src/main/java/com/ai/harnessdroid/core/AgentLoop.kt


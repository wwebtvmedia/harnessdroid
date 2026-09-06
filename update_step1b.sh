#!/bin/bash
cat << 'INNER_EOF' > app/src/main/java/com/ai/harnessdroid/core/AgentLoop.kt.tmp
INNER_EOF

sed -i '/val step1bPrompt = """/,/""".trimIndent()/c\
                val step1bPrompt = """\
<SYSTEM>\
You are HarnessDroid. You have successfully gathered information using tools.\
Look at the CONVERSATION HISTORY below to see the results from your tools.\
Your job is to synthesize these results and provide the final answer to the user.\
If the user asked for a list of tools, output the list of tools from the tool result.\
If the user asked for the OS, output the OS from the tool result.\
Do NOT talk about needing or not needing tools. Just answer the user directly.\
</SYSTEM>\
\
CONVERSATION HISTORY:\
$contextStr\
\
Provide the final answer to the user based on the tool results above:\
Assistant:"""\
.trimIndent()' app/src/main/java/com/ai/harnessdroid/core/AgentLoop.kt


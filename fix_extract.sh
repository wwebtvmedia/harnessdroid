#!/bin/bash
cat << 'INNER_EOF' > app/src/main/java/com/ai/harnessdroid/core/AgentLoop.kt.tmp
INNER_EOF

# We'll just sed it.
sed -i 's/        val upperOut = llmOutput.uppercase()/        for (i in 0 until toolsArray.length()) {\n            val name = toolsArray.getJSONObject(i).optString("name")\n            if (llmOutput.trim().equals(name, ignoreCase = true)) return name\n        }\n        for (i in 0 until toolsArray.length()) {\n            val name = toolsArray.getJSONObject(i).optString("name")\n            if (llmOutput.contains(name, ignoreCase = true)) return name\n        }\n        val upperOut = llmOutput.uppercase()/g' app/src/main/java/com/ai/harnessdroid/core/AgentLoop.kt


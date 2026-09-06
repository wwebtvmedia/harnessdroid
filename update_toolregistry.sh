#!/bin/bash
sed -i 's/        val listIntentsTool = """/        val osInfoTool = """\n            {\n                "name": "get_os_info",\n                "description": "Get device OS version, API level, and Model information.",\n                "parameters": {\n                    "type": "object",\n                    "properties": {}\n                }\n            }\n        """.trimIndent()\n        val listIntentsTool = """/g' app/src/main/java/com/ai/harnessdroid/tools/ToolRegistry.kt

sed -i 's/allSchemas.put(JSONObject(listIntentsTool))/allSchemas.put(JSONObject(listIntentsTool))\n        allSchemas.put(JSONObject(osInfoTool))/g' app/src/main/java/com/ai/harnessdroid/tools/ToolRegistry.kt

sed -i '/if (toolName == "list_harness_intents") {/i \        if (toolName == "get_os_info") {\n            val info = "Android API ${android.os.Build.VERSION.SDK_INT}, Model: ${android.os.Build.MODEL}"\n            return@withContext "{\"result\": \"$info\"}"\n        }\n' app/src/main/java/com/ai/harnessdroid/tools/ToolRegistry.kt

sed -i 's/Available intents: $available, ask_human_for_input, list_harness_intents/Available intents: $available, ask_human_for_input, list_harness_intents, get_os_info/g' app/src/main/java/com/ai/harnessdroid/tools/ToolRegistry.kt


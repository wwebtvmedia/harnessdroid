#!/bin/bash
sed -i '/open suspend fun flushLog/i \    open suspend fun clearLog() = withContext(Dispatchers.IO) {\n        _logFlow.value = emptyList()\n        if (memoryFile.exists()) memoryFile.delete()\n    }\n' app/src/main/java/com/ai/harnessdroid/core/SessionPersistence.kt

sed -i '/fun startTask/i \    fun clearLog() {\n        scope.launch {\n            sessionPersistence.clearLog()\n        }\n    }\n' app/src/main/java/com/ai/harnessdroid/core/HarnessService.kt


package com.ai.harnessdroid.python

import com.ai.harnessdroid.core.ForensicLogger
import com.ai.harnessdroid.tools.ToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Called FROM the native VM (JNI_OnLoad caches these static methods): a plan's
 * harness.call_tool() lands here while the VM thread is blocked inside
 * runBlocking. The actual tool work runs on an IO worker through the same
 * ToolRegistry.executeTool every other tool goes through, so permission
 * guards, the 6000-char result cap and forensic logging all apply unchanged.
 *
 * Re-entrancy: only the single VM thread ever calls callTool, so calls are
 * naturally serial; a nested run_python_plan would deadlock and is refused.
 */
object MicropythonBridge {
    private const val SELF_TOOL = "run_python_plan"

    /** Wired by PythonEngine; null = plans see a clean bridge-unavailable error. */
    @Volatile
    var toolRegistry: ToolRegistry? = null

    @Volatile
    var forensicLogger: ForensicLogger? = null

    /** JNI up-call: [name]/[args] are raw UTF-8, returns raw UTF-8 JSON. */
    @JvmStatic
    fun callTool(name: ByteArray, args: ByteArray): ByteArray {
        val toolName = name.toString(Charsets.UTF_8)
        val jsonArgs = args.toString(Charsets.UTF_8)
        val registry = toolRegistry
        if (registry == null) {
            return errorJson("python tool bridge is not wired to a tool registry")
        }
        if (toolName == SELF_TOOL) {
            return errorJson("run_python_plan cannot be nested inside a running plan")
        }
        val result = runBlocking {
            withContext(Dispatchers.IO) {
                try {
                    registry.executeTool(toolName, jsonArgs)
                } catch (e: Exception) {
                    "{\"error\":\"tool '$toolName' failed: ${e.message?.replace("\"", "'")}\"}"
                }
            }
        }
        return result.toByteArray(Charsets.UTF_8)
    }

    /** JNI up-call: harness.log() from a plan. */
    @JvmStatic
    fun log(line: ByteArray) {
        forensicLogger?.logEvent("PYTHON_LOG", line.toString(Charsets.UTF_8).take(500))
    }

    private fun errorJson(message: String): ByteArray =
        "{\"ok\":false,\"error\":\"$message\"}".toByteArray(Charsets.UTF_8)
}

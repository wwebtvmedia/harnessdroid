package com.ai.harnessdroid.core

import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import android.content.SharedPreferences

/**
 * Replicates the 'interaction/' package from DeepSeek Harness.
 * Gives the system a way to pause the agent and "hand over control" to the human.
 * 
 * In an embedded system, this could trigger a physical LED, send a push notification,
 * or show a dialog on a screen, asking the user to approve an intent or provide input.
 */
interface HumanInteractionHandler {
    suspend fun askForPermission(toolName: String, intentPackage: String, reason: String): Boolean
    suspend fun askUserForInput(prompt: String, defaultAnswer: String? = null, timeoutSeconds: Long = 120): String
}

class InteractionManager(private val context: Context, private val handler: HumanInteractionHandler) {

    // Persistent whitelist of already approved (tool, target package) pairs so the user isn't pestered
    // repeatedly — while still being asked again before the agent touches a different app.
    private val allowedToolsPrefs by lazy { 
        context.getSharedPreferences("harness_permissions", Context.MODE_PRIVATE) 
    }

    /**
     * Called before the ToolRegistry executes an Intent.
     * If the (tool, package) pair is not in the whitelist, it "takes the hand" and waits
     * for user approval. Approving 'launch_app' for Gmail does NOT auto-approve launching
     * any other app.
     */
    suspend fun requireIntentPermission(toolName: String, intentPackage: String, arguments: String): Boolean {
        val key = "$toolName:$intentPackage"
        if (allowedToolsPrefs.getBoolean(key, false)) {
            return true
        }

        val reason = "The agent wants to execute '$toolName' in app '$intentPackage'. This intent is not yet allowed."
        val approved = handler.askForPermission(toolName, intentPackage, reason)

        if (approved) {
            allowedToolsPrefs.edit().putBoolean(key, true).apply()
        }
        return approved
    }

    /**
     * A built-in tool that the LLM can call if it realizes it needs human input.
     */
    suspend fun requestHumanInput(prompt: String, defaultAnswer: String? = null, timeoutSeconds: Long = 120): String {
        return handler.askUserForInput(prompt, defaultAnswer, timeoutSeconds)
    }

    /**
     * Drops every approved (tool, package) pair so the human re-approves after a
     * purge. Returns how many approvals were cleared (for the forensic trail).
     */
    fun resetApprovedTools(): Int {
        val size = allowedToolsPrefs.all.size
        allowedToolsPrefs.edit().clear().apply()
        return size
    }
}

package com.ai.harnessdroid.core

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Replicates the 'interaction/' package from DeepSeek Harness.
 * Gives the system a way to pause the agent and "hand over control" to the human.
 * 
 * In an embedded system, this could trigger a physical LED, send a push notification,
 * or show a dialog on a screen, asking the user to approve an intent or provide input.
 */
interface HumanInteractionHandler {
    suspend fun askForPermission(toolName: String, intentPackage: String, reason: String): Boolean
    suspend fun askUserForInput(prompt: String): String
}

class InteractionManager(private val handler: HumanInteractionHandler) {
    
    // Whitelist of already approved tool intents so the user isn't pestered repeatedly
    private val allowedTools = mutableSetOf<String>()

    /**
     * Called before the ToolRegistry executes an Intent. 
     * If the tool is not in the whitelist, it "takes the hand" and waits for user approval.
     */
    suspend fun requireIntentPermission(toolName: String, intentPackage: String, arguments: String): Boolean {
        if (allowedTools.contains(toolName)) {
            return true
        }
        
        val reason = "The agent wants to execute '$toolName' in app '$intentPackage'. This intent is not yet allowed."
        val approved = handler.askForPermission(toolName, intentPackage, reason)
        
        if (approved) {
            allowedTools.add(toolName)
        }
        return approved
    }

    /**
     * A built-in tool that the LLM can call if it realizes it needs human input.
     */
    suspend fun requestHumanInput(prompt: String): String {
        return handler.askUserForInput(prompt)
    }
}

package com.ai.harnessdroid.llm

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class LLMClientTest {
    @Test
    fun customUrlSupportsNonDefaultPorts() {
        val client = LLMClient(null)
        assertEquals(
            "http://localhost:8080/v1/chat/completions",
            client.resolveCustomUrl("localhost:8080", "OpenAI")
        )
    }

    @Test
    fun customUrlKeepsExistingEndpointWhenAlreadySpecified() {
        val client = LLMClient(null)
        assertEquals(
            "https://example.com:5678/api/chat",
            client.resolveCustomUrl("https://example.com:5678/api/chat", "OpenAI")
        )
    }

    @Test
    fun customUrlTreatsTrailingSlashAsRootAndAppendsEndpoint() {
        // Regression: "http://host:11434/" was POSTed as-is and the server answered 404.
        val client = LLMClient(null)
        assertEquals(
            "http://192.168.1.194:11434/v1/chat/completions",
            client.resolveCustomUrl("http://192.168.1.194:11434/", "OpenAI")
        )
        assertEquals(
            "http://192.168.1.194:11434/v1/chat/completions",
            client.resolveCustomUrl("http://192.168.1.194:11434", "OpenAI")
        )
    }

    @Test
    fun customFailureFallsBackToLocalProvider() = runBlocking {
        val client = object : LLMClient(null) {
            override suspend fun generateViaCustom(settings: CustomLLMSettings, prompt: String): String =
                "Error: Custom LLM API returned HTTP 404"
            override suspend fun generateViaLocal(prompt: String): String = "LOCAL_OK"
        }
        val settings = LLMClient.CustomLLMSettings("http://192.168.1.194:11434", "", "OpenAI", "llama3")
        assertEquals("LOCAL_OK", client.generateWithSettings("hello", settings))
    }

    @Test
    fun customSuccessDoesNotTouchLocalProvider() = runBlocking {
        val client = object : LLMClient(null) {
            override suspend fun generateViaCustom(settings: CustomLLMSettings, prompt: String): String = "REMOTE_OK"
            override suspend fun generateViaLocal(prompt: String): String =
                throw AssertionError("local provider must not be called when the remote works")
        }
        val settings = LLMClient.CustomLLMSettings("http://192.168.1.194:11434", "", "OpenAI", "llama3")
        assertEquals("REMOTE_OK", client.generateWithSettings("hello", settings))
    }

    @Test
    fun remoteErrorSurfacedWhenLocalAlsoFails() = runBlocking {
        val client = object : LLMClient(null) {
            override suspend fun generateViaCustom(settings: CustomLLMSettings, prompt: String): String =
                "Error connecting to Custom LLM: Failed to connect"
            override suspend fun generateViaLocal(prompt: String): String =
                throw java.lang.IllegalStateException("provider not installed")
        }
        val settings = LLMClient.CustomLLMSettings("http://192.168.1.194:11434", "", "OpenAI", "llama3")
        assertEquals(
            "Error connecting to Custom LLM: Failed to connect",
            client.generateWithSettings("hello", settings)
        )
    }

    @Test
    fun nullSettingsUsesLocalProviderOnly() = runBlocking {
        val client = object : LLMClient(null) {
            override suspend fun generateViaCustom(settings: CustomLLMSettings, prompt: String): String =
                throw AssertionError("custom endpoint must not be called in local mode")
            override suspend fun generateViaLocal(prompt: String): String = "LOCAL_OK"
        }
        assertEquals("LOCAL_OK", client.generateWithSettings("hello", null))
    }
}

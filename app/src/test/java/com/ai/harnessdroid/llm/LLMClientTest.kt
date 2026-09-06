package com.ai.harnessdroid.llm

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
}

package com.ai.harnessdroid.core

import com.ai.harnessdroid.llm.LLMClient
import com.ai.harnessdroid.memory.VectorStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.json.JSONArray
import org.json.JSONObject

/**
 * Embedding-mode behaviour of the AgentLoop:
 *  1. dim > 0: history chunks are embedded+stored, FSM calls receive a latent
 *     prefix, and the raw history text no longer appears in any prompt.
 *  2. dim = -1 (remote LLM / old provider): the loop stays entirely on the
 *     text path and never calls the embedding APIs.
 */
class AgentLoopEmbdTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @After
    fun cleanup() {
        System.clearProperty("HARNESS_CONTEXT_MODE")
        System.clearProperty("HARNESS_MAX_CONTEXT_CHARS")
        System.clearProperty("HARNESS_USE_MOCK_LLM")
    }

    private fun bigHistory(): MutableList<SessionEvent> = mutableListOf(
        SessionEvent("user", "What is the OS info of this device?"),
        // ~2520 chars -> 3 chunks of ~900 chars for the embedder.
        SessionEvent("tool", ("os_information: Android 15, build AP4A.250105.002, " +
            "security patch 2025-01-05, kernel 6.1.75, tablet SM-P610, uptime 41h. ").repeat(30), toolName = "get_os_info")
    )

    /** Persistence that always loads a large pre-existing history. */
    class BigHistoryPersistence(initial: List<SessionEvent>) : SessionPersistence(null as android.content.Context?, "test") {
        private val memory = initial.toMutableList()
        var flushed = 0
        override suspend fun flushLog(log: List<SessionEvent>) {
            flushed++
            val copy = log.toList()   // log may BE memory: snapshot before clearing
            memory.clear()
            memory.addAll(copy)
        }
        override suspend fun loadLog(): MutableList<SessionEvent> = memory
    }

    class FakeEmbeddingLLM(private val dim: Int) : LLMClient(null as android.content.Context?) {
        val embdFollowups = mutableListOf<String>()
        val embdVectorSizes = mutableListOf<Int>()
        val textPrompts = mutableListOf<String>()
        var textGenerations = 0
        private var sawTool = false

        override suspend fun embeddingDim(): Int = dim

        override suspend fun embedText(text: String): FloatArray? {
            if (dim <= 0) return null
            // Content-dependent (not just length): repeated chunks must land far
            // apart so the store's near-duplicate skip does not kick in.
            return FloatArray(dim) { i -> (((text.hashCode() * 31 + i * 17) % 13) - 6) / 6f }
        }

        override suspend fun generateFromEmbeddings(
            vectors: FloatArray,
            count: Int?,
            followupPrompt: String,
            nPredict: Int,
            temperature: Float
        ): String? {
            embdFollowups.add(followupPrompt)
            embdVectorSizes.add(vectors.size)
            return fsmAnswer(followupPrompt)
        }

        override suspend fun generateText(prompt: String): String {
            textGenerations++
            textPrompts.add(prompt)
            return fsmAnswer(prompt)
        }

        /** Same FSM contract as the real loop expects; terminates after one tool call. */
        private fun fsmAnswer(prompt: String): String = when {
            prompt.contains("which tool do you choose") -> {
                if (!sawTool) {
                    sawTool = true
                    "harness have to use mockTool"
                } else {
                    "NONE"
                }
            }
            prompt.contains("JSON object containing the arguments") -> "{ \"testArg\": \"val\" }"
            else -> "Final Answer"
        }
    }

    private fun mockToolRegistry() = object : com.ai.harnessdroid.tools.ToolRegistry(null as android.content.Context?, null) {
        var executions = 0
        override suspend fun discoverAndBindTools(): String {
            val tool = JSONObject().apply {
                put("name", "mockTool")
                put("description", "A mock tool that does mock things")
            }
            return JSONArray().put(tool).toString()
        }
        override suspend fun executeTool(toolName: String, jsonArgs: String): String {
            executions++
            return "{\"result\": \"success\"}"
        }
    }

    @Test
    fun `embd mode injects latent prefix and elides history text`() = runBlocking {
        System.setProperty("HARNESS_CONTEXT_MODE", "embd")
        System.setProperty("HARNESS_MAX_CONTEXT_CHARS", "200")
        val store = VectorStore(tmp.newFolder(), "test")
        val llm = FakeEmbeddingLLM(dim = 896)
        val persistence = BigHistoryPersistence(bigHistory())
        val loop = AgentLoop(llm, mockToolRegistry(), persistence, ForensicLoggerMock(), store)

        val result = loop.runTask("What is the OS info of this device?", maxTurns = 5)

        assertEquals("Final Answer", result)
        // The FSM calls went through the latent path.
        assertTrue("no embeddings generation happened", llm.embdFollowups.isNotEmpty())
        // Retrieval returned the stored chunks (user event + tool chunks),
        // each a dim-896 vector, flattened into one float array. Later turns
        // may ingest more chunks (e.g. the tool result after elision), so the
        // final store size is only bounded below.
        assertTrue("store stayed small: ${store.size}", store.size >= 4)
        assertEquals(4 * 896, llm.embdVectorSizes[0])
        // The raw history content disappeared from the latent followups...
        llm.embdFollowups.forEach { followup ->
            assertFalse(followup.contains("AP4A.250105.002"))
            assertTrue(followup.contains("[HISTORY:"))
        }
        // ...and no text prompt ever carried the history payload.
        llm.textPrompts.forEach { prompt ->
            assertFalse(prompt.contains("security patch 2025-01-05"))
        }
        // The store received the embedded chunks.
        assertTrue("store stayed empty", store.size > 0)
        assertTrue("flush at end of task", persistence.flushed > 0)
    }

    @Test
    fun `embd mode falls back to text path when dim is -1`() = runBlocking {
        System.setProperty("HARNESS_CONTEXT_MODE", "embd")
        System.setProperty("HARNESS_MAX_CONTEXT_CHARS", "200")
        val store = VectorStore(tmp.newFolder(), "test")
        val llm = FakeEmbeddingLLM(dim = -1)
        val persistence = BigHistoryPersistence(bigHistory())
        val loop = AgentLoop(llm, mockToolRegistry(), persistence, ForensicLoggerMock(), store)

        val result = loop.runTask("What is the OS info of this device?", maxTurns = 5)

        assertEquals("Final Answer", result)
        assertTrue("embedding APIs must not be called when dim=-1", llm.embdFollowups.isEmpty())
        assertEquals(0, store.size)
        // The text path compressed the history (ACH): the FSM prompts carry the summary.
        assertTrue(llm.textGenerations > 0)
    }

    @Test
    fun `text mode never touches embeddings`() = runBlocking {
        System.setProperty("HARNESS_CONTEXT_MODE", "text")
        System.setProperty("HARNESS_MAX_CONTEXT_CHARS", "200")
        val store = VectorStore(tmp.newFolder(), "test")
        val llm = FakeEmbeddingLLM(dim = 896)
        val persistence = BigHistoryPersistence(bigHistory())
        val loop = AgentLoop(llm, mockToolRegistry(), persistence, ForensicLoggerMock(), store)

        val result = loop.runTask("What is the OS info of this device?", maxTurns = 5)

        assertEquals("Final Answer", result)
        assertTrue(llm.embdFollowups.isEmpty())
        assertEquals(0, store.size)
    }
}

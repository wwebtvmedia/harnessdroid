package com.ai.harnessdroid

import android.content.Intent
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ai.harnessdroid.core.AgentLoop
import com.ai.harnessdroid.core.ForensicLogger
import com.ai.harnessdroid.core.ScreenReaderService
import com.ai.harnessdroid.core.SessionPersistence
import com.ai.harnessdroid.llm.LLMClient
import com.ai.harnessdroid.tools.ToolRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Full end-to-end: HARNESS DROID (the agent loop + the LLM) must do the job.
 * The test only establishes ground truth (what the most recent Gmail message
 * looks like on the inbox screen), hands the device back to a neutral state,
 * then asks the agent to summarize the latest email and checks that the final
 * answer actually reflects that message.
 *
 * Requires on the device: the ScreenReaderService enabled (see MultiAppScreenTest
 * header), the LLM provider (com.tree4five.gguf) installed, and a Gmail account
 * with at least one message in the inbox.
 */
@RunWith(AndroidJUnit4::class)
class SummarizeLastMailE2ETest {

    /** Gmail chrome texts that are never part of a message. */
    private val gmailChrome = setOf(
        "Left pane", "Open navigation drawer", "Search in mail", "Signed in as",
        "Inbox", "Compose", "Meet", "Image", "Mail,", "Update", "Menu"
    )

    /**
     * Waits for the accessibility bridge. `am instrument` recreates the app
     * process, which the OS logs as a service crash and refuses to rebind until
     * the enabled-services setting is toggled again. On this device that toggle
     * only sticks when issued from adb AFTER the test process is up, so the test
     * simply waits here while the operator (or the runner script) toggles:
     *   adb shell am force-stop com.ai.harnessdroid
     *   adb shell settings delete secure enabled_accessibility_services
     *   adb shell settings put secure enabled_accessibility_services \
     *     com.ai.harnessdroid/com.ai.harnessdroid.core.ScreenReaderService
     *   adb shell settings put secure accessibility_enabled 1
     */
    private fun awaitScreenReader() {
        val deadline = System.currentTimeMillis() + 45_000
        while (!ScreenReaderService.isReady() && System.currentTimeMillis() < deadline) {
            Thread.sleep(500)
        }
        assertTrue(
            "ScreenReaderService did not connect within 45s",
            ScreenReaderService.isReady()
        )
    }

    /** A Gmail message row always ends with its date ("Sep 13", "Aug 29").
     *  Matching on that beats scanning for the "Inbox" header: Gmail's a11y
     *  tree can emit the header near the END of the dump, which made an older
     *  parser pick a screenshot filename instead of the newest message. */
    private val dateLike = Regex("(?i)(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?\\s*\\d{1,2}")

    private fun newestMessageRow(screen: String): String? = screen.lines()
        .map { it.replace(Regex("\\[\\d+,\\d+\\]\\[\\d+,\\d+\\]"), "").trim() }
        .filter { it.length >= 3 }
        .firstOrNull { line ->
            gmailChrome.none { chrome -> line.startsWith(chrome, ignoreCase = true) } &&
                dateLike.containsMatchIn(line) &&
                // A bare "Aug 29" node (group header) is not a message row.
                Regex("[A-Za-z]{4,}").containsMatchIn(line)
        }

    /**
     * Opens Gmail via the registry and returns the visible text of the FIRST
     * message row (sender + subject + snippet). This is ground truth only —
     * the agent does the actual job later in the test.
     *
     * When run inside the full suite, earlier UI tests can leave Gmail showing
     * an opened message or a dialog (no message row). HOME + relaunch is then
     * retried a few times before failing.
     */
    private fun firstVisibleMailText(registry: ToolRegistry): String = runBlocking {
        awaitScreenReader()
        registry.discoverAndBindTools()
        var screen = ""
        var first: String? = null
        for (attempt in 1..3) {
            val launched = registry.executeTool(
                "launch_app",
                JSONObject().put("app_name", "Gmail").toString()
            )
            assertTrue(
                "launch_app(Gmail) failed: $launched",
                launched.contains("Successfully launched com.google.android.gm")
            )
            Thread.sleep(3000)
            val read = registry.executeTool("read_screen", "{}")
            assertTrue("read_screen returned an error: $read", read.contains("\"screen\""))
            screen = JSONObject(read).optString("screen")
            first = newestMessageRow(screen)
            if (first != null) break
            // Earlier tests (or a previous run) can leave Gmail showing an
            // OPENED conversation; launch_app then restores it instead of the
            // inbox. Go back out of the message before relaunching.
            val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
            repeat(2) {
                ui.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                Thread.sleep(800)
            }
            appContextHome()
            Thread.sleep(1200)
        }
        assertTrue(
            "Could not find a message row in the Gmail dump:\n$screen",
            first != null && first!!.length >= 4
        )
        first!!
    }

    private fun appContextHome() {
        InstrumentationRegistry.getInstrumentation().targetContext.startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    @Test
    fun agentSummarizesTheLastMail() = runBlocking<Unit> {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val registry = ToolRegistry(appContext, null) // null: permissions auto-approved

        // 1. Ground truth: what does the newest inbox row actually say?
        val newest = firstVisibleMailText(registry)
        val keywords = Regex("[A-Za-z]{4,}").findAll(newest)
            .map { it.value.lowercase() }
            .filter { it !in setOf("gmail", "inbox", "unread", "google", "mail") }
            .toMutableSet()
        // The visible row mixes sender, subject and snippet: keep a few strong ones.
        assertTrue("No usable keywords in newest mail row: $newest", keywords.isNotEmpty())
        Log.d("MailE2E", "Ground truth newest mail: $newest keywords=$keywords")

        // 2. Neutral state: the agent must navigate by itself from HOME.
        appContext.startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        Thread.sleep(1500)

        // 3. The harness does the job.
        val llmClient = LLMClient(appContext)
        val persistence = SessionPersistence(appContext, "mail_summary_e2e")
        // Fresh transcript: one test run == one task, like a real user task.
        // Reusing the tag across runs would otherwise replay every previous
        // (failed) attempt into the context and bury the instruction.
        persistence.clearLog()
        val logger = object : ForensicLogger(appContext) {
            override fun logEvent(tag: String, message: String) {
                Log.d("MailE2E", "[$tag] $message")
            }
        }
        val agent = AgentLoop(llmClient, registry, persistence, logger)
        val result = agent.runTask(
            "Open my Gmail inbox and summarize my most recent email: who sent it and what does it say?",
            maxTurns = 10
        )
        Log.d("MailE2E", "Agent final result: $result")

        // 4. The answer must be a real answer, not a loop failure.
        assertTrue("Agent returned an empty result", result.isNotBlank())
        assertTrue(
            "Agent hit the turn limit instead of summarizing: $result",
            !result.contains("Maximum turns reached", ignoreCase = true)
        )

        // 5. The answer must reflect the actual newest message.
        val matched = keywords.count { kw -> result.lowercase().contains(kw) }
        assertTrue(
            "Summary does not mention the newest mail (\"$newest\"). Agent said: $result",
            matched >= 1
        )
    }
}

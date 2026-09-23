package com.ai.harnessdroid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.io.File

/**
 * JVM tests of the Multi-Think System-2 state (MULTITHINK_DESIGN.md, scenario A):
 * goal attachment, verbatim injection block, turn journal, bounded consolidation,
 * crash-safe parsing and reload.
 */
class GoalStackTest {

    private fun newStack(dir: File, sessionId: String = "gs_test") =
        GoalStack(dir, sessionId, GoalLogger())

    @Test
    fun `first task becomes the standing goal, next ones become missions`() {
        val dir = Files.createTempDirectory("goalstack").toFile()
        val stack = newStack(dir)

        val first = stack.attachTask("Watch the mailbox for the refund email", maxTurns = 6)
        assertTrue(first.contains("<GOAL>Watch the mailbox for the refund email</GOAL>"))
        // A goal alone is not a mission: no mission block on first attach.
        assertFalse(first.contains("<MISSION>"))

        val second = stack.attachTask("Check the latest email now", maxTurns = 4)
        assertTrue(second.contains("<GOAL>Watch the mailbox for the refund email</GOAL>"))
        assertTrue(second.contains("<MISSION>Check the latest email now</MISSION>"))
    }

    @Test
    fun `prompt block is empty when no goal was ever set`() {
        val stack = newStack(Files.createTempDirectory("goalstack").toFile())
        assertEquals("", stack.promptBlock())
        // recordTurn before any goal is a no-op, not a crash.
        stack.recordTurn("launch_app", ok = true, brief = "nothing set yet")
    }

    @Test
    fun `goal injection block is capped to the design budget`() {
        val dir = Files.createTempDirectory("goalstack").toFile()
        val stack = newStack(dir)
        stack.attachTask("G".repeat(5000), maxTurns = 3)
        val block = stack.promptBlock()
        assertTrue("goal capped at 500 chars, got ${block.length}", block.length <= 520)
    }

    @Test
    fun `recordTurn journals one brief per turn with a rolling cap`() {
        val dir = Files.createTempDirectory("goalstack").toFile()
        val stack = newStack(dir)
        stack.attachTask("Long running goal", maxTurns = 2)

        repeat(20) { i -> stack.recordTurn("tool_$i", ok = i % 2 == 0, brief = "step $i") }

        val raw = File(dir, "goalstack/gs_test.json").readText()
        assertTrue("journal kept rolling, not 20 entries: $raw", raw.split("step ").size - 1 <= 12)
        assertTrue(raw.contains("FAIL tool_1"))
    }

    @Test
    fun `valid consolidation refreshes card, facts and mission`() = runBlocking {
        val dir = Files.createTempDirectory("goalstack").toFile()
        val stack = newStack(dir)
        stack.attachTask("Track the refund", maxTurns = 4)
        stack.recordTurn("read_screen", ok = true, brief = "inbox shown")
        stack.recordTurn("web_search", ok = false, brief = "timeout")

        var capturedPrompt = ""
        stack.consolidate { prompt ->
            capturedPrompt = prompt
            """{"achieved":false,"card":"refund not found yet; inbox checked",""" +
                """"mission_done":true,"next_mission":"search the spam folder",""" +
                """"facts":["refund from ACME pending"],"reason":"progress noted"}"""
        }

        assertTrue("briefs must be sent to S2", capturedPrompt.contains("inbox shown"))
        val raw = File(dir, "goalstack/gs_test.json").readText()
        assertTrue(raw.contains("refund not found yet; inbox checked"))
        assertTrue(raw.contains("search the spam folder"))
        assertTrue(raw.contains("refund from ACME pending"))
        assertTrue(raw.contains("TRACK THE REFUND") || raw.contains("Track the refund"))
    }

    @Test
    fun `unparseable consolidation leaves the state untouched`() = runBlocking {
        val dir = Files.createTempDirectory("goalstack").toFile()
        val stack = newStack(dir)
        stack.attachTask("Fragile goal", maxTurns = 2)
        val before = File(dir, "goalstack/gs_test.json").readText()

        stack.consolidate { "I think we are fine, no JSON today." }
        assertEquals(before, File(dir, "goalstack/gs_test.json").readText())

        // Code-fenced JSON is tolerated: the object must still parse.
        stack.consolidate {
            "```json\n{\"achieved\":false,\"card\":\"ok\",\"mission_done\":false," +
                "\"next_mission\":\"\",\"facts\":[],\"reason\":\"fenced\"}\n```"
        }
        assertTrue(File(dir, "goalstack/gs_test.json").readText().contains("\"ok\""))
    }

    @Test
    fun `consolidation stops at the per-session cap`() = runBlocking {
        val dir = Files.createTempDirectory("goalstack").toFile()
        val stack = newStack(dir)
        stack.attachTask("Capped goal", maxTurns = 1)

        var calls = 0
        val reply = """{"achieved":false,"card":"c","mission_done":false,""" +
            """"next_mission":"","facts":[],"reason":"r"}"""
        repeat(GoalStack.MAX_S2_PER_SESSION + 3) {
            stack.consolidate { prompt -> calls++; reply }
        }
        assertEquals(GoalStack.MAX_S2_PER_SESSION, calls)
    }

    @Test
    fun `goal marked achieved clears the stack for the next task`() = runBlocking {
        val dir = Files.createTempDirectory("goalstack").toFile()
        val stack = newStack(dir)
        stack.attachTask("Buy milk", maxTurns = 2)

        stack.consolidate {
            """{"achieved":true,"card":"","mission_done":true,""" +
                """"next_mission":"","facts":[],"reason":"milk bought"}"""
        }
        assertEquals("", stack.promptBlock())
        // The next task starts a FRESH goal, not a mission under a dead one.
        val next = stack.attachTask("Walk the dog", maxTurns = 2)
        assertTrue(next.contains("<GOAL>Walk the dog</GOAL>"))
        assertFalse(next.contains("<MISSION>"))
    }

    @Test
    fun `state survives a reload from disk`() {
        val dir = Files.createTempDirectory("goalstack").toFile()
        val first = newStack(dir)
        first.attachTask("Persistent goal", maxTurns = 3)
        first.recordTurn("read_screen", ok = true, brief = "kept across restarts")

        val reloaded = newStack(dir)
        val block = reloaded.promptBlock()
        assertTrue(block.contains("<GOAL>Persistent goal</GOAL>"))
        assertTrue(reloaded.promptBlock().isNotEmpty())
    }

    // -- editing API for the Goal & Mission dialog and the closed goal loop ----

    @Test
    fun `setGoal establishes a fresh goal and snapshot reflects it`() {
        val dir = Files.createTempDirectory("goalstack").toFile()
        val stack = newStack(dir)

        stack.setGoal("Audit the sync settings")
        assertTrue(stack.hasGoal())
        assertTrue(stack.promptBlock().contains("<GOAL>Audit the sync settings</GOAL>"))

        stack.setMission("Open the sync screen", maxTurns = 4)
        val snap = stack.snapshot()
        assertEquals("Audit the sync settings", snap.goal)
        assertEquals("Open the sync screen", snap.mission)
        assertTrue(stack.currentMission() == "Open the sync screen")
        assertTrue(stack.currentCard().isNotEmpty())
    }

    @Test
    fun `replacing the goal resets the derived state`() = runBlocking {
        val dir = Files.createTempDirectory("goalstack").toFile()
        val stack = newStack(dir)
        stack.setGoal("Old goal")
        stack.setMission("Old mission", maxTurns = 3)
        stack.recordTurn("read_screen", ok = true, brief = "brief under the old goal")

        stack.setGoal("Brand new goal")
        val snap = stack.snapshot()
        assertEquals("Brand new goal", snap.goal)
        // the new goal's card is reseeded from itself, not from the old card
        assertTrue(snap.card.contains("Brand new goal"))
        // old mission is stale for a new goal: dropped from the injection block
        assertFalse(stack.promptBlock().contains("<MISSION>Old mission</MISSION>"))
    }

    @Test
    fun `setMission caps length and empty goal edit is a no-op`() {
        val dir = Files.createTempDirectory("goalstack").toFile()
        val stack = newStack(dir)
        stack.setMission("M".repeat(4000), maxTurns = 2)
        assertTrue("mission capped at 300", stack.currentMission().length <= 300)

        // editing an empty goal must not create a goal out of thin air
        stack.setGoal("   ")
        assertFalse(stack.hasGoal())
    }
}

private class GoalLogger : ForensicLogger(null as android.content.Context?) {
    override fun logEvent(tag: String, message: String) {}
}

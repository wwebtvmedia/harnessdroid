package com.ai.harnessdroid.core

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Closed goal loop: the runner must chain the missions produced by the S2
 * consolidation pass and stop on every budget. The injected runTask replays
 * exactly what the real AgentLoop does per task: attachTask → recordTurn →
 * consolidate (scripted replies standing in for the LLM).
 */
class AutonomousMissionRunnerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class GoalLogger : ForensicLogger(null as android.content.Context?)

    private fun newStack(session: String = "auto-${System.nanoTime()}") =
        GoalStack(tmp.root, session, GoalLogger())

    /** Scripted S2 replies, consumed in order. */
    private fun consolidateReplies(vararg json: String) = object : Iterator<String> {
        var i = 0
        override fun hasNext() = i < json.size
        override fun next(): String = json[i++]
    }

    /**
     * Builds a runTask lambda standing in for AgentLoop.runTask: each call files
     * the instruction as a mission, journals one turn, then consolidates with the
     * next scripted S2 reply.
     */
    private fun fakeRunTask(
        goalStack: GoalStack,
        replies: Iterator<String>,
        ranMissions: MutableList<String>,
        maxTurns: Int = 6,
    ): suspend (String) -> String = { instruction ->
        ranMissions.add(instruction)
        goalStack.attachTask(instruction, maxTurns)
        goalStack.recordTurn("run_python_plan", true, "did one thing")
        goalStack.consolidate { _ -> replies.next() }
        "task result"
    }

    private fun summary(result: JSONObject, key: String) = result.getString(key)

    // -- chaining ---------------------------------------------------------------

    @Test
    fun chainsNextMissionsUntilNoneLeft() = runBlocking {
        val goalStack = newStack()
        goalStack.setGoal("Collect the three logs from the device")
        goalStack.setMission("Fetch log A", maxTurns = 6)
        val ran = mutableListOf<String>()
        val runner = AutonomousMissionRunner(
            goalStack,
            fakeRunTask(goalStack, consolidateReplies(
                // after mission A: A done, next is B
                """{"achieved":false,"card":"A done","mission_done":true,"next_mission":"Fetch log B","facts":[],"reason":"ok"}""",
                // after mission B: B done, nothing left
                """{"achieved":false,"card":"A+B done","mission_done":true,"next_mission":"","facts":[],"reason":"done"}""",
            ), ran),
            sleep = { /* no pause in tests */ },
        )
        val result = runner.run(AutonomousMissionRunner.Config(cooldownMs = 0))
        assertEquals(listOf("Fetch log A", "Fetch log B"), ran)
        assertEquals("NO_NEXT_MISSION", summary(result, "stop_reason"))
        assertEquals(2, result.getInt("missions_run"))
        // the standing goal survives: only the missions were consumed
        assertTrue(goalStack.hasGoal())
    }

    @Test
    fun stopsOnGoalAchieved() = runBlocking {
        val goalStack = newStack()
        goalStack.setGoal("Reply to Alice")
        goalStack.setMission("Draft the reply", maxTurns = 6)
        val ran = mutableListOf<String>()
        val runner = AutonomousMissionRunner(
            goalStack,
            fakeRunTask(goalStack, consolidateReplies(
                """{"achieved":true,"card":"","mission_done":true,"next_mission":"","facts":[],"reason":"sent"}""",
            ), ran),
        )
        val result = runner.run(AutonomousMissionRunner.Config(cooldownMs = 0))
        assertEquals("GOAL_COMPLETE", summary(result, "stop_reason"))
        assertEquals(1, ran.size)
        // achieved → the stack is cleared so the next user task starts a fresh goal
        assertFalse(goalStack.hasGoal())
    }

    // -- budgets ----------------------------------------------------------------

    @Test
    fun missionBudgetCapsConsecutiveMissions() = runBlocking {
        val goalStack = newStack()
        goalStack.setGoal("Endless goal")
        goalStack.setMission("Step", maxTurns = 6)
        val ran = mutableListOf<String>()
        // every task produces a next mission — only the budget stops the loop
        val perpetual = """{"achieved":false,"card":"c","mission_done":true,"next_mission":"Step","facts":[],"reason":"r"}"""
        val runner = AutonomousMissionRunner(
            goalStack,
            fakeRunTask(goalStack, object : Iterator<String> {
                override fun hasNext() = true
                override fun next() = perpetual
            }, ran),
        )
        val result = runner.run(AutonomousMissionRunner.Config(maxMissions = 3, cooldownMs = 0))
        assertEquals(3, ran.size)
        assertEquals("MISSION_BUDGET", summary(result, "stop_reason"))
    }

    @Test
    fun timeBudgetStopsMidChain() = runBlocking {
        val goalStack = newStack()
        goalStack.setGoal("Endless goal")
        goalStack.setMission("Step", maxTurns = 6)
        val perpetual = """{"achieved":false,"card":"c","mission_done":true,"next_mission":"Step","facts":[],"reason":"r"}"""
        // fake clock advanced by the injected sleep, so the cooldown eats the budget
        var now = 0L
        val runner = AutonomousMissionRunner(
            goalStack,
            fakeRunTask(goalStack, object : Iterator<String> {
                override fun hasNext() = true
                override fun next() = perpetual
            }, mutableListOf()),
            clock = { now },
            sleep = { ms -> now += ms },
        )
        val result = runner.run(AutonomousMissionRunner.Config(maxMissions = AutonomousMissionRunner.Config.MAX_MISSIONS_CAP, budgetMs = 100_000, cooldownMs = 60_000))
        assertEquals("TIME_BUDGET", summary(result, "stop_reason"))
        assertTrue("expected a few missions, got ${result.getInt("missions_run")}", result.getInt("missions_run") < 5)
    }

    @Test
    fun cooldownPausesBetweenMissions() = runBlocking {
        val goalStack = newStack()
        goalStack.setGoal("Two steps")
        goalStack.setMission("Step one", maxTurns = 6)
        val pauses = mutableListOf<Long>()
        val runner = AutonomousMissionRunner(
            goalStack,
            fakeRunTask(goalStack, consolidateReplies(
                """{"achieved":false,"card":"c","mission_done":true,"next_mission":"Step two","facts":[],"reason":"r"}""",
                """{"achieved":false,"card":"c","mission_done":true,"next_mission":"","facts":[],"reason":"r"}""",
            ), mutableListOf()),
            sleep = { ms -> pauses.add(ms) },
        )
        runner.run(AutonomousMissionRunner.Config(cooldownMs = 45_000))
        // exactly one pause: between the two chained missions
        assertEquals(listOf(45_000L), pauses)
    }

    // -- entry conditions -------------------------------------------------------

    @Test
    fun noGoalMeansNoAutonomousRun() = runBlocking {
        val goalStack = newStack()
        var called = 0
        val runner = AutonomousMissionRunner(goalStack, { _ -> called++; "never" })
        val result = runner.run(AutonomousMissionRunner.Config(cooldownMs = 0))
        assertEquals(0, called)
        assertEquals("NO_NEXT_MISSION", summary(result, "stop_reason"))
        assertEquals(0, result.getInt("missions_run"))
    }

    @Test
    fun runTaskFailureStopsTheLoop() = runBlocking {
        val goalStack = newStack()
        goalStack.setGoal("Fragile goal")
        goalStack.setMission("Crashing mission", maxTurns = 6)
        val runner = AutonomousMissionRunner(
            goalStack,
            { _ -> throw IllegalStateException("provider gone") },
        )
        val result = runner.run(AutonomousMissionRunner.Config(cooldownMs = 0))
        assertEquals("RUNTASK_ERROR", summary(result, "stop_reason"))
        assertEquals(1, result.getInt("missions_run"))
        // the goal state is intact: the user can retry after fixing the cause
        assertTrue(goalStack.hasGoal())
    }

    @Test
    fun configRejectsOutOfRangeValues() {
        try {
            AutonomousMissionRunner.Config(maxMissions = 0)
            throw AssertionError("maxMissions=0 must be rejected")
        } catch (_: IllegalArgumentException) { /* expected */ }
        try {
            AutonomousMissionRunner.Config(budgetMs = 500)
            throw AssertionError("sub-minute budget must be rejected")
        } catch (_: IllegalArgumentException) { /* expected */ }
    }
}

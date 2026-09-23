package com.ai.harnessdroid.core

import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * Closes the Multi-Think goal loop: chains the missions the System-2
 * consolidation pass produces ([GoalStack.consolidate] writes `next_mission`;
 * nothing used to pick it up) into consecutive [runTask] invocations, until the
 * goal reports achieved, a mission comes back empty, or a budget trips.
 *
 * Pure JVM: the task execution is injected as a lambda so the chaining logic is
 * unit-testable without Android. Every runTask call runs the FULL existing
 * pipeline (FSM prompts, tool approvals, per-task consolidate), so budgets are
 * the only new guardrails:
 *  - [Config.maxMissions]: hard ceiling on consecutive missions per goal;
 *  - [Config.budgetMs]: wall-clock ceiling on one autonomous run;
 *  - [Config.cooldownMs]: pause between missions (bounded by what's left).
 */
class AutonomousMissionRunner(
    private val goalStack: GoalStack,
    private val runTask: suspend (String) -> String,
    private val logger: ForensicLogger? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {

    data class Config(
        val maxMissions: Int = DEFAULT_MAX_MISSIONS,
        val budgetMs: Long = DEFAULT_BUDGET_MS,
        val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    ) {
        init {
            require(maxMissions in 1..MAX_MISSIONS_CAP) { "maxMissions out of range" }
            require(budgetMs in 1_000..MAX_BUDGET_MS) { "budgetMs out of range" }
            require(cooldownMs in 0..MAX_COOLDOWN_MS) { "cooldownMs out of range" }
        }

        companion object {
            const val DEFAULT_MAX_MISSIONS = 8
            const val MAX_MISSIONS_CAP = 20
            const val DEFAULT_BUDGET_MS = 30 * 60_000L      // 30 min of continuous work
            const val MAX_BUDGET_MS = 2 * 60 * 60_000L
            const val DEFAULT_COOLDOWN_MS = 30_000L
            const val MAX_COOLDOWN_MS = 10 * 60_000L
        }
    }

    /** Runs the closed loop; returns a summary JSON for the caller's log/UI. */
    suspend fun run(config: Config = Config()): JSONObject {
        val startedMs = clock()
        var missionsRun = 0
        var stopReason = "NO_GOAL"

        while (true) {
            val mission = goalStack.currentMission().trim()
            if (mission.isEmpty()) { stopReason = "NO_NEXT_MISSION"; break }
            if (missionsRun >= config.maxMissions) { stopReason = "MISSION_BUDGET"; break }
            if (clock() - startedMs >= config.budgetMs) { stopReason = "TIME_BUDGET"; break }

            missionsRun++
            logger?.logEvent("AUTO_MISSION_START", "#$missionsRun/${config.maxMissions} ${mission.take(80)}")
            try {
                // attachTask files this as a mission under the standing goal and
                // the task-end consolidate refreshes card/next_mission.
                runTask(mission)
            } catch (e: Exception) {
                logger?.logEvent("AUTO_MISSION_ERROR", "#$missionsRun ${e.message ?: "unknown"}")
                stopReason = "RUNTASK_ERROR"
                break
            }
            // consolidate() cleared the stack: the goal reports achieved.
            if (!goalStack.hasGoal()) { stopReason = "GOAL_COMPLETE"; break }
            // Nothing left to chain: the goal needs new user input.
            if (goalStack.currentMission().trim().isEmpty()) { stopReason = "NO_NEXT_MISSION"; break }
            if (missionsRun >= config.maxMissions) { stopReason = "MISSION_BUDGET"; break }
            val remainMs = config.budgetMs - (clock() - startedMs)
            if (remainMs <= 0) { stopReason = "TIME_BUDGET"; break }
            if (config.cooldownMs > 0) sleep(minOf(config.cooldownMs, remainMs))
        }

        logger?.logEvent("AUTO_RUN_DONE", "missions=$missionsRun reason=$stopReason")
        return JSONObject()
            .put("missions_run", missionsRun)
            .put("stop_reason", stopReason)
            .put("elapsed_ms", clock() - startedMs)
    }

    companion object {
        val STOP_GOAL_COMPLETE = "GOAL_COMPLETE"
        val STOP_NO_NEXT = "NO_NEXT_MISSION"
        val STOP_MISSION_BUDGET = "MISSION_BUDGET"
        val STOP_TIME_BUDGET = "TIME_BUDGET"
        val STOP_RUNTASK_ERROR = "RUNTASK_ERROR"
    }
}

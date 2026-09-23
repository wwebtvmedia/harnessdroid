package com.ai.harnessdroid.core

import android.content.Context

/**
 * User-configured number of agent loop turns per task (AgentLoop.runTask's
 * maxTurns), persisted in "loop_prefs" so the choice survives restarts.
 * Read at every task start (HarnessService.startTask), so a change made in the
 * Loop Settings dialog applies to the next Run without a service restart.
 */
object AgentLoopSettings {
    private const val PREFS = "loop_prefs"
    private const val KEY_MAX_TURNS = "max_turns"

    /** Matches the historical hardcoded default of AgentLoop.runTask. */
    const val DEFAULT = 10
    const val MIN = 1
    const val MAX = 50

    /** Clamps any user input into the accepted range; pure, so JVM tests can call it. */
    fun clamp(value: Int): Int = value.coerceIn(MIN, MAX)

    fun load(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_MAX_TURNS, DEFAULT).let(::clamp)

    fun save(context: Context, maxTurns: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_MAX_TURNS, clamp(maxTurns)).apply()
    }

    // -- Closed goal loop (AutonomousMissionRunner) --------------------------
    // Off by default: autonomous chaining only starts when the user flips it.

    private const val KEY_AUTONOMOUS = "autonomous_enabled"
    private const val KEY_AUTO_MAX_MISSIONS = "auto_max_missions"
    private const val KEY_AUTO_BUDGET_MS = "auto_budget_ms"
    private const val KEY_AUTO_COOLDOWN_MS = "auto_cooldown_ms"

    const val AUTO_MAX_MISSIONS_DEFAULT = AutonomousMissionRunner.Config.DEFAULT_MAX_MISSIONS
    const val AUTO_BUDGET_MS_DEFAULT = AutonomousMissionRunner.Config.DEFAULT_BUDGET_MS
    const val AUTO_COOLDOWN_MS_DEFAULT = AutonomousMissionRunner.Config.DEFAULT_COOLDOWN_MS

    fun clampAutoMissions(value: Int): Int =
        value.coerceIn(1, AutonomousMissionRunner.Config.MAX_MISSIONS_CAP)

    fun clampAutoBudgetMs(value: Long): Long =
        value.coerceIn(60_000L, AutonomousMissionRunner.Config.MAX_BUDGET_MS)

    fun clampAutoCooldownMs(value: Long): Long =
        value.coerceIn(0L, AutonomousMissionRunner.Config.MAX_COOLDOWN_MS)

    fun isAutonomousEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTONOMOUS, false)

    fun setAutonomousEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTONOMOUS, enabled).apply()
    }

    fun loadAutoMissions(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_AUTO_MAX_MISSIONS, AUTO_MAX_MISSIONS_DEFAULT).let(::clampAutoMissions)

    fun saveAutoMissions(context: Context, missions: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_AUTO_MAX_MISSIONS, clampAutoMissions(missions)).apply()
    }

    fun loadAutoBudgetMs(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_AUTO_BUDGET_MS, AUTO_BUDGET_MS_DEFAULT).let(::clampAutoBudgetMs)

    fun loadAutoCooldownMs(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_AUTO_COOLDOWN_MS, AUTO_COOLDOWN_MS_DEFAULT).let(::clampAutoCooldownMs)
}

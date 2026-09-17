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
}

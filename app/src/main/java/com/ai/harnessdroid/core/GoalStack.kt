package com.ai.harnessdroid.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Multi-Think "System 2" state (MULTITHINK_DESIGN.md, scenario A): the long-lived
 * goal of a session plus the short-lived mission currently driving the FSM.
 *
 * The AgentLoop is System 1: fast, per-turn, reactive. This class holds the two
 * things the sliding context must never lose:
 *  - the goal, kept VERBATIM: its completion criterion must survive every
 *    compression pass word for word (a paraphrased criterion drifts);
 *  - a mission card, refreshed once per task by the System-2 consolidation pass
 *    that folds the turn briefs into "what is done / what is left".
 *
 * Persisted as plain JSON under <baseDir>/goalstack/<sessionId>.json (atomic
 * tmp+rename), same JVM /tmp fallback as SessionPersistence. A failed
 * consolidation never mutates the state: a degraded System 2 must not corrupt
 * the System 1 it supervises.
 */
class GoalStack(baseDir: File?, sessionId: String, private val forensicLogger: ForensicLogger? = null) {

    private val stateFile: File =
        File(baseDir ?: File("/tmp"), "goalstack/$sessionId.json")

    private var goal: String = ""
    private var card: String = ""
    private var mission: String = ""
    private var missionTurnsLeft: Int = 0
    private val facts = mutableListOf<String>()
    private val openTurnBriefs = mutableListOf<String>()
    private var s2Calls: Int = 0

    init {
        load()
    }

    /**
     * Registers a user task against the goal stack and returns the prompt block to
     * inject ahead of every FSM call. The first instruction of a session becomes
     * the standing goal (verbatim); later instructions are missions under it.
     */
    fun attachTask(instruction: String, maxTurns: Int): String {
        val clean = instruction.trim()
        if (clean.isEmpty()) return promptBlock()
        if (goal.isEmpty()) {
            goal = clean
            card = clean.take(CARD_MAX_CHARS)
            forensicLogger?.logEvent("GOAL_SET", "goal=${clean.take(80)}")
        } else {
            mission = clean.take(MISSION_MAX_CHARS)
            missionTurnsLeft = maxTurns
            forensicLogger?.logEvent("GOAL_CARRIED", "mission=${clean.take(80)} under standing goal")
        }
        save()
        return promptBlock()
    }

    /** `<GOAL>/<MISSION>` block injected ahead of every FSM prompt; "" when idle. */
    fun promptBlock(): String {
        val sb = StringBuilder()
        if (goal.isNotEmpty()) sb.append("<GOAL>").append(goal.take(GOAL_MAX_CHARS)).append("</GOAL>\n")
        if (mission.isNotEmpty()) sb.append("<MISSION>").append(mission.take(MISSION_MAX_CHARS)).append("</MISSION>\n")
        return sb.toString()
    }

    /** One System-1 turn happened: journal a one-line brief for the next S2 pass. */
    fun recordTurn(tool: String, ok: Boolean, brief: String) {
        if (goal.isEmpty()) return
        openTurnBriefs.add("${if (ok) "ok" else "FAIL"} $tool: ${brief.take(BRIEF_MAX_CHARS)}")
        while (openTurnBriefs.size > MAX_OPEN_BRIEFS) openTurnBriefs.removeAt(0)
        if (missionTurnsLeft > 0) missionTurnsLeft--
        save()
    }

    /**
     * System-2 pass, run once at the end of a task: the LLM folds the turn briefs
     * into a refreshed card/mission. Bounded by [MAX_S2_PER_SESSION]; a parse
     * failure leaves the state untouched.
     */
    suspend fun consolidate(generate: suspend (String) -> String) {
        if (goal.isEmpty()) return
        if (s2Calls >= MAX_S2_PER_SESSION) {
            forensicLogger?.logEvent("S2_CONSOLIDATE_SKIP", "session cap $MAX_S2_PER_SESSION reached")
            return
        }
        s2Calls++
        forensicLogger?.logEvent("S2_CONSOLIDATE_START", "briefs=${openTurnBriefs.size}")
        val raw = try {
            generate(buildConsolidatePrompt())
        } catch (e: Exception) {
            forensicLogger?.logEvent("S2_CONSOLIDATE_ERROR", e.message ?: "generation failed")
            return
        }
        val obj = extractJsonObject(raw) ?: run {
            forensicLogger?.logEvent("S2_CONSOLIDATE_PARSE_ERROR", "unparseable reply: ${raw.take(120)}")
            return
        }
        if (obj.optBoolean("achieved")) {
            // The standing goal is done: drop everything so the next task of the
            // session becomes a fresh goal instead of a mission under a dead one.
            clear()
            forensicLogger?.logEvent("S2_GOAL_COMPLETE", obj.optString("reason").take(120))
            return
        }
        obj.optString("card").trim().takeIf { it.isNotEmpty() }?.let { card = it.take(CARD_MAX_CHARS) }
        obj.optJSONArray("facts")?.let { arr ->
            facts.clear()
            for (i in 0 until arr.length()) {
                if (facts.size >= MAX_FACTS) break
                val f = arr.optString(i).trim().take(FACT_MAX_CHARS)
                if (f.isNotEmpty()) facts.add(f)
            }
        }
        val missionDone = obj.optBoolean("mission_done")
        val nextMission = obj.optString("next_mission").trim()
        if (missionDone) {
            mission = nextMission.take(MISSION_MAX_CHARS)
            missionTurnsLeft = 0
        }
        // The journal has been digested into the card: start a fresh one.
        openTurnBriefs.clear()
        save()
        forensicLogger?.logEvent(
            "S2_CONSOLIDATE_OK",
            "mission_done=$missionDone facts=${facts.size} reason=${obj.optString("reason").take(120)}"
        )
    }

    /** Drops goal, card, mission and journal (purge, or a completed goal). */
    @Synchronized
    fun clear() {
        goal = ""
        card = ""
        mission = ""
        missionTurnsLeft = 0
        facts.clear()
        openTurnBriefs.clear()
        s2Calls = 0
        save()
    }

    // -- Closed goal loop (AutonomousMissionRunner) and UI editing ------------

    /** Immutable view of the S2 state for the UI and the autonomous runner. */
    data class Snapshot(
        val goal: String,
        val card: String,
        val mission: String,
        val facts: List<String>,
        val s2Calls: Int,
    )

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(
        goal = goal, card = card, mission = mission,
        facts = facts.toList(), s2Calls = s2Calls,
    )

    @Synchronized fun hasGoal(): Boolean = goal.isNotEmpty()
    @Synchronized fun currentGoal(): String = goal
    @Synchronized fun currentMission(): String = mission
    @Synchronized fun currentCard(): String = card

    /**
     * UI/runner edition of the standing goal. Replacing a non-empty goal resets
     * the derived state: the old card, journal AND mission belong to the old goal.
     */
    @Synchronized
    fun setGoal(text: String): String {
        val clean = text.trim().take(GOAL_MAX_CHARS)
        if (clean.isEmpty()) return promptBlock()
        val replaced = goal.isNotEmpty()
        goal = clean
        if (replaced) {
            card = clean.take(CARD_MAX_CHARS)
            facts.clear()
            openTurnBriefs.clear()
            mission = ""
            missionTurnsLeft = 0
        } else if (card.isEmpty()) {
            card = clean.take(CARD_MAX_CHARS)
        }
        forensicLogger?.logEvent("GOAL_SET", "goal=${clean.take(80)}${if (replaced) " (replaced)" else ""}")
        save()
        return promptBlock()
    }

    /** UI/runner edition of the current mission (under the standing goal). */
    @Synchronized
    fun setMission(text: String, maxTurns: Int) {
        mission = text.trim().take(MISSION_MAX_CHARS)
        missionTurnsLeft = maxTurns
        forensicLogger?.logEvent("MISSION_SET", "mission=${mission.take(80)}")
        save()
    }

    private fun buildConsolidatePrompt(): String {
        val briefs = if (openTurnBriefs.isEmpty()) "(no tool ran this task)"
        else openTurnBriefs.joinToString("\n") { "- $it" }
        return """
<SYSTEM>
You are the slow-thinking supervisor (System 2) of an Android agent. The fast loop
(System 1) just finished a task. Fold what happened into the standing goal card.
Reply with ONE JSON object and nothing else, with exactly these keys:
{"achieved": true|false,      - is the standing GOAL now fully achieved?
 "card": "...",               - refreshed working card, <=300 chars, what is done and what is left
 "mission_done": true|false,  - is the current MISSION finished?
 "next_mission": "...",       - next concrete mission serving the goal ("" when none)
 "facts": ["...", "..."],     - up to 3 durable facts worth keeping, <=120 chars each
 "reason": "..."}             - one line explaining the update
</SYSTEM>

<GOAL>
$goal
</GOAL>

<MISSION>
${if (mission.isNotEmpty()) mission else "(none - the task itself was the goal)"}
</MISSION>

<CARD>
${if (card.isNotEmpty()) card else "(empty)"}
</CARD>

<TURN_BRIEFS>
$briefs
</TURN_BRIEFS>

<OUTPUT>
""".trimIndent()
    }

    /** Tolerant extraction: models wrap JSON in prose or code fences. */
    private fun extractJsonObject(raw: String): JSONObject? = try {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) null else JSONObject(raw.substring(start, end + 1))
    } catch (_: Exception) {
        null
    }

    @Synchronized
    private fun save() {
        try {
            stateFile.parentFile?.mkdirs()
            val obj = JSONObject()
                .put("goal", goal)
                .put("card", card)
                .put("mission", mission)
                .put("missionTurnsLeft", missionTurnsLeft)
                .put("s2Calls", s2Calls)
            val factsArr = JSONArray()
            facts.forEach { factsArr.put(it) }
            obj.put("facts", factsArr)
            val briefsArr = JSONArray()
            openTurnBriefs.forEach { briefsArr.put(it) }
            obj.put("openTurnBriefs", briefsArr)
            val tmp = File(stateFile.parentFile, stateFile.name + ".tmp")
            tmp.writeText(obj.toString())
            if (!tmp.renameTo(stateFile)) {
                stateFile.writeText(obj.toString())
                tmp.delete()
            }
        } catch (e: Exception) {
            forensicLogger?.logEvent("GOALSTACK_SAVE_ERROR", e.message ?: "unknown")
        }
    }

    @Synchronized
    private fun load() {
        if (!stateFile.exists()) return
        try {
            val obj = JSONObject(stateFile.readText())
            goal = obj.optString("goal")
            card = obj.optString("card")
            mission = obj.optString("mission")
            missionTurnsLeft = obj.optInt("missionTurnsLeft", 0)
            s2Calls = obj.optInt("s2Calls", 0)
            facts.clear()
            obj.optJSONArray("facts")?.let { arr ->
                for (i in 0 until arr.length()) facts.add(arr.optString(i))
            }
            openTurnBriefs.clear()
            obj.optJSONArray("openTurnBriefs")?.let { arr ->
                for (i in 0 until arr.length()) openTurnBriefs.add(arr.optString(i))
            }
        } catch (e: Exception) {
            forensicLogger?.logEvent("GOALSTACK_LOAD_ERROR", "resetting state: ${e.message}")
            goal = ""
            card = ""
            mission = ""
        }
    }

    companion object {
        private const val GOAL_MAX_CHARS = 500
        private const val CARD_MAX_CHARS = 300
        private const val MISSION_MAX_CHARS = 300
        private const val BRIEF_MAX_CHARS = 160
        private const val MAX_OPEN_BRIEFS = 12
        private const val MAX_FACTS = 3
        private const val FACT_MAX_CHARS = 120

        /** Hard ceiling on slow-thinking (System 2) LLM passes per session. */
        const val MAX_S2_PER_SESSION = 10
    }
}

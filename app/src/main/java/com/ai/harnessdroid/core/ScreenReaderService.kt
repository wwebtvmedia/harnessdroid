package com.ai.harnessdroid.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Accessibility bridge that gives the agent eyes and hands on the screen.
 *
 * The AgentLoop can launch apps, but without this service it can never SEE what
 * an app displays (e.g. the inbox in Gmail), which made any "read my mail" task
 * impossible. The service is bound by the OS once the user enables it under
 * Settings > Accessibility; ToolRegistry talks to it through the static
 * [instance] while it is connected.
 */
class ScreenReaderService : AccessibilityService() {

    companion object {
        @Volatile
        private var instance: ScreenReaderService? = null

        fun isReady(): Boolean = instance != null

        /** Cap the dump so one tool result can never blow up a small LLM's context. */
        private const val MAX_NODES = 120
        private const val MAX_CHARS = 6000

        /**
         * Walks the active window tree and renders every visible, text-bearing node
         * as one line: "text [left,top][right,bottom]". The bounds let a follow-up
         * tap_screen call target the same element.
         */
        fun readScreen(): String {
            val service = instance
                ?: return "Error: accessibility service not connected. Enable 'Harness Droid Screen Reader' in Settings > Accessibility, then retry."

            // Right after launch_app the new activity is still inflating and
            // rootInActiveWindow is null: poll briefly instead of failing the turn.
            var root = service.rootInActiveWindow
            var waited = 0
            while (root == null && waited < 2400) {
                Thread.sleep(400)
                waited += 400
                root = service.rootInActiveWindow
            }
            if (root == null) {
                return "Error: no active window content available. Launch an app first, then retry read_screen."
            }

            val sb = StringBuilder()
            var count = 0
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            while (queue.isNotEmpty() && count < MAX_NODES && sb.length < MAX_CHARS) {
                val node = queue.removeFirst()
                val text = node.text?.toString()?.trim().orEmpty()
                val desc = node.contentDescription?.toString()?.trim().orEmpty()
                if (text.isNotEmpty() || desc.isNotEmpty()) {
                    val bounds = Rect().also { node.getBoundsInScreen(it) }
                    val label = if (text.isNotEmpty()) text else desc
                    if (bounds.width() > 0 && bounds.height() > 0) {
                        sb.appendLine("$label [${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]")
                        count++
                    }
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            }
            if (sb.isBlank()) return "Error: screen has no readable text (maybe a lock screen or an image-only view)."
            return sb.toString().trim()
        }

        /** Taps at screen coordinates; suspends until the gesture is dispatched. */
        suspend fun tap(x: Int, y: Int): Boolean {
            val service = instance ?: return false
            return service.dispatchClick(x, y)
        }

        /**
         * Clicks the first on-screen node whose text contains `text` (case-insensitive),
         * e.g. tapElement("GOT IT") or a mail row. Clicking by label is far more reliable
         * for a small LLM than picking the center of printed bounds.
         */
        suspend fun tapElement(text: String): Boolean {
            val service = instance ?: return false
            val root = service.rootInActiveWindow ?: return false
            val needle = text.trim().lowercase()
            if (needle.isEmpty()) return false

            // Tiny LLMs paraphrase labels ("Veuilez confirmer votre inscription" for a
            // row actually labelled "... votre demande d'inscription"): after the exact
            // pass fails, retry letting ANY significant word of the needle match.
            val words = needle.split(Regex("[^a-z0-9àâäéèêëîïôöùûüç]+"))
                .filter { it.length >= 4 }
                .toSet()

            fun matches(label: String, desc: String, fullOnly: Boolean): Boolean {
                if (label.contains(needle) || desc.contains(needle)) return true
                if (fullOnly) return false
                return words.any { label.contains(it) || desc.contains(it) }
            }

            for (fullOnly in listOf(true, false)) {
                val queue = ArrayDeque<AccessibilityNodeInfo>()
                queue.add(root)
                var visited = 0
                while (queue.isNotEmpty() && visited < MAX_NODES * 2) {
                    val node = queue.removeFirst()
                    visited++
                    val label = (node.text?.toString() ?: "").lowercase()
                    val desc = (node.contentDescription?.toString() ?: "").lowercase()
                    if (matches(label, desc, fullOnly)) {
                        // Prefer the clickable ancestor so containers (list rows, buttons) react.
                        var target: AccessibilityNodeInfo? = node
                        while (target != null && !target.isClickable) {
                            target = target.parent
                        }
                        val finalTarget = target ?: node
                        if (finalTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                        // Some views expose no clickable flag (nor a clickable ancestor) yet
                        // still respond to a real touch: dispatch a gesture at the matched
                        // node's center instead of reporting the element as missing.
                        val bounds = Rect().also { node.getBoundsInScreen(it) }
                        if (bounds.width() > 0 && bounds.height() > 0) {
                            if (tap(bounds.exactCenterX().toInt(), bounds.exactCenterY().toInt())) return true
                        }
                    }
                    for (i in 0 until node.childCount) {
                        node.getChild(i)?.let { queue.add(it) }
                    }
                }
            }
            return false
        }

        /** Swipes from (x1,y1) to (x2,y2); used for scrolling lists like an inbox. */
        suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int): Boolean {
            val service = instance ?: return false
            return service.dispatchSwipe(x1, y1, x2, y2)
        }

        /** Opens the settings page where the user enables this service. */
        fun openSettings(context: android.content.Context) {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        // Not needed: readScreen() polls the tree on demand instead of streaming events.
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private suspend fun dispatchClick(x: Int, y: Int): Boolean = suspendCancellableCoroutine { cont ->
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        val delivered = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(false)
            }
        }, null)
        if (!delivered && cont.isActive) cont.resume(false)
    }

    private suspend fun dispatchSwipe(x1: Int, y1: Int, x2: Int, y2: Int): Boolean = suspendCancellableCoroutine { cont ->
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        val delivered = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(false)
            }
        }, null)
        if (!delivered && cont.isActive) cont.resume(false)
    }
}

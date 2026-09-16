package com.ai.harnessdroid.ui

import android.content.Context
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag

/**
 * Text zoom: a density multiplier persisted in "ui_prefs" so the chosen size
 * survives app restarts (rotation keeps the Activity alive, so it only needs
 * the in-memory value). Applied in MainActivity via LocalDensity: every `sp`
 * dimension — chat bubbles, input field, buttons — scales together while the
 * layout metrics (dp) stay untouched.
 */
object UiZoom {
    private const val PREFS = "ui_prefs"
    private const val KEY_TEXT_SCALE = "text_scale"

    const val MIN = 0.7f
    const val MAX = 2.5f
    /** Factor applied per A+/A- tap and used as the clamp step for pinch. */
    const val BUTTON_STEP = 1.2f

    fun load(context: Context): Float =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(KEY_TEXT_SCALE, 1f).coerceIn(MIN, MAX)

    fun save(context: Context, scale: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_TEXT_SCALE, scale.coerceIn(MIN, MAX)).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_TEXT_SCALE).apply()
    }
}

/**
 * Pinch-to-zoom-text on a container. Runs on the Initial pass so it sees the
 * pointers BEFORE the scrollable child: once two fingers are down and actually
 * zooming, the events are consumed and the LazyColumn does not scroll under
 * the gesture. Single-finger scrolling is never intercepted (no consumption
 * while fewer than two pointers are pressed).
 */
fun Modifier.textPinchZoom(onZoom: (factor: Float) -> Unit): Modifier = this.pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressedPointers = event.changes.count { it.pressed }
            val zoom = event.calculateZoom()
            if (pressedPointers >= 2 && zoom != 1f) {
                onZoom(zoom)
                event.changes.forEach { it.consume() }
            }
        } while (event.changes.any { it.pressed })
    }
}

/**
 * Zoom/pan state of a [ZoomableImage], kept outside the composable so tests
 * (and callers) can read the exact values.
 */
class ZoomState {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set

    /** Returns the clamped scale actually applied. */
    internal fun apply(zoomFactor: Float, pan: Offset): Float {
        val newScale = (scale * zoomFactor).coerceIn(MIN_SCALE, MAX_SCALE)
        scale = newScale
        offset = if (newScale > 1f) offset + pan else Offset.Zero
        return newScale
    }

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }

    companion object {
        const val MIN_SCALE = 0.5f
        const val MAX_SCALE = 5f
    }
}

/**
 * Generic pinch-to-zoom container for photos/screenshots: two-finger pinch
 * scales between 0.5x and 5x, panning is enabled while zoomed in, and a
 * double-tap snaps back to 1x.
 */
@Composable
fun ZoomableImage(
    modifier: Modifier = Modifier,
    state: ZoomState = remember { ZoomState() },
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("zoomable_image")
            .clipToBounds()
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { state.reset() })
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    state.apply(zoom, pan)
                }
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = state.scale
                    scaleY = state.scale
                    translationX = state.offset.x
                    translationY = state.offset.y
                }
        ) {
            content()
        }
    }
}

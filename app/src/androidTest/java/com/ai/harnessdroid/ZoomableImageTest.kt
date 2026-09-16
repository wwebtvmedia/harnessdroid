package com.ai.harnessdroid

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ai.harnessdroid.ui.ZoomState
import com.ai.harnessdroid.ui.ZoomableImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pinch/pan/double-tap behaviour of ZoomableImage — the component any photo
 * or screenshot shown by the app goes through. The ZoomState is passed in so
 * the exact scale/offset values can be asserted instead of pixel colors.
 */
@RunWith(AndroidJUnit4::class)
class ZoomableImageTest {

    @get:Rule
    val rule = createComposeRule()

    private fun setContentWith(state: ZoomState) {
        rule.setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                ZoomableImage(state = state, modifier = Modifier.size(300.dp)) {
                    Text("photo content under zoom")
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun pinchOutZoomsIn_pinchInZoomsOut() {
        val state = ZoomState()
        setContentWith(state)
        assertEquals(1f, state.scale, 0.0001f)

        // Spread two fingers apart: zoom in.
        rule.onNodeWithTag("zoomable_image").performTouchInput {
            pinch(
                start0 = center - Offset(60f, 0f),
                end0 = center - Offset(150f, 0f),
                start1 = center + Offset(60f, 0f),
                end1 = center + Offset(150f, 0f)
            )
        }
        rule.waitForIdle()
        assertTrue("pinch-out must zoom in (scale=${state.scale})", state.scale > 1f)

        // Pinch back tighter than the start: back to (or below) neutral.
        rule.onNodeWithTag("zoomable_image").performTouchInput {
            pinch(
                start0 = center - Offset(150f, 0f),
                end0 = center - Offset(40f, 0f),
                start1 = center + Offset(150f, 0f),
                end1 = center + Offset(40f, 0f)
            )
        }
        rule.waitForIdle()
        assertTrue("pinch-in must zoom out (scale=${state.scale})", state.scale < 1.5f)
    }

    @Test
    fun doubleTapResetsZoom() {
        val state = ZoomState()
        setContentWith(state)
        rule.onNodeWithTag("zoomable_image").performTouchInput {
            pinch(
                start0 = center - Offset(60f, 0f),
                end0 = center - Offset(150f, 0f),
                start1 = center + Offset(60f, 0f),
                end1 = center + Offset(150f, 0f)
            )
        }
        rule.waitForIdle()
        assertTrue(state.scale > 1f)

        rule.onNodeWithTag("zoomable_image").performTouchInput { doubleClick(center) }
        rule.waitForIdle()
        assertEquals("double-tap must snap back to 1x", 1f, state.scale, 0.0001f)
        assertEquals(Offset.Zero, state.offset)
    }

    @Test
    fun zoomIsClampedToBounds() {
        val state = ZoomState()
        setContentWith(state)
        // Far more pinch-out than the 5x ceiling.
        repeat(4) {
            rule.onNodeWithTag("zoomable_image").performTouchInput {
                pinch(
                    start0 = center - Offset(80f, 0f),
                    end0 = center - Offset(180f, 0f),
                    start1 = center + Offset(80f, 0f),
                    end1 = center + Offset(180f, 0f)
                )
            }
        }
        rule.waitForIdle()
        assertTrue("scale must never exceed MAX_SCALE (got ${state.scale})", state.scale <= ZoomState.MAX_SCALE)
        assertTrue("repeated pinch-out must saturate near MAX_SCALE (got ${state.scale})", state.scale > 2f)
    }

    @Test
    fun panMovesOnlyWhenZoomed() {
        val state = ZoomState()
        setContentWith(state)

        // At 1x a drag must not pan the content.
        rule.onNodeWithTag("zoomable_image").performTouchInput { swipeRight() }
        rule.waitForIdle()
        assertEquals("content at 1x must not pan", Offset.Zero, state.offset)

        // Zoom in, then drag: the offset must follow the pan.
        rule.onNodeWithTag("zoomable_image").performTouchInput {
            pinch(
                start0 = center - Offset(60f, 0f),
                end0 = center - Offset(150f, 0f),
                start1 = center + Offset(60f, 0f),
                end1 = center + Offset(150f, 0f)
            )
        }
        rule.waitForIdle()
        val zoomed = state.scale
        assertTrue(zoomed > 1f)
        rule.onNodeWithTag("zoomable_image").performTouchInput { swipeRight() }
        rule.waitForIdle()
        assertNotEquals("zoomed content must pan with the drag", Offset.Zero, state.offset)
    }
}

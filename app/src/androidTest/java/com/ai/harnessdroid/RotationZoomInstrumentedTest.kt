package com.ai.harnessdroid

import android.content.pm.ActivityInfo
import android.os.SystemClock
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ai.harnessdroid.ui.MainActivity
import com.ai.harnessdroid.ui.UiZoom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Rotation & text-zoom guarantees:
 *  - rotating the device must NOT recreate MainActivity (manifest
 *    android:configChanges), so the in-progress window state — the text typed
 *    in the request field, open dialogs, service bindings — survives intact;
 *  - the A+/A- controls must change the persisted text scale.
 */
@RunWith(AndroidJUnit4::class)
class RotationZoomInstrumentedTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun rotationKeepsTypedTextAndActivityInstance() {
        val scenario = rule.activityRule.scenario
        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        rule.waitForIdle()
        SystemClock.sleep(400)

        var firstInstance: MainActivity? = null
        scenario.onActivity { firstInstance = it }

        val typed = "rotation must not lose me"
        rule.onNodeWithTag("request_input").performTextInput(typed)
        rule.waitForIdle()

        // Portrait -> landscape: with configChanges this dispatches
        // onConfigurationChanged and keeps the very same Activity instance.
        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        rule.waitForIdle()
        SystemClock.sleep(600)

        var rotatedInstance: MainActivity? = null
        scenario.onActivity { rotatedInstance = it }
        assertSame(
            "configChanges must prevent Activity recreation on rotation",
            firstInstance, rotatedInstance
        )
        rule.onNodeWithTag("request_input").assertTextContains(typed)

        // And back to portrait: state still intact.
        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        rule.waitForIdle()
        SystemClock.sleep(600)
        var finalInstance: MainActivity? = null
        scenario.onActivity { finalInstance = it }
        assertSame("second rotation must not recreate the Activity either", firstInstance, finalInstance)
        rule.onNodeWithTag("request_input").assertTextContains(typed)
    }

    @Test
    fun rotationKeepsTextZoomApplied() {
        val scenario = rule.activityRule.scenario
        // Isolation: the text scale is persisted across app launches, so start
        // from a clean slate and let onCreate re-read the neutral value.
        scenario.onActivity { activity -> UiZoom.clear(activity) }
        scenario.recreate()
        rule.waitForIdle()

        rule.onNodeWithTag("zoom_in").performClick()
        rule.waitForIdle()

        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        rule.waitForIdle()
        SystemClock.sleep(600)

        // The zoom state lives on the (non-recreated) Activity: it must still
        // be > 1x after rotating, and the scale must come back exactly after
        // shrinking back down (clamped bounds respected).
        scenario.onActivity { activity ->
            val saved = activity.getSharedPreferences("ui_prefs", android.content.Context.MODE_PRIVATE)
                .getFloat("text_scale", 1f)
            assertTrue("zoom should have been persisted before rotation (got $saved)", saved > 1f)
        }
        rule.onNodeWithTag("zoom_out").performClick()
        rule.waitForIdle()
        scenario.onActivity { activity ->
            val saved = activity.getSharedPreferences("ui_prefs", android.content.Context.MODE_PRIVATE)
                .getFloat("text_scale", 1f)
            assertEquals("A- must restore the neutral scale", 1f, saved, 0.0001f)
        }

        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        rule.waitForIdle()
        SystemClock.sleep(400)

        // Cleanup: leave a neutral zoom for the app's next launch.
        scenario.onActivity { activity -> UiZoom.clear(activity) }
    }
}

package com.ai.harnessdroid

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ai.harnessdroid.ui.MainActivity
import com.ai.harnessdroid.ui.LocaleManager
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Language submenu, end to end: open the overflow menu, switch to French,
 * let the activity recreate and assert the UI re-resolved against the pinned
 * locale; then restore the system default so the device is left clean.
 */
@RunWith(AndroidJUnit4::class)
class LanguageMenuInstrumentedTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun languageSwitchTranslatesTheWholeUi() {
        // Driving real clicks needs an interactive display; on a dozing device
        // the compose hierarchy never attaches, so skip instead of failing.
        val ui = androidx.test.uiautomator.UiDevice.getInstance(
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        )
        org.junit.Assume.assumeTrue(
            "device screen is off — test needs an interactive display",
            ui.isScreenOn
        )

        // Clean slate: follow the system locale (device under test is English).
        LocaleManager.save(compose.activity, "")
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithText("Language").performClick()

        // The submenu lists every supported language in its own name.
        compose.onNodeWithText("English").assertExists()
        compose.onNodeWithText("Français").assertExists()
        compose.onNodeWithText("中文").assertExists()
        compose.onNodeWithText("العربية").assertExists()

        compose.onNodeWithText("Français").performClick()
        // recreate() runs: wait until the Clear button renders in French.
        compose.waitUntil(10_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("Effacer"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Effacer").assertExists()
        compose.onNodeWithText("Exécuter").assertExists()
        assertEquals("fr", LocaleManager.load(compose.activity))

        // Restore the system default through the same path.
        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithText("Langue").performClick()
        compose.onNodeWithText("Valeur du système").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("Clear"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals("", LocaleManager.load(compose.activity))
    }
}

package com.ai.harnessdroid

import androidx.test.platform.app.InstrumentationRegistry
import com.ai.harnessdroid.core.ScreenReaderService
import com.ai.harnessdroid.tools.ToolRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Non-regression sweep across real device applications: the agent must be able to
 * LAUNCH each app, SEE what it shows (read_screen) and ACT on it (tap/swipe).
 *
 * Screen tools need the ScreenReaderService to be bound by the OS. Enable it once
 * before the suite:
 *   adb shell settings put secure enabled_accessibility_services \
 *     com.ai.harnessdroid/com.ai.harnessdroid.core.ScreenReaderService
 *   adb shell settings put secure accessibility_enabled 1
 */
class MultiAppScreenTest {

    private fun makeRegistry(): ToolRegistry {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // null InteractionManager: no human-in-the-loop popup, permission auto-approved
        return ToolRegistry(context, null)
    }

    /**
     * `am instrument` restarts the app process; the OS then treats the accessibility
     * service as crashed and stops binding it until it is toggled again. Re-enable it
     * from the test through UiAutomation (shell uid may write secure settings).
     */
    private fun reEnableScreenReaderViaShell() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val pfd = instrumentation.uiAutomation.executeShellCommand(
            "settings delete secure enabled_accessibility_services; " +
                "settings put secure enabled_accessibility_services " +
                "com.ai.harnessdroid/com.ai.harnessdroid.core.ScreenReaderService; " +
                "settings put secure accessibility_enabled 1"
        )
        try { pfd.close() } catch (_: Exception) {}
    }

    /** Waits for the accessibility bridge, re-enabling it once if the OS dropped it. */
    private fun awaitScreenReader() {
        var deadline = System.currentTimeMillis() + 4_000
        while (!ScreenReaderService.isReady() && System.currentTimeMillis() < deadline) {
            Thread.sleep(500)
        }
        if (!ScreenReaderService.isReady()) {
            reEnableScreenReaderViaShell()
            deadline = System.currentTimeMillis() + 15_000
            while (!ScreenReaderService.isReady() && System.currentTimeMillis() < deadline) {
                Thread.sleep(500)
            }
        }
        assertTrue(
            "ScreenReaderService did not connect within 15s even after re-enabling it",
            ScreenReaderService.isReady()
        )
    }

    private suspend fun launchAndRead(registry: ToolRegistry, appName: String, vararg expectedPkgs: String): String {
        awaitScreenReader()
        // Peers the app-label routing table: without it launch_app can't resolve names.
        registry.discoverAndBindTools()
        val launched = registry.executeTool("launch_app", JSONObject().put("app_name", appName).toString())
        // Samsung devices ship their own Contacts/Clock/etc., so any of the
        // expected packages proves the launch worked.
        assertTrue(
            "launch_app($appName) failed: $launched",
            expectedPkgs.any { launched.contains("Successfully launched $it") }
        )
        // Give the app a moment to render its first frame before reading the screen.
        Thread.sleep(2500)
        val read = registry.executeTool("read_screen", "{}")
        assertTrue("read_screen after $appName returned an error: $read", read.contains("\"screen\""))
        return read
    }

    // ---- Screen-reading across applications ----

    @Test
    fun gmailInboxScreenIsReadable() = runBlocking<Unit> {
        val read = launchAndRead(makeRegistry(), "Gmail", "com.google.android.gm")
        // A fresh emulator without an account shows the Gmail welcome/upgrade screen:
        // any rendered text proves the agent can see inside Gmail.
        assertTrue("Screen dump too small: $read", read.length > 60)
    }

    @Test
    fun chromeScreenIsReadable() = runBlocking<Unit> {
        launchAndRead(makeRegistry(), "Chrome", "com.android.chrome")
    }

    @Test
    fun youtubeScreenIsReadable() = runBlocking<Unit> {
        launchAndRead(makeRegistry(), "YouTube", "com.google.android.youtube")
    }

    @Test
    fun mapsScreenIsReadable() = runBlocking<Unit> {
        launchAndRead(makeRegistry(), "Maps", "com.google.android.apps.maps")
    }

    @Test
    fun settingsScreenIsReadable() = runBlocking<Unit> {
        val read = launchAndRead(makeRegistry(), "Settings", "com.android.settings")
        // The settings app always renders titles such as the top-level list.
        assertTrue("Settings screen had no readable content: $read", read.length > 60)
    }

    @Test
    fun photosScreenIsReadable() = runBlocking<Unit> {
        // Play Store has no launcher activity on emulator images: Photos stands in
        // as the second media-heavy Google app to read.
        launchAndRead(makeRegistry(), "Photos", "com.google.android.apps.photos")
    }

    @Test
    fun contactsScreenIsReadable() = runBlocking<Unit> {
        launchAndRead(makeRegistry(), "Contacts", "com.google.android.contacts", "com.samsung.android.app.contacts")
    }

    @Test
    fun clockScreenIsReadable() = runBlocking<Unit> {
        launchAndRead(makeRegistry(), "Clock", "com.google.android.deskclock", "com.sec.android.app.clockpackage")
    }

    // ---- Screen service plumbing ----

    @Test
    fun screenReaderServiceIsBound() {
        awaitScreenReader()
    }

    @Test
    fun tapScreenRejectsMissingCoordinates() = runBlocking<Unit> {
        val result = makeRegistry().executeTool("tap_screen", "{}")
        assertTrue("Unexpected result: $result", result.contains("error"))
        assertTrue("Error should mention x and y: $result", result.contains("x"))
    }

    @Test
    fun swipeScreenRejectsMissingCoordinates() = runBlocking<Unit> {
        val result = makeRegistry().executeTool("swipe_screen", JSONObject().put("x1", 100).toString())
        assertTrue("Unexpected result: $result", result.contains("error"))
    }

    @Test
    fun tapScreenPerformsGesture() = runBlocking<Unit> {
        awaitScreenReader()
        // Tap dead-center of the screen: harmless on any app, validates the gesture path.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val metrics = context.resources.displayMetrics
        val x = metrics.widthPixels / 2
        val y = metrics.heightPixels / 2
        val result = makeRegistry().executeTool("tap_screen", JSONObject().put("x", x).put("y", y).toString())
        assertTrue("Unexpected result: $result", result.contains("Tapped"))
    }

    @Test
    fun swipeScreenPerformsGesture() = runBlocking<Unit> {
        awaitScreenReader()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val metrics = context.resources.displayMetrics
        val cx = metrics.widthPixels / 2
        val args = JSONObject()
            .put("x1", cx).put("y1", metrics.heightPixels / 2)
            .put("x2", cx).put("y2", metrics.heightPixels / 4)
        val result = makeRegistry().executeTool("swipe_screen", args.toString())
        assertTrue("Unexpected result: $result", result.contains("Swiped"))
    }

    // ---- send_android_intent (was announced but not implemented) ----

    @Test
    fun sendAndroidIntentViewsUrl() = runBlocking<Unit> {
        awaitScreenReader()
        val result = makeRegistry().executeTool(
            "send_android_intent",
            JSONObject()
                .put("action", "android.intent.action.VIEW")
                .put("data_uri", "https://www.example.com")
                .toString()
        )
        assertTrue("Unexpected result: $result", result.contains("sent"))
        Thread.sleep(2500)
        val read = makeRegistry().executeTool("read_screen", "{}")
        assertTrue("Browser did not open a readable page: $read", read.contains("\"screen\"") || read.contains("screen"))
    }

    @Test
    fun sendAndroidIntentWithoutActionIsRejected() = runBlocking<Unit> {
        val result = makeRegistry().executeTool("send_android_intent", "{}")
        assertTrue("Unexpected result: $result", result.contains("error"))
        assertTrue("Error should mention action: $result", result.contains("action"))
    }

    @Test
    fun sendAndroidIntentMailtoResolves() = runBlocking<Unit> {
        val result = makeRegistry().executeTool(
            "send_android_intent",
            JSONObject()
                .put("action", "android.intent.action.SENDTO")
                .put("data_uri", "mailto:")
                .toString()
        )
        // Either a mail app opens or the chooser fails gracefully — never a registry miss.
        assertTrue("Unexpected result: $result", !result.contains("not found in registry"))
    }

    // ---- Discovery: the new tools must be advertised to the LLM ----

    @Test
    fun screenToolsAreAdvertised() = runBlocking<Unit> {
        val schemas = makeRegistry().discoverAndBindTools()
        assertTrue("read_screen missing: $schemas", schemas.contains("read_screen"))
        assertTrue("tap_screen missing: $schemas", schemas.contains("tap_screen"))
        assertTrue("swipe_screen missing: $schemas", schemas.contains("swipe_screen"))
        assertTrue("tap_element missing: $schemas", schemas.contains("tap_element"))
    }
}

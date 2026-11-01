package com.altaris.linux.core.rea

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MCP server is a local service, not a screen feature.
 *
 * The clone control server starts with the app and has no start/stop button.
 * REA's bridge must behave the same way: it starts at app launch, is kept alive
 * by the screens that use it, and exposes no control that lets a user leave it
 * switched off — which is exactly what "why is there still a stop and start
 * button" was about.
 */
class ReaServerAlwaysOnTest {

    /** The bridge must be started at app launch, like the clone control server. */
    @Test
    fun theBridgeStartsWithTheApp() {
        val app = readSource("app/src/main/kotlin/com/altaris/linux/AltarisApp.kt")
        assertTrue(
            "the bridge must be started from application create",
            app.contains("ReaBridge.ensureRunning"),
        )
        // It sits next to the clone control server, which is the model to copy.
        assertTrue(
            "the clone control server must still start there too",
            app.contains("ControlServer.start(this)"),
        )
        // Off the main thread: it spawns a guest process.
        assertTrue(
            "must run off the main thread",
            app.contains("Thread {") && app.contains("runBlocking"),
        )
    }

    /** No screen may offer a way to stop or start the server. */
    @Test
    fun noScreenOffersAStartOrStopControl() {
        for (path in listOf(
            "app/src/main/kotlin/com/altaris/linux/ui/screens/ToolsScreen.kt",
            "app/src/main/kotlin/com/altaris/linux/ui/screens/ReaScreen.kt",
        )) {
            val source = readSource(path)
            assertFalse(
                "$path must not offer 'Stop server'",
                source.contains("Stop server"),
            )
            assertFalse(
                "$path must not offer 'Start server'",
                source.contains("Start server"),
            )
            // A bare "Stop" chip is only acceptable for clone sessions; assert
            // the REA bridge is never stopped from the UI.
            assertFalse(
                "$path must not call ReaBridge.stop()",
                source.contains("ReaBridge.stop()"),
            )
        }
    }

    /** The screens keep it alive instead of asking the user to. */
    @Test
    fun theScreensKeepTheServerAlive() {
        val tools = readSource(
            "app/src/main/kotlin/com/altaris/linux/ui/screens/ToolsScreen.kt"
        )
        assertTrue(
            "the Tools card must ensure the server",
            tools.contains("ReaBridge.ensureRunning(context)"),
        )
        assertTrue("it must keep polling", tools.contains("while (true)"))

        val rea = readSource(
            "app/src/main/kotlin/com/altaris/linux/ui/screens/ReaScreen.kt"
        )
        assertTrue(
            "the REA screen must ensure the server",
            rea.contains("ReaBridge.ensureRunning(context)"),
        )
        assertTrue("it must keep polling too", rea.contains("while (true)"))
    }

    /**
     * A down server must explain itself in the card, not hide behind a chip
     * that says "Stopped".
     */
    @Test
    fun aDownServerShowsTheReason() {
        val tools = readSource(
            "app/src/main/kotlin/com/altaris/linux/ui/screens/ToolsScreen.kt"
        )
        assertTrue(
            "the card must surface the bridge error",
            tools.contains("bridge.error.isNotBlank() -> bridge.error"),
        )
    }

    private fun readSource(path: String): String {
        for (candidate in listOf(path, "../$path", "../../$path")) {
            val file = java.io.File(candidate)
            if (file.isFile) return file.readText()
        }
        throw AssertionError("source not found: $path")
    }
}

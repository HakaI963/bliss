package com.altaris.linux.core.rea

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Install lifetime.
 *
 * The install is a multi-gigabyte job that runs for many minutes. It used to be
 * launched from the screen's `rememberCoroutineScope()`, so leaving the screen
 * (or switching tab, or rotating) cancelled it with "the coroutine scope left
 * the composition" and the work died half way — which is also why the UI never
 * reached "Installed": the install never finished.
 *
 * These tests assert the shape of the fix: the work is owned by ReaDistro, the
 * screen only calls a non-suspending launcher, and the launcher cannot start a
 * second install.
 */
class ReaInstallLifetimeTest {

    @Test
    fun theInstallIsOwnedByTheRepositoryNotTheScreen() {
        val source = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/rea/ReaDistro.kt"
        )
        // An app-lifetime scope exists and is used for the install.
        assertTrue(
            "ReaDistro must own a scope",
            source.contains("private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)"),
        )
        assertTrue("the install must be launched on it", source.contains("installJob = scope.launch"))
        // The public entry point must not be suspend: a click handler cannot own it.
        val launcher = source.substringAfter("    fun install(")
            .substringBefore("suspend fun installBlocking(")
        assertFalse(
            "the launcher must not suspend",
            launcher.contains("suspend fun install("),
        )
        assertTrue(
            "the blocking body is kept separately",
            source.contains("suspend fun installBlocking("),
        )
    }

    @Test
    fun aSecondInstallCannotStartWhileOneIsRunning() {
        val source = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/rea/ReaDistro.kt"
        )
        assertTrue("must expose the running state", source.contains("fun isInstalling()"))
        // Every entry point guards on it.
        assertTrue(
            "install must guard",
            source.contains("if (isInstalling()) {"),
        )
        assertTrue(
            "tool repair must guard",
            source.substringAfter("fun installMissingTools(")
                .substringBefore("suspend fun installMissingToolsBlocking(")
                .contains("if (isInstalling()) return"),
        )
        assertTrue(
            "uninstall must guard",
            source.substringAfter("fun uninstall(")
                .substringBefore("suspend fun uninstallBlocking(")
                .contains("if (isInstalling()) return"),
        )
    }

    /** Cancellation must clear busy state, not surface as a user error. */
    @Test
    fun cancellationIsNotReportedAsAnInstallError() {
        val source = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/rea/ReaDistro.kt"
        )
        assertTrue(
            "must handle CancellationException separately",
            source.contains("catch (c: kotlinx.coroutines.CancellationException)"),
        )
        val cancelBlock = source.substringAfter("catch (c: kotlinx.coroutines.CancellationException)")
            .substringBefore("} catch (t: Throwable)")
        assertTrue("must clear busy", cancelBlock.contains("busy = \"\""))
        assertFalse(
            "must not set a user-facing error",
            cancelBlock.contains("error = "),
        )
    }

    /**
     * The screen must call the launcher directly. `scope.launch { install() }`
     * is exactly the bug: it puts the job back under the composable's lifetime.
     */
    @Test
    fun theScreenDoesNotLaunchTheInstallFromItsOwnScope() {
        val screen = readSource(
            "app/src/main/kotlin/com/altaris/linux/ui/screens/ToolsScreen.kt"
        )
        assertFalse(
            "must not wrap the install in the screen scope",
            screen.contains("scope.launch { ReaDistro.install("),
        )
        assertFalse(
            "must not wrap the tool repair either",
            screen.contains("scope.launch { ReaDistro.installMissingTools("),
        )
        assertFalse(
            "must not wrap uninstall either",
            screen.contains("scope.launch { ReaDistro.uninstall("),
        )
        assertTrue(
            "the install is called directly",
            screen.contains("ReaDistro.install(context, chosen)"),
        )
    }

    /** The REA screen must not launch it from its scope either. */
    @Test
    fun noScreenLaunchesTheInstallFromACompositionScope() {
        for (path in listOf(
            "app/src/main/kotlin/com/altaris/linux/ui/screens/ReaScreen.kt",
            "app/src/main/kotlin/com/altaris/linux/ui/screens/ToolsScreen.kt",
        )) {
            val source = readSource(path)
            assertFalse(
                "$path must not launch a REA install from a composition scope",
                source.contains("launch { ReaDistro.install"),
            )
            assertFalse(
                "$path must not launch a REA repair from a composition scope",
                source.contains("launch { ReaDistro.installMissingTools"),
            )
        }
    }

    private fun readSource(path: String): String {
        for (candidate in listOf(path, "../$path", "../../$path")) {
            val file = java.io.File(candidate)
            if (file.isFile) return file.readText()
        }
        throw AssertionError("source not found: $path")
    }
}

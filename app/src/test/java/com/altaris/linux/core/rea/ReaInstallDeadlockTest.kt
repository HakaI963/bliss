package com.altaris.linux.core.rea

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regressions from a device report: a stuck reinstall, and the file manager
 * being unable to reach most of the filesystem.
 */
class ReaInstallDeadlockTest {

    private fun readSource(path: String): String {
        for (candidate in listOf(path, "../$path", "../../$path")) {
            val file = File(candidate)
            if (file.isFile) return file.readText()
        }
        throw AssertionError("source not found: $path")
    }

    /**
     * The reinstall hung at 0% forever.
     *
     * `install()` and `prepare()` hold `mutex` for their whole body. The frida
     * module repair was added inside them and called `installPackages`, which
     * takes the same mutex — and Kotlin's Mutex is not reentrant, so the
     * coroutine waited on a lock it already held. The button stayed on
     * "Working…" with no progress and no way out.
     */
    @Test
    fun theFridaRepairDoesNotTakeTheInstallLockAgain() {
        val manager = readSource("app/src/main/kotlin/com/altaris/linux/core/rea/ReaManager.kt")
        val fn = manager.substringAfter("private suspend fun ensureFridaModule(")
            .substringBefore("private fun coreScript()")
        assertFalse(
            "ensureFridaModule runs while the install mutex is held, so it must " +
                "not call installPackages (deadlock)",
            fn.contains("installPackages("),
        )
        // It must still install the build tools it needs, through the guest.
        assertTrue(
            "must install the frida build toolchain",
            fn.contains("packageInstallScript(manager, toolchain)"),
        )
        assertTrue(
            "must run it in the guest",
            fn.contains("GuestExec.run("),
        )
    }

    /** The callers of ensureFridaModule must be inside the lock, as documented. */
    @Test
    fun theRepairIsCalledFromInsideTheInstall() {
        val manager = readSource("app/src/main/kotlin/com/altaris/linux/core/rea/ReaManager.kt")
        val install = manager.substringAfter("suspend fun install(")
            .substringBefore("private suspend fun installDependency(")
        assertTrue(
            "install must still repair the frida module",
            install.contains("ensureFridaModule("),
        )
        // And the mutex must still guard the install itself.
        assertTrue("install must hold the mutex", install.contains("mutex.withLock"))
    }

    /**
     * A stuck install must be escapable.
     *
     * The Remove button was inside `if (state.installed)`, so a half-finished
     * install — the case where you most want to start over — showed no remove
     * button at all while the install button was disabled.
     */
    @Test
    fun theRemoveButtonIsAlwaysReachable() {
        val screen = readSource(
            "app/src/main/kotlin/com/altaris/linux/ui/screens/ToolsScreen.kt"
        )
        val remove = screen.substringAfter("\"Remove REA toolchain\"")
        assertTrue(
            "the remove button must exist",
            remove.isNotBlank(),
        )
        // Its guard must include the busy and error states, not only installed.
        val guard = screen.substringAfter("// Always offer the removal.")
            .substringBefore("PrismSecondaryButton(")
        assertTrue(
            "removal must be offered while busy",
            guard.contains("state.busy.isNotBlank()"),
        )
        assertTrue(
            "removal must be offered after an error",
            guard.contains("state.error.isNotBlank()"),
        )
    }

    /** "Reinstall" must repair, not wipe a working toolchain. */
    @Test
    fun reinstallRepairsInsteadOfRedownloadingEverything() {
        val screen = readSource(
            "app/src/main/kotlin/com/altaris/linux/ui/screens/ToolsScreen.kt"
        )
        val branch = screen.substringAfter("chosenInstalled && !chosenActive")
            .substringBefore("} else {")
        assertTrue(
            "reinstall must re-probe and install only what is missing",
            branch.contains("installMissingTools("),
        )
        assertFalse(
            "reinstall must not re-run the whole toolchain install",
            branch.contains("ReaDistro.install("),
        )
    }
}

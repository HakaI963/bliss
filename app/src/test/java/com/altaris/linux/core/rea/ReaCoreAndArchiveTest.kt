package com.altaris.linux.core.rea

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Three bugs from the device log.
 *
 * 1. "REA core is not installed in altaris_rea_chroot" — the check ran on the
 *    host, where the chroot's /opt does not exist.
 * 2. radare2 failed with `curl: (56)` at 19.5 of 23.3 MB — no resume.
 * 3. A tool that was already present reported "failed", because the outcome
 *    parser only understood `ok:` and `ERROR`.
 */
class ReaCoreAndArchiveTest {

    /**
     * The core check must run inside the chroot. `captureResult` runs on the
     * host, so it can never see a chroot's /opt/altaris/rea.
     */
    @Test
    fun theChrootCoreCheckRunsInsideTheChroot() {
        val source = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/rea/ReaManager.kt"
        )
        val check = source.substringAfter("suspend fun coreInstalled(")
            .substringBefore("fun cachedTools()")
        assertTrue(
            "the chroot branch must use captureInChroot",
            check.contains("RootShell.captureInChroot"),
        )
        assertFalse(
            "it must not use the host-side captureResult for the test",
            check.contains("RootShell.captureResult(check"),
        )
        assertTrue("it must pass the chroot path", check.contains("chrootPath = path"))
        assertTrue("it must run as root", check.contains("user = \"root\""))
    }

    /** The archive downloader must resume and retry, not restart from zero. */
    @Test
    fun theArchiveDownloaderResumes() {
        val script = readAsset("scripts/rea/install-archive.sh")
        assertTrue("curl must resume", script.contains("-C -"))
        assertTrue("wget must resume", script.contains("wget -c"))
        assertTrue("must retry in a loop", script.contains("while [ \"\$_attempt\" -le 5 ]"))
        // A partial file must not be treated as a failed download while retries remain.
        assertTrue(
            "the retry loop must break only on success",
            script.contains("if dl \"\$URL\" \"\$TMP\"; then break; fi"),
        )
    }

    /**
     * "already installed" is a success. The parser must recognise it and the
     * outcome must carry that wording through to the UI.
     */
    @Test
    fun anAlreadyInstalledToolReportsSuccess() {
        val source = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/rea/ReaManager.kt"
        )
        assertTrue(
            "the outcome parser must recognise the already-installed line",
            source.contains("it.startsWith(\"already installed\")"),
        )
        assertTrue(
            "the detail must say already installed",
            source.contains("\"already installed\""),
        )
        // Both the package and the archive path must handle it.
        val occurrences = Regex("already installed").findAll(source).count()
        assertTrue("both install paths must handle it", occurrences >= 4)
    }

    /** The UI must not print "Failed" for a tool that is present. */
    @Test
    fun theUiSaysAlreadyInstalled() {
        val screen = readSource(
            "app/src/main/kotlin/com/altaris/linux/ui/screens/ToolsScreen.kt"
        )
        assertTrue(
            "must distinguish the already-installed case",
            screen.contains("Already installed "),
        )
        assertTrue(
            "must detect it from the outcome detail",
            screen.contains("outcome.detail.contains(\"already installed\")"),
        )
    }

    /**
     * JADX is not packaged for Debian 13, so its apt variant must be empty or
     * the archive fallback is unreachable — the same bug radare2 and rizin had.
     */
    @Test
    fun jadxIsInstalledFromItsArchiveOnDebian() {
        val dep = ReaCatalog.byId("jadx")
        assertNotNull(dep)
        assertTrue(
            "jadx must have no apt variant on Debian 13",
            ReaCatalog.variantsFor(dep!!, "apt").isEmpty(),
        )
        assertNotNull("jadx must have an archive", dep.archive)
        assertTrue(
            "jadx must route to the archive",
            ReaCatalog.usesArchive(dep, "apt"),
        )
    }

    /**
     * Every tool whose package does not exist on Debian 13 must reach an
     * archive instead of failing the apt step.
     */
    @Test
    fun everyDebianMissingToolHasAnArchive() {
        // Verified against packages.debian.org/trixie: these are absent.
        for (id in listOf("jadx", "radare2", "rizin")) {
            val dep = ReaCatalog.byId(id) ?: throw AssertionError("$id missing from catalog")
            assertTrue(
                "$id must not attempt apt",
                ReaCatalog.variantsFor(dep, "apt").isEmpty(),
            )
            assertNotNull("$id needs an archive", dep.archive)
        }
    }

    /** The server must not need a button, and must retry rather than give up. */
    @Test
    fun theServerRetriesInsteadOfWaitingForAButton() {
        val bridge = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/rea/ReaBridge.kt"
        )
        assertTrue("must expose the waiting state", bridge.contains("fun waitingForCore()"))
        assertTrue("must clear a stale error", bridge.contains("_state.value.copy(error = \"\")"))

        val screen = readSource(
            "app/src/main/kotlin/com/altaris/linux/ui/screens/ReaScreen.kt"
        )
        // It must keep polling, not stop after the first attempt.
        assertTrue(
            "the screen must keep ensuring the server",
            screen.contains("ReaBridge.ensureRunning(context)"),
        )
        assertTrue("it must loop", screen.contains("while (true)"))
        assertFalse(
            "there must be no Start button",
            screen.contains("\"Start\""),
        )
    }

    private fun readAsset(path: String): String {
        for (candidate in listOf(
            "app/src/main/assets/$path",
            "src/main/assets/$path",
            "../app/src/main/assets/$path",
        )) {
            val file = java.io.File(candidate)
            if (file.isFile) return file.readText()
        }
        throw AssertionError("asset not found: $path")
    }

    private fun readSource(path: String): String {
        for (candidate in listOf(path, "../$path", "../../$path")) {
            val file = java.io.File(candidate)
            if (file.isFile) return file.readText()
        }
        throw AssertionError("source not found: $path")
    }
}

package com.altaris.linux.core.rea

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Contract for the REA shell installers shipped as assets.
 *
 * These scripts are the whole install path on a phone; nothing in the JVM can
 * prove they work, so at least pin the invariants that broke in the field:
 * the helpers must exist, take arguments, never `set -e` their way out half
 * installed, and never write into the Ghidra tree the installer deletes.
 */
class ReaScriptsContractTest {

    private fun repoFile(rel: String): File {
        val cwd = File("").absoluteFile
        val candidates = listOf(
            File(cwd, rel),
            File(cwd, "app/$rel"),
            File(cwd.parentFile, rel),
            File(cwd.parentFile, "app/$rel"),
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("missing $rel (cwd=$cwd)")
    }

    private fun reaScript(name: String): String =
        repoFile("src/main/assets/scripts/rea/$name").readText()

    @Test
    fun allInstallersAreShipped() {
        for (name in listOf(
            "pkg-lock.sh",
            "rea-core-install.sh",
            "install-tool.sh",
            "install-archive.sh",
            "ghidra-install.sh",
            "ghidra-decompiler-build.sh",
            "ghidra-analyze.sh",
            "DumpInfo.java",
        )) {
            val file = repoFile("src/main/assets/scripts/rea/$name")
            assertTrue("$name is empty", file.length() > 0L)
        }
    }

    @Test
    fun shellScriptsUseAPosixShebang() {
        for (name in listOf(
            "pkg-lock.sh",
            "rea-core-install.sh",
            "install-tool.sh",
            "install-archive.sh",
            "ghidra-install.sh",
            "ghidra-decompiler-build.sh",
            "ghidra-analyze.sh",
        )) {
            val first = reaScript(name).lines().firstOrNull().orEmpty()
            assertTrue("$name must start with #!/bin/sh, was: $first", first.startsWith("#!/bin/sh"))
        }
    }

    @Test
    fun coreInstallerTakesArgumentsAndReportsFailures() {
        val script = reaScript("rea-core-install.sh")
        assertTrue(script.contains("BASE=\"\$1\""))
        assertTrue(script.contains("NODE_VERSION=\"\$2\""))
        assertTrue(script.contains("REA_VERSION=\"\$3\""))
        // Failures must print a reason the app can show.
        assertTrue(script.contains("ERROR:"))
        // The official node tarball and the musl build for Alpine.
        assertTrue(script.contains("nodejs.org/dist"))
        assertTrue(script.contains("unofficial-builds.nodejs.org"))
        // A pinned version must be honoured, not "latest".
        assertTrue(script.contains("rea-agents@\$REA_VERSION"))
    }

    @Test
    fun everyPackageManagerCallGoesThroughTheLockHelper() {
        // The user's terminal failed with "Could not get lock ... held by
        // process 17639 (apt-get)" while REA was installing: several paths
        // reached apt at once, and nothing serialised them or cleaned up after
        // a killed installer. pkg_run is the single entry point that does.
        val lock = reaScript("pkg-lock.sh")
        assertTrue(lock.contains("flock"))
        assertTrue("must bound the wait", lock.contains("ALTARIS_PKG_WAIT"))
        assertTrue("must bound the transaction", lock.contains("ALTARIS_PKG_TIMEOUT"))
        assertTrue("must reap orphans", lock.contains("pkg_reap_orphans()"))
        // Only an orphan may be killed: a package manager the user started has
        // a live parent and must be left alone.
        assertTrue(lock.contains("pkg_is_orphan()"))
        assertTrue(lock.contains("[ -d \"/proc/\$2\" ] && return 1"))

        // Every installer that touches a package manager must source it and
        // call pkg_run, not the manager directly.
        for (name in listOf("install-tool.sh", "install-archive.sh", "rea-core-install.sh")) {
            val script = reaScript(name)
            assertTrue("$name must source pkg-lock.sh", script.contains("pkg-lock.sh"))
            assertTrue("$name must define a fallback pkg_run", script.contains("pkg_run()"))
            assertFalse(
                "$name must not call apt-get outside pkg_run",
                Regex("(?m)^\\s*(apt-get|apk|dnf|pacman)\\s").containsMatchIn(script),
            )
        }
    }

    @Test
    fun aMissingHelperNeverSilentlyStopsAnInstaller() {
        // Sourcing a missing file is fatal in a POSIX shell, so an unguarded
        // `.` after a failed staging would exit before installing anything —
        // a silent no-op, which is worse than an error.
        for (name in listOf("install-tool.sh", "install-archive.sh", "rea-core-install.sh")) {
            val script = reaScript(name)
            assertTrue(
                "$name must test pkg-lock.sh before sourcing it",
                script.contains("[ -r \"\$ALTARIS_PKG_SH_DIR/pkg-lock.sh\" ]"),
            )
            // Every `. pkg-lock.sh` line must sit under a `[ -r ]` test. A
            // plain "does it source it" check is not enough: the bug was the
            // unguarded form, so assert that no source line appears without a
            // preceding readability test in the same if/elif chain.
            val lines = script.lines()
            for ((i, line) in lines.withIndex()) {
                val trimmed = line.trim()
                if (!trimmed.startsWith(".") || !trimmed.contains("pkg-lock.sh")) continue
                val guarded = lines.subList(maxOf(0, i - 2), i + 1).any { it.contains("[ -r ") }
                assertTrue("$name sources pkg-lock.sh unguarded: $trimmed", guarded)
            }
        }
    }

    @Test
    fun curlIsInstalledAsPartOfTheBaseSet() {
        // The user's rootfs had no curl at all. Every installer that needed one
        // installed it mid-transaction, taking the apt lock in the middle of
        // another install. It is a base tool now.
        val distro = repoFile("src/main/kotlin/com/altaris/linux/core/rea/ReaDistro.kt").readText()
        val tools = distro.substringAfter("val ALL_TOOLS = listOf(")
            .substringBefore(")")
        assertTrue("curl must be in the base tool set", tools.contains("\"curl\""))
        val catalog = repoFile("src/main/kotlin/com/altaris/linux/core/rea/ReaCatalog.kt").readText()
        val dep = catalog.substringAfter("id = \"curl\"")
        assertTrue("curl must have an apt package", dep.contains("\"apt\" to every(listOf(\"curl\"))"))
    }

    /**
     * A guest command must actually time out.
     *
     * `GuestExec.run` passed a timeout all along, but `runCaptureExit` had no
     * timeout parameter and read stdout inline before waiting, so the value was
     * dropped twice over. A hung `apt-get` ran until the app was killed, and
     * being root it kept the apt lock afterwards — which is why the user's own
     * `sudo apt update` then failed against a process they could not see.
     */
    @Test
    fun guestCommandsHonourTheirTimeout() {
        val runner = repoFile(
            "src/main/kotlin/com/altaris/linux/core/terminal/ShellCommandRunner.kt"
        ).readText()
        val fn = runner.substringAfter("fun runCaptureExit(")
            .substringBefore("fun runStreamed(")
        assertTrue(
            "must accept a timeout",
            fn.contains("timeoutMs: Long = 0L"),
        )
        assertTrue(
            "must wait with the deadline",
            fn.contains("proc.waitFor(timeoutMs,"),
        )
        assertTrue(
            "must kill on expiry",
            fn.contains("proc.destroyForcibly()"),
        )
        // Reading must not happen before the wait, or the timeout never fires.
        assertTrue(
            "stdout must be drained on a reader thread",
            fn.contains("val reader = Thread {"),
        )
        assertTrue(
            "a timeout must be reported as -2",
            fn.contains("-2 to partial"),
        )

        val guest = repoFile("src/main/kotlin/com/altaris/linux/core/rea/GuestExec.kt").readText()
        val proot = guest.substringAfter("private fun runProot(")
            .substringBefore("private fun reapOrphans(")
        assertTrue(
            "proot must pass the timeout through",
            proot.contains("timeoutMs = timeoutMs"),
        )
        assertTrue(
            "a timed-out proot run must reap what it left",
            proot.contains("reapOrphans(ctx, profile)"),
        )
        assertTrue(
            "a timed-out chroot run must reap what it left",
            guest.contains("reapOrphans(ctx, profile)"),
        )
        // The reap must be orphan-only, or it would kill a package manager the
        // user started in their own terminal.
        assertTrue(
            "the reap must use the orphan rule",
            guest.contains("pkg_reap_orphans"),
        )
    }

    @Test
    fun toolInstallerWalksEveryVariantAndVerifiesTheProbe() {
        val script = reaScript("install-tool.sh")
        for (manager in listOf("apt", "apk", "dnf", "pacman")) {
            assertTrue("install-tool.sh must handle $manager", script.contains("$manager)"))
        }
        assertTrue(script.contains("have()"))
        assertTrue(script.contains("already installed"))
        // Every attempt is logged and the final failure is explicit.
        assertTrue(script.contains("attempt"))
        assertTrue(script.contains("ERROR:"))
        assertTrue(script.contains("exit 1"))
    }

    @Test
    fun toolInstallerSplitsTheProbeListOnCommas() {
        val script = reaScript("install-tool.sh")
        // The catalog passes "aapt,aapt2" / "7z,7za,7zr". A plain `for p in
        // $PROBES` produced one literal token, so every multi-probe tool was
        // reported as failed even after a clean install.
        assertTrue(script.contains("tr ',' '\\n'"))
        assertTrue(script.contains("probe_list()"))
        assertFalse(
            "probes must be split on commas, not words",
            script.contains("for p in \$PROBES"),
        )
    }

    @Test
    fun archiveInstallerGuardsTruncatedDownloadsAndVerifiesTheProbe() {
        val script = reaScript("install-archive.sh")
        // A captive-portal HTML page or an interrupted transfer is a few KB;
        // unpacking it would leave a half-installed tool.
        assertTrue(script.contains("100000"))
        assertTrue(script.contains("not a release archive"))
        assertTrue(script.contains("probe_list()"))
        assertTrue(script.contains("already installed"))
        assertTrue(script.contains("ERROR:"))
        // Releases wrap everything in one directory; the launcher path must be
        // stable, so the top level is flattened.
        assertTrue(script.contains("Flatten"))
        // No set -e: a failure must print a reason, not abort silently.
        assertFalse(script.contains("set -e"))
    }

    @Test
    fun archiveInstallerLinksTheLauncherIntoPath() {
        val script = reaScript("install-archive.sh")
        assertTrue(script.contains("BIN=\"\$DIR/../bin\""))
        assertTrue(script.contains("ln -sf"))
        assertTrue(script.contains("chmod +x"))
    }

    @Test
    fun managerInstallsArchiveToolsWhenNoPackageExists() {
        val manager = repoFile("src/main/kotlin/com/altaris/linux/core/rea/ReaManager.kt").readText()
        assertTrue(manager.contains("install-archive.sh"))
        assertTrue(manager.contains("installFromArchive"))
        // Archive launchers must be on PATH for probes and for REA itself.
        assertTrue(manager.contains("ARCHIVE_BIN"))
        assertTrue(manager.contains("PROBE_PATH"))
        // The wrapper for a bare jar is staged before the download, so a
        // missing launcher is reported before 15 MB is fetched.
        assertTrue(manager.contains("ALTARIS_WRAPPER_EOF"))
    }

    @Test
    fun reaRunsWithItsDocumentedPrerequisites() {
        val stdio = repoFile("src/main/kotlin/com/altaris/linux/core/rea/ReaStdio.kt").readText()
        // REA refuses several analysis families without a procps-compatible ps
        // and documents the compact schema profile for large tool lists.
        assertTrue(stdio.contains("PROBE_PATH"))
        assertTrue(stdio.contains("REA_MCP_INPUT_SCHEMA_PROFILE"))
    }

    @Test
    fun toolInstallerFallsBackToPlainInstallOnApt() {
        val script = reaScript("install-tool.sh")
        assertTrue(
            "apt needs a retry without --no-install-recommends",
            script.contains(
                "pkg_run apt-get install -y --no-install-recommends \"\$@\" || " +
                    "pkg_run apt-get install -y \"\$@\""
            ),
        )
    }

    @Test
    fun ghidraInstallerNeverWritesIntoTheTreeItDeletes() {
        val script = reaScript("ghidra-install.sh")
        assertTrue(script.contains("GH=\"\$2\""))
        assertTrue(script.contains("JDK_URL=\"\$5\""))
        // rm -rf "$GH" happens before the release is moved in; nothing of ours
        // may live below $GH or it disappears with it.
        assertFalse(
            "the analyzer wrapper must live in \$BASE/scripts, not under \$GH",
            script.contains("> \"\$GH/"),
        )
        assertTrue(script.contains("rm -rf \"\$GH\""))
        // A truncated download must not be unpacked.
        assertTrue(script.contains("100000000"))
        assertTrue(script.contains("analyzeHeadless"))
    }

    @Test
    fun ghidraInstallerDoesNotFailWhenTheDecompilerCannotBuild() {
        val script = reaScript("ghidra-install.sh")
        assertFalse("ghidra-install.sh must not abort on errors", script.contains("set -e"))
        assertTrue(script.contains("warning: decompiler build failed"))
    }

    @Test
    fun decompilerBuildIsBestEffortAndIdempotent() {
        val script = reaScript("ghidra-decompiler-build.sh")
        assertFalse(script.contains("set -e"))
        assertTrue(script.contains("set +e"))
        // Re-running must not append the arch block twice.
        assertTrue(script.contains("grep -q \"Altaris: force linux_arm_64\" Makefile"))
        // A missing compiler is a clean failure, not a partial install.
        assertTrue(script.contains("g++ is unavailable"))
    }

    @Test
    fun managerNeverCallsTheLegacyScriptHelpers() {
        val manager = repoFile("src/main/kotlin/com/altaris/linux/core/rea/ReaManager.kt").readText()
        // The wrapper is staged in ROOT/scripts; writing it under GHIDRA_ROOT
        // would delete it as soon as Ghidra is installed.
        assertFalse(
            manager.contains("GHIDRA_ROOT + \"/ghidra-analyze.sh\""),
        )
        assertTrue(manager.contains("rea-core-install.sh"))
        assertTrue(manager.contains("install-tool.sh"))
        assertTrue(manager.contains("detectPackageManager"))
        // Tools must still install when the REA core fails.
        assertTrue(manager.contains("tools below are still attempted"))
    }

    @Test
    fun cliTargetsOneCloneWithPackageFlag() {
        val cli = repoFile("src/main/assets/scripts/altaris-cli.sh").readText()
        assertTrue(cli.contains("--package"))
        assertTrue(cli.contains("pkg_query()"))
        assertTrue(cli.contains("pkg_field()"))
        assertTrue(cli.contains("sessions)"))
        assertTrue(cli.contains("ALTARIS_PACKAGE"))
    }
}

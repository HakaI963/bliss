package com.altaris.linux.core.rea

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The install failures from a real device log.
 *
 * Each case here corresponds to an error that actually stopped an install:
 * npm's ENOTEMPTY on a retry, a truncated Ghidra download, a doubled path passed
 * to unzip, and two tools that Debian 13 simply does not package.
 */
class ReaInstallRegressionTest {

    /**
     * npm renames the existing package aside before unpacking the new one. A
     * previous attempt that died mid-install leaves that directory populated, so
     * the rename fails with ENOTEMPTY and retrying never helps. The core script
     * has to clear that state itself.
     */
    @Test
    fun theCoreInstallerClearsStaleNpmStateBeforeRetrying() {
        val script = readAsset("scripts/rea/rea-core-install.sh")
        assertTrue(
            "the installer must remove a half-installed package",
            script.contains("clean_npm_state"),
        )
        assertTrue(
            "the stale package directory is what npm cannot rename",
            script.contains("rm -rf \"\$BASE/npm/lib/node_modules/rea-agents\""),
        )
        assertTrue(
            "npm's staging directories must go too",
            script.contains(".rea-agents-*"),
        )
        // The retry path must clean as well, or the second attempt fails the same way.
        val retry = script.substringAfter("npm install failed")
        assertTrue("the retry must also clean", retry.contains("clean_npm_state"))
    }

    /**
     * The Ghidra download aborted at 233 MB and the next attempt started from
     * zero, then unzip reported a corrupt archive. It must resume, and it must
     * verify the zip before trusting it.
     */
    @Test
    fun theGhidraDownloadResumesAndVerifies() {
        val script = readAsset("scripts/rea/ghidra-install.sh")
        assertTrue("must resume a partial download", script.contains("-C -"))
        assertTrue("must retry in a loop", script.contains("while [ \"\$attempt\" -le 5 ]"))
        assertTrue("must test the archive before unpacking", script.contains("unzip -tqq"))
        // A truncated file must not reach unzip.
        val sizeCheck = script.indexOf("Ghidra download is too small")
        val unzipTest = script.indexOf("unzip -tqq")
        assertTrue("size gate comes before the zip test", sizeCheck in 0..unzipTest)
    }

    /**
     * Ghidra must be the release REA actually supports.
     *
     * REA gates its Ghidra provider on a specific version and rejects the rest,
     * so an older tree is not merely unconfigured — every decompile through REA
     * fails with the analyzer sitting right there. The app was still
     * provisioning 11.4.3 while REA 6.8.0 requires 12.1.4.
     */
    @Test
    fun ghidraIsTheVersionReaSupports() {
        val manager = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/rea/ReaManager.kt"
        )
        assertTrue(
            "must pin the release REA 6.8.0 requires",
            manager.contains("Ghidra_12.1.4_build"),
        )
        assertTrue(
            "must name the release asset",
            manager.contains("ghidra_12.1.4_PUBLIC_"),
        )
        val required = manager.substringAfter("const val GHIDRA_REQUIRED = \"")
            .substringBefore("\"")
        assertEquals("12.1.4", required)
        // The asset is published with a SHA-256 on the release page; a 570 MB
        // root install is verified rather than trusted.
        val sha = manager.substringAfter("const val GHIDRA_SHA256 = \"")
            .substringBefore("\"")
        assertEquals("a SHA-256 must be pinned", 64, sha.length)

        val script = readAsset("scripts/rea/ghidra-install.sh")
        assertTrue("the installer must accept the checksum", script.contains("EXPECTED_SHA"))
        val shaCheck = script.indexOf("SHA-256 check")
        val unpack = script.indexOf("unzip -q \"\$ZIP\"")
        assertTrue("integrity is checked before unpacking", shaCheck in 0..unpack)
        assertTrue(
            "a mismatch must stop the install",
            script.contains("failed its SHA-256 check"),
        )
    }

    /**
     * A Ghidra tree REA cannot use must not look installed.
     *
     * The probe used to accept any tree with an `analyzeHeadless`, so an
     * 11.4.3 left behind by an older build stayed "installed" and Tools never
     * offered the upgrade the user needed.
     */
    @Test
    fun aMismatchedGhidraTreeIsReportedAsMissing() {
        val manager = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/rea/ReaManager.kt"
        )
        val probe = manager.substringAfter("private fun probeScript()")
            .substringBefore("private fun availableIds(")
        assertTrue(
            "must check the version, not just the binary",
            probe.contains("application.version="),
        )
        assertTrue(
            "must compare against the version REA requires",
            probe.contains("GHIDRA_REQUIRED"),
        )
    }

    /**
     * REA imports the Node `frida` module for every hook tool, and npm does not
     * always lay it down — the provider then fails with capability_unavailable
     * and no hint that a module is missing.
     */
    @Test
    fun theFridaNodeModuleIsInstalledWithRea() {
        // Its own script, because an installed core is never re-run: the fix
        // has to be callable for a tree that predates it.
        val module = readAsset("scripts/rea/rea-frida-module.sh")
        assertTrue("must install into the REA tree", module.contains("--prefix \"\$BASE/npm\""))
        assertTrue(
            "must prefer the version REA declares",
            module.contains("optionalDependencies"),
        )
        assertTrue("must prove the module imports", module.contains("import('frida')"))
        assertTrue(
            "must be a no-op when it already imports",
            module.contains("if importable; then"),
        )
        // A missing frida must not fail the whole core install: static analysis
        // tools do not need it, and a hard failure would leave REA uninstalled.
        assertTrue(
            "must warn rather than abort",
            module.contains("warning: the frida module did not install"),
        )
        // A partial install must be replaced, not left broken.
        assertTrue(
            "must detect a module that will not import",
            module.contains("present but does not import"),
        )

        val core = readAsset("scripts/rea/rea-core-install.sh")
        assertTrue(
            "the core install must run it",
            core.contains("rea-frida-module.sh"),
        )

        // And an already-installed REA must be repaired too — that is the tree
        // the user actually has, and the core install skips it.
        val manager = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/rea/ReaManager.kt"
        )
        assertTrue(
            "must stage the repair script",
            manager.contains("\"rea-frida-module.sh\""),
        )
        val already = manager.substringAfter("REA core already installed in ")
        assertTrue(
            "an existing install must get the module",
            already.contains("ensureFridaModule("),
        )
    }

    /**
     * The REA process the app launches must be told where Ghidra is.
     *
     * REA reads GHIDRA_INSTALL_DIR at startup and reports the provider as
     * not_configured without it, even with the analyzer installed — which is
     * why four of five providers were rejected while the tools worked.
     */
    @Test
    fun theReaChildIsGivenTheGhidraPath() {
        val stdio = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/rea/ReaStdio.kt"
        )
        assertTrue(
            "must export GHIDRA_INSTALL_DIR",
            stdio.contains("GHIDRA_INSTALL_DIR="),
        )
        assertTrue(
            "must point at the tree the app installs",
            stdio.contains("ReaManager.GHIDRA_ROOT"),
        )
    }

    /**
     * `shellPath(ROOT)` produced
     * `/opt/altaris/rea/scripts//opt/altaris/rea`, so the zip landed somewhere
     * unzip could not find. The base must be quoted directly.
     */
    @Test
    fun theGhidraBasePathIsNotPrefixedWithTheScriptsDirectory() {
        val source = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/rea/ReaManager.kt"
        )
        assertFalse(
            "shellPath(ROOT) doubles the path",
            source.contains("shellPath(ROOT)"),
        )
        assertTrue(
            "ROOT is quoted directly",
            source.contains("GuestExec.shellQuote(ROOT)"),
        )
    }

    /**
     * Debian 13 does not package radare2 or rizin. Leaving an apt variant in
     * place means the package attempt fails and the archive fallback is never
     * reached.
     */
    @Test
    fun radare2AndRizinFallBackToTheirOfficialArchives() {
        for (id in listOf("radare2", "rizin")) {
            val dep = ReaCatalog.byId(id)
            assertNotNull("$id must exist in the catalog", dep)
            assertTrue(
                "$id must have no apt variant on Debian 13",
                ReaCatalog.variantsFor(dep!!, "apt").isEmpty(),
            )
            assertTrue(
                "$id must have an archive to fall back to",
                dep.archive != null,
            )
            assertTrue(
                "$id archive must be routed by usesArchive",
                ReaCatalog.usesArchive(dep, "apt"),
            )
            // The archive must be an arm64 tarball the installer can extract.
            val file = dep.archive!!.fileName
            assertTrue("$id archive must be a tarball", file.endsWith(".tar.gz"))
            assertTrue(
                "$id archive must be for arm64",
                file.contains("aarch64") || file.contains("arm64"),
            )
        }
    }

    /** Every catalog tool that has no package must still be installable. */
    @Test
    fun aToolWithoutAPackageAlwaysHasAnArchive() {
        for (dep in ReaCatalog.DEPENDENCIES) {
            if (dep.kind != "package") continue
            val hasAnyPackage = ReaCatalog.MANAGERS.any {
                ReaCatalog.variantsFor(dep, it).isNotEmpty()
            }
            if (!hasAnyPackage) {
                assertNotNull(
                    "${dep.id} has no package on any manager and no archive",
                    dep.archive,
                )
            }
        }
    }

    /** The bundled scripts must be present in the APK. */
    private fun readAsset(path: String): String {
        val candidates = listOf(
            "app/src/main/assets/$path",
            "src/main/assets/$path",
            "../app/src/main/assets/$path",
        )
        for (candidate in candidates) {
            val file = java.io.File(candidate)
            if (file.isFile) return file.readText()
        }
        throw AssertionError("asset not found: $path")
    }

    private fun readSource(path: String): String {
        val candidates = listOf(path, "../$path", "../../$path")
        for (candidate in candidates) {
            val file = java.io.File(candidate)
            if (file.isFile) return file.readText()
        }
        throw AssertionError("source not found: $path")
    }
}

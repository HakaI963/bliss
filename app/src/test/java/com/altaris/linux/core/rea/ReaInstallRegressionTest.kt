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

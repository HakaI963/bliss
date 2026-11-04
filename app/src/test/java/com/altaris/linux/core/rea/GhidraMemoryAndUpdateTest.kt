package com.altaris.linux.core.rea

import com.altaris.linux.core.update.AppUpdater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Ghidra's memory ceiling, the live REA activity view, and self-updates.
 *
 * The device froze while Ghidra analysed a 23 MB binary: the wrapper sized the
 * heap from total RAM, so it promised a JVM memory that Android was already
 * using, and the OOM killer took the app and the launcher with it.
 */
class GhidraMemoryAndUpdateTest {

    private fun readSource(path: String): String {
        for (candidate in listOf(path, "../$path", "../../$path")) {
            val file = File(candidate)
            if (file.isFile) return file.readText()
        }
        throw AssertionError("source not found: $path")
    }

    private fun readAsset(path: String): String {
        for (candidate in listOf(
            "app/src/main/assets/$path",
            "src/main/assets/$path",
            "../app/src/main/assets/$path",
        )) {
            val file = File(candidate)
            if (file.isFile) return file.readText()
        }
        throw AssertionError("asset not found: $path")
    }

    /**
     * The heap must come from MemAvailable, never MemTotal.
     *
     * Total RAM includes everything Android is already using. Half of it is a
     * promise the kernel cannot keep, and the failure mode is not a slow
     * analysis — it is the device dying.
     */
    @Test
    fun theHeapIsSizedFromAvailableMemory() {
        val script = readAsset("scripts/rea/ghidra-analyze.sh")
        assertTrue("must read MemAvailable", script.contains("MemAvailable"))
        assertTrue(
            "must cap the heap against total RAM",
            script.contains("CAP_MB=$((MAXRAM_MB / 3))") ||
                script.contains("MAXRAM_MB / 3"),
        )
        assertTrue(
            "must keep a reserve for Android",
            script.contains("RESERVE_MB=768"),
        )
        assertTrue(
            "must refuse rather than risk the device",
            script.contains("not enough free memory for Ghidra"),
        )
        assertTrue(
            "must clamp the ceiling",
            script.contains("-gt 2048 ] && HEAP_MB=2048"),
        )
    }

    /**
     * The analyzer that actually eats the memory must be off by default.
     *
     * Decompiler Parameter ID decompiles every function to infer signatures;
     * its working set is many times the binary. That is the analyzer that took
     * the heap to its ceiling.
     */
    @Test
    fun heavyAnalyzersAreOffByDefault() {
        val pre = readAsset("scripts/rea/LowMemoryAnalysis.java")
        assertTrue(
            "must name the expensive analyzer",
            pre.contains("Decompiler Parameter ID"),
        )
        assertTrue("must disable it", pre.contains("setAnalyzerEnabled"))
        assertTrue(
            "must be opt-in for full mode",
            pre.contains("\"full\"") && pre.contains("low"),
        )

        val script = readAsset("scripts/rea/ghidra-analyze.sh")
        assertTrue(
            "the wrapper must run it before analysis",
            script.contains("-preScript"),
        )
        assertTrue(
            "low must be the default",
            script.contains("GHIDRA_ANALYSIS:-low"),
        )

        val manager = readSource("app/src/main/kotlin/com/altaris/linux/core/rea/ReaManager.kt")
        assertTrue(
            "the script must be staged",
            manager.contains("\"LowMemoryAnalysis.java\""),
        )
        val ghidra = manager.substringAfter("suspend fun analyzeGhidra(")
            .substringBefore("// ------------------------------------------------------------- server")
        assertTrue("must default to low", ghidra.contains("mode: String = \"low\""))
    }

    /**
     * The REA tab must show what the agent is doing, not a log dump.
     *
     * It printed the server's log lines: that something happened, never what
     * was running, for how long, or what it found.
     */
    @Test
    fun theReaTabShowsLiveActivity() {
        val screen = readSource(
            "app/src/main/kotlin/com/altaris/linux/ui/screens/ReaScreen.kt"
        )
        assertTrue("must have a live card", screen.contains("LiveActivityCard("))
        assertTrue(
            "must show running calls",
            screen.contains("calls.filter { it.running }"),
        )
        assertTrue(
            "must show elapsed time",
            screen.contains("formatDuration("),
        )
        assertTrue(
            "must show result size",
            screen.contains("formatBytes(call.bytes)"),
        )
        assertTrue(
            "must show what was found",
            screen.contains("call.findings.take(2)"),
        )
        // The old log dump must be gone from the Overview.
        val overview = screen.substringAfter("private fun OverviewTab(")
            .substringBefore("private fun AgentsCard(")
        assertFalse(
            "must not print raw log lines as the activity view",
            overview.contains("logs.take(40).forEach"),
        )
    }

    // ------------------------------------------------------------- updater

    /** A tag and a version must compare numerically, not as strings. */
    @Test
    fun versionComparisonIsNumericAndIgnoresTheTagPrefix() {
        // 1.9.0 is older than 1.10.0; a string compare says the opposite.
        assertFalse(AppUpdater.isNewer("v1.9.0", "1.10.0"))
        assertTrue(AppUpdater.isNewer("v1.10.0", "1.9.0"))
        // The tag carries a "v" and the installed version does not.
        assertFalse(AppUpdater.isNewer("v1.3.0", "1.3.0"))
        assertFalse(AppUpdater.isNewer("1.3.0", "1.3.0"))
        // A different commit suffix is a different build.
        assertTrue(AppUpdater.isNewer("v1.3.0-abc123", "1.3.0"))
        assertTrue(AppUpdater.isNewer("v2.0.0", "1.99.99"))
    }

    /** The updater must check the project's own release, for the shipped APK. */
    @Test
    fun theUpdaterUsesTheProjectRelease() {
        val updater = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/update/AppUpdater.kt"
        )
        assertTrue("must query the release API", updater.contains("api.github.com"))
        assertTrue("must name the repo", updater.contains("HakaI963"))
        assertTrue(
            "must look for the APK the workflow publishes",
            updater.contains("app-altaris-debug.apk"),
        )
        assertTrue("must resume a partial download", updater.contains("Range"))
        assertTrue(
            "must hand the APK over through a FileProvider",
            updater.contains("FileProvider.getUriForFile"),
        )
    }

    /** Settings must offer the check, and the manifest must allow the install. */
    @Test
    fun theUpdateIsReachableFromSettings() {
        val settings = readSource(
            "app/src/main/kotlin/com/altaris/linux/ui/screens/SettingsScreen.kt"
        )
        assertTrue("must call the updater", settings.contains("AppUpdater.check("))
        assertTrue("must download", settings.contains("AppUpdater.download("))
        assertTrue("must install", settings.contains("AppUpdater.install("))

        val manifest = readSource("app/src/main/AndroidManifest.xml")
        assertTrue(
            "must declare the install permission",
            manifest.contains("REQUEST_INSTALL_PACKAGES"),
        )
        assertTrue(
            "must declare the updates provider",
            manifest.contains(".updates"),
        )
        // A dedicated, narrow provider: this URI reaches the package
        // installer, and nothing else in the cache belongs there.
        val paths = readSource("app/src/main/res/xml/update_paths.xml")
        assertTrue("must expose the cache", paths.contains("cache-path"))
        assertTrue(
            "must keep the existing provider intact",
            readSource("app/src/main/res/xml/file_paths.xml").contains("cache-path"),
        )
    }
}

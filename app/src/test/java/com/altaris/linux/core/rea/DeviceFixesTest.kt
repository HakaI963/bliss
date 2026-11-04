package com.altaris.linux.core.rea

import com.altaris.linux.core.files.Attachments
import com.altaris.linux.core.update.AppUpdater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The device-freeze fixes, the terminal attachment, and configuration updates.
 *
 * Three separate reports share one root cause class: work that was assumed to
 * be bounded was not. Ghidra's heap was never actually applied, the file
 * browser forked a root shell before it could draw, and the backdrop animated
 * forever so the composition never reached a still frame.
 */
class DeviceFixesTest {

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

    // ------------------------------------------------------------- ghidra heap

    /**
     * Ghidra must be given its ceiling through the variables it actually reads.
     *
     * The wrapper exported JAVA_OPTS and assumed Ghidra honoured it. It does
     * not: `support/analyzeHeadless` reads GHIDRA_HEADLESS_MAXMEM and
     * GHIDRA_HEADLESS_JAVA_OPTIONS, and builds its own JVM arguments. So the
     * "ceiling" was never applied, the JVM used its default (a quarter of
     * physical RAM), and the device ran out — the reported 5-6 GB heap and the
     * whole-device crash.
     */
    @Test
    fun ghidraIsGivenItsHeapThroughTheVariablesItReads() {
        val script = readAsset("scripts/rea/ghidra-analyze.sh")
        assertTrue(
            "must set GHIDRA_HEADLESS_MAXMEM",
            script.contains("export GHIDRA_HEADLESS_MAXMEM="),
        )
        assertTrue(
            "must pass extra VM args through Ghidra's own variable",
            script.contains("export GHIDRA_HEADLESS_JAVA_OPTIONS="),
        )
        // And the reasoning has to be recorded, because "JAVA_OPTS looks like
        // it works" is exactly the mistake that will be made again.
        assertTrue(
            "must explain why JAVA_OPTS is not the mechanism",
            script.contains("JAVA_OPTS") && script.contains("does not"),
        )
    }

    /** The heap still comes from MemAvailable, with a reserve and a cap. */
    @Test
    fun theHeapIsBoundedByAvailableMemory() {
        val script = readAsset("scripts/rea/ghidra-analyze.sh")
        assertTrue("must read MemAvailable", script.contains("MemAvailable"))
        assertTrue("must cap against total RAM", script.contains("MAXRAM_MB / 3"))
        assertTrue("must hold memory back for Android", script.contains("RESERVE_MB=768"))
        assertTrue(
            "must refuse rather than risk the device",
            script.contains("not enough free memory for Ghidra"),
        )
        assertTrue("must clamp the ceiling", script.contains("-gt 2048 ] && HEAP_MB=2048"))
    }

    /**
     * A target that cannot fit must be refused before the JVM starts.
     *
     * The alternative is twenty minutes of thrashing that ends with the OOM
     * killer taking the app and the launcher — the exact failure reported.
     */
    @Test
    fun anOversizedTargetIsRefusedBeforeTheJvmStarts() {
        val script = readAsset("scripts/rea/ghidra-analyze.sh")
        assertTrue(
            "must compare the target against the heap",
            script.contains("SIZE_MB * 2") && script.contains("HEAP_MB"),
        )
        assertTrue(
            "must explain the refusal",
            script.contains("Ghidra needs roughly twice the file size"),
        )
        // Two analyses at once is two heaps, which is how one analysis becomes
        // a device-wide OOM.
        assertTrue(
            "must refuse a concurrent analysis",
            script.contains("another Ghidra analysis is already running"),
        )
    }

    // --------------------------------------------------------------- backdrop

    /**
     * The backdrop must not animate.
     *
     * It ran a 26-second infinite transition on every screen for the life of
     * the process. A Compose animation invalidates the frame, so the app never
     * reached a still frame — and a route change composed two screens while
     * that was already redrawing. That is the "opening a file freezes" report.
     */
    @Test
    fun theBackdropIsStatic() {
        val source = readSource(
            "app/src/main/kotlin/com/altaris/linux/ui/components/PrismBackdrop.kt"
        )
        assertFalse(
            "must not own an infinite transition",
            source.contains("rememberInfiniteTransition"),
        )
        assertFalse("must not animate a phase", source.contains("animateFloat("))
        assertTrue(
            "must still cache the static layer",
            source.contains("drawStaticLayer("),
        )
    }

    /**
     * Status dots must share one clock.
     *
     * The comment claimed they did, but the function created a new transition
     * per call — so twenty live rows ran twenty animations and kept the
     * composition awake.
     */
    @Test
    fun statusDotsShareOneClock() {
        val source = readSource(
            "app/src/main/kotlin/com/altaris/linux/ui/components/Prism.kt"
        )
        assertTrue(
            "must provide the shared phase",
            source.contains("PrismOrbPulseProvider"),
        )
        assertTrue(
            "orbs must read the shared phase",
            source.contains("LocalPrismOrbPhase.current"),
        )
        // One transition, inside the provider.
        assertEquals(
            "exactly one infinite transition for orbs",
            1,
            Regex("rememberInfiniteTransition\\(").findAll(source).count(),
        )
        val activity = readSource("app/src/main/kotlin/com/altaris/linux/MainActivity.kt")
        assertTrue(
            "the provider must be mounted once",
            activity.contains("PrismOrbPulseProvider {"),
        )
    }

    /**
     * Route transitions must not animate their bounds.
     *
     * Every route is full-screen, so animating the size means re-measuring and
     * re-laying out the incoming screen on every frame — over a LazyColumn that
     * has not moved.
     */
    @Test
    fun routeTransitionsSnapTheirSize() {
        val activity = readSource("app/src/main/kotlin/com/altaris/linux/MainActivity.kt")
        val fn = activity.substringAfter("altarisRouteTransition()")
            .substringBefore("altarisTabTransition()")
        assertTrue(
            "must snap the size",
            fn.contains("sizeAnimationSpec = { _, _ -> tween(1) }"),
        )
        assertFalse(
            "must not interpolate the size",
            fn.contains("SizeTransform(clip = false)"),
        )
    }

    // ------------------------------------------------------------ back button

    /**
     * Back must navigate the file tree before it leaves the app.
     *
     * It exited Altaris instead: the activity handler only knew about routes,
     * and FILES was not one of them, so it fell through to finish().
     */
    @Test
    fun backNavigatesTheFileTreeAndLeavesFilesToHome() {
        val screen = readSource(
            "app/src/main/kotlin/com/altaris/linux/ui/screens/FilesScreen.kt"
        )
        assertTrue(
            "the screen must own back inside the tree",
            screen.contains("BackHandler(enabled = FileBrowser.parent("),
        )
        val activity = readSource("app/src/main/kotlin/com/altaris/linux/MainActivity.kt")
        val handler = activity.substringAfter("BackHandler {")
            .substringBefore("// Selected Distro for Wizard/Settings")
        assertTrue(
            "FILES must return to HOME rather than finishing",
            handler.contains("currentScreen == Screen.FILES"),
        )
    }

    // ------------------------------------------------------- top bar and files

    /** The search icon is gone; the palette it opened is not reachable. */
    @Test
    fun theTopBarHasNoSearchIcon() {
        val header = readSource(
            "app/src/main/kotlin/com/altaris/linux/ui/components/AltarisHeader.kt"
        )
        assertFalse(
            "must not import the search icon",
            header.contains("icons.filled.Search"),
        )
        assertFalse(
            "must not take a palette callback",
            header.contains("onOpenPalette"),
        )
    }

    // ------------------------------------------------------------ attachments

    /**
     * An attachment must land where every shell can read it.
     *
     * The terminal is where the agent runs, and there are three kinds of shell:
     * host, proot and chroot. A path in app-private storage is invisible to two
     * of them, so the destination is shared storage.
     */
    @Test
    fun attachmentsLandInSharedStorage() {
        assertTrue(
            "must use shared storage",
            Attachments.GUEST_DIR.startsWith("/sdcard/"),
        )
        val source = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/files/Attachments.kt"
        )
        assertTrue("must ask the provider for a name", source.contains("OpenableColumns.DISPLAY_NAME"))
        assertTrue("must not overwrite", source.contains("while (target.exists())"))
        assertTrue("must reject an empty file", source.contains("That file is empty"))
    }

    /** The message handed to the shell must name the real path and size. */
    @Test
    fun theAttachmentPromptNamesThePath() {
        val saved = Attachments.Saved(
            guestPath = "/sdcard/Download/Altaris/attach/crash.log",
            name = "crash.log",
            bytes = 2048,
            mime = "text/plain",
        )
        val prompt = Attachments.prompt(saved, "what failed?")
        assertTrue("must name the path", prompt.contains(saved.guestPath))
        assertTrue("must carry the message", prompt.contains("what failed?"))
        assertTrue("must state the size", prompt.contains("2 KB"))
        // With no message it still has to be a request, not a bare path.
        val bare = Attachments.prompt(saved, "")
        assertTrue("must ask for something", bare.contains("Please take a look"))
    }

    /** The terminal must offer the three pickers and send the result. */
    @Test
    fun theTerminalCanAttachAnImageVideoOrFile() {
        val screen = readSource(
            "app/src/main/kotlin/com/altaris/linux/ui/screens/TerminalScreen.kt"
        )
        assertTrue("must open the sheet", screen.contains("TerminalAttachSheet("))
        assertTrue(
            "must send through the one pty path",
            screen.contains("onSend = { text -> sendText(text) }"),
        )
        val sheet = readSource(
            "app/src/main/kotlin/com/altaris/linux/ui/terminal/TerminalAttachSheet.kt"
        )
        assertTrue("must pick an image", sheet.contains("ImageOnly"))
        assertTrue("must pick a video", sheet.contains("VideoOnly"))
        assertTrue("must pick any file", sheet.contains("arrayOf(\"*/*\")"))
    }

    // ---------------------------------------------------------- configuration

    /**
     * Configuration must re-apply itself when its version changes.
     *
     * Everything Altaris wrote into the guest was applied once at install
     * time, so a user who already had REA never received the MCP shape fix,
     * the new guide, or the jadx pin. A version stamp makes an app update
     * enough.
     */
    @Test
    fun theAgentConfigurationIsVersionedAndReapplied() {
        val manager = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/rea/ReaManager.kt"
        )
        assertTrue("must have a version", manager.contains("const val CONFIG_VERSION"))
        assertTrue("must have a stamp", manager.contains("const val CONFIG_STAMP"))
        val fn = manager.substringAfter("suspend fun ensureAgentConfig(")
            .substringBefore("suspend fun configVersion(")
        assertTrue("must compare the stamp", fn.contains("if (stamp == CONFIG_VERSION)"))
        assertTrue("must write the skill", fn.contains("SKILL.md"))
        assertTrue("must write the guide", fn.contains("guide.md"))
        assertTrue("must re-register the MCP server", fn.contains("agent-config.mjs"))
        assertTrue("must check the jadx bridge", fn.contains("ensureJadxBridge("))
        assertTrue(
            "must not stamp a failed run",
            fn.contains("Do not write the stamp on failure"),
        )
    }

    /** Both the install and the prepare path must apply it. */
    @Test
    fun theConfigurationSyncRunsOnInstallAndPrepare() {
        val manager = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/rea/ReaManager.kt"
        )
        val calls = Regex("ensureAgentConfig\\(app, distroId\\)").findAll(manager).count()
        assertTrue("must run from install and prepare, got $calls", calls >= 2)

        val protocol = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/control/McpProtocol.kt"
        )
        assertTrue(
            "an agent's first call must apply it too",
            protocol.contains("ReaManager.ensureAgentConfig(ctx, distro)"),
        )
    }

    /** The guide must tell an agent why low mode is the default. */
    @Test
    fun theInstructionsExplainLowMemoryMode() {
        val protocol = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/control/McpProtocol.kt"
        )
        val instructions = protocol.substringAfter("private val INSTRUCTIONS")
            .substringBefore("private fun ensureReaRunning")
        val flat = instructions.replace(Regex("\\s+"), " ")
        assertTrue("must mention low mode", flat.contains("low-memory mode"))
        assertTrue("must name the analyzer", flat.contains("Decompiler Parameter ID"))
        assertTrue("must warn about full mode", flat.contains("\"mode\": \"full\""))
        assertTrue("must say configuration self-applies", flat.contains("re-applied automatically"))
    }

    // ---------------------------------------------------------------- updates

    /**
     * The updater must refuse a release it cannot install.
     *
     * The APK is arm64-only and installs as com.altaris.linux. Downloading
     * 186 MB of the wrong thing — or a different flavour's package — is the
     * reported "downloads the wrong file".
     */
    @Test
    fun theUpdaterRefusesTheWrongArchitectureAndPackage() {
        val source = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/update/AppUpdater.kt"
        )
        assertTrue("must check the ABI", source.contains("SUPPORTED_ABIS"))
        assertTrue("must require arm64", source.contains("REQUIRED_ABI"))
        assertTrue("must check the package", source.contains("const val PACKAGE"))
        assertTrue(
            "must check before the download",
            source.indexOf("deviceProblem(context)") < source.indexOf("Result.Available("),
        )
        assertTrue(
            "must verify the APK before installing",
            source.contains("getPackageArchiveInfo"),
        )
    }

    /**
     * The installed version must identify the build.
     *
     * versionName was a constant "1.3.0" while the release tag is
     * "v1.3.0-<commit>", so every check saw a different suffix and offered an
     * update — then re-downloaded the same APK.
     */
    @Test
    fun theBuildStampsItsOwnVersion() {
        val gradle = readSource("app/build.gradle.kts")
        assertTrue(
            "must read the CI version name",
            gradle.contains("ALTARIS_VERSION_NAME"),
        )
        assertTrue(
            "must read the CI version code",
            gradle.contains("ALTARIS_VERSION_CODE"),
        )
        val workflow = readSource(".github/workflows/build.yml")
        assertTrue(
            "CI must stamp the commit",
            workflow.contains("ALTARIS_VERSION_NAME: \"1.3.0-\${{ github.sha }}\""),
        )
    }

    /** The comparison itself must be numeric and prefix-insensitive. */
    @Test
    fun versionComparisonHandlesTheTagShape() {
        assertFalse(AppUpdater.isNewer("v1.3.0", "1.3.0"))
        assertFalse(AppUpdater.isNewer("v1.3.0-abc", "1.3.0-abc"))
        assertTrue(AppUpdater.isNewer("v1.3.0-def", "1.3.0-abc"))
        assertFalse(AppUpdater.isNewer("v1.9.0", "1.10.0"))
        assertTrue(AppUpdater.isNewer("v1.10.0", "1.9.0"))
    }
}

package com.altaris.linux.core.rea

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The REA screen's non-analysis tabs and the file manager.
 *
 * These assert the rules that are easy to lose in a refactor: the Functions
 * tab never invents a row, the Other tab never offers an install that cannot
 * work, and the file manager never reads a chroot as an empty directory.
 */
class ReaTabsAndFilesTest {

    /** Tests run with `app/` as the working directory. */
    private fun readSource(path: String): String {
        for (candidate in listOf(path, "../$path", "../../$path")) {
            val file = File(candidate)
            if (file.isFile) return file.readText()
        }
        throw AssertionError("source not found: $path")
    }

    // ------------------------------------------------------------------ tabs

    /** Status folded into Overview; its tab slot is the debugger. */
    @Test
    fun statusIsPartOfOverviewNotItsOwnTab() {
        val screen = readSource(
            "app/src/main/kotlin/com/altaris/linux/ui/screens/ReaScreen.kt"
        )
        assertFalse(
            "there must be no Status tab",
            screen.contains("STATUS(\"Status\")"),
        )
        assertFalse(
            "the Files tab is replaced too",
            screen.contains("FILES(\"Files\")"),
        )
        assertTrue("the debugger takes the slot", screen.contains("DEBUGGER(\"Debugger\")"))
        assertTrue("the asset toolkit takes the other", screen.contains("OTHER(\"Other\")"))
        // Agents and status share one row, split evenly.
        assertTrue(
            "status must sit beside the agents",
            screen.contains("ServerStatusCard(accent, distro, bridge, calls"),
        )
        assertTrue(
            "the split must be even",
            screen.contains("AgentsCard(accent, calls, Modifier.weight(1f).fillMaxSize())"),
        )
    }

    /**
     * The Functions tab must not synthesise a row.
     *
     * It used to guess "function" from a "(" in a name and fall back to the
     * tool name for the offset column — neither came from the binary.
     */
    @Test
    fun functionsComeFromTheBinaryOnly() {
        val findings = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/rea/Findings.kt"
        )
        assertFalse(
            "must not infer a function from a name",
            findings.contains("else -> if (name.contains('(')) Row.Kind.FUNCTION"),
        )
        val screen = readSource(
            "app/src/main/kotlin/com/altaris/linux/ui/screens/ReaScreen.kt"
        )
        assertFalse(
            "the offset column must not show the tool name",
            screen.contains("row.offset.ifBlank { tool.take(12) }"),
        )
        assertTrue(
            "the empty state must say a tool has to read the binary",
            screen.contains("Nothing is shown until a tool has read the binary"),
        )
    }

    /** The debugger is a live attach, and says so when it cannot be. */
    @Test
    fun theDebuggerAttachesToTheCloneNotAWindowsBinary() {
        val debugger = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/rea/ReaDebugger.kt"
        )
        assertTrue("must attach by pid", debugger.contains("fun attach("))
        assertTrue("must run real commands", debugger.contains("fun run("))
        assertTrue("must detach", debugger.contains("fun detach("))
        // A target that cannot be resolved must fail, never attach elsewhere.
        val manager = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/rea/ReaManager.kt"
        )
        assertTrue(
            "must resolve the pid before attaching",
            manager.contains("private fun resolvePid("),
        )
        assertTrue(
            "an unresolved target must be reported",
            manager.contains("No running process matches"),
        )
    }

    // ----------------------------------------------------------------- other

    /** Every asset tool names the command that works on its format. */
    @Test
    fun everyAssetToolCarriesItsCommand() {
        val tools = ReaAssetTools.ALL
        assertTrue("must cover Unity", tools.any { it.id == "assetripper" })
        assertTrue("must cover Unreal", tools.any { it.id == "quickbms" })
        assertTrue("must cover Android assets", tools.any { it.id == "obb" })
        assertTrue("must cover Godot", tools.any { it.id == "godot" })
        assertTrue("must cover audio", tools.any { it.id == "vgmstream" })
        for (tool in tools) {
            assertTrue("${tool.id} must show a command", tool.command.isNotBlank())
            assertTrue("${tool.id} must name a probe", tool.probe.isNotBlank())
        }
    }

    /**
     * Every tool must be installable with no manual step.
     *
     * Three were wrongly marked manual: vgmstream-cli and astcenc are packaged
     * for arm64 even though their GitHub releases are x86_64 only, and unshield
     * was simply missing. A dead "Manual" chip is a tool the agent never gets.
     */
    @Test
    fun everyAssetToolInstallsWithoutHelp() {
        for (tool in ReaAssetTools.ALL) {
            assertTrue(
                "${tool.id} must have an install route: package, archive, pip or build",
                tool.installable,
            )
            val hasRoute = tool.packages.values.any { it.isNotEmpty() } ||
                tool.archive != null || tool.pip.isNotBlank() || tool.build.isNotBlank()
            assertTrue("${tool.id} must resolve to a route", hasRoute)
        }
        // The specific three that were wrong.
        assertTrue(
            "vgmstream must come from a package",
            ReaAssetTools.ALL.first { it.id == "vgmstream" }.packages["apt"]
                ?.contains("vgmstream-cli") == true,
        )
        assertTrue(
            "astcenc must come from a package",
            ReaAssetTools.ALL.first { it.id == "texturetools" }.packages["apt"]
                ?.contains("astcenc") == true,
        )
        assertTrue(
            "unshield must be listed",
            ReaAssetTools.ALL.any { it.probe == "unshield" },
        )
    }

    /** The Other tab must not offer a Manual chip any more. */
    @Test
    fun theOtherTabHasNoManualState() {
        val screen = readSource(
            "app/src/main/kotlin/com/altaris/linux/ui/screens/ReaScreen.kt"
        )
        assertFalse(
            "no Manual chip",
            screen.contains("!tool.installable -> \"Manual\""),
        )
        assertTrue(
            "the install chip is always available",
            screen.contains("else -> \"Install\""),
        )
    }

    /** Package names are per manager, not the tool id. */
    @Test
    fun packageNamesArePerDistro() {
        val tools = ReaAssetTools.ALL
        val exif = tools.first { it.id == "exiftool" }
        assertTrue(
            "Debian's package is not called exiftool",
            exif.packages["apt"]?.contains("libimage-exiftool-perl") == true,
        )
        assertTrue(
            "Fedora's package is not called exiftool",
            exif.packages["dnf"]?.contains("perl-Image-ExifTool") == true,
        )
        // And the installer must use the mapped name, never the id.
        val source = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/rea/ReaAssetTools.kt"
        )
        assertTrue(
            "must install the mapped packages",
            source.contains("ReaManager.installPackages("),
        )
        assertTrue(
            "must look the manager up",
            source.contains("ReaManager.detectPackageManager("),
        )
    }

    // ----------------------------------------------------------------- files

    /**
     * A chroot cannot be read as this uid, so every operation has to go through
     * root. A plain File.list() there returns null, which rendered an installed
     * chroot as an empty folder.
     */
    @Test
    fun chrootPathsAreAlwaysReadThroughRoot() {
        val source = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/files/FileBrowser.kt"
        )
        assertTrue("must have a root listing", source.contains("private fun listThroughRoot("))
        assertTrue(
            "must fall back to root when a listing is unreadable",
            source.contains("return@withContext listThroughRoot(path, showHidden)"),
        )
        // Every mutating operation must have a root branch.
        for (fn in listOf("fun read(", "fun write(", "fun delete(", "fun rename(")) {
            val body = source.substringAfter(fn).substringBefore("\n    suspend fun ")
            assertTrue(
                "$fn must handle a chroot path",
                body.contains("Place.Kind.CHROOT"),
            )
        }
    }

    /** Deleting must refuse to take out a top-level directory. */
    @Test
    fun deleteRefusesATopLevelDirectory() {
        val source = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/files/FileBrowser.kt"
        )
        val del = source.substringAfter("suspend fun delete(")
            .substringBefore("suspend fun rename(")
        assertTrue(
            "must guard the root of a place",
            del.contains("Refusing to delete a top-level directory"),
        )
    }

    /** The browser must offer every place it can really open. */
    @Test
    fun placesCoverHostProotAndChroot() {
        val source = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/files/FileBrowser.kt"
        )
        assertTrue("must offer host storage", source.contains("\"Internal storage\""))
        assertTrue("must offer Downloads", source.contains("\"Downloads\""))
        assertTrue(
            "must find proot rootfs directories",
            source.contains("usr/var/lib/proot-distro/containers"),
        )
        assertTrue(
            "must find chroot trees through root",
            source.contains("/data/local/altaris/chroots"),
        )
    }

    /** Utility hosts Apps and Files, and the dock has no separate Apps slot. */
    @Test
    fun appsAndFilesAreSubTabsOfUtility() {
        val utility = readSource(
            "app/src/main/kotlin/com/altaris/linux/ui/screens/UtilityScreen.kt"
        )
        assertTrue("must have an Apps sub-tab", utility.contains("APPS(\"Apps\")"))
        assertTrue("must have a Files sub-tab", utility.contains("FILES(\"Files\")"))
        assertTrue(
            "must render the clone screen",
            utility.contains("UtilityTab.APPS -> ClonedAppsScreen()"),
        )
        assertTrue(
            "must render the file manager",
            utility.contains("UtilityTab.FILES -> FilesScreen("),
        )
        val dock = readSource(
            "app/src/main/kotlin/com/altaris/linux/ui/components/AltarisDock.kt"
        )
        assertFalse("the Apps dock slot must be gone", dock.contains("APPS(\"Apps\")"))
        assertTrue("Utility takes the slot", dock.contains("UTILITY(\"Utility\""))
    }

    /** The guest home must expose the phone's Downloads folder. */
    @Test
    fun downloadsAreVisibleInTheGuestHome() {
        val script = readSource(
            "app/src/main/assets/scripts/common/setup/altaris_guest_common.sh"
        )
        assertTrue(
            "must link Downloads into the guest home",
            script.contains("ln -sfn /sdcard/Download \"\$_h/Downloads\""),
        )
    }

    /**
     * The debugger belongs to the agent.
     *
     * It was app-only: a console a person had to open and drive. An agent had
     * no way to attach, so runtime questions were answered by looking at
     * screenshots. Every operation must be reachable over MCP.
     */
    @Test
    fun theAgentCanDriveTheDebugger() {
        val mcp = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/control/McpProtocol.kt"
        )
        for (tool in listOf(
            "altaris_debug_attach",
            "altaris_debug_command",
            "altaris_debug_state",
            "altaris_debug_detach",
        )) {
            assertTrue("$tool must be registered", mcp.contains("\"" + tool + "\""))
            // Registered and dispatched are two different lists; a tool that is
            // only listed fails at call time.
            assertTrue(
                "$tool must be dispatched",
                mcp.contains("\"" + tool + "\" ->"),
            )
        }
        // And the result must carry the transcript, or the agent sees nothing.
        assertTrue("must return the transcript", mcp.contains("put(\"transcript\""))
        assertTrue("must report the attached pid", mcp.contains("put(\"attachedPid\""))
    }

    /** The tab must present itself as the agent's tool, not a manual console. */
    @Test
    fun theDebuggerTabSaysTheAgentDrivesIt() {
        val screen = readSource(
            "app/src/main/kotlin/com/altaris/linux/ui/screens/ReaScreen.kt"
        )
        assertTrue(
            "must say the agent drives the session",
            screen.contains("The agent attaches"),
        )
    }

    /**
     * The floating dock must not cover the end of a scrolling tab.
     *
     * The dock is drawn over the content, and the scaffold only reserved the
     * top bar's height — so the last card in REA → Other (Unknown containers)
     * sat underneath it and could not be reached.
     */
    @Test
    fun theDockReservesItsHeightInTheContent() {
        val scaffold = readSource(
            "app/src/main/kotlin/com/altaris/linux/ui/components/GlassScaffold.kt"
        )
        assertTrue(
            "must accept the dock height",
            scaffold.contains("bottomBarInset: Dp = 0.dp"),
        )
        assertTrue(
            "must reserve it in the content",
            scaffold.contains(".padding(bottom = bottomBarInset)"),
        )
        val main = readSource("app/src/main/kotlin/com/altaris/linux/MainActivity.kt")
        assertTrue(
            "the docked shell must pass the dock height",
            main.contains("bottomBarInset = Prism.NavBarHeight"),
        )
    }

    /**
     * The guide must send the agent to the clone, not to /sdcard.
     *
     * An agent asked to "attach to <app> and see what it does" searched the
     * filesystem instead, because the REA guide described artifact analysis and
     * never mentioned clones, the debugger, or how to get a clone's files. It
     * read as a tool for files you already have.
     */
    @Test
    fun theGuidePointsAtTheCloneAndTheDebugger() {
        val guide = ReaKnowledge.GUIDE
        assertTrue(
            "must say the target is a clone",
            guide.contains("The target is a clone"),
        )
        assertTrue(
            "must name the clone listing tool",
            guide.contains("altaris_clones"),
        )
        assertTrue(
            "must name the export tool",
            guide.contains("altaris_rea_clone_apk"),
        )
        assertTrue(
            "must name the debugger",
            guide.contains("altaris_debug_attach"),
        )
        assertTrue(
            "must say searching /sdcard is wrong",
            guide.contains("Do not search the filesystem"),
        )
        // The short prompt an MCP client may request must carry it too, since
        // that is all some clients ever read.
        assertTrue(
            "the prompt must mention the clone",
            ReaKnowledge.PROMPT.contains("clone"),
        )
        assertTrue(
            "the prompt must name the debugger",
            ReaKnowledge.PROMPT.contains("altaris_debug_attach"),
        )
    }

    /**
     * Exporting a clone must hand over its native libraries too.
     *
     * The guide now promises them, and an app's real logic is often in
     * libil2cpp.so. Copying only the APKs left an agent that wanted native code
     * with nothing to open.
     */
    @Test
    fun exportingACloneIncludesItsNativeLibraries() {
        val manager = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/rea/ReaManager.kt"
        )
        val export = manager.substringAfter("suspend fun exportCloneApk(")
            .substringBefore("// ------------------------------------------------------------- helpers")
        assertTrue(
            "must read the native library dir from the package manager",
            export.contains("nativeLibraryDir"),
        )
        assertTrue(
            "must copy the .so files",
            export.contains("*.so"),
        )
        assertTrue(
            "must print the copied paths",
            export.contains("-libs"),
        )
    }

    /**
     * Finding the clone's PID must go through root.
     *
     * Android hides other apps' /proc entries from the app uid. The first
     * version read /proc directly, so it listed only this app and its own
     * service — and then failed to attach to a clone that was running the whole
     * time, reporting "no running process matches".
     */
    @Test
    fun theProcessTableIsReadThroughRoot() {
        val manager = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/rea/ReaManager.kt"
        )
        assertTrue("must have a root reader", manager.contains("procTableThroughRoot("))
        assertTrue(
            "must prefer the root table",
            manager.contains("if (viaRoot.isNotEmpty()) return viaRoot"),
        )
        // The NUL bytes in /proc/<pid>/cmdline separate arguments; trimming them
        // from the ends left the whole command line as the process name.
        assertTrue(
            "must take argv[0], not the whole command line",
            manager.contains("substringBefore('\\u0000')"),
        )
        assertTrue(
            "must reject shell commands and paths as package names",
            manager.contains("private fun isPackageName("),
        )
    }

    /**
     * The attach must run where the PID is visible.
     *
     * A debugger inside the distro has its own PID namespace, so the host PID
     * does not exist there. That is why the first attach did nothing.
     */
    @Test
    fun theAttachRunsOnTheHostSide() {
        val manager = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/rea/ReaManager.kt"
        )
        val attach = manager.substringAfter("suspend fun attachDebugger(")
            .substringBefore("suspend fun detachDebugger(")
        assertTrue(
            "must try frida on the host first",
            attach.contains("ReaFrida.isServerRunning()"),
        )
        assertTrue(
            "must reach the server over loopback",
            attach.contains("127.0.0.1:"),
        )
    }

    /** Frida is a real feature: server on the host, CLI in the distro. */
    @Test
    fun fridaIsInstalledOnBothSides() {
        val frida = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/rea/ReaFrida.kt"
        )
        assertTrue(
            "must install the server on the host",
            frida.contains("HOST_DIR = \"/data/local/altaris/frida\""),
        )
        assertTrue(
            "must pin the release",
            frida.contains("const val VERSION = \"17.25.1\""),
        )
        assertTrue(
            "must bind to loopback only",
            frida.contains("-l 127.0.0.1:"),
        )
        assertTrue(
            "must install the CLI in the distro",
            frida.contains("frida-tools"),
        )
        // Both halves are exposed to the agent.
        val mcp = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/control/McpProtocol.kt"
        )
        assertTrue("must expose install", mcp.contains("\"altaris_frida_install\""))
        assertTrue("must expose stop", mcp.contains("\"altaris_frida_stop\""))
    }

    /** The guide must say how to make runtime changes, not just read state. */
    @Test
    fun theGuideExplainsFridaForRuntimeChanges() {
        val guide = ReaKnowledge.GUIDE
        assertTrue(
            "must explain the $ prefix",
            guide.contains("starting with `$` is JavaScript"),
        )
        assertTrue(
            "must say the debugger answers runtime questions",
            guide.contains("runtime"),
        )
    }

    /**
     * Decompression must not depend on host binaries.
     *
     * The first version shelled out to `xz`, then to `python3`. Android ships
     * neither — no xz binary, and python3 lives inside a distro, not on the
     * device — so both branches failed on every device and reported "cannot
     * decompress", which read as a corrupt download.
     */
    @Test
    fun decompressionUsesAnInAppDecoder() {
        val xz = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/rea/XzArchive.kt"
        )
        assertTrue(
            "must use the bundled pure-Java decoder",
            xz.contains("XZInputStream"),
        )
        assertFalse(
            "must not shell out to xz",
            xz.contains("ProcessBuilder(\"xz\""),
        )
        assertFalse(
            "must not shell out to python3",
            xz.contains("python3 -c"),
        )
        // A partial file must never be left behind looking installed.
        assertTrue("must write via a temp file", xz.contains(".part"))
        assertTrue("must check the decoded size", xz.contains("minBytes"))

        for (path in listOf(
            "app/src/main/kotlin/com/altaris/linux/core/rea/ReaFrida.kt",
            "app/src/main/kotlin/com/altaris/linux/core/rea/FridaGadget.kt",
        )) {
            val source = readSource(path)
            assertTrue(
                "$path must delegate to the shared decoder",
                source.contains("XzArchive.decompress("),
            )
            assertFalse(
                "$path must not run xz itself",
                source.contains("ProcessBuilder(\"xz\""),
            )
        }
        // And the dependency must actually be declared.
        val gradle = readSource("app/build.gradle.kts")
        assertTrue("must depend on the xz library", gradle.contains("libs.xz"))
    }

    /**
     * The debugger must offer clones, not every process on the device.
     *
     * It listed whatever /proc showed, which on a Xiaomi phone is a wall of
     * system services — com.miui.aod, com.xiaomi.aicr, webview sandboxes — and
     * the clone was lost in it.
     */
    @Test
    fun theDebuggerListsClonesNotSystemProcesses() {
        val manager = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/rea/ReaManager.kt"
        )
        val running = manager.substringAfter("private fun runningPackages(")
            .substringBefore("private fun clonePackages(")
        assertTrue(
            "must source the list from clones",
            running.contains("clonePackages()"),
        )
        assertFalse(
            "must not return every process",
            running.contains(".filter { isPackageName(it) }"),
        )
        assertTrue(
            "must read the clone registry",
            manager.contains("clones.json"),
        )
        // Only clones with a live process can be attached to.
        assertTrue(
            "must intersect with live processes",
            running.contains("processTable()"),
        )
    }

    /**
     * The frida CLI must be installable on a minimal rootfs.
     *
     * "No module named pip" is normal: python3 ships without pip on many
     * images. The first version called `python3 -m pip` directly and reported
     * the whole install as failed without ever asking the distro for pip.
     */
    @Test
    fun theFridaCliInstallsPipWhenItIsMissing() {
        val frida = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/rea/ReaFrida.kt"
        )
        val client = frida.substringAfter("suspend fun installClient(")
            .substringBefore("suspend fun state(")
        assertTrue(
            "must detect a missing pip",
            client.contains("python3 -m pip --version"),
        )
        assertTrue(
            "must install pip from the distro's package manager",
            client.contains("python3-pip") || client.contains("py3-pip"),
        )
        assertTrue(
            "must use the distro package manager",
            client.contains("ReaManager.packageInstallScript("),
        )
        // And it must have a fallback when pip cannot be made to work.
        assertTrue(
            "must fall back to the distro's own package",
            client.contains("frida-tools"),
        )
        // The failure must name what was tried, not just "missing".
        assertTrue(
            "must report what was tried",
            client.contains("tried:"),
        )
        val manager = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/rea/ReaManager.kt"
        )
        assertTrue(
            "must offer a one-shot package install",
            manager.contains("fun packageInstallScript("),
        )
        // Each manager needs its own invocation; a generic one installs nothing.
        for (hint in listOf("apt-get install", "apk add", "dnf install", "pacman -Sy")) {
            assertTrue("must handle $hint", manager.contains(hint))
        }
    }

    /**
     * frida-server must not run permanently.
     *
     * It is a resident root process holding a loopback socket and a
     * ptrace-capable agent; leaving it up all day is pure idle cost and was
     * what drained the battery.
     */
    @Test
    fun fridaServerStopsWhenIdle() {
        val frida = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/rea/ReaFrida.kt"
        )
        assertTrue("must have an idle deadline", frida.contains("IDLE_STOP_MS"))
        assertTrue("must arm a watchdog", frida.contains("private fun armWatchdog("))
        assertTrue(
            "must start per session, not permanently",
            frida.contains("suspend fun ensureRunningForSession("),
        )
        val manager = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/rea/ReaManager.kt"
        )
        val attach = manager.substringAfter("suspend fun attachDebugger(")
            .substringBefore("suspend fun detachDebugger(")
        assertTrue(
            "attach must start the server on demand",
            attach.contains("ReaFrida.ensureRunningForSession()"),
        )
        assertTrue(
            "attach must reset the idle timer",
            attach.contains("ReaFrida.touch()"),
        )
        val detach = manager.substringAfter("suspend fun detachDebugger(")
            .substringBefore("private fun resolvePid(")
        assertTrue(
            "detach must stop the server",
            detach.contains("ReaFrida.stopServer()"),
        )
    }

    /** The debugger tab must be short, and root-only rows must be conditional. */
    @Test
    fun theDebuggerTabIsTerseAndRootAware() {
        val screen = readSource(
            "app/src/main/kotlin/com/altaris/linux/ui/screens/ReaScreen.kt"
        )
        val tab = screen.substringAfter("private fun DebuggerTab(")
            .substringBefore("// --------------------------------------------------------------------- other")
        assertFalse(
            "must not print the agent tool list in the UI",
            tab.contains("Agent tools: altaris_debug_attach"),
        )
        assertTrue(
            "must gate the root-only backend",
            tab.contains("if (hasRoot) PrismRow("),
        )
    }

    /**
     * Attaching must start the clone when it is not running.
     *
     * The agent was told the app was not running and gave up, when launching it
     * is one call away.
     */
    @Test
    fun attachStartsTheCloneWhenItIsNotRunning() {
        val manager = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/rea/ReaManager.kt"
        )
        val attach = manager.substringAfter("suspend fun attachDebugger(")
            .substringBefore("suspend fun detachDebugger(")
        assertTrue(
            "must launch instead of refusing",
            attach.contains("launchAndWaitFor("),
        )
        val launch = manager.substringAfter("private suspend fun launchAndWaitFor(")
            .substringBefore("/** Detach whatever the debugger holds. */")
        assertTrue(
            "must launch the clone",
            launch.contains("HeadlessCloneEngine.apiLaunch("),
        )
        assertTrue(
            "must wait for the process to appear",
            launch.contains("resolvePid("),
        )
    }

    /**
     * The guide must forbid filesystem searching and require launching.
     *
     * An agent asked to attach to an app searched /sdcard for files and then
     * reported the app was not running — both wrong, and both things the guide
     * failed to rule out.
     */
    @Test
    fun theGuideForbidsSearchingAndRequiresLaunching() {
        val guide = ReaKnowledge.GUIDE
        assertTrue(
            "must say not to search the filesystem",
            guide.contains("Do not search the filesystem"),
        )
        assertTrue(
            "must name the launch tool",
            guide.contains("altaris_launch_clone"),
        )
        assertTrue(
            "must say the debugger starts the clone",
            guide.contains("starts the clone"),
        )
        val mcp = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/control/McpProtocol.kt"
        )
        assertTrue(
            "the clone instructions must forbid searching too",
            mcp.contains("no folder to search"),
        )
        assertTrue(
            "and must say not-running is not a stop",
            mcp.contains("is never a reason to stop"),
        )
        // The attach tool itself must advertise the launch behaviour.
        assertTrue(
            "the attach tool must say it starts the clone",
            mcp.contains("starts the clone itself when it is not running"),
        )
    }

    /** The clone MCP server must tell an agent there is no real device. */
    @Test
    fun theCloneServerSaysThereIsNoRealDevice() {
        val source = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/control/McpProtocol.kt"
        )
        assertTrue("must publish instructions", source.contains(".put(\"instructions\""))
        assertTrue(
            "must say every app is a clone",
            source.contains("Every app you can reach is a clone"),
        )
        assertTrue(
            "must prefer the node tree",
            source.contains("That is the fast path"),
        )
        assertTrue(
            "must mark OCR as the fallback",
            source.contains("are the fallback"),
        )
    }
}

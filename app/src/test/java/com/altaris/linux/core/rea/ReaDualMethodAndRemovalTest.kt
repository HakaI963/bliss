package com.altaris.linux.core.rea

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Failures reported after the first REA release.
 *
 * Each test corresponds to something the user actually saw:
 *
 *  - removing the analysis environment said "container could not be removed";
 *  - Tools always opened on proot, so it was impossible to tell which method
 *    was installed or what was being downloaded;
 *  - installing chroot when proot existed was not offered;
 *  - the MCP card only knew two clients;
 *  - the clone viewer stole the phone's keyboard.
 */
class ReaDualMethodAndRemovalTest {

    // ----------------------------------------------------------------- removal

    /**
     * The removal script used to find processes by matching the chroot path in
     * `ps` output. A chrooted process has an ordinary cmdline, so nothing was
     * killed, the mounts stayed busy, and every removal refused.
     *
     * The reliable identity is /proc/PID/root.
     */
    @Test
    fun removalKillsProcessesByTheirRootNotTheirCommandLine() {
        val script = readAsset("scripts/chroot/remove-chroot.sh")
        assertTrue(
            "must identify chrooted processes by /proc/PID/root",
            script.contains("readlink \"\$_d/root\""),
        )
        assertFalse(
            "matching the path in ps output never matched a chrooted process",
            script.contains("ps -A -o PID,ARGS"),
        )
        // And it must verify they are gone before touching mounts.
        assertTrue(
            "must refuse while a process still has the chroot as its root",
            script.contains("processes still rooted in the chroot"),
        )
        val kill = script.indexOf("rooted_pids")
        val unmount = script.indexOf("unmounting \$GUEST")
        assertTrue("processes must be stopped before unmounting", kill in 0..unmount)
    }

    /** A prefix match must not let /…/rea match /…/react. */
    @Test
    fun mountMatchingIsPathBoundaryAware() {
        val script = readAsset("scripts/chroot/remove-chroot.sh")
        assertTrue(
            "must match the path as a directory prefix",
            script.contains("index(\$2, base \"/\") == 1"),
        )
    }

    /** Deletion must be verified as root, not from a process that cannot see it. */
    @Test
    fun removalVerifiesThroughRoot() {
        val installer = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/rea/ReaInstaller.kt"
        )
        val remove = installer.substringAfter("private fun removeChroot(")
            .substringBefore("fun parseDownload")
        assertTrue(
            "must verify the deletion as root",
            remove.contains("chrootGone("),
        )
        assertFalse(
            "File.exists() is always false for /data/local from the app uid",
            remove.contains("!File(path).exists() ||"),
        )
        // The script's exit code matters too: a refusal must not read as success.
        assertTrue(
            "must require the script to succeed",
            remove.contains("gone && result.exitCode == 0"),
        )
    }

    /** Every container is removed, not just the active one. */
    @Test
    fun removalCoversBothMethods() {
        val distro = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/rea/ReaDistro.kt"
        )
        val uninstall = distro.substringAfter("suspend fun uninstallBlocking(")
            .substringBefore("private fun appendLog(")
        assertTrue(
            "must enumerate every installed method",
            uninstall.contains("val targets = presentMethods(app, probeRoot = true)"),
        )
        assertTrue("must loop over them", uninstall.contains("for (method in targets)"))
        assertTrue(
            "must clear the remembered choice when everything is gone",
            uninstall.contains("remove(KEY_ACTIVE_METHOD)"),
        )
    }

    // ------------------------------------------------------------ dual method

    /**
     * Both containers can exist. The choice is explicit and remembered, rather
     * than "whichever happened to be ready first".
     */
    @Test
    fun theActiveMethodIsRemembered() {
        val distro = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/rea/ReaDistro.kt"
        )
        assertTrue("must persist the choice", distro.contains("KEY_ACTIVE_METHOD"))
        assertTrue(
            "must offer a switch",
            distro.contains("fun select(context: Context, method: Method)"),
        )
        val choose = distro.substringAfter("private fun chooseActive(")
            .substringBefore("private fun prefs(")
        assertTrue("must honour the remembered method", choose.contains("remembered in present"))
        assertTrue(
            "must fall back rather than fail when it was uninstalled",
            choose.contains("present.firstOrNull { it == Method.PROOT }"),
        )
    }

    /** The state must carry every installed method, not only the active one. */
    @Test
    fun theStateListsEveryInstalledMethod() {
        val distro = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/rea/ReaDistro.kt"
        )
        assertTrue("must expose the set", distro.contains("val methods: Set<Method>"))
        assertTrue(
            "must populate it from the filesystem",
            distro.contains("methods = present.toSet()"),
        )
        // Installing the second method must not forget the first.
        assertTrue(
            "installing must preserve the other method",
            distro.contains("methods = presentMethods(app, probeRoot = true).toSet()"),
        )
    }

    /** The bridge must follow the selection instead of keeping the old process. */
    @Test
    fun theBridgeSwitchesWhenTheMethodChanges() {
        val bridge = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/rea/ReaBridge.kt"
        )
        val ensure = bridge.substringAfter("suspend fun ensureRunning(")
            .substringBefore("fun waitingForCore(")
        assertTrue(
            "must compare the running distro with the selected one",
            ensure.contains("wanted != _state.value.distroId"),
        )
        assertTrue("must stop the old session", ensure.contains("session?.stop()"))
    }

    /** Tools must not open on a hardcoded proot selection. */
    @Test
    fun theToolsChipsReflectWhatIsInstalled() {
        val screen = readSource(
            "app/src/main/kotlin/com/altaris/linux/ui/screens/ToolsScreen.kt"
        )
        assertFalse(
            "must not hardcode proot as the opening selection",
            screen.contains("var method by remember { mutableStateOf(ReaDistro.Method.PROOT) }"),
        )
        assertTrue(
            "must seed from the active method",
            screen.contains("mutableStateOf(state.method ?: ReaDistro.Method.PROOT)"),
        )
        assertTrue(
            "must show which method is in use",
            screen.contains("\"In use\""),
        )
        assertTrue(
            "must offer installing the selected method",
            screen.contains("ReaDistro.install(context, method)"),
        )
        assertTrue(
            "must offer switching without reinstalling",
            screen.contains("ReaDistro.select(context, method)"),
        )
        // Switching must never fall through to an install.
        val switch = screen.substringAfter("if (selectedInstalled && !selectedActive) {")
            .substringBefore("} else {")
        assertTrue("switching uses select", switch.contains("ReaDistro.select(context, method)"))
        assertFalse("switching must not install", switch.contains("ReaDistro.install("))
    }

    // ---------------------------------------------------------------- clients

    /** Every client block must embed the live endpoint. */
    @Test
    fun everyClientBlockUsesTheLiveEndpoint() {
        val configs = ReaBridge.clientConfigs()
        assertTrue("must offer a useful set", configs.size >= 12)
        for (config in configs) {
            assertTrue(
                "${config.label} must name the endpoint",
                config.body.contains("/mcp") || config.body.contains("rea"),
            )
            assertTrue("${config.label} must say where it goes", config.where.isNotBlank())
        }
        val labels = configs.map { it.label }
        // The clients people actually asked about.
        for (expected in listOf(
            "OpenCode v2", "Claude Code", "Codex", "Cursor", "Kiro", "Pi", "Hermes",
        )) {
            assertTrue("$expected must be offered", labels.contains(expected))
        }
    }

    /** OpenCode v2 must not be handed the ignored `enabled` flag. */
    @Test
    fun theOpenCodeBlockOmitsTheIgnoredFlag() {
        val body = ReaBridge.configJson()
        assertFalse(body.contains("\"enabled\""))
        assertTrue(body.contains("\"servers\""))
        // The two fields that made V2 reject the entry.
        assertTrue("a URL server is type remote", body.contains("\"type\": \"remote\""))
        assertTrue("OAuth must be off", body.contains("\"oauth\": false"))
    }

    /**
     * A client probing for the newer MCP revision must get a clean refusal, not
     * a forwarded request REA cannot answer (which hangs until the timeout and
     * looks like a dead server).
     */
    @Test
    fun theBridgeAnswersRevisionProbesWithoutHanging() {
        val source = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/rea/BridgeServer.kt"
        )
        assertTrue(
            "must answer server/discover itself",
            source.contains("if (method == \"server/discover\")"),
        )
        assertTrue(
            "the refusal must name the working protocol",
            source.contains("use protocol 'legacy'"),
        )
        assertTrue("must answer ping", source.contains("if (method == \"ping\")"))
    }

    // ---------------------------------------------------------------- keyboard

    /**
     * The clone viewer must not move the phone's IME onto the virtual display.
     * Doing so stole the real keyboard and collapsed it when the viewer closed.
     */
    @Test
    fun theCloneDisplayKeepsTheSystemImeAway() {
        val session = readSource(
            "app/src/main/java/com/altaris/linux/clone/engine/VirtualDisplaySession.java"
        )
        assertTrue(
            "must hide the IME on the virtual display",
            session.contains("DISPLAY_IME_POLICY_HIDE"),
        )
        assertFalse(
            "must not move the system IME onto the display",
            session.contains("setDisplayImePolicy(displayId, WindowManagerBridge.DISPLAY_IME_POLICY_LOCAL)"),
        )
    }

    /** The viewer supplies its own keyboard, injected per display. */
    @Test
    fun theViewerHasItsOwnKeyboard() {
        val screen = readSource(
            "app/src/main/kotlin/com/altaris/linux/ui/screens/ClonedAppsScreen.kt"
        )
        assertTrue("must render a keyboard", screen.contains("private fun CloneKeyboard("))
        assertTrue(
            "must inject text into this clone",
            screen.contains("HeadlessCloneEngine.text(text, packageName)"),
        )
        assertTrue(
            "must inject navigation keys too",
            screen.contains("HeadlessCloneEngine.key("),
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

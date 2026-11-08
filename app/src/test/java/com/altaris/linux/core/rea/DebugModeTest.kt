package com.altaris.linux.core.rea

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Two failures that took the whole device down.
 *
 * **No choice of mode.** Gadget was only injected when root was *absent*, so
 * on a rooted device there was no way to ask for the no-root path at all — the
 * clone was built without the gadget and every no-root attach failed.
 *
 * The mode is a real setting: it is persisted, resolved against root, honoured
 * by the clone build, by attach, and by the installer.
 */
class DebugModeTest {

    // ------------------------------------------------------- mode selection

    /** The three modes must exist, with stable ids, and resolve sensibly. */
    @Test
    fun theDebugModeIsExplicitAndResolved() {
        val distro = readSource("app/src/main/kotlin/com/altaris/linux/core/rea/ReaDistro.kt")
        assertTrue("must define the modes", distro.contains("enum class DebugMode("))
        for (id in listOf("\"auto\"", "\"root\"", "\"gadget\"")) {
            assertTrue("must offer $id", distro.contains(id))
        }
        assertTrue("must persist the choice", distro.contains("fun setDebugMode("))
        assertTrue("must read the choice", distro.contains("fun debugMode(context: Context): DebugMode"))
        assertTrue(
            "must resolve auto against root",
            distro.contains("fun effectiveDebugMode(context: Context, hasRoot: Boolean)"),
        )
        // AUTO means "root when it exists, gadget when it does not".
        val eff = distro.substringAfter("fun effectiveDebugMode(")
            .substringBefore("// ----------------------------------------------------------- clone-only")
        assertTrue("auto must consult root", eff.contains("if (hasRoot) DebugMode.ROOT"))
        assertTrue("auto must fall back to gadget", eff.contains("DebugMode.GADGET"))
    }

    /**
     * The clone build must honour the mode, not only the absence of root.
     *
     * This is the reported bug: with root present, the injector returned early
     * and the gadget was never baked in, so choosing the no-root path changed
     * nothing.
     */
    @Test
    fun theCloneBuildInjectsGadgetWhenTheModeSaysSo() {
        val manager = readSource(
            "app/src/main/java/com/altaris/linux/clone/engine/CloneManager.java"
        )
        val inject = manager.substringAfter("private void injectGadgetIfNeeded(File apk)")
            .substringBefore("private static void hideLauncher(")
        assertTrue(
            "must ask the chosen mode",
            inject.contains("effectiveDebugMode(context, hasRoot)"),
        )
        assertTrue(
            "must compare against GADGET",
            inject.contains("ReaDistro.DebugMode.GADGET"),
        )
        assertTrue(
            "must inject when the mode says gadget",
            inject.contains(".inject(apk, gadget)"),
        )
        // The old early return on root is what made the choice impossible.
        assertFalse(
            "must not bail out merely because root exists",
            inject.contains("isRootAvailable()) {\n            return;"),
        )
    }

    /** The attach path must obey the mode rather than always starting a server. */
    @Test
    fun theAttachHonoursTheMode() {
        val manager = readSource("app/src/main/kotlin/com/altaris/linux/core/rea/ReaManager.kt")
        val attach = manager.substringAfter("suspend fun attachDebugger(")
            .substringBefore("/** The port a Gadget-instrumented clone listens on")
        assertTrue("must resolve the mode", attach.contains("effectiveDebugMode(ctx, hasRoot)"))
        assertTrue(
            "must only start a root server in root mode",
            attach.contains("mode == ReaDistro.DebugMode.ROOT"),
        )
        assertTrue(
            "must try gadget in gadget mode",
            attach.contains("mode == ReaDistro.DebugMode.GADGET"),
        )
        // A clone built before the gadget cannot be fixed at attach time; the
        // agent has to be told, not silently given root instead.
        assertTrue(
            "must explain a pre-gadget clone",
            attach.contains("built before Frida Gadget"),
        )
    }

    /** Installing Frida must install the half the chosen mode uses. */
    @Test
    fun installingFridaFollowsTheMode() {
        val debugger = readSource("app/src/main/kotlin/com/altaris/linux/core/rea/ReaDebugger.kt")
        val install = debugger.substringAfter("suspend fun installFrida(")
            .substringBefore("suspend fun prepareGadget(")
        assertTrue(
            "must check the mode before downloading a root daemon",
            install.contains("effectiveDebugMode(context, hasRoot)"),
        )
        assertTrue(
            "must install the gadget in gadget mode",
            install.contains("FridaGadget.ensure(context)"),
        )
        // The gadget branch has to come before the server download.
        assertTrue(
            "the mode check must precede the server install",
            install.indexOf("effectiveDebugMode") < install.indexOf("ReaFrida.installServer"),
        )
    }

    /** The agent can read and set the mode. */
    @Test
    fun theModeIsExposedToAgents() {
        val mcp = readSource("app/src/main/kotlin/com/altaris/linux/core/control/McpProtocol.kt")
        assertTrue("must advertise it", mcp.contains("\"altaris_debug_mode\","))
        assertTrue("must route it", mcp.contains("\"altaris_debug_mode\" ->"))
        val route = mcp.substringAfter("\"altaris_debug_mode\" ->")
            .substringBefore("\"altaris_debug_detach\" ->")
        assertTrue("must accept a mode", route.contains("ReaDistro.setDebugMode(ctx, mode)"))
        assertTrue("must report the effective one", route.contains("\"effective\""))
        assertTrue("must reject an unknown mode", route.contains("Use auto, root or gadget"))
    }

    // ------------------------------------------------------ instructions

    /** The agent must know the two modes and the risk of each. */
    @Test
    fun theInstructionsExplainBothModes() {
        val mcp = readSource("app/src/main/kotlin/com/altaris/linux/core/control/McpProtocol.kt")
        val instructions = mcp.substringAfter("private val INSTRUCTIONS = \"\"\"")
            .substringBefore("\"\"\".trimIndent()")
            .replace(Regex("\\s+"), " ")
        assertTrue(
            "must name the tool",
            instructions.contains("altaris_debug_mode"),
        )
        assertTrue(
            "must describe root mode",
            instructions.contains("attaches from outside"),
        )
        assertTrue(
            "must warn that root can reach any process",
            instructions.contains("attach to **any** process"),
        )
        assertTrue(
            "must say gadget is confined",
            instructions.contains("*cannot* reach another app"),
        )
        assertTrue(
            "must say gadget only applies to new clones",
            instructions.contains("built after it was downloaded"),
        )
        assertTrue(
            "must say a pid works too",
            instructions.contains("package or its pid"),
        )
    }

    /** The REA guide must carry the same warning, since that is its server. */
    @Test
    fun theReaGuideExplainsTheRuntimeTools() {
        val guide = ReaKnowledge.REA_GUIDE.replace(Regex("\\s+"), " ")
        assertTrue(
            "must name the tool",
            guide.contains("start_frida_session"),
        )
        assertTrue(
            "must say a pid works",
            guide.contains("package name or its pid"),
        )
        assertTrue(
            "must say the daemon runs as root",
            guide.contains("runs as root"),
        )
        assertTrue(
            "must explain the provider error",
            guide.contains("capability_unavailable"),
        )
        assertTrue(
            "must name the missing binding",
            guide.contains("Node `frida` binding"),
        )
        assertTrue(
            "must point at the repair without naming an altaris tool",
            guide.contains("Frida repair action"),
        )
        // The rea server has no altaris_* tools; naming one here would send the
        // agent to a name that does not exist on this server.
        assertFalse(
            "the rea guide must not name altaris_frida_repair",
            guide.contains("altaris_frida_repair"),
        )
    }

    /**
     * The binding repair must be reachable and must restart REA.
     *
     * REA reads the module at startup, so repairing the files without a restart
     * leaves the provider reporting unavailable — which looks like the repair
     * did nothing.
     */
    @Test
    fun theBindingRepairRestartsRea() {
        val mcp = readSource("app/src/main/kotlin/com/altaris/linux/core/control/McpProtocol.kt")
        assertTrue("must advertise it", mcp.contains("\"altaris_frida_repair\","))
        assertTrue("must route it", mcp.contains("\"altaris_frida_repair\" ->"))
        val route = mcp.substringAfter("\"altaris_frida_repair\" ->")
            .substringBefore("\"altaris_gadget_prepare\" ->")
        assertTrue("must repair", route.contains("repairFridaBinding("))
        assertTrue("must stop REA", route.contains("ReaManager.stop()"))
        assertTrue("must restart REA", route.contains("ReaManager.start(ctx, distro)"))
        assertTrue("must report it", route.contains("\"restarted\""))
        // And the repair itself must verify, not assume.
        val manager = readSource("app/src/main/kotlin/com/altaris/linux/core/rea/ReaManager.kt")
        val repair = manager.substringAfter("suspend fun repairFridaBinding(")
            .substringBefore("// --------------------------------------------------------------- frida")
        assertTrue("must verify the import", repair.contains("fridaNodeStatus("))
        assertTrue("must only succeed when importable", repair.contains("node.importable"))
    }

    // ------------------------------------------------------------- helpers

    private fun readSource(path: String): String {
        val relative = path.removePrefix("app/")
        for (candidate in listOf(path, relative, "../$path", "../$relative", "../../$path")) {
            val file = File(candidate)
            if (file.isFile) return file.readText()
        }
        throw AssertionError("source not found: $path (cwd=" + File(".").absolutePath + ")")
    }
}

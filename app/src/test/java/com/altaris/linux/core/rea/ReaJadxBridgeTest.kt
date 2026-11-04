package com.altaris.linux.core.rea

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The jadx bridge REA's Android lane requires.
 *
 * REA does not ship or fetch it, and refuses every Android/Java tool until
 * REA_JADX_MCP_JAR points at a jar it accepts. The installed `jadx` CLI is a
 * different program and does not satisfy it — which is what made the whole
 * manifest/DEX/decompile lane fail with capability_unavailable.
 */
class ReaJadxBridgeTest {

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
     * The pin is the whole point.
     *
     * REA checks the bridge's reported version *and* the release version, so a
     * newer jar fails with the same "not configured" error as a missing one.
     * Upstream is already past this version, which makes a "latest" URL a
     * permanent, confusing failure.
     */
    @Test
    fun theBridgeIsPinnedToTheVersionReaAccepts() {
        val manager = readSource("app/src/main/kotlin/com/altaris/linux/core/rea/ReaManager.kt")
        assertTrue(
            "must pin v0.7.1",
            manager.contains("releases/download/v0.7.1/"),
        )
        assertFalse(
            "must not follow latest",
            manager.contains("releases/latest"),
        )
        assertTrue(
            "must name the exact artifact",
            manager.contains("jadx-headless-mcp-0.7.1-all.jar"),
        )
        // Size and hash are what make it verifiable rather than hopeful.
        assertTrue("must pin the size", manager.contains("42_619_858"))
        assertTrue(
            "must pin the SHA-256",
            manager.contains("6e5eacf500b64292bfb73c49797c1958f6ee44646e43e868039ae7feb573ff75"),
        )
    }

    /** REA reads the variable at startup, so it belongs on the child's env. */
    @Test
    fun theBridgeIsExportedToTheReaProcess() {
        val manager = readSource("app/src/main/kotlin/com/altaris/linux/core/rea/ReaManager.kt")
        val command = manager.substringAfter("fun mcpLaunchCommand()")
            .substringBefore("private fun coreScript()")
        assertTrue(
            "must export REA_JADX_MCP_JAR",
            command.contains("REA_JADX_MCP_JAR="),
        )
        assertTrue(
            "must only export it when the file exists",
            command.contains("if [ -f "),
        )
    }

    /** The installer verifies before trusting, and can run twice. */
    @Test
    fun theInstallerVerifiesAndIsIdempotent() {
        val script = readAsset("scripts/rea/rea-jadx-mcp.sh")
        assertTrue("must check the size", script.contains("expected $BYTES"))
        assertTrue("must check the hash", script.contains("SHA-256 mismatch"))
        assertTrue(
            "must skip when already correct",
            script.contains("already installed and verified"),
        )
        assertTrue("must resume a partial download", script.contains("-C -"))
        // A mismatch must leave nothing behind that looks installed.
        val mismatch = script.indexOf("SHA-256 mismatch")
        assertTrue(
            "must delete the bad download",
            script.substring(0, mismatch).contains("rm -f \"$JAR.part\""),
        )
    }

    /** Both install paths must reach it, or an upgrade never gains the bridge. */
    @Test
    fun everyInstallPathInstallsTheBridge() {
        val manager = readSource("app/src/main/kotlin/com/altaris/linux/core/rea/ReaManager.kt")
        val install = manager.substringAfter("suspend fun install(")
            .substringBefore("private suspend fun installDependency(")
        assertTrue(
            "the already-installed branch must repair it",
            install.contains("ensureJadxBridge("),
        )
        val prepare = manager.substringAfter("suspend fun prepare(")
            .substringBefore("private suspend fun installDependency(")
        assertTrue(
            "prepare must repair it too",
            prepare.contains("ensureJadxBridge("),
        )
        assertTrue(
            "the script must be staged into the guest",
            manager.contains("\"rea-jadx-mcp.sh\""),
        )
    }

    /** The agent can fix this itself instead of asking a human. */
    @Test
    fun theAgentCanInstallTheBridgeItself() {
        val mcp = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/control/McpProtocol.kt"
        )
        assertTrue(
            "must expose the installer",
            mcp.contains("\"altaris_jadx_bridge_install\""),
        )
        val flat = mcp.replace(Regex("\\s+"), " ")
        assertTrue(
            "must name the blocker in the instructions",
            flat.contains("REA_JADX_MCP_JAR"),
        )
        assertTrue(
            "must tell the agent to use the tool",
            flat.contains("altaris_jadx_bridge_install"),
        )
        // And the guide REA serves must say it too.
        assertTrue(
            "the guide must explain the bridge",
            ReaKnowledge.GUIDE.contains("REA_JADX_MCP_JAR"),
        )
    }
}

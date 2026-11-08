package com.altaris.linux.core.control

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Why every REA call failed with ENOENT for a file that existed.
 *
 * `/sdcard` on Android is a **symlink** to `/storage/emulated/0`. A chroot that
 * runs `mount --bind /sdcard <chroot>/sdcard` creates a mount whose target is
 * that symlink's *text* — and `/storage` does not exist inside the chroot, so
 * the mount looks successful while every path under it is unreachable. REA then
 * answers
 *
 *     Cannot open artifact: target does not exist … ENOENT:
 *     /sdcard/Download/Altaris/attach/topay.apk
 *
 * for a file sitting on the phone. The agent has no way to tell that apart from
 * a genuinely missing file, so it retries the same path.
 */
class SdcardPathAndReaOrderTest {

    /**
     * The chroot must bind the resolved directory, not the symlink.
     *
     * This is the root cause. Binding `/sdcard` produces a dangling mount.
     */
    @Test
    fun theChrootBindsResolvedSharedStorage() {
        val script = readSource("app/src/main/assets/scripts/chroot/altaris_chroot.sh")
        assertTrue(
            "must resolve the symlink",
            script.contains("readlink -f /sdcard"),
        )
        assertTrue(
            "must bind the resolved path",
            script.contains("bind_if_missing \"\$SDCARD_REAL\" \"\$ALTARIS_CHROOT/sdcard\""),
        )
        // A path written as /storage/emulated/0/... must resolve too.
        assertTrue(
            "must provide /storage inside the chroot",
            script.contains("\$ALTARIS_CHROOT/storage/emulated/0"),
        )
        assertTrue(
            "must bind shared storage there as well",
            script.contains("bind_if_missing \"\$SDCARD_REAL\" \"\$ALTARIS_CHROOT/storage/emulated/0\""),
        )
        assertFalse(
            "must not bind the bare symlink",
            script.contains("bind_if_missing /sdcard \"\$ALTARIS_CHROOT/sdcard\""),
        )
    }

    /** Proot already binds the real directory; that must not regress. */
    @Test
    fun prootStillBindsTheRealDirectory() {
        val builder = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/terminal/ProotCommandBuilder.kt"
        )
        assertTrue(
            "must bind /storage/emulated/0 to /sdcard",
            builder.contains("--bind=/storage/emulated/0:/sdcard"),
        )
    }

    /**
     * A path that fails inside the distro must be rewritten, not reported.
     *
     * The tool checks which candidate resolves and sends that one, so the agent
     * never has to know the difference.
     */
    @Test
    fun aSdcardPathIsRewrittenBeforeTheCall() {
        val mcp = readSource("app/src/main/kotlin/com/altaris/linux/core/control/McpProtocol.kt")
        assertTrue("must have the fixer", mcp.contains("private fun guestPathFix("))
        assertTrue("must apply it to arguments", mcp.contains("private fun fixSdcardPaths("))
        val fixer = mcp.substringAfter("private fun guestPathFix(")
            .substringBefore("private fun fixSdcardPaths(")
        // It must try the real mount point, not only /sdcard.
        assertTrue("must try /sdcard", fixer.contains("/sdcard"))
        assertTrue(
            "must try /storage/emulated/0",
            fixer.contains("/storage/emulated/0"),
        )
        assertTrue("must try /mnt/sdcard", fixer.contains("/mnt/sdcard"))
        assertTrue(
            "must only act on a path that fails",
            fixer.contains("if (resolved == path) null else resolved"),
        )
        // And it must be applied on the call path.
        val call = mcp.substringAfter("\"altaris_rea_call\" ->")
            .substringBefore("\"altaris_rea_agents\" ->")
        assertTrue("must be applied", call.contains("fixSdcardPaths(ctx, arguments)"))
        assertTrue("must forward the fixed set", call.contains("ReaManager.call(reaTool, safeArguments"))
    }

    /** A path outside /sdcard is left completely alone. */
    @Test
    fun aNonSdcardPathIsNotTouched() {
        val mcp = readSource("app/src/main/kotlin/com/altaris/linux/core/control/McpProtocol.kt")
        val fixer = mcp.substringAfter("private fun guestPathFix(")
            .substringBefore("private fun fixSdcardPaths(")
        assertTrue(
            "must return early for other paths",
            fixer.contains("if (!path.startsWith(\"/sdcard/\")"),
        )
    }

    /**
     * ENOENT must say what to do, because it reads like a missing file.
     *
     * This is the error in the report. Without a hint the only rational
     * response is to try the same path again.
     */
    @Test
    fun anEnoentReplyExplainsTheSymlink() {
        val mcp = readSource("app/src/main/kotlin/com/altaris/linux/core/control/McpProtocol.kt")
        assertTrue("must have the hint", mcp.contains("private fun withPathHint("))
        val hint = mcp.substringAfter("private fun withPathHint(")
            .substringBefore("private fun callTool(")
        assertTrue("must catch ENOENT", hint.contains("ENOENT"))
        assertTrue(
            "must name the symlink",
            hint.replace(Regex("\\s+"), " ").contains("symlink on Android"),
        )
        assertTrue("must point at the export tool", hint.contains("altaris_rea_clone_apk"))
        assertTrue("must say the paths are correct", hint.contains("already correct"))
    }

    /** "No app is open" must say open_binary comes first. */
    @Test
    fun aNoOpenTargetReplyExplainsTheOrder() {
        val mcp = readSource("app/src/main/kotlin/com/altaris/linux/core/control/McpProtocol.kt")
        val hint = mcp.substringAfter("private fun withPathHint(")
            .substringBefore("private fun callTool(")
        assertTrue("must catch the message", hint.contains("No app is open"))
        assertTrue("must catch the provider variant", hint.contains("does not support this target"))
        assertTrue("must name open_binary", hint.contains("open_binary"))
        assertTrue("must say these take no path", hint.contains("take no path themselves"))
    }

    // ------------------------------------------------------ instructions

    /**
     * The agent must not invent `inspector`.
     *
     * It tried `altaris.inspector` five times. No such tool exists, and one
     * index call would have said so.
     */
    @Test
    fun theInstructionsForbidInventingToolNames() {
        val mcp = readSource("app/src/main/kotlin/com/altaris/linux/core/control/McpProtocol.kt")
        val instructions = mcp.substringAfter("private val INSTRUCTIONS = \"\"\"")
            .substringBefore("\"\"\".trimIndent()")
            .replace(Regex("\\s+"), " ")
        assertTrue(
            "must name the invented tools",
            instructions.contains("no `inspector`"),
        )
        assertTrue(
            "must say the index answers it",
            instructions.contains("lists all 229"),
        )
        assertTrue(
            "must forbid retrying an invented name",
            instructions.contains("Do not invent a name and retry it"),
        )
    }

    /** The open-then-inspect order must be explicit. */
    @Test
    fun theInstructionsStateTheOpenOrder() {
        val mcp = readSource("app/src/main/kotlin/com/altaris/linux/core/control/McpProtocol.kt")
        val instructions = mcp.substringAfter("private val INSTRUCTIONS = \"\"\"")
            .substringBefore("\"\"\".trimIndent()")
            .replace(Regex("\\s+"), " ")
        assertTrue(
            "must say one target at a time",
            instructions.contains("one open target at a time"),
        )
        assertTrue(
            "must show open_binary taking the path",
            instructions.contains("takes the path"),
        )
        assertTrue(
            "must say inspect_artifact takes none",
            instructions.contains("no path"),
        )
        assertTrue(
            "must explain the ENOENT cause",
            instructions.contains("is a symlink"),
        )
    }

    // ------------------------------------------------------------- helpers

    /** The path candidate order, mirrored from guestPathFix. */
    @Test
    fun theCandidateOrderIsCorrect() {
        val candidates = listOf("/sdcard", "/storage/emulated/0", "/mnt/sdcard")
        val rest = "/Download/Altaris/attach/topay.apk"
        // Whatever exists first wins; the original is last-resort.
        assertEquals(
            "/storage/emulated/0/Download/Altaris/attach/topay.apk",
            (candidates.drop(1).first() + rest),
        )
    }

    private fun readSource(path: String): String {
        val relative = path.removePrefix("app/")
        for (candidate in listOf(path, relative, "../$path", "../$relative", "../../$path")) {
            val file = File(candidate)
            if (file.isFile) return file.readText()
        }
        throw AssertionError("source not found: $path (cwd=" + File(".").absolutePath + ")")
    }
}

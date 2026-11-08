package com.altaris.linux.core.rea

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Frida half that was in the wrong place.
 *
 * The reported log is a sequence of hand-rolled CLI attempts, all failing:
 *
 *     frida-ps --remote 192.168.145.1:27042
 *     -> frida-ps: error: unrecognized arguments: 192.168.145.1:27042
 *
 *     frida -H localhost com.altaris.apps.c18c4ecce /tmp/toppay_frida.js
 *     -> Failed to spawn: unable to connect to remote frida-server
 *
 * The server was running. `installClient` had put the CLI inside the distro.
 * What was wrong is that the attach path invoked `frida -H …` through
 * [com.altaris.linux.core.root.RootShell], which runs on the **host** — where
 * the CLI does not exist, because it is a pip package installed into the guest.
 *
 * These tests pin the split the module's own documentation describes: the
 * **server** on the host, the **client** in the distro, and every CLI
 * invocation routed through the guest.
 */
class FridaClientLocationTest {

    /**
     * Every CLI call must run inside the distro.
     *
     * This is the actual bug: three call sites used `RootShell.captureResult`
     * with a `frida -H …` command. `RootShell` runs on the host, so none of
     * them could ever have worked.
     */
    @Test
    fun theCliIsNeverInvokedOnTheHost() {
        val manager = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/rea/ReaManager.kt"
        )
        // No RootShell call may carry a frida client command. A frida-server
        // control command (pidof, the kill loop) is fine — those are host work.
        val offenders = Regex("RootShell\\.captureResult\\([\\s\\S]{0,400}?frida -H")
            .findAll(manager)
            .count()
        assertTrue(
            "the frida CLI must not run on the host: it is installed in the distro",
            offenders == 0,
        )
        assertTrue(
            "must have a guest-side invocation",
            manager.contains("private fun guestFrida("),
        )
    }

    /** The guest invocation must use the CLI and the loopback server. */
    @Test
    fun theGuestInvocationTargetsTheLoopbackServer() {
        val manager = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/rea/ReaManager.kt"
        )
        val helper = manager.substringAfter("private fun guestFrida(")
            .substringBefore("suspend fun debuggerCommand(")
        assertTrue("must run the CLI", helper.contains("frida -H 127.0.0.1:"))
        assertTrue("must use the server port", helper.contains("ReaFrida.PORT"))
        assertTrue(
            "must make the port a parameter, because Gadget listens elsewhere",
            helper.contains("port: Int = ReaFrida.PORT"),
        )
        assertTrue(
            "must put the usual bin dirs on PATH",
            helper.contains("/usr/local/bin"),
        )
    }

    /** Attach, the gadget probe and the console must all go through the guest. */
    @Test
    fun everyCallSiteUsesTheGuestHelper() {
        val manager = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/rea/ReaManager.kt"
        )
        val attach = manager.substringAfter("suspend fun attachDebugger(")
            .substringBefore("private const val GADGET_PORT")
        assertTrue("attach must use the guest helper", attach.contains("guestFrida("))
        assertTrue("attach must run it in the distro", attach.contains("GuestExec.run("))
        // The Gadget listens on its own port, so the probe must say which.
        assertTrue("the gadget probe must pass its port", attach.contains("GADGET_PORT"))

        val command = manager.substringAfter("suspend fun debuggerCommand(")
            .substringBefore("@Volatile\n    private var _attachedPid")
        assertTrue("the console must use the guest helper", command.contains("guestFrida("))
        assertFalse(
            "the console must not run frida on the host",
            command.contains("RootShell.captureResult"),
        )
    }

    /**
     * The module must state the split, and the reason it exists.
     *
     * The previous comment claimed the CLI ran in the distro while three call
     * sites ran it on the host. A comment that contradicts the code is how the
     * bug survived review, so the reason is pinned here.
     */
    @Test
    fun theModuleExplainsWhichHalfRunsWhere() {
        val frida = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/rea/ReaFrida.kt"
        )
        assertTrue(
            "must say the server is on the host",
            frida.contains("frida-server** runs on the **host**"),
        )
        assertTrue(
            "must say the CLI is in the distro",
            frida.contains("the frida CLI** (`frida`, `frida-trace`, `frida-ps`) runs inside the REA"),
        )
        assertTrue(
            "must name the failure the split caused",
            frida.contains("unable to connect to remote frida-server"),
        )
        assertTrue(
            "must say the host has no CLI",
            frida.contains("runs it on the host, where the binary does not exist"),
        )
    }

    /**
     * The instructions must stop the agent hand-rolling the CLI.
     *
     * Every attempt in the log was the agent guessing at flags: `--remote`,
     * `--name`, `--pid`, and a proot gateway address. The debugger tools exist
     * and do all of it.
     */
    @Test
    fun theInstructionsRouteRuntimeWorkToTheDebuggerTools() {
        val mcp = readSource("app/src/main/kotlin/com/altaris/linux/core/control/McpProtocol.kt")
        val instructions = mcp.substringAfter("private val INSTRUCTIONS = \"\"\"")
            .substringBefore("\"\"\".trimIndent()")
            .replace(Regex("\\s+"), " ")
        assertTrue(
            "must forbid hand-rolled frida commands",
            instructions.contains("Do not shell out to `frida`"),
        )
        assertTrue(
            "must name the flag mistake",
            instructions.contains("`--remote` is not a frida-ps flag"),
        )
        assertTrue(
            "must give the real address",
            instructions.contains("127.0.0.1:27042"),
        )
        assertTrue(
            "must say the CLI is in the distro, not the host",
            instructions.contains("lives *inside the distro*"),
        )
        assertTrue(
            "must point at the attach tool",
            instructions.contains("altaris_debug_attach"),
        )
        assertTrue(
            "must explain the $ prefix",
            instructions.contains("prefix for JavaScript"),
        )
    }

    /** The CLI must still be installed in the distro, since that is where it runs. */
    @Test
    fun theCliIsStillInstalledInTheDistro() {
        val frida = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/rea/ReaFrida.kt"
        )
        assertTrue(
            "must install frida-tools in the guest",
            frida.contains("frida-tools"),
        )
        val client = frida.substringAfter("suspend fun installClient(")
            .substringBefore("/** Everything the debugger tab needs to report. */")
        assertTrue(
            "must probe inside the distro",
            client.contains("GuestExec.run("),
        )
        assertTrue(
            "must look for the console script",
            client.contains("command -v frida"),
        )
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

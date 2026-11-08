package com.altaris.linux.core.control

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Why a session full of working tools still fails.
 *
 * The reported log is not one bug. Every failure has the same shape: the tool
 * was correct and the *contract* around it was wrong.
 *
 *  - `altaris_device_screenshot { }` -> `display: Missing key`, for a parameter
 *    whose own description says "default 0, the main screen".
 *  - `altaris_logs { "display": 2 }` -> `package: Missing key`, while the
 *    package was already known to the engine under that display.
 *  - `altaris_rea_call { "name": "inspect_adb_client" }` -> `name is required`,
 *    which contradicts the call being rejected.
 *  - `altaris_logs` succeeded once, then threw
 *    `Cannot read properties of undefined (reading 'slice')` — because the
 *    reply has `lines` on success and no `lines` on failure.
 *
 * Each test pins the contract, not the implementation.
 */
class ToolSchemaAndReplyContractTest {

    // ------------------------------------------------------- required keys

    /**
     * A parameter with a default must not be advertised as required.
     *
     * This is the `display: Missing key` failure. The `required` array is built
     * by scanning the description for "optional", so a property documented as
     * having a *default* was listed as required — and every client that
     * omitted it, correctly, was rejected by schema validation before the call
     * ran.
     */
    @Test
    fun aParameterWithADefaultIsNotRequired() {
        val mcp = readSource("app/src/main/kotlin/com/altaris/linux/core/control/McpProtocol.kt")
        val builder = mcp.substringAfter("private fun tool(name: String")
            .substringBefore("private fun schema(")
        assertTrue(
            "must treat a documented default as omittable",
            builder.contains("contains(\"default\""),
        )
        assertTrue(
            "must keep treating optional as omittable",
            builder.contains("contains(\"optional\")"),
        )
        assertTrue(
            "the required list must only carry non-omittable keys",
            builder.contains("if (!omittable) required.put(key)"),
        )
    }

    /** The screenshot tool must not demand the display it defaults to. */
    @Test
    fun theDeviceScreenshotDoesNotRequireItsDefaultedDisplay() {
        val mcp = readSource("app/src/main/kotlin/com/altaris/linux/core/control/McpProtocol.kt")
        val decl = mcp.substringAfter("\"altaris_device_screenshot\",")
            .substringBefore("\"altaris_doctor\",")
        assertTrue(
            "the display must be documented as having a default",
            decl.contains("default 0"),
        )
        assertFalse(
            "and must not be spelled as a required argument",
            decl.contains("\"display\" to \"integer: display id\")"),
        )
    }

    // -------------------------------------------------- display -> package

    /**
     * A display id must be enough to address a clone.
     *
     * The agent read `display_id` from `altaris_sessions` and then had a
     * display but no package. `altaris_logs` answered `package: Missing key`,
     * and the agent's next move was to pass a display id as a package name.
     * The engine knows the mapping, so it has to answer it.
     */
    @Test
    fun aDisplayIdResolvesToItsClone() {
        val engine = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/clone/HeadlessCloneEngine.kt"
        )
        assertTrue(
            "must expose the inverse mapping",
            engine.contains("fun packageForDisplay(display: Int): String?"),
        )
        assertTrue(
            "must read it from the session list",
            engine.contains("sessions.value.values"),
        )
        assertTrue(
            "must match on the display id",
            engine.contains("it.displayId == display"),
        )
        // A negative display is not a display, and must not match a session.
        val fn = engine.substringAfter("fun packageForDisplay(")
            .substringBefore("private fun ensureClient(")
        assertTrue("must reject a negative display", fn.contains("if (display < 0) return null"))
        assertTrue(
            "must fall back to the session key when the package is unknown",
            fn.contains("session.key"),
        )
    }

    /** Both identifiers must be accepted wherever a clone is addressed. */
    @Test
    fun theToolsAcceptAPackageOrADisplay() {
        val mcp = readSource("app/src/main/kotlin/com/altaris/linux/core/control/McpProtocol.kt")
        assertTrue(
            "must have a shared resolver",
            mcp.contains("private fun resolveClonePackage("),
        )
        assertTrue(
            "must translate the display through the engine",
            mcp.contains("HeadlessCloneEngine.packageForDisplay("),
        )
        // `altaris_logs` is the reported case, and must accept either.
        val logs = mcp.substringAfter("\"altaris_logs\" ->")
            .substringBefore("\"altaris_tap\" ->")
        assertTrue("must use the resolver", logs.contains("resolveClonePackage(args)"))
        assertFalse(
            "must not demand only a package",
            logs.contains("requirePackage(args)"),
        )
        // The declaration has to say so too, or the client strips the field.
        val decl = mcp.substringAfter("\"altaris_logs\",")
            .substringBefore("\"altaris_tap\",")
        assertTrue("must advertise the display", decl.contains("\"display\" to"))
        assertTrue("must say package is optional", decl.contains("optional clone package"))
    }

    /** A failed resolve must name both identifiers, not just one. */
    @Test
    fun anUnresolvableCloneSaysWhatToPass() {
        val mcp = readSource("app/src/main/kotlin/com/altaris/linux/core/control/McpProtocol.kt")
        val logs = mcp.substringAfter("\"altaris_logs\" ->")
            .substringBefore("\"altaris_tap\" ->")
        assertTrue(
            "must say the package is required",
            logs.contains("package is required"),
        )
        assertTrue(
            "must offer the display alternative",
            logs.contains("display id"),
        )
        assertTrue(
            "must name the tool that lists packages",
            logs.contains("altaris_clones"),
        )
    }

    // ------------------------------------------------- rea_call arguments

    /**
     * `altaris_rea_call { name: … }` must never answer "name is required".
     *
     * That is what the log shows, and the message contradicts the call it is
     * rejecting. Whatever the exact shape the client sent, a rejection has to
     * quote the keys it actually received.
     */
    @Test
    fun aRejectedReaCallEchoesTheKeysItReceived() {
        val mcp = readSource("app/src/main/kotlin/com/altaris/linux/core/control/McpProtocol.kt")
        val call = mcp.substringAfter("\"altaris_rea_call\" ->")
            .substringBefore("\"altaris_rea_agents\" ->")
        assertFalse(
            "must not answer a bare name is required",
            call.contains("?: throw IllegalArgumentException(\"name is required\")"),
        )
        assertTrue(
            "must accept the tool alias",
            call.contains("args.optString(\"tool\")"),
        )
        assertTrue(
            "must echo the keys it received",
            call.contains("args.keys().asSequence().toList()"),
        )
        assertTrue(
            "must show the correct shape",
            call.contains("altaris_rea_call { \\\"name\\\": \\\"inspect_artifact\\\""),
        )
        assertTrue(
            "must point at the index",
            call.contains("altaris_rea_tools for the list of names"),
        )
    }

    /** `arguments` is optional: a tool may take none. */
    @Test
    fun theReaCallDoesNotRequireAnArgumentsObject() {
        val mcp = readSource("app/src/main/kotlin/com/altaris/linux/core/control/McpProtocol.kt")
        val decl = mcp.substringAfter("\"altaris_rea_call\",")
            .substringBefore("\"altaris_rea_agents\",")
        assertTrue(
            "must advertise arguments as optional",
            decl.contains("optional tool arguments"),
        )
        val call = mcp.substringAfter("\"altaris_rea_call\" ->")
            .substringBefore("\"altaris_rea_agents\" ->")
        assertTrue(
            "and must default it rather than fail",
            call.contains("args.optJSONObject(\"arguments\") ?: JSONObject()"),
        )
    }

    // --------------------------------------------------- reply stability

    /**
     * A reply's shape must not change between success and failure.
     *
     * `altaris_logs` returned `{ ok, package, lines }` on success and
     * `{ ok, error }` on failure. A caller that wrote `result.lines.slice(...)`
     * once then threw `Cannot read properties of undefined (reading 'slice')`
     * — which looks like a broken tool, not an empty result. `lines` is always
     * an array; the reason lives in `error`.
     */
    @Test
    fun aLogReplyAlwaysCarriesTheLinesArray() {
        val engine = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/clone/HeadlessCloneEngine.kt"
        )
        val logs = engine.substringAfter("suspend fun apiLogs(")
            .substringBefore("suspend fun apiTap(")
        // Every exit path must carry the array.
        assertEquals(
            "every return path must include lines",
            4,
            Regex("put\\(\"lines\"").findAll(logs).count(),
        )
        assertTrue(
            "the disconnected path must still carry it",
            logs.contains("put(\"error\", \"Shell engine is not connected\")"),
        )
        assertTrue(
            "the invalid-JSON path must still carry it",
            logs.contains("Engine returned invalid log JSON"),
        )
        assertTrue(
            "a successful read must report how many lines",
            logs.contains("put(\"count\""),
        )
    }

    // ------------------------------------------------------ instructions

    /** The agent must know a clone has no adb serial. */
    @Test
    fun theInstructionsSayACloneIsNotAnAdbDevice() {
        val mcp = readSource("app/src/main/kotlin/com/altaris/linux/core/control/McpProtocol.kt")
        val instructions = mcp.substringAfter("private val INSTRUCTIONS = \"\"\"")
            .substringBefore("\"\"\".trimIndent()")
            .replace(Regex("\\s+"), " ")
        assertTrue(
            "must say the clone has no serial",
            instructions.contains("**no adb serial**"),
        )
        assertTrue(
            "must name the failure the agent will see",
            instructions.contains("unsupported_target"),
        )
        assertTrue(
            "must point at the tool that works",
            instructions.contains("The clone's logcat is `altaris_logs`"),
        )
        assertTrue(
            "must say adb is not needed",
            instructions.contains("needs no adb at all"),
        )
    }

    /**
     * The agent must stop wrapping tool calls in a program.
     *
     * The reported block was:
     *
     *     JSON.stringify(tools.altaris.altaris_device_screenshot({ display: 2 }))</result_code>
     *     await new Promise(function (r) { setTimeout(r, 300) })
     *
     * Three defects in two lines, and each one fails before any tool runs:
     *
     *   - `</result_code>` is not JavaScript: `SyntaxError: Invalid regular
     *     expression: missing /` (the harness says "Unterminated regular
     *     expression");
     *   - top-level `await` is not valid in a plain program body;
     *   - there are no timers, so `setTimeout` throws.
     *
     * The instructions have to name all three, because each reads like a tool
     * defect and the agent retries the same shape.
     */
    @Test
    fun theInstructionsNameTheThreeWrapperFailures() {
        val mcp = readSource("app/src/main/kotlin/com/altaris/linux/core/control/McpProtocol.kt")
        val instructions = mcp.substringAfter("private val INSTRUCTIONS = \"\"\"")
            .substringBefore("\"\"\".trimIndent()")
            .replace(Regex("\\s+"), " ")
        assertTrue(
            "must name the harness's parse error",
            instructions.contains("Unterminated regular expression"),
        )
        assertTrue(
            "must say the body is plain JavaScript",
            instructions.contains("plain JavaScript written as a program body"),
        )
        assertTrue(
            "must forbid the closing tag",
            instructions.contains("Do not emit `</result_code>`"),
        )
        assertTrue(
            "must say top-level await is a syntax error",
            instructions.contains("`await` is a syntax error"),
        )
        assertTrue(
            "must say a bare call is enough",
            instructions.contains("A bare call is the correct form"),
        )
        assertTrue(
            "must say one call per step",
            instructions.contains("One tool call per step"),
        )
        assertTrue(
            "must forbid the timer",
            instructions.contains("There are none. Use `altaris_wait`"),
        )
        // The section must not itself contain a copyable broken example: the
        // previous version pasted the failing line verbatim, so an agent that
        // copied from the instructions reproduced the bug.
        val section = instructions.substringAfter("One tool call per step")
            .substringBefore("Signing in to a site")
        assertFalse(
            "the instructions must not paste an await example: $section",
            section.contains("await tools."),
        )
        assertFalse(
            "the instructions must not paste a tag example: $section",
            section.contains("</result_code>\n"),
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

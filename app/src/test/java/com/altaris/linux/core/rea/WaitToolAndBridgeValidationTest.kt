package com.altaris.linux.core.rea

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Two failures from the same session, and the two different reasons.
 *
 * **`setTimeout`.** Every `altaris_ui_tap_text` call failed with
 * `ReferenceError: Unknown identifier 'setTimeout'` before the tool ran. That
 * is not a tool defect: the environment that runs tool calls has no timers, so
 * an agent that wants to pause between a tap and the next tree read has
 * nothing to call. The fix is a wait tool, and instructions that name the trap.
 *
 * **`inspect_android_class`.** REA answered "The arguments for MCP tool …
 * do not match its advertised input schema" — one sentence naming neither the
 * field nor the shape. The `altaris` server already validated and named the
 * field; the `rea` server forwarded blindly. The fix is the same validation on
 * the bridge, which is the server the in-distro agent talks to.
 */
class WaitToolAndBridgeValidationTest {

    // ------------------------------------------------------------- wait tool

    /** `altaris_wait` must be advertised, routed and listed in a plan. */
    @Test
    fun theWaitToolIsAdvertisedAndHandled() {
        val mcp = readSource("app/src/main/kotlin/com/altaris/linux/core/control/McpProtocol.kt")
        assertTrue("must advertise it", mcp.contains("\"altaris_wait\","))
        assertTrue("must route it", mcp.contains("\"altaris_wait\" ->"))
        assertTrue(
            "must say what it replaces",
            mcp.contains("instead of setTimeout"),
        )
        // Both forms have to exist: a fixed pause and a poll.
        val route = mcp.substringAfter("\"altaris_wait\" ->")
            .substringBefore("\"altaris_ui_display\" ->")
        assertTrue("must support a fixed wait", route.contains("Thread.sleep(ms)"))
        assertTrue("must support waiting for an element", route.contains("until"))
        assertTrue("must read the tree to poll", route.contains("A11yBridge.tree("))
        assertTrue("must report whether it appeared", route.contains("\"found\""))
        // A poll that never matches must tell the agent what to do next.
        assertTrue(
            "a timeout must name the next step",
            route.contains("read altaris_ui_tree to see what is on screen"),
        )
    }

    /**
     * The instructions must name the error, or the agent will keep hitting it.
     *
     * The message the user reported is `Unknown identifier 'setTimeout'`. An
     * agent that does not know timers are absent reads that as a broken tool.
     */
    @Test
    fun theInstructionsExplainThatTimersDoNotExist() {
        val mcp = readSource("app/src/main/kotlin/com/altaris/linux/core/control/McpProtocol.kt")
        val instructions = mcp.substringAfter("private val INSTRUCTIONS = \"\"\"")
            .substringBefore("\"\"\".trimIndent()")
            .replace(Regex("\\s+"), " ")
        assertTrue(
            "must say there are no timers",
            instructions.contains("no `setTimeout`"),
        )
        assertTrue(
            "must quote the error the agent will see",
            instructions.contains("Unknown identifier 'setTimeout'"),
        )
        assertTrue(
            "must point at the replacement",
            instructions.contains("altaris_wait"),
        )
        assertTrue(
            "must explain why the tool never ran",
            instructions.contains("before the tool is invoked"),
        )
    }

    /** Every plan that taps must offer a wait between the tap and the check. */
    @Test
    fun plansThatTapAlsoWait() {
        for (request in listOf(
            "open the app",
            "tap the shop icon",
            "tap start and verify the result",
            "find out why this button does not work",
        )) {
            val plan = TaskRouter.classify(request)
            val tools = plan.steps.map { it.tool }
            assertTrue(
                "a tapping plan must wait: $request -> $tools",
                "altaris_wait" in tools,
            )
            // And the wait has to sit between the tap and the re-read, or it
            // buys nothing: the tree is read before the UI settled.
            val tap = tools.indexOf("altaris_ui_tap_text")
            if (tap >= 0) {
                val wait = tools.indexOf("altaris_wait")
                val after = tools.drop(tap + 1)
                assertTrue(
                    "the wait must follow the tap: $request -> $tools",
                    wait > tap || "altaris_wait" in after,
                )
            }
        }
    }

    // --------------------------------------------------- bridge validation

    /**
     * The bridge must validate before forwarding.
     *
     * This is the reported failure: the `rea` server forwarded
     * `inspect_android_class { path: … }` and REA answered one generic
     * sentence. Validation has to happen on the bridge, because the in-distro
     * agent talks to the bridge, not to `altaris_rea_call`.
     */
    @Test
    fun theBridgeValidatesBeforeForwarding() {
        val bridge = readSource("app/src/main/kotlin/com/altaris/linux/core/rea/BridgeServer.kt")
        assertTrue("must check the schema", bridge.contains("schemaProblems("))
        assertTrue("must use the shared validator", bridge.contains("ReaSchema.validate("))
        assertTrue("must report the problems", bridge.contains("\"problems\""))
        assertTrue("must give a callable example", bridge.contains("\"example\""))
        assertTrue("must list required keys", bridge.contains("\"required\""))
        // A blocked call must not reach REA at all.
        val block = bridge.substringAfter("val problems = schemaProblems(")
            .substringBefore("return try {")
        assertTrue(
            "a rejected call must not be forwarded",
            !block.contains("session.forward("),
        )
        assertTrue(
            "the rejection must say the call was not sent",
            bridge.contains("The call was not sent"),
        )
        // And the forward must still be there for a valid call.
        assertTrue(
            "a valid call must still be forwarded",
            bridge.contains("session.forward(method, params, TOOL_TIMEOUT_MS)"),
        )
    }

    /**
     * An unknown tool must be forwarded, never blocked.
     *
     * The validator cannot judge a tool it has no schema for, and rejecting on
     * that basis would break tools REA would happily run. `null` means "no
     * opinion".
     */
    @Test
    fun anUnknownToolIsForwardedNotBlocked() {
        val bridge = readSource("app/src/main/kotlin/com/altaris/linux/core/rea/BridgeServer.kt")
        val helper = bridge.substringAfter("private fun schemaProblems(")
            .substringBefore("private fun requiredOf(")
        assertTrue(
            "an unknown tool must yield no opinion",
            helper.contains("?: return null"),
        )
        assertTrue(
            "the catalog lookup must be null-safe",
            bridge.contains("?: return null"),
        )
        // Only a non-null, non-empty result may block.
        assertTrue(
            "only reported problems may block",
            bridge.contains("if (problems != null && problems.isNotEmpty())"),
        )
    }

    /**
     * The exact reported call, run through the real validator.
     *
     * `inspect_android_class { path: … }` must come back naming `path` as not
     * recognised and the required key it is missing — not as a generic line.
     */
    @Test
    fun aWrongArgumentNamesTheFieldAndTheRequiredKey() {
        // Shape of REA's Android class tools: the artifact is already open, so
        // the class is addressed by name plus the evidence that found it.
        val schema = JSONObject(
            """
            {
              "type": "object",
              "properties": {
                "class_name": {"type": "string"},
                "evidence_id": {"type": "string"}
              },
              "required": ["class_name", "evidence_id"],
              "additionalProperties": false
            }
            """.trimIndent()
        )
        val problems = ReaSchema.validate(
            schema,
            JSONObject().put("path", "/sdcard/Download/Altaris/attach/toppay.apk"),
        )
        val text = problems.joinToString("\n") { it.toString() }
        assertTrue("must name the unknown key: $text", text.contains("path"))
        assertTrue("must say it is not recognised: $text", text.contains("not a recognised"))
        assertTrue("must name the missing key: $text", text.contains("class_name"))
        assertTrue("must name the other missing key: $text", text.contains("evidence_id"))

        // And a call with only class_name must still name what is missing.
        val partial = ReaSchema.validate(schema, JSONObject().put("class_name", "com.xpay.app.MainActivity"))
        assertTrue(
            "a partial call must name the missing field: $partial",
            partial.any { it.path == "evidence_id" },
        )
    }

    /** The example the bridge returns must satisfy the schema it came from. */
    @Test
    fun theExampleReturnedToAnAgentIsValid() {
        val schema = JSONObject(
            """
            {
              "type": "object",
              "properties": {
                "class_name": {"type": "string"},
                "evidence_id": {"type": "string", "pattern": "^ev_[a-f0-9]{64}${'$'}"}
              },
              "required": ["class_name", "evidence_id"],
              "additionalProperties": false
            }
            """.trimIndent()
        )
        val example = ReaSchema.example(schema)
        assertNotNull("must produce an example", example)
        assertTrue(
            "the example must pass its own schema: " + ReaSchema.validate(schema, example!!),
            ReaSchema.validate(schema, example).isEmpty(),
        )
        // Both required keys must be present, or copying it fails again.
        assertTrue(example.has("class_name"))
        assertTrue(example.has("evidence_id"))
    }

    /** The REA guide must teach the field-list reply, since that is its server. */
    @Test
    fun theReaGuideExplainsTheFieldListReply() {
        val guide = ReaKnowledge.REA_GUIDE.replace(Regex("\\s+"), " ")
        assertTrue(
            "must say the reply is structured",
            guide.contains("\"problems\""),
        )
        assertTrue(
            "must say the call was not sent",
            guide.contains("was **not sent**"),
        )
        assertTrue(
            "must say to copy the example",
            guide.contains("Copy `example`"),
        )
        assertTrue(
            "must distinguish missing from unknown",
            guide.contains("is required") && guide.contains("is not a recognised argument"),
        )
        assertTrue(
            "must forbid a second guess",
            guide.contains("Do not retry with a different guess"),
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

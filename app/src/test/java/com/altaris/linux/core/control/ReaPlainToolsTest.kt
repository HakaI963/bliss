package com.altaris.linux.core.control

import com.altaris.linux.core.rea.ReaSchema
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The flat tool surface, and the validator gap that made REA's Frida tools
 * uncallable.
 *
 * ## The reported failure
 *
 * `instrument_with_frida` and `start_frida_session` take a `mode` plus a
 * *different required field per mode* — `pid` for attach, `program` for spawn —
 * and express that with `if`/`then` inside `allOf`. Nine calls in the report
 * failed with
 *
 *     The arguments for MCP tool instrument_with_frida do not match its
 *     advertised input schema.
 *
 * naming no field. Two things were wrong:
 *
 *  1. **REA's `if` conditions omit `"type": "object"`.** They carry only
 *     `properties` and `required`. The validator treated a schema with no
 *     `type` as "a construct it does not model" and accepted it — so every
 *     condition matched, the `then` branch was never selected, and the missing
 *     `pid` was never reported.
 *  2. **There was no tool to call instead.** Asking a model to learn a
 *     per-mode union before it can attach is the work these wrappers remove.
 */
class ReaPlainToolsTest {

    // ------------------------------------------------- validator: if/then

    /**
     * A condition with no `type` must still be evaluated.
     *
     * This is the root cause. `properties` + `required` implies an object, and
     * ignoring that made every conditional branch match.
     */
    @Test
    fun aConditionWithoutATypeIsStillChecked() {
        val condition = JSONObject(
            """{"properties":{"mode":{"const":"attach"}},"required":["mode"]}"""
        )
        assertTrue(
            "a matching value must pass",
            ReaSchema.validate(condition, JSONObject().put("mode", "attach")).isEmpty(),
        )
        val mismatch = ReaSchema.validate(condition, JSONObject().put("mode", "spawn"))
        assertFalse(
            "a non-matching value must fail, or the branch is never selected",
            mismatch.isEmpty(),
        )
        assertTrue(
            "the mismatch must name the field: $mismatch",
            mismatch.any { it.toString().contains("mode") },
        )
    }

    /**
     * `if`/`then` inside `allOf` must report the field the branch needs.
     *
     * This reproduces `instrument_with_frida` exactly: attach requires `pid`,
     * spawn requires `program`.
     */
    @Test
    fun theConditionalRequiredFieldIsNamed() {
        val schema = JSONObject(
            """
            {
              "type": "object",
              "properties": {
                "mode": {"enum": ["attach", "spawn"]},
                "pid": {"type": "integer"},
                "program": {"type": "string"}
              },
              "required": ["mode"],
              "allOf": [
                {"if": {"properties": {"mode": {"const": "attach"}}, "required": ["mode"]},
                 "then": {"required": ["pid"]}},
                {"if": {"properties": {"mode": {"const": "spawn"}}, "required": ["mode"]},
                 "then": {"required": ["program"]}}
              ]
            }
            """.trimIndent()
        )

        // attach without pid: must name pid.
        val attachMissing = ReaSchema.validate(schema, JSONObject().put("mode", "attach"))
        assertTrue(
            "must name the conditional field: $attachMissing",
            attachMissing.any { it.path == "pid" },
        )

        // attach with pid: valid.
        assertTrue(
            "a complete attach must pass",
            ReaSchema.validate(schema, JSONObject().put("mode", "attach").put("pid", 8665)).isEmpty(),
        )

        // spawn with program: valid.
        assertTrue(
            "a complete spawn must pass",
            ReaSchema.validate(schema, JSONObject().put("mode", "spawn").put("program", "com.x")).isEmpty(),
        )

        // spawn without program: must name program.
        val spawnMissing = ReaSchema.validate(schema, JSONObject().put("mode", "spawn"))
        assertTrue(
            "must name program: $spawnMissing",
            spawnMissing.any { it.path == "program" },
        )
    }

    /** An enum mismatch is still reported with the accepted values. */
    @Test
    fun anInvalidEnumIsNamedWithItsOptions() {
        val schema = JSONObject(
            """{"type":"object","properties":{"source_kind":{"enum":["inline","file"]}}}"""
        )
        val problems = ReaSchema.validate(schema, JSONObject().put("source_kind", "nope"))
        assertTrue("must report it: $problems", problems.isNotEmpty())
        val text = problems[0].toString()
        assertTrue("must name the field: $text", text.contains("source_kind"))
        assertTrue("must list the options: $text", text.contains("inline"))
    }

    // ------------------------------------------------------ flat tool surface

    /** Every flat tool must be advertised and routed. */
    @Test
    fun theFlatToolsAreAdvertisedAndHandled() {
        val mcp = readSource("app/src/main/kotlin/com/altaris/linux/core/control/McpProtocol.kt")
        for (name in listOf(
            "altaris_analyze",
            "altaris_apk_info",
            "altaris_apk_search",
            "altaris_frida_attach",
            "altaris_frida_run",
            "altaris_frida_list",
            "altaris_frida_detach",
        )) {
            assertTrue("$name must be advertised", mcp.contains("\"$name\","))
            assertTrue("$name must be routed", mcp.contains("\"$name\" ->"))
            assertTrue("$name must be an entry point", mcp.contains("\"$name\" to"))
        }
    }

    /** The flat tools must take plain arguments, not REA's unions. */
    @Test
    fun theFlatToolsTakePlainArguments() {
        val mcp = readSource("app/src/main/kotlin/com/altaris/linux/core/control/McpProtocol.kt")
        val analyze = mcp.substringAfter("\"altaris_analyze\",").substringBefore("\"altaris_apk_info\",")
        assertTrue("analyze takes a path", analyze.contains("\"path\" to"))
        assertFalse(
            "analyze must not ask for a mode",
            analyze.contains("\"mode\" to"),
        )
        val attach = mcp.substringAfter("\"altaris_frida_attach\",").substringBefore("\"altaris_frida_run\",")
        assertTrue("attach takes a package", attach.contains("\"package\" to"))
        assertTrue("attach takes a pid", attach.contains("\"pid\" to"))
        assertFalse(
            "attach must not make the caller choose a mode",
            attach.contains("\"mode\" to"),
        )
        assertFalse(
            "attach must not make the caller choose source_kind",
            attach.contains("\"source_kind\" to"),
        )
    }

    /** `altaris_analyze` must open the file before identifying it. */
    @Test
    fun analyzeOpensBeforeInspecting() {
        val mcp = readSource("app/src/main/kotlin/com/altaris/linux/core/control/McpProtocol.kt")
        val fn = mcp.substringAfter("private suspend fun analyzeFile(")
            .substringBefore("private suspend fun apkInfo(")
        assertTrue("must open first", fn.contains("open_binary"))
        assertTrue("must then identify", fn.contains("inspect_artifact"))
        assertTrue(
            "opening must precede inspecting",
            fn.indexOf("open_binary") < fn.indexOf("inspect_artifact"),
        )
        assertTrue("must fix the path first", fn.contains("fixSdcardPaths("))
        // And it must explain an open failure rather than returning it raw.
        assertTrue("must hint", fn.contains("pathHintFor("))
    }

    /** The Frida attach must resolve the tool from the live catalog. */
    @Test
    fun fridaAttachResolvesTheToolByName() {
        val mcp = readSource("app/src/main/kotlin/com/altaris/linux/core/control/McpProtocol.kt")
        val fn = mcp.substringAfter("private suspend fun fridaAttach(")
            .substringBefore("private suspend fun fridaRun(")
        assertTrue("must resolve from the catalog", fn.contains("ReaPlain.resolve("))
        assertTrue(
            "must name the real candidates",
            fn.contains("instrument_with_frida") && fn.contains("start_frida_session"),
        )
        assertTrue("must build the arguments", fn.contains("ReaPlain.build("))
        assertTrue("must check before sending", fn.contains("ReaPlain.problems("))
        // A package implies the pid, which is what attach mode needs.
        assertTrue("must resolve the pid", fn.contains("resolveClonePidPublic("))
    }

    /** `ReaPlain` must resolve names and derive discriminators. */
    @Test
    fun reaPlainResolvesAndBuilds() {
        val plain = readSource("app/src/main/kotlin/com/altaris/linux/core/control/ReaPlain.kt")
        assertTrue("must resolve", plain.contains("fun resolve(vararg candidates: String): String?"))
        assertTrue("must build", plain.contains("fun build(tool: String, supplied: JSONObject): JSONObject"))
        assertTrue("must report missing", plain.contains("fun problems(tool: String, arguments: JSONObject): List<String>"))
        // It must read the live schema, not a hardcoded copy.
        assertTrue("must read the schema", plain.contains("ReaManager.toolDefinition(tool)"))
        // And follow if/then.
        assertTrue("must handle if/then", plain.contains("optJSONObject(\"if\")"))
        assertTrue("must derive the discriminator", plain.contains("chooseDiscriminator("))
    }

    // --------------------------------------------------------- instructions

    /** The instructions must point at the flat tools first. */
    @Test
    fun theInstructionsLeadWithTheFlatTools() {
        val mcp = readSource("app/src/main/kotlin/com/altaris/linux/core/control/McpProtocol.kt")
        val instructions = mcp.substringAfter("private val INSTRUCTIONS = \"\"\"")
            .substringBefore("\"\"\".trimIndent()")
            .replace(Regex("\\s+"), " ")
        assertTrue("must lead with analyze", instructions.contains("altaris_analyze"))
        assertTrue("must name apk info", instructions.contains("altaris_apk_info"))
        assertTrue("must name frida attach", instructions.contains("altaris_frida_attach"))
        assertTrue(
            "must say the flat tools are the call to reach for",
            instructions.contains("This is the call to reach for"),
        )
        assertTrue(
            "must forbid calling REA's frida tools directly",
            instructions.contains("Do not call REA's Frida tools directly"),
        )
        assertTrue(
            "must explain why",
            instructions.contains("different set of required fields per mode"),
        )
    }

    /** The plans must name the flat tools, so altaris_plan is useful. */
    @Test
    fun thePlansUseTheFlatTools() {
        val router = readSource("app/src/main/kotlin/com/altaris/linux/core/control/TaskRouter.kt")
        val apk = router.substringAfter("Kind.APK_ANALYSIS -> listOf(")
            .substringBefore("Kind.STATIC_RE ->")
        assertTrue("APK analysis must use analyze", apk.contains("altaris_analyze"))
        assertTrue("and apk info", apk.contains("altaris_apk_info"))
        val frida = router.substringAfter("Kind.FRIDA_INSTRUMENTATION -> listOf(")
            .substringBefore("Kind.CLONE_TEST ->")
        assertTrue("frida must use attach", frida.contains("altaris_frida_attach"))
        assertTrue("and run", frida.contains("altaris_frida_run"))
        // Starting the server is a legitimate first step, but it must be
        // optional and must not replace the attach.
        val installStep = frida.lines().firstOrNull { it.contains("altaris_frida_install") }
        if (installStep != null) {
            assertTrue(
                "starting frida-server must be optional, not a required step: $installStep",
                installStep.contains("optional = true"),
            )
        }
        assertTrue(
            "attach must come before run",
            frida.indexOf("altaris_frida_attach") < frida.indexOf("altaris_frida_run"),
        )
    }

    // ------------------------------------------------------------- helpers

    /** The example generator must satisfy the schema it came from. */
    @Test
    fun theExampleSatisfiesAConditionalSchema() {
        val schema = JSONObject(
            """
            {
              "type": "object",
              "properties": {
                "mode": {"enum": ["attach"]},
                "pid": {"type": "integer"}
              },
              "required": ["mode"]
            }
            """.trimIndent()
        )
        val example = ReaSchema.example(schema)
        assertNotNull("must produce one", example)
        assertTrue(
            "the example must pass its own schema: " + ReaSchema.validate(schema, example!!),
            ReaSchema.validate(schema, example).isEmpty(),
        )
        assertEquals("attach", example.optString("mode"))
    }

    private fun readSource(path: String): String {
        val relative = path.removePrefix("app/")
        for (candidate in listOf(path, relative, "../$path", "../$relative", "../../$path")) {
            val file = File(candidate)
            if (file.isFile) return file.readText()
        }
        throw AssertionError("source not found: $path (cwd=" + File(".").absolutePath + ")")
    }

    // ------------------------------------------------- the transport, not the schema

    /**
     * `arguments` may arrive as a one-element array.
     *
     * This is the shape the reported session actually sent, and it is why
     * `altaris_frida_attach { "package": … }` answered
     *
     *     {"ok":false,"error":"Give a package or a pid."}
     *
     * for a call that plainly carried a package. `params.arguments` is
     * specified as an object, `optJSONObject` returns null for an array, and
     * the old parser then fell through to the inline branch — where the only
     * key left was `name`, which it had already removed. The arguments were
     * dropped silently, so the tool saw `{}`.
     *
     * A server can read this shape, so it must.
     */
    @Test
    fun anArgumentsArrayIsUnwrapped() {
        val mcp = readSource("app/src/main/kotlin/com/altaris/linux/core/control/McpProtocol.kt")
        val fn = mcp.substringAfter("private fun argumentsOf(")
            .substringBefore("private fun isFridaBindingError(")
        assertTrue(
            "must read the array form, not only the object form",
            fn.contains("optJSONArray(\"arguments\")"),
        )
        assertTrue("must unwrap a single element", fn.contains("unwrapSingleObject()"))
    }

    /**
     * A failed attach must not report `ok: true`.
     *
     * The reported session shows
     *
     *     {"ok":true,"pid":5624,"output":"Failed to attach: unable to connect
     *      to remote frida-server"}
     *
     * — a success envelope around a failure, so every later script ran against
     * nothing and reported "Not attached". The attach now inspects what Frida
     * actually said.
     */
    @Test
    fun aFailedAttachIsNotASuccess() {
        val mcp = readSource("app/src/main/kotlin/com/altaris/linux/core/control/McpProtocol.kt")
        val fn = mcp.substringAfter("private suspend fun fridaAttach(")
            .substringBefore("private suspend fun fridaRun(")
        assertTrue(
            "must detect the failure text",
            fn.contains("Failed to attach") || fn.contains("unable to connect"),
        )
        // And the failure must come before the success return.
        val failure = fn.indexOf("could not attach")
        val success = fn.indexOf(".put(\"ok\", true)")
        assertTrue("must have a failure branch", failure >= 0)
        assertTrue("failure must precede success", failure < success)
    }

    /**
     * Attaching must record the pid the console reads.
     *
     * `debuggerCommand` reads `_attachedPid`, and only `altaris_debug_attach`
     * filled it. A caller using the flat tools attached successfully and then
     * had every script fail with "Not attached."
     */
    @Test
    fun attachingRecordsThePid() {
        val mcp = readSource("app/src/main/kotlin/com/altaris/linux/core/control/McpProtocol.kt")
        val fn = mcp.substringAfter("private suspend fun fridaAttach(")
            .substringBefore("private suspend fun fridaRun(")
        assertTrue(
            "attach must record the pid for later commands",
            fn.contains("ReaManager.rememberAttachedPid(attached.pid)"),
        )
        val detach = mcp.substringAfter("private suspend fun fridaDetach(")
            .substringBefore("private fun unwrap(")
        assertTrue(
            "detach must clear it",
            detach.contains("rememberAttachedPid(null)"),
        )
    }

    /**
     * A script that did not run must not return gdb output as its result.
     *
     * `debuggerCommand` falls back to gdb, which prints
     * "warning: section .bss not found in .gnu_debugdata" for any pid. The old
     * `out.output.isNotBlank()` test treated that as a successful evaluation.
     */
    @Test
    fun aScriptThatDidNotRunIsAFailure() {
        val mcp = readSource("app/src/main/kotlin/com/altaris/linux/core/control/McpProtocol.kt")
        val fn = mcp.substringAfter("private suspend fun fridaRun(")
            .substringBefore("private suspend fun fridaList(")
        assertTrue("must detect the not-attached state", fn.contains("Not attached"))
        assertTrue("must detect a gdb-only fallback", fn.contains("gnu_debugdata") || fn.contains("gdbOnly"))
        assertTrue("must report failure", fn.contains("\"no Frida session is attached\""))
    }

    /**
     * Frida must not route through REA.
     *
     * REA's Frida provider imports the Node `frida` binding; Frida publishes no
     * android-arm64 prebuild and the source build fails on device. Altaris has
     * its own frida-server, which works, so the flat tools use it and the list
     * comes from the clone registry.
     */
    @Test
    fun fridaDoesNotDependOnRea() {
        val mcp = readSource("app/src/main/kotlin/com/altaris/linux/core/control/McpProtocol.kt")
        val list = mcp.substringAfter("private suspend fun fridaList(")
            .substringBefore("private suspend fun fridaDetach(")
        assertFalse(
            "the list must not call REA's frida provider",
            list.contains("list_frida_processes"),
        )
        assertTrue("it must report the server state", list.contains("ReaFrida.isServerRunning()"))
        // The instructions must say so, or the model will try REA anyway.
        val instructions = mcp.substringAfter("private val INSTRUCTIONS = \"\"\"")
            .substringBefore("\"\"\".trimIndent()")
            .replace(Regex("\\s+"), " ")
        assertTrue(
            "must say Frida is Altaris's, not REA's",
            instructions.contains("Altaris's own frida-server"),
        )
        assertTrue(
            "must forbid the REA frida tools",
            instructions.contains("start_frida_session") &&
                instructions.contains("never call"),
        )
    }

    /**
     * A tool that requires a device gets `device_id: "local"`.
     *
     * `list_frida_processes {}` answers "Select a device_id or a remote
     * endpoint", and `start_frida_session` says "Select one device". Neither
     * names the value, and REA's own example for this host is `local`.
     */
    @Test
    fun aToolThatNeedsADeviceGetsOne() {
        val plain = readSource("app/src/main/kotlin/com/altaris/linux/core/control/ReaPlain.kt")
        assertTrue("must fill a device", plain.contains("fillDeviceId("))
        assertTrue("must use REA's own value", plain.contains("\"local\""))
        assertTrue(
            "must only do it when the schema declares the field",
            plain.contains("props.has(\"device_id\")"),
        )
    }
}
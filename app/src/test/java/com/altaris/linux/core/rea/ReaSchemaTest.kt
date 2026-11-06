package com.altaris.linux.core.rea

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The REA argument-shape fix.
 *
 * Every failure in the reported log came from the same place: REA advertises a
 * **compact** input-schema profile by default, which replaces each nested
 * object with a bare `{type: object}`. An agent reading that has no way to know
 * that `source` is `{kind: "retained-evidence", evidence_id: "ev_..."}`, so it
 * guesses `source: "jadx"` and REA answers `Expected object`.
 *
 * These tests pin three things:
 *   1. the launcher asks for the `full` profile;
 *   2. the catalog drops the 3.47 MB of `outputSchema` it does not need;
 *   3. the validator names the field instead of repeating REA's generic line.
 */
class ReaSchemaTest {

    // ------------------------------------------------------- schema profile

    /**
     * The launcher must request the full profile.
     *
     * This is the root cause of every `Expected object` error: the compact
     * profile deletes the shape the agent needed to see.
     */
    @Test
    fun theLauncherRequestsTheFullSchemaProfile() {
        val manager = readSource("app/src/main/kotlin/com/altaris/linux/core/rea/ReaManager.kt")
        assertTrue(
            "must set the profile explicitly",
            manager.contains("REA_MCP_INPUT_SCHEMA_PROFILE=full"),
        )
        assertFalse(
            "must not fall back to compact, which hides nested shapes",
            manager.contains("REA_MCP_INPUT_SCHEMA_PROFILE:-compact"),
        )
        assertTrue(
            "must export it so REA reads it at startup",
            manager.contains("export REA_MCP_INPUT_SCHEMA_PROFILE"),
        )
    }

    // ------------------------------------------------------------- catalog

    /**
     * The advertised catalog must not carry `outputSchema`.
     *
     * Measured against REA 6.8.0: the catalog is 3.79 MB, of which 3.47 MB is
     * `outputSchema`. Shipping it drowns the input schemas the agent needs.
     */
    @Test
    fun theAdvertisedCatalogDropsOutputSchemas() {
        val manager = readSource("app/src/main/kotlin/com/altaris/linux/core/rea/ReaManager.kt")
        assertTrue(
            "must strip outputSchema before advertising",
            manager.contains("copy.remove(\"outputSchema\")"),
        )
        assertTrue(
            "must keep the full definition for schema lookup",
            manager.contains("fun toolDefinition(name: String): JSONObject?"),
        )
    }

    /** `altaris_rea_tools` must return an index, not the whole catalog. */
    @Test
    fun theToolListIsAnIndexNotTheWholeCatalog() {
        val manager = readSource("app/src/main/kotlin/com/altaris/linux/core/rea/ReaManager.kt")
        assertTrue("must expose an index", manager.contains("fun toolIndex(): JSONArray"))
        val mcp = readSource("app/src/main/kotlin/com/altaris/linux/core/control/McpProtocol.kt")
        val tools = mcp.substringAfter("\"altaris_rea_tools\" ->")
            .substringBefore("\"altaris_rea_schema\" ->")
        assertTrue("must return the index", tools.contains("ReaManager.toolIndex()"))
        assertFalse(
            "must not return the raw multi-megabyte catalog",
            tools.contains("ReaManager.cachedTools()"),
        )
    }

    // ----------------------------------------------------------- validator

    /**
     * The exact reported failure, reproduced.
     *
     * `inspect_analysis_view { source: "jadx", view: "decompiled" }` must be
     * rejected with a message that says the values are objects with a `kind`,
     * not strings.
     */
    @Test
    fun aStringWhereAnObjectBelongsIsNamedPrecisely() {
        val schema = JSONObject(
            """
            {
              "type": "object",
              "properties": {
                "source": {
                  "oneOf": [
                    {"type":"object","properties":{"kind":{"const":"retained-evidence"},"evidence_id":{"type":"string"}},"required":["kind","evidence_id"]},
                    {"type":"object","properties":{"kind":{"const":"inline"},"evidence":{"type":"object"}},"required":["kind","evidence"]},
                    {"type":"object","properties":{"kind":{"const":"evidence-file"},"path":{"type":"string"}},"required":["kind","path"]}
                  ]
                }
              },
              "required": ["source"]
            }
            """.trimIndent()
        )
        val problems = ReaSchema.validate(
            schema,
            JSONObject().put("source", "jadx"),
        )
        assertEquals(1, problems.size)
        val text = problems[0].toString()
        assertTrue("must name the field: $text", text.startsWith("source:"))
        assertTrue("must say it expected an object: $text", text.contains("object"))
        assertTrue("must name the discriminator: $text", text.contains("kind"))
        assertTrue("must list a real kind: $text", text.contains("retained-evidence"))
    }

    /** A missing required key is reported by name, not as "invalid input". */
    @Test
    fun aMissingRequiredKeyIsNamed() {
        val schema = JSONObject(
            """{"type":"object","properties":{"pattern":{"type":"string"},"mode":{"type":"string"}},"required":["pattern"]}"""
        )
        val problems = ReaSchema.validate(schema, JSONObject().put("mode", "literal"))
        assertEquals(1, problems.size)
        assertEquals("pattern", problems[0].path)
        assertTrue(problems[0].message.contains("required"))
    }

    /** A wrong type inside a required key is reported with both types. */
    @Test
    fun aWrongTypeReportsExpectedAndActual() {
        val schema = JSONObject(
            """{"type":"object","properties":{"inventory_evidence":{"type":"array"}},"required":["inventory_evidence"]}"""
        )
        val problems = ReaSchema.validate(
            schema,
            JSONObject().put("inventory_evidence", "x"),
        )
        assertEquals(1, problems.size)
        assertEquals("inventory_evidence", problems[0].path)
        assertTrue(problems[0].message.contains("array"))
        assertTrue(problems[0].message.contains("string"))
    }

    /** An unknown top-level argument is reported when the schema forbids extras. */
    @Test
    fun anUnknownTopLevelArgumentIsReported() {
        val schema = JSONObject(
            """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"],"additionalProperties":false}"""
        )
        val problems = ReaSchema.validate(
            schema,
            JSONObject().put("path", "/a.apk").put("classname", "typo"),
        )
        assertEquals(1, problems.size)
        assertEquals("classname", problems[0].path)
        assertTrue(problems[0].message.contains("not a recognised"))
    }

    /**
     * A valid call produces no problems.
     *
     * The validator must never block a call REA would accept. This is the
     * inverse failure mode, and the one that would be worse than no validator.
     */
    @Test
    fun aValidCallPasses() {
        val schema = JSONObject(
            """
            {
              "type": "object",
              "properties": {
                "source": {
                  "oneOf": [
                    {"type":"object","properties":{"kind":{"const":"retained-evidence"},"evidence_id":{"type":"string"}},"required":["kind","evidence_id"],"additionalProperties":false}
                  ]
                },
                "view": {
                  "oneOf": [
                    {"type":"object","properties":{"kind":{"const":"summary"}},"required":["kind"],"additionalProperties":false}
                  ]
                }
              },
              "required": ["source", "view"],
              "additionalProperties": false
            }
            """.trimIndent()
        )
        val arguments = JSONObject()
            .put("source", JSONObject().put("kind", "retained-evidence").put("evidence_id", "ev_" + "0".repeat(64)))
            .put("view", JSONObject().put("kind", "summary"))
        assertTrue(
            "a valid call must not be blocked: " + ReaSchema.validate(schema, arguments),
            ReaSchema.validate(schema, arguments).isEmpty(),
        )
    }

    /**
     * A schema construct the validator does not model must be skipped.
     *
     * REA uses `$ref`, `if/then` and other constructs in places. Guessing at
     * them would reject valid calls, so anything unrecognised is accepted.
     */
    @Test
    fun unknownSchemaConstructsAreNotRejected() {
        val schema = JSONObject(
            """{"type":"object","properties":{"anything":{"${'$'}ref":"#/definitions/X"}},"required":["anything"]}"""
        )
        val problems = ReaSchema.validate(
            schema,
            JSONObject().put("anything", JSONObject().put("weird", true)),
        )
        assertTrue("an unmodelled construct must not block: $problems", problems.isEmpty())
    }

    /** A null schema (tool not cached) accepts anything. */
    @Test
    fun aMissingSchemaAcceptsEverything() {
        assertTrue(ReaSchema.validate(null, JSONObject().put("x", 1)).isEmpty())
    }

    // ------------------------------------------------------------- example

    /**
     * The example must validate against its own schema.
     *
     * A generated example that its own schema rejects is worse than none: the
     * agent copies it and fails again.
     */
    @Test
    fun theGeneratedExampleSatisfiesItsSchema() {
        val schema = JSONObject(
            """
            {
              "type": "object",
              "properties": {
                "source": {
                  "oneOf": [
                    {"type":"object","properties":{"kind":{"const":"retained-evidence"},"evidence_id":{"type":"string"}},"required":["kind","evidence_id"]}
                  ]
                },
                "view": {
                  "oneOf": [
                    {"type":"object","properties":{"kind":{"const":"summary"}},"required":["kind"]}
                  ]
                }
              },
              "required": ["source", "view"]
            }
            """.trimIndent()
        )
        val example = ReaSchema.example(schema)
        assertNotNull("must produce an example", example)
        assertTrue(
            "the example must satisfy its own schema: " + ReaSchema.validate(schema, example!!),
            ReaSchema.validate(schema, example).isEmpty(),
        )
        // And it must show the discriminator, which is the whole lesson.
        assertEquals(
            "retained-evidence",
            example.getJSONObject("source").optString("kind"),
        )
        assertEquals("summary", example.getJSONObject("view").optString("kind"))
    }

    /** An evidence id placeholder must look like one, not like "<value>". */
    @Test
    fun theEvidencePlaceholderLooksLikeAnEvidenceId() {
        val schema = JSONObject(
            """
            {"type":"object","properties":{"evidence_id":{"type":"string","pattern":"^ev_[a-f0-9]{64}${'$'}"}},"required":["evidence_id"]}
            """.trimIndent()
        )
        val example = ReaSchema.example(schema)!!
        val id = example.optString("evidence_id")
        assertTrue("must look like an evidence id: $id", id.startsWith("ev_"))
        assertEquals("must be the right length: $id", 67, id.length)
    }

    // --------------------------------------------------------- tool wiring

    /** `altaris_rea_schema` must exist, be advertised and be handled. */
    @Test
    fun theSchemaToolIsAdvertisedAndHandled() {
        val mcp = readSource("app/src/main/kotlin/com/altaris/linux/core/control/McpProtocol.kt")
        assertTrue("must advertise it", mcp.contains("\"altaris_rea_schema\","))
        assertTrue("must handle it", mcp.contains("\"altaris_rea_schema\" ->"))
        assertTrue("must offer it before REA runs", mcp.contains("\"altaris_rea_schema\" to"))
    }

    /** `altaris_rea_call` must validate before forwarding. */
    @Test
    fun theCallToolValidatesBeforeForwarding() {
        val mcp = readSource("app/src/main/kotlin/com/altaris/linux/core/control/McpProtocol.kt")
        val call = mcp.substringAfter("\"altaris_rea_call\" ->")
            .substringBefore("\"altaris_rea_agents\" ->")
        assertTrue("must look up the real schema", call.contains("ReaManager.toolDefinition("))
        assertTrue("must validate", call.contains("ReaSchema.validate("))
        assertTrue("must report the problems", call.contains("\"problems\""))
        assertTrue("must still forward a valid call", call.contains("ReaManager.call("))
        // The rejection must precede the call.
        assertTrue(
            "validation must come before forwarding",
            call.indexOf("ReaSchema.validate(") < call.indexOf("ReaManager.call("),
        )
    }

    /** An unknown tool name must be named, not silently forwarded. */
    @Test
    fun anUnknownToolNameIsReported() {
        val mcp = readSource("app/src/main/kotlin/com/altaris/linux/core/control/McpProtocol.kt")
        assertTrue(
            "must say the tool does not exist",
            mcp.contains("REA has no tool named"),
        )
    }

    // ------------------------------------------------------ instructions

    /**
     * The instructions must teach the shape and the evidence rule.
     *
     * Both were in the failure log: a string where an object belonged, and an
     * evidence id reused across sources.
     */
    @Test
    fun theInstructionsExplainNestedArgumentsAndEvidenceScope() {
        val mcp = readSource("app/src/main/kotlin/com/altaris/linux/core/control/McpProtocol.kt")
        val instructions = mcp.substringAfter("private val INSTRUCTIONS = \"\"\"")
            .substringBefore("\"\"\".trimIndent()")
            .replace(Regex("\\s+"), " ")
        assertTrue(
            "must say nested arguments are objects with a kind",
            instructions.contains("object discriminated by a `kind` field"),
        )
        assertTrue(
            "must tell the agent to read the schema first",
            instructions.contains("altaris_rea_schema"),
        )
        assertTrue(
            "must say evidence ids are session-scoped",
            instructions.contains("session-scoped"),
        )
        assertTrue(
            "must name the parent_evidence_id field",
            instructions.contains("parent_evidence_id"),
        )
        assertTrue(
            "must name the integrity error",
            instructions.contains("evidence_integrity_mismatch"),
        )
        assertTrue(
            "must forbid repeating an unchanged call",
            instructions.contains("Do not repeat an unchanged call"),
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

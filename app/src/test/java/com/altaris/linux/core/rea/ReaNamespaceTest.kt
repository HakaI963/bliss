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
 * The two-server namespace fix.
 *
 * There are two MCP servers and they expose different tools:
 *
 *   `altaris` — `altaris_*`: clones, UI, screenshots, debugger, REA lifecycle.
 *   `rea`     — REA's own ~229 tools under their bare names.
 *
 * The reported failure was an agent calling `rea.rea_tools`, then
 * `rea.rea_mcp_config_read`, then `rea.discover_webmcp_tools` — none of which
 * it should have reached for. Three separate defects caused that:
 *
 *   1. the guide served *by* the `rea` server told the agent to call
 *      `altaris_rea_tools` and `altaris_rea_schema`, which that server does not
 *      have;
 *   2. the guide used CLI spellings (`inspect-artifact`) where MCP requires
 *      underscores (`inspect_artifact`) — 18 of its 23 names were unusable;
 *   3. there was no index tool at all, so "what can this server do" had no
 *      answer and the agent invented one.
 */
class ReaNamespaceTest {

    // ------------------------------------------------------ guide isolation

    /**
     * The `rea` guide must not name a tool the `rea` server lacks.
     *
     * This is the exact bug: the guide is served by the bridge, so every tool it
     * names has to exist on that server. `altaris_*` tools live on the other
     * one.
     */
    @Test
    fun theReaGuideNamesOnlyReaTools() {
        val guide = ReaKnowledge.REA_GUIDE
        // The wrappers it used to name, none of which the `rea` server has.
        for (wrong in listOf(
            "altaris_rea_tools",
            "altaris_rea_schema",
            "altaris_rea_call",
            "altaris_rea_status",
            "altaris_launch_clone",
            "altaris_ui_tree",
        )) {
            assertFalse(
                "the rea guide must not name $wrong: that server does not have it",
                guide.contains(wrong),
            )
        }
        // And it must name real REA tools instead.
        for (right in listOf(
            "open_binary",
            "inspect_artifact",
            "inspect_analysis_view",
            "search_strings",
        )) {
            assertTrue("the rea guide must name $right", guide.contains(right))
        }
    }

    /** The Altaris guide must not claim to be the REA tool surface. */
    @Test
    fun theAltarisGuideNamesAltarisTools() {
        val guide = ReaKnowledge.ALTARIS_GUIDE
        for (tool in listOf(
            "altaris_rea_tools",
            "altaris_rea_schema",
            "altaris_rea_call",
            "altaris_launch_clone",
            "altaris_debug_attach",
        )) {
            assertTrue("the altaris guide must name $tool", guide.contains(tool))
        }
        // It explains how to reach REA, but does not pretend to be REA.
        assertTrue(guide.contains("Two servers") || guide.contains("separate MCP server"))
    }

    /**
     * The REA guide must never use a CLI spelling as a tool name.
     *
     * REA's CLI is `rea inspect-artifact`; its MCP tool is `inspect_artifact`.
     * The old guide mixed them and 18 of 23 names were rejected as unknown.
     */
    @Test
    fun theReaGuideUsesUnderscoresNotCliDashes() {
        val guide = ReaKnowledge.REA_GUIDE
        val dashNames = Regex("`([a-z][a-z0-9]+(?:-[a-z0-9]+)+)`")
            .findAll(guide)
            .map { it.groupValues[1] }
            .filterNot { it.startsWith("rea-") }  // rea://guide is a URI
            .toList()
        assertTrue(
            "the rea guide must not spell tool names with dashes: $dashNames",
            dashNames.isEmpty(),
        )
    }

    /** Every backticked REA-looking name in the guide must be a real MCP tool. */
    @Test
    fun everyToolNameInTheReaGuideIsReal() {
        // The catalog is not on the JVM test classpath, so this checks the shape
        // that caused the bug: an `altaris_` prefix on the REA server.
        val guide = ReaKnowledge.REA_GUIDE
        val names = Regex("`([a-z][a-z0-9_]+)`")
            .findAll(guide)
            .map { it.groupValues[1] }
            .filterNot { it.startsWith("altaris") }
            .toList()
        // Words that are not tools, and are used as prose.
        val prose = setOf(
            "kind", "source", "view", "rea", "evidence_id", "normalized_result",
            "parent_evidence_id", "snapshot_path", "capability_unavailable",
            "unsupported_target", "evidence_integrity_mismatch", "rea_tools",
            "list_tools", "help",
        )
        val toolNames = names.filterNot { it in prose }
        assertTrue("the guide should name real tools", toolNames.isNotEmpty())
        for (name in toolNames) {
            assertFalse(
                "$name looks like a CLI name, not an MCP tool",
                name.contains('-'),
            )
        }
    }

    // -------------------------------------------------------- index tool

    /** The bridge must answer `rea_tools` itself. */
    @Test
    fun theBridgeAnswersTheIndexToolItself() {
        val bridge = readSource("app/src/main/kotlin/com/altaris/linux/core/rea/BridgeServer.kt")
        assertTrue(
            "must define the synthetic tool name",
            bridge.contains("INDEX_TOOL = \"rea_tools\""),
        )
        assertTrue(
            "must intercept tools/call for it",
            bridge.contains("params?.optString(\"name\") == INDEX_TOOL"),
        )
        assertTrue(
            "must advertise it in tools/list",
            bridge.contains("indexToolDefinition()"),
        )
        assertTrue(
            "must produce an index body",
            bridge.contains("indexResult("),
        )
        // The interception must come before the forward, or REA answers
        // "unknown tool" for a name it has never heard of.
        assertTrue(
            "the index call must be handled before forwarding",
            bridge.indexOf("== INDEX_TOOL") < bridge.indexOf("session.forward(method, params, TOOL_TIMEOUT_MS)"),
        )
    }

    /** The index must say how to get a schema, since there is no rea_schema. */
    @Test
    fun theIndexSaysWhereSchemasComeFrom() {
        val bridge = readSource("app/src/main/kotlin/com/altaris/linux/core/rea/BridgeServer.kt")
        // The literal is split across concatenated Kotlin strings, so match the
        // fragments that carry the meaning rather than the exact source layout.
        assertTrue(
            "must point at tools/list for schemas",
            bridge.contains("inputSchema from") && bridge.contains("tools/list for its exact"),
        )
        assertTrue(
            "must warn against inventing index tools",
            bridge.contains("do not call") && bridge.contains("rea_schema, list_tools or help"),
        )
    }

    // ------------------------------------------------------- catalog size

    /**
     * The bridge must strip `outputSchema`.
     *
     * REA publishes a reply schema for all 229 tools: 4.58 MB of `tools/list`,
     * of which stripping `outputSchema` removes 3.5 MB. A catalog that large is
     * truncated by the client, and a truncated catalog looks exactly like a
     * missing tool — which is how the agent concluded it had to guess.
     */
    @Test
    fun theBridgeStripsOutputSchemas() {
        val bridge = readSource("app/src/main/kotlin/com/altaris/linux/core/rea/BridgeServer.kt")
        val listCase = bridge.substringAfter("\"tools/list\" ->")
            .substringBefore("\"resources/list\" ->")
        assertTrue(
            "must strip outputSchema from the advertised catalog",
            listCase.contains("remove(\"outputSchema\")"),
        )
        assertTrue(
            "must keep the input schemas",
            !listCase.contains("remove(\"inputSchema\")"),
        )
    }

    /** The nested input shapes must survive, or the yesterday-fix regresses. */
    @Test
    fun theBridgeKeepsNestedInputSchemas() {
        val manager = readSource("app/src/main/kotlin/com/altaris/linux/core/rea/ReaManager.kt")
        assertTrue(
            "the launcher must still ask for the full profile",
            manager.contains("REA_MCP_INPUT_SCHEMA_PROFILE=full"),
        )
        // The bridge shares that launcher, so the in-distro agent gets the same
        // real shapes — just without the reply schemas.
        val bridge = readSource("app/src/main/kotlin/com/altaris/linux/core/rea/BridgeServer.kt")
        assertTrue(
            "the bridge must not downgrade the profile",
            !bridge.contains("REA_MCP_INPUT_SCHEMA_PROFILE=compact"),
        )
    }

    // ---------------------------------------------------------- wiring

    /** The bridge must serve the REA guide, not the Altaris one. */
    @Test
    fun theBridgeServesTheReaGuide() {
        val bridge = readSource("app/src/main/kotlin/com/altaris/linux/core/rea/BridgeServer.kt")
        assertTrue(
            "must serve the REA-native guide",
            bridge.contains("ReaKnowledge.REA_GUIDE"),
        )
        assertFalse(
            "must not serve the Altaris guide from the REA server",
            bridge.contains("ReaKnowledge.ALTARIS_GUIDE"),
        )
    }

    /** The distro files must carry the Altaris guide, since that is the server. */
    @Test
    fun theDistroSkillCarriesTheAltarisGuide() {
        val manager = readSource("app/src/main/kotlin/com/altaris/linux/core/rea/ReaManager.kt")
        assertTrue(
            "the distro skill must be the Altaris guide",
            manager.contains("ReaKnowledge.ALTARIS_GUIDE"),
        )
        assertFalse(
            "the distro skill must not be the REA-only guide",
            manager.contains("ReaKnowledge.REA_GUIDE"),
        )
    }

    /** The instructions must explain the split, so the agent picks the right one. */
    @Test
    fun theInstructionsExplainTheTwoNamespaces() {
        val mcp = readSource("app/src/main/kotlin/com/altaris/linux/core/control/McpProtocol.kt")
        val instructions = mcp.substringAfter("private val INSTRUCTIONS = \"\"\"")
            .substringBefore("\"\"\".trimIndent()")
            .replace(Regex("\\s+"), " ")
        assertTrue(
            "must say there are two servers",
            instructions.contains("Two servers, two namespaces"),
        )
        assertTrue(
            "must say the rea server has bare names",
            instructions.contains("bare names"),
        )
        assertTrue(
            "must say not to mix the namespaces",
            instructions.contains("Do not call an `altaris_*` tool on the `rea` server"),
        )
        assertTrue(
            "must say MCP names use underscores",
            instructions.contains("underscores"),
        )
        assertTrue(
            "must deny the invented index tools",
            instructions.contains("There is no `rea_tools`"),
        )
    }

    /** The two guides must be distinct documents. */
    @Test
    fun theTwoGuidesAreDistinct() {
        assertTrue("the guides must differ", ReaKnowledge.REA_GUIDE != ReaKnowledge.ALTARIS_GUIDE)
        assertNotNull(ReaKnowledge.REA_GUIDE)
        assertNotNull(ReaKnowledge.ALTARIS_GUIDE)
        assertEquals("rea://guide", ReaKnowledge.URI)
        assertEquals("altaris://rea-guide", ReaKnowledge.ALTARIS_URI)
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

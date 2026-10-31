package com.altaris.linux.core.rea

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Finding extraction.
 *
 * The Functions and Compare tabs are built entirely from this: if it recognises
 * the wrong shape, the dashboard shows invented rows. Every case here is a real
 * REA result shape.
 */
class FindingsTest {

    @Test
    fun mcpTextEnvelopeIsUnwrapped() {
        // MCP wraps tool output in content[0].text as a JSON string.
        val inner = """{"functions":[{"name":"main","offset":"0x401000","size":128}]}"""
        val raw = """{"content":[{"type":"text","text":${quote(inner)}}]}"""
        val extracted = Findings.extract(raw)
        assertTrue(extracted.hasRows)
        val row = extracted.rows.first()
        assertEquals("main", row.name)
        assertEquals(Findings.Row.Kind.FUNCTION, row.kind)
        assertEquals("0x401000", row.offset)
        assertEquals(128L, row.size)
    }

    @Test
    fun aPlainJsonResultIsReadDirectly() {
        val raw = """{"symbols":[{"name":"Java_com_app_Main_run","address":4198400}]}"""
        val extracted = Findings.extract(raw)
        assertTrue(extracted.hasRows)
        assertEquals("Java_com_app_Main_run", extracted.rows.first().name)
        // A numeric address is rendered as hex.
        assertTrue(extracted.rows.first().offset.startsWith("0x"))
    }

    @Test
    fun bareStringListsBecomeRows() {
        val raw = """{"sections":["text","data","rodata"]}"""
        val extracted = Findings.extract(raw)
        assertEquals(3, extracted.rows.size)
        assertTrue(extracted.rows.all { it.kind == Findings.Row.Kind.SECTION })
        assertTrue(extracted.rows.any { it.name == "rodata" })
    }

    @Test
    fun aComparisonReportsMatchedAndDifferingBytes() {
        val raw = """
            {"identical":false,"matched_bytes":4096,"differing_bytes":12,
             "total_bytes":4108,
             "diffs":[{"offset":"0x20","name":"padding","detail":"4 bytes"}]}
        """.trimIndent()
        val extracted = Findings.extract(raw)
        assertEquals(false, extracted.compared)
        assertEquals(4096L, extracted.matchedBytes)
        assertEquals(12L, extracted.differentBytes)
        assertEquals(4108L, extracted.totalBytes)
        assertTrue(extracted.rows.any { it.kind == Findings.Row.Kind.DIFF })
    }

    @Test
    fun anIdenticalComparisonIsRecognised() {
        val raw = """{"identical":true,"matched_bytes":1024,"differing_bytes":0}"""
        val extracted = Findings.extract(raw)
        assertEquals(true, extracted.compared)
        assertEquals(1024L, extracted.matchedBytes)
        assertEquals(0L, extracted.differentBytes)
    }

    @Test
    fun malformedInputYieldsNothingRatherThanGuesses() {
        assertFalse(Findings.extract("").hasRows)
        assertFalse(Findings.extract("not json").hasRows)
        assertFalse(Findings.extract("{}").hasRows)
        assertFalse(Findings.extract("""{"unrelated":"value"}""").hasRows)
        assertNull(Findings.extract("{}").compared)
    }

    @Test
    fun rowsAreDeduplicated() {
        // The same symbol listed under two keys must not appear twice.
        val raw = """
            {"functions":[{"name":"main","offset":"0x1000"}],
             "symbols":[{"name":"main","offset":"0x1000"}]}
        """.trimIndent()
        val extracted = Findings.extract(raw)
        assertEquals(1, extracted.rows.count { it.name == "main" })
    }

    /** JSON-escape a string for embedding in a fixture. */
    private fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

/** The MCP bridge contract. */
class ReaBridgeTest {

    @Test
    fun theEndpointIsLoopbackAndDistinctFromCloneControl() {
        assertTrue(ReaBridge.ENDPOINT.startsWith("http://127.0.0.1:"))
        assertTrue(ReaBridge.ENDPOINT.endsWith("/mcp"))
        // The clone-control server already owns 47831; the bridge must not take it.
        assertTrue(ReaBridge.PORT != 47831)
    }

    @Test
    fun theConfigBlockIsValidJsonForAnAgent() {
        val json = org.json.JSONObject(ReaBridge.configJson())
        val server = json.getJSONObject("mcpServers").getJSONObject("rea")
        assertEquals("http", server.getString("type"))
        assertEquals(ReaBridge.ENDPOINT, server.getString("url"))
    }

    @Test
    fun aCallIsRecordedBeforeItFinishesAndThenCompleted() {
        ReaBridge.clearHistory()
        val call = ReaBridge.beginCall("inspect-artifact", """{"path":"/root/a.apk"}""")
        assertTrue(call.running)
        assertEquals(1, ReaBridge.calls.value.size)

        ReaBridge.finishCall(
            call.id,
            """{"functions":[{"name":"a","offset":"0x1"}]}""",
            "",
            42L,
        )
        val done = ReaBridge.calls.value.first { it.id == call.id }
        assertFalse(done.running)
        assertEquals(42L, done.ms)
        assertTrue(done.bytes > 0)
        assertEquals(1, done.findings.size)
        ReaBridge.clearHistory()
    }

    @Test
    fun aFailedCallKeepsItsErrorAndExtractsNothing() {
        ReaBridge.clearHistory()
        val call = ReaBridge.beginCall("decompile", "{}")
        ReaBridge.finishCall(call.id, "", "ghidra is not installed", 7L)
        val done = ReaBridge.calls.value.first { it.id == call.id }
        assertEquals("ghidra is not installed", done.error)
        assertTrue(done.findings.isEmpty())
        // A failure is still a recorded call, so the log shows it.
        assertTrue(ReaBridge.logs.value.any { it.level == ReaBridge.LogLine.Level.ERROR })
        ReaBridge.clearHistory()
    }

    @Test
    fun theLogIsCapped() {
        ReaBridge.clearHistory()
        repeat(320) { ReaBridge.beginCall("tool$it", "{}") }
        assertTrue(ReaBridge.logs.value.size <= 300)
        assertTrue(ReaBridge.calls.value.size <= 400)
        ReaBridge.clearHistory()
        assertTrue(ReaBridge.logs.value.isEmpty())
    }
}

/** The parallel tool installer's state model. */
class ReaToolInstallerTest {

    @Test
    fun theSnapshotCountsEachState() {
        val snapshot = ReaToolInstaller.Snapshot(
            tools = listOf(
                ReaToolInstaller.ToolState("a", "JADX", ReaToolInstaller.ToolState.Status.DONE),
                ReaToolInstaller.ToolState("b", "Ghidra", ReaToolInstaller.ToolState.Status.RUNNING),
                ReaToolInstaller.ToolState("c", "rizin", ReaToolInstaller.ToolState.Status.FAILED),
                ReaToolInstaller.ToolState("d", "binwalk", ReaToolInstaller.ToolState.Status.QUEUED),
            ),
            running = true,
        )
        assertEquals(1, snapshot.done)
        assertEquals(1, snapshot.failed)
        assertEquals(1, snapshot.active)
        assertEquals(1, snapshot.queued)
        assertEquals(0.25f, snapshot.fraction, 0.001f)
        assertTrue(snapshot.summary.contains("1 / 4 done"))
    }

    @Test
    fun anEmptySnapshotIsNotAFailure() {
        val snapshot = ReaToolInstaller.Snapshot()
        assertEquals(0f, snapshot.fraction, 0.001f)
        assertEquals(0, snapshot.failed)
    }
}

package com.altaris.linux.core.rea

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The binary map.
 *
 * Everything here is derived from recorded calls, so these tests are about
 * honesty: a flat map when nothing has run, red where a comparison disagreed,
 * and a rate that matches the rows it summarises.
 */
class BinaryChunksTest {

    private fun call(
        tool: String = "inspect-artifact",
        running: Boolean = false,
        error: String = "",
        compared: Boolean? = null,
        different: Long = 0L,
    ) = ReaBridge.Call(
        tool = tool,
        arguments = "{}",
        running = running,
        error = error,
        compared = compared,
        differentBytes = different,
    )

    @Test
    fun anEmptyRunProducesAFlatMap() {
        val map = BinaryChunks.build(emptyList())
        assertEquals(BinaryChunks.ROWS, map.rows.size)
        assertTrue(map.all.all { it.state == ChunkState.UNTOUCHED })
        // Nothing resolved, so every rail is empty.
        assertTrue(map.rows.all { it.rate == 0f })
        assertEquals(0f, BinaryChunks.progress(map), 0.001f)
    }

    @Test
    fun aSuccessfulCallVerifiesItsChunk() {
        val map = BinaryChunks.build(listOf(call()))
        assertEquals(ChunkState.VERIFIED, map.all.first().state)
        assertTrue(BinaryChunks.progress(map) > 0f)
    }

    @Test
    fun aRunningCallShowsAsProcessing() {
        val map = BinaryChunks.build(listOf(call(running = true)))
        assertEquals(ChunkState.PROCESSING, map.all.first().state)
        // Processing is not resolved yet, so it does not count toward the rate.
        assertEquals(0f, BinaryChunks.progress(map), 0.001f)
    }

    @Test
    fun aFailedCallShowsRed() {
        val map = BinaryChunks.build(listOf(call(error = "ghidra missing")))
        assertEquals(ChunkState.FAILED, map.all.first().state)
        assertTrue(map.rows.first().failed > 0)
    }

    /** A comparison that disagrees marks the chunk as a mismatch, not a pass. */
    @Test
    fun aComparisonMismatchIsRed() {
        val map = BinaryChunks.build(
            listOf(call(tool = "compare-artifacts", compared = false, different = 12L))
        )
        assertEquals(ChunkState.MISMATCH, map.all.first().state)
    }

    @Test
    fun anIdenticalComparisonVerifies() {
        val map = BinaryChunks.build(
            listOf(call(tool = "compare-artifacts", compared = true, different = 0L))
        )
        assertEquals(ChunkState.VERIFIED, map.all.first().state)
    }

    @Test
    fun callsFillTheMapInOrderAndWrap() {
        val calls = (0 until BinaryChunks.ROWS * BinaryChunks.COLUMNS + 5)
            .map { call() }
        val map = BinaryChunks.build(calls)
        assertEquals(BinaryChunks.ROWS * BinaryChunks.COLUMNS, map.all.size)
        // Every chunk is covered once the map is full.
        assertTrue(map.all.all { it.state == ChunkState.VERIFIED })
        assertEquals(1f, BinaryChunks.progress(map), 0.001f)
    }

    @Test
    fun rowsAreLabelledFromA() {
        val map = BinaryChunks.build(emptyList())
        assertEquals("A", map.rows.first().label)
        assertEquals("H", map.rows.last().label)
    }

    @Test
    fun aRowsRateMatchesItsOwnChunks() {
        // Two verified out of twelve.
        val map = BinaryChunks.build(listOf(call(), call()))
        val first = map.rows.first()
        assertEquals(2f / BinaryChunks.COLUMNS, first.rate, 0.001f)
        assertEquals(0f, map.rows.last().rate, 0.001f)
    }

    @Test
    fun agentsGroupByToolFamily() {
        val calls = listOf(
            call(tool = "inspect-artifact"),
            call(tool = "inspect-elf-notes"),
            call(tool = "decompile-function", error = "failed"),
        )
        val agents = BinaryChunks.agents(calls)
        assertEquals(2, agents.size)
        // Sorted by call count, so inspect comes first.
        assertEquals("inspect", agents.first().label)
        assertEquals(2, agents.first().calls)
        assertEquals(1f, agents.first().done, 0.001f)
        val decompile = agents.first { it.label == "decompile" }
        assertEquals(1, decompile.failed)
    }

    @Test
    fun noCallsMeansNoAgents() {
        assertTrue(BinaryChunks.agents(emptyList()).isEmpty())
    }

    @Test
    fun familiesAreRecognisedFromRealToolNames() {
        assertEquals("inspect", BinaryChunks.familyOf("inspect-elf-notes"))
        assertEquals("decompile", BinaryChunks.familyOf("decompile-function"))
        assertEquals("compare", BinaryChunks.familyOf("compare-artifacts"))
        assertEquals("extract", BinaryChunks.familyOf("extract-artifact"))
        assertEquals("analyse", BinaryChunks.familyOf("analyze-javascript-application"))
    }
}

/** The guide an agent reads over MCP. */
class ReaKnowledgeTest {

    @Test
    fun theGuideNamesRealToolFamilies() {
        val guide = ReaKnowledge.GUIDE
        // The tool names an agent will actually see in tools/list.
        for (tool in listOf(
            "inspect-artifact", "inspect-managed-members", "inspect-pe-imports",
            "compare-artifacts", "extract-artifact", "inspect-sqlite-database",
        )) {
            assertTrue("guide must mention $tool", guide.contains(tool))
        }
    }

    @Test
    fun theGuideTeachesMethodNotTrivia() {
        val guide = ReaKnowledge.GUIDE
        // The three rules that stop an agent wasting a run.
        assertTrue(guide.contains("Identify the artifact first"))
        assertTrue(guide.contains("Do not guess a format from the file name"))
        assertTrue(guide.contains("Never invent addresses"))
        assertTrue(guide.contains("Compare instead of eyeballing"))
    }

    @Test
    fun thePromptIsShortAndPointsAtTheGuide() {
        assertTrue(ReaKnowledge.PROMPT.contains("REA"))
        assertTrue(ReaKnowledge.PROMPT.contains(ReaKnowledge.URI))
        assertTrue(ReaKnowledge.PROMPT.length < 600)
    }

    @Test
    fun theUriIsStable() {
        assertEquals("rea://guide", ReaKnowledge.URI)
    }
}

/** Chroot install must not abort on a cold root cache. */
class ReaInstallerRootTest {

    @Test
    fun theDownloadDetailParserStillWorks() {
        val parsed = ReaInstaller.parseDownload("12.3 / 780.1 MB · 3.2 MB/s")
        assertTrue(parsed != null)
        assertEquals(12.3 * 1_048_576.0, parsed!!.first.toDouble(), 1024.0)
        // A phase line is not a byte counter.
        assertTrue(ReaInstaller.parseDownload("Extracting chroot rootfs") == null)
    }

    @Test
    fun theEndpointAndPortAreStable() {
        assertEquals(47832, ReaBridge.PORT)
        assertTrue(ReaBridge.ENDPOINT.contains(":" + ReaBridge.PORT))
        assertFalse(ReaBridge.ENDPOINT.contains("0.0.0.0"))
    }
}

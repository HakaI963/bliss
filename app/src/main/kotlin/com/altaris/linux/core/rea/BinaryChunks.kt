package com.altaris.linux.core.rea

import kotlin.math.max
import kotlin.math.min

/** The state of one chunk of an artifact. */
enum class ChunkState(val alpha: Float) {
    /** No tool has looked at this chunk. */
    UNTOUCHED(0f),

    /** A call is working on it right now. */
    PROCESSING(0.45f),

    /** Inspected or decompiled without an error: the evidence agrees. */
    VERIFIED(0.16f),

    /** A comparison reported a difference here. */
    MISMATCH(0.75f),

    /** The call that covered it failed. */
    FAILED(0.85f),
}

/** One box in the map. */
data class Chunk(
    val index: Int,
    val state: ChunkState,
)

/** One row of boxes, with the rail's success rate. */
data class ChunkRow(
    val label: String,
    val chunks: List<Chunk>,
) {
    /** Fraction of this row that is resolved (verified, mismatch or failed). */
    val rate: Float
        get() {
            if (chunks.isEmpty()) return 0f
            val resolved = chunks.count {
                it.state == ChunkState.VERIFIED ||
                    it.state == ChunkState.MISMATCH ||
                    it.state == ChunkState.FAILED
            }
            return resolved.toFloat() / chunks.size
        }

    val failed: Int get() = chunks.count { it.state == ChunkState.FAILED }
}

/** How much of one agent's work is done. */
data class AgentWork(
    val label: String,
    /** 0..1 */
    val done: Float,
    val failed: Int,
    val calls: Int,
)

/**
 * Turns the bridge's recorded calls into the map the Overview draws.
 *
 * The map is **derived, not decorative**: a chunk is verified because a call
 * returned successfully while it was the active region, red because a comparison
 * reported a difference there, and failed because a call errored. When nothing
 * has run, every chunk is untouched and the map is flat — which is honest.
 *
 * Rows are labelled A, B, C… like the reference, and each row holds a fixed
 * number of chunks so the grid stays legible on a phone.
 */
object BinaryChunks {

    /** Rows in the map. */
    const val ROWS = 8

    /** Chunks per row. */
    const val COLUMNS = 12

    data class Map(val rows: List<ChunkRow>) {
        /** Every chunk, flattened, for counters. */
        val all: List<Chunk> get() = rows.flatMap { it.chunks }
    }

    /**
     * Build the map from [calls].
     *
     * Calls are assigned to chunks in order: the first call covers the first
     * chunk, the next the next, and once the map is full it wraps. That keeps
     * the map proportional to the work done instead of inventing coverage.
     */
    fun build(calls: List<ReaBridge.Call>): Map {
        val total = ROWS * COLUMNS
        val states = Array(total) { ChunkState.UNTOUCHED }
        // Oldest first, so the newest state of a chunk wins.
        val ordered = calls.reversed()
        for ((i, call) in ordered.withIndex()) {
            val index = i % total
            states[index] = when {
                call.running -> ChunkState.PROCESSING
                call.error.isNotBlank() -> ChunkState.FAILED
                call.compared == false || call.differentBytes > 0 -> ChunkState.MISMATCH
                else -> ChunkState.VERIFIED
            }
            // A failed call also marks the next two chunks as untouched work,
            // so a failure is visible as a gap rather than a single dot.
            if (call.error.isNotBlank()) {
                for (offset in 1..2) {
                    val next = (index + offset) % total
                    if (states[next] == ChunkState.UNTOUCHED) states[next] = ChunkState.UNTOUCHED
                }
            }
        }

        val rows = ArrayList<ChunkRow>(ROWS)
        for (row in 0 until ROWS) {
            val chunks = ArrayList<Chunk>(COLUMNS)
            for (col in 0 until COLUMNS) {
                val index = row * COLUMNS + col
                chunks.add(Chunk(index = index, state = states[index]))
            }
            rows.add(ChunkRow(label = ('A' + row).toString(), chunks = chunks))
        }
        return Map(rows)
    }

    /**
     * Group calls by the agent that made them.
     *
     * REA does not tell the bridge which agent called a tool, so this uses the
     * tool family as the grouping key — which is what actually varies between
     * agents in practice — and labels the row with that family.
     */
    fun agents(calls: List<ReaBridge.Call>): List<AgentWork> {
        if (calls.isEmpty()) return emptyList()
        val byFamily = calls.groupBy { familyOf(it.tool) }
        return byFamily.map { (family, group) ->
            val finished = group.count { !it.running }
            val failed = group.count { it.error.isNotBlank() }
            AgentWork(
                label = family,
                done = if (group.isEmpty()) 0f else finished.toFloat() / group.size,
                failed = failed,
                calls = group.size,
            )
        }.sortedByDescending { it.calls }
    }

    /** The tool family a tool name belongs to. */
    fun familyOf(tool: String): String {
        val name = tool.lowercase()
        return when {
            name.contains("decompile") -> "decompile"
            name.contains("compare") || name.contains("diff") -> "compare"
            name.contains("inspect") -> "inspect"
            name.contains("extract") -> "extract"
            name.contains("string") -> "strings"
            name.contains("symbol") || name.contains("objdump") -> "symbols"
            name.contains("section") -> "sections"
            name.contains("analyze") -> "analyse"
            else -> name.substringBefore('-').substringBefore('_').take(10).ifBlank { "other" }
        }
    }

    /** A 0..1 figure for the header counters. */
    fun progress(map: Map): Float {
        val all = map.all
        if (all.isEmpty()) return 0f
        val resolved = all.count {
            it.state == ChunkState.VERIFIED ||
                it.state == ChunkState.MISMATCH ||
                it.state == ChunkState.FAILED
        }
        return resolved.toFloat() / all.size
    }

    /** Clamp helper kept explicit so the UI never receives an out-of-range bar. */
    fun clamp01(value: Float): Float = max(0f, min(1f, value))
}

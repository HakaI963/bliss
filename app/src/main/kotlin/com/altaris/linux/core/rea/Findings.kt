package com.altaris.linux.core.rea

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pulls findings out of a REA tool result.
 *
 * REA answers in JSON, but the shape differs per tool: `inspect-*` returns
 * sections and symbols, `compare-artifacts` returns matched and differing
 * ranges, `decompile` returns functions. This walks the result and recognises
 * the shapes it knows, so the Functions tab has real rows (name, offset,
 * detail) instead of a wall of JSON.
 *
 * Anything it does not recognise is simply not reported — a wrong row is worse
 * than a missing one.
 */
object Findings {

    /** A function/symbol/section row. */
    data class Row(
        val kind: Kind,
        val name: String,
        val detail: String,
        val offset: String = "",
        /** Bytes, when the row describes a size. */
        val size: Long = 0L,
    ) {
        enum class Kind(val label: String) {
            FUNCTION("function"),
            SECTION("section"),
            IMPORT("import"),
            EXPORT("export"),
            STRING("string"),
            CLASS("class"),
            FILE("file"),
            DIFF("diff"),
            OTHER("info"),
        }
    }

    /** Everything recognised in one result. */
    data class Extracted(
        val rows: List<Row> = emptyList(),
        /** True/false when the result reports a comparison; null otherwise. */
        val compared: Boolean? = null,
        val matchedBytes: Long = 0L,
        val differentBytes: Long = 0L,
        val totalBytes: Long = 0L,
    ) {
        val hasRows: Boolean get() = rows.isNotEmpty()
    }

    /**
     * Recognise findings in [raw].
     *
     * @return the extracted rows, or null when nothing was recognised.
     */
    fun extract(raw: String): Extracted {
        if (raw.isBlank()) return Extracted()
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return Extracted()
        // MCP wraps tool output in `content: [{type:text, text:"…json…"}]`.
        val payload = unwrap(json)
        return Extracted(
            rows = collect(payload),
            compared = boolOf(payload, "identical", "match", "equal"),
            matchedBytes = longOf(payload, "matched_bytes", "matching_bytes", "equal_bytes", "matchedBytes"),
            differentBytes = longOf(payload, "differing_bytes", "different_bytes", "diff_bytes", "differingBytes"),
            totalBytes = longOf(payload, "total_bytes", "size", "bytes", "totalBytes"),
        )
    }

    /** Unwrap the MCP text envelope when present. */
    private fun unwrap(json: JSONObject): JSONObject {
        val content = json.optJSONArray("content") ?: return json
        for (i in 0 until content.length()) {
            val text = content.optJSONObject(i)?.optString("text").orEmpty()
            if (text.isBlank()) continue
            runCatching { JSONObject(text) }.getOrNull()?.let { return it }
        }
        return json
    }

    /** Walk the payload for arrays that look like findings. */
    private fun collect(payload: JSONObject): List<Row> {
        val rows = LinkedHashMap<String, Row>()
        fun put(row: Row) {
            val key = row.kind.name + ":" + row.name + ":" + row.offset
            if (!rows.containsKey(key)) rows[key] = row
        }

        fun walk(node: Any?, key: String) {
            when (node) {
                is JSONObject -> {
                    // A single finding described by this object.
                    val name = firstString(node, "name", "symbol", "function", "section", "path", "file")
                    if (name != null) {
                        put(
                            Row(
                                kind = kindFor(key, name),
                                name = name,
                                detail = detailFor(node),
                                offset = offsetFor(node),
                                size = longOf(node, "size", "bytes", "length"),
                            )
                        )
                    }
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        walk(node.opt(k), k)
                    }
                }
                is JSONArray -> {
                    for (i in 0 until node.length()) walk(node.opt(i), key)
                }
                is String -> {
                    // A bare string in a recognised list is a name.
                    if (key in NAMED_LISTS && node.isNotBlank() && node.length < 200) {
                        put(Row(kindFor(key, node), node, ""))
                    }
                }
            }
        }

        val keys = payload.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            walk(payload.opt(k), k)
        }
        return rows.values.take(MAX_ROWS)
    }

    private fun kindFor(key: String, name: String): Row.Kind {
        val k = key.lowercase()
        return when {
            k.contains("function") || k.contains("symbol") || k.contains("method") -> Row.Kind.FUNCTION
            k.contains("section") || k.contains("segment") -> Row.Kind.SECTION
            k.contains("import") -> Row.Kind.IMPORT
            k.contains("export") -> Row.Kind.EXPORT
            k.contains("string") -> Row.Kind.STRING
            k.contains("class") || k.contains("type") -> Row.Kind.CLASS
            k.contains("diff") || k.contains("differ") -> Row.Kind.DIFF
            k.contains("file") || k.contains("artifact") || k.contains("path") -> Row.Kind.FILE
            else -> if (name.contains('(')) Row.Kind.FUNCTION else Row.Kind.OTHER
        }
    }

    private fun detailFor(node: JSONObject): String {
        val parts = ArrayList<String>()
        firstString(node, "type", "kind", "arch", "language")?.let { parts.add(it) }
        longOf(node, "size", "bytes", "length").takeIf { it > 0 }?.let {
            parts.add(ReaBridge.formatBytes(it))
        }
        firstString(node, "summary", "description", "value", "signature")
            ?.takeIf { it.length < 120 }?.let { parts.add(it) }
        return parts.joinToString(" · ")
    }

    /**
     * The offset/address of a row.
     *
     * Checked as a number first: `optString` on a numeric JSON value returns
     * the number's text, which would render a decimal address as if it were
     * already formatted. A real string (often already `0x…`) wins.
     */
    private fun offsetFor(node: JSONObject): String {
        for (key in listOf("offset", "address", "addr", "start", "va", "rva")) {
            when (val value = node.opt(key)) {
                is Number -> {
                    val numeric = value.toLong()
                    if (numeric > 0) return "0x" + numeric.toString(16)
                }
                is String -> if (value.isNotBlank() && value != "null") return value
            }
        }
        return ""
    }

    private fun firstString(node: JSONObject, vararg keys: String): String? {
        for (key in keys) {
            val value = node.optString(key)
            if (value.isNotBlank() && value != "null") return value
        }
        return null
    }

    private fun longOf(node: JSONObject, vararg keys: String): Long {
        for (key in keys) {
            val value = node.opt(key)
            when (value) {
                is Number -> return value.toLong()
                is String -> value.toLongOrNull()?.let { return it }
            }
        }
        return 0L
    }

    private fun boolOf(node: JSONObject, vararg keys: String): Boolean? {
        for (key in keys) {
            if (!node.has(key)) continue
            val value = node.opt(key)
            return when (value) {
                is Boolean -> value
                is String -> value.equals("true", ignoreCase = true)
                else -> null
            }
        }
        return null
    }

    /** Keys whose array members are plain names. */
    private val NAMED_LISTS = setOf(
        "functions", "symbols", "sections", "imports", "exports", "classes",
        "strings", "files", "methods", "types",
    )

    private const val MAX_ROWS = 500
}

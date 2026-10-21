package com.ivarna.fluxlinux.core.a11y

import org.json.JSONArray
import org.json.JSONObject

/**
 * One accessibility node of the virtual display, as listed in the viewer.
 */
data class A11yNode(
    val id: Long,
    val className: String,
    val text: String,
    val description: String,
    val packageName: String,
    val bounds: List<Int>,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val enabled: Boolean,
    val visible: Boolean,
    val checked: Boolean,
    val depth: Int,
) {
    /** Visible label: node text first, then the content description. */
    val label: String get() = text.ifBlank { description }

    /** Short type name, e.g. "TextView". */
    val type: String get() = className.substringAfterLast('.').ifBlank { "node" }

    /** Compact bounds summary, e.g. "12,34 → 200,98". */
    val boundsLabel: String
        get() = if (bounds.size >= 4) {
            "${bounds[0]},${bounds[1]} → ${bounds[2]},${bounds[3]}"
        } else {
            ""
        }
}

/** Parsers for the accessibility tree payloads. */
object A11yNodes {

    /** Parse the `nodes` array of a tree payload. Never throws. */
    fun parse(json: String): List<A11yNode> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        return parseArray(root.optJSONArray("nodes"))
    }

    /** Parse a `nodes` array. Never throws. */
    fun parseArray(array: JSONArray?): List<A11yNode> {
        if (array == null) return emptyList()
        val out = ArrayList<A11yNode>(array.length())
        for (i in 0 until array.length()) {
            val node = array.optJSONObject(i) ?: continue
            out.add(
                A11yNode(
                    id = node.optLong("id", -1L),
                    className = node.optString("class"),
                    text = node.optString("text"),
                    description = node.optString("desc"),
                    packageName = node.optString("package"),
                    bounds = parseBounds(node.optJSONArray("bounds")),
                    clickable = node.optBoolean("clickable", false),
                    editable = node.optBoolean("editable", false),
                    scrollable = node.optBoolean("scrollable", false),
                    enabled = node.optBoolean("enabled", true),
                    visible = node.optBoolean("visible", true),
                    checked = node.optBoolean("checked", false),
                    depth = node.optInt("depth", 0),
                ),
            )
        }
        return out
    }

    private fun parseBounds(array: JSONArray?): List<Int> {
        if (array == null) return emptyList()
        val out = ArrayList<Int>(array.length())
        for (i in 0 until array.length()) out.add(array.optInt(i))
        return out
    }
}

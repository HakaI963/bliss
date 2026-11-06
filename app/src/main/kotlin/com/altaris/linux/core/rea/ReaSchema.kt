package com.altaris.linux.core.rea

import org.json.JSONArray
import org.json.JSONObject

/**
 * Checks a REA call's arguments against the tool's real input schema.
 *
 * REA validates strictly (`additionalProperties: false` on nearly every tool)
 * and answers a mismatch with one generic line:
 *
 *     The arguments for MCP tool inspect_android_class do not match its
 *     advertised input schema.
 *
 * That tells an agent nothing about *which* field is wrong, so it retries the
 * same guess. The failure it cannot recover from is the nested object: the
 * compact schema profile advertised `source: {type: object}`, so the agent
 * tried `source: "jadx"` and was told "Expected object" with no hint that the
 * real shape is `{kind: "retained-evidence", evidence_id: "ev_..."}`.
 *
 * This produces the message REA should have produced:
 *
 *     source: expected an object, got string "jadx"
 *     source.kind: must be one of retained-evidence, inline, evidence-file
 *
 * It is deliberately **conservative**. A check it does not understand is
 * skipped rather than guessed at, because a validator that rejects a valid
 * call is worse than no validator: it turns a working tool into a broken one.
 * Only three things are ever reported — a missing required key, a value of the
 * wrong type, and an unknown key on a schema that forbids extras.
 */
object ReaSchema {

    /** One problem, phrased so an agent can act on it. */
    data class Problem(val path: String, val message: String) {
        override fun toString(): String = if (path.isEmpty()) message else "$path: $message"
    }

    /**
     * Validate [arguments] against [inputSchema].
     *
     * Returns an empty list when the call is acceptable — including when the
     * schema uses a construct this validator does not implement.
     */
    fun validate(inputSchema: JSONObject?, arguments: JSONObject): List<Problem> {
        if (inputSchema == null) return emptyList()
        val problems = ArrayList<Problem>()
        check(inputSchema, arguments, "", problems)
        return problems
    }

    // ------------------------------------------------------------ validation

    private fun check(schema: JSONObject, raw: Any?, path: String, out: MutableList<Problem>) {
        // Android's JSONObject.opt() returns JSONObject.NULL for an explicit
        // null rather than a Kotlin null. Treat both as "absent": a null in an
        // optional field is not a type error, and reporting it as one would
        // reject calls REA accepts.
        val value = if (raw == JSONObject.NULL) null else raw
        // A union: accept the first branch that validates cleanly. When none
        // does, report the discriminating values instead of a wall of noise —
        // that is the whole point for `source` and `view`.
        unionBranches(schema)?.let { branches ->
            if (branches.any { branch -> validateBranch(branch, value).isEmpty() }) return
            val kinds = branches.mapNotNull { discriminator(it) }.distinct()
            if (kinds.isNotEmpty()) {
                // `view` accepts 21 kinds. Printing all of them buries the
                // point; name the first few and say where the rest are.
                val shown = if (kinds.size <= 6) kinds else kinds.take(6)
                val suffix = if (kinds.size <= 6) {
                    ""
                } else {
                    " and " + (kinds.size - 6) + " more (call altaris_rea_schema)"
                }
                out.add(
                    Problem(
                        path,
                        "must be an object with a \"kind\" field — one of " +
                            shown.joinToString(", ") + suffix,
                    )
                )
            } else {
                out.add(Problem(path, "does not match any accepted shape"))
            }
            return
        }

        schema.opt("const")?.let { expected ->
            if (!sameValue(expected, value)) {
                out.add(Problem(path, "must be " + quote(expected) + ", got " + quote(value)))
            }
            return
        }

        val allowed = schema.optJSONArray("enum")
        if (allowed != null) {
            val values = (0 until allowed.length()).map { allowed.opt(it) }
            if (values.none { sameValue(it, value) }) {
                out.add(
                    Problem(
                        path,
                        "must be one of " + values.joinToString(", ") { quote(it) } +
                            ", got " + quote(value),
                    )
                )
            }
            return
        }

        when (schema.optString("type")) {
            "object" -> checkObject(schema, value, path, out)
            "array" -> checkArray(schema, value, path, out)
            "string" -> if (value != null && value !is String) {
                out.add(Problem(path, "expected a string, got " + describe(value)))
            }
            "integer" -> if (value != null && !isInteger(value)) {
                out.add(Problem(path, "expected an integer, got " + describe(value)))
            }
            "number" -> if (value != null && !isNumber(value)) {
                out.add(Problem(path, "expected a number, got " + describe(value)))
            }
            "boolean" -> if (value != null && value !is Boolean) {
                out.add(Problem(path, "expected true or false, got " + describe(value)))
            }
            "null" -> if (value != null) {
                out.add(Problem(path, "expected null, got " + describe(value)))
            }
            // No `type`, or a type this validator does not model: accept.
        }
    }

    private fun checkObject(
        schema: JSONObject,
        value: Any?,
        path: String,
        out: MutableList<Problem>,
    ) {
        if (value == null) return
        if (value !is JSONObject) {
            out.add(
                Problem(
                    path,
                    "expected an object, got " + describe(value) + shapeHint(schema),
                )
            )
            return
        }

        val required = schema.optJSONArray("required")
        if (required != null) {
            for (i in 0 until required.length()) {
                val key = required.optString(i)
                if (key.isBlank()) continue
                if (!value.has(key)) {
                    out.add(
                        Problem(
                            child(path, key),
                            "is required" + typeHint(schema.optJSONObject("properties")?.optJSONObject(key)),
                        )
                    )
                }
            }
        }

        val properties = schema.optJSONObject("properties")
        val keys = value.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val childSchema = properties?.optJSONObject(key)
            if (childSchema == null) {
                // Only complain when the schema actually forbids extras, and
                // only at the top level of a tool call: nested schemas in REA
                // are strict too, but an unknown nested key is more likely a
                // newer REA than a mistake worth blocking on.
                if (schema.optBoolean("additionalProperties", true).not() &&
                    schema.has("properties") &&
                    path.count { it == '.' } == 0
                ) {
                    out.add(Problem(child(path, key), "is not a recognised argument"))
                }
                continue
            }
            check(childSchema, value.opt(key), child(path, key), out)
        }
    }

    private fun checkArray(
        schema: JSONObject,
        value: Any?,
        path: String,
        out: MutableList<Problem>,
    ) {
        if (value == null) return
        if (value !is JSONArray) {
            out.add(Problem(path, "expected an array, got " + describe(value)))
            return
        }
        val itemSchema = schema.optJSONObject("items") ?: return
        for (i in 0 until value.length()) {
            check(itemSchema, value.opt(i), "$path[$i]", out)
        }
    }

    /** Validate against one branch without recording problems. */
    private fun validateBranch(branch: JSONObject, value: Any?): List<Problem> {
        val problems = ArrayList<Problem>()
        check(branch, value, "", problems)
        return problems
    }

    private fun unionBranches(schema: JSONObject): List<JSONObject>? {
        for (key in listOf("oneOf", "anyOf")) {
            val array = schema.optJSONArray(key) ?: continue
            val branches = (0 until array.length()).mapNotNull { array.optJSONObject(it) }
            if (branches.isNotEmpty()) return branches
        }
        return null
    }

    /**
     * The `kind` a branch requires, when it has one.
     *
     * This is what makes the error useful: `source` accepts three objects and
     * they differ only by `kind`.
     */
    private fun discriminator(branch: JSONObject): String? {
        branch.opt("const")?.let { if (it is String) return it }
        branch.optJSONObject("properties")?.optJSONObject("kind")?.let { kind ->
            kind.opt("const")?.let { if (it is String) return it }
            kind.optJSONArray("enum")?.let { values ->
                if (values.length() == 1) return values.optString(0)
            }
        }
        return null
    }

    /** ` (expected {kind, evidence_id})` — the shape, for an "expected object" error. */
    private fun shapeHint(schema: JSONObject): String {
        val properties = schema.optJSONObject("properties") ?: return ""
        val required = schema.optJSONArray("required")
        val names = ArrayList<String>()
        if (required != null) {
            for (i in 0 until required.length()) names.add(required.optString(i))
        }
        if (names.isEmpty()) {
            val keys = properties.keys()
            while (keys.hasNext() && names.size < 4) names.add(keys.next())
        }
        return if (names.isEmpty()) "" else " with {" + names.joinToString(", ") + "}"
    }

    private fun typeHint(schema: JSONObject?): String {
        if (schema == null) return ""
        unionBranches(schema)?.let { branches ->
            val kinds = branches.mapNotNull { discriminator(it) }.distinct()
            if (kinds.isNotEmpty()) return " — an object with kind " + kinds.joinToString(" or ")
        }
        return when (schema.optString("type")) {
            "object" -> " — an object" + shapeHint(schema)
            "array" -> " — an array"
            "string" -> " — a string"
            "integer" -> " — an integer"
            "number" -> " — a number"
            "boolean" -> " — true or false"
            "" -> ""
            else -> ""
        }
    }

    // --------------------------------------------------------------- example

    /**
     * A minimal valid call for [inputSchema].
     *
     * Built only from `required` (plus the first union branch), so it is a
     * skeleton to copy and fill, not a guess at meaningful values.
     */
    fun example(inputSchema: JSONObject?): JSONObject? {
        if (inputSchema == null) return null
        val value = sample(inputSchema, 0) as? JSONObject ?: return null
        return if (value.length() == 0) null else value
    }

    private fun sample(schema: JSONObject, depth: Int): Any? {
        if (depth > 6) return null
        unionBranches(schema)?.firstOrNull()?.let { return sample(it, depth + 1) }
        schema.opt("const")?.let { return it }
        schema.optJSONArray("enum")?.let { if (it.length() > 0) return it.opt(0) }
        return when (schema.optString("type")) {
            "object" -> {
                val out = JSONObject()
                val required = schema.optJSONArray("required") ?: return out
                val properties = schema.optJSONObject("properties")
                for (i in 0 until required.length()) {
                    val key = required.optString(i)
                    if (key.isBlank()) continue
                    out.put(key, sample(properties?.optJSONObject(key) ?: JSONObject(), depth + 1))
                }
                out
            }
            "array" -> JSONArray()
            "integer", "number" -> 0
            "boolean" -> false
            "null" -> JSONObject.NULL
            "string" -> placeholder(schema)
            else -> JSONObject.NULL
        }
    }

    /** A string sample that satisfies a `const`/pattern where one is declared. */
    private fun placeholder(schema: JSONObject): String {
        schema.opt("const")?.let { if (it is String) return it }
        val pattern = schema.optString("pattern")
        if (pattern.contains("ev_")) return "ev_" + "0".repeat(64)
        if (pattern.contains("^[a-f0-9]{64}")) return "0".repeat(64)
        return "<" + (schema.optString("description").takeIf { it.isNotBlank() }?.let {
            it.substringBefore(' ').lowercase().replace(Regex("[^a-z0-9_]"), "")
        }?.takeIf { it.isNotBlank() } ?: "value") + ">"
    }

    // --------------------------------------------------------------- helpers

    private fun child(path: String, key: String): String =
        if (path.isEmpty()) key else "$path.$key"

    private fun isInteger(value: Any): Boolean = when (value) {
        is Int, is Long, is Short, is Byte -> true
        is Double -> value == Math.floor(value) && !value.isInfinite()
        is Float -> value == Math.floor(value.toDouble()) && !value.isInfinite()
        else -> false
    }

    private fun isNumber(value: Any): Boolean =
        value is Number && !(value is Double && value.isNaN())

    private fun sameValue(expected: Any?, actual: Any?): Boolean = when {
        expected == null || expected == JSONObject.NULL ->
            actual == null || actual == JSONObject.NULL
        expected is Number && actual is Number ->
            expected.toDouble() == actual.toDouble()
        else -> expected == actual
    }

    /** Describe a value in the fewest words that identify the mistake. */
    private fun describe(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is String -> "string " + quote(value)
        is JSONObject -> "an object"
        is JSONArray -> "an array"
        is Boolean -> value.toString()
        is Number -> "number " + value
        else -> value.javaClass.simpleName
    }

    private fun quote(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is String -> "\"" + (if (value.length > 60) value.take(57) + "…" else value) + "\""
        else -> value.toString()
    }
}

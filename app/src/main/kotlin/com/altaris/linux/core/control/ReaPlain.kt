package com.altaris.linux.core.control

import com.altaris.linux.core.rea.ReaManager
import com.altaris.linux.core.rea.ReaSchema
import org.json.JSONArray
import org.json.JSONObject

/**
 * Plain-language wrappers over REA's real tools.
 *
 * ## Why this exists
 *
 * REA's tools are precise and correctly typed, and an AI cannot call them.
 * `instrument_with_frida` is the clearest case: it takes `mode` plus a *different
 * set of required fields per mode* — `pid` for attach, `program` for spawn — and
 * expresses that with `if`/`then`. A caller that guesses gets
 *
 *     The arguments for MCP tool instrument_with_frida do not match its
 *     advertised input schema.
 *
 * with no field named. The reported session shows nine of those in a row.
 *
 * The same shape recurs: `source_kind: Invalid option`, `Select one device`,
 * `attach requires pid`. Every one is the same failure — the tool wants a
 * discriminated union and the caller is guessing at it.
 *
 * ## What this does instead
 *
 * These are **intent** tools, not schema mirrors. Each one takes the few things
 * a caller actually knows — the package, the pid, the file path, the script
 * text — and *derives* the rest from the tool's real `inputSchema` at call time:
 *
 *   - the discriminator is chosen from what was supplied, not asked for;
 *   - every required field in that branch is filled or reported;
 *   - the resulting call is validated before it is sent.
 *
 * So `altaris_frida_attach { "package": "…" }` works whether REA calls the tool
 * `start_frida_session`, `instrument_with_frida`, or something else next
 * release: the name is resolved from the live catalog, and the arguments are
 * built from the live schema.
 *
 * ## Why not just fix the validator
 *
 * Because a better error message is still a failed call. The caller has a
 * package name and wants to attach; making it learn REA's union types first is
 * the work this removes.
 */
object ReaPlain {

    /** What a wrapper resolved and sent, for the reply. */
    data class Outcome(
        val tool: String,
        val arguments: JSONObject,
        val result: JSONObject?,
        val error: String = "",
    ) {
        val ok: Boolean get() = error.isBlank() && result != null
    }

    // ------------------------------------------------------------ resolving

    /**
     * The first REA tool whose name matches one of [candidates].
     *
     * Names are not stable across REA releases — `instrument_with_frida` did not
     * exist in the version the guides were written against — so a wrapper names
     * its candidates in order of preference and takes whichever the live catalog
     * actually has. That is what stops a wrapper from going stale.
     */
    fun resolve(vararg candidates: String): String? =
        candidates.firstOrNull { ReaManager.isReaTool(it) }

    /** True when REA publishes at least one of [candidates]. */
    fun available(vararg candidates: String): Boolean = resolve(*candidates) != null

    /**
     * Fill the required fields of a tool from what the caller supplied.
     *
     * The schema is the authority: this walks `required`, and for a tool whose
     * requirements depend on a discriminator it selects the branch that the
     * supplied values satisfy. Anything still missing is reported by name
     * rather than left for REA to reject generically.
     */
    fun build(tool: String, supplied: JSONObject): JSONObject {
        val definition = ReaManager.toolDefinition(tool) ?: return supplied
        val schema = definition.optJSONObject("inputSchema") ?: return supplied
        val out = JSONObject(supplied.toString())

        // Pick the discriminator branch that fits what we were given.
        //
        // `mode: "attach"` needs `pid`; `mode: "spawn"` needs `program`. The
        // caller supplies one of those, so the mode is implied — but if the
        // caller *did* pass a mode, that wins.
        chooseDiscriminator(schema, out)?.let { (key, value) ->
            if (out.optString(key).isBlank()) out.put(key, value)
        }

        // Select this host when the tool insists on a device.
        //
        // REA's Frida tools take `device_id` or `remote` and reject a call that
        // names neither — `list_frida_processes {}` answers "Select a device_id
        // or a remote endpoint", and `start_frida_session` says "Select one
        // device". Both are describing the same missing field, and neither
        // says which value to use. REA's own example for this host is
        // `device_id: "local"`, so that is filled in when the tool asks for a
        // device and the caller did not pick one.
        fillDeviceId(schema, out)

        // Add the required fields of the selected branch that have an obvious
        // source, and leave the rest for the caller.
        val branchRequired = requiredFor(schema, out)
        for (i in 0 until branchRequired.length()) {
            val key = branchRequired.optString(i)
            if (key.isBlank() || out.has(key)) continue
            // A field the caller did not supply and that cannot be derived is
            // left absent on purpose: `problems()` then names it.
        }
        return out
    }

    /**
     * Put `device_id: "local"` on a tool that offers an either/or device choice.
     *
     * The trigger is the *shape*, not the word "device": the tool must declare
     * both `device_id` and `remote`, which is how REA spells "pick one of
     * these" for its Frida tools. Requiring `device_id` in `required` would not
     * work — `list_frida_processes` requires neither, and still rejects a call
     * that names neither.
     *
     * A tool without both properties is left alone: adding a field it does not
     * declare would be rejected under `additionalProperties: false`, and
     * inventing an argument is worse than letting the error name it.
     */
    private fun fillDeviceId(schema: JSONObject, values: JSONObject) {
        if (values.has("device_id") || values.has("remote")) return
        val props = schema.optJSONObject("properties") ?: return
        if (!props.has("device_id") || !props.has("remote")) return
        values.put("device_id", "local")
    }

    /** The `required` list that applies to [values], following `if`/`then`. */
    fun requiredFor(schema: JSONObject, values: JSONObject): JSONArray {
        val out = JSONArray()
        schema.optJSONArray("required")?.let { base ->
            for (i in 0 until base.length()) out.put(base.optString(i))
        }
        // Union branches: use the one whose discriminator matches.
        for (key in listOf("oneOf", "anyOf")) {
            val branches = schema.optJSONArray(key) ?: continue
            val match = (0 until branches.length())
                .mapNotNull { branches.optJSONObject(it) }
                .firstOrNull { branch ->
                    val disc = discriminator(branch)
                    disc == null || values.optString(disc.first) == disc.second
                }
            match?.optJSONArray("required")?.let { r ->
                for (i in 0 until r.length()) out.put(r.optString(i))
            }
            break
        }
        schema.optJSONObject("if")?.let { cond ->
            val branch = if (ReaSchema.validate(cond, values).isEmpty()) {
                schema.optJSONObject("then")
            } else {
                schema.optJSONObject("else")
            }
            branch?.optJSONArray("required")?.let { r ->
                for (i in 0 until r.length()) out.put(r.optString(i))
            }
        }
        return out
    }

    /** The fields a call is still missing, by name. */
    fun problems(tool: String, arguments: JSONObject): List<String> {
        val definition = ReaManager.toolDefinition(tool) ?: return emptyList()
        val schema = definition.optJSONObject("inputSchema") ?: return emptyList()
        val required = requiredFor(schema, arguments)
        val missing = ArrayList<String>()
        for (i in 0 until required.length()) {
            val key = required.optString(i)
            if (key.isNotBlank() && !arguments.has(key)) missing.add(key)
        }
        // And anything the validator objects to.
        val reported = ReaSchema.validate(schema, arguments).map { it.toString() }
        return (missing + reported).distinct()
    }

    /**
     * Choose a discriminator value from what the caller supplied.
     *
     * Returns `key to value` for the field whose union the supplied values
     * already satisfy — that is what makes `{ "pid": 123 }` imply
     * `mode: "attach"` without the caller having to know the vocabulary.
     */
    private fun chooseDiscriminator(schema: JSONObject, values: JSONObject): Pair<String, String>? {
        val branches = unionBranchesOf(schema) ?: return null
        // Prefer the branch that validates with what we have, once the
        // discriminator is filled in.
        for (branch in branches) {
            val disc = discriminator(branch) ?: continue
            if (values.has(disc.first)) continue
            val probe = JSONObject(values.toString()).put(disc.first, disc.second)
            if (ReaSchema.validate(branch, probe).isEmpty()) return disc
        }
        // Nothing validates outright: take the branch whose other required
        // fields the caller already has, which is the closest fit.
        var best: Pair<String, String>? = null
        var bestScore = -1
        for (branch in branches) {
            val disc = discriminator(branch) ?: continue
            if (values.has(disc.first)) continue
            val required = branch.optJSONArray("required") ?: continue
            var have = 0
            var need = 0
            for (i in 0 until required.length()) {
                val key = required.optString(i)
                if (key.isBlank() || key == disc.first) continue
                need += 1
                if (values.has(key)) have += 1
            }
            if (need == 0) continue
            if (have > bestScore) {
                bestScore = have
                best = disc
            }
        }
        return best
    }

    private fun unionBranchesOf(schema: JSONObject): List<JSONObject>? {
        for (key in listOf("oneOf", "anyOf")) {
            val array = schema.optJSONArray(key) ?: continue
            val branches = (0 until array.length()).mapNotNull { array.optJSONObject(it) }
            if (branches.isNotEmpty()) return branches
        }
        // `if`/`then` is the other spelling: the branches are the `then` and
        // `else` bodies, discriminated by whatever `if` constrains.
        val cond = schema.optJSONObject("if") ?: return null
        val key = cond.optJSONObject("properties")?.keys()?.let { k ->
            if (k.hasNext()) k.next() else null
        } ?: return null
        val branches = ArrayList<JSONObject>()
        schema.optJSONObject("then")?.let { branches.add(withDiscriminator(it, cond, key)) }
        schema.optJSONObject("else")?.let { branches.add(withDiscriminator(it, cond, key)) }
        return branches.takeIf { it.isNotEmpty() }
    }

    /**
     * Attach the discriminator a `then`/`else` body implies to its schema.
     *
     * The `then` body carries only `required`; the value that selects it lives
     * in the sibling `if`. Copying it onto the branch is what lets the rest of
     * the builder treat `if`/`then` and `oneOf` the same way.
     */
    private fun withDiscriminator(
        branch: JSONObject,
        condition: JSONObject,
        key: String,
    ): JSONObject {
        val copy = JSONObject(branch.toString())
        val props = copy.optJSONObject("properties")
            ?: JSONObject().also { copy.put("properties", it) }
        val value = condition.optJSONObject("properties")
            ?.optJSONObject(key)
            ?.opt("const")
        if (value is String) {
            props.put(key, JSONObject().put("const", value))
        }
        return copy
    }

    /** The `kind`/`mode`/`source_kind` a branch requires, and its value. */
    private fun discriminator(branch: JSONObject): Pair<String, String>? {
        val props = branch.optJSONObject("properties") ?: return null
        val keys = props.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val spec = props.optJSONObject(key) ?: continue
            spec.opt("const")?.let { if (it is String) return key to it }
            spec.optJSONArray("enum")?.let { values ->
                if (values.length() == 1) return key to values.optString(0)
            }
        }
        return null
    }
}

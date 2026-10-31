package com.altaris.linux.core.quinn

import android.content.Context
import java.io.File

/**
 * Quinn's own OpenAI-compatible endpoint.
 *
 * REA is an MCP *server*: it exposes analysis tools and has no model of its
 * own. Something has to decide which tool to call and what the answer means.
 * The user chose to give Quinn its own key rather than delegate to an installed
 * agent, so this holds the base URL, key and model that Quinn uses when it
 * drives REA.
 *
 * The key is stored in the app's private files (not SharedPreferences) so a
 * backup rule cannot silently copy it off-device.
 */
object QuinnModelConfig {

    private const val FILE = "quinn-model.json"

    data class Config(
        /** OpenAI-compatible base URL, e.g. https://api.openai.com/v1. */
        val baseUrl: String = "",
        val apiKey: String = "",
        val model: String = "",
        /** Models fetched from [baseUrl]/models, provider order preserved. */
        val models: List<String> = emptyList(),
    ) {
        val configured: Boolean get() = baseUrl.isNotBlank() && apiKey.isNotBlank()

        /** `<base>/chat/completions`, tolerating a trailing slash. */
        val chatUrl: String
            get() = baseUrl.trimEnd('/') + "/chat/completions"

        val modelsUrl: String
            get() = baseUrl.trimEnd('/') + "/models"
    }

    fun load(context: Context): Config {
        val file = File(context.filesDir, FILE)
        if (!file.isFile) return Config()
        return runCatching {
            val json = org.json.JSONObject(file.readText())
            val models = ArrayList<String>()
            json.optJSONArray("models")?.let { array ->
                for (i in 0 until array.length()) {
                    val value = array.optString(i)
                    if (value.isNotBlank()) models.add(value)
                }
            }
            Config(
                baseUrl = json.optString("baseUrl"),
                apiKey = json.optString("apiKey"),
                model = json.optString("model"),
                models = models,
            )
        }.getOrElse { Config() }
    }

    fun save(context: Context, config: Config) {
        val json = org.json.JSONObject()
            .put("baseUrl", config.baseUrl.trim())
            .put("apiKey", config.apiKey.trim())
            .put("model", config.model.trim())
            .put("models", org.json.JSONArray(config.models))
        val file = File(context.filesDir, FILE)
        file.writeText(json.toString())
        // Best effort: the key is the only secret in the app.
        runCatching { file.setReadable(false, false); file.setReadable(true, true) }
    }

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE).delete() }
    }

    /**
     * Normalise a user-typed base URL.
     *
     * Accepts "api.openai.com", "api.openai.com/v1" and
     * "https://api.openai.com/v1/" — the three things people actually type —
     * and refuses anything that is not http(s).
     */
    fun normalizeBaseUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.contains("://") -> return null
            else -> "https://$trimmed"
        }
        val uri = runCatching { java.net.URI(withScheme) }.getOrNull() ?: return null
        if (uri.scheme !in listOf("http", "https")) return null
        if (uri.host.isNullOrBlank()) return null
        return withScheme.trimEnd('/')
    }

    /**
     * Parse an OpenAI `/v1/models` reply into model ids.
     *
     * **Order is preserved.** Sorting alphabetically used to push provider
     * families like `nvidia/nemotron-3-super-120b-a12b` to position 45 of 85,
     * so the UI (which showed the first N) never displayed them. Provider
     * order is also the order a human expects: newest/most relevant first.
     */
    fun parseModels(body: String): List<String> {
        val json = runCatching { org.json.JSONObject(body) }.getOrNull() ?: return emptyList()
        val data = json.optJSONArray("data") ?: return emptyList()
        val out = LinkedHashSet<String>()
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val id = item.optString("id")
            if (id.isNotBlank()) out.add(id)
        }
        return out.toList()
    }

    /**
     * Models matching [query], case-insensitively.
     *
     * The model list can be 85+ entries on a hosted provider, so search is the
     * primary way to reach a family rather than scrolling a truncated row.
     */
    fun search(models: List<String>, query: String): List<String> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return models
        // Matches on the full id first, then on any segment of it, so "super"
        // finds "nvidia/nemotron-3-super-120b-a12b".
        val direct = models.filter { it.lowercase().contains(q) }
        return direct
    }

    /** Display label: the family part of a provider-prefixed model id. */
    fun shortName(model: String): String = model.substringAfterLast('/')
}

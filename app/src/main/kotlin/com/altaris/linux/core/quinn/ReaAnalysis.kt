package com.altaris.linux.core.quinn

import android.content.Context
import android.util.Log
import com.altaris.linux.core.rea.ReaDistro
import com.altaris.linux.core.rea.ReaManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Drives REA from Quinn using the user's own OpenAI-compatible endpoint.
 *
 * REA is an MCP *server*: it exposes analysis tools and contains no model. The
 * recording this tab is modelled on worked because an agent (Kimi K3) decided
 * which REA tool to call. Quinn takes that role here: it asks the configured
 * model what to do, executes the REA tool the model chose, feeds the result
 * back, and repeats until the model stops asking for tools.
 *
 * That loop is what makes the REA tab useful without a second app: the user
 * supplies one key, and the analysis runs inside the hidden REA distro.
 */
object ReaAnalysis {

    private const val TAG = "QuinnRea"
    private const val MAX_STEPS = 8

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        // Analysis tools are slow (Ghidra especially); the model call itself is
        // the short leg, the tool call below gets its own longer timeout.
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** One analysis run: what was asked, and every step it took. */
    data class Run(
        val id: Long = System.nanoTime(),
        val prompt: String,
        val target: String = "",
        val steps: List<Step> = emptyList(),
        val running: Boolean = false,
        val answer: String = "",
        val error: String = "",
        val model: String = "",
    )

    /** One tool call the model asked for, and what it returned. */
    data class Step(
        val tool: String,
        val arguments: String,
        val result: String = "",
        val error: String = "",
        val ms: Long = 0L,
    )

    private val _runs = MutableStateFlow<List<Run>>(emptyList())
    val runs: StateFlow<List<Run>> = _runs.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /**
     * Run one analysis.
     *
     * @param prompt what the user wants to know
     * @param target absolute path inside the REA distro (optional)
     */
    fun analyze(context: Context, prompt: String, target: String = "") {
        if (prompt.isBlank() || _busy.value) return
        val app = context.applicationContext
        val config = QuinnModelConfig.load(app)
        if (!config.configured) {
            push(
                Run(
                    prompt = prompt,
                    target = target,
                    error = "Add a model and API key in the REA tab first.",
                    model = config.model,
                )
            )
            return
        }
        val distroId = ReaDistro.distroId(app)
        if (distroId == null) {
            push(
                Run(
                    prompt = prompt,
                    target = target,
                    error = "REA is not installed. Install it from Tools > Reverse engineering.",
                    model = config.model,
                )
            )
            return
        }
        val run = Run(prompt = prompt, target = target, running = true, model = config.model)
        push(run)
        _busy.value = true
        scope.launch {
            try {
                val answer = withContext(Dispatchers.IO) { loop(app, run, distroId, config) }
                update(run.id) { it.copy(answer = answer, running = false) }
            } catch (t: Throwable) {
                Log.w(TAG, "analysis failed", t)
                update(run.id) { it.copy(error = t.message ?: "Analysis failed", running = false) }
            } finally {
                _busy.value = false
            }
        }
    }

    /**
     * The tool-calling loop.
     *
     * Bounded by [MAX_STEPS] so a model that keeps asking for tools cannot run
     * forever (or spend the user's quota without limit).
     */
    private suspend fun loop(
        context: Context,
        run: Run,
        distroId: String,
        config: QuinnModelConfig.Config,
    ): String {
        // REA has to be running for tool calls to resolve; starting it is
        // idempotent and cheap when it already is.
        runCatching { ReaManager.start(context, distroId) }
        val tools = ReaManager.cachedTools() ?: ReaManager.probeTools(context, distroId)
        val toolSpec = openAiTools(tools)
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt()))
            .put(JSONObject().put("role", "user").put("content", userPrompt(run)))

        for (step in 0 until MAX_STEPS) {
            val reply = chat(config, messages, toolSpec)
            val message = reply.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?: throw IllegalStateException("The model returned no message")
            val toolCalls = message.optJSONArray("tool_calls")
            if (toolCalls == null || toolCalls.length() == 0) {
                return message.optString("content").ifBlank { "(no answer)" }
            }
            // Feed the assistant turn back so the next request has context.
            messages.put(message)
            for (i in 0 until toolCalls.length()) {
                val call = toolCalls.optJSONObject(i) ?: continue
                val function = call.optJSONObject("function") ?: continue
                val name = function.optString("name")
                val rawArgs = function.optString("arguments")
                val started = System.currentTimeMillis()
                val (result, error) = callReaTool(context, distroId, name, rawArgs)
                val elapsed = System.currentTimeMillis() - started
                appendStep(
                    run.id,
                    Step(
                        tool = name,
                        arguments = rawArgs,
                        result = result,
                        error = error,
                        ms = elapsed,
                    )
                )
                messages.put(
                    JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", call.optString("id"))
                        .put("name", name)
                        .put("content", if (error.isBlank()) result else "ERROR: $error")
                )
            }
        }
        return "Stopped after $MAX_STEPS tool calls without a final answer."
    }

    /** Execute one REA tool; never throws, so one bad call cannot kill the run. */
    private suspend fun callReaTool(
        context: Context,
        distroId: String,
        name: String,
        rawArgs: String,
    ): Pair<String, String> {
        if (name.isBlank()) return "" to "the model asked for a tool with no name"
        val args = runCatching {
            if (rawArgs.isBlank()) JSONObject() else JSONObject(rawArgs)
        }.getOrElse { return "" to "invalid tool arguments: ${it.message}" }
        return try {
            val result = ReaManager.call(name, args)
            val text = result.toString()
            // Keep replies bounded: a Ghidra dump can be megabytes and would
            // blow the model's context (and the user's budget).
            text.take(MAX_TOOL_CHARS) to ""
        } catch (t: Throwable) {
            "" to (t.message ?: "tool call failed")
        }
    }

    /** One chat completion; returns the raw JSON reply. */
    private fun chat(
        config: QuinnModelConfig.Config,
        messages: JSONArray,
        tools: JSONArray,
    ): JSONObject {
        val body = JSONObject()
            .put("model", config.model)
            .put("messages", messages)
            .put("temperature", 0.2)
        if (tools.length() > 0) {
            body.put("tools", tools).put("tool_choice", "auto")
        }
        val request = Request.Builder()
            .url(config.chatUrl)
            .header("Authorization", "Bearer " + config.apiKey)
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                // Surface the provider's own message; it usually names the
                // model or the key problem exactly.
                val detail = runCatching {
                    JSONObject(text).optJSONObject("error")?.optString("message")
                }.getOrNull()
                throw IllegalStateException(
                    detail?.takeIf { it.isNotBlank() }
                        ?: "HTTP ${response.code} from the model endpoint"
                )
            }
            return JSONObject(text)
        }
    }

    /** Convert REA's MCP tool catalog into OpenAI function definitions. */
    private fun openAiTools(tools: JSONArray?): JSONArray {
        val out = JSONArray()
        if (tools == null) return out
        for (i in 0 until tools.length()) {
            val tool = tools.optJSONObject(i) ?: continue
            val name = tool.optString("name")
            if (name.isBlank()) continue
            val schema = tool.optJSONObject("inputSchema") ?: JSONObject()
                .put("type", "object")
                .put("properties", JSONObject())
            out.put(
                JSONObject().put("type", "function").put(
                    "function",
                    JSONObject()
                        .put("name", name)
                        .put("description", tool.optString("description").take(1024))
                        .put("parameters", schema)
                )
            )
        }
        return out
    }

    private fun systemPrompt(): String = """
        You are the analysis engine inside Altaris. You answer questions about
        software by calling the REA tools provided.
        Rules:
        - Prefer inspecting the actual target over guessing; call a tool first.
        - Report what the evidence shows, and say when something is unknown.
        - Keep the final answer short and concrete: what you found, and where.
        - Never invent file contents, addresses or symbol names.
    """.trimIndent()

    private fun userPrompt(run: Run): String =
        if (run.target.isBlank()) run.prompt
        else "Target: ${run.target}\n\n${run.prompt}"

    private fun push(run: Run) {
        _runs.value = (listOf(run) + _runs.value).take(20)
    }

    private fun update(id: Long, block: (Run) -> Run) {
        _runs.value = _runs.value.map { if (it.id == id) block(it) else it }
    }

    private fun appendStep(id: Long, step: Step) {
        update(id) { it.copy(steps = it.steps + step) }
    }

    fun clear() {
        _runs.value = emptyList()
    }

    private const val MAX_TOOL_CHARS = 24_000
}

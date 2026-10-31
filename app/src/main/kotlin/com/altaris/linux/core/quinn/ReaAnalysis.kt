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
import java.util.concurrent.atomic.AtomicLong

/**
 * Drives REA from Quinn using the user's own OpenAI-compatible endpoint.
 *
 * REA is an MCP *server*: it exposes analysis tools and contains no model. The
 * recording this tab is modelled on worked because an agent decided which REA
 * tool to call. Quinn takes that role: it asks the configured model what to do,
 * executes the REA tool the model chose, feeds the result back, and repeats
 * until the model stops asking for tools.
 *
 * The model is told what REA is and which tools exist on every run, so nothing
 * about REA has to be explained by the user.
 */
object ReaAnalysis {

    private const val TAG = "QuinnRea"
    private const val MAX_STEPS = 12

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        // Analysis tools are slow (Ghidra especially); the model call itself is
        // the short leg, the tool call below gets its own longer timeout.
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** One tool call the model asked for, and what it returned. */
    data class Step(
        val tool: String,
        val arguments: String,
        val result: String = "",
        val error: String = "",
        val ms: Long = 0L,
        /** True while the call is still running (drives the live pulse). */
        val running: Boolean = false,
    )

    /** One analysis run: what was asked, and every step it took. */
    data class Run(
        val id: Long = System.nanoTime(),
        val prompt: String,
        val files: List<String> = emptyList(),
        val steps: List<Step> = emptyList(),
        val running: Boolean = false,
        val answer: String = "",
        val error: String = "",
        val model: String = "",
        val startedAt: Long = System.currentTimeMillis(),
        /** Bytes of tool output received so far — the dashboard's byte counter. */
        val bytes: Long = 0L,
        /** Tool names the model has used, in order, for the activity strip. */
        val activity: List<String> = emptyList(),
    ) {
        val elapsedMs: Long get() = System.currentTimeMillis() - startedAt
    }

    /** A single live event, for the dashboard's ticker. */
    data class Event(
        val text: String,
        val kind: Kind = Kind.INFO,
        val at: Long = System.currentTimeMillis(),
    ) {
        enum class Kind { INFO, TOOL, RESULT, ERROR }
    }

    private val _runs = MutableStateFlow<List<Run>>(emptyList())
    val runs: StateFlow<List<Run>> = _runs.asStateFlow()

    private val _events = MutableStateFlow<List<Event>>(emptyList())
    /** Newest first, capped. The REA dashboard renders this as a live ticker. */
    val events: StateFlow<List<Event>> = _events.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Monotonic counters the dashboard plots; never reset by a new run. */
    private val totalToolCalls = AtomicLong(0L)
    private val totalBytes = AtomicLong(0L)

    private val _totals = MutableStateFlow(Totals())
    data class Totals(
        val toolCalls: Long = 0L,
        val bytes: Long = 0L,
        val runs: Int = 0,
    )
    val totals: StateFlow<Totals> = _totals.asStateFlow()

    /**
     * Run one analysis.
     *
     * @param prompt what the user wants to know
     * @param files absolute paths inside the REA distro
     */
    fun analyze(context: Context, prompt: String, files: List<String> = emptyList()) {
        if (prompt.isBlank() || _busy.value) return
        val app = context.applicationContext
        val config = QuinnModelConfig.load(app)
        if (!config.configured) {
            push(
                Run(
                    prompt = prompt,
                    files = files,
                    error = "Add a model and API key in the REA panel first.",
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
                    files = files,
                    error = "REA is not installed. Install it from Tools > Reverse engineering.",
                    model = config.model,
                )
            )
            return
        }
        val run = Run(prompt = prompt, files = files, running = true, model = config.model)
        push(run)
        _busy.value = true
        log("analysis started · " + config.model, Event.Kind.INFO)
        scope.launch {
            try {
                val answer = withContext(Dispatchers.IO) { loop(app, run, distroId, config) }
                update(run.id) { it.copy(answer = answer, running = false) }
                log("analysis complete", Event.Kind.RESULT)
            } catch (t: Throwable) {
                Log.w(TAG, "analysis failed", t)
                update(run.id) { it.copy(error = t.message ?: "Analysis failed", running = false) }
                log(t.message ?: "analysis failed", Event.Kind.ERROR)
            } finally {
                _busy.value = false
                _totals.value = _totals.value.copy(
                    toolCalls = totalToolCalls.get(),
                    bytes = totalBytes.get(),
                    runs = _totals.value.runs + 1,
                )
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
        log("${tools.length()} REA tools available", Event.Kind.INFO)
        val toolSpec = openAiTools(tools)
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt(tools)))
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
                val stepIndex = appendStep(
                    run.id,
                    Step(tool = name, arguments = rawArgs, running = true),
                )
                log(name, Event.Kind.TOOL)
                val started = System.currentTimeMillis()
                val (result, error) = callReaTool(context, distroId, name, rawArgs)
                val elapsed = System.currentTimeMillis() - started
                val bytes = result.length.toLong()
                totalToolCalls.incrementAndGet()
                totalBytes.addAndGet(bytes)
                finishStep(
                    run.id,
                    stepIndex,
                    Step(
                        tool = name,
                        arguments = rawArgs,
                        result = result,
                        error = error,
                        ms = elapsed,
                    ),
                )
                if (error.isBlank()) {
                    log("$name → ${formatBytes(bytes)} in ${elapsed}ms", Event.Kind.RESULT)
                } else {
                    log("$name failed: $error", Event.Kind.ERROR)
                }
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

    /**
     * The system prompt, generated from the live tool catalog.
     *
     * The model is told what REA is and exactly which tools it can call, so the
     * user never has to explain the environment or name a tool.
     */
    private fun systemPrompt(tools: JSONArray): String {
        val families = LinkedHashSet<String>()
        for (i in 0 until tools.length()) {
            val name = tools.optJSONObject(i)?.optString("name").orEmpty()
            if (name.isBlank()) continue
            families.add(name.substringBefore('-').substringBefore('_'))
        }
        val catalog = buildString {
            append("Available tool families: ")
            append(families.joinToString(", ").take(600))
            append(". ")
        }
        return """
            You are the analysis engine inside Altaris, running inside a private
            Linux environment that already has reverse-engineering tools
            installed (JADX, Apktool, aapt, radare2/rizin, binwalk, objdump,
            gdb, sqlite3, Python and more).

            You are connected to REA (Reverse Engineering Agents), an MCP server
            that exposes those tools. $catalog

            How to work:
            - Call a REA tool to inspect the real artifact before answering.
            - For an Android package start with the JADX or Apktool tools; for a
              native binary use the ELF/objdump/rizin tools; for firmware or an
              archive use the artifact/entropy tools; for a database use the
              SQLite tools.
            - If a tool fails, read the error and try a different tool rather
              than repeating the same call.
            - Report what the evidence shows. Never invent file contents,
              addresses, symbol names or offsets.
            - Finish with a short, concrete answer: what you found, and where.
        """.trimIndent()
    }

    private fun userPrompt(run: Run): String = buildString {
        if (run.files.isNotEmpty()) {
            append("Files inside the analysis environment:\n")
            for (file in run.files) append("- ").append(file).append('\n')
            append('\n')
        }
        append(run.prompt)
    }

    private fun push(run: Run) {
        _runs.value = (listOf(run) + _runs.value).take(20)
    }

    private fun update(id: Long, block: (Run) -> Run) {
        _runs.value = _runs.value.map { if (it.id == id) block(it) else it }
    }

    /** Append a step and return its index, so the caller can update it in place. */
    private fun appendStep(id: Long, step: Step): Int {
        var index = -1
        update(id) {
            index = it.steps.size
            it.copy(steps = it.steps + step, activity = it.activity + step.tool)
        }
        return index
    }

    private fun finishStep(id: Long, index: Int, step: Step) {
        update(id) {
            if (index < 0 || index >= it.steps.size) return@update it
            val steps = it.steps.toMutableList()
            steps[index] = step
            it.copy(steps = steps, bytes = it.bytes + step.result.length)
        }
    }

    private fun log(text: String, kind: Event.Kind) {
        _events.value = (listOf(Event(text, kind)) + _events.value).take(80)
    }

    fun clear() {
        _runs.value = emptyList()
        _events.value = emptyList()
    }

    /** Human byte count for the dashboard. */
    fun formatBytes(bytes: Long): String = when {
        bytes <= 0L -> "0 B"
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> String.format("%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024L * 1024L -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }

    private const val MAX_TOOL_CHARS = 24_000
}

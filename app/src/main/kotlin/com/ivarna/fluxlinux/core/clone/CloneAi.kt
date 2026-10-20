package com.ivarna.fluxlinux.core.clone

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * ShadowAuto-style AI control for the clone display, running in the app.
 *
 * <p>Each step: take a fresh screenshot of the shell-owned virtual display,
 * run on-device OCR (ML Kit), ask an OpenAI-compatible chat model for exactly
 * one JSON action, then inject that action into the clone display through the
 * Shizuku input bridge. The model never touches the phone screen.
 *
 * <p>No MCP server, no JSON-RPC daemon and no external bridge are involved:
 * the agent is part of Bliss and talks to [CloneEngine]/[com.ivarna.fluxlinux.clone.ShellVd]
 * directly.
 */
object CloneAi {

    private const val TAG = "BlissCloneAI"
    private const val PREFS = "bliss_clone_ai"
    private const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
    private const val DEFAULT_MODEL = "gpt-4o-mini"
    private const val MAX_EVENTS = 160
    private const val MAX_HISTORY = 8

    data class Config(
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        val vision: Boolean,
        val maxSteps: Int,
    ) {
        // Cloud endpoints need a key; plain-HTTP endpoints are usually local
        // servers (llama.cpp, vLLM, Ollama proxies) that accept no key.
        val usable: Boolean
            get() = baseUrl.isNotBlank() && model.isNotBlank() &&
                (apiKey.isNotBlank() || baseUrl.trim().startsWith("http://"))
    }

    /** One OCR line in screenshot pixels. */
    data class ScreenLine(val text: String, val x: Int, val y: Int, val w: Int, val h: Int) {
        val cx: Int get() = x + w / 2
        val cy: Int get() = y + h / 2
    }

    data class Observation(
        val jpeg: ByteArray,
        val width: Int,
        val height: Int,
        val lines: List<ScreenLine>,
    )

    // ------------------------------------------------------------------ state

    val running = MutableStateFlow(false)
    val step = MutableStateFlow(0)
    val maxSteps = MutableStateFlow(30)
    val status = MutableStateFlow<String?>(null)
    val events = MutableStateFlow<List<String>>(emptyList())
    val goal = MutableStateFlow("")
    val activePackage = MutableStateFlow<String?>(null)
    val lastAction = MutableStateFlow("")
    val configured = MutableStateFlow(false)
    val ocrLineCount = MutableStateFlow(0)
    val configEpoch = MutableStateFlow(0)

    private var prefs: SharedPreferences? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var activeCall: okhttp3.Call? = null

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(150, TimeUnit.SECONDS)
            .build()
    }

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    // --------------------------------------------------------------- lifecycle

    fun init(app: Application) {
        val p = prefs ?: app.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        val cfg = config()
        configured.value = cfg.usable
        maxSteps.value = cfg.maxSteps
    }

    fun config(): Config {
        val p = prefs ?: return Config(DEFAULT_BASE_URL, "", DEFAULT_MODEL, false, 30)
        return Config(
            baseUrl = p.getString("base_url", DEFAULT_BASE_URL).orEmpty(),
            apiKey = p.getString("api_key", "").orEmpty(),
            model = p.getString("model", DEFAULT_MODEL).orEmpty(),
            vision = p.getBoolean("vision", false),
            maxSteps = p.getInt("max_steps", 30).coerceIn(5, 60),
        )
    }

    fun saveConfig(baseUrl: String, apiKey: String, model: String, vision: Boolean, maxSteps: Int) {
        val p = prefs ?: return
        val steps = maxSteps.coerceIn(5, 60)
        p.edit()
            .putString("base_url", baseUrl.trim())
            .putString("api_key", apiKey.trim())
            .putString("model", model.trim())
            .putBoolean("vision", vision)
            .putInt("max_steps", steps)
            .apply()
        this.maxSteps.value = steps
        configured.value = config().usable
        configEpoch.value++
        log("AI settings saved (model=${model.trim().ifBlank { "-" }}, vision=$vision, steps=$steps)")
    }

    /** Starts one AI task. No-op when a task is already running. */
    fun start(packageName: String, goalText: String) {
        if (running.value) {
            status.value = "An AI task is already running"
            return
        }
        val cfg = config()
        if (!cfg.usable) {
            status.value = "Configure the AI endpoint and model first"
            return
        }
        val task = goalText.trim()
        if (task.isEmpty()) {
            status.value = "Describe what the AI should do"
            return
        }
        if (packageName.isBlank()) {
            status.value = "No clone app selected"
            return
        }
        running.value = true
        step.value = 0
        events.value = emptyList()
        status.value = "Starting…"
        lastAction.value = ""
        ocrLineCount.value = 0
        goal.value = task
        activePackage.value = packageName
        maxSteps.value = cfg.maxSteps
        job = scope.launch {
            try {
                runLoop(cfg, packageName, task)
            } catch (cancelled: CancellationException) {
                status.value = "Stopped"
                throw cancelled
            } catch (t: Throwable) {
                status.value = "AI failed: ${describe(t)}"
                log("AI failed: ${describe(t)}")
            } finally {
                running.value = false
            }
        }
    }

    fun stop() {
        if (!running.value) return
        running.value = false
        try {
            activeCall?.cancel()
        } catch (ignored: Throwable) {
        }
        job?.cancel()
        job = null
        status.value = "Stopped"
        log("AI stopped by user")
    }

    fun clearEvents() {
        events.value = emptyList()
    }

    // -------------------------------------------------------------------- loop

    private suspend fun runLoop(cfg: Config, packageName: String, goalText: String) {
        val history = ArrayDeque<String>()
        var errors = 0
        var relaunches = 0

        if (CloneEngine.running.value != packageName || !CloneEngine.displayAlive()) {
            status.value = "Launching the app…"
            val err = CloneEngine.launchNowBlocking(packageName)
            if (err != null) {
                status.value = "Could not launch $packageName: $err"
                log("Launch failed: $err")
                return
            }
            delay(2_000)
        }

        log("AI started · $packageName · goal: $goalText")
        while (running.value && step.value < cfg.maxSteps) {
            if (CloneEngine.running.value != packageName) {
                relaunches++
                if (relaunches > 2) {
                    status.value = "The clone app is no longer running"
                    log("The clone app is no longer running")
                    return
                }
                status.value = "Re-launching the app…"
                val err = CloneEngine.launchNowBlocking(packageName)
                if (err != null) {
                    status.value = "Could not relaunch: $err"
                    return
                }
                delay(2_000)
            }

            step.value += 1
            status.value = "Step ${step.value}/${cfg.maxSteps} · observing"
            val observation = observe()
            if (!running.value) return
            if (observation == null) {
                errors++
                status.value = "Step ${step.value}/${cfg.maxSteps} · no picture"
                log("No frame from the clone display (attempt $errors)")
                // A dead/expired shell display is the usual cause: rebuild it
                // and relaunch once before giving up.
                if (errors >= 2 && relaunches < 2) {
                    relaunches++
                    log("Rebuilding the clone display…")
                    val healErr = CloneEngine.launchNowBlocking(packageName)
                    if (healErr == null) {
                        errors = 0
                        delay(1_800)
                        continue
                    }
                    log("Display rebuild failed: $healErr")
                }
                delay(1_200)
                if (errors >= 4) {
                    status.value = "No picture from the clone display — open Display and press Run app"
                    return
                }
                continue
            }
            ocrLineCount.value = observation.lines.size
            log("Observed ${observation.width}x${observation.height}, ${observation.lines.size} text line(s)")

            status.value = "Step ${step.value}/${cfg.maxSteps} · thinking"
            val answer = try {
                requestAction(cfg, packageName, goalText, observation, history.toList())
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                if (!running.value) return
                errors++
                status.value = "Step ${step.value}/${cfg.maxSteps} · AI error"
                log("AI request failed: ${describe(t)}")
                delay(1_200)
                if (errors >= 3) {
                    status.value = "AI request failed: ${describe(t)}"
                    return
                }
                continue
            }
            if (!running.value) return

            val action = AiProtocol.parseAction(answer)
            if (action == null) {
                errors++
                log("Unparseable model answer: ${answer.take(180).replace('\n', ' ')}")
                history.addLast("invalid answer -> reply with one JSON action only")
                trimHistory(history)
                delay(500)
                if (errors >= 4) {
                    status.value = "The model is not returning valid actions — try another model"
                    return
                }
                continue
            }
            errors = 0

            val summary = action.describe()
            lastAction.value = summary
            log("Action: $summary${action.reason?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""}")

            if (action.type == "finish") {
                val reason = action.reason.orEmpty().ifBlank { action.text.orEmpty() }
                status.value = "Done · ${reason.ifBlank { "goal reached" }}"
                log("Finished: ${reason.ifBlank { "goal reached" }}")
                return
            }

            val result = try {
                execute(action, observation)
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                "failed: ${describe(t)}"
            }
            log("Result: $result")
            history.addLast("$summary -> $result")
            trimHistory(history)
            delay(action.settleDelay())
        }

        if (running.value) {
            status.value = "Stopped at the ${cfg.maxSteps}-step limit"
            log("Reached the ${cfg.maxSteps}-step limit")
        }
    }

    private fun trimHistory(history: ArrayDeque<String>) {
        while (history.size > MAX_HISTORY) {
            history.removeFirst()
        }
    }

    // ------------------------------------------------------------- observation

    private suspend fun observe(): Observation? = withContext(Dispatchers.IO) {
        val displayW = CloneEngine.displayW.value.coerceIn(320, 1920)
        val displayH = CloneEngine.displayH.value.coerceIn(320, 1920)
        // Keep the JPEG comfortably below the 1 MB binder limit; OCR
        // coordinates are rescaled to display pixels before the model sees them.
        val shotWidth = displayW.coerceAtMost(720)
        val jpeg = CloneEngine.screenshotJpeg(shotWidth, 80) ?: fallbackJpeg()
            ?: return@withContext null
        val bitmap = try {
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        } catch (t: Throwable) {
            null
        } ?: return@withContext null
        return@withContext try {
            val scaleX = displayW.toFloat() / bitmap.width.coerceAtLeast(1)
            val scaleY = displayH.toFloat() / bitmap.height.coerceAtLeast(1)
            val lines = CloneOcr.recognize(bitmap)
                ?.mapNotNull { line ->
                    val text = line.text.replace(Regex("\\s+"), " ").trim()
                    if (text.isEmpty()) null
                    else ScreenLine(
                        text,
                        (line.x * scaleX).roundToInt(),
                        (line.y * scaleY).roundToInt(),
                        (line.w * scaleX).roundToInt(),
                        (line.h * scaleY).roundToInt(),
                    )
                }
                ?.filter { it.w > 0 && it.h > 0 }
                ?.sortedWith(compareBy({ it.y }, { it.x }))
                ?.take(160)
                ?: emptyList()
            Observation(jpeg, displayW, displayH, lines)
        } finally {
            try {
                bitmap.recycle()
            } catch (ignored: Throwable) {
            }
        }
    }

    /** Fallback when the shell service cannot provide a fresh screenshot. */
    private fun fallbackJpeg(): ByteArray? {
        val bitmap = CloneEngine.latestFrameBitmap() ?: return null
        return try {
            ByteArrayOutputStream().also { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }.toByteArray()
        } catch (t: Throwable) {
            null
        }
    }

    // -------------------------------------------------------------------- HTTP

    private suspend fun requestAction(
        cfg: Config,
        packageName: String,
        goalText: String,
        observation: Observation,
        history: List<String>,
    ): String = withContext(Dispatchers.IO) {
        val label = CloneEngine.apps.value.firstOrNull { it.packageName == packageName }?.label ?: packageName
        val prompt = AiProtocol.buildPrompt(
            goal = goalText,
            appLabel = label,
            packageName = packageName,
            width = CloneEngine.displayW.value,
            height = CloneEngine.displayH.value,
            lines = observation.lines,
            history = history,
            step = step.value,
            maxSteps = cfg.maxSteps,
        )
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", AiProtocol.SYSTEM_PROMPT))
        if (cfg.vision) {
            val content = JSONArray()
            content.put(JSONObject().put("type", "text").put("text", prompt))
            content.put(
                JSONObject().put("type", "image_url").put(
                    "image_url",
                    JSONObject().put(
                        "url",
                        "data:image/jpeg;base64," +
                            Base64.encodeToString(observation.jpeg, Base64.NO_WRAP),
                    ),
                ),
            )
            messages.put(JSONObject().put("role", "user").put("content", content))
        } else {
            messages.put(JSONObject().put("role", "user").put("content", prompt))
        }
        chat(cfg, messages, maxTokens = 500)
    }

    private fun chat(cfg: Config, messages: JSONArray, maxTokens: Int): String {
        val body = JSONObject()
            .put("model", cfg.model)
            .put("stream", false)
            .put("temperature", 0.15)
            .put("max_tokens", maxTokens)
            .put("messages", messages)
        val builder = Request.Builder()
            .url(AiProtocol.endpoint(cfg.baseUrl))
            .header("Accept", "application/json")
            .post(body.toString().toRequestBody(jsonMedia))
        if (cfg.apiKey.isNotBlank()) {
            builder.header("Authorization", "Bearer ${cfg.apiKey}")
        }
        val call = http.newCall(builder.build())
        activeCall = call
        try {
            call.execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IllegalStateException("HTTP ${response.code}: ${text.take(400)}")
                }
                if (text.isBlank()) {
                    throw IllegalStateException("empty response from the AI endpoint")
                }
                val root = try {
                    JSONObject(text)
                } catch (t: Throwable) {
                    throw IllegalStateException("invalid JSON from the AI endpoint: ${text.take(200)}")
                }
                val choices = root.optJSONArray("choices")
                val choice = choices?.optJSONObject(0)
                val message = choice?.optJSONObject("message")
                var content = message?.optString("content").orEmpty()
                if (content.isBlank()) {
                    content = choice?.optString("text").orEmpty()
                }
                if (content.isBlank()) {
                    throw IllegalStateException("the model returned no text: ${text.take(300)}")
                }
                return content
            }
        } finally {
            if (activeCall === call) activeCall = null
        }
    }

    /** Blocking connectivity check; call from IO. Returns a human-readable result. */
    suspend fun testConnection(baseUrl: String, apiKey: String, model: String): String =
        withContext(Dispatchers.IO) {
            val cfg = Config(
                baseUrl = baseUrl.trim(),
                apiKey = apiKey.trim(),
                model = model.trim(),
                vision = false,
                maxSteps = 30,
            )
            if (!cfg.usable) {
                return@withContext "Set an endpoint, model and API key first (or use an http:// local endpoint)"
            }
            try {
                val messages = JSONArray()
                    .put(JSONObject().put("role", "system").put("content", "Reply with exactly: OK"))
                    .put(JSONObject().put("role", "user").put("content", "Connectivity test"))
                val reply = chat(cfg, messages, maxTokens = 16)
                "Connected · ${cfg.model} · ${reply.trim().take(40)}"
            } catch (t: Throwable) {
                "Failed · ${describe(t)}"
            }
        }

    // ------------------------------------------------------------------ action

    private suspend fun execute(action: AiAction, observation: Observation): String {
        val displayW = CloneEngine.displayW.value.coerceAtLeast(1)
        val displayH = CloneEngine.displayH.value.coerceAtLeast(1)
        val scaleX = displayW.toFloat() / observation.width.coerceAtLeast(1)
        val scaleY = displayH.toFloat() / observation.height.coerceAtLeast(1)

        fun toDisplay(line: ScreenLine): Pair<Int, Int> =
            (line.cx * scaleX).roundToInt().coerceIn(0, displayW - 1) to
                (line.cy * scaleY).roundToInt().coerceIn(0, displayH - 1)

        fun actionPoint(): Pair<Int, Int>? {
            val index = action.index
            if (index != null) {
                val line = observation.lines.getOrNull(index) ?: return null
                return toDisplay(line)
            }
            val x = action.x
            val y = action.y
            if (x != null && y != null) {
                return x.coerceIn(0, displayW - 1) to y.coerceIn(0, displayH - 1)
            }
            return null
        }

        return when (action.type) {
            "tap_text", "click_text" -> {
                val needle = action.text.orEmpty().trim()
                if (needle.isEmpty()) {
                    "missing text"
                } else {
                    val line = AiProtocol.bestTextMatch(observation.lines, needle)
                    if (line == null) {
                        "text not found on screen: $needle"
                    } else {
                        val (x, y) = toDisplay(line)
                        if (CloneEngine.tapSync(x, y)) {
                            "tapped '${line.text}' at $x,$y"
                        } else {
                            "tap was rejected by the shell service"
                        }
                    }
                }
            }

            "tap", "click", "tap_index" -> {
                val point = actionPoint()
                if (point == null) {
                    "missing a valid index or x/y"
                } else if (CloneEngine.tapSync(point.first, point.second)) {
                    "tapped ${point.first},${point.second}"
                } else {
                    "tap was rejected by the shell service"
                }
            }

            "long_press" -> {
                val point = actionPoint()
                if (point == null) {
                    "missing a valid index or x/y"
                } else {
                    val ms = (action.ms ?: 600).coerceIn(300, 3_000)
                    if (CloneEngine.longPressSync(point.first, point.second, ms)) {
                        "long-pressed ${point.first},${point.second} for ${ms}ms"
                    } else {
                        "long press was rejected by the shell service"
                    }
                }
            }

            "swipe", "scroll" -> {
                val direction = action.direction?.lowercase(Locale.ROOT) ?: "up"
                val (x1, y1, x2, y2) = AiProtocol.swipePath(
                    direction,
                    displayW,
                    displayH,
                    action.distance ?: 0,
                )
                if (CloneEngine.swipeSync(x1, y1, x2, y2, 340)) {
                    "swiped $direction"
                } else {
                    "swipe was rejected by the shell service"
                }
            }

            "input_text", "type", "set_text" -> {
                val text = action.text.orEmpty()
                if (text.isEmpty()) {
                    "missing text"
                } else if (CloneEngine.textSync(text)) {
                    "typed ${text.length} character(s)"
                } else {
                    "text injection was rejected (non-Latin text may be unsupported)"
                }
            }

            "key", "press_key", "press_back" -> {
                val key = if (action.type == "press_back") "back"
                else action.key ?: action.text ?: "back"
                if (CloneEngine.keySync(key)) "pressed $key" else "key press was rejected"
            }

            "launch", "open_app" -> {
                val pkg = action.packageName ?: action.text
                if (pkg.isNullOrBlank()) {
                    "missing package name"
                } else {
                    val err = CloneEngine.launchNowBlocking(pkg)
                    if (err == null) "launched $pkg" else "launch failed: $err"
                }
            }

            "wait", "sleep" -> {
                val ms = (action.ms ?: 800).coerceIn(200, 10_000)
                delay(ms.toLong())
                "waited ${ms}ms"
            }

            "observe", "screenshot" -> "screen refreshed"

            else -> "unknown action '${action.type}'"
        }
    }

    // -------------------------------------------------------------------- util

    private fun log(line: String) {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(java.util.Date())
        val text = "$stamp  $line"
        Log.d(TAG, line)
        events.value = (events.value + text).takeLast(MAX_EVENTS)
        CloneEngine.log("AI: $line")
    }

    private fun describe(t: Throwable): String {
        val message = t.message?.takeIf { it.isNotBlank() }
        return message ?: t.javaClass.simpleName
    }
}

/**
 * Pure parsing/formatting for the AI loop. Kept free of Android types so it is
 * unit-testable on the JVM.
 */
internal object AiProtocol {

    val SYSTEM_PROMPT: String = """
You are the control loop of an Android automation agent.
You operate ONE app running on a hidden virtual display. Each step you receive
OCR text with display-pixel bounds and your recent action history. Reply with
exactly ONE JSON object and nothing else.

Actions:
  {"action":"tap_text","text":"Search"}
  {"action":"tap","index":3}
  {"action":"tap","x":120,"y":640}
  {"action":"long_press","x":120,"y":640,"ms":600}
  {"action":"swipe","direction":"up"}
  {"action":"swipe","direction":"down","distance":600}
  {"action":"input_text","text":"hello"}
  {"action":"key","key":"back"}
  {"action":"launch","package":"com.example.app"}
  {"action":"wait","ms":800}
  {"action":"finish","reason":"what proves the goal is done"}

Rules:
- Prefer tap_text, or tap by OCR index; use raw coordinates only when needed.
- To type, tap the input field first, then call input_text on a later step.
- After an action that opens a screen or keyboard, wait or observe once.
- Never repeat an action that failed; choose another target.
- Stay inside the screen bounds. Do not invent text that is not on screen.
- input_text can type Latin text; prefer on-screen choices for other scripts.
- finish only when the goal is visibly achieved, or explain why it is impossible.
""".trim()

    fun endpoint(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        return if (base.endsWith("/chat/completions", ignoreCase = true)) {
            base
        } else {
            "$base/chat/completions"
        }
    }

    fun buildPrompt(
        goal: String,
        appLabel: String,
        packageName: String,
        width: Int,
        height: Int,
        lines: List<CloneAi.ScreenLine>,
        history: List<String>,
        step: Int,
        maxSteps: Int,
    ): String = buildString {
        append("GOAL: ").append(goal.trim()).append('\n')
        append("APP: ").append(appLabel).append(" (").append(packageName).append(")\n")
        append("SCREEN: ").append(width).append('x').append(height).append(" (display pixels)\n")
        append("STEP: ").append(step).append('/').append(maxSteps).append('\n')
        append("\nRECENT ACTIONS:\n")
        if (history.isEmpty()) {
            append("- (none)\n")
        } else {
            history.forEach { append("- ").append(it.replace('\n', ' ').take(220)).append('\n') }
        }
        append("\nOCR (index | text | center x,y | bounds x,y,w,h), top to bottom:\n")
        if (lines.isEmpty()) {
            append("(no text detected on this screen)\n")
        } else {
            lines.forEachIndexed { index, line ->
                append(index).append(" | ")
                    .append(line.text.replace('\n', ' ').take(120)).append(" | ")
                    .append(line.cx).append(',').append(line.cy).append(" | ")
                    .append(line.x).append(',').append(line.y).append(',')
                    .append(line.w).append(',').append(line.h).append('\n')
            }
        }
        append("\nReply with the single best next action as JSON.")
    }

    /** Swipe path for the *content* direction (down reveals lower content). */
    fun swipePath(direction: String, width: Int, height: Int, distance: Int): IntArray {
        val fraction = if (distance > 0) {
            (distance.toFloat() / max(width, height).coerceAtLeast(1)).coerceIn(0.15f, 0.8f)
        } else {
            0.55f
        }
        return when (direction.lowercase(Locale.ROOT)) {
            "down" -> intArrayOf(
                width / 2,
                (height * (0.5f - fraction / 2f)).roundToInt(),
                width / 2,
                (height * (0.5f + fraction / 2f)).roundToInt(),
            )
            "right" -> intArrayOf(
                (width * (0.5f - fraction / 2f)).roundToInt(),
                height / 2,
                (width * (0.5f + fraction / 2f)).roundToInt(),
                height / 2,
            )
            "left" -> intArrayOf(
                (width * (0.5f + fraction / 2f)).roundToInt(),
                height / 2,
                (width * (0.5f - fraction / 2f)).roundToInt(),
                height / 2,
            )
            else -> intArrayOf(
                width / 2,
                (height * (0.5f + fraction / 2f)).roundToInt(),
                width / 2,
                (height * (0.5f - fraction / 2f)).roundToInt(),
            )
        }
    }

    /** Best visible line for a requested label. Exact > prefix > contains > tokens. */
    fun bestTextMatch(lines: List<CloneAi.ScreenLine>, needle: String): CloneAi.ScreenLine? {
        val wanted = normalize(needle)
        if (wanted.isEmpty()) return null
        val wantedTokens = wanted.split(' ').filter { it.isNotBlank() }
        var best: CloneAi.ScreenLine? = null
        var bestScore = 0
        for (line in lines) {
            val value = normalize(line.text)
            if (value.isEmpty()) continue
            val score = when {
                value == wanted -> 1000 + value.length
                value.startsWith(wanted) || wanted.startsWith(value) -> 600 + min(value.length, wanted.length)
                value.contains(wanted) -> 400 + wanted.length
                wantedTokens.isNotEmpty() && wantedTokens.all { value.contains(it) } -> 250 + wantedTokens.sumOf { it.length }
                else -> 0
            }
            if (score > bestScore) {
                bestScore = score
                best = line
            }
        }
        return best
    }

    fun parseAction(raw: String): AiAction? {
        val objectText = extractJsonObject(raw) ?: return null
        val root = try {
            JSONObject(objectText)
        } catch (t: Throwable) {
            return null
        }
        // Some models wrap the action in {"name":..., "arguments":{...}}.
        var args = root
        var name = firstString(root, "action", "name", "type")
        val nested = root.optJSONObject("function") ?: root.optJSONObject("arguments")
            ?: root.optJSONObject("parameters")
        if (nested != null) {
            args = nested
            val nestedName = firstString(nested, "name", "action")
            if (!nestedName.isNullOrBlank()) {
                name = nestedName
            }
            // function.arguments / arguments may be a JSON string.
            when (val nestedArgs = nested.opt("arguments")) {
                is JSONObject -> args = nestedArgs
                is String -> if (nestedArgs.trim().startsWith("{")) {
                    runCatching { args = JSONObject(nestedArgs) }
                }
            }
        }
        // {"name":"tap","arguments":"{\"x\":1,\"y\":2}"} (arguments as a string
        // at the top level) and {"name":...,"arguments":{...}} both occur.
        when (val rawArgs = root.opt("arguments")) {
            is String -> if (rawArgs.trim().startsWith("{")) {
                runCatching { args = JSONObject(rawArgs) }
            }
            is JSONObject -> args = rawArgs
        }
        val action = name?.lowercase(Locale.ROOT)?.trim() ?: return null
        val text = firstString(args, "text", "value", "target", "label", "query")
        return AiAction(
            type = action,
            text = text,
            x = firstInt(args, "x"),
            y = firstInt(args, "y"),
            index = firstInt(args, "index", "targetIndex", "inputIndex"),
            direction = firstString(args, "direction"),
            distance = firstInt(args, "distance"),
            ms = firstInt(args, "ms", "durationMs", "duration"),
            key = firstString(args, "key", "keyName"),
            packageName = firstString(args, "package", "packageName", "app"),
            reason = firstString(args, "reason", "why", "evidence"),
        )
    }

    private fun extractJsonObject(raw: String): String? {
        val text = raw.trim()
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    private fun firstString(obj: JSONObject, vararg keys: String): String? {
        for (key in keys) {
            if (!obj.has(key) || obj.isNull(key)) continue
            val value = obj.opt(key)
            if (value is String && value.isNotBlank()) return value
            if (value is Number) return value.toString()
        }
        return null
    }

    private fun firstInt(obj: JSONObject, vararg keys: String): Int? {
        for (key in keys) {
            if (!obj.has(key) || obj.isNull(key)) continue
            when (val value = obj.opt(key)) {
                is Number -> return value.toInt()
                is String -> value.trim().toIntOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun normalize(value: String): String {
        val builder = StringBuilder(value.length)
        var lastSpace = false
        for (ch in value.lowercase(Locale.ROOT)) {
            if (ch.isLetterOrDigit()) {
                builder.append(ch)
                lastSpace = false
            } else if (!lastSpace && builder.isNotEmpty()) {
                builder.append(' ')
                lastSpace = true
            }
        }
        return builder.toString().trim()
    }
}

/** One parsed model action. */
internal data class AiAction(
    val type: String,
    val text: String? = null,
    val x: Int? = null,
    val y: Int? = null,
    val index: Int? = null,
    val direction: String? = null,
    val distance: Int? = null,
    val ms: Int? = null,
    val key: String? = null,
    val packageName: String? = null,
    val reason: String? = null,
) {
    fun describe(): String = buildString {
        append(type)
        text?.let { append(" text=\"").append(it.replace('\n', ' ').take(60)).append('"') }
        index?.let { append(" index=").append(it) }
        if (x != null || y != null) append(" ").append(x ?: "?").append(',').append(y ?: "?")
        direction?.let { append(" ").append(it) }
        key?.let { append(" key=").append(it) }
        packageName?.let { append(" pkg=").append(it) }
        ms?.let { append(" ").append(it).append("ms") }
    }

    /** Delay after the action so the display can settle before the next observation. */
    fun settleDelay(): Long = when (type) {
        "launch", "open_app" -> 2_200L
        "wait", "sleep" -> (ms ?: 800).toLong().coerceAtMost(10_000L)
        "key", "press_key", "press_back" -> 1_000L
        "swipe", "scroll" -> 900L
        "input_text", "type", "set_text" -> 500L
        else -> 750L
    }
}

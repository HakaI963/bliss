package com.altaris.linux.core.quinn

import android.content.Context
import com.altaris.linux.core.control.AgentDetector
import com.altaris.linux.core.install.DistroInstallProfile
import com.altaris.linux.core.rea.ReaDistro
import com.altaris.linux.core.rea.ReaManager
import com.altaris.linux.core.terminal.DistroFsCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Quinn answering about Altaris itself.
 *
 * This is what `//` is for: Quinn knows the app — every installed distro, every
 * agent, what each one is doing, the REA environment and the current thread —
 * and answers from that live state rather than from a guess. It is also the
 * source of the side-panel text, so `//btw` in the middle of an agent's work
 * opens a real answer instead of interrupting the agent.
 *
 * Every fact is collected on the device first and handed to the model as
 * context, so an answer can never invent a distro that is not installed.
 */
object QuinnBrain {

    /** Everything Quinn knows about this install, in one snapshot. */
    data class Snapshot(
        val distros: List<DistroFact>,
        val agents: List<AgentFact>,
        val rea: ReaFact,
        val quinnModel: String,
        val activeSession: String,
        val runningAgents: Int,
    )

    data class DistroFact(
        val id: String,
        val name: String,
        val method: String,
        val installed: Boolean,
        val agents: List<String>,
    )

    data class AgentFact(
        val label: String,
        val distro: String,
        val binary: String,
        val headless: Boolean,
    )

    data class ReaFact(
        val installed: Boolean,
        val ready: Boolean,
        val method: String,
        val tools: Int,
        val serverRunning: Boolean,
    )

    /** Collect the live state. Blocking; call from IO. */
    fun snapshot(context: Context): Snapshot {
        val app = context.applicationContext
        val installed = runCatching { DistroFsCache.get(app).installed }.getOrDefault(emptySet())
        val reports = runCatching { AgentDetector.scanDistros(app) }.getOrDefault(emptyList())
        val byDistro = reports.associateBy { it.id }

        val distros = ArrayList<DistroFact>()
        for (id in installed.sorted()) {
            if (DistroInstallProfile.isHidden(id)) continue
            val profile = DistroInstallProfile.forId(id) ?: continue
            distros.add(
                DistroFact(
                    id = id,
                    name = profile.displayName.ifBlank { id },
                    method = profile.method,
                    installed = true,
                    agents = byDistro[id]?.agents?.map { it.name }.orEmpty(),
                )
            )
        }

        val agents = ArrayList<AgentFact>()
        for (report in reports) {
            for (agent in report.agents) {
                agents.add(
                    AgentFact(
                        label = AgentCatalog.forBinary(agent.name).label,
                        distro = report.name,
                        binary = agent.name,
                        headless = AgentCatalog.isHeadless(agent.name),
                    )
                )
            }
        }

        ReaDistro.refresh(app)
        val reaState = ReaDistro.state.value
        val reaStatus = ReaManager.status.value

        return Snapshot(
            distros = distros,
            agents = agents,
            rea = ReaFact(
                installed = reaState.installed,
                ready = reaState.ready,
                method = reaState.method?.label.orEmpty(),
                tools = reaStatus.toolCount,
                serverRunning = reaStatus.running,
            ),
            quinnModel = QuinnModelConfig.load(app).model,
            activeSession = QuinnEngine.sessions.value.values
                .firstOrNull { it.running }?.displayTitle.orEmpty(),
            runningAgents = QuinnEngine.runningCount.value,
        )
    }

    /**
     * A deterministic answer for a command, used when no model is configured.
     *
     * Every command has a factual answer that needs no model at all, which
     * means `//status` works on a fresh install and never costs a request.
     */
    fun answerLocally(command: String, snapshot: Snapshot): String? = when (command.lowercase()) {
        "status" -> buildString {
            append("Altaris status\n")
            append("· ").append(snapshot.distros.size).append(" distros installed\n")
            append("· ").append(snapshot.agents.size).append(" agents reachable\n")
            append("· ").append(snapshot.runningAgents).append(" running now")
            if (snapshot.activeSession.isNotBlank()) {
                append(" (").append(snapshot.activeSession).append(')')
            }
            append('\n')
            append("· REA ").append(
                when {
                    !snapshot.rea.installed -> "not installed"
                    !snapshot.rea.ready -> "container present, tools missing"
                    else -> "ready (" + snapshot.rea.tools + " tools)"
                }
            )
        }
        "distros" -> if (snapshot.distros.isEmpty()) {
            "No distros installed."
        } else {
            snapshot.distros.joinToString("\n") {
                "· ${it.name} — ${it.method}, ${it.agents.size} agents"
            }
        }
        "agents" -> if (snapshot.agents.isEmpty()) {
            "No agent found. Install one in a distro, or add it by name in the agent window."
        } else {
            snapshot.agents.joinToString("\n") {
                "· ${it.label} in ${it.distro} (${if (it.headless) "headless" else "terminal"})"
            }
        }
        "rea" -> buildString {
            append("REA environment\n")
            append("· installed: ").append(if (snapshot.rea.installed) "yes" else "no").append('\n')
            append("· method: ").append(snapshot.rea.method.ifBlank { "—" }).append('\n')
            append("· toolchain: ").append(if (snapshot.rea.ready) "ready" else "incomplete").append('\n')
            append("· tools: ").append(snapshot.rea.tools).append('\n')
            append("· server: ").append(if (snapshot.rea.serverRunning) "live" else "stopped")
        }
        "model" -> if (snapshot.quinnModel.isBlank()) {
            "No Quinn model set. Add one in Settings → Quinn AI."
        } else {
            "Quinn is using ${snapshot.quinnModel}."
        }
        "help" -> SlashCommands.QUINN.joinToString("\n") { "${it.token} — ${it.description}" }
        else -> null
    }

    /**
     * The prompt for a `//` question the local answers do not cover.
     *
     * The snapshot is rendered as facts, so the model can only describe what is
     * actually installed.
     */
    fun systemPrompt(snapshot: Snapshot): String = buildString {
        append(
            "You are Quinn, the assistant inside Altaris, an Android app that runs Linux " +
                "distros and coding agents on the device. Answer from the state below and " +
                "nothing else. If a fact is not listed, say it is unknown.\n\n"
        )
        append("DISTROS (").append(snapshot.distros.size).append("):\n")
        if (snapshot.distros.isEmpty()) append("none\n")
        for (d in snapshot.distros) {
            append("- ").append(d.name).append(" [").append(d.method).append("]")
            if (d.agents.isNotEmpty()) {
                append(" agents: ").append(d.agents.joinToString(", "))
            }
            append('\n')
        }
        append("\nAGENTS (").append(snapshot.agents.size).append("):\n")
        if (snapshot.agents.isEmpty()) append("none\n")
        for (a in snapshot.agents) {
            append("- ").append(a.label).append(" in ").append(a.distro)
            append(if (a.headless) " (headless)" else " (terminal)")
            append('\n')
        }
        append("\nREA: ")
        append(
            when {
                !snapshot.rea.installed -> "not installed"
                !snapshot.rea.ready -> "container present, toolchain incomplete"
                else -> "ready, " + snapshot.rea.tools + " tools, server " +
                    (if (snapshot.rea.serverRunning) "live" else "stopped")
            }
        ).append('\n')
        append("QUINN MODEL: ").append(snapshot.quinnModel.ifBlank { "not configured" }).append('\n')
        append("RUNNING NOW: ").append(snapshot.runningAgents).append('\n')
        if (snapshot.activeSession.isNotBlank()) {
            append("ACTIVE THREAD: ").append(snapshot.activeSession).append('\n')
        }
    }

    /**
     * Answer a `//` question.
     *
     * Uses the local deterministic answer first; only an open-ended question
     * (`//btw …`, `//files …`) reaches the model. Returns null when there is no
     * model and the command has no local answer.
     */
    suspend fun ask(
        context: Context,
        command: String,
        argument: String,
        threadContext: String = "",
    ): String? = withContext(Dispatchers.IO) {
        val snapshot = snapshot(context)
        if (argument.isBlank()) {
            answerLocally(command, snapshot)?.let { return@withContext it }
        }
        val config = QuinnModelConfig.load(context.applicationContext)
        if (!config.configured) {
            return@withContext answerLocally(command, snapshot)
                ?: "Quinn has no model configured. Add one in Settings → Quinn AI."
        }
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt(snapshot)))
        if (threadContext.isNotBlank()) {
            messages.put(
                JSONObject().put("role", "system")
                    .put("content", "Current conversation:\n" + threadContext.take(6000))
            )
        }
        val question = if (argument.isBlank()) command else "$command: $argument"
        messages.put(JSONObject().put("role", "user").put("content", question))
        runCatching { chat(config, messages) }.getOrNull()
            ?: answerLocally(command, snapshot)
    }

    private fun chat(config: QuinnModelConfig.Config, messages: JSONArray): String {
        val body = JSONObject()
            .put("model", config.model)
            .put("messages", messages)
            .put("temperature", 0.3)
        val request = okhttp3.Request.Builder()
            .url(config.chatUrl)
            .header("Authorization", "Bearer " + config.apiKey)
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code} from the Quinn model")
            }
            val json = JSONObject(text)
            return json.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content").orEmpty()
                .ifBlank { "(no answer)" }
        }
    }
}

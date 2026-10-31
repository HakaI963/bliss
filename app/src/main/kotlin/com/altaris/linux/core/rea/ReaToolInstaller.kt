package com.altaris.linux.core.rea

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Installs REA's analysis tools in parallel, with one progress bar per tool.
 *
 * The requirement is that every tool downloads with its own visible progress,
 * several at once. Package managers are the obstacle: `apt`, `dnf` and `pacman`
 * take a database lock, so two package installs at the same time corrupt the
 * database or fail outright. Archives have no such problem.
 *
 * So the work is split by *how* each tool installs:
 *
 *  - **package tools** are serialised on one worker — the lock is real, and a
 *    fast serial install is still correct;
 *  - **archive tools** (JADX, Apktool, Ghidra) run in parallel on their own
 *    workers, because they are plain downloads into their own directories.
 *
 * Every tool still reports its own state and its own progress line, which is
 * what the dashboard shows. A failure in one never stops the others.
 */
object ReaToolInstaller {

    /** How many archive downloads run at once. */
    private const val ARCHIVE_WORKERS = 3

    /** Per-tool install state. */
    data class ToolState(
        val id: String,
        val label: String,
        val status: Status = Status.QUEUED,
        /** 0..100, or -1 when the tool has no measurable progress. */
        val percent: Int = -1,
        /** What the tool is doing right now. */
        val detail: String = "",
        /** Bytes fetched, when the installer reports it. */
        val downloaded: Long = 0L,
        val total: Long = 0L,
        val error: String = "",
    ) {
        enum class Status { QUEUED, RUNNING, DONE, FAILED, SKIPPED }
    }

    data class Snapshot(
        val tools: List<ToolState> = emptyList(),
        val running: Boolean = false,
    ) {
        val done: Int get() = tools.count { it.status == ToolState.Status.DONE }
        val failed: Int get() = tools.count { it.status == ToolState.Status.FAILED }
        val queued: Int get() = tools.count { it.status == ToolState.Status.QUEUED }
        val active: Int get() = tools.count { it.status == ToolState.Status.RUNNING }

        /** Fraction across the whole list, 0..1. */
        val fraction: Float
            get() = if (tools.isEmpty()) 0f else done.toFloat() / tools.size

        /** "12 / 18 done · 2 running · 1 failed" */
        val summary: String
            get() = "$done / ${tools.size} done" +
                (if (active > 0) " · $active running" else "") +
                (if (failed > 0) " · $failed failed" else "")
    }

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    private val updates = ConcurrentHashMap<String, ToolState>()

    /**
     * Install [ids] into [distroId].
     *
     * The REA core itself is installed first (serial: Node and npm are shared by
     * everything else), then the tools split into their two lanes.
     */
    suspend fun install(
        context: Context,
        distroId: String,
        ids: List<String>,
        onLog: (String) -> Unit = {},
    ): ReaManager.Status = withContext(Dispatchers.IO) {
        val deps = ids.mapNotNull { ReaCatalog.byId(it) }
        seed(deps)
        val manager = ReaManager.detectPackageManager(context, distroId)
        onLog("package manager: " + manager.ifBlank { "none" })

        // Core first: every tool depends on the distro being usable, and Node is
        // installed into a shared directory.
        _state.value = _state.value.copy(running = true)
        val status = ReaManager.install(context, distroId, emptyList()) { onLog(it) }
        if (status.error.isNotBlank()) {
            onLog("core: " + status.error)
        }

        val packageLane = deps.filter { isPackage(dep = it, manager = manager) }
        val archiveLane = deps.filterNot { isPackage(dep = it, manager = manager) }

        coroutineScope {
            // One worker for the package manager: its lock is real.
            val packageJob = async(Dispatchers.IO) {
                for (dep in packageLane) {
                    runOne(context, distroId, dep, manager, onLog)
                }
            }
            // Archives are independent downloads into their own directories.
            val gate = Semaphore(ARCHIVE_WORKERS)
            val archiveJobs = archiveLane.map { dep ->
                async(Dispatchers.IO) {
                    gate.withPermit { runOne(context, distroId, dep, manager, onLog) }
                }
            }
            packageJob.await()
            archiveJobs.awaitAll()
        }

        _state.value = _state.value.copy(running = false)
        ReaManager.probe(context, distroId)
    }

    /** Cancel is not supported mid-install; the caller restarts instead. */
    fun reset() {
        updates.clear()
        _state.value = Snapshot()
    }

    // ------------------------------------------------------------------ private

    private suspend fun runOne(
        context: Context,
        distroId: String,
        dep: ReaDependency,
        @Suppress("UNUSED_PARAMETER") manager: String,
        onLog: (String) -> Unit,
    ) {
        setStatus(dep, ToolState.Status.RUNNING, "starting")
        onLog("installing " + dep.label)
        val result = runCatching {
            ReaManager.install(context, distroId, listOf(dep.id)) { line ->
                // The manager's own line is the most precise progress there is;
                // it names the archive being fetched or the package being set up.
                update(dep.id) {
                    it.copy(
                        detail = line.take(120),
                        percent = percentFrom(line, it.percent),
                        downloaded = bytesFrom(line).first,
                        total = bytesFrom(line).second,
                    )
                }
                onLog(line)
            }
        }.getOrNull()

        val outcome = result?.outcomes?.firstOrNull { it.id == dep.id }
        val ok = outcome?.ok ?: result?.error.isNullOrBlank()
        if (ok) {
            update(dep.id) {
                it.copy(
                    status = ToolState.Status.DONE,
                    percent = 100,
                    detail = outcome?.detail?.take(80).orEmpty().ifBlank { "installed" },
                    error = "",
                )
            }
        } else {
            update(dep.id) {
                it.copy(
                    status = ToolState.Status.FAILED,
                    detail = "",
                    error = outcome?.detail?.take(160).orEmpty()
                        .ifBlank { result?.error?.take(160) ?: "install failed" },
                )
            }
        }
    }

    /** True when this tool installs through the distro's package manager. */
    private fun isPackage(dep: ReaDependency, manager: String): Boolean {
        if (dep.kind != "package") return false
        if (manager.isBlank() || manager == "none") return false
        return ReaCatalog.packagedFor(dep, manager)
    }

    private fun seed(deps: List<ReaDependency>) {
        updates.clear()
        for (dep in deps) {
            updates[dep.id] = ToolState(id = dep.id, label = dep.label)
        }
        publish(deps)
    }

    private fun setStatus(dep: ReaDependency, status: ToolState.Status, detail: String) {
        update(dep.id) { it.copy(status = status, detail = detail) }
    }

    private fun update(id: String, block: (ToolState) -> ToolState) {
        val current = updates[id] ?: return
        updates[id] = block(current)
        publish(updates.values.toList())
    }

    private fun publish(tools: List<ToolState>) {
        // Stable order: running first, then queued, then finished.
        val order = mapOf(
            ToolState.Status.RUNNING to 0,
            ToolState.Status.QUEUED to 1,
            ToolState.Status.FAILED to 2,
            ToolState.Status.DONE to 3,
            ToolState.Status.SKIPPED to 4,
        )
        _state.value = _state.value.copy(
            tools = tools.sortedWith(
                compareBy({ order[it.status] ?: 9 }, { it.label.lowercase() })
            ),
        )
    }

    /**
     * Progress from an installer line.
     *
     * Package managers do not report percentages, so a running tool keeps its
     * previous value rather than jumping around; archive downloads do report
     * bytes and are parsed by [bytesFrom].
     */
    private fun percentFrom(line: String, current: Int): Int {
        val (done, total) = bytesFrom(line)
        if (total > 0L) return ((done * 100) / total).toInt().coerceIn(0, 99)
        val explicit = Regex("""(\d{1,3})\s*%""").find(line)?.groupValues?.get(1)?.toIntOrNull()
        if (explicit != null) return explicit.coerceIn(0, 99)
        return current
    }

    /** "12.3 / 45.6 MB" → (bytes, bytes). */
    private fun bytesFrom(line: String): Pair<Long, Long> {
        val match = BYTES_RE.find(line) ?: return 0L to 0L
        val done = match.groupValues[1].toDoubleOrNull() ?: return 0L to 0L
        val total = match.groupValues[2].toDoubleOrNull() ?: return 0L to 0L
        val unit = match.groupValues[3].uppercase()
        val scale = when (unit) {
            "GB", "G" -> 1024.0 * 1024.0 * 1024.0
            "MB", "M" -> 1024.0 * 1024.0
            "KB", "K" -> 1024.0
            else -> 1.0
        }
        return (done * scale).toLong() to (total * scale).toLong()
    }

    private val BYTES_RE = Regex("""([\d.]+)\s*/\s*([\d.]+)\s*(GB|MB|KB|G|M|K)""", RegexOption.IGNORE_CASE)
}

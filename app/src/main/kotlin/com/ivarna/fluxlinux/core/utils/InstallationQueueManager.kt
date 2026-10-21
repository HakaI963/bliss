package com.ivarna.fluxlinux.core.utils

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class InstallTask(
    val id: String,
    val name: String,
    val type: TaskType,
    val scriptName: String? = null,
    val isManual: Boolean = false,
    val distroId: String, // Required for building intents
    val extraEnv: Map<String, String> = emptyMap(),
    val isUninstall: Boolean = false
)

enum class TaskType {
    BASE_INSTALL,
    HW_ACCEL,
    COMPONENT
}


data class InstallationState(
    val isInstalling: Boolean = false,
    val currentTaskName: String = "",
    val progressCurrent: Int = 0,
    val progressTotal: Int = 0,
    val currentDistroId: String? = null,
    val cancelledByUser: Boolean = false,
    // Live runner progress (InstallTaskBar). -1 = unknown/indeterminate.
    val phaseLabel: String = "",
    val overallPercent: Int = -1,
    val detail: String = "",
    val failed: Boolean = false,
    val done: Boolean = false,
    /** Wall-clock time of the last runner tick; used to retire a stale 100% bar. */
    val updatedAtMillis: Long = 0L
)

object InstallationQueueManager {
    private val queue = ArrayDeque<InstallTask>()
    var currentTask: InstallTask? = null
        private set

    var activeDistroId: String? = null
        private set

    // Reactive State
    private val _installState = MutableStateFlow(InstallationState())
    val installState: StateFlow<InstallationState> = _installState.asStateFlow()

    fun enqueue(tasks: List<InstallTask>) {
        if (tasks.isNotEmpty()) {
            activeDistroId = tasks.first().distroId
        }
        queue.addAll(tasks)
        
        // Initial State Update
        _installState.value = _installState.value.copy(
            isInstalling = true,
            progressTotal = tasks.size, // This adds to existing queue size ideally, but we usually clear first
            currentDistroId = activeDistroId
        )
    }

    fun next(): InstallTask? {
        currentTask = queue.removeFirstOrNull()
        if (currentTask != null) {
            // Update State
            _installState.value = _installState.value.copy(
                currentTaskName = currentTask?.name ?: "Processing...",
                progressCurrent = _installState.value.progressCurrent + 1
            )
        }
        return currentTask
    }

    fun hasPending(): Boolean = queue.isNotEmpty()

    fun clear() {
        queue.clear()
        currentTask = null
        activeDistroId = null
        _installState.value = InstallationState()
    }

    fun cancelByUser() {
        _installState.value = _installState.value.copy(
            isInstalling = false,
            cancelledByUser = true,
            currentTaskName = "",
            progressCurrent = 0,
            progressTotal = 0,
            phaseLabel = "Cancelled",
            detail = "Install cancelled by user",
            failed = false,
            done = false
        )
        queue.clear()
        currentTask = null
        activeDistroId = null
    }

    /**
     * Live progress from the embedded base-install runner
     * ([com.ivarna.fluxlinux.core.install.OnboardingInstallRunner]).
     *
     * Queue semantics (enqueue/next/clear/cancelledByUser) are untouched: this is
     * a separate write path used by the task bar + the live install screen.
     */
    fun updateActiveInstall(
        taskName: String,
        phaseLabel: String,
        overallPercent: Int,
        detail: String,
        distroId: String? = null,
        failed: Boolean = false,
        done: Boolean = false
    ) {
        _installState.value = _installState.value.copy(
            isInstalling = !failed && !done,
            currentTaskName = taskName,
            currentDistroId = distroId ?: _installState.value.currentDistroId,
            phaseLabel = phaseLabel,
            overallPercent = overallPercent,
            detail = detail,
            failed = failed,
            done = done,
            cancelledByUser = false,
            updatedAtMillis = System.currentTimeMillis()
        )
    }

    /** Dismiss a terminal (cancelled / failed / done) task-bar snapshot. */
    fun dismissTerminalState() {
        if (_installState.value.isInstalling) return
        _installState.value = InstallationState()
    }

    /**
     * Retire a bar that reached 100% but never received its terminal tick
     * (e.g. a superseded runner whose postSuccess was filtered out). Only the
     * exact snapshot that has been idle is dismissed, so a live finalization
     * tick always restarts the grace period.
     */
    fun dismissCompletedIfStale(expectedUpdatedAtMillis: Long) {
        val current = _installState.value
        if (current.isInstalling &&
            current.overallPercent >= 100 &&
            current.updatedAtMillis == expectedUpdatedAtMillis
        ) {
            _installState.value = InstallationState()
        }
    }

    fun peek(): InstallTask? = queue.firstOrNull()
}

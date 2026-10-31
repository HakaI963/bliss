package com.altaris.linux.core.rea

import android.content.Context
import android.util.Log
import com.altaris.linux.core.install.DistroInstallProfile
import com.altaris.linux.core.root.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The hidden REA analysis distro.
 *
 * Quinn's REA tab needs a stable home with the right tools, without asking the
 * user to pick a distro and without appearing in the app. This object owns that
 * distro's lifecycle:
 *
 *  - **not installed** — the REA tab shows a frosted "install from Tools" state;
 *  - **installing** — one proot or chroot container is created and REA plus the
 *    analysis tools are installed into it;
 *  - **ready** — Quinn drives REA in it over MCP.
 *
 * The distro id is [DistroInstallProfile.REA_PROOT_ID] or `_CHROOT_ID`; both
 * profiles are marked `hidden`, so no list in the app can show them.
 */
object ReaDistro {

    private const val TAG = "AltarisReaDistro"

    enum class Method(val id: String, val label: String) {
        PROOT(DistroInstallProfile.REA_PROOT_ID, "proot"),
        CHROOT(DistroInstallProfile.REA_CHROOT_ID, "chroot");

        companion object {
            fun fromId(id: String?): Method? = entries.firstOrNull { it.id == id }
        }
    }

    data class State(
        val installed: Boolean = false,
        /** Which method is installed, if any. */
        val method: Method? = null,
        /** True while the container or the toolchain is being installed. */
        val busy: String = "",
        val ready: Boolean = false,
        val error: String = "",
        /** Rootfs size on disk, in bytes, for the status card. */
        val sizeBytes: Long = 0L,
        /** True when a chroot install is possible (root available). */
        val chrootPossible: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val mutex = Mutex()

    /** Recompute [state] from the filesystem. Cheap; safe to call often. */
    fun refresh(context: Context) {
        val app = context.applicationContext
        val method = installedMethod(app)
        val rootfs = method?.let { rootfsOf(app, it) }
        _state.value = _state.value.copy(
            installed = method != null,
            method = method,
            ready = method != null && ReaManager.isInstalledIn(app, method.id),
            sizeBytes = rootfs?.let { dirSize(it) } ?: 0L,
            chrootPossible = RootShell.isRootAvailable(),
            busy = _state.value.busy,
        )
    }

    /** The distro id of the installed REA distro, or null. */
    fun distroId(context: Context): String? = installedMethod(context)?.id

    private fun installedMethod(context: Context): Method? {
        for (method in Method.entries) {
            val rootfs = rootfsOf(context, method) ?: continue
            if (rootfs.isDirectory && rootfs.list()?.isNotEmpty() == true) return method
        }
        return null
    }

    private fun rootfsOf(context: Context, method: Method): File? =
        when (method) {
            Method.PROOT -> File(
                context.filesDir,
                "usr/var/lib/proot-distro/containers/${DistroInstallProfile.REA_PROOT_ID}/rootfs"
            )
            Method.CHROOT -> DistroInstallProfile.REA_CHROOT.chrootPath?.let { File(it) }
        }

    /**
     * Install the REA distro and the REA toolchain into it.
     *
     * The container itself is created by the normal distro install path (so
     * rootfs verification, proot setup and chroot staging are not duplicated);
     * this only chooses the profile, then hands off to [ReaManager] for the
     * Node + REA + tools install.
     */
    suspend fun install(
        context: Context,
        method: Method,
        tools: List<String> = DEFAULT_TOOLS,
        onLog: (String) -> Unit = {},
    ): State = withContext(Dispatchers.IO) {
        mutex.withLock {
            val app = context.applicationContext
            if (method == Method.CHROOT && !RootShell.isRootAvailable()) {
                _state.value = _state.value.copy(error = "chroot needs root access")
                return@withLock _state.value
            }
            val profile = DistroInstallProfile.reaProfile(method.id) ?: run {
                _state.value = _state.value.copy(error = "REA profile is missing")
                return@withLock _state.value
            }
            try {
                _state.value = _state.value.copy(
                    busy = "Creating the analysis environment",
                    error = "",
                )
                onLog("creating the hidden REA distro (" + method.label + ")")
                val created = ReaInstaller.ensureContainer(app, profile, onLog)
                if (!created) {
                    _state.value = _state.value.copy(
                        busy = "",
                        error = "Could not create the REA distro. Check the install log.",
                    )
                    return@withLock _state.value
                }
                onLog("installing REA and the analysis tools")
                val status = ReaManager.install(app, method.id, tools) { line ->
                    _state.value = _state.value.copy(busy = line.take(80))
                    onLog(line)
                }
                _state.value = _state.value.copy(
                    busy = "",
                    installed = true,
                    method = method,
                    ready = status.installed,
                    error = status.error,
                )
                refresh(app)
                _state.value
            } catch (t: Throwable) {
                Log.w(TAG, "REA distro install failed", t)
                _state.value = _state.value.copy(
                    busy = "",
                    error = t.message ?: t.javaClass.simpleName,
                )
                _state.value
            }
        }
    }

    /** Remove the REA distro entirely (its files and its container). */
    suspend fun uninstall(context: Context): State = withContext(Dispatchers.IO) {
        mutex.withLock {
            val app = context.applicationContext
            _state.value = _state.value.copy(busy = "Removing the analysis environment")
            runCatching { ReaManager.stop() }
            val method = installedMethod(app)
            if (method != null) {
                runCatching { ReaInstaller.removeContainer(app, method) }
            }
            _state.value = State(chrootPossible = RootShell.isRootAvailable())
            _state.value
        }
    }

    private fun dirSize(dir: File): Long =
        runCatching {
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)

    /** Tools installed with REA by default: enough for APK, ELF and firmware work. */
    val DEFAULT_TOOLS = listOf(
        "java", "jadx", "apktool", "binutils", "gdb", "radare2", "rizin",
        "python3", "p7zip", "sqlite3", "procps", "file", "adb", "binwalk",
    )
}

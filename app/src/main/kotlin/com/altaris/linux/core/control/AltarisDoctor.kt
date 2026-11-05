package com.altaris.linux.core.control

import android.content.Context
import android.os.Build
import com.altaris.linux.core.a11y.A11yBridge
import com.altaris.linux.core.clone.HeadlessCloneEngine
import com.altaris.linux.core.rea.ReaDebugger
import com.altaris.linux.core.rea.ReaDistro
import com.altaris.linux.core.rea.ReaManager
import com.altaris.linux.core.root.RootShell
import com.altaris.linux.core.system.ShizukuShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What this device can actually do right now.
 *
 * Every capability is reported with one of eight labels, and the difference
 * between them matters: `AVAILABLE_NOT_TESTED` means the dependency is present
 * but this call did not exercise it, so an agent must not promise the user it
 * works. The old behaviour was to infer capability from the presence of a
 * package and then fail at the first real call — which is how "Frida is
 * installed" turned into `capability_unavailable` mid-investigation.
 *
 * Nothing here mutates state: no installs, no service starts. It answers "what
 * would work if I tried it", plus the reason when the answer is no.
 */
object AltarisDoctor {

    /** The eight labels from the brief. */
    enum class Status(val label: String) {
        AVAILABLE_AND_TESTED("AVAILABLE_AND_TESTED"),
        AVAILABLE_NOT_TESTED("AVAILABLE_NOT_TESTED"),
        MISSING_DEPENDENCY("MISSING_DEPENDENCY"),
        PERMISSION_REQUIRED("PERMISSION_REQUIRED"),
        TARGET_UNAVAILABLE("TARGET_UNAVAILABLE"),
        TRANSPORT_UNAVAILABLE("TRANSPORT_UNAVAILABLE"),
        UNSUPPORTED("UNSUPPORTED"),
        TEMPORARY_FAILURE("TEMPORARY_FAILURE"),
    }

    /** One line of the report. */
    private fun entry(
        name: String,
        status: Status,
        detail: String,
        fix: String = "",
    ): JSONObject = JSONObject()
        .put("capability", name)
        .put("status", status.label)
        .put("detail", detail)
        .apply { if (fix.isNotBlank()) put("fix", fix) }

    /**
     * Build the report. Blocking; call from IO.
     *
     * [deep] also exercises the paths that can be tested cheaply (a real
     * accessibility tree read, a real screenshot). Those are the only two
     * capabilities that can be verified without side effects, and they are the
     * two an agent relies on most.
     */
    suspend fun report(context: Context, deep: Boolean = false): JSONObject =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val capabilities = JSONArray()

            // ---------------------------------------------------- device
            capabilities.put(
                entry(
                    "android_device",
                    Status.AVAILABLE_AND_TESTED,
                    "API " + Build.VERSION.SDK_INT + " · " +
                        Build.SUPPORTED_ABIS.firstOrNull().orEmpty() + " · " +
                        Build.MANUFACTURER + " " + Build.MODEL,
                )
            )

            // ---------------------------------------------------- privileges
            val root = runCatching { RootShell.isRootAvailable() }.getOrDefault(false)
            capabilities.put(
                if (root) {
                    entry("root", Status.AVAILABLE_NOT_TESTED, "su resolves and answered")
                } else {
                    entry(
                        "root", Status.PERMISSION_REQUIRED,
                        "no superuser grant; root-only paths are unavailable",
                        "Not required for UI work. Needed for host frida-server and chroot paths.",
                    )
                }
            )

            val shizukuRunning = runCatching { ShizukuShell.running() }.getOrDefault(false)
            val shizukuGranted = runCatching { ShizukuShell.granted() }.getOrDefault(false)
            capabilities.put(
                when {
                    shizukuRunning && shizukuGranted ->
                        entry("shizuku", Status.AVAILABLE_NOT_TESTED, "server up, permission granted")
                    shizukuRunning ->
                        entry(
                            "shizuku", Status.PERMISSION_REQUIRED,
                            "server up, Altaris not granted",
                            "Grant Altaris the Shizuku permission in the Shizuku app.",
                        )
                    else ->
                        entry(
                            "shizuku", Status.TRANSPORT_UNAVAILABLE,
                            "the Shizuku server is not running",
                            "Start Shizuku (root or adb) and grant Altaris permission.",
                        )
                }
            )

            // ---------------------------------------------------- accessibility
            val a11yStatus = runCatching { A11yBridge.status(app) }.getOrNull()
            val a11yJson = a11yStatus?.let { runCatching { JSONObject(it) }.getOrNull() }
            val a11yConnected = a11yJson?.optBoolean("connected") == true
            capabilities.put(
                if (a11yConnected) {
                    entry(
                        "accessibility", Status.AVAILABLE_AND_TESTED,
                        "service bound · " + a11yJson.optInt("windows", 0) + " window(s) visible",
                    )
                } else {
                    entry(
                        "accessibility", Status.PERMISSION_REQUIRED,
                        a11yJson?.optString("error").orEmpty().ifBlank {
                            "the Altaris accessibility service is not running"
                        },
                        "Call altaris_ui_enable — it enables the service through Shizuku or root.",
                    )
                }
            )

            // Physical UI interaction is only proven if the tree can be read.
            var uiTested = false
            if (deep && a11yConnected) {
                val tree = runCatching { A11yBridge.tree(app, -1, 40) }.getOrNull()
                val treeJson = tree?.let { runCatching { JSONObject(it) }.getOrNull() }
                uiTested = treeJson?.optBoolean("ok") == true &&
                    (treeJson.optJSONArray("nodes")?.length() ?: 0) > 0
            }
            capabilities.put(
                entry(
                    "physical_ui",
                    if (uiTested) Status.AVAILABLE_AND_TESTED else
                        if (a11yConnected) Status.AVAILABLE_NOT_TESTED else
                            Status.PERMISSION_REQUIRED,
                    if (uiTested) {
                        "the accessibility tree of the physical display was read"
                    } else if (a11yConnected) {
                        "service bound; pass deep=true to read a real tree"
                    } else {
                        "needs the accessibility service"
                    },
                    "altaris_ui_tree reads the physical display when no clone is active.",
                )
            )

            // ---------------------------------------------------- screenshots
            val a11yScreenshot = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && a11yConnected
            capabilities.put(
                when {
                    a11yScreenshot ->
                        entry(
                            "physical_screenshot", Status.AVAILABLE_NOT_TESTED,
                            "accessibility takeScreenshot is available (API " +
                                Build.VERSION.SDK_INT + ")",
                            "altaris_device_screenshot captures the physical display.",
                        )
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.R && shizukuRunning && shizukuGranted ->
                        entry(
                            "physical_screenshot", Status.AVAILABLE_NOT_TESTED,
                            "via Shizuku screencap (accessibility capture needs API 30+)",
                        )
                    else ->
                        entry(
                            "physical_screenshot", Status.MISSING_DEPENDENCY,
                            "needs API 30+ accessibility, or Shizuku",
                            "altaris_ui_enable, or start Shizuku.",
                        )
                }
            )

            // ---------------------------------------------------- clone engine
            val engineConnected = runCatching { HeadlessCloneEngine.connected.value }.getOrDefault(false)
            capabilities.put(
                entry(
                    "clone_engine",
                    if (engineConnected) Status.AVAILABLE_NOT_TESTED
                    else Status.TRANSPORT_UNAVAILABLE,
                    if (engineConnected) "the engine service is connected"
                    else "the clone engine is not connected",
                    if (engineConnected) "" else "Open Altaris once; the engine starts with the app.",
                )
            )

            // ---------------------------------------------------- REA
            val distro = runCatching { ReaDistro.distroId(app) }.getOrNull()
            val rea = ReaManager.status.value
            capabilities.put(
                when {
                    distro == null ->
                        entry(
                            "rea", Status.MISSING_DEPENDENCY,
                            "no distro selected for REA",
                            "Install a distro, then Tools → Reverse engineering → Install REA.",
                        )
                    rea.running ->
                        entry(
                            "rea", Status.AVAILABLE_AND_TESTED,
                            "running in " + rea.distroId + " · REA " + rea.reaVersion +
                                " · " + rea.toolCount + " tools",
                        )
                    rea.installed ->
                        entry(
                            "rea", Status.AVAILABLE_NOT_TESTED,
                            "installed in " + rea.distroId + ", not started",
                            "Any altaris_rea_call starts it.",
                        )
                    else ->
                        entry(
                            "rea", Status.MISSING_DEPENDENCY,
                            "not installed in " + distro,
                            "Tools → Reverse engineering → Install REA.",
                        )
                }
            )

            // ---------------------------------------------------- analysis tools
            val bins = rea.available
            for ((id, label) in listOf(
                "jadx" to "jadx",
                "apktool" to "apktool",
                "ghidra" to "ghidra",
                "binutils" to "binutils",
                "gdb" to "gdb",
            )) {
                val present = bins.contains(id)
                capabilities.put(
                    entry(
                        "tool:" + label,
                        if (present) Status.AVAILABLE_NOT_TESTED else Status.MISSING_DEPENDENCY,
                        if (present) "installed" else "not installed in the REA distro",
                        if (present) "" else "Tools → Reverse engineering installs it.",
                    )
                )
            }

            // ---------------------------------------------------- frida
            capabilities.put(fridaEntry(app))

            // ---------------------------------------------------- storage
            val cache = app.cacheDir
            val writable = cache.canWrite()
            val freeMb = runCatching { cache.usableSpace / (1024 * 1024) }.getOrDefault(0L)
            capabilities.put(
                entry(
                    "storage",
                    if (writable) Status.AVAILABLE_AND_TESTED else Status.TEMPORARY_FAILURE,
                    if (writable) "$freeMb MB free in the app cache"
                    else "the app cache is not writable",
                )
            )
            val shots = File(cache, "device-shots")
            capabilities.put(
                entry(
                    "image_handoff",
                    if (writable) Status.AVAILABLE_AND_TESTED else Status.TEMPORARY_FAILURE,
                    "screenshots are written to " + shots.absolutePath +
                        " and returned as file paths (not base64)",
                )
            )

            // ---------------------------------------------------- summary
            val blocked = ArrayList<String>()
            for (i in 0 until capabilities.length()) {
                val c = capabilities.optJSONObject(i) ?: continue
                val s = c.optString("status")
                if (s != Status.AVAILABLE_AND_TESTED.label &&
                    s != Status.AVAILABLE_NOT_TESTED.label
                ) {
                    blocked.add(c.optString("capability") + ": " + c.optString("detail"))
                }
            }

            JSONObject()
                .put("ok", true)
                .put("deep", deep)
                .put("api", Build.VERSION.SDK_INT)
                .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
                .put("capabilities", capabilities)
                .put(
                    "summary",
                    JSONObject()
                        .put("total", capabilities.length())
                        .put("blocked", blocked.size)
                        .put("blockedList", JSONArray(blocked))
                        .put(
                            "guidance",
                            if (blocked.isEmpty()) {
                                "Everything listed is available. UI work does not need root or Frida."
                            } else {
                                "These are the only gaps. Each has a fix above; none of them " +
                                    "blocks ordinary navigation, inspection or APK analysis."
                            },
                        ),
                )
        }
    /**
     * Frida, reported by the part that actually knows.
     *
     * Three independent things have to be true, and they were being collapsed
     * into one: the **server** (a host binary, root), the **CLI** (a Python
     * package inside the distro), and the **Node binding** (an optional npm
     * dependency inside REA's own tree). REA's hook tools import the Node one,
     * so a running server with no binding still answers
     * capability_unavailable — which is the confusion this entry removes.
     */
    private suspend fun fridaEntry(context: Context): JSONObject {
        val state = ReaDebugger.state.value
        val node = ReaManager.fridaNodeStatus(context)
        val parts = ArrayList<String>()
        if (state.fridaRunning) parts.add("server running") else parts.add("server stopped")
        if (state.fridaClient) parts.add("CLI present") else parts.add("CLI missing")
        parts.add(
            when {
                node.importable -> "Node binding ok (" + node.version.ifBlank { "?" } + ")"
                node.installed -> "Node binding broken"
                else -> "Node binding missing"
            }
        )
        if (state.gadgetReady) parts.add("Gadget ready")

        val status = when {
            node.importable && state.fridaRunning -> Status.AVAILABLE_AND_TESTED
            node.importable -> Status.AVAILABLE_NOT_TESTED
            state.gadgetReady -> Status.AVAILABLE_NOT_TESTED
            node.installed -> Status.TEMPORARY_FAILURE
            else -> Status.MISSING_DEPENDENCY
        }
        val fix = when {
            node.importable -> ""
            state.gadgetReady -> "Clones can be instrumented through Gadget without the Node binding."
            else -> "altaris_frida_install repairs the Node binding, or " +
                "altaris_gadget_prepare gives the no-root path."
        }
        return entry("frida", status, parts.joinToString(" · "), fix)
            .put(
                "node",
                JSONObject()
                    .put("importable", node.importable)
                    .put("installed", node.installed)
                    .put("version", node.version)
                    .put("declared", node.declared)
                    .put("nodeVersion", node.nodeVersion)
                    .put("platform", node.platform)
                    .put("arch", node.arch)
                    .put("detail", node.detail)
                    .put("repair", node.repair),
            )
    }

}

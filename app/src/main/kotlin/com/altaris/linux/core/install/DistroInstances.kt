package com.altaris.linux.core.install

import android.content.Context
import android.util.Log
import java.io.File
import java.util.Locale

/**
 * Registry of named PRoot distro instances.
 *
 * A distro can now be installed more than once: every install gets its own
 * proot-distro container and, optionally, a user-chosen name. An instance is
 * identified by its container name, so every code path that resolves a distro
 * id through [DistroInstallProfile.forId] (terminal sessions, desktops,
 * appearance, storage, components) keeps working unchanged:
 *
 *  - the first install of a distro keeps the legacy container name (the base
 *    distro id, e.g. `alpine`) — it never enters this registry;
 *  - any install with a custom name creates a separate container
 *    `<base>-<slug>` (e.g. `alpine-work`) that is registered here;
 *  - unregistered custom containers (registry lost, app data cleared) are
 *    re-discovered by scanning the proot-distro containers dir and mapping the
 *    guest rootfs `/etc/os-release` ID back to a base distro.
 *
 * The registry is a small SharedPreferences map kept in an in-memory cache.
 * [profileFor] deliberately needs no Context so the pure lookup in
 * [DistroInstallProfile.forId] can consult it from any thread.
 */
object DistroInstances {

    private const val TAG = "DistroInstances"

    private const val PREFS = "altaris_distro_instances"
    private const val KEY_PREFIX = "inst:"
    private const val SEP = "\u001f"

    /** One named install of a base distro. [id] == [container]. */
    data class Instance(
        val id: String,
        val baseId: String,
        val container: String,
        val label: String
    )

    private val cache = LinkedHashMap<String, Instance>()

    @Volatile
    private var loaded = false

    /** Read the persisted registry and discover containers on disk. */
    @Synchronized
    fun init(context: Context) {
        load(context.applicationContext)
        scan(context.applicationContext)
    }

    /** All registered instances (containers with a rootfs on disk). */
    @Synchronized
    fun all(context: Context): List<Instance> {
        load(context.applicationContext)
        scan(context.applicationContext)
        return cache.values.filter { it.containerExists(context) }
    }

    /** The instance registered for [id] (the container name), or null. */
    @Synchronized
    fun get(context: Context, id: String): Instance? {
        load(context.applicationContext)
        return cache[id]?.takeIf { it.containerExists(context) }
    }

    /** PRoot container name for [id]: registered instance, or the id itself. */
    fun containerFor(context: Context, id: String): String =
        get(context, id)?.container ?: id

    /** Base distro id for [id]: registered instance, or the id itself. */
    fun baseFor(context: Context, id: String): String =
        get(context, id)?.baseId ?: id

    /** Display label for [id]: registered instance, or the id itself. */
    fun labelFor(context: Context, id: String): String =
        get(context, id)?.label ?: id

    /**
     * Register (or refresh) a named install. [container] must be unique.
     * Persisted, so the label survives process death.
     */
    @Synchronized
    fun register(context: Context, container: String, baseId: String, label: String): Instance {
        val inst = Instance(container, baseId, container, label.ifBlank { container })
        load(context.applicationContext)
        cache[container] = inst
        persist(context.applicationContext, inst)
        return inst
    }

    /** Drop a registered instance (uninstall). */
    @Synchronized
    fun remove(context: Context, id: String) {
        load(context.applicationContext)
        if (cache.remove(id) != null) {
            try {
                prefs(context).edit().remove(KEY_PREFIX + id).apply()
            } catch (e: Exception) {
                Log.w(TAG, "instance remove failed: ${e.message}")
            }
        }
    }

    /**
     * A unique container name for a new install of [baseId] labelled [label]:
     * `<base>-<slug>`, then `-2`, `-3` … when taken. Returns null when the
     * label is empty (the caller should install into the default container).
     */
    @Synchronized
    fun containerForNew(context: Context, baseId: String, label: String?): String? {
        val clean = label?.trim().orEmpty()
        if (clean.isEmpty()) return null
        load(context.applicationContext)
        val slug = slugOf(clean)
        var candidate = "$baseId-$slug"
        var n = 2
        while (isTaken(context, candidate)) {
            candidate = "$baseId-$slug-$n"
            n++
        }
        return candidate
    }

    /**
     * Pure in-memory profile lookup used by [DistroInstallProfile.forId].
     * Must not touch Context: it runs on every card/session open.
     */
    @Synchronized
    fun profileFor(id: String): DistroInstallProfile? {
        if (!loaded) return null
        val inst = cache[id] ?: return null
        val base = DistroInstallProfile.baseProfile(inst.baseId) ?: return null
        return base.copy(
            distroId = inst.id,
            prootName = inst.container,
            displayName = inst.label
        )
    }

    /** Cache-only lookup without Context (pure-JVM callers, card mapping). */
    @Synchronized
    fun cached(id: String): Instance? = if (loaded) cache[id] else null

    /** All cached instances without Context (browse lists after init). */
    @Synchronized
    fun cachedAll(): List<Instance> = if (loaded) cache.values.toList() else emptyList()

    // ─────────────────────────────────────────────────────────────────────────

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    private fun load(context: Context) {
        if (loaded) return
        loaded = true
        try {
            prefs(context).all.forEach { (key, value) ->
                if (key.startsWith(KEY_PREFIX) && value is String) {
                    val parts = value.split(SEP)
                    if (parts.size >= 2) {
                        val container = key.removePrefix(KEY_PREFIX)
                        cache[container] = Instance(
                            id = container,
                            baseId = parts[0],
                            container = container,
                            label = parts.getOrNull(1)?.ifBlank { container } ?: container
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "instance registry load failed: ${e.message}")
        }
    }

    /**
     * Discover containers that exist on disk but are not in the registry:
     * custom containers from a wiped registry, re-mapped through the guest
     * os-release id. Legacy containers named exactly like a base distro are
     * never registered — [DistroInstallProfile.forId] resolves them natively.
     */
    @Synchronized
    private fun scan(context: Context) {
        val containers = File(
            context.filesDir, "usr/var/lib/proot-distro/containers"
        )
        if (!containers.isDirectory) return
        val children = containers.listFiles() ?: return
        for (dir in children) {
            val name = dir.name
            if (!File(dir, "rootfs").isDirectory) continue
            if (cache.containsKey(name)) continue
            if (DistroInstallProfile.baseProfile(name)?.method == "proot") continue
            val baseId = inferBase(name, dir)
            if (baseId != null) {
                cache[name] = Instance(name, baseId, name, name)
            }
        }
    }

    /** Guess the base distro of an unregistered container from its rootfs. */
    private fun inferBase(container: String, dir: File): String? {
        val osRelease = File(dir, "rootfs/etc/os-release")
        val text = try {
            if (osRelease.isFile) osRelease.readText() else ""
        } catch (_: Exception) {
            ""
        }
        val id = Regex("(?m)^ID=(.*)$").find(text)?.groupValues?.get(1)
            ?.trim()?.trim('"', '\'')?.lowercase(Locale.ROOT).orEmpty()
        val byOs = when {
            id.startsWith("alpine") -> "alpine"
            id.startsWith("chimera") -> "chimera"
            id.startsWith("fedora") -> "fedora"
            id.startsWith("void") -> "void"
            id.startsWith("opensuse") || id.startsWith("suse") -> "opensuse"
            id.startsWith("deepin") -> "deepin"
            id.startsWith("manjaro") -> "manjaro"
            id.startsWith("ubuntu") -> "ubuntu"
            id.startsWith("kali") -> "kali"
            id.startsWith("parrot") -> "parrot"
            id.startsWith("arch") -> "archlinux"
            id.startsWith("rocky") -> "rocky"
            id.startsWith("centos") -> "centos_stream"
            id.startsWith("debian") -> "debian"
            else -> null
        }
        if (byOs != null) return byOs
        // Fall back to the container-name prefix: `alpine-work` → alpine.
        return DistroInstallProfile.allRootfsProfiles()
            .map { it.distroId }
            .firstOrNull { container == it || container.startsWith("$it-") }
    }

    private fun isTaken(context: Context, container: String): Boolean {
        if (cache.containsKey(container)) return true
        if (DistroInstallProfile.baseProfile(container) != null) return true
        return File(
            context.filesDir, "usr/var/lib/proot-distro/containers/$container"
        ).exists()
    }

    @Synchronized
    private fun persist(context: Context, inst: Instance) {
        try {
            prefs(context).edit()
                .putString(KEY_PREFIX + inst.container, inst.baseId + SEP + inst.label)
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "instance persist failed: ${e.message}")
        }
    }

    private fun Instance.containerExists(context: Context): Boolean = File(
        context.filesDir, "usr/var/lib/proot-distro/containers/$container/rootfs"
    ).isDirectory

    /** proot-distro-safe name fragment: lowercase alnum, dash, dot, underscore. */
    private fun slugOf(label: String): String {
        val s = label.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-', '.')
            .take(24)
            .trim('-', '.')
        return s.ifEmpty { "instance" }
    }
}

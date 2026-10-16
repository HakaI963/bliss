package com.ivarna.fluxlinux.core.theme

import android.content.Context
import android.net.Uri
import android.util.Log
import com.ivarna.fluxlinux.R
import com.ivarna.fluxlinux.core.install.DistroInstallProfile
import com.ivarna.fluxlinux.core.install.ProotXfceAssetInstaller
import com.ivarna.fluxlinux.core.root.ChrootPaths
import com.ivarna.fluxlinux.core.root.RootShell
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Folio Linux per-distro appearance (wallpaper / accent / terminal colors /
 * optional video wallpaper).
 *
 * Storage: one JSON string per distro in SharedPreferences [PREFS]
 * (`appearance_<distroId>`), plus picked media in `filesDir/appearance/`.
 *
 * Guest application is **single-sourced** in the guest script
 * `scripts/common/setup/folio_appearance_apply.sh` (staged as
 * `~/.config/folio/appearance-apply.sh` together with
 * `~/.config/folio/appearance.env`). This object only:
 *  - keeps the per-distro config,
 *  - resolves it into the `FLUX_*` env contract ([exportEnvFor]),
 *  - stages env + media into the distro:
 *      - proot: direct host-side writes into the rootfs (app uid owns it),
 *      - chroot: root copy into `/data/local/folio/chroots/<distro>` via
 *        [RootShell] + an immediate in-chroot apply run (best-effort).
 *
 * Applying on next shell/desktop session is expected: the guest trigger runs
 * the staged script and the script hash-guards against the env file, so it
 * only re-applies when the config actually changed.
 */
object AppearanceStore {

    private const val TAG = "AppearanceStore"

    /** SharedPreferences file holding one JSON config per distro. */
    const val PREFS = "folio_appearance"
    private const val KEY_PREFIX = "appearance_"
    private const val JSON_VERSION = 1

    // ── wallpaper kinds ──────────────────────────────────────────────────────
    const val KIND_ASSET = "asset"     // one of [WALLPAPER_PRESETS]
    const val KIND_CUSTOM = "custom"   // user-picked image
    const val KIND_NONE = "none"       // solid color
    const val KIND_RANDOM = "random"   // random pick from [Config.randomIds]

    // ── wallpaper styles ─────────────────────────────────────────────────────
    const val STYLE_ZOOM = "zoom"
    const val STYLE_STRETCHED = "stretched"
    const val STYLE_CENTERED = "centered"
    val WALLPAPER_STYLES = listOf(STYLE_ZOOM, STYLE_STRETCHED, STYLE_CENTERED)

    /** Solid desktop color used when no wallpaper image is set (Folio carbon). */
    const val SOLID_COLOR = "#0B0B0D"

    // ── guest paths ──────────────────────────────────────────────────────────
    const val GUEST_HOME = "/home/flux"
    const val GUEST_CFG = "$GUEST_HOME/.config/folio"
    const val GUEST_SHARE = "$GUEST_HOME/.local/share/folio"
    const val GUEST_ENV_FILE = "appearance.env"
    const val GUEST_APPLY_SCRIPT = "appearance-apply.sh"
    const val GUEST_MARKER = ".appearance.applied"

    /** Guest-side applier (asset, also staged into `~/.config/folio/`). */
    const val APPLY_ASSET = "scripts/common/setup/folio_appearance_apply.sh"

    private const val MAX_WALLPAPER_BYTES = 64L * 1024L * 1024L
    private const val MAX_VIDEO_BYTES = 2L * 1024L * 1024L * 1024L

    private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
    private val VIDEO_EXTS = setOf("mp4", "webm", "mkv", "mov")

    // ── presets ──────────────────────────────────────────────────────────────

    /** Built-in wallpaper (asset path is APK-relative, guestFile the staged name). */
    data class WallpaperPreset(
        val id: String,
        val label: String,
        val asset: String,
        val guestFile: String,
        val thumbRes: Int
    )

    data class AccentPreset(val id: String, val label: String, val hex: String)

    data class TerminalScheme(
        val id: String,
        val label: String,
        val fg: String,
        val bg: String,
        val cursor: String,
        val selection: String,
        val promptColor: String
    )

    /** Shipped presets (the three originals + three Folio presets). */
    val WALLPAPER_PRESETS: List<WallpaperPreset> = listOf(
        WallpaperPreset(
            "folio-dark", "Folio Linux Dark",
            "xfce4/wallpaper/fluxlinux-dark.png", "wallpaper-folio-dark.png",
            R.drawable.folio_wall_dark
        ),
        WallpaperPreset(
            "folio-light", "Folio Linux Light",
            "xfce4/wallpaper/fluxlinux-light.png", "wallpaper-folio-light.png",
            R.drawable.folio_wall_light
        ),
        WallpaperPreset(
            "folio-shard", "Folio Linux Shard",
            "xfce4/wallpaper/folio-dark.svg", "wallpaper-folio-shard.svg",
            R.drawable.folio_wall_shard
        ),
        WallpaperPreset(
            "aurora", "Aurora",
            "xfce4/wallpaper/presets/folio-aurora.png", "wallpaper-aurora.png",
            R.drawable.folio_wall_aurora
        ),
        WallpaperPreset(
            "traces", "Traces",
            "xfce4/wallpaper/presets/folio-traces.png", "wallpaper-traces.png",
            R.drawable.folio_wall_traces
        ),
        WallpaperPreset(
            "density", "Density",
            "xfce4/wallpaper/presets/folio-density.png", "wallpaper-density.png",
            R.drawable.folio_wall_density
        )
    )

    /** Desktop accent presets — mono-first, then the requested accents. */
    val ACCENT_PRESETS: List<AccentPreset> = listOf(
        AccentPreset("mono", "Mono", "#F4F4F5"),
        AccentPreset("white", "White", "#FFFFFF"),
        AccentPreset("ice", "Ice Blue", "#7FD4FF"),
        AccentPreset("emerald", "Emerald", "#52D486"),
        AccentPreset("amber", "Amber", "#FFB000"),
        AccentPreset("rose", "Rose", "#FF8FB0")
    )

    /** Terminal color schemes (ids are persisted; colors are resolved app-side). */
    val TERMINAL_SCHEMES: List<TerminalScheme> = listOf(
        TerminalScheme("folio-mono", "Folio Linux Mono", "#F4F4F5", "#09090B", "#A1A1AA", "#27272A", "255"),
        TerminalScheme("white-black", "White on Black", "#FFFFFF", "#000000", "#FFFFFF", "#333333", "255"),
        TerminalScheme("amber-crt", "Amber CRT", "#FFB000", "#0A0500", "#FFB000", "#3A2600", "214"),
        TerminalScheme("ice", "Ice", "#D6EEFF", "#060B12", "#7FD4FF", "#14304A", "117"),
        TerminalScheme("emerald", "Emerald", "#CFF7DD", "#04100A", "#52D486", "#123B26", "79"),
        TerminalScheme("rose", "Rose", "#FFD9E3", "#14060B", "#FF8FB0", "#43172A", "211")
    )

    /** xfce4-terminal custom FG/BG swatch palette (logger picker). */
    val CUSTOM_COLORS: List<String> = listOf(
        "#F4F4F5", "#FFFFFF", "#A1A1AA", "#52525B", "#09090B",
        "#7FD4FF", "#52D486", "#FFB000", "#FF8FB0"
    )

    // ── config ───────────────────────────────────────────────────────────────

    data class Config(
        val wallpaperKind: String = KIND_ASSET,
        /** Preset id for [KIND_ASSET] and the fallback preset. */
        val wallpaperPreset: String = "folio-dark",
        /** App-side file name (`filesDir/appearance/…`) for [KIND_CUSTOM]. */
        val wallpaperLocal: String = "",
        val wallpaperStyle: String = STYLE_ZOOM,
        /** Preset ids used by [KIND_RANDOM] (empty = all presets). */
        val randomIds: List<String> = emptyList(),
        val accent: String = "mono",
        val termScheme: String = "folio-mono",
        /** Custom FG/BG (used when termScheme == "custom"). */
        val termFg: String = "#F4F4F5",
        val termBg: String = "#09090B",
        val videoEnabled: Boolean = false,
        /** App-side file name (`filesDir/appearance/…`) for the video wallpaper. */
        val videoLocal: String = ""
    )

    fun presetFor(id: String?): WallpaperPreset? = WALLPAPER_PRESETS.firstOrNull { it.id == id }

    fun accentFor(cfg: Config): AccentPreset =
        ACCENT_PRESETS.firstOrNull { it.id == cfg.accent } ?: ACCENT_PRESETS[0]

    fun schemeFor(cfg: Config): TerminalScheme {
        if (cfg.termScheme == "custom") {
            val fg = normalizeHex(cfg.termFg) ?: TERMINAL_SCHEMES[0].fg
            val bg = normalizeHex(cfg.termBg) ?: TERMINAL_SCHEMES[0].bg
            return TerminalScheme("custom", "Custom", fg, bg, fg, "#27272A", "255")
        }
        return TERMINAL_SCHEMES.firstOrNull { it.id == cfg.termScheme } ?: TERMINAL_SCHEMES[0]
    }

    /** Preset ids participating in random mode (all presets when unset). */
    fun randomIdsFor(cfg: Config): List<String> {
        val valid = cfg.randomIds.filter { presetFor(it) != null }
        return valid.ifEmpty { WALLPAPER_PRESETS.map { it.id } }
    }

    fun normalizeHex(value: String?): String? {
        val v = value?.trim() ?: return null
        val hex = if (v.startsWith("#")) v else "#$v"
        return if (Regex("^#[0-9a-fA-F]{6}$").matches(hex)) hex.uppercase() else null
    }

    // ── persistence ──────────────────────────────────────────────────────────

    fun load(ctx: Context, distroId: String): Config {
        val raw = prefs(ctx).getString(KEY_PREFIX + distroId, null) ?: return Config()
        return try {
            val o = JSONObject(raw)
            Config(
                wallpaperKind = o.optString("wallpaperKind", KIND_ASSET)
                    .takeIf { it in setOf(KIND_ASSET, KIND_CUSTOM, KIND_NONE, KIND_RANDOM) }
                    ?: KIND_ASSET,
                wallpaperPreset = o.optString("wallpaperPreset", "folio-dark")
                    .takeIf { presetFor(it) != null } ?: "folio-dark",
                wallpaperLocal = o.optString("wallpaperLocal", ""),
                wallpaperStyle = o.optString("wallpaperStyle", STYLE_ZOOM)
                    .takeIf { it in WALLPAPER_STYLES } ?: STYLE_ZOOM,
                randomIds = o.optJSONArray("randomIds")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
                } ?: emptyList(),
                accent = o.optString("accent", "mono")
                    .takeIf { id -> ACCENT_PRESETS.any { it.id == id } } ?: "mono",
                termScheme = o.optString("termScheme", "folio-mono")
                    .takeIf { id -> id == "custom" || TERMINAL_SCHEMES.any { it.id == id } }
                    ?: "folio-mono",
                termFg = o.optString("termFg", "#F4F4F5"),
                termBg = o.optString("termBg", "#09090B"),
                videoEnabled = o.optBoolean("videoEnabled", false),
                videoLocal = o.optString("videoLocal", "")
            )
        } catch (e: Exception) {
            Log.w(TAG, "config parse failed for $distroId: ${e.message}")
            Config()
        }
    }

    fun save(ctx: Context, distroId: String, cfg: Config) {
        val o = JSONObject()
            .put("v", JSON_VERSION)
            .put("wallpaperKind", cfg.wallpaperKind)
            .put("wallpaperPreset", cfg.wallpaperPreset)
            .put("wallpaperLocal", cfg.wallpaperLocal)
            .put("wallpaperStyle", cfg.wallpaperStyle)
            .put("randomIds", JSONArray(cfg.randomIds))
            .put("accent", cfg.accent)
            .put("termScheme", cfg.termScheme)
            .put("termFg", cfg.termFg)
            .put("termBg", cfg.termBg)
            .put("videoEnabled", cfg.videoEnabled)
            .put("videoLocal", cfg.videoLocal)
        prefs(ctx).edit().putString(KEY_PREFIX + distroId, o.toString()).apply()
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** One-line summary for the Distro Settings row. */
    fun summary(ctx: Context, distroId: String): String {
        val cfg = load(ctx, distroId)
        val wallpaper = when (cfg.wallpaperKind) {
            KIND_CUSTOM ->
                if (cfg.wallpaperLocal.isBlank()) "Custom image (none picked)" else "Custom image"
            KIND_RANDOM -> "Random (${randomIdsFor(cfg).size})"
            KIND_NONE -> "Solid color"
            else -> presetFor(cfg.wallpaperPreset)?.label ?: "Default"
        }
        return "$wallpaper · ${accentFor(cfg).label} · ${schemeFor(cfg).label}"
    }

    // ── media import (SAF → app files dir) ───────────────────────────────────

    /** Copy a picked image into `filesDir/appearance/wallpaper.<ext>`. Returns the file name. */
    fun importWallpaper(ctx: Context, uri: Uri): String? =
        importMedia(ctx, uri, "wallpaper", false, IMAGE_EXTS, MAX_WALLPAPER_BYTES)

    /** Copy a picked video into `filesDir/appearance/video-wallpaper.<ext>`. Returns the file name. */
    fun importVideo(ctx: Context, uri: Uri): String? =
        importMedia(ctx, uri, "video-wallpaper", true, VIDEO_EXTS, MAX_VIDEO_BYTES)

    /** App-side stored media file (`filesDir/appearance/<name>`), or null. */
    fun localFile(ctx: Context, name: String): File? {
        if (name.isBlank()) return null
        val f = File(File(ctx.filesDir, "appearance"), File(name).name)
        return f.takeIf { it.isFile }
    }

    private fun importMedia(
        ctx: Context,
        uri: Uri,
        base: String,
        video: Boolean,
        exts: Set<String>,
        maxBytes: Long
    ): String? {
        var dest: File? = null
        return try {
            val dir = File(ctx.filesDir, "appearance").also { it.mkdirs() }
            val input = ctx.contentResolver.openInputStream(uri) ?: return null
            input.use { source ->
                // Peek the header so the stored extension matches the real bytes
                // (SAF mime can be wrong/unknown, e.g. image/heic).
                val head = ByteArray(16)
                var headLen = 0
                while (headLen < head.size) {
                    val n = source.read(head, headLen, head.size - headLen)
                    if (n <= 0) break
                    headLen += n
                }
                val ext = detectExt(head, headLen, video)
                    ?: resolveExt(ctx, uri, exts)
                    ?: return null
                dir.listFiles()?.forEach { f -> if (f.name.startsWith("$base.")) f.delete() }
                val out = File(dir, "$base.$ext")
                dest = out
                FileOutputStream(out).use { sink ->
                    sink.write(head, 0, headLen)
                    val buf = ByteArray(64 * 1024)
                    var total = headLen.toLong()
                    while (true) {
                        val n = source.read(buf)
                        if (n <= 0) break
                        total += n
                        if (total > maxBytes) throw IllegalStateException("media too large")
                        sink.write(buf, 0, n)
                    }
                }
            }
            val out = dest
            if (out != null && out.isFile && out.length() > 0L) out.name else null
        } catch (e: Exception) {
            Log.w(TAG, "importMedia failed: ${e.message}")
            dest?.delete()
            null
        }
    }

    /** Extension from magic bytes (null when unknown). */
    private fun detectExt(head: ByteArray, len: Int, video: Boolean): String? {
        fun at(i: Int): Int = if (i < len) head[i].toInt() and 0xFF else -1
        return when {
            !video && at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> "png"
            !video && at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> "jpg"
            !video && at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x38 -> "gif"
            !video && at(0) == 0x42 && at(1) == 0x4D -> "bmp"
            !video && at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 &&
                at(8) == 0x57 && at(9) == 0x45 && at(10) == 0x42 && at(11) == 0x50 -> "webp"
            video && at(0) == 0x1A && at(1) == 0x45 && at(2) == 0xDF && at(3) == 0xA3 -> "mkv"
            video && at(4) == 0x66 && at(5) == 0x74 && at(6) == 0x79 && at(7) == 0x70 -> "mp4"
            else -> null
        }
    }

    private fun resolveExt(ctx: Context, uri: Uri, allowed: Set<String>): String? {
        val mime = try {
            ctx.contentResolver.getType(uri)
        } catch (_: Exception) {
            null
        }
        val fromMime = when (mime) {
            "image/png" -> "png"
            "image/jpeg", "image/jpg" -> "jpg"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/bmp", "image/x-ms-bmp" -> "bmp"
            "video/mp4" -> "mp4"
            "video/webm" -> "webm"
            "video/x-matroska" -> "mkv"
            "video/quicktime" -> "mov"
            else -> null
        }
        if (fromMime != null && fromMime in allowed) return fromMime
        val raw = (uri.lastPathSegment ?: "").substringAfterLast('.', "").lowercase()
        val safe = raw.filter { it.isLetterOrDigit() }
        if (safe.isNotEmpty() && safe.length <= 5 && safe in allowed) return safe
        // Unknown container — reject honestly instead of mislabeling the file.
        return null
    }

    // ── env contract ─────────────────────────────────────────────────────────

    /** Raw (unquoted) guest env for a distro's saved config. */
    fun exportEnvFor(ctx: Context, distroId: String): Map<String, String> =
        exportEnv(load(ctx, distroId))

    internal fun exportEnv(cfg: Config): Map<String, String> {
        val scheme = schemeFor(cfg)
        val accent = accentFor(cfg)
        var cursor = scheme.cursor
        var selection = scheme.selection
        if (accent.id != "mono") {
            cursor = accent.hex
            selection = accent.hex
        }
        val (wallpaper, choices, random) = wallpaperEnv(cfg)
        val video = if (cfg.videoEnabled && cfg.videoLocal.isNotBlank()) {
            "$GUEST_SHARE/${File(cfg.videoLocal).name}"
        } else {
            ""
        }
        return linkedMapOf(
            "FOLIO_APPEARANCE_VERSION" to "1",
            "FLUX_WALLPAPER" to wallpaper,
            "FLUX_WALLPAPER_STYLE" to cfg.wallpaperStyle,
            "FLUX_WALLPAPER_SOLID" to SOLID_COLOR,
            "FLUX_WALLPAPER_RANDOM" to random,
            "FLUX_WALLPAPER_CHOICES" to choices,
            "FLUX_ACCENT" to accent.id,
            "FLUX_ACCENT_HEX" to if (accent.id == "mono") "" else accent.hex,
            "FLUX_TERM_FG" to scheme.fg,
            "FLUX_TERM_BG" to scheme.bg,
            "FLUX_TERM_CURSOR" to cursor,
            "FLUX_TERM_SELECTION" to selection,
            "FLUX_PROMPT_COLOR" to scheme.promptColor,
            "FLUX_VIDEO_WALLPAPER" to video
        )
    }

    private fun wallpaperEnv(cfg: Config): Triple<String, String, String> {
        return when (cfg.wallpaperKind) {
            KIND_CUSTOM -> {
                val name = File(cfg.wallpaperLocal).name
                if (name.isBlank()) Triple("", "", "0")
                else Triple("$GUEST_SHARE/$name", "", "0")
            }
            KIND_ASSET -> {
                val preset = presetFor(cfg.wallpaperPreset) ?: WALLPAPER_PRESETS[0]
                Triple("$GUEST_SHARE/${preset.guestFile}", "", "0")
            }
            KIND_RANDOM -> {
                val ids = randomIdsFor(cfg)
                val paths = ids.mapNotNull { presetFor(it)?.guestFile }.joinToString(" ") { "$GUEST_SHARE/$it" }
                Triple("", paths, "1")
            }
            else -> Triple("", "", "0")
        }
    }

    /** Renders the env map as a shell-quoted KEY=VALUE file. */
    fun renderEnvFile(env: Map<String, String>): String =
        env.entries.joinToString(separator = "\n", postfix = "\n") { (k, v) ->
            "$k=${shellQuote(v)}"
        }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    // ── mpv probe (video wallpaper gate) ─────────────────────────────────────

    /**
     * True when the guest has mpv installed. Blocking (proot: file probe;
     * chroot: root probe inside the chroot). Background thread only; the video
     * wallpaper is disabled in the UI when this returns false.
     */
    fun isMpvAvailable(ctx: Context, distroId: String): Boolean {
        val app = ctx.applicationContext
        val profile = DistroInstallProfile.forId(distroId)
        val isChroot = profile?.method == "chroot" ||
            (profile == null && distroId.endsWith("_chroot"))
        return try {
            if (isChroot) {
                val path = profile?.chrootPath ?: ChrootPaths.pathForDistro(distroId)
                val res = RootShell.captureInChroot(
                    "command -v mpv >/dev/null 2>&1 && echo FOLIO_MPV_YES || echo FOLIO_MPV_NO",
                    user = "root",
                    chrootPath = path,
                    timeoutMs = 20_000L,
                    context = app
                )
                res.stdout.contains("FOLIO_MPV_YES")
            } else {
                val name = profile?.prootName?.takeIf { it.isNotBlank() } ?: prootNameFor(distroId)
                if (name.isBlank()) return false
                val rootfs = ProotXfceAssetInstaller.prootRootfs(app, name)
                if (!rootfs.isDirectory) return false
                listOf("usr/bin/mpv", "usr/local/bin/mpv", "bin/mpv")
                    .any { File(rootfs, it).exists() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "mpv probe failed for $distroId: ${e.message}")
            false
        }
    }

    // ── staging ──────────────────────────────────────────────────────────────

    data class StageOutcome(val ok: Boolean, val message: String)

    /** Save the config and stage it into the distro (blocking; background thread). */
    fun applyAndStage(ctx: Context, distroId: String, cfg: Config): StageOutcome {
        save(ctx, distroId, cfg)
        return stageForDistro(ctx, distroId)
    }

    /**
     * Stage `appearance.env` + `appearance-apply.sh` + media into the distro.
     * proot: direct host-side writes. chroot: root copy + immediate in-chroot
     * apply (best-effort; the login helper re-applies when root is available).
     */
    fun stageForDistro(ctx: Context, distroId: String, onLog: (String) -> Unit = {}): StageOutcome {
        val app = ctx.applicationContext
        val cfg = load(app, distroId)
        val envText = renderEnvFile(exportEnv(cfg))
        val profile = DistroInstallProfile.forId(distroId)
        val isChroot = profile?.method == "chroot" ||
            (profile == null && distroId.endsWith("_chroot"))
        return try {
            val files = collectMedia(app, cfg, onLog)
            if (isChroot) {
                stageChroot(app, distroId, profile?.chrootPath, envText, files, onLog)
            } else {
                stageProot(app, profile?.prootName?.takeIf { it.isNotBlank() } ?: prootNameFor(distroId),
                    envText, files, onLog)
            }
        } catch (e: Exception) {
            Log.w(TAG, "stage failed for $distroId: ${e.message}")
            StageOutcome(false, "Staging failed: ${e.message ?: "unknown error"}")
        }
    }

    private data class StagedFile(val src: File, val guestName: String)

    /**
     * Materialize every media file the config needs into the cache dir.
     * Missing custom media is skipped (the guest keeps the previous/default).
     */
    private fun collectMedia(app: Context, cfg: Config, onLog: (String) -> Unit): List<StagedFile> {
        val cache = File(app.cacheDir, "folio_appearance").also { it.mkdirs() }
        val out = mutableListOf<StagedFile>()

        fun stageAsset(asset: String, guestName: String) {
            val dest = File(cache, guestName)
            try {
                app.assets.open(asset).use { input ->
                    FileOutputStream(dest).use { input.copyTo(it) }
                }
                if (dest.isFile && dest.length() > 0L) {
                    out.add(StagedFile(dest, guestName))
                } else {
                    onLog("asset missing: $asset")
                }
            } catch (e: Exception) {
                onLog("asset copy failed: $asset (${e.message})")
            }
        }

        when (cfg.wallpaperKind) {
            KIND_CUSTOM -> {
                val name = File(cfg.wallpaperLocal).name
                val src = localFile(app, name)
                if (src != null) out.add(StagedFile(src, name))
                else onLog("custom wallpaper missing — keeping previous")
            }
            KIND_ASSET -> {
                val preset = presetFor(cfg.wallpaperPreset) ?: WALLPAPER_PRESETS[0]
                stageAsset(preset.asset, preset.guestFile)
            }
            KIND_RANDOM -> {
                for (id in randomIdsFor(cfg)) {
                    val preset = presetFor(id) ?: continue
                    stageAsset(preset.asset, preset.guestFile)
                }
            }
            else -> Unit
        }

        if (cfg.videoEnabled && cfg.videoLocal.isNotBlank()) {
            val name = File(cfg.videoLocal).name
            val src = localFile(app, name)
            if (src != null) out.add(StagedFile(src, name))
            else onLog("video wallpaper missing — disabling until re-picked")
        }
        return out
    }

    private fun stageProot(
        app: Context,
        prootName: String,
        envText: String,
        files: List<StagedFile>,
        onLog: (String) -> Unit
    ): StageOutcome {
        if (prootName.isBlank()) return StageOutcome(false, "Unknown distro — cannot stage appearance.")
        val rootfs = ProotXfceAssetInstaller.prootRootfs(app, prootName)
        if (!rootfs.isDirectory) {
            return StageOutcome(false, "Distro rootfs not found — install it first.")
        }
        val home = File(rootfs, GUEST_HOME.removePrefix("/"))
        val cfgDir = File(home, ".config/folio").also { it.mkdirs() }
        val shareDir = File(home, ".local/share/folio").also { it.mkdirs() }
        if (!cfgDir.isDirectory || !shareDir.isDirectory) {
            return StageOutcome(false, "Cannot write into the distro home directory.")
        }
        // Drop previously staged appearance media (managed names only).
        shareDir.listFiles()?.forEach { f ->
            if (f.name.startsWith("wallpaper") || f.name.startsWith("video-wallpaper")) {
                f.delete()
            }
        }
        for (sf in files) {
            try {
                sf.src.copyTo(File(shareDir, sf.guestName), overwrite = true)
            } catch (e: Exception) {
                onLog("copy ${sf.guestName} failed: ${e.message}")
            }
        }
        File(cfgDir, GUEST_ENV_FILE).writeText(envText)
        File(cfgDir, GUEST_APPLY_SCRIPT).writeText(readApplyScript(app))
        // Force one apply pass on the next guest shell (cheap; hash guard anyway).
        File(cfgDir, GUEST_MARKER).delete()
        onLog("staged ${files.size} file(s) into $prootName rootfs")
        return StageOutcome(true, "Staged for the distro — applies on next session.")
    }

    private fun stageChroot(
        app: Context,
        distroId: String,
        chrootPath: String?,
        envText: String,
        files: List<StagedFile>,
        onLog: (String) -> Unit
    ): StageOutcome {
        val path = chrootPath?.takeIf { it.isNotBlank() } ?: ChrootPaths.pathForDistro(distroId)
        val cache = File(app.cacheDir, "folio_appearance").also { it.mkdirs() }
        val envFile = File(cache, GUEST_ENV_FILE).also { it.writeText(envText) }
        val scriptFile = File(cache, GUEST_APPLY_SCRIPT).also { it.writeText(readApplyScript(app)) }

        val homeDir = "$path$GUEST_HOME"
        val cfgDir = "$path$GUEST_CFG"
        val shareDir = "$path$GUEST_SHARE"
        val cmd = buildString {
            append("if [ ! -d '$homeDir' ]; then echo FOLIO_APPEARANCE_NO_HOME; exit 0; fi; ")
            append("mkdir -p '$cfgDir' '$shareDir' 2>/dev/null || true; ")
            append("rm -f '$shareDir'/wallpaper-* '$shareDir'/wallpaper.* '$shareDir'/video-wallpaper.* 2>/dev/null || true; ")
            for (sf in files) {
                append("cp -f '${sf.src.absolutePath}' '$shareDir/${sf.guestName}' 2>/dev/null || true; ")
            }
            append("cp -f '${envFile.absolutePath}' '$cfgDir/$GUEST_ENV_FILE' 2>/dev/null || true; ")
            append("cp -f '${scriptFile.absolutePath}' '$cfgDir/$GUEST_APPLY_SCRIPT' 2>/dev/null || true; ")
            append("chmod 644 '$cfgDir/$GUEST_ENV_FILE' '$cfgDir/$GUEST_APPLY_SCRIPT' 2>/dev/null || true; ")
            append("rm -f '$cfgDir/$GUEST_MARKER' 2>/dev/null || true; ")
            append("chown -R --reference='$homeDir' '$cfgDir' '$shareDir' 2>/dev/null || true; ")
            append("echo FOLIO_APPEARANCE_STAGED")
        }
        val res = RootShell.capture(cmd, timeoutMs = 60_000L)
        if (res.contains("FOLIO_APPEARANCE_NO_HOME")) {
            return StageOutcome(false, "Distro home not found — install the distro first.")
        }
        if (!res.contains("FOLIO_APPEARANCE_STAGED")) {
            return StageOutcome(
                false,
                "Root shell unavailable — appearance for a rooted distro needs root. Grant root and retry."
            )
        }
        onLog("staged ${files.size} file(s) into $path")
        // Immediate in-chroot apply (best-effort; the login helper re-applies).
        val applied = try {
            val apply = RootShell.captureInChroot(
                "FOLIO_APPLY_HOME='$GUEST_HOME' sh '$GUEST_CFG/$GUEST_APPLY_SCRIPT' >/dev/null 2>&1; " +
                    "echo FOLIO_APPEARANCE_APPLIED",
                user = "root",
                chrootPath = path,
                timeoutMs = 60_000L,
                context = app
            )
            apply.stdout.contains("FOLIO_APPEARANCE_APPLIED")
        } catch (e: Exception) {
            onLog("in-chroot apply failed: ${e.message}")
            false
        }
        return StageOutcome(
            true,
            if (applied) "Staged & applied for the rooted distro."
            else "Staged for the rooted distro — applies on next session."
        )
    }

    private fun readApplyScript(app: Context): String =
        app.assets.open(APPLY_ASSET).use { it.readBytes() }.toString(Charsets.UTF_8)

    private fun prootNameFor(distroId: String): String = when (distroId) {
        "alpine", "alpine_chroot" -> "alpine"
        "debian", "debian13_chroot", "debian_chroot" -> "debian"
        else -> distroId.removeSuffix("_chroot").ifBlank { "debian" }
    }
}

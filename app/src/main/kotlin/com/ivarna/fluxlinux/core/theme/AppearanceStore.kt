package com.ivarna.fluxlinux.core.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.util.Log
import com.ivarna.fluxlinux.R
import com.ivarna.fluxlinux.core.desktop.DesktopSession
import com.ivarna.fluxlinux.core.install.DistroInstallProfile
import com.ivarna.fluxlinux.core.install.ProotXfceAssetInstaller
import com.ivarna.fluxlinux.core.root.ChrootPaths
import com.ivarna.fluxlinux.core.root.RootShell
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Bliss per-distro appearance (wallpaper / accent / terminal colors /
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
 * Applying is immediate when the distro's XFCE desktop is already running
 * ([AppearanceLiveApply]: guest script + live xfconf update + xfdesktop reload);
 * otherwise the guest trigger applies it on the next shell/desktop session
 * (login autostart entry, ~/.zshrc, xstartup). The script hash-guards against
 * the env file, so it only re-applies when the config actually changed.
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

    /** Solid desktop color used when no wallpaper image is set (Bliss carbon). */
    const val SOLID_COLOR = "#0B0B0D"

    // ── guest paths ──────────────────────────────────────────────────────────
    const val GUEST_HOME = "/home/flux"
    const val GUEST_CFG = "$GUEST_HOME/.config/folio"
    const val GUEST_SHARE = "$GUEST_HOME/.local/share/folio"
    const val GUEST_ENV_FILE = "appearance.env"
    const val GUEST_APPLY_SCRIPT = "appearance-apply.sh"
    const val GUEST_MARKER = ".appearance.applied"
    const val GUEST_AUTOSTART_DIR = "$GUEST_HOME/.config/autostart"
    const val GUEST_AUTOSTART_FILE = "folio-appearance.desktop"

    /** Staged name of the app-side dimmed variant of a custom wallpaper. */
    private const val DIMMED_WALLPAPER_NAME = "wallpaper-dim.png"

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

    /** Shipped presets (the three originals + three Bliss presets + 7 new). */
    val WALLPAPER_PRESETS: List<WallpaperPreset> = listOf(
        WallpaperPreset(
            "folio-dark", "Bliss Dark",
            "xfce4/wallpaper/fluxlinux-dark.png", "wallpaper-folio-dark.png",
            R.drawable.folio_wall_dark
        ),
        WallpaperPreset(
            "folio-light", "Bliss Light",
            "xfce4/wallpaper/fluxlinux-light.png", "wallpaper-folio-light.png",
            R.drawable.folio_wall_light
        ),
        WallpaperPreset(
            "folio-shard", "Bliss Shard",
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
        ),
        WallpaperPreset(
            "folio-amoled", "Bliss AMOLED",
            "xfce4/wallpaper/presets/folio-amoled.png", "wallpaper-folio-amoled.png",
            R.drawable.folio_wall_amoled
        ),
        WallpaperPreset(
            "folio-mesh", "Bliss Mesh",
            "xfce4/wallpaper/presets/folio-mesh.png", "wallpaper-folio-mesh.png",
            R.drawable.folio_wall_mesh
        ),
        WallpaperPreset(
            "folio-silk", "Bliss Silk",
            "xfce4/wallpaper/presets/folio-silk.png", "wallpaper-folio-silk.png",
            R.drawable.folio_wall_silk
        ),
        WallpaperPreset(
            "folio-dither", "Bliss Dither",
            "xfce4/wallpaper/presets/folio-dither.png", "wallpaper-folio-dither.png",
            R.drawable.folio_wall_dither
        ),
        WallpaperPreset(
            "folio-waves", "Bliss Waves",
            "xfce4/wallpaper/presets/folio-waves.png", "wallpaper-folio-waves.png",
            R.drawable.folio_wall_waves
        ),
        WallpaperPreset(
            "folio-beams", "Bliss Beams",
            "xfce4/wallpaper/presets/folio-beams.png", "wallpaper-folio-beams.png",
            R.drawable.folio_wall_beams
        ),
        WallpaperPreset(
            "folio-sakura", "Bliss Sakura",
            "xfce4/wallpaper/presets/folio-sakura.png", "wallpaper-folio-sakura.png",
            R.drawable.folio_wall_sakura
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
        TerminalScheme("folio-mono", "Bliss Mono", "#F4F4F5", "#09090B", "#A1A1AA", "#27272A", "255"),
        TerminalScheme("white-black", "White on Black", "#FFFFFF", "#000000", "#FFFFFF", "#333333", "255"),
        TerminalScheme("amber-crt", "Amber CRT", "#FFB000", "#0A0500", "#FFB000", "#3A2600", "214"),
        TerminalScheme("ice", "Ice", "#D6EEFF", "#060B12", "#7FD4FF", "#14304A", "117"),
        TerminalScheme("emerald", "Emerald", "#CFF7DD", "#04100A", "#52D486", "#123B26", "79"),
        TerminalScheme("rose", "Rose", "#FFD9E3", "#14060B", "#FF8FB0", "#43172A", "211"),
        TerminalScheme("graphite", "Graphite", "#E5E7EB", "#141417", "#9CA3AF", "#2A2A31", "250"),
        TerminalScheme("paper", "Paper", "#1A1A1E", "#F4F4F5", "#52525B", "#D4D4D8", "240")
    )

    // ── panel / icons / wallpaper dim / terminal font ────────────────────────

    data class PanelOpacityOption(val id: String, val label: String, val alpha: String)

    data class IconSizeOption(val id: String, val label: String)

    data class DimOption(val percent: Int, val label: String)

    data class FontSizeOption(val size: Int, val label: String)

    /** Panel & dock opacity steps (patched into xfce4-panel.xml by the guest). */
    val PANEL_OPACITY_OPTIONS: List<PanelOpacityOption> = listOf(
        PanelOpacityOption("solid", "Solid", "1.0"),
        PanelOpacityOption("frosted", "Frosted", "0.9"),
        PanelOpacityOption("glass", "Glass", "0.7")
    )

    /** Panel & dock icon size steps (S/M/L). */
    val ICON_SIZE_OPTIONS: List<IconSizeOption> = listOf(
        IconSizeOption("s", "S"),
        IconSizeOption("m", "M"),
        IconSizeOption("l", "L")
    )

    /** Wallpaper dim steps — custom images only (pre-processed app-side). */
    val DIM_OPTIONS: List<DimOption> = listOf(
        DimOption(0, "Off"),
        DimOption(25, "25%"),
        DimOption(50, "50%")
    )

    /** xfce4-terminal font size steps (family of the guest font is kept). */
    val TERM_FONT_SIZES: List<FontSizeOption> = listOf(
        FontSizeOption(10, "10"),
        FontSizeOption(12, "12"),
        FontSizeOption(14, "14")
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
        val videoLocal: String = "",
        /** Panel/dock opacity id (see [PANEL_OPACITY_OPTIONS]). */
        val panelOpacity: String = "frosted",
        /** Panel/dock icon size id (s/m/l, see [ICON_SIZE_OPTIONS]). */
        val iconSize: String = "m",
        /** Wallpaper darkening percent (0/25/50) — custom images only. */
        val wallpaperDim: Int = 0,
        /** xfce4-terminal font size (10/12/14). */
        val termFontSize: Int = 10
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
                videoLocal = o.optString("videoLocal", ""),
                panelOpacity = o.optString("panelOpacity", "frosted")
                    .takeIf { id -> PANEL_OPACITY_OPTIONS.any { it.id == id } } ?: "frosted",
                iconSize = o.optString("iconSize", "m")
                    .takeIf { id -> ICON_SIZE_OPTIONS.any { it.id == id } } ?: "m",
                wallpaperDim = o.optInt("wallpaperDim", 0)
                    .takeIf { d -> DIM_OPTIONS.any { it.percent == d } } ?: 0,
                termFontSize = o.optInt("termFontSize", 10)
                    .takeIf { s -> TERM_FONT_SIZES.any { it.size == s } } ?: 10
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
            .put("panelOpacity", cfg.panelOpacity)
            .put("iconSize", cfg.iconSize)
            .put("wallpaperDim", cfg.wallpaperDim)
            .put("termFontSize", cfg.termFontSize)
        prefs(ctx).edit().putString(KEY_PREFIX + distroId, o.toString()).apply()
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** One-line summary for the Distro Settings row. */
    fun summary(ctx: Context, distroId: String): String {
        val cfg = load(ctx, distroId)
        val wallpaper = when (cfg.wallpaperKind) {
            KIND_CUSTOM ->
                if (cfg.wallpaperLocal.isBlank()) "Custom image (none picked)"
                else if (cfg.wallpaperDim > 0) "Custom image (${cfg.wallpaperDim}% dim)"
                else "Custom image"
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

    /**
     * @param dimmedWallpaperGuestName guest file name of the pre-processed dimmed
     *   custom wallpaper (from [collectMedia]) — null = use the original file.
     */
    internal fun exportEnv(cfg: Config, dimmedWallpaperGuestName: String? = null): Map<String, String> {
        val scheme = schemeFor(cfg)
        val accent = accentFor(cfg)
        var cursor = scheme.cursor
        var selection = scheme.selection
        if (accent.id != "mono") {
            cursor = accent.hex
            selection = accent.hex
        }
        val (wallpaper, choices, random) = wallpaperEnv(cfg, dimmedWallpaperGuestName)
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
            "FLUX_WALLPAPER_DIM" to cfg.wallpaperDim.toString(),
            "FLUX_ACCENT" to accent.id,
            "FLUX_ACCENT_HEX" to if (accent.id == "mono") "" else accent.hex,
            "FLUX_TERM_FG" to scheme.fg,
            "FLUX_TERM_BG" to scheme.bg,
            "FLUX_TERM_CURSOR" to cursor,
            "FLUX_TERM_SELECTION" to selection,
            "FLUX_TERM_FONT_SIZE" to cfg.termFontSize.toString(),
            "FLUX_PROMPT_COLOR" to scheme.promptColor,
            "FLUX_PANEL_OPACITY" to cfg.panelOpacity,
            "FLUX_ICON_SIZE" to cfg.iconSize,
            "FLUX_VIDEO_WALLPAPER" to video
        )
    }

    private fun wallpaperEnv(cfg: Config, dimmedName: String?): Triple<String, String, String> {
        return when (cfg.wallpaperKind) {
            KIND_CUSTOM -> {
                val name = File(cfg.wallpaperLocal).name
                if (name.isBlank()) Triple("", "", "0")
                else Triple("$GUEST_SHARE/${dimmedName ?: name}", "", "0")
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

    /**
     * Honest outcome of a stage attempt.
     *
     * @param live true when the staged config was also applied to a desktop that
     *   is running right now.
     * @param desktopRunning true when a desktop session for this distro is up.
     * @param hint optional actionable next step for the UI.
     */
    data class StageOutcome(
        val ok: Boolean,
        val message: String,
        val live: Boolean = false,
        val desktopRunning: Boolean = false,
        val hint: String? = null
    )

    /** Save the config and stage it into the distro (blocking; background thread). */
    fun applyAndStage(ctx: Context, distroId: String, cfg: Config): StageOutcome {
        save(ctx, distroId, cfg)
        return stageForDistro(ctx, distroId)
    }

    /**
     * Stage `appearance.env` + `appearance-apply.sh` + media into the distro.
     * proot: direct host-side writes. chroot: root copy + immediate in-chroot
     * apply (best-effort; the login helper re-applies when root is available).
     *
     * When the XFCE desktop for this distro is running, the config is applied
     * immediately (live) and xfdesktop/panel are kicked to repaint.
     */
    fun stageForDistro(ctx: Context, distroId: String, onLog: (String) -> Unit = {}): StageOutcome {
        val app = ctx.applicationContext
        val cfg = load(app, distroId)
        val profile = DistroInstallProfile.forId(distroId)
        val isChroot = profile?.method == "chroot" ||
            (profile == null && distroId.endsWith("_chroot"))
        return try {
            val media = collectMedia(app, cfg, onLog)
            val envText = renderEnvFile(exportEnv(cfg, media.dimmedWallpaper))
            val staged = if (isChroot) {
                stageChroot(app, distroId, profile?.chrootPath, envText, media.files, onLog)
            } else {
                stageProot(app, profile?.prootName?.takeIf { it.isNotBlank() } ?: prootNameFor(distroId),
                    envText, media.files, onLog)
            }
            if (!staged.ok) return staged
            liveApply(app, distroId, profile, isChroot, staged, onLog)
        } catch (e: Exception) {
            Log.w(TAG, "stage failed for $distroId: ${e.message}")
            StageOutcome(false, "Staging failed: ${e.message ?: "unknown error"}")
        }
    }

    /**
     * Apply to a running desktop (the fix for "staged but the desktop is already
     * up"). Never throws; falls back to the honest next-session message.
     */
    private fun liveApply(
        app: Context,
        distroId: String,
        profile: DistroInstallProfile?,
        isChroot: Boolean,
        staged: StageOutcome,
        onLog: (String) -> Unit
    ): StageOutcome {
        val session = AppearanceLiveApply.sessionFor(app, distroId) ?: return staged
        if (session.type != DesktopSession.Type.XFCE4) {
            onLog("a KDE desktop is running — XFCE appearance applies when XFCE starts")
            return staged.copy(
                desktopRunning = true,
                hint = "A KDE desktop is running — the XFCE look appears when the XFCE desktop starts."
            )
        }
        val outcome = try {
            if (isChroot) {
                val path = profile?.chrootPath?.takeIf { it.isNotBlank() }
                    ?: ChrootPaths.pathForDistro(distroId)
                AppearanceLiveApply.applyChroot(app, path)
            } else {
                val name = profile?.prootName?.takeIf { it.isNotBlank() } ?: prootNameFor(distroId)
                AppearanceLiveApply.applyProot(app, name)
            }
        } catch (e: Exception) {
            Log.w(TAG, "live apply failed for $distroId: ${e.message}")
            AppearanceLiveApply.Outcome(AppearanceLiveApply.State.FAILED, e.message)
        }
        return when (outcome.state) {
            AppearanceLiveApply.State.APPLIED -> {
                onLog("live apply ok (${if (isChroot) "chroot" else "proot"})")
                staged.copy(
                    message = "Applied — desktop updated live.",
                    live = true,
                    desktopRunning = true
                )
            }
            else -> {
                onLog("live apply failed: ${outcome.detail ?: "unknown"}")
                staged.copy(
                    desktopRunning = true,
                    hint = "The running desktop could not be updated live — " +
                        "close and reopen the desktop to see the new look."
                )
            }
        }
    }

    private data class StagedFile(val src: File, val guestName: String)

    /** Media files to stage + the dimmed wallpaper variant when one was built. */
    private data class MediaPlan(
        val files: List<StagedFile>,
        val dimmedWallpaper: String? = null
    )

    /**
     * Materialize every media file the config needs into the cache dir.
     * Missing custom media is skipped (the guest keeps the previous/default).
     * Custom wallpapers with dim > 0 are pre-processed here (host-side);
     * asset presets are never dimmed (documented in the editor).
     */
    private fun collectMedia(app: Context, cfg: Config, onLog: (String) -> Unit): MediaPlan {
        val cache = File(app.cacheDir, "folio_appearance").also { it.mkdirs() }
        val out = mutableListOf<StagedFile>()
        var dimmed: String? = null

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
                if (src == null) {
                    onLog("custom wallpaper missing — keeping previous")
                } else {
                    val dimmedFile = if (cfg.wallpaperDim > 0) {
                        prepareDimmedWallpaper(app, src, cfg.wallpaperDim, cache, onLog)
                    } else {
                        null
                    }
                    if (dimmedFile != null) {
                        out.add(StagedFile(dimmedFile, DIMMED_WALLPAPER_NAME))
                        dimmed = DIMMED_WALLPAPER_NAME
                    } else {
                        out.add(StagedFile(src, name))
                    }
                }
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
        return MediaPlan(out, dimmed)
    }

    /**
     * Decode the picked custom wallpaper, darken it by [dim] percent and write a
     * PNG into the staging cache. Returns the file (guest name is fixed) or null
     * when the image cannot be decoded — the caller then stages the original.
     */
    private fun prepareDimmedWallpaper(
        app: Context,
        src: File,
        dim: Int,
        cache: File,
        onLog: (String) -> Unit
    ): File? {
        val dest = File(cache, DIMMED_WALLPAPER_NAME)
        val ok = try {
            writeDimmedPng(src, dest, dim)
        } catch (e: Exception) {
            Log.w(TAG, "wallpaper dim failed: ${e.message}")
            false
        }
        return if (ok && dest.isFile && dest.length() > 0L) {
            onLog("wallpaper dim ${dim}% pre-processed")
            dest
        } else {
            dest.delete()
            onLog("wallpaper dim ${dim}% failed — using the original image")
            null
        }
    }

    /** Bitmap decode (≤4096 px long edge) → darken → PNG. Returns true on success. */
    private fun writeDimmedPng(src: File, dest: File, dim: Int): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        var sample = 1
        while (bounds.outWidth / sample > 4096 || bounds.outHeight / sample > 4096) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeFile(src.absolutePath, opts) ?: return false
        val factor = (100 - dim.coerceIn(0, 100)) / 100f
        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(
                ColorMatrix(
                    floatArrayOf(
                        factor, 0f, 0f, 0f, 0f,
                        0f, factor, 0f, 0f, 0f,
                        0f, 0f, factor, 0f, 0f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }
        Canvas(out).drawBitmap(bitmap, 0f, 0f, paint)
        var ok = false
        try {
            FileOutputStream(dest).use { sink ->
                ok = out.compress(Bitmap.CompressFormat.PNG, 100, sink)
            }
        } finally {
            bitmap.recycle()
            out.recycle()
        }
        return ok
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
        // Login-time safety net (runs the hash-guarded applier at desktop start).
        val autostartDir = File(home, ".config/autostart").also { it.mkdirs() }
        try {
            File(autostartDir, GUEST_AUTOSTART_FILE).writeText(autostartEntry())
        } catch (e: Exception) {
            onLog("autostart entry write failed: ${e.message}")
        }
        // Force one apply pass on the next guest shell (cheap; hash guard anyway).
        File(cfgDir, GUEST_MARKER).delete()
        onLog("staged ${files.size} file(s) into $prootName rootfs")
        return StageOutcome(true, "Applied — appears when the desktop next starts.")
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
        val autostartFile = File(cache, GUEST_AUTOSTART_FILE).also { it.writeText(autostartEntry()) }

        val homeDir = "$path$GUEST_HOME"
        val cfgDir = "$path$GUEST_CFG"
        val shareDir = "$path$GUEST_SHARE"
        val autostartDir = "$path$GUEST_AUTOSTART_DIR"
        val cmd = buildString {
            append("if [ ! -d '$homeDir' ]; then echo FOLIO_APPEARANCE_NO_HOME; exit 0; fi; ")
            append("mkdir -p '$cfgDir' '$shareDir' '$autostartDir' 2>/dev/null || true; ")
            append("rm -f '$shareDir'/wallpaper-* '$shareDir'/wallpaper.* '$shareDir'/video-wallpaper.* 2>/dev/null || true; ")
            for (sf in files) {
                append("cp -f '${sf.src.absolutePath}' '$shareDir/${sf.guestName}' 2>/dev/null || true; ")
            }
            append("cp -f '${envFile.absolutePath}' '$cfgDir/$GUEST_ENV_FILE' 2>/dev/null || true; ")
            append("cp -f '${scriptFile.absolutePath}' '$cfgDir/$GUEST_APPLY_SCRIPT' 2>/dev/null || true; ")
            append("cp -f '${autostartFile.absolutePath}' '$autostartDir/$GUEST_AUTOSTART_FILE' 2>/dev/null || true; ")
            append("chmod 644 '$cfgDir/$GUEST_ENV_FILE' '$cfgDir/$GUEST_APPLY_SCRIPT' '$autostartDir/$GUEST_AUTOSTART_FILE' 2>/dev/null || true; ")
            append("rm -f '$cfgDir/$GUEST_MARKER' 2>/dev/null || true; ")
            append("chown -R --reference='$homeDir' '$cfgDir' '$shareDir' 2>/dev/null || true; ")
            append("chown --reference='$homeDir' '$autostartDir/$GUEST_AUTOSTART_FILE' 2>/dev/null || true; ")
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
        // When the desktop for this distro is running, [liveApply] kicks the
        // desktop so the change is visible right away.
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
            if (applied) "Applied — appears when the desktop next starts."
            else "Staged for the rooted distro — applies on next session."
        )
    }

    /** Login-time safety net entry written into the guest (idempotent). */
    internal fun autostartEntry(): String =
        "[Desktop Entry]\n" +
            "Type=Application\n" +
            "Version=1.0\n" +
            "Name=Bliss appearance\n" +
            "Comment=Applies your Bliss wallpaper, colors and terminal settings at login.\n" +
            "Exec=sh $GUEST_CFG/$GUEST_APPLY_SCRIPT\n" +
            "Terminal=false\n" +
            "StartupNotify=false\n" +
            "OnlyShowIn=XFCE;\n" +
            "X-GNOME-Autostart-enabled=true\n"

    private fun readApplyScript(app: Context): String =
        app.assets.open(APPLY_ASSET).use { it.readBytes() }.toString(Charsets.UTF_8)

    private fun prootNameFor(distroId: String): String = when (distroId) {
        "alpine", "alpine_chroot" -> "alpine"
        "debian", "debian13_chroot", "debian_chroot" -> "debian"
        else -> distroId.removeSuffix("_chroot").ifBlank { "debian" }
    }
}

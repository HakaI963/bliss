package com.ivarna.fluxlinux.core.banner

import android.content.Context
import com.ivarna.fluxlinux.core.utils.TerminalPreferences
import java.io.File

/** One launch-sticker design: file name (with `.txt`) + literal `\033` text. */
data class Art(val name: String, val text: String)

/**
 * Launch-sticker art sources.
 *
 * * **SFW** set: APK assets `banner/l<quality>/` (quality L1 24 cols …
 *   L5 54 cols, 49 designs per level).
 * * **NSFW** set: rendered designs cached by the app at
 *   `ctx.cacheDir/banner-nsfw/l<quality>/` (empty until fetched).
 *
 * [active] picks the set — NSFW replaces SFW only when the pref is on **and**
 * the cache has designs — and the revision string stored in the guest's
 * `~/.config/bliss/banner/.banner-rev`; the guest `banner.sh` prints one
 * random design per interactive shell.
 */
object BannerAssets {

    /** Launch-sticker art revision (`~/.config/bliss/banner/.banner-rev`). */
    const val REV = "7"

    private const val QUALITY_MIN = 1
    private const val QUALITY_MAX = 5
    private const val LEVEL_MIN = 1
    private const val LEVEL_MAX = 4

    private const val ASSET_ROOT = "banner"

    /** SFW designs from assets `banner/l<quality>/`, sorted by file name. */
    fun sfw(ctx: Context, quality: Int): List<Art> {
        val q = quality.coerceIn(QUALITY_MIN, QUALITY_MAX)
        val dir = "$ASSET_ROOT/l$q"
        return try {
            ctx.assets.list(dir)
                ?.filter { it.endsWith(".txt") }
                ?.sorted()
                ?.mapNotNull { name -> readAsset(ctx, "$dir/$name")?.let { Art(name, it) } }
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Rendered NSFW designs from `ctx.cacheDir/banner-nsfw/l<quality>/`,
     * sorted by file name. Empty list when the dir is missing/unreadable.
     */
    fun nsfw(ctx: Context, quality: Int): List<Art> {
        val q = quality.coerceIn(QUALITY_MIN, QUALITY_MAX)
        return try {
            val dir = File(ctx.cacheDir, "banner-nsfw/l$q")
            dir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".txt") }
                ?.sortedBy { it.name }
                ?.mapNotNull { f ->
                    try {
                        Art(f.name, f.readText())
                    } catch (_: Exception) {
                        null
                    }
                }
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Active art set + revision marker. NSFW wins when enabled **and** its
     * cache set is non-empty; otherwise (and when the asset set is empty) the
     * SFW assets are used.
     */
    fun active(ctx: Context): Pair<String, List<Art>> {
        val quality = TerminalPreferences.getBannerQuality(ctx)
            .coerceIn(QUALITY_MIN, QUALITY_MAX)
        if (TerminalPreferences.isNsfwEnabled(ctx)) {
            val level = TerminalPreferences.getNsfwLevel(ctx).coerceIn(LEVEL_MIN, LEVEL_MAX)
            val nsfwArts = nsfw(ctx, quality)
            if (nsfwArts.isNotEmpty()) {
                return "$REV-n$level-l$quality" to nsfwArts
            }
        }
        return "$REV-l$quality" to sfw(ctx, quality)
    }

    /** `banner.sh` payload (the POSIX sticker); empty string when missing. */
    fun bannerSh(ctx: Context): String =
        readAsset(ctx, "$ASSET_ROOT/banner.sh") ?: ""

    private fun readAsset(ctx: Context, path: String): String? = try {
        ctx.assets.open(path).bufferedReader().use { it.readText() }
    } catch (_: Exception) {
        null
    }
}

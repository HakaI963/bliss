package com.ivarna.fluxlinux.core.banner

import android.content.Context
import com.ivarna.fluxlinux.core.utils.TerminalPreferences

/** One launch-sticker design: file name (with `.txt`) + literal `\033` text. */
data class Art(val name: String, val text: String)

/**
 * Launch-sticker art sources.
 *
 * * **SFW** set: APK assets `banner/l<quality>/` (quality L1 24 cols …
 *   L5 54 cols, 49 designs per level).
 * * **NSFW** set: APK assets `banner/nsfw/l<level>/l<quality>/` — hardcoded,
 *   rendered offline, no network at runtime (level 1 minimal … level 4 full).
 *
 * [active] picks the set — NSFW replaces SFW only when the pref is on — and
 * the revision string stored in the guest's `~/.config/bliss/banner/.banner-rev`;
 * the guest `banner.sh` prints one random design per interactive shell.
 */
object BannerAssets {

    /** Launch-sticker art revision (`~/.config/bliss/banner/.banner-rev`). */
    const val REV = "8"

    private const val QUALITY_MIN = 1
    private const val QUALITY_MAX = 5
    private const val LEVEL_MIN = 1
    private const val LEVEL_MAX = 4

    private const val ASSET_ROOT = "banner"

    /** SFW designs from assets `banner/l<quality>/`, sorted by file name. */
    fun sfw(ctx: Context, quality: Int): List<Art> {
        val q = quality.coerceIn(QUALITY_MIN, QUALITY_MAX)
        return listAssets(ctx, "$ASSET_ROOT/l$q")
    }

    /**
     * Hardcoded NSFW designs from assets `banner/nsfw/l<level>/l<quality>/`,
     * sorted by file name. Empty list when the level dir is missing.
     */
    fun nsfw(ctx: Context, level: Int, quality: Int): List<Art> {
        val l = level.coerceIn(LEVEL_MIN, LEVEL_MAX)
        val q = quality.coerceIn(QUALITY_MIN, QUALITY_MAX)
        return listAssets(ctx, "$ASSET_ROOT/nsfw/l$l/l$q")
    }

    /**
     * Active art set + revision marker. NSFW wins when enabled **and** its
     * asset set is non-empty; otherwise (and when the SFW set is empty) the
     * SFW assets are used.
     */
    fun active(ctx: Context): Pair<String, List<Art>> {
        val quality = TerminalPreferences.getBannerQuality(ctx)
            .coerceIn(QUALITY_MIN, QUALITY_MAX)
        if (TerminalPreferences.isNsfwEnabled(ctx)) {
            val level = TerminalPreferences.getNsfwLevel(ctx).coerceIn(LEVEL_MIN, LEVEL_MAX)
            val nsfwArts = nsfw(ctx, level, quality)
            if (nsfwArts.isNotEmpty()) {
                return "$REV-n$level-l$quality" to nsfwArts
            }
        }
        return "$REV-l$quality" to sfw(ctx, quality)
    }

    /** `banner.sh` payload (the POSIX sticker); empty string when missing. */
    fun bannerSh(ctx: Context): String =
        readAsset(ctx, "$ASSET_ROOT/banner.sh") ?: ""

    private fun listAssets(ctx: Context, dir: String): List<Art> = try {
        ctx.assets.list(dir)
            ?.filter { it.endsWith(".txt") }
            ?.sorted()
            ?.mapNotNull { name -> readAsset(ctx, "$dir/$name")?.let { Art(name, it) } }
            ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    private fun readAsset(ctx: Context, path: String): String? = try {
        ctx.assets.open(path).bufferedReader().use { it.readText() }
    } catch (_: Exception) {
        null
    }
}

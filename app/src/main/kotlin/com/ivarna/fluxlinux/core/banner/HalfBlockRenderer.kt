package com.ivarna.fluxlinux.core.banner

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Standalone half-block truecolor ANSI art renderer for the optional NSFW
 * banner ([NsfwArtFetcher] renders fetched images through this).
 *
 * Pipeline: decode -> center-crop square -> scale to `cols x cols` px ->
 * [renderPixels] (alpha flatten -> border flood-fill background knock-out ->
 * dark-frame brightness boost -> trim black border cells -> grouped SGR runs).
 *
 * Escape sequences are written as the LITERAL four-character text `\033`
 * (backslash, zero, three, three) — never a raw ESC byte — because the guest
 * banner helpers render the files with `printf '%b'`.
 *
 * No project dependencies on purpose: the pure-emission half of this file is
 * mirrored by a throwaway Python port for off-device verification (the app has
 * no Android runtime in CI).
 */
object HalfBlockRenderer {

    /** UTF-8 byte budget for one rendered art; bigger frames retry at 80% cols. */
    private const val MAX_TEXT_BYTES = 45 * 1024

    /** Long-side pixel cap used while decoding huge photos. */
    private const val MAX_DECODE_PX = 1024

    /** Border flood-fill tolerance: euclidean RGB distance from the seed colour. */
    private const val KNOCK_TOLERANCE = 40

    /** Frames whose mean non-black luminance is below this get a x1.5 boost. */
    private const val DARK_FRAME_LUMA = 24

    /** 2 px is one cell row — never shrink the art to nothing. */
    private const val MIN_COLS = 2

    /** Opaque black: the knocked-out background colour (0xFF000000 as Int). */
    private const val OPAQUE_BLACK = -0x1000000

    /**
     * Sentinel for "no colour emitted on this line yet". Safe because every
     * stored pixel is forced opaque (alpha 0xFF) before emission, so no real
     * packed colour can equal [Int.MIN_VALUE].
     */
    private const val NO_COLOR = Int.MIN_VALUE

    /**
     * Decode [bytes] with [BitmapFactory] (PNG / JPEG / WEBP / GIF — animated
     * GIFs decode their first frame automatically), center-crop to a square,
     * scale to `cols x cols` px with a high-quality filter and return the
     * half-block ANSI text. Null when the bytes cannot be decoded.
     *
     * When the emitted text exceeds [MAX_TEXT_BYTES] the frame is re-rendered
     * at 80% of the previous column count until it fits.
     */
    fun render(bytes: ByteArray, cols: Int): String? {
        if (bytes.isEmpty()) return null
        return try {
            val square = decodeSquare(bytes) ?: return null
            var current = cols.coerceAtLeast(MIN_COLS)
            var text = renderScaled(square, current)
            while (text.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES && current > MIN_COLS) {
                current = (current * 4 / 5).coerceAtLeast(MIN_COLS)
                text = renderScaled(square, current)
            }
            square.recycle()
            text
        } catch (t: Throwable) {
            // Decode/scale failures must not break the fetch pipeline.
            null
        }
    }

    /** Decode + downsample (<= [MAX_DECODE_PX] long side) + center-crop to square. */
    private fun decodeSquare(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        val longSide = maxOf(bounds.outWidth, bounds.outHeight)
        while (longSide / sample > MAX_DECODE_PX) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        var work = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        val decodedLongSide = maxOf(work.width, work.height)
        if (decodedLongSide > MAX_DECODE_PX) {
            // Belt and braces for decoders that ignore inSampleSize (some GIFs).
            val scaled = Bitmap.createScaledBitmap(
                work,
                (work.width.toLong() * MAX_DECODE_PX / decodedLongSide).toInt().coerceAtLeast(1),
                (work.height.toLong() * MAX_DECODE_PX / decodedLongSide).toInt().coerceAtLeast(1),
                true
            )
            if (scaled !== work) work.recycle()
            work = scaled
        }
        val side = minOf(work.width, work.height)
        if (side <= 0) {
            work.recycle()
            return null
        }
        val square = Bitmap.createBitmap(
            work,
            (work.width - side) / 2,
            (work.height - side) / 2,
            side,
            side
        )
        if (square !== work) work.recycle()
        return square
    }

    /** Scale the square frame to `cols x cols` px (filtered) and render it. */
    private fun renderScaled(square: Bitmap, cols: Int): String {
        val scaled = Bitmap.createScaledBitmap(square, cols, cols, true)
        val pixels = IntArray(cols * cols)
        scaled.getPixels(pixels, 0, cols, 0, 0, cols, cols)
        if (scaled !== square) scaled.recycle()
        return renderPixels(pixels, cols, cols)
    }

    /**
     * Render an ARGB pixel buffer (`width x height` px, one half-block cell per
     * pixel column and per two pixel rows) as literal-`\033` truecolor ANSI:
     * `U+2580` with the top pixel as foreground and the bottom pixel as
     * background; equal top/bottom emits a space with only the background.
     * SGR sequences are emitted only when the corresponding colour changes
     * and every line ends with `\033[0m`.
     *
     * The input array is copied, not modified.
     */
    fun renderPixels(pixels: IntArray, width: Int, height: Int): String {
        if (width <= 0 || height < 2 || pixels.size < width * height) return ""
        val px = pixels.copyOf(width * height)

        flattenOverBlack(px)
        knockOutBackground(px, width, height, KNOCK_TOLERANCE)
        brightenDarkFrame(px)

        val cellRows = height / 2
        val rowIsBlack = BooleanArray(cellRows) { row -> isBlackRow(px, width, row) }
        var firstRow = 0
        while (firstRow < cellRows - 1 && rowIsBlack[firstRow]) firstRow++
        var lastRow = cellRows - 1
        while (lastRow > firstRow && rowIsBlack[lastRow]) lastRow--

        val colIsBlack = BooleanArray(width) { col -> isBlackCol(px, width, col, firstRow, lastRow) }
        var firstCol = 0
        while (firstCol < width - 1 && colIsBlack[firstCol]) firstCol++
        var lastCol = width - 1
        while (lastCol > firstCol && colIsBlack[lastCol]) lastCol--

        val sb = StringBuilder((lastCol - firstCol + 1) * (lastRow - firstRow + 1) * 12)
        for (row in firstRow..lastRow) {
            var lastFg = NO_COLOR
            var lastBg = NO_COLOR
            val topRow = (2 * row) * width
            val bottomRow = topRow + width
            for (col in firstCol..lastCol) {
                val top = px[topRow + col]
                val bottom = px[bottomRow + col]
                if (top == bottom) {
                    if (bottom != lastBg) {
                        appendBackground(sb, bottom)
                        lastBg = bottom
                    }
                    sb.append(' ')
                } else {
                    if (top != lastFg) {
                        appendForeground(sb, top)
                        lastFg = top
                    }
                    if (bottom != lastBg) {
                        appendBackground(sb, bottom)
                        lastBg = bottom
                    }
                    sb.append('\u2580')
                }
            }
            sb.append("\\033[0m").append('\n')
        }
        return sb.toString()
    }

    /** Composite semi-transparent pixels over black and force alpha 0xFF. */
    private fun flattenOverBlack(px: IntArray) {
        for (i in px.indices) {
            val c = px[i]
            val a = c ushr 24
            if (a == 0xFF) continue
            val r = ((c shr 16) and 0xFF) * a / 255
            val g = ((c shr 8) and 0xFF) * a / 255
            val b = (c and 0xFF) * a / 255
            px[i] = OPAQUE_BLACK or (r shl 16) or (g shl 8) or b
        }
    }

    /**
     * Iterative border flood fill (4-connected; explicit stack, no recursion):
     * every border pixel seeds a fill whose pixels within [tolerance]
     * (euclidean RGB distance) become opaque black.
     */
    private fun knockOutBackground(px: IntArray, width: Int, height: Int, tolerance: Int) {
        val count = width * height
        val knocked = BooleanArray(count)
        val marked = IntArray(count)
        val stack = IntArray(count)
        val tolerance2 = tolerance.toLong() * tolerance
        var generation = 0

        fun flood(startX: Int, startY: Int) {
            val start = startY * width + startX
            if (knocked[start]) return
            val seed = px[start]
            val seedR = (seed shr 16) and 0xFF
            val seedG = (seed shr 8) and 0xFF
            val seedB = seed and 0xFF
            generation++
            var sp = 0
            stack[sp++] = start
            marked[start] = generation
            while (sp > 0) {
                val i = stack[--sp]
                if (knocked[i]) continue
                val c = px[i]
                val dr = ((c shr 16) and 0xFF) - seedR
                val dg = ((c shr 8) and 0xFF) - seedG
                val db = (c and 0xFF) - seedB
                if (dr.toLong() * dr + dg.toLong() * dg + db.toLong() * db > tolerance2) continue
                knocked[i] = true
                px[i] = OPAQUE_BLACK
                val x = i % width
                val y = i / width
                if (x > 0) {
                    val j = i - 1
                    if (!knocked[j] && marked[j] != generation) {
                        marked[j] = generation
                        stack[sp++] = j
                    }
                }
                if (x < width - 1) {
                    val j = i + 1
                    if (!knocked[j] && marked[j] != generation) {
                        marked[j] = generation
                        stack[sp++] = j
                    }
                }
                if (y > 0) {
                    val j = i - width
                    if (!knocked[j] && marked[j] != generation) {
                        marked[j] = generation
                        stack[sp++] = j
                    }
                }
                if (y < height - 1) {
                    val j = i + width
                    if (!knocked[j] && marked[j] != generation) {
                        marked[j] = generation
                        stack[sp++] = j
                    }
                }
            }
        }

        for (x in 0 until width) {
            flood(x, 0)
            flood(x, height - 1)
        }
        for (y in 1 until height - 1) {
            flood(0, y)
            flood(width - 1, y)
        }
    }

    /** If the mean luminance of non-black pixels is < 24, scale RGB by 1.5. */
    private fun brightenDarkFrame(px: IntArray) {
        var sum = 0L
        var count = 0
        for (c in px) {
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            if ((r or g or b) == 0) continue
            sum += (299 * r + 587 * g + 114 * b) / 1000
            count++
        }
        if (count == 0 || sum / count >= DARK_FRAME_LUMA) return
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            if ((r or g or b) == 0) continue
            px[i] = OPAQUE_BLACK or
                (minOf(255, r * 3 / 2) shl 16) or
                (minOf(255, g * 3 / 2) shl 8) or
                minOf(255, b * 3 / 2)
        }
    }

    /** True when an entire cell row (top + bottom pixel) is opaque black. */
    private fun isBlackRow(px: IntArray, width: Int, row: Int): Boolean {
        val topRow = (2 * row) * width
        val bottomRow = topRow + width
        for (x in 0 until width) {
            if (px[topRow + x] != OPAQUE_BLACK || px[bottomRow + x] != OPAQUE_BLACK) return false
        }
        return true
    }

    /** True when a cell column is opaque black across the kept rows. */
    private fun isBlackCol(px: IntArray, width: Int, col: Int, firstRow: Int, lastRow: Int): Boolean {
        for (row in firstRow..lastRow) {
            if (px[(2 * row) * width + col] != OPAQUE_BLACK ||
                px[(2 * row + 1) * width + col] != OPAQUE_BLACK
            ) {
                return false
            }
        }
        return true
    }

    private fun appendForeground(sb: StringBuilder, color: Int) {
        sb.append("\\033[38;2;")
        appendRgb(sb, color)
    }

    private fun appendBackground(sb: StringBuilder, color: Int) {
        sb.append("\\033[48;2;")
        appendRgb(sb, color)
    }

    private fun appendRgb(sb: StringBuilder, color: Int) {
        sb.append((color shr 16) and 0xFF).append(';')
            .append((color shr 8) and 0xFF).append(';')
            .append(color and 0xFF).append('m')
    }
}

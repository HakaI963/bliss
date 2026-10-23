package com.altaris.linux.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.altaris.linux.ui.theme.Prism
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Decode a picked photo into a software bitmap capped at ~2048 px. Keeping the
 * decode bounded makes the crop screen responsive even for a 50 MP camera
 * shot, and ImageDecoder applies the EXIF rotation on API 28+.
 */
fun decodeCustomArtwork(context: Context, uri: Uri): Bitmap? = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val width = info.size.width
            val height = info.size.height
            val longest = max(width, height).toFloat()
            if (longest > 2048f) {
                val scale = 2048f / longest
                decoder.setTargetSize(
                    (width * scale).roundToInt().coerceAtLeast(1),
                    (height * scale).roundToInt().coerceAtLeast(1),
                )
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2048) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }
}.getOrNull()

/**
 * Full-screen crop sheet for the home banner. The viewport is locked to
 * [Prism.HeroAspect] (the exact HomeHero box); pinch to zoom, drag to choose
 * which part of the photo fills it, then Use writes a 1600 px wide crop.
 */
@Composable
fun CustomArtworkCropDialog(
    source: Bitmap,
    onDismiss: () -> Unit,
    onCropped: (Bitmap) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Prism.PanelRadius)
    val sourceImage = remember(source) { source.asImageBitmap() }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val baseScale = if (viewport.width == 0 || viewport.height == 0) {
        1f
    } else {
        max(
            viewport.width.toFloat() / source.width,
            viewport.height.toFloat() / source.height,
        )
    }
    // The transformable lambda is remembered once; read the live viewport and
    // scale through updated states so the first gesture after layout is right.
    val latestViewport = rememberUpdatedState(viewport)
    val latestBaseScale = rememberUpdatedState(baseScale)
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val vp = latestViewport.value
        val base = latestBaseScale.value
        val newZoom = (zoom * zoomChange).coerceIn(1f, 6f)
        val total = base * newZoom
        val maxX = ((source.width * total - vp.width) / 2f).coerceAtLeast(0f)
        val maxY = ((source.height * total - vp.height) / 2f).coerceAtLeast(0f)
        zoom = newZoom
        offset = Offset(
            (offset.x + panChange.x).coerceIn(-maxX, maxX),
            (offset.y + panChange.y).coerceIn(-maxY, maxY),
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(scheme.background)
                .systemBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = scheme.onSurface, fontSize = 14.sp)
                }
                Text(
                    text = "Crop artwork",
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface,
                )
                TextButton(
                    onClick = {
                        if (viewport.width > 0 && viewport.height > 0) {
                            onCropped(cropToHero(source, viewport, baseScale, zoom, offset))
                        }
                    },
                    enabled = viewport.width > 0 && viewport.height > 0,
                ) {
                    Text(
                        "Use",
                        color = if (viewport.width > 0) scheme.onSurface else scheme.onSurfaceVariant,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(Prism.HeroAspect)
                        .clip(shape)
                        .background(Color.Black)
                        .onSizeChanged { viewport = it }
                        .transformable(transformState),
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        if (viewport.width == 0 || viewport.height == 0) return@Canvas
                        val total = baseScale * zoom
                        val drawWidth = source.width * total
                        val drawHeight = source.height * total
                        val left = (size.width - drawWidth) / 2f + offset.x
                        val top = (size.height - drawHeight) / 2f + offset.y
                        drawImage(
                            image = sourceImage,
                            srcOffset = IntOffset.Zero,
                            srcSize = IntSize(source.width, source.height),
                            dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                            dstSize = IntSize(
                                drawWidth.roundToInt().coerceAtLeast(1),
                                drawHeight.roundToInt().coerceAtLeast(1),
                            ),
                            filterQuality = FilterQuality.High,
                        )
                        val hairline = 1.dp.toPx()
                        drawRect(
                            color = Color.White.copy(alpha = 0.55f),
                            style = Stroke(hairline),
                        )
                        val guide = Color.White.copy(alpha = 0.22f)
                        for (i in 1..2) {
                            val x = size.width * i / 3f
                            val y = size.height * i / 3f
                            drawLine(guide, Offset(x, 0f), Offset(x, size.height), hairline)
                            drawLine(guide, Offset(0f, y), Offset(size.width, y), hairline)
                        }
                    }
                }
            }

            Text(
                text = "Pinch to zoom · drag to reposition",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp),
                textAlign = TextAlign.Center,
                fontSize = 11.sp,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Map the on-screen viewport back to source pixels. The crop viewport is
 * always exactly the HomeHero aspect, so the returned bitmap can be drawn with
 * ContentScale.Crop and nothing important is lost.
 */
private fun cropToHero(
    source: Bitmap,
    viewport: IntSize,
    baseScale: Float,
    zoom: Float,
    offset: Offset,
): Bitmap {
    val total = (baseScale * zoom).coerceAtLeast(0.0001f)
    val srcWidth = (viewport.width / total).roundToInt().coerceIn(1, source.width)
    val srcHeight = (viewport.height / total).roundToInt().coerceIn(1, source.height)
    val left = (viewport.width - source.width * total) / 2f + offset.x
    val top = (viewport.height - source.height * total) / 2f + offset.y
    val srcX = ((-left) / total).roundToInt()
        .coerceIn(0, (source.width - srcWidth).coerceAtLeast(0))
    val srcY = ((-top) / total).roundToInt()
        .coerceIn(0, (source.height - srcHeight).coerceAtLeast(0))
    val cropped = Bitmap.createBitmap(source, srcX, srcY, srcWidth, srcHeight)
    val outWidth = 1600
    val outHeight = (outWidth / Prism.HeroAspect).roundToInt()
    val scaled = Bitmap.createScaledBitmap(cropped, outWidth, outHeight, true)
    if (scaled !== cropped) cropped.recycle()
    return scaled
}

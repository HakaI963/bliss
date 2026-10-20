package com.ivarna.fluxlinux.core.control

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import com.ivarna.fluxlinux.core.clone.HeadlessCloneEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MP4 recorder for the clone virtual display. Captures JPEG frames from the
 * shell engine, converts them to YUV420 in the H.264 input Image and muxes a
 * standard MP4. Six frames per second by default; enough for UI review without
 * pinning the CPU.
 */
object CloneRecorder {

    data class State(
        val recording: Boolean = false,
        val path: String? = null,
        val frames: Int = 0,
        val fps: Int = 6,
        val error: String? = null,
    )

    val state = MutableStateFlow(State())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun start(context: Context, fps: Int = 6, maxWidth: Int = 720): JSONObject {
        if (job != null) {
            return JSONObject().put("ok", false).put("error", "Recording already active")
                .put("path", state.value.path ?: JSONObject.NULL)
        }
        val ctx = context.applicationContext
        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "recordings").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "clone-$stamp.mp4")
        val rate = fps.coerceIn(2, 15)
        state.value = State(recording = true, path = file.absolutePath, fps = rate)
        job = scope.launch {
            var codec: MediaCodec? = null
            var muxer: MediaMuxer? = null
            var trackIndex = -1
            var muxerStarted = false
            var frameIndex = 0L
            try {
                val first = capture(maxWidth)
                    ?: throw IllegalStateException("No display frame to record")
                val size = evenSize(first.width, first.height, maxWidth)
                val width = size.first
                val height = size.second
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                    setInteger(
                        MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                    )
                    setInteger(MediaFormat.KEY_BIT_RATE, (width * height * 4).coerceIn(1_500_000, 8_000_000))
                    setInteger(MediaFormat.KEY_FRAME_RATE, rate)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                }
                val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                codec = encoder
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                encoder.start()
                val mux = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                muxer = mux
                val info = MediaCodec.BufferInfo()
                var bitmap: Bitmap? = first
                while (isActive) {
                    val frame = bitmap ?: capture(maxWidth)
                    if (frame == null) {
                        delay(1000L / rate)
                        continue
                    }
                    if (frame.width != width || frame.height != height) {
                        bitmap = Bitmap.createScaledBitmap(frame, width, height, true)
                    } else {
                        bitmap = frame
                    }
                    val inputIndex = encoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val image = encoder.getInputImage(inputIndex)
                        if (image != null) {
                            fillYuv(image, bitmap!!)
                            val pts = frameIndex * 1_000_000L / rate
                            encoder.queueInputBuffer(inputIndex, 0, 0, pts, 0)
                            frameIndex++
                        } else {
                            encoder.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                        }
                    }
                    var drained = false
                    while (true) {
                        val outIndex = encoder.dequeueOutputBuffer(info, 0)
                        if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                            break
                        } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            if (!muxerStarted) {
                                trackIndex = mux.addTrack(encoder.outputFormat)
                                mux.start()
                                muxerStarted = true
                            }
                        } else if (outIndex >= 0) {
                            val buffer = encoder.getOutputBuffer(outIndex)
                            if (buffer != null && info.size > 0 && muxerStarted) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                mux.writeSampleData(trackIndex, buffer, info)
                            }
                            encoder.releaseOutputBuffer(outIndex, false)
                            drained = true
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        }
                    }
                    state.value = state.value.copy(frames = frameIndex.toInt())
                    delay((1000L / rate).coerceAtLeast(66L))
                    if (drained) {
                        // keep the loop responsive to stop()
                    }
                }
                // End of stream
                val eosIndex = encoder.dequeueInputBuffer(20_000)
                if (eosIndex >= 0) {
                    encoder.queueInputBuffer(
                        eosIndex, 0, 0, frameIndex * 1_000_000L / rate,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                }
                var attempts = 0
                while (attempts < 100) {
                    val outIndex = encoder.dequeueOutputBuffer(info, 10_000)
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (!muxerStarted) {
                            trackIndex = mux.addTrack(encoder.outputFormat)
                            mux.start()
                            muxerStarted = true
                        }
                    } else if (outIndex >= 0) {
                        val buffer = encoder.getOutputBuffer(outIndex)
                        if (buffer != null && info.size > 0 && muxerStarted) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            mux.writeSampleData(trackIndex, buffer, info)
                        }
                        encoder.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                    attempts++
                }
                state.value = state.value.copy(recording = false, path = file.absolutePath)
            } catch (t: Throwable) {
                state.value = state.value.copy(
                    recording = false,
                    error = t.message ?: t.javaClass.simpleName,
                    path = file.absolutePath,
                )
            } finally {
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                runCatching { if (muxerStarted) muxer?.stop() }
                runCatching { muxer?.release() }
                state.value = state.value.copy(recording = false)
                job = null
            }
        }
        return JSONObject()
            .put("ok", true)
            .put("path", file.absolutePath)
            .put("fps", rate)
    }

    fun stop(): JSONObject {
        val current = state.value
        if (job == null) {
            return JSONObject().put("ok", false).put("error", "No recording active")
                .put("path", current.path ?: JSONObject.NULL)
        }
        job?.cancel()
        state.value = state.value.copy(recording = false)
        return JSONObject().put("ok", true).put("path", current.path ?: JSONObject.NULL)
    }

    fun status(): JSONObject = JSONObject()
        .put("ok", true)
        .put("recording", state.value.recording)
        .put("path", state.value.path ?: JSONObject.NULL)
        .put("frames", state.value.frames)
        .put("fps", state.value.fps)
        .put("error", state.value.error ?: JSONObject.NULL)

    private suspend fun capture(maxWidth: Int): Bitmap? {
        val bytes = HeadlessCloneEngine.apiCapture(maxWidth, 88) ?: return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun evenSize(width: Int, height: Int, maxWidth: Int): Pair<Int, Int> {
        val scale = if (width > maxWidth) maxWidth.toFloat() / width else 1f
        var w = (width * scale).toInt().coerceAtLeast(160)
        var h = (height * scale).toInt().coerceAtLeast(160)
        if (w % 2 != 0) w--
        if (h % 2 != 0) h--
        return w to h
    }

    /** RGB bitmap → I420 into the codec's writable Image planes. */
    private fun fillYuv(image: android.media.Image, bitmap: Bitmap) {
        val width = image.width
        val height = image.height
        val scaled = if (bitmap.width == width && bitmap.height == height) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        }
        val argb = IntArray(width * height)
        scaled.getPixels(argb, 0, width, 0, 0, width, height)
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val yRow = yPlane.rowStride
        val yPix = yPlane.pixelStride
        val uRow = uPlane.rowStride
        val uPix = uPlane.pixelStride
        val vRow = vPlane.rowStride
        val vPix = vPlane.pixelStride
        for (y in 0 until height) {
            for (x in 0 until width) {
                val c = argb[y * width + x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val yv = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yBuffer.put(y * yRow + x * yPix, yv.coerceIn(0, 255).toByte())
            }
        }
        val uvWidth = (width + 1) / 2
        val uvHeight = (height + 1) / 2
        for (y in 0 until uvHeight) {
            for (x in 0 until uvWidth) {
                var r = 0
                var g = 0
                var b = 0
                var count = 0
                for (dy in 0..1) {
                    for (dx in 0..1) {
                        val px = (x * 2 + dx).coerceAtMost(width - 1)
                        val py = (y * 2 + dy).coerceAtMost(height - 1)
                        val c = argb[py * width + px]
                        r += (c shr 16) and 0xFF
                        g += (c shr 8) and 0xFF
                        b += c and 0xFF
                        count++
                    }
                }
                r /= count
                g /= count
                b /= count
                val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                val uIndex = y * uRow + x * uPix
                val vIndex = y * vRow + x * vPix
                if (uIndex < uBuffer.limit()) uBuffer.put(uIndex, u.coerceIn(0, 255).toByte())
                if (vIndex < vBuffer.limit()) vBuffer.put(vIndex, v.coerceIn(0, 255).toByte())
            }
        }
        if (scaled !== bitmap) scaled.recycle()
    }
}

package com.ivarna.fluxlinux.clone;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.Surface;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;

/**
 * The clone display, owned by the shell (uid 2000) process.
 *
 * ShadowAuto's production model, ported exactly: the display is created with
 * a MediaCodec H.264 encoder input surface as its output, and the frame
 * stream is the encoder's output samples (real video, like screen recording).
 * Plain ImageReader surfaces — attached permanently or swapped in on demand —
 * never receive a single composed frame on some OEM builds; the encoder
 * surface is the path the whole screen-recording stack is validated on.
 *
 * Flags are tried as a candidate ladder (hidden SUPPORTS_TOUCH / OWN_FOCUS /
 * TRUSTED first) so the display behaves like a real secondary screen. The IME
 * policy is set to LOCAL so the clone keyboard shows up on the clone display,
 * never on the phone screen.
 */
final class DisplaySession {
    interface VideoSink {
        void onVideo(byte[] data, long ptsUs, boolean keyframe, boolean format, int width, int height);
    }

    final int displayId;
    final int width;
    final int height;
    final int dpi;

    private final MediaCodec encoder;
    private final Surface encoderSurface;
    private final VirtualDisplay display;
    private final WindowManagerBridge windowManager; // optional: may be null on locked-down builds
    private final int previousImePolicy;
    private final boolean localImeEnabled;

    private volatile VideoSink sink;
    private volatile boolean encoderRunning;
    private volatile long lastSampleAt;
    private volatile long samplesSent;
    private volatile byte[] lastConfig;
    private volatile String lastEncoderError;

    private DisplaySession(int width, int height, int dpi, MediaCodec encoder,
                           Surface encoderSurface, VirtualDisplay display) {
        this.width = width;
        this.height = height;
        this.dpi = dpi;
        this.encoder = encoder;
        this.encoderSurface = encoderSurface;
        this.display = display;
        this.displayId = display.getDisplay().getDisplayId();
        // IME policy is best-effort: a WindowManagerBridge failure must not
        // destroy a display that was just created.
        WindowManagerBridge bridge = null;
        int previous = -1;
        boolean imeEnabled = false;
        try {
            bridge = new WindowManagerBridge();
        } catch (Throwable ignored) {
        }
        if (bridge != null) {
            try {
                previous = bridge.getDisplayImePolicy(displayId);
            } catch (Throwable ignored) {
            }
            try {
                imeEnabled = bridge.setDisplayImePolicy(displayId, WindowManagerBridge.DISPLAY_IME_POLICY_LOCAL);
            } catch (Throwable ignored) {
            }
        }
        this.windowManager = bridge;
        this.previousImePolicy = previous;
        this.localImeEnabled = imeEnabled;
    }

    boolean localImeEnabled() {
        return localImeEnabled;
    }

    static DisplaySession create(android.content.Context context, int width, int height, int dpi) {
        MediaCodec encoder;
        Surface inputSurface;
        try {
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitRateFor(width, height));
            format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            // Reference-engine pacing: keep the surface producing even when
            // display content is static, and keep latency low.
            format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 150_000L);
            format.setInteger(MediaFormat.KEY_PRIORITY, 0);
            format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
            format.setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, 30f);
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            }
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            inputSurface = encoder.createInputSurface();
        } catch (Throwable t) {
            throw new IllegalStateException("encoder setup failed", t);
        }
        DisplayManager manager = displayManager(context);
        int baseFlags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
        int touchFlag = hiddenFlag("VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH");
        int focusFlag = hiddenFlag("VIRTUAL_DISPLAY_FLAG_OWN_FOCUS");
        int trustedFlag = hiddenFlag("VIRTUAL_DISPLAY_FLAG_TRUSTED");
        int[] candidates = new int[]{
                baseFlags | touchFlag | focusFlag | trustedFlag,
                baseFlags | touchFlag | focusFlag,
                baseFlags | touchFlag,
                baseFlags
        };
        Throwable lastError = null;
        for (int flags : candidates) {
            try {
                VirtualDisplay display = manager.createVirtualDisplay("FluxClone", width, height, dpi,
                        inputSurface, flags);
                if (display != null && display.getDisplay() != null) {
                    return new DisplaySession(width, height, dpi, encoder, inputSurface, display);
                }
            } catch (Throwable e) {
                lastError = e;
            }
        }
        try {
            encoder.release();
        } catch (Throwable ignored) {
        }
        throw new IllegalStateException("createVirtualDisplay failed", lastError);
    }

    private static int bitRateFor(int width, int height) {
        int bitRate = (int) (width * (long) height * 0.07d * 30d);
        return Math.max(1_000_000, Math.min(8_000_000, bitRate));
    }

    /** Starts H.264 encoding of the display output; samples go to the sink. */
    synchronized void startEncoder(final VideoSink videoSink) {
        this.sink = videoSink;
        if (encoderRunning) {
            return;
        }
        try {
            final HandlerThread thread = new HandlerThread("ShellVdEnc");
            thread.start();
            encoder.setCallback(new MediaCodec.Callback() {
                @Override
                public void onInputBufferAvailable(MediaCodec codec, int index) {
                    // Surface input mode: no client input buffers.
                }

                @Override
                public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {
                    try {
                        ByteBuffer buffer = codec.getOutputBuffer(index);
                        if (buffer != null && info.size > 0) {
                            byte[] data = new byte[info.size];
                            buffer.position(info.offset);
                            buffer.limit(info.offset + info.size);
                            buffer.get(data);
                            boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                            boolean keyframe = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                            if (config) {
                                lastConfig = data;
                            }
                            lastSampleAt = SystemClock.uptimeMillis();
                            samplesSent++;
                            VideoSink s = sink;
                            if (s != null) {
                                s.onVideo(data, info.presentationTimeUs, keyframe, config, width, height);
                            }
                        }
                    } catch (Throwable t) {
                        lastEncoderError = String.valueOf(t);
                    } finally {
                        try {
                            codec.releaseOutputBuffer(index, false);
                        } catch (Throwable ignored) {
                        }
                    }
                }

                @Override
                public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {
                }

                @Override
                public void onError(MediaCodec codec, MediaCodec.CodecException e) {
                    lastEncoderError = String.valueOf(e);
                }
            }, new Handler(thread.getLooper()));
            encoder.start();
            encoderRunning = true;
        } catch (Throwable t) {
            lastEncoderError = String.valueOf(t);
        }
    }

    /** Pauses delivery (encoder keeps running; samples are cheap when idle). */
    void pauseDelivery() {
        sink = null;
    }

    byte[] lastConfig() {
        return lastConfig;
    }

    long lastSampleAt() {
        return lastSampleAt;
    }

    long samplesSent() {
        return samplesSent;
    }

    String lastEncoderError() {
        return lastEncoderError;
    }

    void release() {
        try {
            if (previousImePolicy != -1 && windowManager != null) {
                windowManager.setDisplayImePolicy(displayId, previousImePolicy);
            }
        } catch (Throwable ignored) {
        }
        sink = null;
        try {
            encoder.stop();
        } catch (Throwable ignored) {
        }
        try {
            encoder.release();
        } catch (Throwable ignored) {
        }
        try {
            display.release();
        } catch (Throwable ignored) {
        }
        try {
            encoderSurface.release();
        } catch (Throwable ignored) {
        }
    }

    private static DisplayManager displayManager(android.content.Context context) {
        try {
            Constructor<DisplayManager> ctor = DisplayManager.class.getDeclaredConstructor(Context.class);
            ctor.setAccessible(true);
            return ctor.newInstance(context);
        } catch (Throwable ignored) {
            return (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        }
    }

    private static int hiddenFlag(String name) {
        try {
            Field field = DisplayManager.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.getInt(null);
        } catch (Throwable ignored) {
            return 0;
        }
    }
}

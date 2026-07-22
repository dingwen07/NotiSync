package com.genymobile.scrcpy.video;

import com.genymobile.scrcpy.AndroidVersions;
import com.genymobile.scrcpy.AsyncProcessor;
import com.genymobile.scrcpy.Options;
import com.genymobile.scrcpy.device.Streamer;
import com.genymobile.scrcpy.model.Codec;
import com.genymobile.scrcpy.model.ConfigurationException;
import com.genymobile.scrcpy.model.Size;
import com.genymobile.scrcpy.util.IO;
import com.genymobile.scrcpy.util.Ln;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/** Encodes the mirrored display with the exact hardware encoder selected before session start. */
public final class SurfaceEncoder implements AsyncProcessor {

    private static final int DEFAULT_I_FRAME_INTERVAL = 10;
    private static final int REPEAT_FRAME_DELAY_US = 100_000;
    private static final String KEY_MAX_FPS_TO_ENCODER = "max-fps-to-encoder";

    private final SurfaceCapture capture;
    private final Streamer streamer;
    private final String encoderName;
    private final int videoBitRate;
    private final int maxSize;
    private final float maxFps;
    private final CaptureControl captureControl;
    private final AtomicBoolean stopped = new AtomicBoolean();

    private Thread thread;

    public SurfaceEncoder(SurfaceCapture capture, Streamer streamer, Options options) {
        this(capture, streamer, options, new CaptureControl());
    }

    /** NotiSync constructor sharing authenticated viewer visibility with the control loop. */
    public SurfaceEncoder(SurfaceCapture capture, Streamer streamer, Options options, CaptureControl captureControl) {
        this.capture = capture;
        this.streamer = streamer;
        this.videoBitRate = options.getVideoBitRate();
        this.maxSize = options.getMaxSize();
        this.maxFps = options.getMaxFps();
        this.encoderName = options.getVideoEncoder();
        this.captureControl = captureControl;
    }

    private void streamCapture() throws IOException, ConfigurationException, InterruptedException {
        Codec codec = streamer.getCodec();
        MediaCodec mediaCodec = createHardwareMediaCodec(codec, encoderName);
        MediaFormat format = createFormat(codec.getMimeType(), videoBitRate, maxFps);

        MediaCodecInfo.VideoCapabilities capabilities = mediaCodec.getCodecInfo()
                .getCapabilitiesForType(codec.getMimeType())
                .getVideoCapabilities();
        int alignment = Math.max(capabilities.getWidthAlignment(), capabilities.getHeightAlignment());
        VideoConstraints constraints = new VideoConstraints(maxSize, alignment, capabilities);
        capture.init(captureControl, constraints);

        try {
            streamer.writeVideoHeader();
            boolean alive;
            do {
                int resetReasons = captureControl.consumeReset();
                if ((resetReasons & CaptureControl.RESET_REASON_TERMINATED) != 0) {
                    break;
                }
                // While the player has no video surface, release capture and codec resources but
                // leave both authenticated descriptors open. Resume always starts a new codec
                // session rather than forwarding undecodable inter frames from the old one.
                if (!captureControl.awaitVideoVisible()) {
                    break;
                }

                capture.prepare();
                Size size = capture.getSize();
                format.setInteger(MediaFormat.KEY_WIDTH, size.getWidth());
                format.setInteger(MediaFormat.KEY_HEIGHT, size.getHeight());

                Surface surface = null;
                boolean mediaCodecStarted = false;
                boolean captureStarted = false;
                try {
                    mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                    surface = mediaCodec.createInputSurface();
                    capture.start(surface);
                    captureStarted = true;
                    mediaCodec.start();
                    mediaCodecStarted = true;
                    captureControl.setRunningMediaCodec(mediaCodec);

                    if (stopped.get()) {
                        alive = false;
                    } else {
                        if (!captureControl.isResetRequested()) {
                            streamer.writeSessionMeta(size.getWidth(), size.getHeight(), false);
                            requestSyncFrame(mediaCodec);
                            encode(mediaCodec, streamer, captureControl);
                        }
                        alive = !stopped.get() && !capture.isClosed();
                    }
                } finally {
                    captureControl.setRunningMediaCodec(null);
                    if (captureStarted) {
                        capture.stop();
                    }
                    if (mediaCodecStarted) {
                        try {
                            mediaCodec.stop();
                        } catch (IllegalStateException ignored) {
                            // The codec may already have transitioned after an EOS/reset.
                        }
                    }
                    mediaCodec.reset();
                    if (surface != null) {
                        surface.release();
                    }
                }
            } while (alive);
        } finally {
            try {
                capture.release();
            } finally {
                mediaCodec.release();
            }
        }
    }

    private static MediaCodec createHardwareMediaCodec(Codec codec, String encoderName) throws IOException, ConfigurationException {
        if (encoderName == null || encoderName.isEmpty()) {
            throw new ConfigurationException("A hardware encoder name is required");
        }
        final MediaCodec mediaCodec;
        try {
            mediaCodec = MediaCodec.createByCodecName(encoderName);
        } catch (IllegalArgumentException error) {
            throw new ConfigurationException("Unknown encoder: " + encoderName);
        }

        MediaCodecInfo info = mediaCodec.getCodecInfo();
        boolean supportsMime = false;
        for (String type : info.getSupportedTypes()) {
            if (codec.getMimeType().equalsIgnoreCase(type)) {
                supportsMime = true;
                break;
            }
        }
        if (!info.isEncoder() || !info.isHardwareAccelerated() || !supportsMime) {
            mediaCodec.release();
            throw new ConfigurationException("Encoder is not an exact hardware " + codec.getName() + " encoder: " + encoderName);
        }
        Ln.d("Using hardware video encoder: '" + encoderName + "'");
        return mediaCodec;
    }

    private static MediaFormat createFormat(String mimeType, int bitRate, float maxFps) {
        MediaFormat format = new MediaFormat();
        format.setString(MediaFormat.KEY_MIME, mimeType);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 60);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        if (Build.VERSION.SDK_INT >= AndroidVersions.API_24_ANDROID_7_0) {
            format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
        }
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, DEFAULT_I_FRAME_INTERVAL);
        format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, REPEAT_FRAME_DELAY_US);
        if (Build.VERSION.SDK_INT >= AndroidVersions.API_23_ANDROID_6_0) {
            format.setInteger(MediaFormat.KEY_PRIORITY, 0);
        }
        if (Build.VERSION.SDK_INT >= AndroidVersions.API_26_ANDROID_8_0) {
            format.setInteger(MediaFormat.KEY_LATENCY, 1);
        }
        if (maxFps > 0) {
            format.setFloat(KEY_MAX_FPS_TO_ENCODER, maxFps);
        }
        return format;
    }

    private static boolean requestSyncFrame(MediaCodec codec) {
        Bundle parameters = new Bundle();
        parameters.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
        try {
            codec.setParameters(parameters);
            return true;
        } catch (RuntimeException error) {
            // A newly started encoder is already required to begin at a random-access frame. This
            // request makes resume immediate on implementations that support the standard key.
            Ln.w("Video encoder rejected sync-frame request: " + error.getMessage());
            return false;
        }
    }

    private static void encode(MediaCodec codec, Streamer streamer, CaptureControl captureControl) throws IOException {
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        boolean eos;
        do {
            int outputBufferId = codec.dequeueOutputBuffer(bufferInfo, -1);
            try {
                eos = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                if (outputBufferId >= 0 && bufferInfo.size > 0) {
                    ByteBuffer codecBuffer = codec.getOutputBuffer(outputBufferId);
                    // A hide/reset may race with buffers already queued by MediaCodec. Never write
                    // those stale frames, even if a rapid hide/show has made visibility true again;
                    // isResetRequested() remains true until the fresh encoder iteration begins.
                    if (captureControl.isVideoVisible() && !captureControl.isResetRequested()) {
                        streamer.writePacket(codecBuffer, bufferInfo);
                    }
                }
            } finally {
                if (outputBufferId >= 0) {
                    codec.releaseOutputBuffer(outputBufferId, false);
                }
            }
        } while (!eos);
    }

    @Override
    public void start(TerminationListener listener) {
        thread = new Thread(() -> {
            Looper.prepare();
            try {
                streamCapture();
            } catch (ConfigurationException error) {
                Ln.e("Video encoder configuration rejected: " + error.getMessage());
            } catch (IOException error) {
                if (!IO.isBrokenPipe(error)) {
                    Ln.e("Video encoding error", error);
                }
            } catch (RuntimeException error) {
                Ln.e("Video encoder failed", error);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                if (!stopped.get()) {
                    Ln.w("Video encoder interrupted");
                }
            } finally {
                listener.onTerminated(true);
            }
        }, "video");
        thread.start();
    }

    @Override
    public void stop() {
        if (thread != null) {
            stopped.set(true);
            captureControl.reset(CaptureControl.RESET_REASON_TERMINATED);
        }
    }

    @Override
    public void join() throws InterruptedException {
        if (thread != null) {
            thread.join();
        }
    }
}

package com.genymobile.scrcpy;

import com.genymobile.scrcpy.control.ControlChannel;
import com.genymobile.scrcpy.control.Controller;
import com.genymobile.scrcpy.device.Device;
import com.genymobile.scrcpy.device.Streamer;
import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.video.CaptureControl;
import com.genymobile.scrcpy.video.ScreenCapture;
import com.genymobile.scrcpy.video.SurfaceCapture;
import com.genymobile.scrcpy.video.SurfaceEncoder;
import com.genymobile.scrcpy.video.VideoCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.view.KeyEvent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lifecycle-safe entry point for the NotiSync Shizuku UserService.
 *
 * <p>The caller supplies private socket-pair endpoints. This class never opens a network or adb
 * socket and never receives session keys. Only the display encoder and the explicitly allowlisted
 * scrcpy control messages execute in the privileged process.</p>
 */
public final class NotiSyncCaptureBackend {

    public static final int CODEC_H264 = 1;
    public static final int CODEC_H265 = 2;
    public static final int CODEC_AV1 = 4;

    public static final int PROBE_DISPLAY_CAPTURE = 1;
    public static final int PROBE_INPUT_INJECTION = 2;

    public static final int STARTED = 0;
    public static final int BUSY = 1;
    public static final int INVALID_ARGUMENT = 2;
    public static final int ROOT_UNSUPPORTED = 3;
    public static final int CODEC_UNAVAILABLE = 4;
    public static final int BACKEND_FAILURE = 5;

    private Session session;

    public static int probeHardwareCodecs() {
        int result = 0;
        if (findHardwareEncoder(VideoCodec.H264) != null) {
            result |= CODEC_H264;
        }
        if (findHardwareEncoder(VideoCodec.H265) != null) {
            result |= CODEC_H265;
        }
        if (findHardwareEncoder(VideoCodec.AV1) != null) {
            result |= CODEC_AV1;
        }
        return result;
    }

    /**
     * Short-lived shell-context readiness probe used by the explicit Settings opt-in flow.
     * It creates no network path and forwards no pixels. Encoder availability is reported
     * separately and is always rechecked at session start.
     */
    public static int probeCapabilities() {
        if (Os.getuid() == 0) {
            return 0;
        }
        int result = 0;
        try {
            Workarounds.apply();
            if (probeDisplayCapture()) {
                result |= PROBE_DISPLAY_CAPTURE;
            }
        } catch (Throwable error) {
            Ln.w("Display capture probe failed", error);
        }
        try {
            // ACTION_UP for KEYCODE_UNKNOWN is a no-op, but InputManager still performs the
            // privileged injection permission check and reports whether it accepted the event.
            if (Device.supportsInputEvents(0)
                    && Device.injectKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_UNKNOWN, 0, 0, 0,
                    Device.INJECT_MODE_WAIT_FOR_RESULT)) {
                result |= PROBE_INPUT_INJECTION;
            }
        } catch (Throwable error) {
            Ln.w("Input injection probe failed", error);
        }
        return result;
    }

    private static boolean probeDisplayCapture() {
        // Shizuku UserService runs on an ordinary app classpath, where scrcpy's hidden static
        // DisplayManager five-argument overload is absent. Probe the exact SurfaceControl fallback
        // that sessions use instead of falsely reporting capture unavailable.
        return ScreenCapture.probePrimaryDisplayCapture();
    }

    public synchronized int startSession(String ownerToken, int codecId, int maxDimension, int maxFps, int bitrateBps,
            boolean allowControl, boolean allowClipboard, ParcelFileDescriptor videoWriteFd, ParcelFileDescriptor controlFd) {
        if (session != null) {
            closeQuietly(videoWriteFd);
            closeQuietly(controlFd);
            return BUSY;
        }
        if (!isValidOwnerToken(ownerToken) || videoWriteFd == null || controlFd == null
                || !videoWriteFd.getFileDescriptor().valid() || !controlFd.getFileDescriptor().valid()
                || maxDimension <= 0 || maxDimension > 8192
                || maxFps <= 0 || maxFps > 240
                || bitrateBps <= 0 || bitrateBps > 100_000_000) {
            closeQuietly(videoWriteFd);
            closeQuietly(controlFd);
            return INVALID_ARGUMENT;
        }
        if (Os.getuid() == 0) {
            closeQuietly(videoWriteFd);
            closeQuietly(controlFd);
            return ROOT_UNSUPPORTED;
        }

        VideoCodec codec = codecForId(codecId);
        String encoderName = codec == null ? null : findHardwareEncoder(codec);
        if (codec == null || encoderName == null) {
            closeQuietly(videoWriteFd);
            closeQuietly(controlFd);
            return CODEC_UNAVAILABLE;
        }

        ParcelFileDescriptor ownedVideo = null;
        ParcelFileDescriptor ownedControl = null;
        try {
            ownedVideo = ParcelFileDescriptor.dup(videoWriteFd.getFileDescriptor());
            ownedControl = ParcelFileDescriptor.dup(controlFd.getFileDescriptor());
        } catch (IOException | RuntimeException error) {
            closeQuietly(ownedVideo);
            closeQuietly(ownedControl);
            Ln.e("Could not duplicate NotiSync capture descriptors", error);
            return BACKEND_FAILURE;
        } finally {
            closeQuietly(videoWriteFd);
            closeQuietly(controlFd);
        }

        try {
            Session next = new Session(
                    ownerToken,
                    codec,
                    encoderName,
                    maxDimension,
                    maxFps,
                    bitrateBps,
                    allowControl,
                    allowClipboard,
                    ownedVideo,
                    ownedControl,
                    this::onSessionFinished
            );
            session = next;
            next.start();
            return STARTED;
        } catch (RuntimeException e) {
            closeQuietly(ownedVideo);
            closeQuietly(ownedControl);
            session = null;
            Ln.e("Could not start NotiSync capture backend", e);
            return BACKEND_FAILURE;
        }
    }

    /**
     * Stops only the exact process-local owner and waits until processors, descriptors, and the active
     * slot have all been released. Never wait while holding this backend's monitor: completion clears
     * the active slot through {@link #onSessionFinished(Session)}.
     */
    public boolean stopSession(String ownerToken) {
        Session current;
        synchronized (this) {
            current = session;
            if (current == null) {
                return true;
            }
            if (!current.ownerToken.equals(ownerToken)) {
                return false;
            }
        }
        current.stop();
        boolean stopped = current.awaitStopped(STOP_TIMEOUT_MILLIS);
        if (!stopped) {
            Ln.w("Timed out stopping exact NotiSync capture owner");
        }
        return stopped;
    }

    /** Updates only the exact active session; stale app-process callbacks cannot affect a replacement. */
    public boolean recoverVideo(String ownerToken, int bitrateBps) {
        Session current;
        synchronized (this) {
            current = session;
            if (current == null || !current.ownerToken.equals(ownerToken)
                    || bitrateBps < 128_000 || bitrateBps > current.bitrateBps) {
                return false;
            }
        }
        return current.recoverVideo(bitrateBps);
    }

    public void destroy() {
        Session current;
        synchronized (this) {
            current = session;
        }
        if (current != null) {
            current.stop();
            if (!current.awaitStopped(STOP_TIMEOUT_MILLIS)) {
                Ln.w("Timed out cleaning up NotiSync capture backend");
            }
        }
    }

    private synchronized void onSessionFinished(Session finished) {
        if (session == finished) {
            session = null;
        }
    }

    private static VideoCodec codecForId(int codecId) {
        switch (codecId) {
            case CODEC_H264:
                return VideoCodec.H264;
            case CODEC_H265:
                return VideoCodec.H265;
            case CODEC_AV1:
                return VideoCodec.AV1;
            default:
                return null;
        }
    }

    private static boolean isValidOwnerToken(String ownerToken) {
        if (ownerToken == null || ownerToken.isEmpty() || ownerToken.length() > 128) {
            return false;
        }
        for (int i = 0; i < ownerToken.length(); ++i) {
            char c = ownerToken.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return false;
            }
        }
        return true;
    }

    private static String findHardwareEncoder(VideoCodec codec) {
        MediaCodecInfo[] infos = new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos();
        for (MediaCodecInfo info : infos) {
            if (!info.isEncoder() || !info.isHardwareAccelerated()) {
                continue;
            }
            for (String type : info.getSupportedTypes()) {
                if (codec.getMimeType().equalsIgnoreCase(type)) {
                    return info.getName();
                }
            }
        }
        return null;
    }

    private static void closeQuietly(ParcelFileDescriptor fd) {
        if (fd == null) {
            return;
        }
        try {
            fd.close();
        } catch (IOException ignored) {
            // Best effort during teardown.
        }
    }

    private static final class Session {
        private final String ownerToken;
        private final VideoCodec codec;
        private final String encoderName;
        private final int maxDimension;
        private final int maxFps;
        private final int bitrateBps;
        private final boolean allowControl;
        private final boolean allowClipboard;
        private final ParcelFileDescriptor videoFd;
        private final ParcelFileDescriptor controlFd;
        private final java.util.function.Consumer<Session> finished;
        private final AtomicBoolean stopping = new AtomicBoolean();
        private final CountDownLatch stopped = new CountDownLatch(1);
        private final Object lifecycleLock = new Object();
        private final List<AsyncProcessor> processors = new ArrayList<>();
        private ControlChannel controlChannel;
        private CaptureControl captureControl;

        Session(String ownerToken, VideoCodec codec, String encoderName, int maxDimension, int maxFps, int bitrateBps,
                boolean allowControl, boolean allowClipboard, ParcelFileDescriptor videoFd, ParcelFileDescriptor controlFd,
                java.util.function.Consumer<Session> finished) {
            this.ownerToken = ownerToken;
            this.codec = codec;
            this.encoderName = encoderName;
            this.maxDimension = maxDimension;
            this.maxFps = maxFps;
            this.bitrateBps = bitrateBps;
            this.allowControl = allowControl;
            this.allowClipboard = allowClipboard;
            this.videoFd = videoFd;
            this.controlFd = controlFd;
            this.finished = finished;
        }

        void start() {
            Looper mainLooper = Looper.getMainLooper();
            if (mainLooper != null && Looper.myLooper() != mainLooper) {
                if (!new Handler(mainLooper).post(this::startOnMainThread)) {
                    finishAsync();
                }
            } else {
                startOnMainThread();
            }
        }

        private void startOnMainThread() {
            if (stopping.get()) {
                finishAsync();
                return;
            }
            try {
                synchronized (lifecycleLock) {
                    if (stopping.get()) {
                        return;
                    }
                    Workarounds.apply();
                    Ln.initLogLevel(Ln.Level.INFO);
                    Options options = Options.forScreenMirror(maxDimension, maxFps, bitrateBps, encoderName, allowClipboard);

                    captureControl = new CaptureControl();
                    ParcelFileDescriptor readFd = null;
                    ParcelFileDescriptor writeFd = null;
                    try {
                        readFd = ParcelFileDescriptor.dup(controlFd.getFileDescriptor());
                        writeFd = ParcelFileDescriptor.dup(controlFd.getFileDescriptor());
                        controlChannel = new ControlChannel(
                                new ParcelFileDescriptor.AutoCloseInputStream(readFd),
                                new ParcelFileDescriptor.AutoCloseOutputStream(writeFd),
                                allowControl,
                                allowClipboard
                        );
                        readFd = null;
                        writeFd = null;
                    } finally {
                        closeQuietly(readFd);
                        closeQuietly(writeFd);
                    }
                    // The controller always exists because video visibility is session flow
                    // control, independent from Android input and clipboard authorization.
                    Controller controller = new Controller(controlChannel, options, captureControl);
                    processors.add(controller);

                    Streamer streamer = new Streamer(videoFd.getFileDescriptor(), codec);
                    SurfaceCapture capture = new ScreenCapture(controller, options);
                    SurfaceEncoder encoder = new SurfaceEncoder(capture, streamer, options, captureControl);
                    processors.add(encoder);

                    if (stopping.get()) {
                        return;
                    }
                    AsyncProcessor.TerminationListener listener = fatalError -> finishAsync();
                    for (AsyncProcessor processor : processors) {
                        processor.start(listener);
                    }
                }
            } catch (Throwable error) {
                Ln.e("NotiSync capture initialization failed", error);
                finishAsync();
            }
        }

        void stop() {
            finishAsync();
        }

        boolean recoverVideo(int bitRate) {
            synchronized (lifecycleLock) {
                return !stopping.get() && captureControl != null && captureControl.recoverVideo(bitRate);
            }
        }

        boolean awaitStopped(long timeoutMillis) {
            try {
                return stopped.await(timeoutMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        private void finishAsync() {
            if (!stopping.compareAndSet(false, true)) {
                return;
            }
            Thread cleanup = new Thread(() -> {
                try {
                    List<AsyncProcessor> ownedProcessors;
                    synchronized (lifecycleLock) {
                        ownedProcessors = new ArrayList<>(processors);
                    }
                    for (AsyncProcessor processor : ownedProcessors) {
                        try {
                            processor.stop();
                        } catch (Throwable error) {
                            Ln.w("Could not stop capture processor: " + error.getMessage());
                        }
                    }
                    if (controlChannel != null) {
                        controlChannel.close();
                    }
                    closeQuietly(controlFd);
                    closeQuietly(videoFd);
                    for (AsyncProcessor processor : ownedProcessors) {
                        try {
                            processor.join();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        } catch (Throwable error) {
                            Ln.w("Could not join capture processor: " + error.getMessage());
                        }
                    }
                } finally {
                    try {
                        finished.accept(this);
                    } finally {
                        stopped.countDown();
                    }
                }
            }, "notisync-scrcpy-cleanup");
            cleanup.start();
        }
    }

    private static final long STOP_TIMEOUT_MILLIS = 10_000;
}

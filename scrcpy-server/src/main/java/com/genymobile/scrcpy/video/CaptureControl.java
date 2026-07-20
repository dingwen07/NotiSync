package com.genymobile.scrcpy.video;

import android.media.MediaCodec;

public class CaptureControl {

    public static final int RESET_REASON_TERMINATED = 1;
    public static final int RESET_REASON_DISPLAY_PROPERTIES_CHANGED = 1 << 1;
    public static final int RESET_REASON_CLIENT_RESET = 1 << 2;
    public static final int RESET_REASON_CLIENT_RESIZED = 1 << 3;
    /** NotiSync v1: the authenticated viewer no longer has a video consumer. */
    public static final int RESET_REASON_VIDEO_HIDDEN = 1 << 4;

    private int reset = 0;
    private boolean videoVisible = true;

    // Current instance of MediaCodec to "interrupt" on reset
    private MediaCodec runningMediaCodec;

    public synchronized boolean isResetRequested() {
        return reset != 0;
    }

    public synchronized int consumeReset() {
        int value = reset;
        reset = 0;
        return value;
    }

    public synchronized void reset(int reason) {
        assert reason != 0;
        reset |= reason;
        if (runningMediaCodec != null) {
            try {
                runningMediaCodec.signalEndOfInputStream();
            } catch (IllegalStateException e) {
                // ignore
            }
        }
        notifyAll();
    }

    public synchronized void setRunningMediaCodec(MediaCodec runningMediaCodec) {
        this.runningMediaCodec = runningMediaCodec;
    }

    /**
     * Update whether the authenticated viewer currently has a surface consuming video.
     *
     * <p>Hiding interrupts the current encoder so capture resources can be released. The encoder
     * thread then waits in {@link #awaitVideoVisible()} without closing the video or control
     * descriptors. Showing does not reuse an inter-frame stream: it wakes the encoder loop, which
     * creates a fresh codec session and emits a new session boundary.</p>
     */
    public synchronized void setVideoVisible(boolean visible) {
        if (videoVisible == visible) {
            return;
        }
        videoVisible = visible;
        if (!visible) {
            reset(RESET_REASON_VIDEO_HIDDEN);
        } else {
            notifyAll();
        }
    }

    public synchronized boolean isVideoVisible() {
        return videoVisible;
    }

    /** Return false when termination was requested while video was hidden. */
    public synchronized boolean awaitVideoVisible() throws InterruptedException {
        while (!videoVisible && (reset & RESET_REASON_TERMINATED) == 0) {
            wait();
        }
        return (reset & RESET_REASON_TERMINATED) == 0;
    }
}

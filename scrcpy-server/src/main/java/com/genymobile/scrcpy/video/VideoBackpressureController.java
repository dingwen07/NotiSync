package com.genymobile.scrcpy.video;

/** Latency-first response to a blocked encoded-video write. */
final class VideoBackpressureController {

    static final long SLOW_WRITE_NANOS = 75_000_000L;
    static final long MIN_BITRATE_ADJUSTMENT_NANOS = 1_000_000_000L;
    static final long RECOVERY_STABLE_NANOS = 5_000_000_000L;

    static final class Decision {
        final boolean requestSyncFrame;
        final int newBitRate;

        Decision(boolean requestSyncFrame, int newBitRate) {
            this.requestSyncFrame = requestSyncFrame;
            this.newBitRate = newBitRate;
        }
    }

    private final int targetBitRate;
    private final int minimumBitRate;
    private int currentBitRate;
    private long lastCongestionNanos = Long.MIN_VALUE;
    private long lastBitrateAdjustmentNanos = Long.MIN_VALUE;
    private boolean dropUntilKeyFrame;

    VideoBackpressureController(int targetBitRate) {
        if (targetBitRate <= 0) {
            throw new IllegalArgumentException("target bitrate must be positive");
        }
        this.targetBitRate = targetBitRate;
        minimumBitRate = Math.min(targetBitRate, 750_000);
        currentBitRate = targetBitRate;
    }

    boolean shouldWritePacket(boolean codecConfig, boolean keyFrame) {
        if (codecConfig || !dropUntilKeyFrame) {
            return true;
        }
        if (keyFrame) {
            dropUntilKeyFrame = false;
            return true;
        }
        return false;
    }

    void beginDroppingUntilKeyFrame(boolean syncFrameAccepted) {
        dropUntilKeyFrame = syncFrameAccepted;
    }

    Decision onPacketWritten(long writeDurationNanos, long completedAtNanos) {
        if (writeDurationNanos >= SLOW_WRITE_NANOS) {
            lastCongestionNanos = completedAtNanos;
            int adjusted = 0;
            if (elapsedAtLeast(completedAtNanos, lastBitrateAdjustmentNanos, MIN_BITRATE_ADJUSTMENT_NANOS)) {
                int reduced = Math.max(minimumBitRate, currentBitRate * 3 / 4);
                if (reduced < currentBitRate) {
                    currentBitRate = reduced;
                    adjusted = reduced;
                    lastBitrateAdjustmentNanos = completedAtNanos;
                }
            }
            return new Decision(true, adjusted);
        }

        if (lastCongestionNanos != Long.MIN_VALUE
                && elapsedAtLeast(completedAtNanos, lastCongestionNanos, RECOVERY_STABLE_NANOS)
                && elapsedAtLeast(completedAtNanos, lastBitrateAdjustmentNanos, RECOVERY_STABLE_NANOS)
                && currentBitRate < targetBitRate) {
            currentBitRate = Math.min(targetBitRate, Math.max(currentBitRate + 1, currentBitRate * 5 / 4));
            lastBitrateAdjustmentNanos = completedAtNanos;
            return new Decision(false, currentBitRate);
        }
        return new Decision(false, 0);
    }

    int getCurrentBitRate() {
        return currentBitRate;
    }

    private static boolean elapsedAtLeast(long now, long then, long duration) {
        return then == Long.MIN_VALUE || now - then >= duration;
    }
}

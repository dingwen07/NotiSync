package com.genymobile.scrcpy;

/**
 * Immutable capture settings for the NotiSync screen-mirroring entry point.
 *
 * <p>This is intentionally not scrcpy's command-line option parser. The privileged service has no
 * generic option surface: it always mirrors the primary display and only accepts the bounded
 * quality values selected by {@link NotiSyncCaptureBackend}.</p>
 */
public final class Options {

    private static final int PRIMARY_DISPLAY_ID = 0;

    private final int maxSize;
    private final int videoBitRate;
    private final float maxFps;
    private final String videoEncoder;
    private final boolean clipboardAutosync;

    private Options(int maxSize, int maxFps, int videoBitRate, String videoEncoder, boolean clipboardAutosync) {
        if (maxSize <= 0 || maxSize > 8192 || maxFps <= 0 || maxFps > 240
                || videoBitRate <= 0 || videoBitRate > 100_000_000
                || videoEncoder == null || videoEncoder.isEmpty()) {
            throw new IllegalArgumentException("Invalid NotiSync capture options");
        }
        this.maxSize = maxSize;
        this.maxFps = maxFps;
        this.videoBitRate = videoBitRate;
        this.videoEncoder = videoEncoder;
        this.clipboardAutosync = clipboardAutosync;
    }

    public static Options forScreenMirror(int maxSize, int maxFps, int videoBitRate, String videoEncoder,
            boolean clipboardAutosync) {
        return new Options(maxSize, maxFps, videoBitRate, videoEncoder, clipboardAutosync);
    }

    public int getMaxSize() {
        return maxSize;
    }

    public int getVideoBitRate() {
        return videoBitRate;
    }

    public float getMaxFps() {
        return maxFps;
    }

    public String getVideoEncoder() {
        return videoEncoder;
    }

    public boolean getClipboardAutosync() {
        return clipboardAutosync;
    }

    public int getDisplayId() {
        return PRIMARY_DISPLAY_ID;
    }

}

package net.extrawdw.apps.notisync.screen;

import android.os.ParcelFileDescriptor;

/** Narrow Binder boundary into the shell-uid screen-capture process. */
interface IScreenMirrorUserService {
    /** Reserved by Shizuku for removal of a non-daemon UserService process. */
    void destroy() = 16777114;

    /** READY or a bounded, non-sensitive reason the backend cannot be used. */
    int getBackendStatus() = 1;

    /** Hardware encoder bitmask: H.264=1, H.265=2, AV1=4. */
    int probeHardwareCodecs() = 2;

    /** Non-invasive readiness flags: display-0 capture=1, input injection=2. */
    int probeCapabilities() = 5;

    /**
     * Starts one session and takes ownership of both descriptors. Returns immediately after workers start.
     * videoWriteFd is write-only by convention; controlFd carries scrcpy control and device messages.
     */
    int startSession(
        String ownerToken,
        int codecId,
        int maxDimension,
        int maxFps,
        int bitrateBps,
        boolean allowControl,
        boolean allowClipboard,
        in ParcelFileDescriptor videoWriteFd,
        in ParcelFileDescriptor controlFd
    ) = 3;

    /**
     * Stops only the session with this process-local owner token and waits for capture cleanup.
     * Returns false when another owner is active or cleanup could not be acknowledged in time.
     */
    boolean stopSession(String ownerToken) = 4;

    /**
     * Applies latency recovery to the exact active encoder: update its bitrate and request a sync frame.
     * This is local app-to-UserService feedback; no network/session material crosses Binder.
     */
    /** Result flags: bitrate applied=1, sync frame requested=2. */
    int recoverVideo(String ownerToken, int bitrateBps) = 6;
}

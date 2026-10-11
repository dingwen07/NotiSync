package com.genymobile.scrcpy.video;

import com.genymobile.scrcpy.VirtualDisplayConfig;
import java.io.IOException;

/** Keeps an unavailable notification's app fallback on the already authorized virtual display. */
final class NotificationDisplayLauncher {
    interface Notification {
        /** False only when the activity intent is unavailable or canceled; other errors propagate. */
        boolean send(int displayId) throws Exception;
        String resolveFallbackPackage() throws Exception;
    }

    interface AppLauncher {
        void launch(String packageName, int displayId) throws Exception;
    }

    private NotificationDisplayLauncher() { }

    /** Returns true only when the original notification was sent and may be auto-canceled. */
    static boolean launch(int displayId, Notification notification, AppLauncher appLauncher) throws Exception {
        if (displayId <= 0) throw new IllegalArgumentException("Virtual display required");
        if (notification.send(displayId)) return true;
        String packageName = notification.resolveFallbackPackage();
        if (!VirtualDisplayConfig.isValidPackageName(packageName)) {
            throw new IOException("Notification is unavailable and its app cannot be resolved");
        }
        appLauncher.launch(packageName, displayId);
        return false;
    }
}

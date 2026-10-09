package com.genymobile.scrcpy;

import android.app.PendingIntent;

/** Narrow local configuration. It never accepts a display ID or arbitrary Intent from a peer. */
public final class VirtualDisplayConfig {
    public interface NotificationResolver {
        PendingIntent resolve() throws Exception;
        void onSent() throws Exception;
    }

    public final int width;
    public final int height;
    public final int densityDpi;
    public final String packageName;
    public final NotificationResolver notificationResolver;
    public final PendingIntent launcherIntent;

    public VirtualDisplayConfig(int width, int height, int densityDpi, String packageName, NotificationResolver notificationResolver) {
        this(width, height, densityDpi, packageName, notificationResolver, null);
    }

    public VirtualDisplayConfig(int width, int height, int densityDpi, String packageName, NotificationResolver notificationResolver,
            PendingIntent launcherIntent) {
        if (!isValidSize(width, height, densityDpi)
                || (packageName != null && (packageName.length() > 255
                    || !packageName.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")))
                || (packageName != null && notificationResolver != null)) {
            throw new IllegalArgumentException("Invalid virtual display configuration");
        }
        this.width = width;
        this.height = height;
        this.densityDpi = densityDpi;
        this.packageName = packageName;
        this.notificationResolver = notificationResolver;
        this.launcherIntent = launcherIntent;
    }

    public static boolean isValidSize(int width, int height, int densityDpi) {
        return width >= 240 && width <= 4096 && height >= 240 && height <= 4096
                && (long) width * height <= 8_388_608 && densityDpi >= 120 && densityDpi <= 640;
    }
}

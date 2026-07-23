package com.genymobile.scrcpy.wrappers;

import com.genymobile.scrcpy.util.Ln;

import android.os.IInterface;

import java.lang.reflect.Method;

/** Minimal shell-authorized wrapper for the one status-bar action exposed by NotiSync. */
public final class StatusBarManager {

    private final IInterface manager;
    private Method expandNotificationsPanelMethod;

    static StatusBarManager create() {
        IInterface manager = ServiceManager.getService(
                "statusbar",
                "com.android.internal.statusbar.IStatusBarService"
        );
        return new StatusBarManager(manager);
    }

    private StatusBarManager(IInterface manager) {
        this.manager = manager;
    }

    public boolean expandNotificationsPanel() {
        try {
            if (expandNotificationsPanelMethod == null) {
                expandNotificationsPanelMethod = manager.getClass()
                        .getMethod("expandNotificationsPanel");
            }
            expandNotificationsPanelMethod.invoke(manager);
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Ln.w("Could not invoke IStatusBarService.expandNotificationsPanel", error);
            return false;
        }
    }
}

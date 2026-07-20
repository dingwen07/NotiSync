package com.genymobile.scrcpy.wrappers;

import com.genymobile.scrcpy.AndroidVersions;
import com.genymobile.scrcpy.util.Ln;

import android.annotation.TargetApi;
import android.os.IInterface;
import android.view.IDisplayWindowListener;

/** API 34+ display-configuration listener used for rotation and fold handling. */
public final class WindowManager {

    private final IInterface manager;

    static WindowManager create() {
        return new WindowManager(ServiceManager.getService("window", "android.view.IWindowManager"));
    }

    private WindowManager(IInterface manager) {
        this.manager = manager;
    }

    @TargetApi(AndroidVersions.API_30_ANDROID_11)
    public void registerDisplayWindowListener(IDisplayWindowListener listener) {
        try {
            manager.getClass().getMethod("registerDisplayWindowListener", IDisplayWindowListener.class).invoke(manager, listener);
        } catch (ReflectiveOperationException error) {
            Ln.e("Could not register display window listener", error);
        }
    }

    @TargetApi(AndroidVersions.API_30_ANDROID_11)
    public void unregisterDisplayWindowListener(IDisplayWindowListener listener) {
        try {
            manager.getClass().getMethod("unregisterDisplayWindowListener", IDisplayWindowListener.class).invoke(manager, listener);
        } catch (ReflectiveOperationException error) {
            Ln.e("Could not unregister display window listener", error);
        }
    }
}

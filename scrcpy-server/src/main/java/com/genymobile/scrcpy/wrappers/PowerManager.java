package com.genymobile.scrcpy.wrappers;

import com.genymobile.scrcpy.FakeContext;
import com.genymobile.scrcpy.util.Ln;

import android.os.IInterface;
import android.os.SystemClock;

import java.lang.reflect.Method;

public final class PowerManager {

    private static final int PRIMARY_DISPLAY_ID = 0;
    private static final int WAKE_REASON_APPLICATION = 2;
    private static final int GO_TO_SLEEP_REASON_POWER_BUTTON = 4;
    private static final int GO_TO_SLEEP_FLAGS_NONE = 0;
    private static final long POWER_STATE_VERIFY_TIMEOUT_MS = 1_500;
    private static final long POWER_STATE_VERIFY_INTERVAL_MS = 50;

    private final IInterface manager;
    private Method isScreenOnMethod;
    private Method wakeUpMethod;
    private Method goToSleepMethod;

    static PowerManager create() {
        IInterface manager = ServiceManager.getService("power", "android.os.IPowerManager");
        return new PowerManager(manager);
    }

    private PowerManager(IInterface manager) {
        this.manager = manager;
    }

    private Method getIsScreenOnMethod() throws NoSuchMethodException {
        if (isScreenOnMethod == null) {
            isScreenOnMethod = manager.getClass().getMethod("isDisplayInteractive", int.class);
        }
        return isScreenOnMethod;
    }

    private Method getWakeUpMethod() throws NoSuchMethodException {
        if (wakeUpMethod == null) {
            wakeUpMethod = manager.getClass().getMethod(
                    "wakeUp",
                    long.class,
                    int.class,
                    String.class,
                    String.class
            );
        }
        return wakeUpMethod;
    }

    private Method getGoToSleepMethod() throws NoSuchMethodException {
        if (goToSleepMethod == null) {
            // Stable IPowerManager entry point on Android 14-16. The public hidden wrapper has a
            // display-aware overload on newer releases, but the default-display method remains.
            goToSleepMethod = manager.getClass().getMethod(
                    "goToSleep",
                    long.class,
                    int.class,
                    int.class
            );
        }
        return goToSleepMethod;
    }

    public boolean isScreenOn(int displayId) {
        try {
            Method method = getIsScreenOnMethod();
            return (boolean) method.invoke(manager, displayId);
        } catch (ReflectiveOperationException e) {
            Ln.e("Could not invoke method", e);
            return false;
        }
    }

    /**
     * Wake the default power group through the shell-authorized power binder, then verify that the
     * primary display became interactive. This changes wakefulness only; it cannot dismiss keyguard.
     */
    public boolean wakePrimaryDisplay() {
        if (isScreenOn(PRIMARY_DISPLAY_ID)) {
            return true;
        }
        try {
            getWakeUpMethod().invoke(
                    manager,
                    SystemClock.uptimeMillis(),
                    WAKE_REASON_APPLICATION,
                    "notisync:screen_mirroring",
                    FakeContext.PACKAGE_NAME
            );
        } catch (ReflectiveOperationException | RuntimeException error) {
            Ln.w("Could not invoke IPowerManager.wakeUp", error);
            return false;
        }
        return waitUntilScreenOn(PRIMARY_DISPLAY_ID, POWER_STATE_VERIFY_TIMEOUT_MS);
    }

    /** Put the default power group to sleep with physical-power-button semantics. */
    public boolean sleepPrimaryDisplay() {
        if (!isScreenOn(PRIMARY_DISPLAY_ID)) {
            return true;
        }
        try {
            getGoToSleepMethod().invoke(
                    manager,
                    SystemClock.uptimeMillis(),
                    GO_TO_SLEEP_REASON_POWER_BUTTON,
                    GO_TO_SLEEP_FLAGS_NONE
            );
        } catch (ReflectiveOperationException | RuntimeException error) {
            Ln.w("Could not invoke IPowerManager.goToSleep", error);
            return false;
        }
        return waitUntilScreenOff(PRIMARY_DISPLAY_ID, POWER_STATE_VERIFY_TIMEOUT_MS);
    }

    public boolean waitUntilScreenOn(int displayId, long timeoutMs) {
        long deadline = SystemClock.uptimeMillis() + Math.max(0, timeoutMs);
        do {
            if (isScreenOn(displayId)) {
                return true;
            }
            long remaining = deadline - SystemClock.uptimeMillis();
            if (remaining <= 0) {
                return false;
            }
            SystemClock.sleep(Math.min(POWER_STATE_VERIFY_INTERVAL_MS, remaining));
        } while (true);
    }

    public boolean waitUntilScreenOff(int displayId, long timeoutMs) {
        long deadline = SystemClock.uptimeMillis() + Math.max(0, timeoutMs);
        do {
            if (!isScreenOn(displayId)) {
                return true;
            }
            long remaining = deadline - SystemClock.uptimeMillis();
            if (remaining <= 0) {
                return false;
            }
            SystemClock.sleep(Math.min(POWER_STATE_VERIFY_INTERVAL_MS, remaining));
        } while (true);
    }

}

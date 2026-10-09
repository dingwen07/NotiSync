package com.genymobile.scrcpy.wrappers;

import com.genymobile.scrcpy.FakeContext;
import com.genymobile.scrcpy.util.Ln;

import android.os.IInterface;
import android.os.SystemClock;

import java.lang.reflect.Method;
import java.util.function.LongSupplier;

public final class PowerManager {

    private static final int PRIMARY_DISPLAY_ID = 0;
    private static final int WAKE_REASON_APPLICATION = 2;
    private static final int GO_TO_SLEEP_REASON_POWER_BUTTON = 4;
    private static final int GO_TO_SLEEP_FLAGS_NONE = 0;
    private static final long POWER_STATE_VERIFY_TIMEOUT_MS = 1_500;
    private static final long POWER_STATE_VERIFY_INTERVAL_MS = 50;

    private final IInterface manager;
    private final DisplayManager displayManager;
    private final LongSupplier uptimeMillis;
    private Method isScreenOnMethod;
    private Method wakeUpMethod;
    private Method goToSleepMethod;
    private Method userActivityMethod;
    private Method wakeUpWithDisplayIdMethod;

    static PowerManager create() {
        IInterface manager = ServiceManager.getService("power", "android.os.IPowerManager");
        return new PowerManager(manager);
    }

    private PowerManager(IInterface manager) {
        this(manager, ServiceManager.getDisplayManager(), SystemClock::uptimeMillis);
    }

    PowerManager(IInterface manager, DisplayManager displayManager, LongSupplier uptimeMillis) {
        this.manager = manager;
        this.displayManager = displayManager;
        this.uptimeMillis = uptimeMillis;
    }

    /** Session-scoped activity; a shared power group intentionally keeps the phone awake too. */
    public boolean keepDisplayActive(int displayId) {
        if (displayId <= 0) return false;
        try {
            if (displayManager.getDisplayGroupId(displayId) < 0) return false;
            if (userActivityMethod == null) {
                userActivityMethod = manager.getClass().getMethod("userActivity", int.class, long.class, int.class, int.class);
            }
            userActivityMethod.invoke(manager, displayId, uptimeMillis.getAsLong(), 0, 0);
            return true;
        } catch (ReflectiveOperationException error) { return false; }
    }

    /** Wake the session's assigned power group, preserving keyguard. Some ROMs share the phone's group. */
    public boolean wakeVirtualDisplay(int displayId) {
        if (displayId <= 0) return false;
        try {
            int groupId = displayManager.getDisplayGroupId(displayId);
            int primaryGroupId = displayManager.getDisplayGroupId(PRIMARY_DISPLAY_ID);
            if (groupId < 0 || primaryGroupId < 0) return false;
            if (isScreenOn(displayId)) return true;
            if (groupId == primaryGroupId) {
                return wakePrimaryDisplay() && waitUntilScreenOn(displayId, POWER_STATE_VERIFY_TIMEOUT_MS);
            }
            if (wakeUpWithDisplayIdMethod == null) {
                wakeUpWithDisplayIdMethod = manager.getClass().getMethod("wakeUpWithDisplayId",
                        long.class, int.class, String.class, String.class, int.class);
            }
            wakeUpWithDisplayIdMethod.invoke(manager, uptimeMillis.getAsLong(), WAKE_REASON_APPLICATION,
                    "notisync:virtual_display", FakeContext.PACKAGE_NAME, displayId);
            return waitUntilScreenOn(displayId, POWER_STATE_VERIFY_TIMEOUT_MS);
        } catch (ReflectiveOperationException | RuntimeException error) {
            Ln.w("Could not wake the virtual display's power group", error);
            return false;
        }
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
                    uptimeMillis.getAsLong(),
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
                    uptimeMillis.getAsLong(),
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
        long deadline = uptimeMillis.getAsLong() + Math.max(0, timeoutMs);
        do {
            if (isScreenOn(displayId)) {
                return true;
            }
            long remaining = deadline - uptimeMillis.getAsLong();
            if (remaining <= 0) {
                return false;
            }
            SystemClock.sleep(Math.min(POWER_STATE_VERIFY_INTERVAL_MS, remaining));
        } while (true);
    }

    public boolean waitUntilScreenOff(int displayId, long timeoutMs) {
        long deadline = uptimeMillis.getAsLong() + Math.max(0, timeoutMs);
        do {
            if (!isScreenOn(displayId)) {
                return true;
            }
            long remaining = deadline - uptimeMillis.getAsLong();
            if (remaining <= 0) {
                return false;
            }
            SystemClock.sleep(Math.min(POWER_STATE_VERIFY_INTERVAL_MS, remaining));
        } while (true);
    }

}

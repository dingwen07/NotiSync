package com.genymobile.scrcpy.device;

import com.genymobile.scrcpy.AndroidVersions;
import com.genymobile.scrcpy.wrappers.ClipboardManager;
import com.genymobile.scrcpy.wrappers.InputManager;
import com.genymobile.scrcpy.wrappers.PowerManager;
import com.genymobile.scrcpy.wrappers.ServiceManager;

import android.os.Build;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

/** Narrow privileged device facade for input, wake state, and plain-text clipboard access. */
public final class Device {

    public static final int DISPLAY_ID_NONE = -1;
    public static final int INJECT_MODE_ASYNC = InputManager.INJECT_INPUT_EVENT_MODE_ASYNC;
    public static final int INJECT_MODE_WAIT_FOR_RESULT = InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT;
    public static final int INJECT_MODE_WAIT_FOR_FINISH = InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH;

    private Device() {
    }

    public static boolean supportsInputEvents(int displayId) {
        return displayId == 0 || Build.VERSION.SDK_INT >= AndroidVersions.API_29_ANDROID_10;
    }

    public static boolean injectEvent(InputEvent inputEvent, int displayId, int injectMode) {
        if (!supportsInputEvents(displayId)) {
            return false;
        }
        if (displayId != 0 && !InputManager.setDisplayId(inputEvent, displayId)) {
            return false;
        }
        return ServiceManager.getInputManager().injectInputEvent(inputEvent, injectMode);
    }

    public static boolean injectKeyEvent(int action, int keyCode, int repeat, int metaState, int displayId, int injectMode) {
        long now = SystemClock.uptimeMillis();
        KeyEvent event = new KeyEvent(now, now, action, keyCode, repeat, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                InputDevice.SOURCE_KEYBOARD);
        return injectEvent(event, displayId, injectMode);
    }

    public static boolean pressReleaseKeycode(int keyCode, int displayId, int injectMode) {
        return injectKeyEvent(KeyEvent.ACTION_DOWN, keyCode, 0, 0, displayId, injectMode)
                && injectKeyEvent(KeyEvent.ACTION_UP, keyCode, 0, 0, displayId, injectMode);
    }

    /** Reproduce `adb shell input keyevent KEYCODE_WAKEUP`'s coherent down/up event pair. */
    private static boolean injectWakeupKey() {
        long now = SystemClock.uptimeMillis();
        KeyEvent down = new KeyEvent(
                now,
                now,
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_WAKEUP,
                0,
                0,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                0,
                0,
                InputDevice.SOURCE_KEYBOARD
        );
        if (!injectEvent(down, 0, INJECT_MODE_WAIT_FOR_FINISH)) {
            return false;
        }
        return injectEvent(KeyEvent.changeAction(down, KeyEvent.ACTION_UP), 0, INJECT_MODE_WAIT_FOR_FINISH);
    }

    /**
     * Wake without toggle semantics. Prefer the shell-authorized power binder; retain Android's
     * one-way WAKEUP key as a verified fallback for vendor binder variations.
     */
    public static boolean wakeUp(int displayId) {
        PowerManager powerManager = ServiceManager.getPowerManager();
        if (displayId == 0 && powerManager.wakePrimaryDisplay()) {
            return true;
        }
        if (displayId != 0 || !injectWakeupKey()) {
            return false;
        }
        return powerManager.waitUntilScreenOn(displayId, 600);
    }

    /** Toggle only the primary display, after an authenticated NotiSync control request. */
    public static boolean togglePrimaryDisplayPower() {
        PowerManager powerManager = ServiceManager.getPowerManager();
        if (powerManager.isScreenOn(0)) {
            return powerManager.sleepPrimaryDisplay();
        }
        if (powerManager.wakePrimaryDisplay()) {
            return true;
        }
        // Match the known-working `adb shell input keyevent KEYCODE_WAKEUP` path for vendor
        // implementations where the direct binder wake request is ignored.
        return injectWakeupKey() && powerManager.waitUntilScreenOn(0, 600);
    }

    public static boolean isScreenOn(int displayId) {
        return displayId != DISPLAY_ID_NONE && ServiceManager.getPowerManager().isScreenOn(displayId);
    }

    public static String getClipboardText() {
        ClipboardManager clipboardManager = ServiceManager.getClipboardManager();
        if (clipboardManager == null) {
            return null;
        }
        CharSequence text = clipboardManager.getText();
        return text == null ? null : text.toString();
    }

    public static boolean setClipboardText(String text) {
        ClipboardManager clipboardManager = ServiceManager.getClipboardManager();
        if (clipboardManager == null) {
            return false;
        }
        String current = getClipboardText();
        if (text.equals(current)) {
            return false;
        }
        return clipboardManager.setText(text);
    }
}

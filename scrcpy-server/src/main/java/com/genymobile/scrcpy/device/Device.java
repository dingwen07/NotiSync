package com.genymobile.scrcpy.device;

import com.genymobile.scrcpy.AndroidVersions;
import com.genymobile.scrcpy.wrappers.ClipboardManager;
import com.genymobile.scrcpy.wrappers.InputManager;
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

    /** One-way wake request: unlike POWER, KEYCODE_WAKEUP cannot turn an already-awake display off. */
    public static boolean wakeUp(int displayId) {
        return pressReleaseKeycode(KeyEvent.KEYCODE_WAKEUP, displayId, INJECT_MODE_WAIT_FOR_FINISH);
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

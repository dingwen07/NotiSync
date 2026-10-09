package com.genymobile.scrcpy.control;

import android.view.KeyEvent;

/** Shared viewer/source policy for keyboard events carried by the existing keycode frame. */
public final class ControlKeyPolicy {
    public static final int MAX_REPEAT = 1_000;
    public static final int META_STATE_MASK = 0x007770ff;

    private ControlKeyPolicy() {
    }

    public static boolean isAllowed(int keycode) {
        if (keycode >= KeyEvent.KEYCODE_0 && keycode <= KeyEvent.KEYCODE_POUND) return true;
        if (keycode >= KeyEvent.KEYCODE_A && keycode <= KeyEvent.KEYCODE_SYM) return true;
        if (keycode >= KeyEvent.KEYCODE_ENTER && keycode <= KeyEvent.KEYCODE_AT) return true;
        if (keycode >= KeyEvent.KEYCODE_ESCAPE && keycode <= KeyEvent.KEYCODE_FORWARD) return true;
        if (keycode >= KeyEvent.KEYCODE_F1 && keycode <= KeyEvent.KEYCODE_NUMPAD_RIGHT_PAREN) return true;
        if (keycode >= KeyEvent.KEYCODE_ZENKAKU_HANKAKU && keycode <= KeyEvent.KEYCODE_KANA) return true;
        switch (keycode) {
            case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_APP_SWITCH:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_PLUS:
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_LANGUAGE_SWITCH:
            case KeyEvent.KEYCODE_CUT:
            case KeyEvent.KEYCODE_COPY:
            case KeyEvent.KEYCODE_PASTE:
                return true;
            default:
                // Power, wake, sleep, launch-app, and unrelated device controls are not keyboard input.
                return false;
        }
    }
}

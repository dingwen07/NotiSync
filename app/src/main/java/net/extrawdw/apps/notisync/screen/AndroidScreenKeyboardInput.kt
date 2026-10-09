package net.extrawdw.apps.notisync.screen

import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent

internal fun isScreenHardwareKeyboardEvent(source: Int, flags: Int): Boolean =
    source and InputDevice.SOURCE_KEYBOARD == InputDevice.SOURCE_KEYBOARD &&
        flags and KeyEvent.FLAG_SOFT_KEYBOARD == 0

/** Physical key translation on the focused viewer's UI thread; software input uses the IME bridge. */
internal class AndroidScreenKeyboardInput(
    private val composeDeadChar: (Int, Int) -> Int = KeyCharacterMap::getDeadChar,
) {
    var control: AndroidScreenControlDispatcher? = null
        set(value) {
            if (field !== value) releasePressedKeys()
            field = value
        }

    // A null value means text, whose key-up must be consumed without another frame.
    private val pressedKeys = mutableMapOf<Pair<Int, Int>, Int?>()
    private var deadAccent = 0

    fun dispatch(
        action: Int,
        keyCode: Int,
        repeat: Int,
        metaState: Int,
        unicodeChar: Int,
        deviceId: Int,
    ): Boolean {
        val dispatcher = control ?: return false
        val identity = deviceId to keyCode
        if (action == KeyEvent.ACTION_UP) {
            if (!pressedKeys.containsKey(identity)) return false
            pressedKeys.remove(identity)?.let { remoteKey ->
                dispatcher.sendKeyEvent(KeyEvent.ACTION_UP, remoteKey, 0, metaState)
            }
            return true
        }
        if (action != KeyEvent.ACTION_DOWN) return false
        // Do not start a remote hold from the tail of a key pressed in a dialog or another window.
        if (repeat > 0 && !pressedKeys.containsKey(identity)) return false

        val shortcut = metaState and
            (KeyEvent.META_CTRL_MASK or KeyEvent.META_META_MASK or KeyEvent.META_ALT_MASK) != 0
        val remoteKey = keyCode.takeIf {
            isAndroidScreenControlKey(it) &&
                (shortcut || it in MODIFIER_KEYS || it in NAVIGATION_KEYS || unicodeChar == 0)
        }
        // Keep a repeated key on the route selected by its initial DOWN even if modifiers change.
        val heldRemoteKey = pressedKeys[identity]
        if (heldRemoteKey != null || (remoteKey != null && !pressedKeys.containsKey(identity))) {
            val target = heldRemoteKey ?: remoteKey!!
            if (keyCode !in MODIFIER_KEYS) deadAccent = 0
            if (dispatcher.sendKeyEvent(KeyEvent.ACTION_DOWN, target, repeat, metaState)) {
                pressedKeys[identity] = target
            }
            return true
        }
        // Never turn an unsupported shortcut into ordinary typed text.
        if (shortcut) return pressedKeys.containsKey(identity)
        if (unicodeChar == 0 || (unicodeChar > 0 && Character.isISOControl(unicodeChar))) return false

        pressedKeys[identity] = null
        if (unicodeChar and KeyCharacterMap.COMBINING_ACCENT != 0) {
            if (deadAccent != 0) sendCodePoint(dispatcher, deadAccent)
            deadAccent = unicodeChar and KeyCharacterMap.COMBINING_ACCENT_MASK
        } else {
            val accent = deadAccent
            deadAccent = 0
            val combined = if (accent != 0) composeDeadChar(accent, unicodeChar) else 0
            if (accent != 0 && combined == 0) sendCodePoint(dispatcher, accent)
            sendCodePoint(dispatcher, combined.takeIf { it != 0 } ?: unicodeChar)
        }
        return true
    }

    /** Release through the original dispatcher before losing focus or switching sessions. */
    fun releasePressedKeys() {
        pressedKeys.values.filterNotNull().asReversed().forEach { keyCode ->
            control?.sendKeyEvent(KeyEvent.ACTION_UP, keyCode, 0, 0)
        }
        pressedKeys.clear()
        deadAccent = 0
    }

    private fun sendCodePoint(dispatcher: AndroidScreenControlDispatcher, codePoint: Int) {
        if (Character.isValidCodePoint(codePoint)) {
            dispatcher.sendText(String(Character.toChars(codePoint)))
        }
    }

    private companion object {
        val MODIFIER_KEYS = setOf(
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT,
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT,
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT,
            KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT,
            KeyEvent.KEYCODE_CAPS_LOCK, KeyEvent.KEYCODE_NUM_LOCK, KeyEvent.KEYCODE_SCROLL_LOCK,
            KeyEvent.KEYCODE_FUNCTION, KeyEvent.KEYCODE_SYM,
        )
        val NAVIGATION_KEYS = setOf(
            KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DEL,
            KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END,
            KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_NUMPAD_ENTER,
        )
    }
}

package com.genymobile.scrcpy.control;

import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Source-side key lifetimes. All calls are serialized by Controller's input-state lock. */
final class KeyInputState {
    interface Injector {
        boolean inject(long downTime, long eventTime, int action, int keyCode, int repeat,
                int metaState, int flags, int displayId, int injectMode);
    }

    private static final class PressedKey {
        final long downTime;
        final int displayId;
        final int injectMode;

        PressedKey(long downTime, int displayId, int injectMode) {
            this.downTime = downTime;
            this.displayId = displayId;
            this.injectMode = injectMode;
        }
    }

    private final Injector injector;
    private final Map<Integer, PressedKey> pressed = new LinkedHashMap<>();

    KeyInputState(Injector injector) {
        this.injector = injector;
    }

    boolean inject(int action, int keyCode, int repeat, int metaState, int displayId, int injectMode, long now) {
        PressedKey previous = pressed.get(keyCode);
        long downTime = previous != null ? previous.downTime : now;
        boolean injected = injector.inject(downTime, now, action, keyCode, repeat, metaState, 0, displayId, injectMode);
        if (injected) {
            if (action == KeyEvent.ACTION_DOWN && previous == null) {
                pressed.put(keyCode, new PressedKey(downTime, displayId, injectMode));
            } else if (action == KeyEvent.ACTION_UP) {
                pressed.remove(keyCode);
            }
        }
        return injected;
    }

    boolean releaseAll(long now) {
        // Release keys before their modifiers, and never trigger a click/Back on teardown.
        var entries = new ArrayList<>(pressed.entrySet());
        pressed.clear();
        boolean success = true;
        for (int i = entries.size() - 1; i >= 0; --i) {
            var entry = entries.get(i);
            PressedKey key = entry.getValue();
            try {
                success &= injector.inject(key.downTime, now, KeyEvent.ACTION_UP, entry.getKey(), 0, 0,
                        KeyEvent.FLAG_CANCELED, key.displayId, key.injectMode);
            } catch (RuntimeException error) {
                // A failed release must not prevent the remaining held keys from being released.
                success = false;
            }
        }
        return success;
    }
}

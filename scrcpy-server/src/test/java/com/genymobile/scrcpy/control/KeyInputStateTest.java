package com.genymobile.scrcpy.control;

import android.view.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class KeyInputStateTest {
    private record Event(long downTime, long eventTime, int action, int key, int repeat,
            int meta, int flags, int display, int mode) { }

    @Test
    public void repeatsAndReleasePreserveOriginalDownTime() {
        List<Event> events = new ArrayList<>();
        KeyInputState keys = recording(events);
        keys.inject(0, KeyEvent.KEYCODE_A, 0, 0, 7, 0, 10);
        keys.inject(0, KeyEvent.KEYCODE_A, 3, 0, 7, 0, 40);
        keys.inject(1, KeyEvent.KEYCODE_A, 0, 0, 7, 0, 50);
        keys.releaseAll(60);
        assertEquals(List.of(10L, 10L, 10L), events.stream().map(Event::downTime).toList());
        assertEquals(List.of(10L, 40L, 50L), events.stream().map(Event::eventTime).toList());
        assertEquals(3, events.get(1).repeat());
    }

    @Test
    public void disconnectCancelsEveryHeldKeyOnItsOriginalDisplayBeforeModifiers() {
        List<Event> events = new ArrayList<>();
        KeyInputState keys = recording(events);
        keys.inject(0, KeyEvent.KEYCODE_CTRL_LEFT, 0, KeyEvent.META_CTRL_ON, 7, 0, 10);
        keys.inject(0, KeyEvent.KEYCODE_SPACE, 0, KeyEvent.META_CTRL_ON, 7, 0, 20);
        assertTrue(keys.releaseAll(30));
        assertTrue(keys.releaseAll(40));
        assertEquals(4, events.size());
        assertEquals(KeyEvent.KEYCODE_SPACE, events.get(2).key());
        assertEquals(KeyEvent.KEYCODE_CTRL_LEFT, events.get(3).key());
        for (Event event : events.subList(2, 4)) {
            assertEquals(KeyEvent.ACTION_UP, event.action());
            assertEquals(KeyEvent.FLAG_CANCELED, event.flags());
            assertEquals(0, event.meta());
            assertEquals(7, event.display());
        }
    }

    @Test
    public void failedDownIsNotHeldAndFailedUpCanBeReleasedDuringTeardown() {
        List<Event> events = new ArrayList<>();
        KeyInputState keys = new KeyInputState((down, time, action, key, repeat, meta, flags, display, mode) -> {
            events.add(new Event(down, time, action, key, repeat, meta, flags, display, mode));
            return key != KeyEvent.KEYCODE_B && (action == 0 || flags == KeyEvent.FLAG_CANCELED);
        });
        assertFalse(keys.inject(0, KeyEvent.KEYCODE_B, 0, 0, 7, 0, 10));
        assertTrue(keys.inject(0, KeyEvent.KEYCODE_A, 0, 0, 7, 0, 20));
        assertFalse(keys.inject(1, KeyEvent.KEYCODE_A, 0, 0, 7, 0, 30));
        assertTrue(keys.releaseAll(40));
        assertEquals(4, events.size());
        assertEquals(KeyEvent.KEYCODE_A, events.get(3).key());
        assertEquals(20, events.get(3).downTime());
    }

    @Test
    public void oneReleaseFailureDoesNotPreventRemainingKeyCleanup() {
        List<Integer> released = new ArrayList<>();
        KeyInputState keys = new KeyInputState((down, time, action, key, repeat, meta, flags, display, mode) -> {
            if (action == KeyEvent.ACTION_UP) {
                released.add(key);
                if (key == KeyEvent.KEYCODE_B) throw new IllegalStateException("injection unavailable");
            }
            return true;
        });
        keys.inject(0, KeyEvent.KEYCODE_A, 0, 0, 7, 0, 10);
        keys.inject(0, KeyEvent.KEYCODE_B, 0, 0, 7, 0, 20);
        assertFalse(keys.releaseAll(30));
        assertTrue(keys.releaseAll(40));
        assertEquals(List.of(KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_A), released);
    }

    @Test
    public void displayReplacementStartsANewKeyLifetimeAfterCleanup() {
        List<Event> events = new ArrayList<>();
        KeyInputState keys = recording(events);
        keys.inject(0, KeyEvent.KEYCODE_TAB, 0, 0, 7, 0, 10);
        keys.releaseAll(20);
        keys.inject(0, KeyEvent.KEYCODE_TAB, 0, 0, 8, 0, 30);
        keys.releaseAll(40);
        assertEquals(List.of(7, 7, 8, 8), events.stream().map(Event::display).toList());
        assertEquals(List.of(10L, 10L, 30L, 30L), events.stream().map(Event::downTime).toList());
    }

    private static KeyInputState recording(List<Event> events) {
        return new KeyInputState((down, time, action, key, repeat, meta, flags, display, mode) -> {
            events.add(new Event(down, time, action, key, repeat, meta, flags, display, mode));
            return true;
        });
    }
}

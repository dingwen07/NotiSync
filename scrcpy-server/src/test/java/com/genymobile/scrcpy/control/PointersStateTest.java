package com.genymobile.scrcpy.control;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.genymobile.scrcpy.model.Position;
import com.genymobile.scrcpy.model.Size;

import org.junit.Test;

public final class PointersStateTest {

    @Test
    public void heldTouchesAreClearedBeforeMapperRotation() {
        PositionMapper portrait = PositionMapper.create(new Size(100, 200), new Size(100, 200));
        PositionMapper landscape = PositionMapper.create(new Size(200, 100), new Size(200, 100));
        Position staleMove = new Position(50, 100, 100, 200);
        assertNotNull(portrait.map(staleMove));

        PointersState pointers = new PointersState();
        pointers.getOrCreatePointerIndex(41);
        pointers.getOrCreatePointerIndex(42);
        assertEquals(2, pointers.size());

        // Controller performs this clear in the same lock as its display-mapper swap, after
        // injecting ACTION_CANCEL against the previous display.
        pointers.clear();

        assertEquals(0, pointers.size());
        assertNull(landscape.map(staleMove));
        assertEquals(0, pointers.getOrCreatePointerIndex(99));
    }

    @Test
    public void globalCancelClearsEveryTouchAndOrphansWaitForFreshDown() {
        PointersState pointers = new PointersState();
        assertEquals(0, pointers.getOrCreatePointerIndex(11));
        assertEquals(1, pointers.getOrCreatePointerIndex(22));
        assertEquals(2, pointers.size());

        // One Android ACTION_CANCEL terminates the complete two-pointer gesture.
        pointers.clear();

        assertEquals(-1, pointers.getActivePointerIndex(11)); // delayed MOVE
        assertEquals(-1, pointers.getActivePointerIndex(22)); // delayed UP
        assertEquals(0, pointers.size());
        assertEquals(0, pointers.getOrCreatePointerIndex(33)); // fresh DOWN
        assertEquals(1, pointers.size());
    }
}

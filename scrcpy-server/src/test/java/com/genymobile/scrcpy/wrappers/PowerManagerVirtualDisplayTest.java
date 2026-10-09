package com.genymobile.scrcpy.wrappers;

import android.os.IBinder;
import android.os.IInterface;
import java.lang.reflect.Constructor;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Regression for ROMs that assign an OWN_DISPLAY_GROUP request to the phone's power group. */
public class PowerManagerVirtualDisplayTest {
    @Test public void sharedGroupWakesPhoneAndKeepsTheSessionActive() throws Exception {
        for (int group : new int[] {0, 4}) {
            FakePowerService service = new FakePowerService();
            PowerManager power = create(service, group, group);
            assertTrue(power.wakeVirtualDisplay(12));
            assertEquals(1, service.primaryWakeCalls);
            assertEquals(0, service.virtualWakeCalls);
            assertTrue(power.keepDisplayActive(12));
            assertEquals(12, service.activityDisplayId);
            assertEquals(1, service.calls);
        }
    }

    @Test public void separateGroupWakesOnlyItsOwnDisplay() throws Exception {
        FakePowerService service = new FakePowerService();
        PowerManager power = create(service, 7, 0);
        assertTrue(power.wakeVirtualDisplay(12));
        assertEquals(0, service.primaryWakeCalls);
        assertEquals(1, service.virtualWakeCalls);
        assertEquals(12, service.interactiveDisplay);
        assertTrue(power.keepDisplayActive(12));
        assertEquals(12, service.activityDisplayId);
    }

    @Test public void alreadyInteractiveDisplayIsNotWokenAgain() throws Exception {
        FakePowerService service = new FakePowerService();
        service.interactiveDisplay = 12;
        assertTrue(create(service, 0, 0).wakeVirtualDisplay(12));
        assertEquals(0, service.primaryWakeCalls);
        assertEquals(0, service.virtualWakeCalls);
    }

    @Test public void missingDisplayOrUnknownPrimaryGroupFailsClosed() throws Exception {
        for (int[] groups : new int[][] {{-1, 0}, {4, -1}}) {
            FakePowerService service = new FakePowerService();
            assertFalse(create(service, groups[0], groups[1]).wakeVirtualDisplay(12));
            assertEquals(0, service.primaryWakeCalls);
            assertEquals(0, service.virtualWakeCalls);
        }
        FakePowerService service = new FakePowerService();
        assertFalse(create(service, -1, 0).keepDisplayActive(12));
        assertEquals(0, service.calls);
    }

    @Test public void primaryAndInvalidDisplayIdsAreRejected() throws Exception {
        for (int id : new int[] {0, -1}) {
            FakePowerService service = new FakePowerService();
            PowerManager power = create(service, 4, 0);
            assertFalse(power.keepDisplayActive(id));
            assertFalse(power.wakeVirtualDisplay(id));
            assertEquals(0, service.calls);
            assertEquals(0, service.primaryWakeCalls);
            assertEquals(0, service.virtualWakeCalls);
        }
    }

    private PowerManager create(FakePowerService service, int group, int primaryGroup) throws Exception {
        Constructor<DisplayManager> displayConstructor = DisplayManager.class.getDeclaredConstructor(Object.class);
        displayConstructor.setAccessible(true);
        DisplayManager displays = displayConstructor.newInstance(new FakeDisplayService(group, primaryGroup));
        return new PowerManager(service, displays, () -> 100L);
    }

    public static class FakeDisplayService {
        private final int group;
        private final int primaryGroup;
        FakeDisplayService(int group, int primaryGroup) { this.group = group; this.primaryGroup = primaryGroup; }
        public Object getDisplayInfo(int id) {
            int value = id == 0 ? primaryGroup : group;
            return value < 0 ? null : new RawDisplayInfo(value);
        }
    }

    public static class RawDisplayInfo {
        public int displayGroupId;
        RawDisplayInfo(int group) { displayGroupId = group; }
    }

    public static class FakePowerService implements IInterface {
        int calls;
        int primaryWakeCalls;
        int virtualWakeCalls;
        int activityDisplayId = -1;
        int interactiveDisplay = -1;
        @Override public IBinder asBinder() { return null; }
        public void userActivity(int displayId, long time, int event, int flags) { calls++; activityDisplayId = displayId; }
        public boolean isDisplayInteractive(int displayId) { return interactiveDisplay == -2 || interactiveDisplay == displayId; }
        public void wakeUp(long time, int reason, String details, String packageName) { primaryWakeCalls++; interactiveDisplay = -2; }
        public void wakeUpWithDisplayId(long time, int reason, String details, String packageName, int displayId) {
            virtualWakeCalls++;
            interactiveDisplay = displayId;
        }
    }
}

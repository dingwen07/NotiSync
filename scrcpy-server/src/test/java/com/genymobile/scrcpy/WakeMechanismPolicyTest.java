package com.genymobile.scrcpy;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

/** Keeps display power explicit, bounded, and independent of generic POWER-key injection. */
public final class WakeMechanismPolicyTest {

    @Test
    public void usesExplicitDirectPowerToggleWithWakeKeyFallback() throws IOException {
        Path root = locateSourceRoot();
        String powerManager = source(root, "wrappers/PowerManager.java");
        String device = source(root, "device/Device.java");
        String backend = source(root, "NotiSyncCaptureBackend.java");
        String controller = source(root, "control/Controller.java");

        assertTrue(powerManager.contains("\"wakeUp\""));
        assertTrue(powerManager.contains("WAKE_REASON_APPLICATION = 2"));
        assertTrue(powerManager.contains("FakeContext.PACKAGE_NAME"));
        assertTrue(powerManager.contains("SystemClock.uptimeMillis()"));
        assertTrue(powerManager.contains("waitUntilScreenOn"));
        assertTrue(powerManager.contains("\"goToSleep\""));
        assertTrue(powerManager.contains("GO_TO_SLEEP_REASON_POWER_BUTTON = 4"));
        assertTrue(powerManager.contains("waitUntilScreenOff"));

        assertTrue(device.contains("powerManager.wakePrimaryDisplay()"));
        assertTrue(device.contains("powerManager.sleepPrimaryDisplay()"));
        assertTrue(device.contains("togglePrimaryDisplayPower()"));
        assertTrue(device.contains("KeyEvent.KEYCODE_WAKEUP"));
        assertTrue(device.contains("KeyEvent.changeAction(down, KeyEvent.ACTION_UP)"));
        assertTrue(device.contains("InputDevice.SOURCE_KEYBOARD"));
        assertFalse(device.contains("InputDevice.SOURCE_UNKNOWN"));
        assertTrue(device.contains("INJECT_MODE_WAIT_FOR_FINISH"));
        assertFalse(backend.contains("Device.wakeUp(0)"));
        assertFalse(backend.contains("wakePrimaryDisplayOnce"));
        assertTrue(controller.contains("Device.wakeUp(displayId)"));
        assertTrue(controller.contains("Device.togglePrimaryDisplayPower()"));

        assertFalse(device.contains("KeyEvent.KEYCODE_POWER"));
        assertFalse(backend.contains("KeyEvent.KEYCODE_POWER"));
        assertFalse(controller.contains("KeyEvent.KEYCODE_POWER"));
    }

    private static String source(Path root, String relative) throws IOException {
        return Files.readString(root.resolve("com/genymobile/scrcpy").resolve(relative));
    }

    private static Path locateSourceRoot() {
        Path moduleRelative = Path.of("src/main/java");
        if (Files.isDirectory(moduleRelative)) {
            return moduleRelative;
        }
        Path repositoryRelative = Path.of("scrcpy-server/src/main/java");
        assertTrue("Could not locate scrcpy-server sources", Files.isDirectory(repositoryRelative));
        return repositoryRelative;
    }
}

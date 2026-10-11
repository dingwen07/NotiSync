package com.genymobile.scrcpy.video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class NotificationDisplayLauncherTest {
    @Test
    public void liveNotificationPreservesItsDestinationAndAllowsAutoCancel() throws Exception {
        Target target = new Target(true, "com.example.postingapp");
        assertTrue(NotificationDisplayLauncher.launch(17, target, target::launchApp));
        assertEquals(List.of("notification:17"), target.events);
    }

    @Test
    public void unavailableOrCanceledNotificationOpensPostingAppOnSameDisplayWithoutAutoCancel() throws Exception {
        Target target = new Target(false, "com.example.postingapp");
        assertFalse(NotificationDisplayLauncher.launch(17, target, target::launchApp));
        assertEquals(List.of("notification:17", "resolve-app", "com.example.postingapp:17"), target.events);
    }

    @Test
    public void revokedOrMissingFallbackAndInvalidPackageNeverLaunchAnApp() {
        for (String packageName : new String[] {null, "", "com.example;command"}) {
            Target target = new Target(false, packageName);
            assertThrows(IOException.class, () -> NotificationDisplayLauncher.launch(17, target, target::launchApp));
            assertEquals(List.of("notification:17", "resolve-app"), target.events);
        }
    }

    @Test
    public void otherSendFailuresAreNotTreatedAsUnavailableNotifications() {
        Target target = new Target(false, "com.example.postingapp");
        target.sendFailure = new SecurityException("denied");
        assertSame(target.sendFailure, assertThrows(SecurityException.class,
                () -> NotificationDisplayLauncher.launch(17, target, target::launchApp)));
        assertEquals(List.of("notification:17"), target.events);
    }

    @Test
    public void appLaunchFailurePropagatesWithoutRetryingOnThePhysicalDisplay() {
        Target target = new Target(false, "com.example.postingapp");
        IOException failure = new IOException("App has no launchable activity");
        assertSame(failure, assertThrows(IOException.class, () -> NotificationDisplayLauncher.launch(17, target, (pkg, id) -> {
            target.launchApp(pkg, id);
            throw failure;
        })));
        assertEquals(List.of("notification:17", "resolve-app", "com.example.postingapp:17"), target.events);
    }

    @Test
    public void physicalDisplayCannotReceiveEitherLaunch() {
        Target target = new Target(true, "com.example.postingapp");
        assertThrows(IllegalArgumentException.class, () -> NotificationDisplayLauncher.launch(0, target, target::launchApp));
        assertTrue(target.events.isEmpty());
    }

    private static final class Target implements NotificationDisplayLauncher.Notification {
        final List<String> events = new ArrayList<>();
        final boolean available;
        final String packageName;
        RuntimeException sendFailure;

        Target(boolean available, String packageName) {
            this.available = available;
            this.packageName = packageName;
        }

        @Override public boolean send(int displayId) {
            events.add("notification:" + displayId);
            if (sendFailure != null) throw sendFailure;
            return available;
        }

        @Override public String resolveFallbackPackage() {
            events.add("resolve-app");
            return packageName;
        }

        void launchApp(String pkg, int displayId) {
            events.add(pkg + ":" + displayId);
        }
    }
}

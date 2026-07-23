package com.genymobile.scrcpy;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.Test;

/** Regression gate preventing removed generic scrcpy capabilities from returning on rebase. */
public final class MinimalPrivilegedSurfaceTest {

    @Test
    public void sourceClosureContainsNoRemovedPrivilegedFeatures() throws IOException {
        Path sourceRoot = locateSourceRoot();
        assertFalse(containsJavaSource(sourceRoot.resolve("com/genymobile/scrcpy/audio")));
        assertFalse(containsJavaSource(sourceRoot.resolve("com/genymobile/scrcpy/opengl")));

        List<String> forbiddenFiles = List.of(
                "control/UhidManager.java",
                "video/CameraCapture.java",
                "video/NewDisplayCapture.java",
                "wrappers/ActivityManager.java",
                "wrappers/ContentProvider.java",
                "util/Command.java",
                "util/Settings.java"
        );
        for (String relative : forbiddenFiles) {
            assertFalse("Removed privileged source returned: " + relative,
                    Files.exists(sourceRoot.resolve("com/genymobile/scrcpy").resolve(relative)));
        }

        List<String> forbiddenCode = List.of(
                "Runtime.getRuntime(",
                "ProcessBuilder(",
                "TYPE_UHID_",
                "TYPE_START_APP",
                "TYPE_SCAN_FILE",
                "TYPE_CAMERA_",
                "createNewVirtualDisplay(",
                "android.media.Audio",
                "android.hardware.camera",
                "getActivityManager("
        );
        try (var files = Files.walk(sourceRoot)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String code = Files.readString(file);
                for (String forbidden : forbiddenCode) {
                    assertFalse("Forbidden code returned in " + file + ": " + forbidden, code.contains(forbidden));
                }
            }
        }

        // Notification shade expansion is the only restored panel capability. Keep settings,
        // collapse, disable, and arbitrary status-bar methods outside the privileged closure.
        Path statusBarWrapper = sourceRoot.resolve(
                "com/genymobile/scrcpy/wrappers/StatusBarManager.java"
        );
        assertTrue(Files.exists(statusBarWrapper));
        String statusBarCode = Files.readString(statusBarWrapper);
        assertTrue(statusBarCode.contains("expandNotificationsPanel"));
        assertFalse(statusBarCode.contains("expandSettingsPanel"));
        assertFalse(statusBarCode.contains("collapsePanels"));
        assertFalse(statusBarCode.contains("disable("));
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

    private static boolean containsJavaSource(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return false;
        }
        try (var files = Files.walk(directory)) {
            return files.anyMatch(path -> path.toString().endsWith(".java"));
        }
    }
}

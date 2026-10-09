package com.genymobile.scrcpy.video;

import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.hardware.display.VirtualDisplay;
import android.media.ImageReader;
import android.os.Build;
import android.os.Process;
import android.system.Os;
import android.view.Display;
import android.view.Surface;
import com.genymobile.scrcpy.FakeContext;
import com.genymobile.scrcpy.VirtualDisplayConfig;
import com.genymobile.scrcpy.control.PositionMapper;
import com.genymobile.scrcpy.display.DisplayInfo;
import com.genymobile.scrcpy.display.DisplayMonitor;
import com.genymobile.scrcpy.display.DisplayProperties;
import com.genymobile.scrcpy.model.Size;
import com.genymobile.scrcpy.opengl.AffineOpenGLFilter;
import com.genymobile.scrcpy.opengl.OpenGLRunner;
import com.genymobile.scrcpy.util.AffineMatrix;
import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.wrappers.DisplayActivityLauncher;
import com.genymobile.scrcpy.wrappers.ServiceManager;
import java.io.IOException;

/** Session-owned virtual display. Its resolution is independent of encoder scaling. */
public final class NewDisplayCapture extends SurfaceCapture {
    // Android 14+ flags from the pinned upstream, without DESTROY_CONTENT_ON_REMOVAL so Android
    // returns app tasks to the main display. SECURE and AUTO_MIRROR are never set.
    static final int FLAGS = (1 << 0) | (1 << 1) | (1 << 3) | (1 << 6) | (1 << 7)
            | (1 << 9) | (1 << 10) | (1 << 11) | (1 << 12)
            | (1 << 13) | (1 << 14) | (1 << 15);
    private final VirtualDisplayConfig config;
    private final CaptureDisplayListener listener;
    private final DisplayMonitor monitor = new DisplayMonitor();
    private VideoConstraints constraints;
    private VirtualDisplay display;
    private OpenGLRunner gl;
    private Size logicalSize;
    private Size physicalSize;
    private Size videoSize;
    private int rotation;
    private Size requestedSize;
    private int requestedDensityDpi;
    private int densityDpi;
    private boolean released;
    private java.util.concurrent.ScheduledExecutorService keepActive;
    private final java.util.function.BooleanSupplier active;

    public NewDisplayCapture(CaptureDisplayListener listener, VirtualDisplayConfig config,
            java.util.function.BooleanSupplier active) {
        this.listener = listener;
        this.config = config;
        this.active = active;
        requestedSize = new Size(config.width, config.height);
        requestedDensityDpi = config.densityDpi;
    }

    @Override protected void init(VideoConstraints value) { constraints = value; }

    @Override public synchronized void prepare() throws IOException {
        if (display == null) {
            logicalSize = requestedSize;
            densityDpi = requestedDensityDpi;
            rotation = 0;
        } else {
            DisplayInfo info = ServiceManager.getDisplayManager().getDisplayInfo(display.getDisplay().getDisplayId());
            if (info == null) throw new IOException("Virtual display was removed");
            logicalSize = info.getSize();
            rotation = info.getRotation();
        }
        physicalSize = rotation % 2 == 0 ? logicalSize : logicalSize.rotate();
        videoSize = logicalSize.constrain(constraints);
        monitor.setSessionDisplayProperties(new DisplayProperties(logicalSize, rotation));
    }

    @Override public synchronized void start(Surface surface) throws IOException {
        try {
            // The display surface stays in natural orientation. Rotate the video only; injected
            // coordinates already use the logical display orientation (same as upstream 4.1).
            gl = new OpenGLRunner(new AffineOpenGLFilter(AffineMatrix.rotateOrtho(rotation).invert()));
            Surface input = gl.start(physicalSize, videoSize, surface);
            boolean created = display == null;
            if (created) {
                display = create(physicalSize.getWidth(), physicalSize.getHeight(), densityDpi, input, FLAGS);
                int id = display.getDisplay().getDisplayId();
                ServiceManager.getWindowManager().setDisplayImePolicyLocal(id);
                monitor.start(id, props -> getCaptureControl().reset(CaptureControl.RESET_REASON_DISPLAY_PROPERTIES_CHANGED));
                if (!active.getAsBoolean()) throw new IOException("Virtual display session stopped");
                if (!ServiceManager.getPowerManager().wakeVirtualDisplay(id)) {
                    throw new IOException("Could not wake the virtual display's power group");
                }
                keepActive = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "virtual-display-active"));
                keepActive.scheduleWithFixedDelay(() -> {
                    if (active.getAsBoolean()) ServiceManager.getPowerManager().keepDisplayActive(id);
                }, 0, 5, java.util.concurrent.TimeUnit.SECONDS);
            } else {
                display.setSurface(input);
            }
            int id = display.getDisplay().getDisplayId();
            if (!active.getAsBoolean()) throw new IOException("Virtual display session stopped");
            listener.onCaptureDisplay(id, PositionMapper.create(videoSize, logicalSize));
            if (created && (!requestedSize.equals(logicalSize) || requestedDensityDpi != densityDpi)) resizeToRequested();
            if (created) launch(id); // An empty display may never produce the first frame.
        } catch (Exception error) {
            // SurfaceEncoder will also release this capture; keep failure cleanup idempotent.
            release();
            throw new IOException("Could not start virtual display or launch its target", error);
        }
    }

    /** The platform's configuration callback rebuilds video and input mapping after the resize. */
    public synchronized void requestResize(int width, int height, int dpi) throws IOException {
        if (!VirtualDisplayConfig.isValidSize(width, height, dpi)) throw new IOException("Invalid virtual display size");
        if (released || !active.getAsBoolean()) return;
        requestedSize = new Size(width, height);
        requestedDensityDpi = dpi;
        resizeToRequested();
    }

    private void resizeToRequested() throws IOException {
        if (display == null) return;
        DisplayInfo info = ServiceManager.getDisplayManager().getDisplayInfo(display.getDisplay().getDisplayId());
        if (info == null) throw new IOException("Virtual display was removed");
        if (requestedSize.equals(info.getSize()) && requestedDensityDpi == densityDpi) return;
        // VirtualDisplay.resize() takes natural-orientation dimensions; the viewer supplies logical ones.
        Size naturalSize = info.getRotation() % 2 == 0 ? requestedSize : requestedSize.rotate();
        try {
            display.resize(naturalSize.getWidth(), naturalSize.getHeight(), requestedDensityDpi);
        } catch (RuntimeException error) {
            throw new IOException("Could not resize virtual display", error);
        }
        densityDpi = requestedDensityDpi;
    }

    private void launch(int id) throws Exception {
        if (config.notificationResolver == null) {
            if (config.packageName == null) DisplayActivityLauncher.launchLauncher(config.launcherIntent, id);
            else DisplayActivityLauncher.launch(config.packageName, id);
            return;
        }
        PendingIntent intent = config.notificationResolver.resolve();
        if (intent == null || !intent.isActivity()) {
            throw new IOException("Notification is unavailable or does not launch an activity");
        }
        ActivityOptions options = ActivityOptions.makeBasic().setLaunchDisplayId(id);
        options.setPendingIntentBackgroundActivityStartMode(backgroundActivityStartMode());
        intent.send(FakeContext.get(), 0, null, null, null, null, options.toBundle());
        // Match a normal content tap's auto-cancel behavior. Sending is not proof of placement,
        // and no failure here may retry the launch on display 0 or tear down the opened app.
        try { config.notificationResolver.onSent(); }
        catch (Exception error) { Ln.w("Could not complete notification auto-cancel", error); }
    }

    @SuppressWarnings("deprecation") // The deprecated mode is still required on Android 14–15.
    private static int backgroundActivityStartMode() {
        return Build.VERSION.SDK_INT >= 36 ? ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
                : ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED;
    }

    private static VirtualDisplay create(int width, int height, int dpi, Surface surface, int flags) throws Exception {
        if (Os.getuid() != Process.SHELL_UID) throw new SecurityException("Shell context required");
        for (String permission : new String[] {"android.permission.ADD_TRUSTED_DISPLAY", "android.permission.ADD_ALWAYS_UNLOCKED_DISPLAY"}) {
            if (FakeContext.get().checkPermission(permission, Process.myPid(), Process.SHELL_UID) != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Virtual display permission unavailable");
            }
        }
        VirtualDisplay result = ServiceManager.getDisplayManager().createNewDisplay("NotiSync", width, height, dpi, surface, flags);
        if (result == null) throw new IOException("Virtual display creation rejected");
        try {
            Display actual = result.getDisplay();
            int alwaysUnlocked = Display.class.getField("FLAG_ALWAYS_UNLOCKED").getInt(null);
            int trusted = Display.class.getField("FLAG_TRUSTED").getInt(null);
            if (actual.getDisplayId() <= 0 || (actual.getFlags() & (alwaysUnlocked | trusted)) != (alwaysUnlocked | trusted)
                    || (actual.getFlags() & Display.FLAG_SECURE) != 0) {
                throw new IOException("Virtual display properties unavailable");
            }
            return result;
        } catch (Exception error) {
            result.release();
            throw error;
        }
    }

    public static boolean probe() {
        VirtualDisplay probe = null;
        try (ImageReader reader = ImageReader.newInstance(16, 16, PixelFormat.RGBA_8888, 2)) {
            // No decorations/launcher during probing; all permission-bearing flags remain identical.
            probe = create(16, 16, 160, reader.getSurface(), FLAGS & ~(1 << 9));
            ServiceManager.getWindowManager().setDisplayImePolicyLocal(probe.getDisplay().getDisplayId());
            return true;
        } catch (Exception error) {
            Ln.w("Virtual display probe unavailable");
            return false;
        } finally {
            if (probe != null) probe.release();
        }
    }

    @Override public synchronized void stop() {
        try {
            if (display != null) display.setSurface(null);
        } finally {
            if (gl != null) {
                OpenGLRunner current = gl;
                gl = null;
                current.stopAndRelease();
            }
        }
    }

    @Override public synchronized void release() {
        released = true;
        if (keepActive != null) { keepActive.shutdownNow(); keepActive = null; }
        monitor.stopAndRelease();
        try {
            if (display != null) {
                VirtualDisplay current = display;
                display = null;
                current.release(); // Android reparents app tasks because the destruction flag is unset.
            }
        } finally { stop(); }
    }

    @Override public Size getSize() { return videoSize; }
    @Override protected boolean applyNewVideoConstraints(VideoConstraints value) { constraints = value; return true; }
}

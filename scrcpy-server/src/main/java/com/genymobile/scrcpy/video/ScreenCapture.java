package com.genymobile.scrcpy.video;

import com.genymobile.scrcpy.Options;
import com.genymobile.scrcpy.control.PositionMapper;
import com.genymobile.scrcpy.device.Device;
import com.genymobile.scrcpy.display.DisplayInfo;
import com.genymobile.scrcpy.display.DisplayMonitor;
import com.genymobile.scrcpy.display.DisplayProperties;
import com.genymobile.scrcpy.model.ConfigurationException;
import com.genymobile.scrcpy.model.Size;
import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.wrappers.ServiceManager;
import com.genymobile.scrcpy.wrappers.SurfaceControl;

import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.VirtualDisplay;
import android.media.ImageReader;
import android.os.IBinder;
import android.view.Surface;

import java.io.IOException;

/** Primary-display capture through a non-secure SurfaceControl mirror. */
public final class ScreenCapture extends SurfaceCapture {

    private final CaptureDisplayListener displayListener;
    private final int displayId;
    private final DisplayMonitor displayMonitor = new DisplayMonitor();

    private VideoConstraints videoConstraints;
    private DisplayInfo displayInfo;
    private Size videoSize;
    private IBinder display;
    private VirtualDisplay virtualDisplay;

    public ScreenCapture(CaptureDisplayListener displayListener, Options options) {
        this.displayListener = displayListener;
        this.displayId = options.getDisplayId();
        if (displayId != 0) {
            throw new IllegalArgumentException("NotiSync only captures the primary display");
        }
    }

    @Override
    public void init(VideoConstraints videoConstraints) {
        this.videoConstraints = videoConstraints;
        displayMonitor.start(displayId, props -> getCaptureControl().reset(CaptureControl.RESET_REASON_DISPLAY_PROPERTIES_CHANGED));
    }

    @Override
    public void prepare() throws ConfigurationException {
        displayInfo = ServiceManager.getDisplayManager().getDisplayInfo(displayId);
        if (displayInfo == null) {
            throw new ConfigurationException("Primary display is unavailable");
        }
        if ((displayInfo.getFlags() & DisplayInfo.FLAG_SUPPORTS_PROTECTED_BUFFERS) == 0) {
            Ln.w("Display does not report protected-buffer support; secure content remains unavailable");
        }

        Size displaySize = displayInfo.getSize();
        displayMonitor.setSessionDisplayProperties(new DisplayProperties(displaySize, displayInfo.getRotation()));
        videoSize = displaySize.constrain(videoConstraints);
    }

    @Override
    public void start(Surface surface) throws IOException {
        destroyCurrentDisplay();
        try {
            virtualDisplay = ServiceManager.getDisplayManager().createMirrorDisplay(
                    "notisync-screen",
                    videoSize.getWidth(),
                    videoSize.getHeight(),
                    displayId,
                    surface
            );
            Ln.d("Display: using DisplayManager primary-display mirror");
        } catch (Exception displayManagerError) {
            try {
                display = createDisplay("notisync-screen");
                setDisplaySurface(display, surface, displayInfo.getSize().toRect(), videoSize.toRect(), displayInfo.getLayerStack());
                Ln.d("Display: using non-secure SurfaceControl mirror fallback");
            } catch (Exception surfaceControlError) {
                surfaceControlError.addSuppressed(displayManagerError);
                destroyCurrentDisplay();
                throw new IOException("Could not create primary-display mirror", surfaceControlError);
            }
        }

        if (displayListener != null) {
            displayListener.onCaptureDisplay(displayId, PositionMapper.create(videoSize, displayInfo.getSize()));
        }
    }

    @Override
    public void release() {
        displayMonitor.stopAndRelease();
        destroyCurrentDisplay();
    }

    @Override
    public Size getSize() {
        return videoSize;
    }

    @Override
    protected boolean applyNewVideoConstraints(VideoConstraints videoConstraints) {
        this.videoConstraints = videoConstraints;
        return true;
    }

    private void destroyCurrentDisplay() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (display != null) {
            SurfaceControl.destroyDisplay(display);
            display = null;
        }
    }

    private static IBinder createDisplay(String name) throws Exception {
        // Non-secure is mandatory: FLAG_SECURE surfaces must stay blank in the stream.
        return SurfaceControl.createNonSecureDisplay(name);
    }

    /** Exercises the exact session capture setup without consuming or forwarding a frame. */
    public static boolean probePrimaryDisplayCapture() {
        DisplayInfo info = ServiceManager.getDisplayManager().getDisplayInfo(0);
        if (info == null) {
            return false;
        }
        ImageReader reader = null;
        IBinder probeDisplay = null;
        VirtualDisplay probeVirtualDisplay = null;
        try {
            reader = ImageReader.newInstance(16, 16, PixelFormat.RGBA_8888, 2);
            Throwable displayManagerError;
            try {
                probeVirtualDisplay = ServiceManager.getDisplayManager().createMirrorDisplay(
                        "notisync-screen-probe",
                        16,
                        16,
                        0,
                        reader.getSurface()
                );
                return true;
            } catch (Throwable error) {
                displayManagerError = error;
            }
            try {
                probeDisplay = createDisplay("notisync-screen-probe");
                setDisplaySurface(probeDisplay, reader.getSurface(), info.getSize().toRect(), new Rect(0, 0, 16, 16), info.getLayerStack());
                return true;
            } catch (Throwable surfaceControlError) {
                surfaceControlError.addSuppressed(displayManagerError);
                Ln.w("Display capture setup probe failed", surfaceControlError);
                return false;
            }
        } finally {
            if (probeVirtualDisplay != null) {
                try {
                    probeVirtualDisplay.release();
                } catch (Throwable error) {
                    Ln.w("Could not release display capture probe", error);
                }
            }
            if (probeDisplay != null) {
                try {
                    SurfaceControl.destroyDisplay(probeDisplay);
                } catch (Throwable error) {
                    Ln.w("Could not destroy display capture probe", error);
                }
            }
            if (reader != null) {
                reader.close();
            }
        }
    }

    private static void setDisplaySurface(IBinder display, Surface surface, Rect deviceRect, Rect displayRect, int layerStack) {
        SurfaceControl.openTransaction();
        try {
            SurfaceControl.setDisplaySurface(display, surface);
            SurfaceControl.setDisplayProjection(display, 0, deviceRect, displayRect);
            SurfaceControl.setDisplayLayerStack(display, layerStack);
        } finally {
            SurfaceControl.closeTransaction();
        }
    }
}

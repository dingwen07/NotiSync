package com.genymobile.scrcpy.display;

import com.genymobile.scrcpy.device.Device;
import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.wrappers.DisplayWindowListener;
import com.genymobile.scrcpy.wrappers.ServiceManager;

import android.content.res.Configuration;
import android.view.IDisplayWindowListener;

/** Tracks rotation, resolution, and fold-driven display configuration changes on API 34+. */
public final class DisplayMonitor {

    public interface Listener {
        void onDisplayPropertiesChanged(DisplayProperties props);
    }

    private int displayId = Device.DISPLAY_ID_NONE;
    private DisplayProperties props;
    private Listener listener;
    private IDisplayWindowListener displayWindowListener;

    public void start(int displayId, Listener listener) {
        if (listener == null || this.displayId != Device.DISPLAY_ID_NONE) {
            throw new IllegalStateException("Display monitor already started or missing listener");
        }
        this.listener = listener;
        this.displayId = displayId;
        displayWindowListener = new DisplayWindowListener() {
            @Override
            public void onDisplayConfigurationChanged(int eventDisplayId, Configuration newConfig) {
                if (eventDisplayId == displayId) {
                    checkDisplayPropertiesChanged();
                }
            }
        };
        ServiceManager.getWindowManager().registerDisplayWindowListener(displayWindowListener);
    }

    public void stopAndRelease() {
        if (displayWindowListener != null) {
            ServiceManager.getWindowManager().unregisterDisplayWindowListener(displayWindowListener);
            displayWindowListener = null;
        }
    }

    public synchronized void setSessionDisplayProperties(DisplayProperties props) {
        this.props = props;
    }

    private void checkDisplayPropertiesChanged() {
        DisplayInfo info = ServiceManager.getDisplayManager().getDisplayInfo(displayId);
        DisplayProperties newProps = info == null ? null : new DisplayProperties(info.getSize(), info.getRotation());
        DisplayProperties oldProps;
        synchronized (this) {
            oldProps = props;
            props = newProps;
        }
        if (!java.util.Objects.equals(newProps, oldProps)) {
            if (Ln.isEnabled(Ln.Level.VERBOSE)) {
                Ln.v("DisplayMonitor: " + oldProps + " -> " + newProps);
            }
            listener.onDisplayPropertiesChanged(newProps);
        }
    }
}

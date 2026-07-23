package com.genymobile.scrcpy.wrappers;

import com.genymobile.scrcpy.display.DisplayInfo;
import com.genymobile.scrcpy.model.Size;

import android.annotation.SuppressLint;
import android.hardware.display.VirtualDisplay;
import android.view.Surface;
import java.lang.reflect.Method;

/** Minimal hidden display API wrapper for primary-display mirroring. */
@SuppressLint("PrivateApi,DiscouragedPrivateApi")
public final class DisplayManager {

    private final Object manager;
    private Method getDisplayInfoMethod;
    private Method createVirtualDisplayMethod;

    static DisplayManager create() {
        try {
            Class<?> clazz = Class.forName("android.hardware.display.DisplayManagerGlobal");
            Object manager = clazz.getDeclaredMethod("getInstance").invoke(null);
            return new DisplayManager(manager);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private DisplayManager(Object manager) {
        this.manager = manager;
    }

    private synchronized Method getGetDisplayInfoMethod() throws NoSuchMethodException {
        if (getDisplayInfoMethod == null) {
            getDisplayInfoMethod = manager.getClass().getMethod("getDisplayInfo", int.class);
        }
        return getDisplayInfoMethod;
    }

    public DisplayInfo getDisplayInfo(int displayId) {
        try {
            Object info = getGetDisplayInfoMethod().invoke(manager, displayId);
            if (info == null) {
                return null;
            }
            Class<?> cls = info.getClass();
            int width = cls.getDeclaredField("logicalWidth").getInt(info);
            int height = cls.getDeclaredField("logicalHeight").getInt(info);
            int rotation = cls.getDeclaredField("rotation").getInt(info);
            int layerStack = cls.getDeclaredField("layerStack").getInt(info);
            int flags = cls.getDeclaredField("flags").getInt(info);
            return new DisplayInfo(new Size(width, height), rotation, layerStack, flags);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    /**
     * Hidden framework entry point used by scrcpy 4.1 to mirror an existing display. This is not
     * NotiSync's out-of-scope "new display" mode: {@code displayIdToMirror} is always the primary
     * display and the returned display exists only as a sink for the encoder surface.
     */
    private Method getCreateVirtualDisplayMethod() throws NoSuchMethodException {
        if (createVirtualDisplayMethod == null) {
            createVirtualDisplayMethod = android.hardware.display.DisplayManager.class.getMethod(
                    "createVirtualDisplay",
                    String.class,
                    int.class,
                    int.class,
                    int.class,
                    Surface.class
            );
        }
        return createVirtualDisplayMethod;
    }

    public VirtualDisplay createMirrorDisplay(String name, int width, int height, int displayIdToMirror,
            Surface surface) throws Exception {
        return (VirtualDisplay) getCreateVirtualDisplayMethod().invoke(
                null,
                name,
                width,
                height,
                displayIdToMirror,
                surface
        );
    }

}

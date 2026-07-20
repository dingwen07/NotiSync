package com.genymobile.scrcpy.wrappers;

import android.annotation.SuppressLint;
import android.graphics.Rect;
import android.os.IBinder;
import android.view.Surface;

/** SurfaceControl fallback limited to non-secure display mirroring. */
@SuppressLint("PrivateApi")
public final class SurfaceControl {

    private static final Class<?> CLASS;

    static {
        try {
            CLASS = Class.forName("android.view.SurfaceControl");
        } catch (ClassNotFoundException error) {
            throw new AssertionError(error);
        }
    }

    private SurfaceControl() {
    }

    public static void openTransaction() {
        invokeNoArgs("openTransaction");
    }

    public static void closeTransaction() {
        invokeNoArgs("closeTransaction");
    }

    private static void invokeNoArgs(String method) {
        try {
            CLASS.getMethod(method).invoke(null);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    public static void setDisplayProjection(IBinder displayToken, int orientation, Rect layerStackRect, Rect displayRect) {
        try {
            CLASS.getMethod("setDisplayProjection", IBinder.class, int.class, Rect.class, Rect.class)
                    .invoke(null, displayToken, orientation, layerStackRect, displayRect);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    public static void setDisplayLayerStack(IBinder displayToken, int layerStack) {
        try {
            CLASS.getMethod("setDisplayLayerStack", IBinder.class, int.class).invoke(null, displayToken, layerStack);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    public static void setDisplaySurface(IBinder displayToken, Surface surface) {
        try {
            CLASS.getMethod("setDisplaySurface", IBinder.class, Surface.class).invoke(null, displayToken, surface);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    public static IBinder createNonSecureDisplay(String name) throws Exception {
        // A non-secure display preserves Android's FLAG_SECURE blanking behavior.
        return (IBinder) CLASS.getMethod("createDisplay", String.class, boolean.class).invoke(null, name, false);
    }

    public static void destroyDisplay(IBinder displayToken) {
        try {
            CLASS.getMethod("destroyDisplay", IBinder.class).invoke(null, displayToken);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }
}

package com.genymobile.scrcpy.wrappers;

import com.genymobile.scrcpy.util.Ln;

import android.os.IInterface;

import java.lang.reflect.Method;

public final class PowerManager {

    private final IInterface manager;
    private Method isScreenOnMethod;

    static PowerManager create() {
        IInterface manager = ServiceManager.getService("power", "android.os.IPowerManager");
        return new PowerManager(manager);
    }

    private PowerManager(IInterface manager) {
        this.manager = manager;
    }

    private Method getIsScreenOnMethod() throws NoSuchMethodException {
        if (isScreenOnMethod == null) {
            isScreenOnMethod = manager.getClass().getMethod("isDisplayInteractive", int.class);
        }
        return isScreenOnMethod;
    }

    public boolean isScreenOn(int displayId) {

        try {
            Method method = getIsScreenOnMethod();
            return (boolean) method.invoke(manager, displayId);
        } catch (ReflectiveOperationException e) {
            Ln.e("Could not invoke method", e);
            return false;
        }
    }

}

package com.genymobile.scrcpy;

import com.genymobile.scrcpy.util.Ln;

import android.annotation.SuppressLint;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Minimal ActivityThread initialization required by display and clipboard framework APIs. */
@SuppressLint("PrivateApi,BlockedPrivateApi,SoonBlockedPrivateApi,DiscouragedPrivateApi")
public final class Workarounds {

    private static final Class<?> ACTIVITY_THREAD_CLASS;
    private static final Object ACTIVITY_THREAD;
    private static final boolean SYNTHETIC_ACTIVITY_THREAD;

    static {
        try {
            ACTIVITY_THREAD_CLASS = Class.forName("android.app.ActivityThread");
            Method currentActivityThread = ACTIVITY_THREAD_CLASS.getDeclaredMethod("currentActivityThread");
            currentActivityThread.setAccessible(true);
            Object existing = currentActivityThread.invoke(null);
            if (existing != null) {
                // A Shizuku v13 UserService has already bootstrapped app_process. Constructing a second
                // ActivityThread (the normal standalone scrcpy path) fails on current Android releases
                // and poisons this class with ExceptionInInitializerError.
                ACTIVITY_THREAD = existing;
                SYNTHETIC_ACTIVITY_THREAD = false;
            } else {
                Constructor<?> constructor = ACTIVITY_THREAD_CLASS.getDeclaredConstructor();
                constructor.setAccessible(true);
                ACTIVITY_THREAD = constructor.newInstance();

                Field current = ACTIVITY_THREAD_CLASS.getDeclaredField("sCurrentActivityThread");
                current.setAccessible(true);
                current.set(null, ACTIVITY_THREAD);

                Field systemThread = ACTIVITY_THREAD_CLASS.getDeclaredField("mSystemThread");
                systemThread.setAccessible(true);
                systemThread.setBoolean(ACTIVITY_THREAD, true);
                SYNTHETIC_ACTIVITY_THREAD = true;
            }
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private Workarounds() {
    }

    public static void apply() {
        // The real UserService ActivityThread is already fully initialized. The field surgery below
        // is only for scrcpy's standalone app_process bootstrap and must not overwrite Shizuku state.
        if (!SYNTHETIC_ACTIVITY_THREAD) {
            return;
        }
        if (Build.VERSION.SDK_INT >= AndroidVersions.API_31_ANDROID_12) {
            fillConfigurationController();
        }
        if (!Build.BRAND.equalsIgnoreCase("ONYX")) {
            fillAppInfo();
        }
        fillAppContext();
    }

    private static void fillAppInfo() {
        try {
            Class<?> appBindDataClass = Class.forName("android.app.ActivityThread$AppBindData");
            Constructor<?> appBindDataConstructor = appBindDataClass.getDeclaredConstructor();
            appBindDataConstructor.setAccessible(true);
            Object appBindData = appBindDataConstructor.newInstance();

            ApplicationInfo applicationInfo = new ApplicationInfo();
            applicationInfo.packageName = FakeContext.PACKAGE_NAME;
            Field appInfo = appBindDataClass.getDeclaredField("appInfo");
            appInfo.setAccessible(true);
            appInfo.set(appBindData, applicationInfo);

            Field boundApplication = ACTIVITY_THREAD_CLASS.getDeclaredField("mBoundApplication");
            boundApplication.setAccessible(true);
            boundApplication.set(ACTIVITY_THREAD, appBindData);
        } catch (Throwable error) {
            Ln.d("Could not fill app info: " + error.getMessage());
        }
    }

    private static void fillAppContext() {
        try {
            Application app = Instrumentation.newApplication(Application.class, FakeContext.get());
            Field initialApplication = ACTIVITY_THREAD_CLASS.getDeclaredField("mInitialApplication");
            initialApplication.setAccessible(true);
            initialApplication.set(ACTIVITY_THREAD, app);
        } catch (Throwable error) {
            Ln.d("Could not fill app context: " + error.getMessage());
        }
    }

    private static void fillConfigurationController() {
        try {
            Class<?> controllerClass = Class.forName("android.app.ConfigurationController");
            Class<?> activityThreadInternalClass = Class.forName("android.app.ActivityThreadInternal");
            Constructor<?> constructor = controllerClass.getDeclaredConstructor(activityThreadInternalClass);
            constructor.setAccessible(true);
            Object controller = constructor.newInstance(ACTIVITY_THREAD);

            Field controllerField = ACTIVITY_THREAD_CLASS.getDeclaredField("mConfigurationController");
            controllerField.setAccessible(true);
            controllerField.set(ACTIVITY_THREAD, controller);
        } catch (Throwable error) {
            Ln.d("Could not fill configuration: " + error.getMessage());
        }
    }

    static Context getSystemContext() {
        try {
            Method method = ACTIVITY_THREAD_CLASS.getDeclaredMethod("getSystemContext");
            return (Context) method.invoke(ACTIVITY_THREAD);
        } catch (Throwable error) {
            Ln.d("Could not get system context: " + error.getMessage());
            return null;
        }
    }
}

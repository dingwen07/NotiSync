package com.genymobile.scrcpy.wrappers;

import android.app.ActivityOptions;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import com.genymobile.scrcpy.FakeContext;

/** Minimal scrcpy 4.1 startActivityAsUser wrapper; no force-stop or arbitrary command entry point. */
public final class DisplayActivityLauncher {
    private DisplayActivityLauncher() { }

    @SuppressWarnings("deprecation") // Compatibility mode for Android 14-15.
    public static void launchLauncher(android.app.PendingIntent launcher, int displayId) throws Exception {
        if (displayId <= 0 || launcher == null || !launcher.isActivity()) {
            throw new IllegalArgumentException("Virtual display launcher required");
        }
        ActivityOptions options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId);
        options.setPendingIntentBackgroundActivityStartMode(android.os.Build.VERSION.SDK_INT >= 36
                ? ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS : ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
        launcher.send(FakeContext.get(), 0, null, null, null, null, options.toBundle());
    }

    public static void launch(String packageName, int displayId) throws Exception {
        if (displayId <= 0) throw new IllegalArgumentException("Virtual display required");
        PackageManager pm = FakeContext.get().getPackageManager();
        Intent intent;
        if (packageName == null) {
            intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_SECONDARY_HOME);
            ResolveInfo resolved = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
            if (resolved == null || resolved.activityInfo == null || !resolved.activityInfo.exported) {
                throw new IllegalStateException("No secondary launcher; select an app explicitly");
            }
            intent.setClassName(resolved.activityInfo.packageName, resolved.activityInfo.name);
        } else {
            intent = pm.getLaunchIntentForPackage(packageName);
            if (intent == null) intent = pm.getLeanbackLaunchIntentForPackage(packageName);
            if (intent == null) throw new IllegalStateException("App has no launchable activity");
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Bundle options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle();
        IInterface manager = ServiceManager.getService("activity", "android.app.IActivityManager");
        int result = (int) manager.getClass().getMethod("startActivityAsUser",
                Class.forName("android.app.IApplicationThread"), String.class, Intent.class, String.class,
                IBinder.class, String.class, int.class, int.class, Class.forName("android.app.ProfilerInfo"),
                Bundle.class, int.class).invoke(manager, null, FakeContext.PACKAGE_NAME, intent, null,
                        null, null, 0, 0, null, options, -2);
        if (result < 0 || result >= 100) throw new IllegalStateException("Activity launch rejected");
    }
}

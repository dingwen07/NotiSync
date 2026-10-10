package net.extrawdw.apps.notisync.hotspot.provider;

import android.annotation.SuppressLint;
import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.net.TetheringInterface;
import android.net.TetheringManager;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import androidx.annotation.RequiresApi;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Android 16+ shell implementation, based on the device-tested standalone helper. */
@RequiresApi(36)
@SuppressLint({"PrivateApi", "DiscouragedPrivateApi", "MissingPermission"})
final class HotspotPlatform implements AutoCloseable {
    private final WifiManager wifi;
    private final TetheringManager tethering;
    private final TetheringManager.TetheringRequest request =
            new TetheringManager.TetheringRequest.Builder(TetheringManager.TETHERING_WIFI).build();
    private volatile boolean wifiTethered;
    private final CountDownLatch initial = new CountDownLatch(1);
    private final TetheringManager.TetheringEventCallback events;

    HotspotPlatform(Runnable changed) throws Exception {
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("currentActivityThread").invoke(null);
        if (thread == null) thread = activityThread.getMethod("systemMain").invoke(null);
        Context system = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
        Context context = new ContextWrapper(system.createPackageContext("com.android.shell", 0)) {
            @Override public String getPackageName() { return "com.android.shell"; }
            @Override public String getOpPackageName() { return "com.android.shell"; }
            @Override public AttributionSource getAttributionSource() {
                return new AttributionSource.Builder(Process.myUid()).setPackageName("com.android.shell").build();
            }
        };
        wifi = context.getSystemService(WifiManager.class);
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "tethering");
        if (wifi == null || binder == null) throw new IllegalStateException("service unavailable");
        // The base context's cached manager retains the wrong operation package on tested ROMs.
        tethering = TetheringManager.class.getConstructor(Context.class, Supplier.class)
                .newInstance(context, (Supplier<IBinder>) () -> binder);
        events = new TetheringManager.TetheringEventCallback() {
            @Override public void onTetheredInterfacesChanged(Set<TetheringInterface> values) {
                wifiTethered = values.stream().anyMatch(value -> value.getType() == TetheringManager.TETHERING_WIFI);
                initial.countDown();
                changed.run();
            }
        };
        tethering.registerTetheringEventCallback(Runnable::run, events);
    }

    private static Object invoke(Object target, String method) throws Exception {
        return target.getClass().getMethod(method).invoke(target);
    }

    private int apState() throws Exception { return (Integer) invoke(wifi, "getWifiApState"); }

    Bundle query() throws Exception {
        if (!initial.await(5, TimeUnit.SECONDS)) return failure("TIMEOUT", null);
        Bundle result = new Bundle();
        result.putString("result", "OK");
        result.putInt("apState", apState());
        result.putBoolean("wifiTethered", wifiTethered);
        Object config = invoke(wifi, "getSoftApConfiguration");
        if (config != null) {
            result.putString("ssid", (String) invoke(config, "getSsid"));
            String password = (String) invoke(config, "getPassphrase");
            if (password != null && !password.isEmpty() && !password.equals("*")) result.putString("psk", password);
            result.putInt("securityType", (Integer) invoke(config, "getSecurityType"));
            result.putBoolean("hiddenSsid", (Boolean) invoke(config, "isHiddenSsid"));
        }
        return result;
    }

    Bundle setEnabled(boolean enabled) throws Exception {
        if (!initial.await(5, TimeUnit.SECONDS)) return failure("TIMEOUT", null);
        int state = apState();
        if (state == (enabled ? 13 : 11) && wifiTethered == enabled) return query();
        if (state == 10 || state == 12) return failure("BUSY", null);
        long deadline = SystemClock.elapsedRealtime() + 20_000;
        CountDownLatch done = new CountDownLatch(1);
        AtomicInteger error = new AtomicInteger(-1);
        // No SoftApConfiguration override and no entitlement bypass: Android uses saved Settings values.
        if (enabled) {
            tethering.startTethering(request, Runnable::run, new TetheringManager.StartTetheringCallback() {
                @Override public void onTetheringStarted() { error.set(0); done.countDown(); }
                @Override public void onTetheringFailed(int code) { error.set(code); done.countDown(); }
            });
        } else {
            tethering.stopTethering(request, Runnable::run, new TetheringManager.StopTetheringCallback() {
                @Override public void onStopTetheringSucceeded() { error.set(0); done.countDown(); }
                @Override public void onStopTetheringFailed(int code) { error.set(code); done.countDown(); }
            });
        }
        if (!done.await(20, TimeUnit.SECONDS)) return failure("TIMEOUT", null);
        if (error.get() != 0) return failure("FAILED", error.get());
        do {
            if (apState() == (enabled ? 13 : 11) && wifiTethered == enabled) return query();
            Thread.sleep(200);
        } while (SystemClock.elapsedRealtime() < deadline);
        return failure("TIMEOUT", null);
    }

    static Bundle failure(String result, Integer error) {
        Bundle value = new Bundle();
        value.putString("result", result);
        if (error != null) value.putInt("platformError", error);
        return value;
    }

    @Override public void close() { tethering.unregisterTetheringEventCallback(events); }
}

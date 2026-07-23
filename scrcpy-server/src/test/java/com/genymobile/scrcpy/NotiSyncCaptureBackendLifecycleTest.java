package com.genymobile.scrcpy;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.os.ParcelFileDescriptor;

import com.genymobile.scrcpy.video.VideoCodec;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.Test;

/** Exact-owner and synchronous-cleanup regression tests without starting Android capture APIs. */
public final class NotiSyncCaptureBackendLifecycleTest {

    @Test
    public void staleOwnerCannotStopActiveCapture() throws Exception {
        NotiSyncCaptureBackend backend = new NotiSyncCaptureBackend();
        Field activeField = activeSessionField();
        CountDownLatch finished = new CountDownLatch(1);
        Object active = newSession("lease-current", ignored -> finished.countDown());
        activeField.set(backend, active);

        assertFalse(backend.stopSession("lease-stale"));
        assertSame(active, activeField.get(backend));
        assertFalse(finished.await(100, TimeUnit.MILLISECONDS));

        // Avoid leaving the synthetic session installed after the assertion.
        activeField.set(backend, null);
    }

    @Test
    public void exactStopWaitsThroughFinishedCallbackAndClearsSlot() throws Exception {
        NotiSyncCaptureBackend backend = new NotiSyncCaptureBackend();
        Field activeField = activeSessionField();
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch allowCallbackReturn = new CountDownLatch(1);
        Object active = newSession("lease-current", finished -> {
            try {
                activeField.set(backend, null);
                callbackEntered.countDown();
                assertTrue(allowCallbackReturn.await(2, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            } catch (IllegalAccessException e) {
                throw new AssertionError(e);
            }
        });
        activeField.set(backend, active);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> stop = executor.submit(() -> backend.stopSession("lease-current"));
            assertTrue(callbackEntered.await(2, TimeUnit.SECONDS));
            assertFalse("stop returned before backend cleanup callback completed", stop.isDone());
            allowCallbackReturn.countDown();
            assertTrue(stop.get(2, TimeUnit.SECONDS));
            assertNull(activeField.get(backend));
        } finally {
            allowCallbackReturn.countDown();
            executor.shutdownNow();
        }
    }

    private static Field activeSessionField() throws Exception {
        Field field = NotiSyncCaptureBackend.class.getDeclaredField("session");
        field.setAccessible(true);
        return field;
    }

    private static Object newSession(String ownerToken, Consumer<Object> finished) throws Exception {
        Class<?> sessionClass = Class.forName(NotiSyncCaptureBackend.class.getName() + "$Session");
        Constructor<?> constructor = sessionClass.getDeclaredConstructor(
                String.class,
                VideoCodec.class,
                String.class,
                int.class,
                int.class,
                int.class,
                boolean.class,
                boolean.class,
                ParcelFileDescriptor.class,
                ParcelFileDescriptor.class,
                Consumer.class
        );
        constructor.setAccessible(true);
        return constructor.newInstance(
                ownerToken,
                VideoCodec.H264,
                "test-encoder",
                1920,
                60,
                8_000_000,
                true,
                true,
                null,
                null,
                finished
        );
    }
}

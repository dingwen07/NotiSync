package com.genymobile.scrcpy.video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

public final class CaptureControlVisibilityTest {

    @Test
    public void hideRequestsResetAndShowReleasesWaiter() throws Exception {
        CaptureControl control = new CaptureControl();
        assertTrue(control.isVideoVisible());

        control.setVideoVisible(false);
        assertFalse(control.isVideoVisible());
        assertEquals(CaptureControl.RESET_REASON_VIDEO_HIDDEN, control.consumeReset());

        CountDownLatch waiting = new CountDownLatch(1);
        AtomicBoolean resumed = new AtomicBoolean();
        Thread waiter = new Thread(() -> {
            waiting.countDown();
            try {
                resumed.set(control.awaitVideoVisible());
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        });
        waiter.start();
        assertTrue(waiting.await(1, TimeUnit.SECONDS));

        control.setVideoVisible(true);
        waiter.join(1_000);
        assertFalse(waiter.isAlive());
        assertTrue(resumed.get());
        assertTrue(control.isVideoVisible());
        assertEquals(0, control.consumeReset());
    }

    @Test
    public void terminationReleasesHiddenWaiterWithoutResumingVideo() throws Exception {
        CaptureControl control = new CaptureControl();
        control.setVideoVisible(false);
        control.consumeReset();

        CountDownLatch waiting = new CountDownLatch(1);
        AtomicBoolean result = new AtomicBoolean(true);
        Thread waiter = new Thread(() -> {
            waiting.countDown();
            try {
                result.set(control.awaitVideoVisible());
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        });
        waiter.start();
        assertTrue(waiting.await(1, TimeUnit.SECONDS));

        control.reset(CaptureControl.RESET_REASON_TERMINATED);
        waiter.join(1_000);
        assertFalse(waiter.isAlive());
        assertFalse(result.get());
        assertFalse(control.isVideoVisible());
    }

    @Test
    public void rapidHideShowKeepsFreshSessionResetPending() {
        CaptureControl control = new CaptureControl();

        control.setVideoVisible(false);
        control.setVideoVisible(true);

        assertTrue(control.isVideoVisible());
        assertEquals(CaptureControl.RESET_REASON_VIDEO_HIDDEN, control.consumeReset());
        control.setVideoVisible(true);
        assertEquals(0, control.consumeReset());
    }
}

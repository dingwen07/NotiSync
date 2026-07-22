package com.genymobile.scrcpy.video;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class VideoBackpressureControllerTest {

    @Test
    public void slowWriteReducesBitrateAndRequestsFreshSyncFrame() {
        VideoBackpressureController controller = new VideoBackpressureController(8_000_000);

        VideoBackpressureController.Decision decision = controller.onPacketWritten(
                VideoBackpressureController.SLOW_WRITE_NANOS,
                1_000_000_000L
        );

        assertTrue(decision.requestSyncFrame);
        assertEquals(6_000_000, decision.newBitRate);
        assertEquals(6_000_000, controller.getCurrentBitRate());
    }

    @Test
    public void congestionDropsInterFramesOnlyUntilRequestedKeyFrame() {
        VideoBackpressureController controller = new VideoBackpressureController(8_000_000);
        controller.beginDroppingUntilKeyFrame(true);

        assertTrue(controller.shouldWritePacket(true, false));
        assertFalse(controller.shouldWritePacket(false, false));
        assertTrue(controller.shouldWritePacket(false, true));
        assertTrue(controller.shouldWritePacket(false, false));
    }

    @Test
    public void stableWritesRecoverBitrateGradually() {
        VideoBackpressureController controller = new VideoBackpressureController(8_000_000);
        controller.onPacketWritten(VideoBackpressureController.SLOW_WRITE_NANOS, 1_000_000_000L);

        VideoBackpressureController.Decision decision = controller.onPacketWritten(
                1_000_000L,
                1_000_000_000L + VideoBackpressureController.RECOVERY_STABLE_NANOS
        );

        assertFalse(decision.requestSyncFrame);
        assertEquals(7_500_000, decision.newBitRate);
    }
}

package net.extrawdw.apps.notisync.screen

import net.extrawdw.notisync.protocol.ClientId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidScreenPictureInPicturePolicyTest {
    @Test
    fun `connected supported viewer with valid dimensions may auto enter`() {
        val policy = androidScreenPictureInPicturePolicy(
            supported = true,
            connected = true,
            sourceWidth = 1080,
            sourceHeight = 2400,
        )

        assertTrue(policy.supported)
        assertTrue(policy.eligible)
        assertEquals(
            AndroidScreenPictureInPictureAspectRatio(9, 20),
            policy.aspectRatioParts,
        )
    }

    @Test
    fun `unsupported device never becomes eligible`() {
        val policy = androidScreenPictureInPicturePolicy(
            supported = false,
            connected = true,
            sourceWidth = 1920,
            sourceHeight = 1080,
        )

        assertFalse(policy.supported)
        assertFalse(policy.eligible)
        // The ratio is still useful if platform support changes while the session remains active.
        assertEquals(
            AndroidScreenPictureInPictureAspectRatio(16, 9),
            policy.aspectRatioParts,
        )
    }

    @Test
    fun `disconnected viewer never becomes eligible`() {
        val policy = androidScreenPictureInPicturePolicy(
            supported = true,
            connected = false,
            sourceWidth = 1080,
            sourceHeight = 1920,
        )

        assertFalse(policy.eligible)
        assertEquals(
            AndroidScreenPictureInPictureAspectRatio(9, 16),
            policy.aspectRatioParts,
        )
    }

    @Test
    fun `missing or non-positive dimensions reject auto enter`() {
        listOf(
            null to 1080,
            1920 to null,
            0 to 1080,
            1920 to 0,
            -1 to 1080,
            1920 to -1,
        ).forEach { (width, height) ->
            val policy = androidScreenPictureInPicturePolicy(
                supported = true,
                connected = true,
                sourceWidth = width,
                sourceHeight = height,
            )

            assertFalse(policy.eligible)
            assertNull(policy.aspectRatioParts)
        }
    }

    @Test
    fun `extreme wide and tall ratios clamp to platform bounds`() {
        assertEquals(
            AndroidScreenPictureInPictureAspectRatio(239, 100),
            androidScreenPictureInPicturePolicy(true, true, 4000, 1000).aspectRatioParts,
        )
        assertEquals(
            AndroidScreenPictureInPictureAspectRatio(100, 239),
            androidScreenPictureInPicturePolicy(true, true, 1000, 4000).aspectRatioParts,
        )
    }

    @Test
    fun `platform boundary ratios remain inclusive`() {
        assertEquals(
            AndroidScreenPictureInPictureAspectRatio(239, 100),
            androidScreenPictureInPicturePolicy(true, true, 2390, 1000).aspectRatioParts,
        )
        assertEquals(
            AndroidScreenPictureInPictureAspectRatio(100, 239),
            androidScreenPictureInPicturePolicy(true, true, 1000, 2390).aspectRatioParts,
        )
    }

    @Test
    fun `large dimensions cannot overflow ratio comparisons`() {
        assertEquals(
            AndroidScreenPictureInPictureAspectRatio(239, 100),
            androidScreenPictureInPicturePolicy(
                supported = true,
                connected = true,
                sourceWidth = Int.MAX_VALUE,
                sourceHeight = 1,
            ).aspectRatioParts,
        )
    }

    @Test
    fun `matching terminal attempt removes only background or pip renderer`() {
        val source = ClientId("source")
        val terminal = AndroidScreenHostState(
            phase = AndroidScreenHostPhase.ENDED,
            attemptId = "attempt-1",
            sourceId = source,
        )

        assertTrue(
            shouldFinishTerminatedScreenViewer(
                source,
                observedAttemptId = "attempt-1",
                hostState = terminal,
                inPictureInPicture = true,
                renderingAllowed = true,
            ),
        )
        assertTrue(
            shouldFinishTerminatedScreenViewer(
                source,
                observedAttemptId = "attempt-1",
                hostState = terminal,
                inPictureInPicture = false,
                renderingAllowed = false,
            ),
        )
        assertFalse(
            shouldFinishTerminatedScreenViewer(
                source,
                observedAttemptId = "attempt-1",
                hostState = terminal,
                inPictureInPicture = false,
                renderingAllowed = true,
            ),
        )
    }

    @Test
    fun `stale or unobserved terminal attempt cannot close a replacement viewer`() {
        val source = ClientId("source")
        val terminal = AndroidScreenHostState(
            phase = AndroidScreenHostPhase.ERROR,
            attemptId = "old-attempt",
            sourceId = source,
        )

        assertFalse(
            shouldFinishTerminatedScreenViewer(
                source,
                observedAttemptId = null,
                hostState = terminal,
                inPictureInPicture = true,
                renderingAllowed = true,
            ),
        )
        assertFalse(
            shouldFinishTerminatedScreenViewer(
                source,
                observedAttemptId = "new-attempt",
                hostState = terminal,
                inPictureInPicture = true,
                renderingAllowed = true,
            ),
        )
        assertFalse(
            shouldFinishTerminatedScreenViewer(
                ClientId("other-source"),
                observedAttemptId = "old-attempt",
                hostState = terminal,
                inPictureInPicture = true,
                renderingAllowed = true,
            ),
        )
        assertFalse(
            shouldFinishTerminatedScreenViewer(
                source,
                observedAttemptId = "old-attempt",
                hostState = terminal.copy(phase = AndroidScreenHostPhase.CONNECTED),
                inPictureInPicture = true,
                renderingAllowed = true,
            ),
        )
    }
}

package net.extrawdw.apps.notisync.screen

import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ScreenMirrorAction
import net.extrawdw.notisync.protocol.ScreenMirrorSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenMirrorSessionOwnershipTest {
    private val source = ClientId("source-peer")
    private val requester = ClientId("requester-peer")
    private val otherRequester = ClientId("other-requester")

    private fun sync(
        action: ScreenMirrorAction,
        sessionId: String = "session-1",
        requesterPeerId: ClientId = requester,
        sourcePeerId: ClientId = source,
    ) = ScreenMirrorSync(
        action = action,
        sessionId = sessionId,
        requesterPeerId = requesterPeerId,
        sourcePeerId = sourcePeerId,
        issuedAt = 1_000L,
    )

    @Test
    fun authenticatedStopMustMatchRecordedRequesterAndSession() {
        val active = sync(ScreenMirrorAction.REQUEST)

        assertTrue(
            AuthenticatedScreenStop(
                request = sync(ScreenMirrorAction.CANCEL),
                senderId = requester,
                senderOwnDevice = true,
            ).permits(active, source),
        )
        assertTrue(
            AuthenticatedScreenStop(
                request = sync(ScreenMirrorAction.END),
                senderId = requester,
                senderOwnDevice = true,
            ).permits(active, source),
        )

        // A different trusted own device cannot stop the active controller merely by copying the
        // session ID and naming itself in an otherwise valid signed CANCEL.
        assertFalse(
            AuthenticatedScreenStop(
                request = sync(
                    ScreenMirrorAction.CANCEL,
                    requesterPeerId = otherRequester,
                ),
                senderId = otherRequester,
                senderOwnDevice = true,
            ).permits(active, source),
        )
        assertFalse(
            AuthenticatedScreenStop(
                request = sync(ScreenMirrorAction.CANCEL, sessionId = "session-2"),
                senderId = requester,
                senderOwnDevice = true,
            ).permits(active, source),
        )
    }

    @Test
    fun authenticatedStopRejectsBodySubstitutionAndNonOwnSender() {
        val active = sync(ScreenMirrorAction.REQUEST)

        assertFalse(
            AuthenticatedScreenStop(
                request = sync(ScreenMirrorAction.CANCEL),
                senderId = otherRequester,
                senderOwnDevice = true,
            ).permits(active, source),
        )
        assertFalse(
            AuthenticatedScreenStop(
                request = sync(ScreenMirrorAction.CANCEL, sourcePeerId = ClientId("other-source")),
                senderId = requester,
                senderOwnDevice = true,
            ).permits(active, source),
        )
        assertFalse(
            AuthenticatedScreenStop(
                request = sync(ScreenMirrorAction.CANCEL),
                senderId = requester,
                senderOwnDevice = false,
            ).permits(active, source),
        )
        assertFalse(
            AuthenticatedScreenStop(
                request = sync(ScreenMirrorAction.STATUS),
                senderId = requester,
                senderOwnDevice = true,
            ).permits(active, source),
        )
    }

    @Test
    fun completedForegroundLeaseCannotTearDownImmediateReplacement() {
        val ownership = ScreenMirrorForegroundOwnership()

        assertEquals(
            ScreenMirrorForegroundOwnership.StartDecision.ACQUIRED,
            ownership.onStart("old-session", startId = 10),
        )
        assertTrue(ownership.markForegroundOwned("old-session"))
        assertEquals(
            ScreenMirrorForegroundOwnership.StartDecision.DUPLICATE,
            ownership.onStart("old-session", startId = 11),
        )
        assertEquals(
            ScreenMirrorForegroundOwnership.StartDecision.CONFLICT,
            ownership.onStart("stale-session", startId = 12),
        )
        assertTrue(ownership.noteCommand(startId = 13))

        val oldLease = ownership.finish("old-session")
        assertEquals(13, oldLease?.latestStartId)
        assertTrue(oldLease?.foregroundOwned == true)

        // The controller publishes IDLE only after the call above. If a replacement START is then
        // delivered, Android gives it a newer startId; stopSelf(oldLease.latestStartId) cannot stop
        // it, and a delayed duplicate completion cannot clear its ownership record.
        assertEquals(
            ScreenMirrorForegroundOwnership.StartDecision.ACQUIRED,
            ownership.onStart("new-session", startId = 14),
        )
        assertTrue(ownership.markForegroundOwned("new-session"))
        assertNull(ownership.finish("old-session"))
        assertTrue(ownership.isOwnedBy("new-session"))

        val newLease = ownership.finish("new-session")
        assertEquals(14, newLease?.latestStartId)
        assertTrue(newLease?.foregroundOwned == true)
        assertFalse(ownership.isOwnedBy("new-session"))
    }
}

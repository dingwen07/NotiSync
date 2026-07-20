package net.extrawdw.apps.notisync.channel

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.cbor.CborLabel
import net.extrawdw.notisync.peer.channel.Recipients
import net.extrawdw.notisync.peer.channel.RetryableDeliveryException

import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.notisync.testsupport.CapturingTransport
import net.extrawdw.apps.notisync.testsupport.FakeTrustState
import net.extrawdw.apps.notisync.testsupport.newHpke
import net.extrawdw.apps.notisync.testsupport.newOperationalSigner
import net.extrawdw.apps.notisync.testsupport.newSigner
import net.extrawdw.apps.notisync.testsupport.peerOf
import net.extrawdw.apps.notisync.testsupport.seal
import net.extrawdw.apps.notisync.testsupport.sealOperational
import net.extrawdw.apps.notisync.testsupport.testChannel
import net.extrawdw.apps.notisync.transport.DeliveryMode
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.Urgency
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The generic substrate: dedup, sender-auth, signature verify, HPKE-open, and audience scoping. */
class SecureChannelTest {

    private fun channel(
        me: net.extrawdw.notisync.protocol.crypto.IdentitySigner,
        myHpkePrivate: ByteArray,
        trust: FakeTrustState,
        transport: CapturingTransport = CapturingTransport(),
        onBadSignature: (ClientId, Long, DeliveryMode) -> Unit = { _, _, _ -> },
    ) = testChannel(me, myHpkePrivate, trust, transport, onBadSignature)

    @Test
    fun deliversDecryptedBody_withAuthenticatedSenderAndOwnFlag_toRegisteredHandler() {
        val me = newSigner();
        val myHpke = newHpke()
        val sender = newSigner();
        val senderHpke = newHpke()
        val trust = FakeTrustState().apply {
            peers.value = listOf(peerOf(sender, senderHpke.publicKeyset, ownDevice = true))
        }
        val channel = channel(me, myHpke.privateKeyset, trust)

        val received = mutableListOf<InboundMessage>()
        channel.onMessage(MessageType.NOTIFICATION) { received.add(it) }
        channel.deliver(
            seal(
                sender,
                MessageType.NOTIFICATION,
                byteArrayOf(7, 8, 9),
                me.clientId,
                myHpke.publicKeyset,
                "n1"
            )
        )

        assertEquals(1, received.size)
        assertEquals(sender.clientId, received.single().senderId)
        assertTrue(received.single().senderOwnDevice)
        assertArrayEquals(byteArrayOf(7, 8, 9), received.single().body)
    }

    @Test
    fun surfacesOtherDeviceFlag_butNeverDropsOnIt() {
        val me = newSigner();
        val myHpke = newHpke()
        val other = newSigner();
        val otherHpke = newHpke()
        val trust = FakeTrustState().apply {
            peers.value = listOf(peerOf(other, otherHpke.publicKeyset, ownDevice = false))
        }
        val channel = channel(me, myHpke.privateKeyset, trust)

        val received = mutableListOf<InboundMessage>()
        channel.onMessage(MessageType.NOTIFICATION) { received.add(it) }
        channel.deliver(
            seal(
                other,
                MessageType.NOTIFICATION,
                byteArrayOf(1),
                me.clientId,
                myHpke.publicKeyset,
                "n1"
            )
        )

        // The channel itself never applies an ownDevice gate — it forwards with the flag; policy is a caller concern.
        assertEquals(1, received.size)
        assertEquals(false, received.single().senderOwnDevice)
    }

    @Test
    fun dropsDuplicateMessageId_acrossDeliveries() {
        val me = newSigner();
        val myHpke = newHpke()
        val sender = newSigner();
        val senderHpke = newHpke()
        val trust =
            FakeTrustState().apply { peers.value = listOf(peerOf(sender, senderHpke.publicKeyset)) }
        val channel = channel(me, myHpke.privateKeyset, trust)

        var count = 0
        channel.onMessage(MessageType.NOTIFICATION) { count++ }
        val env = seal(
            sender,
            MessageType.NOTIFICATION,
            byteArrayOf(1),
            me.clientId,
            myHpke.publicKeyset,
            "dup"
        )
        channel.deliver(env)
        channel.deliver(env) // same messageId — FCM/WebSocket double delivery

        assertEquals("a duplicate messageId must be handled exactly once", 1, count)
    }

    @Test
    fun dropsUnknownSender() {
        val me = newSigner();
        val myHpke = newHpke()
        val stranger = newSigner();
        val strangerHpke = newHpke()
        val channel = channel(me, myHpke.privateKeyset, FakeTrustState()) // empty roster

        var count = 0
        channel.onMessage(MessageType.NOTIFICATION) { count++ }
        channel.deliver(
            seal(
                stranger,
                MessageType.NOTIFICATION,
                byteArrayOf(1),
                me.clientId,
                myHpke.publicKeyset,
                "x"
            )
        )

        assertEquals(0, count)
    }

    @Test
    fun dropsBadSignature_andNotifies() {
        val me = newSigner();
        val myHpke = newHpke()
        val sender = newSigner();
        val senderHpke = newHpke()
        val trust =
            FakeTrustState().apply { peers.value = listOf(peerOf(sender, senderHpke.publicKeyset)) }
        val rejected = mutableListOf<Pair<ClientId, DeliveryMode>>()
        val channel = channel(
            me,
            myHpke.privateKeyset,
            trust,
            onBadSignature = { id, _, deliveryMode -> rejected.add(id to deliveryMode) })

        var count = 0
        channel.onMessage(MessageType.NOTIFICATION) { count++ }
        val tampered = seal(
            sender,
            MessageType.NOTIFICATION,
            byteArrayOf(1),
            me.clientId,
            myHpke.publicKeyset,
            "t"
        )
            .let { it.copy(sig = ByteArray(it.sig.size)) }
        channel.deliver(tampered, DeliveryMode.FCM_INLINE)

        assertEquals(0, count)
        assertEquals(listOf(sender.clientId to DeliveryMode.FCM_INLINE), rejected)
    }

    @Test
    fun send_sealsToOwnMesh_andReturnsRecipientCount() {
        val me = newSigner();
        val myHpke = newHpke()
        val own1 = newSigner();
        val own1Hpke = newHpke()
        val own2 = newSigner();
        val own2Hpke = newHpke()
        val other = newSigner();
        val otherHpke = newHpke()
        val trust = FakeTrustState().apply {
            peers.value = listOf(
                peerOf(own1, own1Hpke.publicKeyset, ownDevice = true),
                peerOf(own2, own2Hpke.publicKeyset, ownDevice = true),
                peerOf(other, otherHpke.publicKeyset, ownDevice = false),
            )
        }
        val transport = CapturingTransport()
        val channel = channel(me, myHpke.privateKeyset, trust, transport)

        val n = runBlocking {
            channel.send(
                MessageType.NOTIFICATION,
                byteArrayOf(1),
                Recipients.OwnMesh,
                Urgency.HIGH
            )
        }

        assertEquals(2, n)
        assertEquals(
            setOf(own1.clientId, own2.clientId),
            transport.envelopes.single().recipientIds().toSet()
        )
    }

    @Test
    fun send_toEmptyAudience_returnsZero_andSendsNothing() {
        val me = newSigner();
        val myHpke = newHpke()
        val transport = CapturingTransport()
        val channel = channel(me, myHpke.privateKeyset, FakeTrustState(), transport)

        val n = runBlocking {
            channel.send(
                MessageType.NOTIFICATION,
                byteArrayOf(1),
                Recipients.OwnMesh,
                Urgency.HIGH
            )
        }

        assertEquals(0, n)
        assertTrue(transport.sent.isEmpty())
    }

    /** An in-memory stand-in for the app's persisted dedup, to exercise the cross-restart path. */
    private class FakeDedup : MessageDedup {
        val ids = java.util.Collections.synchronizedSet(HashSet<String>())
        override fun seen(messageId: String) = messageId in ids
        override fun record(messageId: String) {
            ids.add(messageId)
        }
    }

    @Test
    fun deliver_reportsOutcomes_andRecordsHandledIdsDurably() {
        val me = newSigner();
        val myHpke = newHpke()
        val sender = newSigner();
        val senderHpke = newHpke()
        val trust =
            FakeTrustState().apply { peers.value = listOf(peerOf(sender, senderHpke.publicKeyset)) }
        val dedup = FakeDedup()
        val channel = testChannel(me, myHpke.privateKeyset, trust, dedup = dedup)
        channel.onMessage(MessageType.NOTIFICATION) { }

        val env = seal(
            sender,
            MessageType.NOTIFICATION,
            byteArrayOf(1),
            me.clientId,
            myHpke.publicKeyset,
            "m1"
        )
        assertEquals(DeliveryOutcome.HANDLED, channel.deliver(env))
        assertTrue(
            "a handled id must be recorded durably so a restart still dedups it",
            dedup.seen("m1")
        )
        assertEquals(DeliveryOutcome.DUPLICATE, channel.deliver(env))
    }

    @Test
    fun retryableHandlerFailure_isNotAcknowledgedOrDeduplicated() {
        val me = newSigner()
        val myHpke = newHpke()
        val sender = newSigner()
        val senderHpke = newHpke()
        val trust = FakeTrustState().apply {
            peers.value = listOf(peerOf(sender, senderHpke.publicKeyset))
        }
        val dedup = FakeDedup()
        val channel = testChannel(me, myHpke.privateKeyset, trust, dedup = dedup)
        channel.onMessage(MessageType.ACTION) { throw RetryableDeliveryException("disk unavailable") }
        val envelope = seal(
            sender,
            MessageType.ACTION,
            byteArrayOf(1),
            me.clientId,
            myHpke.publicKeyset,
            "durable-action",
        )

        assertEquals(DeliveryOutcome.DROPPED, channel.deliver(envelope))
        assertFalse(dedup.seen("durable-action"))
        assertEquals(DeliveryOutcome.DROPPED, channel.deliver(envelope))
    }

    @Test
    fun futureNotificationEnum_isAlreadyContainedBySecureChannel_withoutCrashingReceiveLoop() {
        val me = newSigner()
        val myHpke = newHpke()
        val sender = newSigner()
        val senderHpke = newHpke()
        val trust = FakeTrustState().apply {
            peers.value = listOf(peerOf(sender, senderHpke.publicKeyset))
        }
        val dedup = FakeDedup()
        val channel = testChannel(me, myHpke.privateKeyset, trust, dedup = dedup)
        val body = ProtocolCodec.encodeToCbor(
            FutureCapturedNotification(FutureNotifStyle.MEDIA),
        )

        val strictFailure = assertThrows(SerializationException::class.java) {
            ProtocolCodec.decodeFromCbor<LegacyCapturedNotification>(body)
        }
        assertTrue(strictFailure.message.orEmpty().contains("NotifStyle"))
        assertTrue(strictFailure.message.orEmpty().contains("MEDIA"))

        var rendered = 0
        channel.onMessage(MessageType.NOTIFICATION) { message ->
            // Production handlers decode strictly. SecureChannel's existing handler boundary owns
            // containment and durable deduplication for a mixed-version poison body.
            val notification = ProtocolCodec.decodeFromCbor<LegacyCapturedNotification>(message.body)
            if (notification.style == LegacyNotifStyle.DEFAULT) rendered++
        }
        val envelope = seal(
            sender,
            MessageType.NOTIFICATION,
            body,
            me.clientId,
            myHpke.publicKeyset,
            "future-notification-style",
        )

        assertEquals(DeliveryOutcome.HANDLED, channel.deliver(envelope))
        assertEquals(0, rendered)
        assertTrue("a poison body must be consumed instead of redelivered forever", dedup.seen(envelope.messageId))
        assertEquals(DeliveryOutcome.DUPLICATE, channel.deliver(envelope))
    }

    @Test
    fun persistedDedup_dropsRedeliveryAcrossAFreshChannel() {
        val me = newSigner();
        val myHpke = newHpke()
        val sender = newSigner();
        val senderHpke = newHpke()
        val trust =
            FakeTrustState().apply { peers.value = listOf(peerOf(sender, senderHpke.publicKeyset)) }
        val dedup = FakeDedup() // the shared, persisted layer that survives the "restart"
        val env = seal(
            sender,
            MessageType.NOTIFICATION,
            byteArrayOf(1),
            me.clientId,
            myHpke.publicKeyset,
            "m1"
        )

        var firstCount = 0
        testChannel(me, myHpke.privateKeyset, trust, dedup = dedup)
            .apply { onMessage(MessageType.NOTIFICATION) { firstCount++ } }
            .deliver(env)
        assertEquals(1, firstCount)

        // A fresh channel (empty in-memory recent — i.e. after a process restart) sharing the persisted
        // dedup must NOT re-handle the redelivered envelope. This is the core of the re-post fix.
        var secondCount = 0
        val outcome = testChannel(me, myHpke.privateKeyset, trust, dedup = dedup)
            .apply { onMessage(MessageType.NOTIFICATION) { secondCount++ } }
            .deliver(env)
        assertEquals("a redelivery after restart must not re-post", 0, secondCount)
        assertEquals(DeliveryOutcome.DUPLICATE, outcome)
    }

    @Test
    fun droppedMessages_areNotRecorded_soTheyCanStillArriveLater() {
        val me = newSigner();
        val myHpke = newHpke()
        val stranger = newSigner()
        val dedup = FakeDedup()
        // Empty roster -> unknown sender -> dropped before handling.
        val channel = testChannel(me, myHpke.privateKeyset, FakeTrustState(), dedup = dedup)
        channel.onMessage(MessageType.NOTIFICATION) { }

        val outcome = channel.deliver(
            seal(
                stranger,
                MessageType.NOTIFICATION,
                byteArrayOf(1),
                me.clientId,
                myHpke.publicKeyset,
                "m1"
            )
        )

        assertEquals(DeliveryOutcome.DROPPED, outcome)
        assertFalse(
            "a dropped (never-handled) message must not be recorded — it may yet deliver once trusted",
            dedup.seen("m1")
        )
    }

    @Test
    fun deliversOperationalSignedEnvelope_verifiedAgainstOperationalKey() {
        val me = newSigner();
        val myHpke = newHpke()
        val sender = newSigner();
        val senderHpke = newHpke()
        val senderOp = newOperationalSigner(sender, epoch = 1)
        val trust = FakeTrustState().apply {
            peers.value = listOf(peerOf(sender, senderHpke.publicKeyset, currentEpoch = 1))
            // The directory resolves signerEpoch 1 to the operational key (NOT the identity key).
            operationalSpkis = mapOf((sender.clientId to 1) to senderOp.operationalPublicKeySpki)
        }
        val channel = channel(me, myHpke.privateKeyset, trust)

        val received = mutableListOf<InboundMessage>()
        channel.onMessage(MessageType.NOTIFICATION) { received.add(it) }
        channel.deliver(
            sealOperational(
                senderOp,
                MessageType.NOTIFICATION,
                byteArrayOf(4, 2),
                me.clientId,
                myHpke.publicKeyset,
                "op1"
            )
        )

        assertEquals(1, received.size)
        assertEquals(sender.clientId, received.single().senderId)
    }

    @Test
    fun dropsOperationalEnvelope_whenEpochNotResolvable() {
        // The anti-rollback gate: an operational epoch the directory won't resolve (below floor / unknown /
        // no ENVELOPE_SIGN) drops BEFORE any signature check, even though the signature itself is valid.
        val me = newSigner();
        val myHpke = newHpke()
        val sender = newSigner();
        val senderHpke = newHpke()
        val senderOp = newOperationalSigner(sender, epoch = 1)
        val trust = FakeTrustState().apply {
            peers.value = listOf(peerOf(sender, senderHpke.publicKeyset, currentEpoch = 1))
        } // no operationalSpkis
        val channel = channel(me, myHpke.privateKeyset, trust)

        var count = 0
        channel.onMessage(MessageType.NOTIFICATION) { count++ }
        val outcome = channel.deliver(
            sealOperational(
                senderOp,
                MessageType.NOTIFICATION,
                byteArrayOf(1),
                me.clientId,
                myHpke.publicKeyset,
                "op2"
            )
        )

        assertEquals(0, count)
        assertEquals(DeliveryOutcome.DROPPED, outcome)
    }

    @Test
    fun send_skipsAnUnsealableRecipient_withoutCrashing_andDeliversToTheRest() = runBlocking {
        val me = newSigner();
        val myHpke = newHpke()
        val good = newSigner();
        val goodHpke = newHpke()
        val bad = newSigner()
        val transport = CapturingTransport()
        // One own-mesh peer carries a 3-byte HPKE key (not a 32-byte raw key, not a Tink keyset) — Hpke.seal
        // throws for it. This is exactly the shape that crashed the old app's send path.
        val trust = FakeTrustState().apply {
            peers.value = listOf(
                peerOf(good, goodHpke.publicKeyset, ownDevice = true),
                peerOf(bad, byteArrayOf(1, 2, 3), ownDevice = true),
            )
        }
        val channel = channel(me, myHpke.privateKeyset, trust, transport)

        // Must not throw despite the unsealable peer.
        channel.send(MessageType.NOTIFICATION, byteArrayOf(9), Recipients.OwnMesh, Urgency.HIGH)

        // The notification still went out, sealed only to the healthy peer.
        assertEquals(1, transport.envelopes.size)
        assertEquals(1, transport.envelopes.single().recipients.size)
        assertEquals(good.clientId, transport.envelopes.single().recipients.single().recipientId)
    }

    @Test
    fun send_triggersKeyEpochRepairForAnUnsealableScopePeer() = runBlocking {
        val me = newSigner();
        val myHpke = newHpke()
        val keyless = newSigner()
        val repairRequests = mutableListOf<ClientId>()
        // No sealable peers — the keyless peer is absent from activePeers — but it IS trusted-and-needing a
        // key-epoch (e.g. just upgraded, or its saved epoch went invalid), so it can never be a recipient.
        val trust = FakeTrustState().apply {
            peersNeeding = listOf(keyless.clientId)
            peerOwnDevices = mapOf(keyless.clientId to true)
        }
        // Send-side repair reuses the receive-side onUnresolvedSender handler (the broker key-epoch refetch).
        val channel = testChannel(
            me,
            myHpke.privateKeyset,
            trust,
            onUnresolvedSender = { repairRequests.add(it) })

        // Attempting to deliver to own-mesh must drive a repair for the keyless peer even though there is no
        // one to actually seal to — this is what makes "try to deliver" heal it over the server (no restart).
        val n =
            channel.send(MessageType.NOTIFICATION, byteArrayOf(9), Recipients.OwnMesh, Urgency.HIGH)

        assertEquals(0, n) // nobody sealable yet
        assertEquals(listOf(keyless.clientId), repairRequests)
    }

    @Test
    fun send_filteredScope_excludesPlatformsFromRecipientsAndKeyEpochRepair() = runBlocking {
        val me = newSigner();
        val myHpke = newHpke()
        val android = newSigner();
        val androidHpke = newHpke()
        val ios = newSigner();
        val iosHpke = newHpke()
        val androidKeyless = newSigner()
        val iosKeyless = newSigner()
        val repairRequests = mutableListOf<ClientId>()
        val transport = CapturingTransport()
        val trust = FakeTrustState().apply {
            peers.value = listOf(
                peerOf(android, androidHpke.publicKeyset, ownDevice = true, platform = "Android"),
                peerOf(ios, iosHpke.publicKeyset, ownDevice = true, platform = " iOS "),
            )
            peersNeeding = listOf(androidKeyless.clientId, iosKeyless.clientId)
            peerPlatforms = mapOf(
                androidKeyless.clientId to "android",
                iosKeyless.clientId to " IOS ",
            )
            peerOwnDevices = mapOf(
                androidKeyless.clientId to true,
                iosKeyless.clientId to true,
            )
        }
        val channel = testChannel(
            me,
            myHpke.privateKeyset,
            trust,
            transport,
            onUnresolvedSender = { repairRequests.add(it) },
        )

        val n = channel.send(
            MessageType.NOTIFICATION,
            byteArrayOf(9),
            Recipients.OwnMeshFiltered(excludedPlatforms = setOf(" ios ")),
            Urgency.HIGH,
        )

        assertEquals(1, n)
        assertEquals(setOf(android.clientId), transport.envelopes.single().recipientIds().toSet())
        assertEquals(listOf(androidKeyless.clientId), repairRequests)
    }

    @Test
    fun send_filteredScope_doesNotRepairCapabilityExcludedPeer() = runBlocking {
        val me = newSigner()
        val myHpke = newHpke()
        val supported = newSigner()
        val unsupported = newSigner()
        val repairRequests = mutableListOf<ClientId>()
        val trust = FakeTrustState().apply {
            peersNeeding = listOf(supported.clientId, unsupported.clientId)
            peerCapabilitySets = mapOf(
                supported.clientId to listOf(
                    Capability.CAPABILITY_ROUTING_V1,
                    Capability.DISPLAY,
                    Capability.DISPLAY_NOTIFICATION_UPDATES,
                ),
                unsupported.clientId to listOf(Capability.CAPABILITY_ROUTING_V1),
            )
            peerOwnDevices = mapOf(
                supported.clientId to true,
                unsupported.clientId to true,
            )
        }
        val channel = testChannel(
            me,
            myHpke.privateKeyset,
            trust,
            onUnresolvedSender = { repairRequests.add(it) },
        )

        channel.send(
            MessageType.DATA_SYNC,
            byteArrayOf(9),
            Recipients.OwnMeshFiltered(
                requiredCapabilities = setOf(
                    Capability.DISPLAY,
                    Capability.DISPLAY_NOTIFICATION_UPDATES,
                ),
            ),
            Urgency.NORMAL,
        )

        assertEquals(listOf(supported.clientId), repairRequests)
    }
}

@Serializable
@SerialName("net.extrawdw.notisync.protocol.NotifStyle")
private enum class LegacyNotifStyle { DEFAULT, BIG_TEXT, BIG_PICTURE, MESSAGING, INBOX }

@Serializable
@SerialName("net.extrawdw.notisync.protocol.NotifStyle")
private enum class FutureNotifStyle { DEFAULT, BIG_TEXT, BIG_PICTURE, MESSAGING, INBOX, MEDIA }

@Serializable
private data class LegacyCapturedNotification(
    @CborLabel(10) val style: LegacyNotifStyle,
)

@Serializable
private data class FutureCapturedNotification(
    @CborLabel(10) val style: FutureNotifStyle,
)

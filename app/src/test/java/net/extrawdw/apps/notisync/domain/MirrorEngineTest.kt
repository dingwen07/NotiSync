package net.extrawdw.apps.notisync.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.notisync.channel.SecureChannel
import net.extrawdw.apps.notisync.data.ActivityLog
import net.extrawdw.apps.notisync.foundation.TrustPeerDirectory
import net.extrawdw.apps.notisync.testsupport.CapturingTransport
import net.extrawdw.apps.notisync.transport.DeliveryMode
import net.extrawdw.apps.notisync.testsupport.FakeTrustState
import net.extrawdw.apps.notisync.testsupport.newHpke
import net.extrawdw.apps.notisync.testsupport.newSigner
import net.extrawdw.apps.notisync.testsupport.peerOf
import net.extrawdw.apps.notisync.testsupport.seal
import net.extrawdw.apps.notisync.testsupport.TestActivityText
import net.extrawdw.notisync.protocol.AssetRole
import net.extrawdw.notisync.protocol.CapturedNotification
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.DismissEvent
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.MirrorCategory
import net.extrawdw.notisync.protocol.MirrorImportance
import net.extrawdw.notisync.protocol.NotifStyle
import net.extrawdw.notisync.protocol.PrivateAssetRef
import net.extrawdw.notisync.protocol.ProtocolCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The notification application over the channel: own-mesh capture + inbound render, ownDevice gated. */
class MirrorEngineTest {

    private class RecordingRenderer : MirrorRenderer {
        var renders = 0
        val cleared = mutableListOf<Pair<ClientId, String>>()
        override fun render(notif: CapturedNotification) { renders++ }
        override fun clear(sourceClientId: ClientId, sourceKey: String) { cleared.add(sourceClientId to sourceKey) }
    }

    /** Reports every ref as still-missing so onNotification fires an ASSET_MISSING repair request. */
    private class MissingResolver(private val missing: List<PrivateAssetRef>) : AssetResolver {
        override suspend fun ensureLocal(refs: List<PrivateAssetRef>) = ResolveResult(newlyAvailable = false, stillMissing = missing)
        override suspend fun repair(assetHash: String, sourceClientId: ClientId): PrivateAssetRef? = null
    }

    private fun ref(source: ClientId) = PrivateAssetRef(
        role = AssetRole.LARGE_ICON, assetHash = "h", mimeType = "image/png", sizeBytes = 1,
        sourceClientId = source, assetId = "a", assetKey = byteArrayOf(1),
    )

    private fun sampleNotif(source: ClientId) = CapturedNotification(
        sourceClientId = source, sourceKey = "0|com.x|1|t", packageName = "com.x", appLabel = "X",
        title = "t", text = "x", style = NotifStyle.DEFAULT, category = MirrorCategory.MESSAGE,
        importance = MirrorImportance.DEFAULT, postTime = 1L,
    )

    private fun engine(
        me: net.extrawdw.notisync.protocol.crypto.IdentitySigner,
        myHpkePrivate: ByteArray,
        trust: FakeTrustState,
        renderer: MirrorRenderer,
        transport: CapturingTransport = CapturingTransport(),
        activityLog: ActivityLog = ActivityLog(),
    ): Pair<SecureChannel, MirrorEngine> {
        val channel = SecureChannel(me, myHpkePrivate, transport, TrustPeerDirectory(trust), log = {})
        val mirror = MirrorEngine(
            channel = channel,
            renderer = renderer,
            activityLog = activityLog,
            scope = CoroutineScope(Dispatchers.Unconfined),
            activityText = TestActivityText,
        )
        mirror.register()
        return channel to mirror
    }

    @Test
    fun notificationFromNotOwnDevice_isDropped() {
        val me = newSigner(); val myHpke = newHpke()
        val other = newSigner(); val otherHpke = newHpke()
        val trust = FakeTrustState().apply { peers.value = listOf(peerOf(other, otherHpke.publicKeyset, ownDevice = false)) }
        val renderer = RecordingRenderer()
        val (channel, _) = engine(me, myHpke.privateKeyset, trust, renderer)

        channel.deliver(seal(other, MessageType.NOTIFICATION, ProtocolCodec.encodeToCbor(sampleNotif(other.clientId)), me.clientId, myHpke.publicKeyset, "n1"))

        assertEquals("a notification from a not-own device must be dropped", 0, renderer.renders)
    }

    @Test
    fun notificationFromOwnDevice_isRendered() {
        val me = newSigner(); val myHpke = newHpke()
        val own = newSigner(); val ownHpke = newHpke()
        val trust = FakeTrustState().apply { peers.value = listOf(peerOf(own, ownHpke.publicKeyset, ownDevice = true)) }
        val renderer = RecordingRenderer()
        val (channel, _) = engine(me, myHpke.privateKeyset, trust, renderer)

        channel.deliver(seal(own, MessageType.NOTIFICATION, ProtocolCodec.encodeToCbor(sampleNotif(own.clientId)), me.clientId, myHpke.publicKeyset, "n1"))

        assertEquals(1, renderer.renders)
    }

    @Test
    fun notificationActivity_includesDeliveryMode() {
        val me = newSigner(); val myHpke = newHpke()
        val own = newSigner(); val ownHpke = newHpke()
        val trust = FakeTrustState().apply { peers.value = listOf(peerOf(own, ownHpke.publicKeyset, ownDevice = true, name = "Desk")) }
        val renderer = RecordingRenderer()
        val activityLog = ActivityLog()
        val channel = SecureChannel(me, myHpke.privateKeyset, CapturingTransport(), TrustPeerDirectory(trust), log = {})
        val mirror = MirrorEngine(
            channel = channel,
            renderer = renderer,
            activityLog = activityLog,
            scope = CoroutineScope(Dispatchers.Unconfined),
            activityText = TestActivityText,
            peerNameResolver = { trust.displayName(it) ?: it.shortForm() },
        )
        mirror.register()

        channel.deliver(
            seal(own, MessageType.NOTIFICATION, ProtocolCodec.encodeToCbor(sampleNotif(own.clientId)), me.clientId, myHpke.publicKeyset, "n1"),
            DeliveryMode.FCM_RELAY_FETCH,
        )

        assertEquals(1, renderer.renders)
        val row = activityLog.events.value.single()
        assertEquals("from Desk", row.detail)
        assertEquals(DeliveryMode.FCM_RELAY_FETCH, row.deliveryMode)
    }

    @Test
    fun dismissalFromOwnDevice_clearsAndCancelsOriginal() {
        val me = newSigner(); val myHpke = newHpke()
        val own = newSigner(); val ownHpke = newHpke()
        val trust = FakeTrustState().apply { peers.value = listOf(peerOf(own, ownHpke.publicKeyset, ownDevice = true)) }
        val renderer = RecordingRenderer()
        val activityLog = ActivityLog()
        val (channel, mirror) = engine(me, myHpke.privateKeyset, trust, renderer, activityLog = activityLog)
        val canceled = mutableListOf<String>()
        mirror.originalCanceler = OriginalCanceler { canceled.add(it) }

        val event = DismissEvent(own.clientId, "0|com.x|1|t", 1L)
        channel.deliver(
            seal(own, MessageType.DISMISSAL, ProtocolCodec.encodeToCbor(event), me.clientId, myHpke.publicKeyset, "d1"),
            DeliveryMode.WEBSOCKET,
        )

        assertEquals(listOf(own.clientId to "0|com.x|1|t"), renderer.cleared)
        assertEquals(listOf("0|com.x|1|t"), canceled)
        assertEquals(DeliveryMode.WEBSOCKET, activityLog.events.value.single().deliveryMode)
    }

    @Test
    fun captureLocal_sealsNotificationToOwnMesh() = runBlocking {
        val me = newSigner(); val myHpke = newHpke()
        val own = newSigner(); val ownHpke = newHpke()
        val other = newSigner(); val otherHpke = newHpke()
        val trust = FakeTrustState().apply {
            peers.value = listOf(peerOf(own, ownHpke.publicKeyset, ownDevice = true), peerOf(other, otherHpke.publicKeyset, ownDevice = false))
        }
        val transport = CapturingTransport()
        val (_, mirror) = engine(me, myHpke.privateKeyset, trust, RecordingRenderer(), transport)

        mirror.captureLocal(sampleNotif(me.clientId))

        // Sealed to own devices only (never to an "other" contact device).
        assertEquals(setOf(own.clientId), transport.envelopes.single().recipientIds().toSet())
        assertEquals(MessageType.NOTIFICATION, transport.envelopes.single().typ)
    }

    @Test
    fun assetRepairRequest_isSuppressedWhenSourceIsNonOwn() {
        val me = newSigner(); val myHpke = newHpke()
        val sender = newSigner(); val senderHpke = newHpke()  // own device that delivered the notification
        val other = newSigner(); val otherHpke = newHpke()    // a TRUSTED non-own contact device
        val trust = FakeTrustState().apply {
            peers.value = listOf(
                peerOf(sender, senderHpke.publicKeyset, ownDevice = true),
                peerOf(other, otherHpke.publicKeyset, ownDevice = false),
            )
        }
        val transport = CapturingTransport()
        val channel = SecureChannel(me, myHpke.privateKeyset, transport, TrustPeerDirectory(trust), log = {})
        val mirror = MirrorEngine(
            channel = channel,
            renderer = RecordingRenderer(),
            activityLog = ActivityLog(),
            scope = CoroutineScope(Dispatchers.Unconfined),
            activityText = TestActivityText,
            assetResolver = MissingResolver(listOf(ref(other.clientId))),
        )
        mirror.register()

        // NOTIFICATION from an OWN device, but its body's sourceClientId names a NON-own peer (body-controlled).
        val notif = sampleNotif(other.clientId).copy(largeIcon = ref(other.clientId))
        channel.deliver(seal(sender, MessageType.NOTIFICATION, ProtocolCodec.encodeToCbor(notif), me.clientId, myHpke.publicKeyset, "n1"))

        assertTrue("an asset-repair request must never be sealed to a non-own device", transport.sent.isEmpty())
    }

    @Test
    fun assetRepairRequest_isSentWhenSourceIsOwn() {
        val me = newSigner(); val myHpke = newHpke()
        val sender = newSigner(); val senderHpke = newHpke()
        val trust = FakeTrustState().apply { peers.value = listOf(peerOf(sender, senderHpke.publicKeyset, ownDevice = true)) }
        val transport = CapturingTransport()
        val channel = SecureChannel(me, myHpke.privateKeyset, transport, TrustPeerDirectory(trust), log = {})
        val mirror = MirrorEngine(
            channel = channel,
            renderer = RecordingRenderer(),
            activityLog = ActivityLog(),
            scope = CoroutineScope(Dispatchers.Unconfined),
            activityText = TestActivityText,
            assetResolver = MissingResolver(listOf(ref(sender.clientId))),
        )
        mirror.register()

        val notif = sampleNotif(sender.clientId).copy(largeIcon = ref(sender.clientId))
        channel.deliver(seal(sender, MessageType.NOTIFICATION, ProtocolCodec.encodeToCbor(notif), me.clientId, myHpke.publicKeyset, "n1"))

        // sourceClientId is an own device → the ASSET_MISSING repair is delivered to it.
        assertEquals(1, transport.sent.size)
        assertEquals(MessageType.DATA_SYNC, transport.envelopes.single().typ)
        assertEquals(setOf(sender.clientId), transport.envelopes.single().recipientIds().toSet())
    }
}

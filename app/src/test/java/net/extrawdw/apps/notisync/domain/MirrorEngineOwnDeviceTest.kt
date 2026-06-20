package net.extrawdw.apps.notisync.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.notisync.data.ActivityLog
import net.extrawdw.apps.notisync.data.Peer
import net.extrawdw.notisync.protocol.CapturedNotification
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.DataSync
import net.extrawdw.notisync.protocol.DataSyncKind
import net.extrawdw.notisync.protocol.Envelope
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.MirrorCategory
import net.extrawdw.notisync.protocol.MirrorImportance
import net.extrawdw.notisync.protocol.NotifStyle
import net.extrawdw.notisync.protocol.ProfileUpdate
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.SendResult
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.TrustTable
import net.extrawdw.notisync.protocol.Transport
import net.extrawdw.notisync.protocol.TransportType
import net.extrawdw.notisync.protocol.Urgency
import net.extrawdw.notisync.protocol.crypto.EnvelopeCrypto
import net.extrawdw.notisync.protocol.crypto.Hpke
import net.extrawdw.notisync.protocol.crypto.IdentitySigner
import net.extrawdw.notisync.protocol.crypto.RecipientKey
import net.extrawdw.notisync.protocol.crypto.SoftwareIdentitySigner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Own-device scoping: only profile updates cross the own/not-own boundary; everything else stays own-only. */
class MirrorEngineOwnDeviceTest {

    private val enc = Base64.getEncoder()

    private class CapturingTransport : Transport {
        override val type = TransportType.WEBSOCKET
        val sent = mutableListOf<Envelope>()
        override suspend fun send(envelope: Envelope, urgency: Urgency): SendResult { sent.add(envelope); return SendResult(true) }
        override suspend fun publishCard(card: SignedBlob) = Unit
        override suspend fun publishRoutes(routes: List<SignedBlob>) = Unit
        override suspend fun fetchCard(clientId: ClientId): SignedBlob? = null
        override suspend fun uploadPrivateAsset(sourceClientId: ClientId, assetId: String, ciphertext: ByteArray) = true
        override suspend fun fetchPrivateAsset(sourceClientId: ClientId, assetId: String): ByteArray? = null
        override fun incoming(): Flow<Envelope> = emptyFlow()
    }

    private class RecordingRenderer : MirrorRenderer {
        var renders = 0
        override fun render(notif: CapturedNotification) { renders++ }
        override fun clear(sourceClientId: ClientId, sourceKey: String) = Unit
    }

    private fun peer(signer: IdentitySigner, hpkePublic: ByteArray, ownDevice: Boolean) = Peer(
        clientId = signer.clientId, displayName = "p", platform = "android",
        identityPublicKeyB64 = enc.encodeToString(signer.publicKeySpki),
        hpkePublicKeysetB64 = enc.encodeToString(hpkePublic), addedAt = 1L, ownDevice = ownDevice,
    )

    private fun sampleNotif(source: ClientId) = CapturedNotification(
        sourceClientId = source, sourceKey = "0|com.x|1|t", packageName = "com.x", appLabel = "X",
        title = "t", text = "x", style = NotifStyle.DEFAULT, category = MirrorCategory.MESSAGE,
        importance = MirrorImportance.DEFAULT, postTime = 1L,
    )

    @Test
    fun notOwnSender_notificationDropped_butProfileApplied() = runBlocking {
        val me = SoftwareIdentitySigner.generate(); val myHpke = Hpke.generateKeyPair()
        val other = SoftwareIdentitySigner.generate(); val otherHpke = Hpke.generateKeyPair()
        val renderer = RecordingRenderer()
        val applied = mutableListOf<ProfileUpdate>()
        val engine = MirrorEngine(
            signer = me, myHpkePrivateKeyset = myHpke.privateKeyset, transport = CapturingTransport(),
            peersProvider = { listOf(peer(other, otherHpke.publicKeyset, ownDevice = false)) }, renderer = renderer,
            activityLog = ActivityLog(), scope = CoroutineScope(Dispatchers.Unconfined),
            profileUpdater = { applied.add(it); true },
        )

        // A NOTIFICATION from an "other" (not-own) device is dropped.
        engine.handleEnvelope(seal(other, MessageType.NOTIFICATION, ProtocolCodec.encodeToCbor(sampleNotif(other.clientId)), me, myHpke.publicKeyset, "n1"))
        assertEquals("notification from a not-own device must be dropped", 0, renderer.renders)

        // A PROFILE update from the same device is still applied.
        val update = ProfileUpdate(other.clientId, "New", "android", emptyList(), 9L)
        engine.handleEnvelope(seal(other, MessageType.DATA_SYNC, ProtocolCodec.encodeToCbor(DataSync(DataSyncKind.PROFILE, profile = update)), me, myHpke.publicKeyset, "p1"))
        assertEquals(1, applied.size)
        assertEquals("New", applied.single().displayName)
    }

    @Test
    fun broadcastScope_trustOwnOnly_profileAll() = runBlocking {
        val me = SoftwareIdentitySigner.generate(); val myHpke = Hpke.generateKeyPair()
        val own = SoftwareIdentitySigner.generate(); val ownHpke = Hpke.generateKeyPair()
        val other = SoftwareIdentitySigner.generate(); val otherHpke = Hpke.generateKeyPair()
        val transport = CapturingTransport()
        val engine = MirrorEngine(
            signer = me, myHpkePrivateKeyset = myHpke.privateKeyset, transport = transport,
            peersProvider = { listOf(peer(own, ownHpke.publicKeyset, true), peer(other, otherHpke.publicKeyset, false)) },
            renderer = RecordingRenderer(), activityLog = ActivityLog(), scope = CoroutineScope(Dispatchers.Unconfined),
            trustTableProvider = { TrustTable(emptyList()) },
        )

        engine.broadcastTrust()
        assertEquals(setOf(own.clientId), transport.sent.single().recipientIds().toSet()) // own only

        transport.sent.clear()
        engine.broadcastProfile(ProfileUpdate(me.clientId, "Me", "android", emptyList(), 5L))
        val profileRecipients = transport.sent.single().recipientIds().toSet()
        assertTrue(profileRecipients.contains(own.clientId))
        assertTrue("profile reaches not-own devices too", profileRecipients.contains(other.clientId))
    }

    private fun seal(
        sender: IdentitySigner, typ: MessageType, body: ByteArray,
        recipient: IdentitySigner, recipientHpke: ByteArray, messageId: String,
    ): Envelope = EnvelopeCrypto.seal(
        sender, typ, body, listOf(RecipientKey(recipient.clientId, recipientHpke)), messageId, 1L, 1L,
    )
}

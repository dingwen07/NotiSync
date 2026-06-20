package net.extrawdw.apps.notisync.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.notisync.data.ActivityLog
import net.extrawdw.apps.notisync.data.Peer
import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.CapturedNotification
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.DataSync
import net.extrawdw.notisync.protocol.DataSyncKind
import net.extrawdw.notisync.protocol.Envelope
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.ProfileUpdate
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.SendResult
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.Transport
import net.extrawdw.notisync.protocol.TransportType
import net.extrawdw.notisync.protocol.Urgency
import net.extrawdw.notisync.protocol.crypto.EnvelopeCrypto
import net.extrawdw.notisync.protocol.crypto.Hpke
import net.extrawdw.notisync.protocol.crypto.IdentitySigner
import net.extrawdw.notisync.protocol.crypto.RecipientKey
import net.extrawdw.notisync.protocol.crypto.SoftwareIdentitySigner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** End-to-end (real seal/verify/open) coverage of the DATA_SYNC PROFILE path. */
class MirrorEngineProfileTest {

    private val enc = Base64.getEncoder()

    /** Captures everything sent so a broadcast can be opened back as the recipient. */
    private class CapturingTransport : Transport {
        override val type = TransportType.WEBSOCKET
        val sent = mutableListOf<Pair<Envelope, Urgency>>()
        override suspend fun send(envelope: Envelope, urgency: Urgency): SendResult {
            sent.add(envelope to urgency); return SendResult(accepted = true)
        }
        override suspend fun publishCard(card: SignedBlob) = Unit
        override suspend fun publishRoutes(routes: List<SignedBlob>) = Unit
        override suspend fun fetchCard(clientId: ClientId): SignedBlob? = null
        override suspend fun uploadPrivateAsset(sourceClientId: ClientId, assetId: String, ciphertext: ByteArray) = true
        override suspend fun fetchPrivateAsset(sourceClientId: ClientId, assetId: String): ByteArray? = null
        override fun incoming(): Flow<Envelope> = emptyFlow()
    }

    private val noopRenderer = object : MirrorRenderer {
        override fun render(notif: CapturedNotification) = Unit
        override fun clear(sourceClientId: ClientId, sourceKey: String) = Unit
    }

    private fun peerFor(signer: IdentitySigner, hpkePublicKeyset: ByteArray, name: String, profileTs: Long = 0L) = Peer(
        clientId = signer.clientId,
        displayName = name,
        platform = "android",
        identityPublicKeyB64 = enc.encodeToString(signer.publicKeySpki),
        hpkePublicKeysetB64 = enc.encodeToString(hpkePublicKeyset),
        addedAt = 1L,
        profileUpdatedAt = profileTs,
    )

    private fun engine(
        me: IdentitySigner,
        myHpkePrivate: ByteArray,
        transport: Transport,
        peers: List<Peer>,
        profileUpdater: ((ProfileUpdate) -> Boolean)? = null,
    ) = MirrorEngine(
        signer = me,
        myHpkePrivateKeyset = myHpkePrivate,
        transport = transport,
        peersProvider = { peers },
        renderer = noopRenderer,
        activityLog = ActivityLog(),
        scope = CoroutineScope(Dispatchers.Unconfined),
        profileUpdater = profileUpdater,
    )

    @Test
    fun appliesProfileUpdate_fromTrustedPeer() = runBlocking {
        val sender = SoftwareIdentitySigner.generate(); val senderHpke = Hpke.generateKeyPair()
        val me = SoftwareIdentitySigner.generate(); val myHpke = Hpke.generateKeyPair()
        val applied = mutableListOf<ProfileUpdate>()
        val engine = engine(me, myHpke.privateKeyset, CapturingTransport(),
            peers = listOf(peerFor(sender, senderHpke.publicKeyset, "Old Name")),
            profileUpdater = { applied.add(it); true })

        val update = ProfileUpdate(sender.clientId, "New Name", "android", listOf(Capability.DISPLAY), 500L)
        engine.handleEnvelope(sealProfile(sender, me, myHpke.publicKeyset, update, "m1"))

        assertEquals(1, applied.size)
        assertEquals("New Name", applied[0].displayName)
        assertEquals(sender.clientId, applied[0].clientId)
    }

    @Test
    fun ignoresSpoofedProfile_whenClientIdIsNotTheSigner() = runBlocking {
        val sender = SoftwareIdentitySigner.generate(); val senderHpke = Hpke.generateKeyPair()
        val me = SoftwareIdentitySigner.generate(); val myHpke = Hpke.generateKeyPair()
        val applied = mutableListOf<ProfileUpdate>()
        val engine = engine(me, myHpke.privateKeyset, CapturingTransport(),
            peers = listOf(peerFor(sender, senderHpke.publicKeyset, "Old Name")),
            profileUpdater = { applied.add(it); true })

        // The (validly signed) sender claims to rename a DIFFERENT device.
        val spoof = ProfileUpdate(ClientId("someone-else"), "Hijacked", "android", emptyList(), 999L)
        engine.handleEnvelope(sealProfile(sender, me, myHpke.publicKeyset, spoof, "m2"))

        assertTrue("a peer must not be able to rename another device", applied.isEmpty())
    }

    @Test
    fun broadcastProfile_sealsDataSyncProfileToEveryPeer() = runBlocking {
        val me = SoftwareIdentitySigner.generate(); val myHpke = Hpke.generateKeyPair()
        val peerSigner = SoftwareIdentitySigner.generate(); val peerHpke = Hpke.generateKeyPair()
        val transport = CapturingTransport()
        val engine = engine(me, myHpke.privateKeyset, transport,
            peers = listOf(peerFor(peerSigner, peerHpke.publicKeyset, "Peer")))

        val update = ProfileUpdate(me.clientId, "Renamed", "android", listOf(Capability.CAPTURE), 700L)
        engine.broadcastProfile(update)

        assertEquals(1, transport.sent.size)
        val (env, urgency) = transport.sent.single()
        assertEquals(MessageType.DATA_SYNC, env.typ)
        assertEquals(Urgency.NORMAL, urgency)
        assertTrue(env.recipientIds().contains(peerSigner.clientId))

        // The peer can open it and read the new name.
        val body = EnvelopeCrypto.open(env, peerSigner.clientId, peerHpke.privateKeyset)
        val decoded = ProtocolCodec.decodeFromCbor<DataSync>(body)
        assertEquals(DataSyncKind.PROFILE, decoded.kind)
        assertEquals("Renamed", decoded.profile?.displayName)
    }

    private fun sealProfile(
        sender: IdentitySigner,
        recipient: IdentitySigner,
        recipientHpkePublic: ByteArray,
        update: ProfileUpdate,
        messageId: String,
    ): Envelope = EnvelopeCrypto.seal(
        signer = sender,
        typ = MessageType.DATA_SYNC,
        bodyPlaintext = ProtocolCodec.encodeToCbor(DataSync(DataSyncKind.PROFILE, profile = update)),
        recipients = listOf(RecipientKey(recipient.clientId, recipientHpkePublic)),
        messageId = messageId,
        seq = 1L,
        createdAt = 1L,
    )
}

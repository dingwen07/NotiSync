package net.extrawdw.apps.notisync.foundation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.notisync.channel.InboundMessage
import net.extrawdw.apps.notisync.channel.SecureChannel
import net.extrawdw.apps.notisync.data.ActivityLog
import net.extrawdw.apps.notisync.data.IncomingTrustResult
import net.extrawdw.apps.notisync.data.TrustPrompt
import net.extrawdw.apps.notisync.testsupport.CapturingTransport
import net.extrawdw.apps.notisync.testsupport.FakeTrustState
import net.extrawdw.apps.notisync.testsupport.newHpke
import net.extrawdw.apps.notisync.testsupport.newSigner
import net.extrawdw.apps.notisync.testsupport.peerOf
import net.extrawdw.apps.notisync.testsupport.seal
import net.extrawdw.notisync.protocol.CardDelivery
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.DataSync
import net.extrawdw.notisync.protocol.DataSyncKind
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.ProfileUpdate
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.SignedType
import net.extrawdw.notisync.protocol.TrustStatus
import net.extrawdw.notisync.protocol.TrustTable
import net.extrawdw.notisync.protocol.TrustTableEntry
import net.extrawdw.notisync.protocol.Urgency
import net.extrawdw.notisync.protocol.crypto.EnvelopeCrypto
import net.extrawdw.notisync.protocol.crypto.HpkeKeyPair
import net.extrawdw.notisync.protocol.crypto.IdentitySigner
import net.extrawdw.notisync.protocol.crypto.SoftwareIdentitySigner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Trust-table / card / profile wire I/O — the half that used to live in MirrorEngine. */
class FoundationEngineTest {

    private class Harness(
        val me: SoftwareIdentitySigner,
        val myHpke: HpkeKeyPair,
        val trust: FakeTrustState,
        val transport: CapturingTransport,
        val channel: SecureChannel,
        val foundation: FoundationEngine,
        val prompts: MutableList<Triple<ClientId, TrustPrompt, String>>,
        val forwardedAssets: MutableList<Pair<InboundMessage, DataSync>>,
        val activityLog: ActivityLog,
    )

    private fun harness(trust: FakeTrustState = FakeTrustState()): Harness {
        val me = newSigner(); val myHpke = newHpke()
        val transport = CapturingTransport()
        val channel = SecureChannel(me, myHpke.privateKeyset, transport, TrustPeerDirectory(trust), log = {})
        val prompts = mutableListOf<Triple<ClientId, TrustPrompt, String>>()
        val forwarded = mutableListOf<Pair<InboundMessage, DataSync>>()
        val activityLog = ActivityLog()
        val foundation = FoundationEngine(
            channel = channel,
            trust = trust,
            activityLog = activityLog,
            scope = CoroutineScope(Dispatchers.Unconfined),
            onTrustPrompt = { id, p, by -> prompts.add(Triple(id, p, by)) },
            onAsset = { msg, sync -> forwarded.add(msg to sync) },
        )
        foundation.register()
        return Harness(me, myHpke, trust, transport, channel, foundation, prompts, forwarded, activityLog)
    }

    private fun openAs(env: net.extrawdw.notisync.protocol.Envelope, recipient: IdentitySigner, recipientHpke: HpkeKeyPair): DataSync =
        ProtocolCodec.decodeFromCbor(EnvelopeCrypto.open(env, recipient.clientId, recipientHpke.privateKeyset))

    // ---- outbound ----

    @Test
    fun broadcastTrust_sealsTrustTableToOwnPeers() = runBlocking {
        val peerSigner = newSigner(); val peerHpke = newHpke()
        val trust = FakeTrustState().apply {
            peers.value = listOf(peerOf(peerSigner, peerHpke.publicKeyset))
            table = TrustTable(listOf(TrustTableEntry(ClientId("x"), TrustStatus.TRUSTED, 5L, keyAvailable = true)))
        }
        val h = harness(trust)

        h.foundation.broadcastTrust()

        assertEquals(1, h.transport.sent.size)
        val sync = openAs(h.transport.envelopes.single(), peerSigner, peerHpke)
        assertEquals(DataSyncKind.TRUST, sync.kind)
        assertEquals(ClientId("x"), sync.trust?.entries?.single()?.clientId)
    }

    @Test
    fun broadcastTrust_pushesOwnCardsAlongsideTable() = runBlocking {
        val peerSigner = newSigner(); val peerHpke = newHpke()
        val card = SignedBlob(SignedType.CLIENT_CARD, signerId = ClientId("x"), payload = byteArrayOf(1), sig = byteArrayOf(2))
        val trust = FakeTrustState().apply {
            peers.value = listOf(peerOf(peerSigner, peerHpke.publicKeyset))
            table = TrustTable(emptyList())
            cards = listOf(card)
        }
        val h = harness(trust)

        h.foundation.broadcastTrust()

        assertEquals(2, h.transport.sent.size) // a TRUST table + a CARD push
        val kinds = h.transport.envelopes.map { openAs(it, peerSigner, peerHpke).kind }
        assertEquals(listOf(DataSyncKind.TRUST, DataSyncKind.CARD), kinds)
        assertEquals(ClientId("x"), openAs(h.transport.envelopes[1], peerSigner, peerHpke).card?.clientId)
    }

    @Test
    fun broadcastProfile_sealsProfileToEveryPeer_atNormalUrgency() = runBlocking {
        val peerSigner = newSigner(); val peerHpke = newHpke()
        val trust = FakeTrustState().apply { peers.value = listOf(peerOf(peerSigner, peerHpke.publicKeyset, name = "Peer")) }
        val h = harness(trust)

        h.foundation.broadcastProfile(ProfileUpdate(h.me.clientId, "Renamed", "android", emptyList(), 700L))

        assertEquals(1, h.transport.sent.size)
        val (env, urgency) = h.transport.sent.single()
        assertEquals(Urgency.NORMAL, urgency)
        assertTrue(env.recipientIds().contains(peerSigner.clientId))
        val sync = openAs(env, peerSigner, peerHpke)
        assertEquals(DataSyncKind.PROFILE, sync.kind)
        assertEquals("Renamed", sync.profile?.displayName)
    }

    @Test
    fun broadcastScope_trustReachesOwnOnly_profileReachesOwnAndOther() = runBlocking {
        val own = newSigner(); val ownHpke = newHpke()
        val other = newSigner(); val otherHpke = newHpke()
        val trust = FakeTrustState().apply {
            peers.value = listOf(peerOf(own, ownHpke.publicKeyset, ownDevice = true), peerOf(other, otherHpke.publicKeyset, ownDevice = false))
        }
        val h = harness(trust)

        h.foundation.broadcastTrust()
        assertEquals(setOf(own.clientId), h.transport.envelopes.single().recipientIds().toSet()) // own only

        h.transport.sent.clear()
        h.foundation.broadcastProfile(ProfileUpdate(h.me.clientId, "Me", "android", emptyList(), 5L))
        val recipients = h.transport.envelopes.single().recipientIds().toSet()
        assertTrue(recipients.contains(own.clientId))
        assertTrue("profile reaches not-own devices too", recipients.contains(other.clientId))
    }

    // ---- inbound ----

    @Test
    fun receivingTrustTable_foldsAndOffersCardsBackToSender() {
        val sender = newSigner(); val senderHpke = newHpke()
        val offered = SignedBlob(SignedType.CLIENT_CARD, signerId = ClientId("x"), payload = byteArrayOf(1), sig = byteArrayOf(2))
        val trust = FakeTrustState().apply {
            peers.value = listOf(peerOf(sender, senderHpke.publicKeyset, name = "S"))
            incomingResult = IncomingTrustResult(listOf(ClientId("x") to TrustPrompt.NEW_TRUST), listOf(offered))
        }
        val h = harness(trust)

        val table = TrustTable(listOf(TrustTableEntry(ClientId("x"), TrustStatus.TRUSTED, 5L, keyAvailable = false)))
        h.channel.deliver(seal(sender, MessageType.DATA_SYNC, ProtocolCodec.encodeToCbor(DataSync(DataSyncKind.TRUST, trust = table)), h.me.clientId, h.myHpke.publicKeyset, "t1"))

        assertEquals(1, trust.foldedTables.size)
        assertEquals(sender.clientId, trust.foldedTables.single().first)
        assertEquals(listOf(TrustPrompt.NEW_TRUST), h.prompts.map { it.second })
        assertEquals("S", h.prompts.single().third)
        // The offered card was sealed back to the sender as a CARD delivery.
        val sync = openAs(h.transport.envelopes.single(), sender, senderHpke)
        assertEquals(DataSyncKind.CARD, sync.kind)
        assertEquals(ClientId("x"), sync.card?.clientId)
    }

    @Test
    fun receivingTrustTable_rebroadcastsWhenFoldNeedsIt() {
        val sender = newSigner(); val senderHpke = newHpke()
        val trust = FakeTrustState().apply {
            peers.value = listOf(peerOf(sender, senderHpke.publicKeyset))
            table = TrustTable(emptyList())
            incomingResult = IncomingTrustResult(emptyList(), emptyList(), needsBroadcast = true)
        }
        val h = harness(trust)

        val table = TrustTable(listOf(TrustTableEntry(ClientId("x"), TrustStatus.PENDING_TRUST, 5L, keyAvailable = false)))
        h.channel.deliver(seal(sender, MessageType.DATA_SYNC, ProtocolCodec.encodeToCbor(DataSync(DataSyncKind.TRUST, trust = table)), h.me.clientId, h.myHpke.publicKeyset, "t1"))

        // needsBroadcast -> the engine re-announces its own table so a card holder can repair it.
        assertEquals(1, h.transport.sent.size)
        assertEquals(DataSyncKind.TRUST, openAs(h.transport.envelopes.single(), sender, senderHpke).kind)
    }

    @Test
    fun receivingCardDelivery_pinsCard() {
        val sender = newSigner(); val senderHpke = newHpke()
        val card = SignedBlob(SignedType.CLIENT_CARD, signerId = ClientId("x"), payload = byteArrayOf(1), sig = byteArrayOf(2))
        val trust = FakeTrustState().apply { peers.value = listOf(peerOf(sender, senderHpke.publicKeyset)) }
        val h = harness(trust)

        h.channel.deliver(seal(sender, MessageType.DATA_SYNC, ProtocolCodec.encodeToCbor(DataSync(DataSyncKind.CARD, card = CardDelivery(ClientId("x"), card))), h.me.clientId, h.myHpke.publicKeyset, "c1"))

        assertEquals(listOf(ClientId("x")), trust.appliedCards.map { it.first })
    }

    @Test
    fun appliesProfileUpdate_fromTrustedPeer() {
        val sender = newSigner(); val senderHpke = newHpke()
        val trust = FakeTrustState().apply { peers.value = listOf(peerOf(sender, senderHpke.publicKeyset, name = "Old Name")) }
        val h = harness(trust)

        val update = ProfileUpdate(sender.clientId, "New Name", "android", emptyList(), 500L)
        h.channel.deliver(seal(sender, MessageType.DATA_SYNC, ProtocolCodec.encodeToCbor(DataSync(DataSyncKind.PROFILE, profile = update)), h.me.clientId, h.myHpke.publicKeyset, "p1"))

        assertEquals(1, trust.appliedProfiles.size)
        assertEquals("New Name", trust.appliedProfiles.single().displayName)
        assertEquals("the right device must be renamed", sender.clientId, trust.appliedProfiles.single().clientId)
    }

    @Test
    fun profileRenameRow_usesThePreMutationName() {
        val sender = newSigner(); val senderHpke = newHpke()
        val trust = FakeTrustState().apply { peers.value = listOf(peerOf(sender, senderHpke.publicKeyset, name = "Old Name")) }
        val h = harness(trust)

        val update = ProfileUpdate(sender.clientId, "New Name", "android", emptyList(), 500L)
        h.channel.deliver(seal(sender, MessageType.DATA_SYNC, ProtocolCodec.encodeToCbor(DataSync(DataSyncKind.PROFILE, profile = update)), h.me.clientId, h.myHpke.publicKeyset, "p1"))

        // The PAIRED row's "was <name>" must be the OLD name, captured before applyProfile mutated it.
        val row = h.activityLog.events.value.first()
        assertEquals("New Name", row.title)
        assertEquals("renamed (was Old Name)", row.detail)
    }

    @Test
    fun ignoresSpoofedProfile_whenClientIdIsNotTheSigner() {
        val sender = newSigner(); val senderHpke = newHpke()
        val trust = FakeTrustState().apply { peers.value = listOf(peerOf(sender, senderHpke.publicKeyset, name = "Old Name")) }
        val h = harness(trust)

        // The (validly signed) sender claims to rename a DIFFERENT device.
        val spoof = ProfileUpdate(ClientId("someone-else"), "Hijacked", "android", emptyList(), 999L)
        h.channel.deliver(seal(sender, MessageType.DATA_SYNC, ProtocolCodec.encodeToCbor(DataSync(DataSyncKind.PROFILE, profile = spoof)), h.me.clientId, h.myHpke.publicKeyset, "p2"))

        assertTrue("a peer must not be able to rename another device", trust.appliedProfiles.isEmpty())
    }

    @Test
    fun otherDevice_mayOnlySendProfile_trustFromOtherIsDropped() {
        val other = newSigner(); val otherHpke = newHpke()
        val trust = FakeTrustState().apply { peers.value = listOf(peerOf(other, otherHpke.publicKeyset, ownDevice = false, name = "Other")) }
        val h = harness(trust)

        // TRUST from a not-own device is rejected by SendPolicy (never folded).
        val table = TrustTable(listOf(TrustTableEntry(ClientId("x"), TrustStatus.TRUSTED, 5L, keyAvailable = true)))
        h.channel.deliver(seal(other, MessageType.DATA_SYNC, ProtocolCodec.encodeToCbor(DataSync(DataSyncKind.TRUST, trust = table)), h.me.clientId, h.myHpke.publicKeyset, "t1"))
        assertTrue("trust from a not-own device must be dropped", trust.foldedTables.isEmpty())

        // PROFILE from the same not-own device is still applied.
        val update = ProfileUpdate(other.clientId, "Renamed", "android", emptyList(), 9L)
        h.channel.deliver(seal(other, MessageType.DATA_SYNC, ProtocolCodec.encodeToCbor(DataSync(DataSyncKind.PROFILE, profile = update)), h.me.clientId, h.myHpke.publicKeyset, "p1"))
        assertEquals(1, trust.appliedProfiles.size)
    }

    @Test
    fun forwardsAssetSubBody_toTheNotificationApp() {
        val sender = newSigner(); val senderHpke = newHpke()
        val trust = FakeTrustState().apply { peers.value = listOf(peerOf(sender, senderHpke.publicKeyset)) }
        val h = harness(trust)

        val asset = net.extrawdw.notisync.protocol.AssetSync(net.extrawdw.notisync.protocol.AssetSyncKind.ASSET_MISSING, emptyList())
        h.channel.deliver(seal(sender, MessageType.DATA_SYNC, ProtocolCodec.encodeToCbor(DataSync(DataSyncKind.ASSET, asset = asset)), h.me.clientId, h.myHpke.publicKeyset, "a1"))

        assertEquals(1, h.forwardedAssets.size)
        assertEquals(DataSyncKind.ASSET, h.forwardedAssets.single().second.kind)
        assertEquals(sender.clientId, h.forwardedAssets.single().first.senderId)
    }
}

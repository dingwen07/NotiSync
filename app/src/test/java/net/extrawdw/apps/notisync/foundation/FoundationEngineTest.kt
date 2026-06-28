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
import net.extrawdw.apps.notisync.testsupport.keyEpochBlob
import net.extrawdw.apps.notisync.testsupport.newHpke
import net.extrawdw.apps.notisync.testsupport.newOperationalSigner
import net.extrawdw.apps.notisync.testsupport.newSigner
import net.extrawdw.apps.notisync.testsupport.peerOf
import net.extrawdw.apps.notisync.testsupport.seal
import net.extrawdw.apps.notisync.testsupport.sealOperational
import net.extrawdw.apps.notisync.testsupport.testChannel
import net.extrawdw.apps.notisync.testsupport.TestActivityText
import net.extrawdw.apps.notisync.transport.DeliveryMode
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
import org.junit.Assert.assertFalse
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

    private fun harness(
        trust: FakeTrustState = FakeTrustState(),
        fetchKeyEpoch: suspend (ClientId, Int?) -> SignedBlob? = { _, _ -> null },
    ): Harness {
        val me = newSigner();
        val myHpke = newHpke()
        val transport = CapturingTransport()
        val channel = testChannel(me, myHpke.privateKeyset, trust, transport)
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
            activityText = TestActivityText,
            fetchKeyEpoch = fetchKeyEpoch,
        )
        foundation.register()
        return Harness(
            me,
            myHpke,
            trust,
            transport,
            channel,
            foundation,
            prompts,
            forwarded,
            activityLog
        )
    }

    private fun openAs(
        env: net.extrawdw.notisync.protocol.Envelope,
        recipient: IdentitySigner,
        recipientHpke: HpkeKeyPair
    ): DataSync =
        ProtocolCodec.decodeFromCbor(
            EnvelopeCrypto.open(
                env,
                recipient.clientId,
                recipientHpke.privateKeyset
            )
        )

    // ---- outbound ----

    @Test
    fun broadcastTrust_sealsTrustTableToOwnPeers() = runBlocking {
        val peerSigner = newSigner();
        val peerHpke = newHpke()
        val trust = FakeTrustState().apply {
            peers.value = listOf(peerOf(peerSigner, peerHpke.publicKeyset))
            table = TrustTable(
                listOf(
                    TrustTableEntry(
                        ClientId("x"),
                        TrustStatus.TRUSTED,
                        5L,
                        keyAvailable = true
                    )
                )
            )
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
        val peerSigner = newSigner();
        val peerHpke = newHpke()
        val card = SignedBlob(
            SignedType.CLIENT_CARD,
            signerId = ClientId("x"),
            payload = byteArrayOf(1),
            sig = byteArrayOf(2)
        )
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
        assertEquals(
            ClientId("x"),
            openAs(h.transport.envelopes[1], peerSigner, peerHpke).card?.clientId
        )
    }

    @Test
    fun broadcastProfile_sealsProfileToEveryPeer_atNormalUrgency() = runBlocking {
        val peerSigner = newSigner();
        val peerHpke = newHpke()
        val trust = FakeTrustState().apply {
            peers.value = listOf(peerOf(peerSigner, peerHpke.publicKeyset, name = "Peer"))
        }
        val h = harness(trust)

        h.foundation.broadcastProfile(
            ProfileUpdate(
                h.me.clientId,
                "Renamed",
                "android",
                emptyList(),
                700L
            )
        )

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
        val own = newSigner();
        val ownHpke = newHpke()
        val other = newSigner();
        val otherHpke = newHpke()
        val trust = FakeTrustState().apply {
            peers.value = listOf(
                peerOf(own, ownHpke.publicKeyset, ownDevice = true),
                peerOf(other, otherHpke.publicKeyset, ownDevice = false)
            )
        }
        val h = harness(trust)

        h.foundation.broadcastTrust()
        assertEquals(
            setOf(own.clientId),
            h.transport.envelopes.single().recipientIds().toSet()
        ) // own only

        h.transport.sent.clear()
        h.foundation.broadcastProfile(
            ProfileUpdate(
                h.me.clientId,
                "Me",
                "android",
                emptyList(),
                5L
            )
        )
        val recipients = h.transport.envelopes.single().recipientIds().toSet()
        assertTrue(recipients.contains(own.clientId))
        assertTrue("profile reaches not-own devices too", recipients.contains(other.clientId))
    }

    // ---- inbound ----

    @Test
    fun receivingTrustTable_foldsAndOffersCardsBackToSender() {
        val sender = newSigner();
        val senderHpke = newHpke()
        val offered = SignedBlob(
            SignedType.CLIENT_CARD,
            signerId = ClientId("x"),
            payload = byteArrayOf(1),
            sig = byteArrayOf(2)
        )
        val trust = FakeTrustState().apply {
            peers.value = listOf(peerOf(sender, senderHpke.publicKeyset, name = "S"))
            incomingResult =
                IncomingTrustResult(listOf(ClientId("x") to TrustPrompt.NEW_TRUST), listOf(offered))
        }
        val h = harness(trust)

        val table = TrustTable(
            listOf(
                TrustTableEntry(
                    ClientId("x"),
                    TrustStatus.TRUSTED,
                    5L,
                    keyAvailable = false
                )
            )
        )
        h.channel.deliver(
            seal(
                sender,
                MessageType.DATA_SYNC,
                ProtocolCodec.encodeToCbor(DataSync(DataSyncKind.TRUST, trust = table)),
                h.me.clientId,
                h.myHpke.publicKeyset,
                "t1"
            ),
            DeliveryMode.WEBSOCKET,
        )

        assertEquals(1, trust.foldedTables.size)
        assertEquals(sender.clientId, trust.foldedTables.single().first)
        assertEquals(listOf(TrustPrompt.NEW_TRUST), h.prompts.map { it.second })
        assertEquals("S", h.prompts.single().third)
        assertEquals(DeliveryMode.WEBSOCKET, h.activityLog.events.value.single().deliveryMode)
        // The offered card was sealed back to the sender as a CARD delivery.
        val sync = openAs(h.transport.envelopes.single(), sender, senderHpke)
        assertEquals(DataSyncKind.CARD, sync.kind)
        assertEquals(ClientId("x"), sync.card?.clientId)
    }

    @Test
    fun rejectsOperationalSignedTrustTable_onlyIdentityMayGossipRoster() {
        val sender = newSigner();
        val senderHpke = newHpke()
        val senderOp = newOperationalSigner(sender, epoch = 1)
        val trust = FakeTrustState().apply {
            peers.value = listOf(peerOf(sender, senderHpke.publicKeyset, currentEpoch = 1))
            operationalSpkis = mapOf((sender.clientId to 1) to senderOp.operationalPublicKeySpki)
        }
        val h = harness(trust)

        // A validly operational-signed TrustTable (signerEpoch 1) — the channel verifies it, but the handler
        // must reject it: a roster assertion may only come from the identity root (§2.3 / §8 #12).
        val table = TrustTable(
            listOf(
                TrustTableEntry(
                    ClientId("x"),
                    TrustStatus.TRUSTED,
                    5L,
                    keyAvailable = true
                )
            )
        )
        h.channel.deliver(
            sealOperational(
                senderOp,
                MessageType.DATA_SYNC,
                ProtocolCodec.encodeToCbor(DataSync(DataSyncKind.TRUST, trust = table)),
                h.me.clientId,
                h.myHpke.publicKeyset,
                "opt1"
            )
        )

        assertTrue(
            "a leaked operational key must not be able to drive roster gossip",
            trust.foldedTables.isEmpty()
        )
    }

    @Test
    fun receivingTrustTable_rebroadcastsWhenFoldNeedsIt() {
        val sender = newSigner();
        val senderHpke = newHpke()
        val trust = FakeTrustState().apply {
            peers.value = listOf(peerOf(sender, senderHpke.publicKeyset))
            table = TrustTable(emptyList())
            incomingResult = IncomingTrustResult(emptyList(), emptyList(), needsBroadcast = true)
        }
        val h = harness(trust)

        val table = TrustTable(
            listOf(
                TrustTableEntry(
                    ClientId("x"),
                    TrustStatus.PENDING_TRUST,
                    5L,
                    keyAvailable = false
                )
            )
        )
        h.channel.deliver(
            seal(
                sender,
                MessageType.DATA_SYNC,
                ProtocolCodec.encodeToCbor(DataSync(DataSyncKind.TRUST, trust = table)),
                h.me.clientId,
                h.myHpke.publicKeyset,
                "t1"
            )
        )

        // needsBroadcast -> the engine re-announces its own table so a card holder can repair it.
        assertEquals(1, h.transport.sent.size)
        assertEquals(
            DataSyncKind.TRUST,
            openAs(h.transport.envelopes.single(), sender, senderHpke).kind
        )
    }

    @Test
    fun receivingCardDelivery_pinsCard() {
        val sender = newSigner();
        val senderHpke = newHpke()
        val card = SignedBlob(
            SignedType.CLIENT_CARD,
            signerId = ClientId("x"),
            payload = byteArrayOf(1),
            sig = byteArrayOf(2)
        )
        val trust =
            FakeTrustState().apply { peers.value = listOf(peerOf(sender, senderHpke.publicKeyset)) }
        val h = harness(trust)

        h.channel.deliver(
            seal(
                sender,
                MessageType.DATA_SYNC,
                ProtocolCodec.encodeToCbor(
                    DataSync(
                        DataSyncKind.CARD,
                        card = CardDelivery(ClientId("x"), card)
                    )
                ),
                h.me.clientId,
                h.myHpke.publicKeyset,
                "c1"
            )
        )

        assertEquals(listOf(ClientId("x")), trust.appliedCards.map { it.first })
    }

    @Test
    fun receivingCardDelivery_withEpochBlob_appliesKeyEpoch() {
        val sender = newSigner();
        val senderHpke = newHpke()
        val subject = newSigner();
        val subjectHpke = newHpke()
        val epochBlob = keyEpochBlob(
            subject,
            newOperationalSigner(subject, 1),
            subjectHpke.publicKeyset,
            epoch = 1
        )
        val trust =
            FakeTrustState().apply { peers.value = listOf(peerOf(sender, senderHpke.publicKeyset)) }
        val h = harness(trust)

        h.channel.deliver(
            seal(
                sender,
                MessageType.DATA_SYNC,
                ProtocolCodec.encodeToCbor(
                    DataSync(
                        DataSyncKind.CARD,
                        card = CardDelivery(subject.clientId, epochBlob = epochBlob)
                    )
                ),
                h.me.clientId,
                h.myHpke.publicKeyset,
                "e1"
            )
        )

        assertEquals(
            "an epochBlob in a CardDelivery is ingested as a key-epoch",
            listOf(subject.clientId),
            trust.appliedKeyEpochs.map { it.first })
    }

    @Test
    fun convergeKeyEpochs_pullsKeyEpochForPeerWithUnusableKey() {
        val peer = newSigner();
        val peerHpke = newHpke()
        val pulled =
            keyEpochBlob(peer, newOperationalSigner(peer, 1), peerHpke.publicKeyset, epoch = 1)
        val trust = FakeTrustState().apply {
            peersNeeding = listOf(peer.clientId) // missing-or-expired key → must pull
        }
        val fetched = mutableListOf<Pair<ClientId, Int?>>()
        val h =
            harness(trust) { id, epoch -> fetched.add(id to epoch); if (id == peer.clientId) pulled else null }

        runBlocking { h.foundation.convergeKeyEpochs() }

        // The bootstrap pull fetches the LATEST (epoch=null) straight from the broker and applies it — no
        // E2E round-trip, no re-pair. This is what makes two upgraded devices reach each other again.
        assertEquals(listOf(peer.clientId to null), fetched)
        assertEquals(listOf(peer.clientId), trust.appliedKeyEpochs.map { it.first })
    }

    @Test
    fun convergeKeyEpochs_skipsPeerWithUsableKey() {
        val peer = newSigner()
        val trust = FakeTrustState().apply {
            peersNeeding = emptyList()
        } // current key is usable → nothing to pull
        val fetched = mutableListOf<Pair<ClientId, Int?>>()
        val h = harness(trust) { id, epoch -> fetched.add(id to epoch); null }

        runBlocking { h.foundation.convergeKeyEpochs() }

        assertTrue(fetched.isEmpty())
        assertTrue(trust.appliedKeyEpochs.isEmpty())
    }

    @Test
    fun onUnresolvedSender_fetchesForTrustedSender_broadcastsOnFailure() {
        val sender = newSigner();
        val senderHpke = newHpke()
        // A trusted own-mesh peer we can broadcast to, plus the sender as a trusted id we can't yet resolve.
        val trust = FakeTrustState().apply {
            peers.value = listOf(peerOf(sender, senderHpke.publicKeyset))
            table = TrustTable(emptyList())
            trustedIds = listOf(sender.clientId)
        }
        val h = harness(trust) { _, _ -> null } // broker has nothing → fetch fails

        runBlocking { h.foundation.onUnresolvedSender(sender.clientId) }

        // Couldn't pull → fall back to a roster broadcast so a mesh peer can repair us.
        assertEquals(1, h.transport.sent.size)
        assertEquals(
            DataSyncKind.TRUST,
            openAs(h.transport.envelopes.single(), sender, senderHpke).kind
        )
    }

    @Test
    fun onUnresolvedSender_ignoresUntrustedSender() {
        val stranger = newSigner()
        val trust = FakeTrustState() // stranger not in trustedIds
        var fetched = false
        val h = harness(trust) { _, _ -> fetched = true; null }

        runBlocking { h.foundation.onUnresolvedSender(stranger.clientId) }

        assertFalse("an untrusted/unknown sender must not drive a fetch", fetched)
        assertTrue(h.transport.sent.isEmpty())
    }

    @Test
    fun receivingTrustTable_relaysHeldKeyEpochWhenSenderIsBehind() {
        val sender = newSigner();
        val senderHpke = newHpke()
        val subject = newSigner();
        val subjectHpke = newHpke()
        val offered = keyEpochBlob(
            subject,
            newOperationalSigner(subject, 2),
            subjectHpke.publicKeyset,
            epoch = 2
        )
        // We hold subject@2 and the fold reports the sender is behind on it → relay it as a CardDelivery.
        val trust = FakeTrustState().apply {
            peers.value = listOf(peerOf(sender, senderHpke.publicKeyset))
            incomingResult =
                IncomingTrustResult(emptyList(), emptyList(), keyEpochsToOffer = listOf(offered))
        }
        val h = harness(trust)

        val table = TrustTable(
            listOf(
                TrustTableEntry(
                    subject.clientId,
                    TrustStatus.TRUSTED,
                    5L,
                    keyAvailable = true,
                    epoch = 1
                )
            )
        )
        h.channel.deliver(
            seal(
                sender,
                MessageType.DATA_SYNC,
                ProtocolCodec.encodeToCbor(DataSync(DataSyncKind.TRUST, trust = table)),
                h.me.clientId,
                h.myHpke.publicKeyset,
                "tk"
            )
        )

        val sync = openAs(h.transport.envelopes.single(), sender, senderHpke)
        assertEquals(DataSyncKind.CARD, sync.kind)
        assertEquals(
            "relays the subject's current key-epoch to the sender",
            subject.clientId,
            sync.card?.epochBlob?.signerId
        )
    }

    @Test
    fun receivingTrustTable_pullsKeyEpochWhenAdvertisedEpochIsHigher() {
        val sender = newSigner();
        val senderHpke = newHpke()
        val subject = newSigner();
        val subjectHpke = newHpke()
        val pulled = keyEpochBlob(
            subject,
            newOperationalSigner(subject, 2),
            subjectHpke.publicKeyset,
            epoch = 2
        )
        val trust = FakeTrustState().apply {
            peers.value = listOf(peerOf(sender, senderHpke.publicKeyset))
            peerEpochs = mapOf(subject.clientId to 1) // we hold epoch 1; the roster advertises 2
        }
        val fetched = mutableListOf<Pair<ClientId, Int?>>()
        val h =
            harness(trust) { id, epoch -> fetched.add(id to epoch); if (id == subject.clientId) pulled else null }

        val table = TrustTable(
            listOf(
                TrustTableEntry(
                    subject.clientId,
                    TrustStatus.TRUSTED,
                    5L,
                    keyAvailable = true,
                    epoch = 2
                )
            )
        )
        h.channel.deliver(
            seal(
                sender,
                MessageType.DATA_SYNC,
                ProtocolCodec.encodeToCbor(DataSync(DataSyncKind.TRUST, trust = table)),
                h.me.clientId,
                h.myHpke.publicKeyset,
                "t9"
            )
        )

        assertEquals(
            "a higher advertised epoch triggers a key-epoch pull",
            listOf(subject.clientId to 2),
            fetched
        )
        assertEquals(listOf(subject.clientId), trust.appliedKeyEpochs.map { it.first })
    }

    @Test
    fun appliesProfileUpdate_fromTrustedPeer() {
        val sender = newSigner();
        val senderHpke = newHpke()
        val trust = FakeTrustState().apply {
            peers.value = listOf(peerOf(sender, senderHpke.publicKeyset, name = "Old Name"))
        }
        val h = harness(trust)

        val update = ProfileUpdate(sender.clientId, "New Name", "android", emptyList(), 500L)
        h.channel.deliver(
            seal(
                sender,
                MessageType.DATA_SYNC,
                ProtocolCodec.encodeToCbor(DataSync(DataSyncKind.PROFILE, profile = update)),
                h.me.clientId,
                h.myHpke.publicKeyset,
                "p1"
            ),
            DeliveryMode.FCM_INLINE,
        )

        assertEquals(1, trust.appliedProfiles.size)
        assertEquals("New Name", trust.appliedProfiles.single().displayName)
        assertEquals(
            "the right device must be renamed",
            sender.clientId,
            trust.appliedProfiles.single().clientId
        )
    }

    @Test
    fun profileRenameRow_usesThePreMutationName() {
        val sender = newSigner();
        val senderHpke = newHpke()
        val trust = FakeTrustState().apply {
            peers.value = listOf(peerOf(sender, senderHpke.publicKeyset, name = "Old Name"))
        }
        val h = harness(trust)

        val update = ProfileUpdate(sender.clientId, "New Name", "android", emptyList(), 500L)
        h.channel.deliver(
            seal(
                sender,
                MessageType.DATA_SYNC,
                ProtocolCodec.encodeToCbor(DataSync(DataSyncKind.PROFILE, profile = update)),
                h.me.clientId,
                h.myHpke.publicKeyset,
                "p1"
            ),
            DeliveryMode.FCM_INLINE,
        )

        // The PAIRED row's "was <name>" must be the OLD name, captured before applyProfile mutated it.
        val row = h.activityLog.events.value.first()
        assertEquals("New Name", row.title)
        assertEquals("renamed (was Old Name)", row.detail)
        assertEquals(DeliveryMode.FCM_INLINE, row.deliveryMode)
    }

    @Test
    fun ignoresSpoofedProfile_whenClientIdIsNotTheSigner() {
        val sender = newSigner();
        val senderHpke = newHpke()
        val trust = FakeTrustState().apply {
            peers.value = listOf(peerOf(sender, senderHpke.publicKeyset, name = "Old Name"))
        }
        val h = harness(trust)

        // The (validly signed) sender claims to rename a DIFFERENT device.
        val spoof =
            ProfileUpdate(ClientId("someone-else"), "Hijacked", "android", emptyList(), 999L)
        h.channel.deliver(
            seal(
                sender,
                MessageType.DATA_SYNC,
                ProtocolCodec.encodeToCbor(DataSync(DataSyncKind.PROFILE, profile = spoof)),
                h.me.clientId,
                h.myHpke.publicKeyset,
                "p2"
            )
        )

        assertTrue(
            "a peer must not be able to rename another device",
            trust.appliedProfiles.isEmpty()
        )
    }

    @Test
    fun otherDevice_mayOnlySendProfile_trustFromOtherIsDropped() {
        val other = newSigner();
        val otherHpke = newHpke()
        val trust = FakeTrustState().apply {
            peers.value =
                listOf(peerOf(other, otherHpke.publicKeyset, ownDevice = false, name = "Other"))
        }
        val h = harness(trust)

        // TRUST from a not-own device is rejected by SendPolicy (never folded).
        val table = TrustTable(
            listOf(
                TrustTableEntry(
                    ClientId("x"),
                    TrustStatus.TRUSTED,
                    5L,
                    keyAvailable = true
                )
            )
        )
        h.channel.deliver(
            seal(
                other,
                MessageType.DATA_SYNC,
                ProtocolCodec.encodeToCbor(DataSync(DataSyncKind.TRUST, trust = table)),
                h.me.clientId,
                h.myHpke.publicKeyset,
                "t1"
            )
        )
        assertTrue("trust from a not-own device must be dropped", trust.foldedTables.isEmpty())

        // PROFILE from the same not-own device is still applied.
        val update = ProfileUpdate(other.clientId, "Renamed", "android", emptyList(), 9L)
        h.channel.deliver(
            seal(
                other,
                MessageType.DATA_SYNC,
                ProtocolCodec.encodeToCbor(DataSync(DataSyncKind.PROFILE, profile = update)),
                h.me.clientId,
                h.myHpke.publicKeyset,
                "p1"
            )
        )
        assertEquals(1, trust.appliedProfiles.size)
    }

    @Test
    fun forwardsAssetSubBody_toTheNotificationApp() {
        val sender = newSigner();
        val senderHpke = newHpke()
        val trust =
            FakeTrustState().apply { peers.value = listOf(peerOf(sender, senderHpke.publicKeyset)) }
        val h = harness(trust)

        val asset = net.extrawdw.notisync.protocol.AssetSync(
            net.extrawdw.notisync.protocol.AssetSyncKind.ASSET_MISSING,
            emptyList()
        )
        h.channel.deliver(
            seal(
                sender,
                MessageType.DATA_SYNC,
                ProtocolCodec.encodeToCbor(DataSync(DataSyncKind.ASSET, asset = asset)),
                h.me.clientId,
                h.myHpke.publicKeyset,
                "a1"
            )
        )

        assertEquals(1, h.forwardedAssets.size)
        assertEquals(DataSyncKind.ASSET, h.forwardedAssets.single().second.kind)
        assertEquals(sender.clientId, h.forwardedAssets.single().first.senderId)
    }
}

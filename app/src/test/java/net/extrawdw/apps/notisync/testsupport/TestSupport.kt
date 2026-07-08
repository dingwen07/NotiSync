package net.extrawdw.apps.notisync.testsupport

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.extrawdw.apps.notisync.channel.MessageDedup
import net.extrawdw.apps.notisync.channel.SecureChannel
import net.extrawdw.apps.notisync.data.IncomingTrustResult
import net.extrawdw.apps.notisync.data.Peer
import net.extrawdw.apps.notisync.data.PendingRotation
import net.extrawdw.apps.notisync.data.TrustState
import net.extrawdw.apps.notisync.foundation.TrustPeerDirectory
import net.extrawdw.apps.notisync.transport.DeliveryMode
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ClientKeyEpoch
import net.extrawdw.notisync.protocol.Envelope
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.ProfileUpdate
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.Purpose
import net.extrawdw.notisync.protocol.SendResult
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.SignedType
import net.extrawdw.notisync.protocol.Transport
import net.extrawdw.notisync.protocol.TransportType
import net.extrawdw.notisync.protocol.TrustTable
import net.extrawdw.notisync.protocol.Urgency
import net.extrawdw.notisync.protocol.crypto.EnvelopeCrypto
import net.extrawdw.notisync.protocol.crypto.Hpke
import net.extrawdw.notisync.protocol.crypto.HpkeKeyPair
import net.extrawdw.notisync.protocol.crypto.IdentitySigner
import net.extrawdw.notisync.protocol.crypto.OperationalSigner
import net.extrawdw.notisync.protocol.crypto.RecipientKey
import net.extrawdw.notisync.protocol.crypto.SoftwareIdentitySigner
import net.extrawdw.notisync.protocol.crypto.SoftwareOperationalSigner
import java.util.Base64

/** Records everything sent so a broadcast can be opened back as the recipient. */
class CapturingTransport : Transport {
    override val type = TransportType.WEBSOCKET
    val sent = mutableListOf<Pair<Envelope, Urgency>>()
    val envelopes: List<Envelope> get() = sent.map { it.first }
    val publishedKeyEpochs = mutableListOf<SignedBlob>()

    /** Key-epochs the engine can pull (keyed by clientId then epoch; epoch null ⇒ highest). */
    var keyEpochs: Map<ClientId, Map<Int, SignedBlob>> = emptyMap()
    override suspend fun send(envelope: Envelope, urgency: Urgency): SendResult {
        sent.add(envelope to urgency); return SendResult(accepted = true)
    }

    override suspend fun publishKeyEpoch(keyEpoch: SignedBlob) {
        publishedKeyEpochs.add(keyEpoch)
    }

    override suspend fun publishRoutes(routes: List<SignedBlob>) = Unit
    override suspend fun fetchKeyEpoch(clientId: ClientId, epoch: Int?): SignedBlob? {
        val byEpoch = keyEpochs[clientId] ?: return null
        return if (epoch != null) byEpoch[epoch] else byEpoch.maxByOrNull { it.key }?.value
    }

    override suspend fun uploadPrivateAsset(
        sourceClientId: ClientId,
        assetId: String,
        ciphertext: ByteArray
    ) = true

    override suspend fun fetchPrivateAsset(sourceClientId: ClientId, assetId: String): ByteArray? =
        null

    override suspend fun runLiveDelivery(onEnvelope: (Envelope) -> Unit) = Unit
}

/**
 * In-memory [TrustState] for unit-testing the channel + foundation without DataStore. Configure the
 * roster via [peers] and the fold results via [incomingResult] / [profileApplies]; inspect what the
 * engine applied via the `applied*` / `folded*` lists. NS2 operational-key resolution is configured via
 * [operationalSpkis] / [peerEpochs]; ingested key-epochs are recorded in [appliedKeyEpochs].
 */
class FakeTrustState : TrustState {
    val peers = MutableStateFlow<List<Peer>>(emptyList())
    override val activePeers: StateFlow<List<Peer>> = peers

    var table: TrustTable = TrustTable(emptyList())
    var cards: List<SignedBlob> = emptyList()
    var incomingResult: IncomingTrustResult = IncomingTrustResult(emptyList(), emptyList())
    var profileApplies: Boolean = true
    var selfEpochValue: Int = 1
    var pendingRotationValue: PendingRotation? = null

    /** (clientId, epoch) → operational SPKI, for [peerOperationalSpki] resolution in deliver tests. */
    var operationalSpkis: Map<Pair<ClientId, Int>, ByteArray> = emptyMap()

    /** clientId → highest epoch we "hold", for [peerEpoch] (drives refetch-on-higher-epoch tests). */
    var peerEpochs: Map<ClientId, Int> = emptyMap()

    /** Trusted peer ids returned by [trustedClientIds] (drives the proactive key-epoch convergence pull). */
    var trustedIds: List<ClientId> = emptyList()

    /** Peers returned by [peersNeedingKeyEpoch] (missing-or-expired key — drives convergence + refetch). */
    var peersNeeding: List<ClientId> = emptyList()

    /** clientId → current key-epoch blob, for [currentKeyEpochBlob] (the repair-relay source). */
    var currentKeyEpochBlobs: Map<ClientId, SignedBlob> = emptyMap()

    val appliedProfiles = mutableListOf<ProfileUpdate>()
    val foldedTables = mutableListOf<Pair<ClientId, TrustTable>>()
    val appliedCards = mutableListOf<Pair<ClientId, SignedBlob>>()
    val appliedKeyEpochs = mutableListOf<Pair<ClientId, SignedBlob>>()

    override fun displayName(clientId: ClientId): String? =
        peers.value.firstOrNull { it.clientId == clientId }?.displayName

    override fun buildTrustTable(): TrustTable = table
    override fun trustedCards(): List<SignedBlob> = cards
    override fun applyProfile(update: ProfileUpdate): Boolean {
        appliedProfiles.add(update)
        // Mutate the roster name like the real store, so a row built from displayName AFTER apply
        // would observe the new name — letting tests pin the capture-before-mutation ordering.
        if (profileApplies) peers.value =
            peers.value.map { if (it.clientId == update.clientId) it.copy(displayName = update.displayName) else it }
        return profileApplies
    }

    override fun applyIncomingTable(sender: ClientId, table: TrustTable): IncomingTrustResult {
        foldedTables.add(sender to table); return incomingResult
    }

    override fun applyCard(clientId: ClientId, cardBlob: SignedBlob): Boolean {
        appliedCards.add(clientId to cardBlob); return true
    }

    override fun applyKeyEpoch(clientId: ClientId, keyEpochBlob: SignedBlob): Boolean {
        appliedKeyEpochs.add(clientId to keyEpochBlob); return true
    }

    override fun peerOperationalSpki(clientId: ClientId, epoch: Int): ByteArray? =
        operationalSpkis[clientId to epoch]

    override fun peerEpoch(clientId: ClientId): Int = peerEpochs[clientId] ?: 0
    override fun trustedClientIds(): List<ClientId> = trustedIds
    override fun peersNeedingKeyEpoch(now: Long): List<ClientId> = peersNeeding
    override fun currentKeyEpochBlob(clientId: ClientId): SignedBlob? =
        currentKeyEpochBlobs[clientId]

    override fun selfEpoch(): Int = selfEpochValue
    override fun advanceSelfEpoch(to: Int): Int {
        if (to > selfEpochValue) selfEpochValue = to; return selfEpochValue
    }

    override fun pendingRotation(): PendingRotation? = pendingRotationValue
    override fun setPendingRotation(pending: PendingRotation?) {
        pendingRotationValue = pending
    }
}

private val enc = Base64.getEncoder()

fun newSigner(): SoftwareIdentitySigner = SoftwareIdentitySigner.generate()
fun newHpke(): HpkeKeyPair = Hpke.generateKeyPair()

/** A software operational signer bound to [signer]'s identity fingerprint at [epoch] (the NS2 hot key). */
fun newOperationalSigner(signer: IdentitySigner, epoch: Int = 1): SoftwareOperationalSigner =
    SoftwareOperationalSigner.generate(signer.clientId, epoch)

/** A [Peer] for [signer] with the given HPKE public keyset (and NS2 [currentEpoch], 0 = NS1-era). */
fun peerOf(
    signer: IdentitySigner,
    hpkePublicKey: ByteArray,
    ownDevice: Boolean = true,
    name: String = "peer",
    platform: String = "android",
    profileTs: Long = 0L,
    currentEpoch: Int = 0,
): Peer = Peer(
    clientId = signer.clientId,
    displayName = name,
    platform = platform,
    identityPublicKeyB64 = enc.encodeToString(signer.publicKeySpki),
    hpkePublicKeyB64 = enc.encodeToString(hpkePublicKey),
    addedAt = 1L,
    profileUpdatedAt = profileTs,
    ownDevice = ownDevice,
    currentEpoch = currentEpoch,
)

/** Build an identity-signed [SignedType.KEY_EPOCH] blob for [signer] (the device's own identity root). */
fun keyEpochBlob(
    signer: IdentitySigner,
    op: OperationalSigner,
    hpkePublicKey: ByteArray,
    epoch: Int = 1,
    minEpoch: Int = epoch,
    notBefore: Long = 0L,
    notAfter: Long = Long.MAX_VALUE,
    purposes: List<Purpose> = listOf(
        Purpose.ENVELOPE_SIGN,
        Purpose.REQUEST_AUTH,
        Purpose.HPKE_SEAL
    ),
): SignedBlob {
    val ke = ClientKeyEpoch(
        clientId = signer.clientId,
        identityPublicKey = signer.publicKeySpki,
        epoch = epoch,
        operationalSigningKey = op.operationalPublicKeySpki,
        hpkePublicKey = hpkePublicKey,
        purposes = purposes,
        notBefore = notBefore,
        notAfter = notAfter,
        minEpoch = minEpoch,
    )
    val payload = ProtocolCodec.encodeToCbor(ke)
    return SignedBlob(
        SignedType.KEY_EPOCH,
        signerId = signer.clientId,
        payload = payload,
        sig = signer.sign(payload)
    )
}

/** Seal [body] from [sender]'s IDENTITY key (signerEpoch 0) to a single recipient — one identity-signed send. */
fun seal(
    sender: IdentitySigner,
    typ: MessageType,
    body: ByteArray,
    recipientId: ClientId,
    recipientHpkePublic: ByteArray,
    messageId: String,
    seq: Long = 1L,
    createdAt: Long = 1L,
    recipientEpoch: Int = 0,
): Envelope = EnvelopeCrypto.seal(
    sender,
    typ,
    body,
    listOf(RecipientKey(recipientId, recipientHpkePublic, recipientEpoch)),
    messageId,
    seq,
    createdAt
)

/** Seal [body] from [sender]'s OPERATIONAL key (signerEpoch ≥1) to a single recipient — the NS2 hot path. */
fun sealOperational(
    sender: OperationalSigner,
    typ: MessageType,
    body: ByteArray,
    recipientId: ClientId,
    recipientHpkePublic: ByteArray,
    messageId: String,
    seq: Long = 1L,
    createdAt: Long = 1L,
    recipientEpoch: Int = 1,
): Envelope = EnvelopeCrypto.seal(
    sender,
    typ,
    body,
    listOf(RecipientKey(recipientId, recipientHpkePublic, recipientEpoch)),
    messageId,
    seq,
    createdAt
)

/**
 * Construct a [SecureChannel] for tests with the NS2 dual-signer surface: a software operational signer for
 * the hot path and an epoch-agnostic private HPKE keyset selector (tests seal at recipientEpoch 0 or 1 and
 * open with the same [myHpkePrivate] bytes). Centralises the constructor so per-test call sites stay terse.
 */
fun testChannel(
    me: IdentitySigner,
    myHpkePrivate: ByteArray,
    trust: TrustState,
    transport: Transport = CapturingTransport(),
    onBadSignature: (ClientId, Long, DeliveryMode) -> Unit = { _, _, _ -> },
    onUnresolvedSender: (ClientId) -> Unit = { _ -> },
    dedup: MessageDedup? = null,
    operational: OperationalSigner = newOperationalSigner(me),
): SecureChannel = SecureChannel(
    signer = me,
    operationalSigner = { operational },
    myHpkePrivate = { _ -> myHpkePrivate },
    transport = transport,
    directory = TrustPeerDirectory(trust),
    log = {},
    onBadSignature = onBadSignature,
    onUnresolvedSender = onUnresolvedSender,
    dedup = dedup,
)

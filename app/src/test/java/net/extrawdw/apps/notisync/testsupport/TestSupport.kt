package net.extrawdw.apps.notisync.testsupport

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.extrawdw.apps.notisync.data.IncomingTrustResult
import net.extrawdw.apps.notisync.data.Peer
import net.extrawdw.apps.notisync.data.TrustState
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.Envelope
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.ProfileUpdate
import net.extrawdw.notisync.protocol.SendResult
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.Transport
import net.extrawdw.notisync.protocol.TransportType
import net.extrawdw.notisync.protocol.TrustTable
import net.extrawdw.notisync.protocol.Urgency
import net.extrawdw.notisync.protocol.crypto.EnvelopeCrypto
import net.extrawdw.notisync.protocol.crypto.Hpke
import net.extrawdw.notisync.protocol.crypto.HpkeKeyPair
import net.extrawdw.notisync.protocol.crypto.IdentitySigner
import net.extrawdw.notisync.protocol.crypto.RecipientKey
import net.extrawdw.notisync.protocol.crypto.SoftwareIdentitySigner
import java.util.Base64

/** Records everything sent so a broadcast can be opened back as the recipient. */
class CapturingTransport : Transport {
    override val type = TransportType.WEBSOCKET
    val sent = mutableListOf<Pair<Envelope, Urgency>>()
    val envelopes: List<Envelope> get() = sent.map { it.first }
    override suspend fun send(envelope: Envelope, urgency: Urgency): SendResult {
        sent.add(envelope to urgency); return SendResult(accepted = true)
    }
    override suspend fun publishCard(card: SignedBlob) = Unit
    override suspend fun publishRoutes(routes: List<SignedBlob>) = Unit
    override suspend fun fetchCard(clientId: ClientId): SignedBlob? = null
    override suspend fun uploadPrivateAsset(sourceClientId: ClientId, assetId: String, ciphertext: ByteArray) = true
    override suspend fun fetchPrivateAsset(sourceClientId: ClientId, assetId: String): ByteArray? = null
    override suspend fun runLiveDelivery(onEnvelope: (Envelope) -> Unit) = Unit
}

/**
 * In-memory [TrustState] for unit-testing the channel + foundation without DataStore. Configure the
 * roster via [peers] and the fold results via [incomingResult] / [profileApplies]; inspect what the
 * engine applied via the `applied*` / `folded*` lists.
 */
class FakeTrustState : TrustState {
    val peers = MutableStateFlow<List<Peer>>(emptyList())
    override val activePeers: StateFlow<List<Peer>> = peers

    var table: TrustTable = TrustTable(emptyList())
    var cards: List<SignedBlob> = emptyList()
    var incomingResult: IncomingTrustResult = IncomingTrustResult(emptyList(), emptyList())
    var profileApplies: Boolean = true

    val appliedProfiles = mutableListOf<ProfileUpdate>()
    val foldedTables = mutableListOf<Pair<ClientId, TrustTable>>()
    val appliedCards = mutableListOf<Pair<ClientId, SignedBlob>>()

    override fun displayName(clientId: ClientId): String? = peers.value.firstOrNull { it.clientId == clientId }?.displayName
    override fun buildTrustTable(): TrustTable = table
    override fun trustedCards(): List<SignedBlob> = cards
    override fun applyProfile(update: ProfileUpdate): Boolean {
        appliedProfiles.add(update)
        // Mutate the roster name like the real store, so a row built from displayName AFTER apply
        // would observe the new name — letting tests pin the capture-before-mutation ordering.
        if (profileApplies) peers.value = peers.value.map { if (it.clientId == update.clientId) it.copy(displayName = update.displayName) else it }
        return profileApplies
    }
    override fun applyIncomingTable(sender: ClientId, table: TrustTable): IncomingTrustResult { foldedTables.add(sender to table); return incomingResult }
    override fun applyCard(clientId: ClientId, cardBlob: SignedBlob): Boolean { appliedCards.add(clientId to cardBlob); return true }
}

private val enc = Base64.getEncoder()

fun newSigner(): SoftwareIdentitySigner = SoftwareIdentitySigner.generate()
fun newHpke(): HpkeKeyPair = Hpke.generateKeyPair()

/** A [Peer] for [signer] with the given HPKE public keyset. */
fun peerOf(
    signer: IdentitySigner,
    hpkePublicKeyset: ByteArray,
    ownDevice: Boolean = true,
    name: String = "peer",
    profileTs: Long = 0L,
): Peer = Peer(
    clientId = signer.clientId,
    displayName = name,
    platform = "android",
    identityPublicKeyB64 = enc.encodeToString(signer.publicKeySpki),
    hpkePublicKeysetB64 = enc.encodeToString(hpkePublicKeyset),
    addedAt = 1L,
    profileUpdatedAt = profileTs,
    ownDevice = ownDevice,
)

/** Seal [body] from [sender] to a single recipient — the test-side equivalent of one channel send. */
fun seal(
    sender: IdentitySigner,
    typ: MessageType,
    body: ByteArray,
    recipientId: ClientId,
    recipientHpkePublic: ByteArray,
    messageId: String,
    seq: Long = 1L,
    createdAt: Long = 1L,
): Envelope = EnvelopeCrypto.seal(sender, typ, body, listOf(RecipientKey(recipientId, recipientHpkePublic)), messageId, seq, createdAt)

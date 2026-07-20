package net.extrawdw.notisync.peer.ports

import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.AttestationType
import net.extrawdw.notisync.protocol.IntegrityVerificationResponse
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.crypto.IdentitySigner
import net.extrawdw.notisync.protocol.crypto.OperationalSigner
import net.extrawdw.notisync.peer.transport.DeliveryMode
import net.extrawdw.notisync.peer.trust.TrustPrompt

/** Persisted sections used by the signed trust store. Keys are stable wire/storage names; [write] applies
 *  the complete batch atomically and durably before returning. */
interface TrustPersistence {
    fun read(key: String): String?
    fun write(values: Map<String, String?>)
}

/** Client-integrity evidence is a platform concern (Firebase on Android, none on desktop). */
fun interface IntegrityEvidenceProvider {
    suspend fun evidence(): IntegrityEvidence
}

data class IntegrityEvidence(
    val type: String,
    val token: String? = null,
    val keyId: String? = null,
)

/** Desktop provider for brokers configured with integrityRequired=false. */
data object NoIntegrityEvidenceProvider : IntegrityEvidenceProvider {
    override suspend fun evidence(): IntegrityEvidence = IntegrityEvidence(AttestationType.NONE)
}

/** Small observability port; peer-core must not depend on Firebase or android.util.Log. */
interface PeerTelemetry {
    fun warning(component: String, message: String) = Unit
    fun event(
        name: String,
        attributes: Map<String, String> = emptyMap(),
        metrics: Map<String, Long> = emptyMap(),
    ) = Unit

    data object None : PeerTelemetry
}

class PeerTrace internal constructor(
    private val telemetry: PeerTelemetry,
    private val name: String,
) {
    private val attributes = linkedMapOf<String, String>()
    private val metrics = linkedMapOf<String, Long>()

    fun attr(key: String, value: String) { attributes[key] = value }
    fun metric(key: String, value: Long) { metrics[key] = value }
    fun stop() = telemetry.event(name, attributes.toMap(), metrics.toMap())
}

fun PeerTelemetry.trace(name: String): PeerTrace = PeerTrace(this, name)

inline fun <T> PeerTelemetry.trace(name: String, block: (PeerTrace) -> T): T {
    val trace = trace(name)
    return try { block(trace) } finally { trace.stop() }
}

/** Foundation events are presentation-neutral; an app may localize and persist them. */
interface FoundationEventSink {
    fun profileBroadcast(recipientCount: Int) = Unit
    fun profileRenamed(
        newName: String,
        previousName: String,
        deliveryMode: DeliveryMode?,
    ) = Unit
    fun trustChanged(
        subject: ClientId,
        prompt: TrustPrompt,
        introducedBy: String,
        deliveryMode: DeliveryMode?,
        automaticallyApplied: Boolean,
    ) = Unit

    data object None : FoundationEventSink
}

/** Policy for authenticated trust-table assertions received from an already trusted own device. */
fun interface IncomingTrustPolicy {
    fun shouldAutoApply(change: IncomingTrustChange): Boolean

    companion object {
        val MANUAL = IncomingTrustPolicy { false }
        val TRUSTED_OWN_DEVICES = IncomingTrustPolicy { it.senderIsTrustedOwnDevice }
    }
}

data class IncomingTrustChange(
    val senderId: ClientId,
    val subjectId: ClientId,
    val prompt: TrustPrompt,
    val senderIsTrustedOwnDevice: Boolean,
)

/** Durable cross-session message idempotency. */
interface MessageDedupRepository {
    fun seen(messageId: String): Boolean
    fun record(messageId: String)
}

/** Broker bearer persistence; implementations may encrypt it at rest. */
interface AuthTokenRepository {
    fun load(): IntegrityVerificationResponse?
    fun save(token: IntegrityVerificationResponse?)
}

/**
 * Key operations exposed to a desktop peer. Private material stays behind this interface so a file
 * provider can later be replaced by Keychain, Secret Service, or a GnuPG-backed provider.
 */
interface KeyMaterialProvider {
    val identity: IdentitySigner
    fun operationalSigner(epoch: Int): OperationalSigner
    fun currentOperationalSigner(): OperationalSigner
    fun hpkePrivateKeyset(epoch: Int): ByteArray?
    fun hpkePublicKeyset(epoch: Int): ByteArray
    fun currentKeyEpoch(): SignedBlob
    fun destroyEpoch(epoch: Int)
}

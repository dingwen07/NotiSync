package net.extrawdw.apps.notisync.data.corecommand.preparation

import net.extrawdw.apps.notisync.data.corecommand.CoreCommandLimits
import net.extrawdw.apps.notisync.data.corecommand.requireCompactIdentifier
import net.extrawdw.apps.notisync.data.relay.RelayStableCode
import net.extrawdw.notisync.peer.trust.SignedTrustSnapshot
import net.extrawdw.notisync.protocol.crypto.IdentitySigner

/** One repository-hydrated Core identity plus its exact, already integrity-checked signed trust authority. */
internal class CoreTrustPreparationSnapshot(
    val identityAlias: String,
    val identityClientId: String,
    identityPublicSpki: ByteArray,
    trustSnapshot: SignedTrustSnapshot,
    trustSnapshotDigest: ByteArray,
) {
    private val storedIdentityPublicSpki = identityPublicSpki.copyOf()
    private val storedTrustSnapshot = trustSnapshot.defensivePreparationCopy()
    private val storedTrustSnapshotDigest = trustSnapshotDigest.copyOf()

    init {
        require(identityAlias.isNotBlank() && identityAlias.length <= MAX_IDENTITY_ALIAS_CHARS) {
            "Core identity alias is invalid"
        }
        require(identityAlias.none(Char::isISOControl)) { "Core identity alias contains control characters" }
        requireCompactIdentifier(identityClientId, "Core identity client id")
        require(storedIdentityPublicSpki.isNotEmpty() && storedIdentityPublicSpki.size <= MAX_IDENTITY_SPKI_BYTES) {
            "Core identity SPKI is outside the reviewed bound"
        }
        require(storedTrustSnapshotDigest.size == CoreCommandLimits.SHA256_BYTES) {
            "Core trust snapshot digest must be SHA-256"
        }
    }

    fun identityPublicSpkiCopy(): ByteArray = storedIdentityPublicSpki.copyOf()
    fun trustSnapshotCopy(): SignedTrustSnapshot = storedTrustSnapshot.defensivePreparationCopy()
    fun trustSnapshotDigestCopy(): ByteArray = storedTrustSnapshotDigest.copyOf()

    override fun toString(): String =
        "CoreTrustPreparationSnapshot(identityAlias=<redacted>, identityClientId=$identityClientId, " +
            "identitySpki=<${storedIdentityPublicSpki.size} bytes>, trust=$storedTrustSnapshot, " +
            "digest=<${storedTrustSnapshotDigest.size} bytes>)"
}

/** Typed repository outcome; storage exceptions and cancellation deliberately escape unchanged. */
internal sealed interface CoreTrustPreparationSnapshotResult {
    data class Ready(val snapshot: CoreTrustPreparationSnapshot) : CoreTrustPreparationSnapshotResult
    data class NotReady(val errorCode: RelayStableCode) : CoreTrustPreparationSnapshotResult
    data class SecurityBlocked(val errorCode: RelayStableCode) : CoreTrustPreparationSnapshotResult
}

/** Storage-independent read boundary. Its implementation may hydrate Core Room, but this package cannot import it. */
internal fun interface CoreTrustPreparationSnapshotReader {
    suspend fun readCurrent(): CoreTrustPreparationSnapshotResult
}

/** Load the already-recorded non-exportable identity key. Implementations must never create or replace an alias. */
internal fun interface ExistingIdentitySignerLoader {
    suspend fun loadExisting(identityAlias: String): IdentitySigner?
}

private fun SignedTrustSnapshot.defensivePreparationCopy(): SignedTrustSnapshot = SignedTrustSnapshot(
    format = format,
    entriesUtf8 = entriesUtf8Copy(),
    cardsUtf8 = cardsUtf8Copy(),
    overlaysUtf8 = overlaysUtf8Copy(),
    epochsUtf8 = epochsUtf8CopyOrNull(),
    signatureBase64UrlUtf8 = signatureBase64UrlUtf8Copy(),
)

private const val MAX_IDENTITY_ALIAS_CHARS = 256
private const val MAX_IDENTITY_SPKI_BYTES = 4 * 1024

package net.extrawdw.apps.notisync.data.corecommand.preparation

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import net.extrawdw.apps.notisync.crypto.AndroidIdentitySigner
import net.extrawdw.apps.notisync.data.relay.RelayStableCode
import net.extrawdw.apps.notisync.data.storage.core.CoreFoundationRepository
import net.extrawdw.apps.notisync.data.storage.core.CoreTrustIntegrityException
import net.extrawdw.apps.notisync.data.storage.core.IdentityLifecycleState
import net.extrawdw.apps.notisync.data.storage.core.TrustSignatureFormat
import net.extrawdw.apps.notisync.data.storage.core.TrustSnapshot
import net.extrawdw.notisync.peer.trust.SignedTrustSnapshot
import net.extrawdw.notisync.peer.trust.SignedTrustSnapshotFormat

/**
 * Production Core-to-reducer read adapter.
 *
 * Identity key binding is immutable after bootstrap, while the trust digest is rechecked by the later Core
 * compare-and-replace transaction. Consequently these two short repository reads do not need a long SQL
 * transaction: a concurrent trust replacement merely makes the prepared command stale and retryable at commit.
 */
internal class RepositoryCoreTrustPreparationSnapshotReader(
    private val repository: CoreFoundationRepository,
) : CoreTrustPreparationSnapshotReader {
    override suspend fun readCurrent(): CoreTrustPreparationSnapshotResult {
        val identity = repository.identity.first()
            ?: return CoreTrustPreparationSnapshotResult.NotReady(CODE_IDENTITY_MISSING)
        if (identity.lifecycleState != IdentityLifecycleState.ACTIVE) {
            return CoreTrustPreparationSnapshotResult.SecurityBlocked(CODE_IDENTITY_INACTIVE)
        }

        val trust = try {
            repository.loadValidatedTrustSnapshot()
        } catch (_: CoreTrustIntegrityException) {
            return CoreTrustPreparationSnapshotResult.SecurityBlocked(CODE_TRUST_INVALID)
        } ?: return CoreTrustPreparationSnapshotResult.NotReady(CODE_TRUST_MISSING)

        return try {
            CoreTrustPreparationSnapshotResult.Ready(
                CoreTrustPreparationSnapshot(
                    identityAlias = identity.keyAlias,
                    identityClientId = identity.clientId,
                    identityPublicSpki = identity.publicSpki,
                    trustSnapshot = trust.toPreparationSignedSnapshot(),
                    trustSnapshotDigest = trust.snapshotDigest,
                ),
            )
        } catch (_: IllegalArgumentException) {
            CoreTrustPreparationSnapshotResult.SecurityBlocked(CODE_TRUST_INVALID)
        } catch (_: IllegalStateException) {
            CoreTrustPreparationSnapshotResult.SecurityBlocked(CODE_TRUST_INVALID)
        }
    }

    private companion object {
        val CODE_IDENTITY_MISSING = RelayStableCode.of("core_identity_missing")
        val CODE_IDENTITY_INACTIVE = RelayStableCode.of("core_identity_inactive")
        val CODE_TRUST_MISSING = RelayStableCode.of("core_trust_missing")
        val CODE_TRUST_INVALID = RelayStableCode.of("core_trust_invalid")
    }
}

/** Existing-only Android Keystore adapter; it never creates, replaces, or deletes an alias. */
internal class AndroidExistingIdentitySignerLoader(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ExistingIdentitySignerLoader {
    override suspend fun loadExisting(identityAlias: String) = withContext(ioDispatcher) {
        AndroidIdentitySigner.loadExisting(identityAlias)
    }
}

internal fun TrustSnapshot.toPreparationSignedSnapshot(): SignedTrustSnapshot = SignedTrustSnapshot(
    format = when (signatureFormat) {
        TrustSignatureFormat.TRUSTSTORE_V1_THREE_SECTION -> SignedTrustSnapshotFormat.THREE_SECTION
        TrustSignatureFormat.TRUSTSTORE_V1_FOUR_SECTION -> SignedTrustSnapshotFormat.FOUR_SECTION
    },
    entriesUtf8 = entriesUtf8,
    cardsUtf8 = cardsUtf8,
    overlaysUtf8 = overlaysUtf8,
    epochsUtf8 = epochsUtf8,
    signatureBase64UrlUtf8 = signatureBase64UrlUtf8,
)

package net.extrawdw.apps.notisync.data.storage.importer.target.core.mapping

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import net.extrawdw.apps.notisync.data.storage.importer.legacy.core.LegacyCoreConsistencyResult
import net.extrawdw.apps.notisync.data.storage.importer.legacy.core.LegacyCoreFileReadResult
import net.extrawdw.apps.notisync.data.storage.importer.legacy.core.LegacyCoreKeystoreReadResult
import net.extrawdw.apps.notisync.data.storage.importer.legacy.core.LegacyCorePreferencesReadResult
import net.extrawdw.apps.notisync.data.storage.importer.legacy.core.LegacyCoreReadStatus
import net.extrawdw.apps.notisync.data.storage.importer.legacy.core.LegacyCoreSourceConsistencyValidator
import net.extrawdw.apps.notisync.data.storage.importer.legacy.core.LegacyKeystoreSecurityLevel
import net.extrawdw.apps.notisync.data.storage.importer.legacy.core.LegacySignedTrustFormat
import net.extrawdw.apps.notisync.data.storage.importer.legacy.core.LegacySignedTrustSource
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51EpochCommand
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51EpochLifecycle
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51IdentityBacking
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51IdentityCommand
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51ImportPlan
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51MaintenanceCommand
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51OperationalBacking
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51TransportCommand
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51TrustCommand
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51WrappedAuthTokenCommand
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51WrappingKeyCommand
import net.extrawdw.notisync.protocol.ProtocolCodec

internal data class CoreV51MappingDefaults(
    val defaultBrokerUrl: String,
) {
    init {
        require(defaultBrokerUrl.isNotBlank()) { "Core v51 default broker must not be blank" }
    }
}

internal enum class CoreV51MappingIssue {
    SOURCE_REQUIRES_RECOVERY,
    SOURCE_CONTRACT_DRIFT,
    MISSING_KEY_CREATION_TIME,
    UNSUPPORTED_OPERATIONAL_BACKING,
    UNAUTHORIZED_EPOCH_INVENTORY,
}

/** Value-free mapping failure; source values and key bytes never enter its message. */
internal class CoreV51MappingException(
    val issue: CoreV51MappingIssue,
) : IllegalStateException("Core v51 source mapping failed: ${issue.name}")

/**
 * The only legacy-aware mapper. It emits Room-independent target commands and cannot import Core entities/DAOs.
 * Shipped defaults are explicit inputs, so this pure mapping never consults BuildConfig, clocks, or mutable stores.
 */
internal class LegacyCoreV51Mapper(
    private val defaults: CoreV51MappingDefaults,
    private val consistencyValidator: LegacyCoreSourceConsistencyValidator =
        LegacyCoreSourceConsistencyValidator(),
) {
    fun map(
        preferences: LegacyCorePreferencesReadResult,
        keystore: LegacyCoreKeystoreReadResult,
        files: LegacyCoreFileReadResult,
    ): CoreV51ImportPlan {
        val consistency = consistencyValidator.validate(preferences, keystore, files)
        if (consistency.status == LegacyCoreReadStatus.RECOVERY_REQUIRED) {
            throw CoreV51MappingException(CoreV51MappingIssue.SOURCE_REQUIRES_RECOVERY)
        }
        if (consistency.status == LegacyCoreReadStatus.ABSENT) return CoreV51ImportPlan.absent()
        consistency.requireReady()

        val keySource = keystore.snapshot
            ?: throw CoreV51MappingException(CoreV51MappingIssue.SOURCE_CONTRACT_DRIFT)
        val fileSource = files.snapshot
            ?: throw CoreV51MappingException(CoreV51MappingIssue.SOURCE_CONTRACT_DRIFT)
        val settings = preferences.snapshot
        val signedTrust = settings?.signedTrust
        val currentEpoch = signedTrust?.effectiveSelfEpoch ?: LEGACY_DEFAULT_SELF_EPOCH
        val pending = signedTrust?.pendingRotationOrNull()

        val identity = keySource.identity
        val identityCreatedAt = identity.createdAt
            ?: throw CoreV51MappingException(CoreV51MappingIssue.MISSING_KEY_CREATION_TIME)
        val wrappingCreatedAt = keySource.wrappingKey.createdAt
            ?: throw CoreV51MappingException(CoreV51MappingIssue.MISSING_KEY_CREATION_TIME)
        val hpkeByEpoch = fileSource.hpkeEpochs.associateBy { it.epoch }
        val signerEpochs = keySource.operationalSigners.map { it.epoch }
        if (signedTrust == null && signerEpochs != listOf(LEGACY_DEFAULT_SELF_EPOCH)) {
            throw CoreV51MappingException(CoreV51MappingIssue.UNAUTHORIZED_EPOCH_INVENTORY)
        }

        val epochs = keySource.operationalSigners.map { signer ->
            val hpke = hpkeByEpoch[signer.epoch]
                ?: throw CoreV51MappingException(CoreV51MappingIssue.SOURCE_CONTRACT_DRIFT)
            val lifecycle = signer.epoch.toLifecycle(currentEpoch, pending)
            CoreV51EpochCommand(
                epoch = signer.epoch,
                operationalSignerAlias = signer.alias,
                operationalSignerAliasVersion = signer.aliasVersion,
                operationalSignerPublicSpki = signer.publicSpkiCopy(),
                hpkePublicKeyset = hpke.publicKeysetCopy(),
                hpkePrivateKeysetWrapped = hpke.wrappedPrivateKeysetCopy(),
                backing = signer.securityLevel.toOperationalBacking(),
                lifecycle = lifecycle,
                antiRollbackFloor = currentEpoch.toLong(),
                // v51's runtime-defined default is zero until the rotation clock is first seeded.
                activationAt = if (lifecycle == CoreV51EpochLifecycle.ACTIVE) {
                    settings?.selfEpochActivatedAt ?: LEGACY_UNSEEDED_ACTIVATION_TIME
                } else {
                    null
                },
                retirementAt = pending?.takeIf {
                    lifecycle == CoreV51EpochLifecycle.RETIRED &&
                        it.retiredEpoch == signer.epoch && it.targetEpoch <= currentEpoch
                }?.retireRetiredAt,
                createdAt = signer.createdAt
                    ?: throw CoreV51MappingException(CoreV51MappingIssue.MISSING_KEY_CREATION_TIME),
            )
        }

        val foundation = CoreV51ImportPlan.Foundation(
            identity = CoreV51IdentityCommand(
                alias = identity.alias,
                aliasVersion = identity.aliasVersion,
                publicSpki = identity.publicSpkiCopy(),
                backing = identity.securityLevel.toIdentityBacking(),
                createdAt = identityCreatedAt,
            ),
            wrappingKey = CoreV51WrappingKeyCommand(
                alias = keySource.wrappingKey.alias,
                aliasVersion = keySource.wrappingKey.aliasVersion,
                backing = keySource.wrappingKey.securityLevel.toIdentityBacking(),
                createdAt = wrappingCreatedAt,
            ),
            trust = signedTrust?.toTargetTrust(),
            epochs = epochs,
            authToken = fileSource.authToken?.let { CoreV51WrappedAuthTokenCommand(it.wrappedTokenCopy()) },
            transport = CoreV51TransportCommand(
                brokerUrl = settings?.brokerUrl ?: defaults.defaultBrokerUrl,
                groupId = settings?.groupId,
                fcmRouteRef = settings?.fcmRouteRef,
                routeEpoch = settings?.routeEpoch?.toLong() ?: 0L,
                // Physical absence is retained for transport; unlike the epoch row this column is nullable.
                selfEpochActivatedAt = settings?.selfEpochActivatedAt,
            ),
            maintenance = CoreV51MaintenanceCommand(
                trustCleanupCompleted = settings?.trustCleanupCompleted == true,
            ),
            currentEpoch = currentEpoch,
            skippedUnversionedHpkeFileCount = fileSource.skippedUnversionedHpkeFileCount,
        )
        return CoreV51ImportPlan.ready(foundation)
    }

    private fun LegacyCoreConsistencyResult.requireReady() {
        if (status != LegacyCoreReadStatus.READY || issues.isNotEmpty()) {
            throw CoreV51MappingException(CoreV51MappingIssue.SOURCE_CONTRACT_DRIFT)
        }
    }

    private fun LegacySignedTrustSource.toTargetTrust(): CoreV51TrustCommand = when (format) {
        LegacySignedTrustFormat.LEGACY_THREE_SECTION -> CoreV51TrustCommand.ThreeSection(
            entriesUtf8 = entriesUtf8(),
            cardsUtf8 = cardsUtf8(),
            overlaysUtf8 = overlaysUtf8(),
            signatureBase64UrlUtf8 = signatureBase64Url.encodeToByteArray(),
        )
        LegacySignedTrustFormat.FOUR_SECTION -> CoreV51TrustCommand.FourSection(
            entriesUtf8 = entriesUtf8(),
            cardsUtf8 = cardsUtf8(),
            overlaysUtf8 = overlaysUtf8(),
            epochsUtf8 = requireNotNull(epochsUtf8OrNull()),
            signatureBase64UrlUtf8 = signatureBase64Url.encodeToByteArray(),
        )
    }

    private fun LegacySignedTrustSource.pendingRotationOrNull(): PendingRotationView? {
        if (format == LegacySignedTrustFormat.LEGACY_THREE_SECTION) return null
        val root = runCatching {
            ProtocolCodec.json.parseToJsonElement(requireNotNull(epochsJson)) as? JsonObject
        }.getOrNull() ?: throw CoreV51MappingException(CoreV51MappingIssue.SOURCE_CONTRACT_DRIFT)
        val raw = root["pending"] ?: return null
        if (raw is JsonNull) return null
        val pending = raw as? JsonObject
            ?: throw CoreV51MappingException(CoreV51MappingIssue.SOURCE_CONTRACT_DRIFT)
        return PendingRotationView(
            targetEpoch = pending["targetEpoch"]?.jsonPrimitive?.intOrNull
                ?: throw CoreV51MappingException(CoreV51MappingIssue.SOURCE_CONTRACT_DRIFT),
            retiredEpoch = pending["retiredEpoch"]?.jsonPrimitive?.intOrNull
                ?: throw CoreV51MappingException(CoreV51MappingIssue.SOURCE_CONTRACT_DRIFT),
            retireRetiredAt = pending["retireRetiredAt"]?.jsonPrimitive?.longOrNull
                ?: throw CoreV51MappingException(CoreV51MappingIssue.SOURCE_CONTRACT_DRIFT),
        )
    }
}

private data class PendingRotationView(
    val targetEpoch: Int,
    val retiredEpoch: Int,
    val retireRetiredAt: Long,
)

private fun Int.toLifecycle(currentEpoch: Int, pending: PendingRotationView?): CoreV51EpochLifecycle = when {
    this == currentEpoch -> CoreV51EpochLifecycle.ACTIVE
    this < currentEpoch -> CoreV51EpochLifecycle.RETIRED
    pending?.targetEpoch == this && this > currentEpoch -> CoreV51EpochLifecycle.PROVISIONING
    else -> throw CoreV51MappingException(CoreV51MappingIssue.UNAUTHORIZED_EPOCH_INVENTORY)
}

private fun LegacyKeystoreSecurityLevel.toIdentityBacking(): CoreV51IdentityBacking = when (this) {
    LegacyKeystoreSecurityLevel.UNKNOWN_SECURE -> CoreV51IdentityBacking.HARDWARE_SECURE_UNKNOWN
    LegacyKeystoreSecurityLevel.TRUSTED_ENVIRONMENT -> CoreV51IdentityBacking.TRUSTED_ENVIRONMENT
    LegacyKeystoreSecurityLevel.STRONGBOX -> CoreV51IdentityBacking.STRONGBOX
    LegacyKeystoreSecurityLevel.UNKNOWN,
    LegacyKeystoreSecurityLevel.SOFTWARE,
    -> throw CoreV51MappingException(CoreV51MappingIssue.SOURCE_CONTRACT_DRIFT)
}

private fun LegacyKeystoreSecurityLevel.toOperationalBacking(): CoreV51OperationalBacking = when (this) {
    LegacyKeystoreSecurityLevel.TRUSTED_ENVIRONMENT -> CoreV51OperationalBacking.TRUSTED_ENVIRONMENT
    LegacyKeystoreSecurityLevel.STRONGBOX -> CoreV51OperationalBacking.STRONGBOX
    LegacyKeystoreSecurityLevel.UNKNOWN_SECURE ->
        throw CoreV51MappingException(CoreV51MappingIssue.UNSUPPORTED_OPERATIONAL_BACKING)
    LegacyKeystoreSecurityLevel.UNKNOWN,
    LegacyKeystoreSecurityLevel.SOFTWARE,
    -> throw CoreV51MappingException(CoreV51MappingIssue.SOURCE_CONTRACT_DRIFT)
}

private const val LEGACY_DEFAULT_SELF_EPOCH = 1
private const val LEGACY_UNSEEDED_ACTIVATION_TIME = 0L

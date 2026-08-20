package net.extrawdw.apps.notisync.data.storage.importer.target.core.model

import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Attempt-local origin decision captured before either Room target is written.
 *
 * Physical source discovery and consistency stay inside the owning legacy readers. In particular, Core preferences,
 * files, and aliases are one foundation contract, not public bit positions. Composition must classify any
 * Operational legacy presence without a complete Core foundation as [RECOVERY_REQUIRED].
 */
internal enum class V51LegacySourceInventory {
    ALL_ABSENT,
    CORE_FOUNDATION_PRESENT,
    RECOVERY_REQUIRED,
}

/** Defensive SHA-256 evidence. Its text form is deliberately value-free. */
internal class CoreV51Digest private constructor(bytes: ByteArray) {
    private val stored = bytes.copyOf()

    fun copyBytes(): ByteArray = stored.copyOf()

    override fun equals(other: Any?): Boolean =
        other is CoreV51Digest && MessageDigest.isEqual(stored, other.stored)

    override fun hashCode(): Int = stored.contentHashCode()

    override fun toString(): String = "CoreV51Digest(SHA-256)"

    companion object {
        const val BYTES = 32

        fun sha256(bytes: ByteArray): CoreV51Digest {
            require(bytes.size == BYTES) { "Core v51 evidence must be SHA-256" }
            return CoreV51Digest(bytes)
        }
    }
}

internal enum class CoreV51IdentityBacking {
    HARDWARE_SECURE_UNKNOWN,
    TRUSTED_ENVIRONMENT,
    STRONGBOX,
}

internal enum class CoreV51OperationalBacking {
    TRUSTED_ENVIRONMENT,
    STRONGBOX,
}

internal enum class CoreV51EpochLifecycle {
    PROVISIONING,
    ACTIVE,
    RETIRED,
}

internal class CoreV51IdentityCommand(
    val alias: String,
    val aliasVersion: Int,
    publicSpki: ByteArray,
    val backing: CoreV51IdentityBacking,
    val createdAt: Long,
) {
    private val storedPublicSpki = publicSpki.copyOf()
    fun publicSpkiCopy(): ByteArray = storedPublicSpki.copyOf()

    init {
        require(alias.isNotBlank() && aliasVersion > 0) { "invalid Core v51 identity reference" }
        require(storedPublicSpki.isNotEmpty()) { "Core v51 identity SPKI is empty" }
        require(createdAt >= 0) { "Core v51 identity creation time is invalid" }
    }

    override fun toString(): String =
        "CoreV51IdentityCommand(aliasVersion=$aliasVersion, backing=$backing, key=<redacted>)"
}

internal class CoreV51WrappingKeyCommand(
    val alias: String,
    val aliasVersion: Int,
    val backing: CoreV51IdentityBacking,
    val createdAt: Long,
) {
    init {
        require(alias.isNotBlank() && aliasVersion > 0) { "invalid Core v51 wrapping-key reference" }
        require(createdAt >= 0) { "Core v51 wrapping-key creation time is invalid" }
    }

    override fun toString(): String =
        "CoreV51WrappingKeyCommand(aliasVersion=$aliasVersion, backing=$backing)"
}

internal sealed class CoreV51TrustCommand private constructor(
    entriesUtf8: ByteArray,
    cardsUtf8: ByteArray,
    overlaysUtf8: ByteArray,
    signatureBase64UrlUtf8: ByteArray,
) {
    private val storedEntries = entriesUtf8.copyOf()
    private val storedCards = cardsUtf8.copyOf()
    private val storedOverlays = overlaysUtf8.copyOf()
    private val storedSignature = signatureBase64UrlUtf8.copyOf()

    fun entriesUtf8Copy(): ByteArray = storedEntries.copyOf()
    fun cardsUtf8Copy(): ByteArray = storedCards.copyOf()
    fun overlaysUtf8Copy(): ByteArray = storedOverlays.copyOf()
    fun signatureBase64UrlUtf8Copy(): ByteArray = storedSignature.copyOf()
    abstract fun epochsUtf8OrNull(): ByteArray?

    class ThreeSection(
        entriesUtf8: ByteArray,
        cardsUtf8: ByteArray,
        overlaysUtf8: ByteArray,
        signatureBase64UrlUtf8: ByteArray,
    ) : CoreV51TrustCommand(entriesUtf8, cardsUtf8, overlaysUtf8, signatureBase64UrlUtf8) {
        override fun epochsUtf8OrNull(): ByteArray? = null
    }

    class FourSection(
        entriesUtf8: ByteArray,
        cardsUtf8: ByteArray,
        overlaysUtf8: ByteArray,
        epochsUtf8: ByteArray,
        signatureBase64UrlUtf8: ByteArray,
    ) : CoreV51TrustCommand(entriesUtf8, cardsUtf8, overlaysUtf8, signatureBase64UrlUtf8) {
        private val storedEpochs = epochsUtf8.copyOf()
        override fun epochsUtf8OrNull(): ByteArray = storedEpochs.copyOf()
    }

    override fun toString(): String =
        "CoreV51TrustCommand(format=${if (epochsUtf8OrNull() == null) "THREE_SECTION" else "FOUR_SECTION"}, " +
            "values=<redacted>)"
}

internal class CoreV51EpochCommand(
    val epoch: Int,
    val operationalSignerAlias: String,
    val operationalSignerAliasVersion: Int,
    operationalSignerPublicSpki: ByteArray,
    hpkePublicKeyset: ByteArray,
    hpkePrivateKeysetWrapped: ByteArray,
    val backing: CoreV51OperationalBacking,
    val lifecycle: CoreV51EpochLifecycle,
    val antiRollbackFloor: Long,
    val activationAt: Long?,
    val retirementAt: Long?,
    val createdAt: Long,
) {
    private val storedSignerSpki = operationalSignerPublicSpki.copyOf()
    private val storedHpkePublic = hpkePublicKeyset.copyOf()
    private val storedHpkePrivateWrapped = hpkePrivateKeysetWrapped.copyOf()

    fun operationalSignerPublicSpkiCopy(): ByteArray = storedSignerSpki.copyOf()
    fun hpkePublicKeysetCopy(): ByteArray = storedHpkePublic.copyOf()
    fun hpkePrivateKeysetWrappedCopy(): ByteArray = storedHpkePrivateWrapped.copyOf()

    init {
        require(epoch > 0 && operationalSignerAlias.isNotBlank() && operationalSignerAliasVersion > 0) {
            "invalid Core v51 epoch identity"
        }
        require(storedSignerSpki.isNotEmpty() && storedHpkePublic.isNotEmpty() && storedHpkePrivateWrapped.isNotEmpty()) {
            "Core v51 epoch key material is incomplete"
        }
        require(antiRollbackFloor >= 0 && createdAt >= 0) { "invalid Core v51 epoch metadata" }
        require(activationAt == null || activationAt >= 0) { "invalid Core v51 activation time" }
        require(retirementAt == null || retirementAt >= 0) { "invalid Core v51 retirement time" }
        require(lifecycle != CoreV51EpochLifecycle.ACTIVE || activationAt != null) {
            "active Core v51 epoch lacks activation evidence"
        }
    }

    override fun toString(): String =
        "CoreV51EpochCommand(epoch=$epoch, lifecycle=$lifecycle, backing=$backing, keys=<redacted>)"
}

internal class CoreV51WrappedAuthTokenCommand(wrappedToken: ByteArray) {
    private val stored = wrappedToken.copyOf()
    fun wrappedTokenCopy(): ByteArray = stored.copyOf()

    init {
        require(stored.isNotEmpty()) { "Core v51 wrapped auth token is empty" }
    }

    override fun toString(): String = "CoreV51WrappedAuthTokenCommand(value=<redacted>)"
}

internal data class CoreV51TransportCommand(
    val brokerUrl: String,
    val groupId: String?,
    val fcmRouteRef: String?,
    val routeEpoch: Long,
    val selfEpochActivatedAt: Long?,
) {
    init {
        require(brokerUrl.isNotBlank() && routeEpoch >= 0) { "invalid Core v51 transport state" }
        require(selfEpochActivatedAt == null || selfEpochActivatedAt >= 0) {
            "invalid Core v51 transport activation time"
        }
    }

    override fun toString(): String = "CoreV51TransportCommand(values=<redacted>)"
}

internal data class CoreV51MaintenanceCommand(
    val trustCleanupCompleted: Boolean,
)

/** One immutable, cross-source-consistent v51 foundation plan. Null [foundation] means Core sources were absent. */
internal class CoreV51ImportPlan private constructor(
    val foundation: Foundation?,
) {
    val targetContentDigest: CoreV51Digest = computeTargetContentDigest(foundation)
    val isAbsent: Boolean get() = foundation == null

    class Foundation(
        val identity: CoreV51IdentityCommand,
        val wrappingKey: CoreV51WrappingKeyCommand,
        val trust: CoreV51TrustCommand?,
        epochs: List<CoreV51EpochCommand>,
        val authToken: CoreV51WrappedAuthTokenCommand?,
        val transport: CoreV51TransportCommand,
        val maintenance: CoreV51MaintenanceCommand,
        val currentEpoch: Int,
        val skippedUnversionedHpkeFileCount: Int,
    ) {
        val epochs: List<CoreV51EpochCommand> = epochs.toList()

        init {
            require(currentEpoch > 0 && this.epochs.isNotEmpty()) { "Core v51 epoch inventory is empty" }
            require(this.epochs.map { it.epoch }.distinct().size == this.epochs.size) {
                "Core v51 epoch inventory contains duplicates"
            }
            require(this.epochs.zipWithNext().all { (left, right) -> left.epoch < right.epoch }) {
                "Core v51 epoch inventory is not sorted"
            }
            require(this.epochs.singleOrNull { it.lifecycle == CoreV51EpochLifecycle.ACTIVE }?.epoch == currentEpoch) {
                "Core v51 plan must have exactly one current active epoch"
            }
            require(skippedUnversionedHpkeFileCount in 0..2) { "invalid skipped Core v51 file count" }
        }

        override fun toString(): String =
            "CoreV51Foundation(currentEpoch=$currentEpoch, epochs=${epochs.map { it.epoch }}, values=<redacted>)"
    }

    override fun toString(): String =
        "CoreV51ImportPlan(absent=$isAbsent, evidence=<redacted>)"

    companion object {
        fun absent(): CoreV51ImportPlan = CoreV51ImportPlan(null)

        fun ready(foundation: Foundation): CoreV51ImportPlan = CoreV51ImportPlan(foundation)
    }
}

private fun computeTargetContentDigest(foundation: CoreV51ImportPlan.Foundation?): CoreV51Digest {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.framed("notisync-core-v51-target-plan-v1".encodeToByteArray())
    if (foundation == null) {
        digest.update(0.toByte())
        return CoreV51Digest.sha256(digest.digest())
    }
    digest.update(1.toByte())
    digest.framed(foundation.identity.alias.encodeToByteArray())
    digest.int(foundation.identity.aliasVersion)
    digest.framed(foundation.identity.publicSpkiCopy())
    digest.framed(foundation.identity.backing.name.encodeToByteArray())
    digest.long(foundation.identity.createdAt)
    digest.framed(foundation.wrappingKey.alias.encodeToByteArray())
    digest.int(foundation.wrappingKey.aliasVersion)
    digest.framed(foundation.wrappingKey.backing.name.encodeToByteArray())
    digest.long(foundation.wrappingKey.createdAt)
    foundation.trust.let { trust ->
        digest.update(if (trust == null) 0.toByte() else 1.toByte())
        if (trust != null) {
            digest.framed(if (trust is CoreV51TrustCommand.ThreeSection) byteArrayOf(3) else byteArrayOf(4))
            digest.framed(trust.entriesUtf8Copy())
            digest.framed(trust.cardsUtf8Copy())
            digest.framed(trust.overlaysUtf8Copy())
            trust.epochsUtf8OrNull()?.let(digest::framed)
            digest.framed(trust.signatureBase64UrlUtf8Copy())
        }
    }
    foundation.epochs.forEach { epoch ->
        digest.int(epoch.epoch)
        digest.framed(epoch.operationalSignerAlias.encodeToByteArray())
        digest.int(epoch.operationalSignerAliasVersion)
        digest.framed(epoch.operationalSignerPublicSpkiCopy())
        digest.framed(epoch.hpkePublicKeysetCopy())
        digest.framed(epoch.hpkePrivateKeysetWrappedCopy())
        digest.framed(epoch.backing.name.encodeToByteArray())
        digest.framed(epoch.lifecycle.name.encodeToByteArray())
        digest.long(epoch.antiRollbackFloor)
        digest.nullableLong(epoch.activationAt)
        digest.nullableLong(epoch.retirementAt)
        digest.long(epoch.createdAt)
    }
    foundation.authToken.let { token ->
        digest.update(if (token == null) 0.toByte() else 1.toByte())
        token?.let { digest.framed(it.wrappedTokenCopy()) }
    }
    digest.framed(foundation.transport.brokerUrl.encodeToByteArray())
    digest.nullableText(foundation.transport.groupId)
    digest.nullableText(foundation.transport.fcmRouteRef)
    digest.long(foundation.transport.routeEpoch)
    digest.nullableLong(foundation.transport.selfEpochActivatedAt)
    digest.update(if (foundation.maintenance.trustCleanupCompleted) 1.toByte() else 0.toByte())
    digest.int(foundation.currentEpoch)
    digest.int(foundation.skippedUnversionedHpkeFileCount)
    return CoreV51Digest.sha256(digest.digest())
}

private fun MessageDigest.framed(bytes: ByteArray) {
    update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
    update(bytes)
}

private fun MessageDigest.int(value: Int) = update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value).array())
private fun MessageDigest.long(value: Long) = update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(value).array())

private fun MessageDigest.nullableLong(value: Long?) {
    update(if (value == null) 0.toByte() else 1.toByte())
    value?.let(::long)
}

private fun MessageDigest.nullableText(value: String?) {
    update(if (value == null) 0.toByte() else 1.toByte())
    value?.encodeToByteArray()?.let(::framed)
}

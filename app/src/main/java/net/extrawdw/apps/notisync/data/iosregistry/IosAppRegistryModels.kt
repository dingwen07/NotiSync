package net.extrawdw.apps.notisync.data.iosregistry

import kotlinx.coroutines.flow.Flow

object IosAppRegistryLimits {
    const val MAX_BUNDLE_ID_CHARS = 512
    const val MAX_DISPLAY_CHARS = 1_024
}

/** Independent allowlist membership. It intentionally has no display metadata or foreign-key dependency. */
data class IosAllowlistedApp(
    val bundleId: String,
) {
    init {
        requireBundleId(bundleId)
    }
}

/** Independently observed iOS metadata. An observed app may be absent from the allowlist and vice versa. */
data class IosSeenApp(
    val bundleId: String,
    val displayName: String,
    val lastSeenAt: Long,
) {
    init {
        requireBundleId(bundleId)
        require(displayName.isNotBlank() && displayName.length <= IosAppRegistryLimits.MAX_DISPLAY_CHARS) {
            "iOS display name is invalid"
        }
        require(displayName.none(Char::isISOControl)) {
            "iOS display name contains a control character"
        }
        require(lastSeenAt > 0) { "iOS app last-seen timestamp must be positive" }
    }
}

/** Domain-facing owner of independently enabled and observed iOS app registries. */
interface IosAppRegistryRepository {
    /** Empty allowlist and seen registry are valid states; invalid persisted rows fail closed. */
    fun observeAllowlisted(): Flow<List<IosAllowlistedApp>>

    fun observeSeen(): Flow<List<IosSeenApp>>

    suspend fun findAllowlisted(bundleId: String): IosAllowlistedApp?

    suspend fun findSeen(bundleId: String): IosSeenApp?

    /** Returns true only when allowlist membership changed. Excluded app identities can never be enabled. */
    suspend fun setEnabled(bundleId: String, enabled: Boolean): Boolean

    suspend fun recordSeen(bundleId: String, displayName: String, seenAt: Long)

    suspend fun forgetSeen(bundleId: String): Boolean
}

private fun requireBundleId(bundleId: String) {
    require(bundleId.isNotBlank() && bundleId.length <= IosAppRegistryLimits.MAX_BUNDLE_ID_CHARS) {
        "iOS bundle id is invalid"
    }
    require(bundleId.none(Char::isISOControl)) { "iOS bundle id contains a control character" }
}

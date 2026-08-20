package net.extrawdw.apps.notisync.data.iosregistry

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.extrawdw.apps.notisync.data.storage.operational.IosAppAllowlistEntity
import net.extrawdw.apps.notisync.data.storage.operational.IosAppDao
import net.extrawdw.apps.notisync.data.storage.operational.IosSeenAppEntity
import net.extrawdw.apps.notisync.ios.IosBundleIdExclusions

/** Sole Room adapter for [IosAppRegistryRepository]. Allowlist and seen metadata remain separate projections. */
internal class RoomIosAppRegistryRepository(
    private val dao: IosAppDao,
) : IosAppRegistryRepository {
    override fun observeAllowlisted(): Flow<List<IosAllowlistedApp>> =
        dao.observeAllowlist().map { rows -> rows.map { it.toDomain() } }

    override fun observeSeen(): Flow<List<IosSeenApp>> =
        dao.observeSeen().map { rows -> rows.map { it.toDomain() } }

    override suspend fun findAllowlisted(bundleId: String): IosAllowlistedApp? {
        requireBundleIdForRead(bundleId)
        return dao.findAllowlisted(bundleId)?.toDomain()
    }

    override suspend fun findSeen(bundleId: String): IosSeenApp? {
        requireBundleIdForRead(bundleId)
        return dao.findSeen(bundleId)?.toDomain()
    }

    override suspend fun setEnabled(bundleId: String, enabled: Boolean): Boolean {
        requireBundleIdForRead(bundleId)
        if (enabled && IosBundleIdExclusions.isExcluded(bundleId)) return false
        return dao.setEnabled(bundleId, enabled)
    }

    override suspend fun recordSeen(bundleId: String, displayName: String, seenAt: Long) {
        dao.recordSeen(bundleId, displayName, seenAt)
    }

    override suspend fun forgetSeen(bundleId: String): Boolean {
        requireBundleIdForRead(bundleId)
        return dao.forgetSeen(bundleId) == 1
    }
}

private fun IosAppAllowlistEntity.toDomain(): IosAllowlistedApp {
    try {
        require(!IosBundleIdExclusions.isExcluded(bundleId)) {
            "excluded iOS bundle id is present in the allowlist"
        }
        return IosAllowlistedApp(bundleId)
    } catch (error: IllegalArgumentException) {
        throw IllegalStateException("Persisted iOS allowlist row is invalid", error)
    }
}

private fun IosSeenAppEntity.toDomain(): IosSeenApp = try {
    IosSeenApp(
        bundleId = bundleId,
        displayName = displayName,
        lastSeenAt = lastSeenAt,
    )
} catch (error: IllegalArgumentException) {
    throw IllegalStateException("Persisted iOS seen-app row is invalid", error)
}

private fun requireBundleIdForRead(bundleId: String) {
    require(bundleId.isNotBlank() && bundleId.length <= IosAppRegistryLimits.MAX_BUNDLE_ID_CHARS) {
        "iOS bundle id is invalid"
    }
    require(bundleId.none(Char::isISOControl)) { "iOS bundle id contains a control character" }
}

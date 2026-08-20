package net.extrawdw.apps.notisync.data.profile

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import net.extrawdw.apps.notisync.data.storage.operational.LocalProfileEntity
import net.extrawdw.apps.notisync.data.storage.operational.OperationalProfileDao

/**
 * Sole Room adapter for [DeviceProfileRepository]. Room entities and DAOs remain internal to the adapter boundary;
 * callers receive immutable domain values only.
 */
internal class RoomDeviceProfileRepository(
    private val dao: OperationalProfileDao,
) : DeviceProfileRepository {
    override fun observeProfile(): Flow<DeviceProfile?> =
        dao.observeLocalProfile().map { entity -> entity?.toDomain() }

    override suspend fun readProfile(): DeviceProfile? =
        dao.observeLocalProfile().first()?.toDomain()

    override suspend fun replaceProfile(profile: DeviceProfile) {
        dao.replaceLocalProfile(profile.toEntity())
    }
}

private fun LocalProfileEntity.toDomain(): DeviceProfile = try {
    DeviceProfile(
        deviceName = deviceName,
        deviceNameUpdatedAt = deviceNameUpdatedAt,
        profileFingerprint = profileFingerprint,
        profileRevisionAt = profileRevisionAt,
        updatedAt = updatedAt,
    )
} catch (error: IllegalArgumentException) {
    throw IllegalStateException("Persisted local profile is invalid", error)
}

private fun DeviceProfile.toEntity(): LocalProfileEntity = LocalProfileEntity(
    deviceName = deviceName,
    deviceNameUpdatedAt = deviceNameUpdatedAt,
    profileFingerprint = profileFingerprint,
    profileRevisionAt = profileRevisionAt,
    updatedAt = updatedAt,
)

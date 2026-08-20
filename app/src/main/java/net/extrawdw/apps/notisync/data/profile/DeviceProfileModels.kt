package net.extrawdw.apps.notisync.data.profile

import kotlinx.coroutines.flow.Flow

/**
 * Immutable, storage-independent projection of the local device profile.
 *
 * A missing row is a valid pre-import/uninitialized state and is represented as `null` by the repository.
 * Persisted rows that violate these invariants are corruption and are surfaced as an exception rather than
 * replaced with a display default.
 */
data class DeviceProfile(
    val deviceName: String,
    val deviceNameUpdatedAt: Long,
    val profileFingerprint: String?,
    val profileRevisionAt: Long,
    val updatedAt: Long,
) {
    init {
        require(deviceName.isNotBlank()) { "device name must not be blank" }
        require(deviceName.none(Char::isISOControl)) { "device name contains a control character" }
        require(deviceNameUpdatedAt >= 0) { "device-name timestamp must not be negative" }
        require(profileRevisionAt >= 0) { "profile revision timestamp must not be negative" }
        require(updatedAt > 0) { "profile update timestamp must be positive" }
        profileFingerprint?.let {
            require(it.isNotBlank()) { "profile fingerprint must not be blank" }
        }
    }
}

/** Domain-facing owner of the local profile aggregate. */
interface DeviceProfileRepository {
    /** Empty before the profile import/first write; invalid persisted rows fail closed. */
    fun observeProfile(): Flow<DeviceProfile?>

    suspend fun readProfile(): DeviceProfile?

    suspend fun replaceProfile(profile: DeviceProfile)
}

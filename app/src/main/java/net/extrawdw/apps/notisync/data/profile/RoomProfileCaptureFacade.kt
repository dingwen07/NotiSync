package net.extrawdw.apps.notisync.data.profile

import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDatabase

/**
 * Small application-runtime adapter for the two local facts that used to live in Preferences:
 * the advertised device profile and the notification-listener backfill watermark.
 *
 * The database/DAO types stop at this boundary.  Profile flows are hydrated directly from Room, while the
 * watermark remains a best-effort asynchronous write because losing it only causes an extra backfill scan;
 * the server still retains incoming messages until acknowledgement.
 */
internal class RoomProfileCaptureFacade(
    database: OperationalDatabase,
    private val scope: CoroutineScope,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val dao = database.profileDao()
    private val profileRepository = RoomDeviceProfileRepository(dao)
    private val profileWriteMutex = Mutex()

    val profile: StateFlow<DeviceProfile?> = profileRepository.observeProfile()
        .stateIn(scope, SharingStarted.Eagerly, null)

    val deviceName: StateFlow<String> = profile
        .map { it?.deviceName ?: Build.MODEL }
        .stateIn(scope, SharingStarted.Eagerly, Build.MODEL)

    val deviceNameUpdatedAt: StateFlow<Long> = profile
        .map { it?.deviceNameUpdatedAt ?: 0L }
        .stateIn(scope, SharingStarted.Eagerly, 0L)

    val selfProfileFingerprint: StateFlow<String?> = profile
        .map { it?.profileFingerprint }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val selfProfileUpdatedAt: StateFlow<Long> = profile
        .map { it?.profileRevisionAt ?: 0L }
        .stateIn(scope, SharingStarted.Eagerly, 0L)

    suspend fun readProfile(): DeviceProfile? = profileRepository.readProfile()

    /** Persists a rename without touching the profile declaration revision; the profile observer will do that. */
    suspend fun setDeviceName(name: String, now: Long = this.now()) {
        profileWriteMutex.withLock {
            require(now > 0) { "device-name update time must be positive" }
            val current = readProfile()
            val deviceNameUpdatedAt = nextTimestamp(now, current?.deviceNameUpdatedAt ?: 0L)
            val updatedAt = nextTimestamp(now, current?.updatedAt ?: 0L)
            replaceProfile(
                DeviceProfile(
                    deviceName = name,
                    deviceNameUpdatedAt = deviceNameUpdatedAt,
                    profileFingerprint = current?.profileFingerprint,
                    profileRevisionAt = current?.profileRevisionAt ?: 0L,
                    updatedAt = updatedAt,
                ),
            )
        }
    }

    /**
     * Records the complete advertised profile declaration and returns its new LWW revision, or null when the
     * declaration is already current.  This preserves the old API's idempotence while making Room authoritative.
     */
    suspend fun ensureSelfProfileRevision(fingerprint: String, now: Long = this.now()): Long? {
        profileWriteMutex.withLock {
            require(now > 0) { "profile revision time must be positive" }
            val current = readProfile()
            if (current?.profileFingerprint == fingerprint && current.profileRevisionAt > 0L) return null

            val revision = nextTimestamp(
                now,
                maxOf(current?.profileRevisionAt ?: 0L, current?.deviceNameUpdatedAt ?: 0L),
            )
            val updatedAt = nextTimestamp(now, current?.updatedAt ?: 0L)
            replaceProfile(
                DeviceProfile(
                    deviceName = current?.deviceName ?: Build.MODEL,
                    deviceNameUpdatedAt = current?.deviceNameUpdatedAt ?: 0L,
                    profileFingerprint = fingerprint,
                    profileRevisionAt = revision,
                    updatedAt = updatedAt,
                ),
            )
            return revision
        }
    }

    /** Room remains the durable high-water mark, but the write is intentionally fire-and-forget. */
    fun updateLastSeenPostTime(timeMillis: Long) {
        scope.launch { recordLastSeenPostTime(timeMillis) }
    }

    /** Suspending form used by deterministic storage tests and controlled initialization paths. */
    suspend fun recordLastSeenPostTime(timeMillis: Long) {
        require(timeMillis >= 0) { "notification last-seen time must not be negative" }
        dao.advanceNotificationCaptureLastSeenPostTime(
            timeMillis = timeMillis,
            updatedAt = nextTimestamp(now(), 0L),
        )
    }

    suspend fun lastSeenPostTime(): Long =
        dao.observeNotificationCaptureState().first()?.lastSeenPostTime ?: 0L

    private suspend fun replaceProfile(profile: DeviceProfile) {
        profileRepository.replaceProfile(profile)
    }

    private fun nextTimestamp(candidate: Long, previous: Long): Long =
        maxOf(candidate, previous + 1L).coerceAtLeast(1L)
}

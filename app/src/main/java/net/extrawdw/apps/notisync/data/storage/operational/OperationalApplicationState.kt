package net.extrawdw.apps.notisync.data.storage.operational

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Narrow runtime facade over the application-owned Operational v1 tables. It deliberately leaves
 * the known-good repositories in charge of codecs, validation, and in-memory projections while
 * serializing the few full-snapshot writes that previously relied on DataStore's edit mutex.
 */
internal class OperationalApplicationState(context: Context) {
    private val dao = OperationalDatabaseFactory.get(context).applicationState()
    private val androidSelectionMutex = Mutex()
    private val iosSelectionMutex = Mutex()
    private val screenMutex = Mutex()
    private val codecMutex = Mutex()

    private val initialScreenState = runBlocking { dao.screenMirrorState() ?: defaultScreenState() }
    private val _screenMirroringEnabled = MutableStateFlow(initialScreenState.enabled)
    val screenMirroringEnabled: StateFlow<Boolean> = _screenMirroringEnabled.asStateFlow()

    suspend fun lastSeenPostTime(): Long =
        dao.notificationCaptureState()?.lastSeenPostTime ?: 0L

    suspend fun advanceLastSeenPostTime(timeMillis: Long) =
        dao.advanceLastSeenPostTime(timeMillis)

    suspend fun androidApps(): List<AndroidAppEntity> = dao.androidApps()

    suspend fun replaceAndroidEnabledPackages(packageNames: Set<String>) =
        androidSelectionMutex.withLock { dao.replaceAndroidEnabledPackages(packageNames) }

    suspend fun setAndroidAppConfig(packageName: String, json: String) =
        dao.setAndroidAppConfig(packageName, json)

    suspend fun setAndroidSeenChannels(packageName: String, json: String) =
        dao.setAndroidSeenChannels(packageName, json)

    suspend fun incomingNotificationFilters(): List<IncomingNotificationFilterEntity> =
        dao.incomingNotificationFilters()

    suspend fun upsertIncomingNotificationFilter(entity: IncomingNotificationFilterEntity) =
        dao.upsertIncomingNotificationFilter(
            entity.requesterClientId,
            entity.filterJson,
            entity.updatedAt,
        )

    suspend fun deleteIncomingNotificationFilter(requesterClientId: String) =
        dao.deleteIncomingNotificationFilter(requesterClientId)

    suspend fun iosApps(): List<IosAppEntity> = dao.iosApps()

    suspend fun replaceEnabledIosApps(bundleIds: Set<String>) =
        iosSelectionMutex.withLock { dao.replaceEnabledIosApps(bundleIds) }

    suspend fun recordIosApp(bundleId: String, displayName: String, lastSeenAt: Long) =
        dao.recordIosApp(bundleId, displayName, lastSeenAt)

    suspend fun forgetIosApp(bundleId: String) = dao.forgetIosApp(bundleId)

    suspend fun screenMirrorState(): ScreenMirrorStateEntity = screenMutex.withLock {
        dao.screenMirrorState() ?: defaultScreenState()
    }

    suspend fun replaceScreenMirrorState(entity: ScreenMirrorStateEntity) = screenMutex.withLock {
        dao.replaceScreenMirrorState(entity)
        _screenMirroringEnabled.value = entity.enabled
    }

    suspend fun updateScreenMirrorState(
        transform: (ScreenMirrorStateEntity) -> ScreenMirrorStateEntity,
    ): ScreenMirrorStateEntity = screenMutex.withLock {
        val next = transform(dao.screenMirrorState() ?: defaultScreenState())
        dao.replaceScreenMirrorState(next)
        _screenMirroringEnabled.value = next.enabled
        next
    }

    suspend fun setScreenMirroringEnabled(enabled: Boolean) {
        updateScreenMirrorState { it.copy(enabled = enabled) }
    }

    suspend fun screenCodecPreferences(): List<ScreenCodecPreferenceEntity> =
        dao.screenCodecPreferences()

    suspend fun replaceScreenCodecPreferences(entities: List<ScreenCodecPreferenceEntity>) =
        codecMutex.withLock { dao.replaceScreenCodecPreferences(entities) }

    suspend fun openPgpEnrollment(): OpenPgpEnrollmentEntity? = dao.openPgpEnrollment()

    suspend fun replaceOpenPgpEnrollment(entity: OpenPgpEnrollmentEntity) =
        dao.replaceOpenPgpEnrollment(entity)

    private fun defaultScreenState() = ScreenMirrorStateEntity(
        enabled = false,
        authorizedPeerIdsJson = "[]",
        requestReplayJson = null,
        replayBlocked = false,
        replayQuarantineDigest = null,
        replayQuarantinedAt = null,
    )
}

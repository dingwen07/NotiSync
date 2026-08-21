package net.extrawdw.apps.notisync.data.storage.operational

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface OperationalApplicationState {
    val screenMirroringEnabled: StateFlow<Boolean>

    suspend fun lastSeenPostTime(): Long
    suspend fun advanceLastSeenPostTime(timeMillis: Long)
    suspend fun androidApps(): List<AndroidAppEntity>
    suspend fun replaceAndroidEnabledPackages(packageNames: Set<String>)
    suspend fun setAndroidAppConfig(packageName: String, json: String)
    suspend fun setAndroidSeenChannels(packageName: String, json: String)
    suspend fun incomingNotificationFilters(): List<IncomingNotificationFilterEntity>
    suspend fun upsertIncomingNotificationFilter(entity: IncomingNotificationFilterEntity)
    suspend fun deleteIncomingNotificationFilter(requesterClientId: String)
    suspend fun iosApps(): List<IosAppEntity>
    suspend fun replaceEnabledIosApps(bundleIds: Set<String>)
    suspend fun recordIosApp(bundleId: String, displayName: String, lastSeenAt: Long)
    suspend fun forgetIosApp(bundleId: String)
    suspend fun screenMirrorState(): ScreenMirrorStateEntity
    suspend fun replaceScreenMirrorState(entity: ScreenMirrorStateEntity)
    suspend fun updateScreenMirrorState(
        transform: (ScreenMirrorStateEntity) -> ScreenMirrorStateEntity,
    ): ScreenMirrorStateEntity
    suspend fun setScreenMirroringEnabled(enabled: Boolean)
    suspend fun screenCodecPreferences(): List<ScreenCodecPreferenceEntity>
    suspend fun replaceScreenCodecPreferences(entities: List<ScreenCodecPreferenceEntity>)
    suspend fun openPgpEnrollment(): OpenPgpEnrollmentEntity?
    suspend fun replaceOpenPgpEnrollment(entity: OpenPgpEnrollmentEntity)
}

/**
 * Runtime facade over the application-owned Operational tables. Room is the only backing store;
 * repositories retain their proven codecs and in-memory projections while this facade serializes the
 * few full-snapshot writes that previously relied on DataStore's edit mutex.
 */
internal class RoomOperationalApplicationState(context: Context) : OperationalApplicationState {
    private val dao = OperationalDatabaseFactory.get(context).applicationState()
    private val androidSelectionMutex = Mutex()
    private val iosSelectionMutex = Mutex()
    private val screenMutex = Mutex()
    private val codecMutex = Mutex()

    private val initialScreenState = runBlocking { dao.screenMirrorState() ?: defaultScreenState() }
    private val _screenMirroringEnabled = MutableStateFlow(initialScreenState.enabled)
    override val screenMirroringEnabled: StateFlow<Boolean> = _screenMirroringEnabled.asStateFlow()

    override suspend fun lastSeenPostTime(): Long =
        dao.notificationCaptureState()?.lastSeenPostTime ?: 0L

    override suspend fun advanceLastSeenPostTime(timeMillis: Long) =
        dao.advanceLastSeenPostTime(timeMillis)

    override suspend fun androidApps(): List<AndroidAppEntity> = dao.androidApps()

    override suspend fun replaceAndroidEnabledPackages(packageNames: Set<String>) =
        androidSelectionMutex.withLock { dao.replaceAndroidEnabledPackages(packageNames) }

    override suspend fun setAndroidAppConfig(packageName: String, json: String) =
        dao.setAndroidAppConfig(packageName, json)

    override suspend fun setAndroidSeenChannels(packageName: String, json: String) =
        dao.setAndroidSeenChannels(packageName, json)

    override suspend fun incomingNotificationFilters(): List<IncomingNotificationFilterEntity> =
        dao.incomingNotificationFilters()

    override suspend fun upsertIncomingNotificationFilter(entity: IncomingNotificationFilterEntity) =
        dao.upsertIncomingNotificationFilter(
            entity.requesterClientId,
            entity.filterJson,
            entity.updatedAt,
        )

    override suspend fun deleteIncomingNotificationFilter(requesterClientId: String) =
        dao.deleteIncomingNotificationFilter(requesterClientId)

    override suspend fun iosApps(): List<IosAppEntity> = dao.iosApps()

    override suspend fun replaceEnabledIosApps(bundleIds: Set<String>) =
        iosSelectionMutex.withLock { dao.replaceEnabledIosApps(bundleIds) }

    override suspend fun recordIosApp(bundleId: String, displayName: String, lastSeenAt: Long) =
        dao.recordIosApp(bundleId, displayName, lastSeenAt)

    override suspend fun forgetIosApp(bundleId: String) = dao.forgetIosApp(bundleId)

    override suspend fun screenMirrorState(): ScreenMirrorStateEntity = screenMutex.withLock {
        dao.screenMirrorState() ?: defaultScreenState()
    }

    override suspend fun replaceScreenMirrorState(entity: ScreenMirrorStateEntity) = screenMutex.withLock {
        dao.replaceScreenMirrorState(entity)
        _screenMirroringEnabled.value = entity.enabled
    }

    override suspend fun updateScreenMirrorState(
        transform: (ScreenMirrorStateEntity) -> ScreenMirrorStateEntity,
    ): ScreenMirrorStateEntity = screenMutex.withLock {
        val next = transform(dao.screenMirrorState() ?: defaultScreenState())
        dao.replaceScreenMirrorState(next)
        _screenMirroringEnabled.value = next.enabled
        next
    }

    override suspend fun setScreenMirroringEnabled(enabled: Boolean) {
        updateScreenMirrorState { it.copy(enabled = enabled) }
    }

    override suspend fun screenCodecPreferences(): List<ScreenCodecPreferenceEntity> =
        dao.screenCodecPreferences()

    override suspend fun replaceScreenCodecPreferences(entities: List<ScreenCodecPreferenceEntity>) =
        codecMutex.withLock { dao.replaceScreenCodecPreferences(entities) }

    override suspend fun openPgpEnrollment(): OpenPgpEnrollmentEntity? = dao.openPgpEnrollment()

    override suspend fun replaceOpenPgpEnrollment(entity: OpenPgpEnrollmentEntity) =
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

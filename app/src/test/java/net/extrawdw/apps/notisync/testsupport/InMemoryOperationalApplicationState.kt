package net.extrawdw.apps.notisync.testsupport

import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.extrawdw.apps.notisync.data.storage.operational.AndroidAppEntity
import net.extrawdw.apps.notisync.data.storage.operational.IncomingNotificationFilterEntity
import net.extrawdw.apps.notisync.data.storage.operational.IosAppEntity
import net.extrawdw.apps.notisync.data.storage.operational.OpenPgpEnrollmentEntity
import net.extrawdw.apps.notisync.data.storage.operational.OperationalApplicationState
import net.extrawdw.apps.notisync.data.storage.operational.ScreenCodecPreferenceEntity
import net.extrawdw.apps.notisync.data.storage.operational.ScreenMirrorStateEntity

internal class InMemoryOperationalApplicationState(
    initialLastSeenPostTime: Long = 0L,
    initialAndroidApps: List<AndroidAppEntity> = emptyList(),
    initialFilters: List<IncomingNotificationFilterEntity> = emptyList(),
    initialIosApps: List<IosAppEntity> = emptyList(),
    initialScreenState: ScreenMirrorStateEntity = defaultScreenState(),
    initialCodecs: List<ScreenCodecPreferenceEntity> = emptyList(),
    initialOpenPgpEnrollment: OpenPgpEnrollmentEntity? = null,
) : OperationalApplicationState {
    @Volatile var failWrites: Boolean = false
    private var lastSeenPostTime = initialLastSeenPostTime
    private val androidApps = initialAndroidApps.associateByTo(linkedMapOf(), AndroidAppEntity::packageName)
    private val filters = initialFilters.associateByTo(
        linkedMapOf(),
        IncomingNotificationFilterEntity::requesterClientId,
    )
    private val iosApps = initialIosApps.associateByTo(linkedMapOf(), IosAppEntity::bundleId)
    private var screenState = initialScreenState
    private val _screenMirroringEnabled = MutableStateFlow(screenState.enabled)
    override val screenMirroringEnabled: StateFlow<Boolean> = _screenMirroringEnabled
    private var codecs = initialCodecs
    private var openPgpEnrollment = initialOpenPgpEnrollment

    override suspend fun lastSeenPostTime(): Long = lastSeenPostTime

    override suspend fun advanceLastSeenPostTime(timeMillis: Long) {
        checkWrite()
        lastSeenPostTime = maxOf(lastSeenPostTime, timeMillis)
    }

    override suspend fun androidApps(): List<AndroidAppEntity> = androidApps.values.toList()

    override suspend fun replaceAndroidEnabledPackages(packageNames: Set<String>) {
        checkWrite()
        androidApps.replaceAll { packageName, row -> row.copy(enabled = packageName in packageNames) }
        packageNames.forEach { packageName ->
            androidApps.putIfAbsent(packageName, AndroidAppEntity(packageName, true, null, null))
        }
        androidApps.entries.removeIf { (_, row) ->
            !row.enabled && row.configJson == null && row.seenChannelsJson == null
        }
    }

    override suspend fun setAndroidAppConfig(packageName: String, json: String) {
        checkWrite()
        val current = androidApps[packageName]
        androidApps[packageName] = current?.copy(configJson = json)
            ?: AndroidAppEntity(packageName, false, json, null)
    }

    override suspend fun setAndroidSeenChannels(packageName: String, json: String) {
        checkWrite()
        val current = androidApps[packageName]
        androidApps[packageName] = current?.copy(seenChannelsJson = json)
            ?: AndroidAppEntity(packageName, false, null, json)
    }

    override suspend fun incomingNotificationFilters(): List<IncomingNotificationFilterEntity> =
        filters.values.toList()

    override suspend fun upsertIncomingNotificationFilter(entity: IncomingNotificationFilterEntity) {
        checkWrite()
        val current = filters[entity.requesterClientId]
        if (current == null || entity.updatedAt >= current.updatedAt) filters[entity.requesterClientId] = entity
    }

    override suspend fun deleteIncomingNotificationFilter(requesterClientId: String) {
        checkWrite()
        filters.remove(requesterClientId)
    }

    override suspend fun iosApps(): List<IosAppEntity> = iosApps.values.toList()

    override suspend fun replaceEnabledIosApps(bundleIds: Set<String>) {
        checkWrite()
        iosApps.replaceAll { bundleId, row -> row.copy(enabled = bundleId in bundleIds) }
        bundleIds.forEach { bundleId ->
            iosApps.putIfAbsent(bundleId, IosAppEntity(bundleId, true, null, null))
        }
        iosApps.entries.removeIf { (_, row) ->
            !row.enabled && row.displayName == null && row.lastSeenAt == null
        }
    }

    override suspend fun recordIosApp(bundleId: String, displayName: String, lastSeenAt: Long) {
        checkWrite()
        val current = iosApps[bundleId]
        iosApps[bundleId] = current?.copy(displayName = displayName, lastSeenAt = lastSeenAt)
            ?: IosAppEntity(bundleId, false, displayName, lastSeenAt)
    }

    override suspend fun forgetIosApp(bundleId: String) {
        checkWrite()
        val current = iosApps[bundleId] ?: return
        if (current.enabled) {
            iosApps[bundleId] = current.copy(displayName = null, lastSeenAt = null)
        } else {
            iosApps.remove(bundleId)
        }
    }

    override suspend fun screenMirrorState(): ScreenMirrorStateEntity = screenState

    override suspend fun replaceScreenMirrorState(entity: ScreenMirrorStateEntity) {
        checkWrite()
        screenState = entity
        _screenMirroringEnabled.value = entity.enabled
    }

    override suspend fun updateScreenMirrorState(
        transform: (ScreenMirrorStateEntity) -> ScreenMirrorStateEntity,
    ): ScreenMirrorStateEntity = transform(screenState).also { replaceScreenMirrorState(it) }

    override suspend fun setScreenMirroringEnabled(enabled: Boolean) {
        replaceScreenMirrorState(screenState.copy(enabled = enabled))
    }

    override suspend fun screenCodecPreferences(): List<ScreenCodecPreferenceEntity> = codecs

    override suspend fun replaceScreenCodecPreferences(entities: List<ScreenCodecPreferenceEntity>) {
        checkWrite()
        codecs = entities
    }

    override suspend fun openPgpEnrollment(): OpenPgpEnrollmentEntity? = openPgpEnrollment

    override suspend fun replaceOpenPgpEnrollment(entity: OpenPgpEnrollmentEntity) {
        checkWrite()
        openPgpEnrollment = entity
    }

    private fun checkWrite() {
        if (failWrites) throw IOException("synthetic Operational Room failure")
    }

    private companion object {
        fun defaultScreenState() = ScreenMirrorStateEntity(
            enabled = false,
            authorizedPeerIdsJson = "[]",
            requestReplayJson = null,
            replayBlocked = false,
            replayQuarantineDigest = null,
            replayQuarantinedAt = null,
        )
    }
}

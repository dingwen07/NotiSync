package net.extrawdw.apps.notisync.hotspot.controller

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDatabaseFactory
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.HotspotSnapshot

/** Persistence adapter; encryption and key custody belong entirely to OperationalDatabase. */
internal class HotspotCredentialRepository(context: Context, scope: CoroutineScope) {
    private val dao = OperationalDatabaseFactory.get(context).hotspotCredentials()
    val saved = dao.observe().map { entries -> entries.associate {
        ClientId(it.hotspotDeviceId) to SavedHotspot(it.ssid, it.psk, it.securityType, it.hiddenSsid, it.updatedAt)
    } }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    suspend fun save(deviceId: ClientId, snapshot: HotspotSnapshot, issuedAt: Long) {
        val details = SavedHotspot.from(snapshot, issuedAt) ?: return
        dao.save(deviceId.value, details.ssid, details.psk, details.securityType, details.hiddenSsid, details.updatedAt)
    }

    suspend fun forget(deviceId: ClientId) = dao.delete(deviceId.value)
}

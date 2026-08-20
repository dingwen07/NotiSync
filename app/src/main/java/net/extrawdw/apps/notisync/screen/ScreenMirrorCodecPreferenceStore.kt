package net.extrawdw.apps.notisync.screen

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.extrawdw.apps.notisync.data.RosterDevice
import net.extrawdw.apps.notisync.data.storage.operational.ScreenCodecPreferenceEntity
import net.extrawdw.apps.notisync.data.storage.operational.ScreenCodecToken
import net.extrawdw.apps.notisync.data.storage.operational.ScreenDao
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ScreenMirrorCodec
import net.extrawdw.notisync.protocol.TrustStatus

/** Local, per-source codec preferences backed by the Operational Room database. */
internal class ScreenMirrorCodecPreferenceStore internal constructor(
    private val dao: ScreenDao,
    scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val _preferredCodecs = MutableStateFlow(load())
    val preferredCodecs: StateFlow<Map<String, ScreenMirrorCodec>> = _preferredCodecs.asStateFlow()

    init {
        scope.launch {
            dao.observeCodecPreferences().collect { rows ->
                _preferredCodecs.value = rows.associate { it.peerId to it.codec.toProtocol() }
            }
        }
    }

    fun preferredCodec(peerId: ClientId): ScreenMirrorCodec? = preferredCodecs.value[peerId.value]

    /** A null value selects Auto and removes the durable override. */
    suspend fun setPreferredCodec(peerId: ClientId, codec: ScreenMirrorCodec?) = mutex.withLock {
        requireValidPeerId(peerId.value)
        if (codec == null) {
            dao.removeCodecPreference(peerId.value)
        } else {
            dao.putCodecPreference(
                ScreenCodecPreferenceEntity(
                    peerId = peerId.value,
                    codec = codec.toStorage(),
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
        _preferredCodecs.value = loadFromRoom()
    }

    /** Forget preferences when a peer is removed, revoked, reclassified, or no longer verified. */
    suspend fun retainTrustedOwnPeers(roster: Collection<RosterDevice>) = mutex.withLock {
        val allowed = roster.asSequence()
            .filter { it.ownDevice && it.status == TrustStatus.TRUSTED && it.verified }
            .map { it.clientId.value }
            .toSet()
        _preferredCodecs.value.keys
            .filterNot(allowed::contains)
            .forEach { dao.removeCodecPreference(it) }
        _preferredCodecs.value = loadFromRoom()
    }

    private fun load(): Map<String, ScreenMirrorCodec> = runBlocking { loadFromRoom() }

    private suspend fun loadFromRoom(): Map<String, ScreenMirrorCodec> =
        dao.observeCodecPreferences().first().associate { it.peerId to it.codec.toProtocol() }
}

private fun ScreenCodecToken.toProtocol(): ScreenMirrorCodec = when (this) {
    ScreenCodecToken.H264 -> ScreenMirrorCodec.H264
    ScreenCodecToken.H265 -> ScreenMirrorCodec.H265
    ScreenCodecToken.AV1 -> ScreenMirrorCodec.AV1
}

private fun ScreenMirrorCodec.toStorage(): ScreenCodecToken = when (this) {
    ScreenMirrorCodec.H264 -> ScreenCodecToken.H264
    ScreenMirrorCodec.H265 -> ScreenCodecToken.H265
    ScreenMirrorCodec.AV1 -> ScreenCodecToken.AV1
}

private fun requireValidPeerId(value: String) {
    require(value.isNotBlank() && value.length <= 128 && value.none(Char::isISOControl)) {
        "screen codec peer id is invalid"
    }
}

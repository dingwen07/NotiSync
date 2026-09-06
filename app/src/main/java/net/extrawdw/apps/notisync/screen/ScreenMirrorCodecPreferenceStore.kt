package net.extrawdw.apps.notisync.screen

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.extrawdw.apps.notisync.data.RosterDevice
import net.extrawdw.apps.notisync.data.storage.operational.OperationalApplicationState
import net.extrawdw.apps.notisync.data.storage.operational.ScreenCodecPreferenceEntity
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ScreenMirrorCodec
import net.extrawdw.notisync.protocol.TrustStatus

/** Local, per-source codec preferences. Absence means automatic selection. */
internal class ScreenMirrorCodecPreferenceStore(
    private val operationalState: OperationalApplicationState,
) {
    private val mutex = Mutex()
    private val _preferredCodecs = MutableStateFlow(load())
    val preferredCodecs: StateFlow<Map<String, ScreenMirrorCodec>> = _preferredCodecs.asStateFlow()

    fun preferredCodec(peerId: ClientId): ScreenMirrorCodec? = preferredCodecs.value[peerId.value]

    /** A null value selects Auto and removes the durable override. */
    suspend fun setPreferredCodec(peerId: ClientId, codec: ScreenMirrorCodec?) = mutex.withLock {
        if (codec != null && _preferredCodecs.value[peerId.value] == codec) return@withLock
        if (codec == null) {
            operationalState.deleteScreenCodecPreference(peerId.value)
        } else {
            operationalState.setScreenCodecPreference(ScreenCodecPreferenceEntity(peerId.value, codec.name.lowercase()))
        }
        _preferredCodecs.value = if (codec == null) {
            _preferredCodecs.value - peerId.value
        } else {
            _preferredCodecs.value + (peerId.value to codec)
        }
    }

    /** Forget preferences when a peer is removed, revoked, reclassified, or no longer verified. */
    suspend fun retainTrustedOwnPeers(roster: Collection<RosterDevice>) = mutex.withLock {
        val allowed = roster.asSequence()
            .filter { it.ownDevice && it.status == TrustStatus.TRUSTED && it.verified }
            .map { it.clientId.value }
            .toSet()
        // Check durable rows too, so unknown codecs and invalid peer ids are pruned when their peer leaves.
        val rows = operationalState.screenCodecPreferences()
        if (rows.any { it.peerId !in allowed }) operationalState.retainScreenCodecPreferences(allowed)
        _preferredCodecs.value = rows.filter { it.peerId in allowed }
            .mapNotNull { decodeEntry(it.peerId, it.codec) }.toMap()
    }

    private fun load(): Map<String, ScreenMirrorCodec> = runCatching {
        runBlocking { loadRoom() }
    }.getOrDefault(emptyMap())

    private suspend fun loadRoom(): Map<String, ScreenMirrorCodec> =
        operationalState.screenCodecPreferences()
            .mapNotNull { row -> decodeEntry(row.peerId, row.codec) }
            .toMap()

    private fun decodeEntry(peerId: String, codecName: String): Pair<String, ScreenMirrorCodec>? {
        if (peerId.isBlank() || peerId.length > MAX_PEER_ID_LENGTH || peerId.any(Char::isISOControl)) {
            return null
        }
        val codec = ScreenMirrorCodec.entries.firstOrNull {
            it.name.equals(codecName, ignoreCase = true)
        } ?: return null
        return peerId to codec
    }

    private companion object {
        const val MAX_PEER_ID_LENGTH = 128
    }
}

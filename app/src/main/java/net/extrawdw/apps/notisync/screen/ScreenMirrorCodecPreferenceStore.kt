package net.extrawdw.apps.notisync.screen

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.extrawdw.apps.notisync.data.RosterDevice
import net.extrawdw.apps.notisync.data.storage.operational.OperationalApplicationState
import net.extrawdw.apps.notisync.data.storage.operational.ScreenCodecPreferenceEntity
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.ScreenMirrorCodec
import net.extrawdw.notisync.protocol.TrustStatus

/** Local, per-source codec preferences. Absence means automatic selection. */
internal class ScreenMirrorCodecPreferenceStore(
    private val store: DataStore<Preferences>,
    private val operationalState: OperationalApplicationState? = null,
) {
    private val key = stringPreferencesKey("screen_mirror_codec_preferences_v1")
    private val mutex = Mutex()
    private val _preferredCodecs = MutableStateFlow(load())
    val preferredCodecs: StateFlow<Map<String, ScreenMirrorCodec>> = _preferredCodecs.asStateFlow()

    fun preferredCodec(peerId: ClientId): ScreenMirrorCodec? = preferredCodecs.value[peerId.value]

    /** A null value selects Auto and removes the durable override. */
    suspend fun setPreferredCodec(peerId: ClientId, codec: ScreenMirrorCodec?) = mutex.withLock {
        update { current ->
            if (codec == null) current - peerId.value else current + (peerId.value to codec)
        }
    }

    /** Forget preferences when a peer is removed, revoked, reclassified, or no longer verified. */
    suspend fun retainTrustedOwnPeers(roster: Collection<RosterDevice>) = mutex.withLock {
        val allowed = roster.asSequence()
            .filter { it.ownDevice && it.status == TrustStatus.TRUSTED && it.verified }
            .map { it.clientId.value }
            .toSet()
        update { current -> current.filterKeys(allowed::contains) }
    }

    private suspend fun update(
        transform: (Map<String, ScreenMirrorCodec>) -> Map<String, ScreenMirrorCodec>,
    ) {
        var next = emptyMap<String, ScreenMirrorCodec>()
        if (operationalState != null) {
            val current = loadRoom()
            next = transform(current)
                .entries
                .sortedBy { it.key }
                .associate { it.toPair() }
            operationalState.replaceScreenCodecPreferences(
                next.map { (peerId, codec) ->
                    ScreenCodecPreferenceEntity(peerId, codec.name.lowercase())
                },
            )
        } else {
            store.edit { preferences ->
                val current = decode(preferences[key])
                next = transform(current)
                    .entries
                    .sortedBy { it.key }
                    .associate { it.toPair() }
                if (next.isEmpty()) {
                    preferences.remove(key)
                } else {
                    preferences[key] = encode(next)
                }
            }
        }
        _preferredCodecs.value = next
    }

    private fun load(): Map<String, ScreenMirrorCodec> = runCatching {
        runBlocking {
            operationalState?.let { loadRoom() } ?: decode(store.data.first()[key])
        }
    }.getOrDefault(emptyMap())

    private suspend fun loadRoom(): Map<String, ScreenMirrorCodec> =
        operationalState?.screenCodecPreferences().orEmpty()
            .mapNotNull { row -> decodeEntry(row.peerId, row.codec) }
            .toMap()

    private fun encode(value: Map<String, ScreenMirrorCodec>): String = ProtocolCodec.encodeToJson(
        value.mapValues { (_, codec) -> codec.name.lowercase() },
    )

    private fun decode(encoded: String?): Map<String, ScreenMirrorCodec> {
        if (encoded == null) return emptyMap()
        val raw = runCatching {
            ProtocolCodec.decodeFromJson<Map<String, String>>(encoded)
        }.getOrDefault(emptyMap())
        if (raw.size > MAX_PREFERENCES) return emptyMap()
        return raw.mapNotNull { (peerId, codecName) -> decodeEntry(peerId, codecName) }.toMap()
    }

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
        const val MAX_PREFERENCES = 256
        const val MAX_PEER_ID_LENGTH = 128
    }
}

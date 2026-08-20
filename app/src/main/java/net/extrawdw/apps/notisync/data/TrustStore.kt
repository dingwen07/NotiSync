package net.extrawdw.apps.notisync.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.notisync.data.storage.core.CoreFoundationRepository
import net.extrawdw.apps.notisync.data.storage.core.TrustSnapshot
import net.extrawdw.apps.notisync.data.storage.core.TrustSnapshotInput
import net.extrawdw.apps.notisync.data.storage.core.TrustSnapshotWriteResult
import net.extrawdw.notisync.peer.ports.TrustPersistence
import net.extrawdw.notisync.protocol.crypto.IdentitySigner

/** Android persistence adapters for the platform-neutral, signed peer trust store. */
class TrustStore private constructor(
    persistence: TrustPersistence,
    identity: IdentitySigner,
    clock: () -> Long = System::currentTimeMillis,
) : net.extrawdw.notisync.peer.trust.TrustStore(
    persistence,
    identity,
    clock,
) {
    /** Legacy-only constructor retained for the isolated v51 source path until AppGraph is removed. */
    constructor(
        store: DataStore<Preferences>,
        identity: IdentitySigner,
        clock: () -> Long = System::currentTimeMillis,
    ) : this(DataStoreTrustPersistence(store), identity, clock)

    /** Production Room authority. Every signed aggregate replacement is one Core transaction. */
    internal constructor(
        repository: CoreFoundationRepository,
        identity: IdentitySigner,
        clock: () -> Long = System::currentTimeMillis,
    ) : this(RoomTrustPersistence(repository), identity, clock)

    companion object {
        const val REVOKE_PURGE_DELAY_MS =
            net.extrawdw.notisync.peer.trust.TrustStore.REVOKE_PURGE_DELAY_MS
    }
}

private class RoomTrustPersistence(
    private val repository: CoreFoundationRepository,
) : TrustPersistence {
    private val lock = Any()
    private val values = mutableMapOf<String, String?>()
    private var expectedDigest: ByteArray? = null

    init {
        runBlocking { repository.loadValidatedTrustSnapshot() }?.let(::replaceCache)
    }

    override fun read(key: String): String? = synchronized(lock) { values[key] }

    override fun write(values: Map<String, String?>) = synchronized(lock) {
        val next = this.values.toMutableMap().apply { putAll(values) }
        val entries = requireNotNull(next[net.extrawdw.notisync.peer.trust.TrustStore.ENTRIES_KEY])
        val cards = requireNotNull(next[net.extrawdw.notisync.peer.trust.TrustStore.CARDS_KEY])
        val overlays = requireNotNull(next[net.extrawdw.notisync.peer.trust.TrustStore.OVERLAYS_KEY])
        val signature = requireNotNull(next[net.extrawdw.notisync.peer.trust.TrustStore.SIGNATURE_KEY]) {
            "Room trust authority does not support persisting an intentionally invalid signature"
        }
        val epochs = next[net.extrawdw.notisync.peer.trust.TrustStore.EPOCHS_KEY]
        val candidate = if (epochs == null) {
            TrustSnapshotInput.ThreeSection(
                entries.encodeToByteArray(),
                cards.encodeToByteArray(),
                overlays.encodeToByteArray(),
                signature.encodeToByteArray(),
            )
        } else {
            TrustSnapshotInput.FourSection(
                entries.encodeToByteArray(),
                cards.encodeToByteArray(),
                overlays.encodeToByteArray(),
                epochs.encodeToByteArray(),
                signature.encodeToByteArray(),
            )
        }
        when (runBlocking { repository.replaceTrustSnapshot(candidate, expectedDigest) }) {
            TrustSnapshotWriteResult.APPLIED,
            TrustSnapshotWriteResult.ALREADY_CURRENT,
            -> Unit
            TrustSnapshotWriteResult.CONFLICT -> error("Core trust authority changed concurrently")
            TrustSnapshotWriteResult.MISSING_IDENTITY -> error("Core identity authority is missing")
        }
        replaceCache(checkNotNull(runBlocking { repository.loadValidatedTrustSnapshot() }))
    }

    private fun replaceCache(snapshot: TrustSnapshot) {
        values.clear()
        values[net.extrawdw.notisync.peer.trust.TrustStore.ENTRIES_KEY] = snapshot.entriesUtf8.decodeToString()
        values[net.extrawdw.notisync.peer.trust.TrustStore.CARDS_KEY] = snapshot.cardsUtf8.decodeToString()
        values[net.extrawdw.notisync.peer.trust.TrustStore.OVERLAYS_KEY] = snapshot.overlaysUtf8.decodeToString()
        values[net.extrawdw.notisync.peer.trust.TrustStore.EPOCHS_KEY] = snapshot.epochsUtf8?.decodeToString()
        values[net.extrawdw.notisync.peer.trust.TrustStore.SIGNATURE_KEY] =
            snapshot.signatureBase64UrlUtf8.decodeToString()
        expectedDigest = snapshot.snapshotDigest
    }
}

private class DataStoreTrustPersistence(
    private val store: DataStore<Preferences>,
) : TrustPersistence {
    private val lock = Any()
    private val values = runBlocking {
        val preferences = store.data.first()
        TRUST_KEYS.associateWith { preferences[stringPreferencesKey(it)] }.toMutableMap()
    }

    override fun read(key: String): String? = synchronized(lock) { values[key] }

    override fun write(values: Map<String, String?>) {
        synchronized(lock) {
            // TrustPersistence is a durable-before-return contract. Serialize and await DataStore's atomic
            // edit so concurrent WS/FCM deliveries cannot reorder signed snapshots on disk after an ACK.
            runBlocking {
                store.edit { preferences ->
                    values.forEach { (key, value) ->
                        val preferenceKey = stringPreferencesKey(key)
                        if (value == null) preferences.remove(preferenceKey)
                        else preferences[preferenceKey] = value
                    }
                }
            }
            this.values.putAll(values)
        }
    }

    private companion object {
        val TRUST_KEYS = listOf(
            net.extrawdw.notisync.peer.trust.TrustStore.ENTRIES_KEY,
            net.extrawdw.notisync.peer.trust.TrustStore.CARDS_KEY,
            net.extrawdw.notisync.peer.trust.TrustStore.OVERLAYS_KEY,
            net.extrawdw.notisync.peer.trust.TrustStore.EPOCHS_KEY,
            net.extrawdw.notisync.peer.trust.TrustStore.SIGNATURE_KEY,
        )
    }
}

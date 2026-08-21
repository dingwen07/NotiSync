package net.extrawdw.apps.notisync.data.storage.migration

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import net.extrawdw.apps.notisync.data.PerAppConfig
import net.extrawdw.apps.notisync.data.SeenChannel
import net.extrawdw.apps.notisync.data.storage.core.CoreDatabase
import net.extrawdw.apps.notisync.data.storage.core.CoreDatabaseFactory
import net.extrawdw.apps.notisync.data.storage.operational.LegacyDatabaseNames
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDatabase
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDatabaseFactory
import net.extrawdw.apps.notisync.ios.IosApp
import net.extrawdw.notisync.protocol.FilterSync
import net.extrawdw.notisync.protocol.ProtocolCodec

internal val Context.notiSyncDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "notisync")

internal data class PreparedStorage(
    val usesRoom: Boolean,
    val migrationFailure: Throwable? = null,
)

/**
 * The only custom storage cutover. An absent flag in the existing Preferences DataStore rebuilds
 * both v1 targets from retained known-good SQLite sources and application aggregates in DataStore.
 * Retained legacy inputs are never modified; the flag is committed last and selects Room as the sole
 * runtime authority. Once v1 is authoritative, opening Room applies ordinary version migrations and
 * validates exported history.
 */
internal class RoomStorageMigration(
    private val context: Context,
    private val preferences: DataStore<Preferences> = context.applicationContext.notiSyncDataStore,
) {
    private val appContext = context.applicationContext

    suspend fun prepare(): PreparedStorage {
        val legacyPreferences = runCatching {
            preferences.data.first()
        }.getOrElse { failure ->
            Log.e(TAG, "Could not read Room migration state; using retained legacy storage", failure)
            return PreparedStorage(
                usesRoom = false,
                migrationFailure = failure,
            )
        }
        if (legacyPreferences[MIGRATION_COMPLETE] == true) return openRoomStorage()

        return runCatching {
            rebuildV1Targets(legacyPreferences)
            val prepared = openRoomStorage()
            // Written only after both targets were copied, integrity-checked, and reopened by Room.
            // Every legacy SQLite file and DataStore entry remains available for an explicit rehearsal.
            preferences.edit { it[MIGRATION_COMPLETE] = true }
            prepared
        }.getOrElse { failure ->
            Log.e(TAG, "Legacy-to-Room v1 migration failed; using retained legacy storage", failure)
            closeAndDeleteTargets()
            PreparedStorage(
                usesRoom = false,
                migrationFailure = failure,
            )
        }
    }

    private suspend fun openRoomStorage(): PreparedStorage {
        val core = CoreDatabaseFactory.get(appContext)
        try {
            core.metadata().schemaObjectCount()
        } finally {
            CoreDatabaseFactory.close(appContext)
        }
        val operational = OperationalDatabase.create(appContext)
        try {
            val database = operational
            database.metadata().schemaObjectCount()
        } finally {
            operational.close()
        }
        return PreparedStorage(
            usesRoom = true,
        )
    }

    private suspend fun rebuildV1Targets(legacyPreferences: Preferences) {
        closeAndDeleteTargets()

        val core = CoreDatabaseFactory.get(appContext)
        core.metadata().schemaObjectCount()
        CoreDatabaseFactory.close(appContext)

        val operational = OperationalDatabase.create(appContext)
        try {
            val database = operational
            database.metadata().schemaObjectCount()
        } finally {
            operational.close()
        }
        copyCoreSources()
        copyOperationalSources(legacyPreferences)
    }

    private fun closeAndDeleteTargets() {
        CoreDatabaseFactory.close(appContext)
        OperationalDatabaseFactory.close(appContext)
        deleteDatabaseIfPresent(CoreDatabase.DATABASE_NAME)
        deleteDatabaseIfPresent(OperationalDatabase.DATABASE_NAME)
    }

    private fun deleteDatabaseIfPresent(name: String) {
        val file = appContext.getDatabasePath(name)
        if (file.exists()) {
            check(appContext.deleteDatabase(name) || !file.exists()) {
                "Could not rebuild $name"
            }
        }
    }

    private fun copyCoreSources() = copySources(
        destinationName = CoreDatabase.DATABASE_NAME,
        sources = CORE_SOURCES,
        databaseLabel = "Core",
        seedOperationalDefaults = false,
    )

    private fun copyOperationalSources(legacyPreferences: Preferences) = copySources(
        destinationName = OperationalDatabase.DATABASE_NAME,
        sources = OPERATIONAL_SOURCES,
        databaseLabel = "Operational",
        seedOperationalDefaults = true,
        legacyPreferences = legacyPreferences,
    )

    private fun copySources(
        destinationName: String,
        sources: List<LegacySource>,
        databaseLabel: String,
        seedOperationalDefaults: Boolean,
        legacyPreferences: Preferences? = null,
    ) {
        val destinationFile = appContext.getDatabasePath(destinationName)
        val attached = sources.mapNotNull { source ->
            val file = appContext.getDatabasePath(source.databaseName)
            if (file.isFile) AttachedSource(source, file) else null
        }
        val database = SQLiteDatabase.openDatabase(
            destinationFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        )
        try {
            database.execSQL("PRAGMA foreign_keys=OFF")
            attached.forEach { source ->
                database.execSQL(
                    "ATTACH DATABASE ? AS ${source.spec.alias}",
                    arrayOf(source.file.absolutePath),
                )
            }
            database.beginTransaction()
            try {
                attached.forEach { source -> copySource(database, source.spec) }
                if (seedOperationalDefaults) {
                    seedSshProviderState(database)
                    seedOperationalPreferences(database, checkNotNull(legacyPreferences))
                }
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
            attached.forEach { database.execSQL("DETACH DATABASE ${it.spec.alias}") }
            database.rawQuery("PRAGMA integrity_check", emptyArray()).use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0) == "ok") {
                    "$databaseLabel v1 integrity check failed"
                }
            }
            database.rawQuery("PRAGMA foreign_key_check", emptyArray()).use { cursor ->
                check(!cursor.moveToFirst()) { "$databaseLabel v1 foreign-key check failed" }
            }
        } finally {
            database.close()
        }
    }

    private fun copySource(database: SQLiteDatabase, source: LegacySource) {
        source.tables.forEach { table ->
            check(sourceTableExists(database, source.alias, table.name)) {
                "${source.databaseName} is missing known-good table ${table.name}"
            }
            val actualColumns = sourceColumns(database, source.alias, table.name)
            check(actualColumns.containsAll(table.columns)) {
                "${source.databaseName}/${table.name} is missing known-good columns"
            }
            val columns = table.columns.joinToString(",")
            database.execSQL(
                "INSERT INTO ${table.name}($columns) SELECT $columns FROM ${source.alias}.${table.name}",
            )
            val sourceCount = rowCount(database, "${source.alias}.${table.name}")
            val targetCount = rowCount(database, table.name)
            check(sourceCount == targetCount) {
                "${source.databaseName}/${table.name} row count changed during migration"
            }
        }
    }

    private fun sourceTableExists(database: SQLiteDatabase, alias: String, table: String): Boolean =
        database.rawQuery(
            "SELECT 1 FROM $alias.sqlite_master WHERE type='table' AND name=? LIMIT 1",
            arrayOf(table),
        ).use { it.moveToFirst() }

    private fun sourceColumns(database: SQLiteDatabase, alias: String, table: String): Set<String> =
        database.rawQuery("PRAGMA $alias.table_info($table)", emptyArray()).use { cursor ->
            buildSet {
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
        }

    private fun rowCount(database: SQLiteDatabase, table: String): Long =
        database.rawQuery("SELECT COUNT(*) FROM $table", emptyArray()).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun seedSshProviderState(database: SQLiteDatabase) {
        if (rowCount(database, "provider_state") != 0L) return
        database.execSQL(
            "INSERT INTO provider_state(singleton, inventory_generation, revision) VALUES(1, ?, 1)",
            arrayOf(UUID.randomUUID().toString()),
        )
    }

    private fun seedOperationalPreferences(database: SQLiteDatabase, values: Preferences) {
        database.execSQL(
            "INSERT INTO notification_capture_state(singleton_id, last_seen_post_time) VALUES(1, ?)",
            arrayOf(values[LAST_SEEN_POST_TIME] ?: 0L),
        )

        val enabledPackages = decodeSetOrEmpty<String>(values[ENABLED_PACKAGES])
        val configs = decodeMapOrEmpty<String, PerAppConfig>(values[PER_APP_CONFIG])
        val seenChannels = decodeMapOrEmpty<String, List<SeenChannel>>(values[PER_APP_SEEN_CHANNELS])
        (enabledPackages + configs.keys + seenChannels.keys).sorted().forEach { packageName ->
            database.execSQL(
                "INSERT INTO android_apps(package_name, enabled, config_json, seen_channels_json) " +
                    "VALUES(?, ?, ?, ?)",
                arrayOf<Any?>(
                    packageName,
                    if (packageName in enabledPackages) 1 else 0,
                    configs[packageName]?.let(ProtocolCodec::encodeToJson),
                    seenChannels[packageName]?.let(ProtocolCodec::encodeToJson),
                ),
            )
        }

        decodeMapOrEmpty<String, FilterSync>(values[RECEIVED_NOTIFICATION_FILTERS])
            .toSortedMap()
            .forEach { (requesterClientId, filter) ->
                database.execSQL(
                    "INSERT INTO incoming_notification_filters" +
                        "(requester_client_id, filter_json, updated_at) VALUES(?, ?, ?)",
                    arrayOf(requesterClientId, ProtocolCodec.encodeToJson(filter), filter.updatedAt),
                )
            }

        val enabledBundles = decodeSetOrEmpty<String>(values[ANCS_ENABLED_BUNDLES])
        val discoveredApps = decodeMapOrEmpty<String, IosApp>(values[ANCS_DISCOVERED_APPS])
        (enabledBundles + discoveredApps.keys).sorted().forEach { bundleId ->
            val discovered = discoveredApps[bundleId]
            database.execSQL(
                "INSERT INTO ios_apps(bundle_id, enabled, display_name, last_seen_at) VALUES(?, ?, ?, ?)",
                arrayOf<Any?>(
                    bundleId,
                    if (bundleId in enabledBundles) 1 else 0,
                    discovered?.displayName,
                    discovered?.lastSeen,
                ),
            )
        }

        database.execSQL(
            "INSERT INTO screen_mirror_state(" +
                "singleton_id, enabled, authorized_peer_ids_json, request_replay_json, " +
                "replay_blocked, replay_quarantine_digest, replay_quarantined_at" +
                ") VALUES(1, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(
                if (values[SCREEN_MIRRORING_ENABLED] == true) 1 else 0,
                values[SCREEN_AUTHORIZED_PEERS] ?: ProtocolCodec.encodeToJson(emptySet<String>()),
                values[SCREEN_REQUEST_REPLAY],
                if (values[SCREEN_REPLAY_BLOCKED] == true) 1 else 0,
                values[SCREEN_REPLAY_QUARANTINE_DIGEST],
                values[SCREEN_REPLAY_QUARANTINED_AT],
            ),
        )

        decodeMapOrEmpty<String, String>(values[SCREEN_CODEC_PREFERENCES])
            .toSortedMap()
            .forEach { (peerId, codec) ->
                database.execSQL(
                    "INSERT INTO screen_codec_preferences(peer_id, codec) VALUES(?, ?)",
                    arrayOf(peerId, codec),
                )
            }

        database.execSQL(
            "INSERT INTO openpgp_enrollment(" +
                "singleton_id, enabled, provider_id, provider_key_reference, primary_key_id, " +
                "display_identity, enrolled_at" +
                ") VALUES(1, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(
                if (values[OPENPGP_ENABLED] == true) 1 else 0,
                values[OPENPGP_PROVIDER],
                values[OPENPGP_PROVIDER_REFERENCE],
                values[OPENPGP_PRIMARY_KEY_ID],
                values[OPENPGP_DISPLAY_IDENTITY],
                values[OPENPGP_ENROLLED_AT],
            ),
        )
    }

    private inline fun <reified T> decodeSetOrEmpty(encoded: String?): Set<T> = encoded?.let {
        runCatching { ProtocolCodec.decodeFromJson<Set<T>>(it) }.getOrDefault(emptySet())
    } ?: emptySet()

    private inline fun <reified K, reified V> decodeMapOrEmpty(encoded: String?): Map<K, V> = encoded?.let {
        runCatching { ProtocolCodec.decodeFromJson<Map<K, V>>(it) }.getOrDefault(emptyMap())
    } ?: emptyMap()

    private data class AttachedSource(val spec: LegacySource, val file: File)

    private data class LegacySource(
        val databaseName: String,
        val alias: String,
        val tables: List<LegacyTable>,
    )

    private data class LegacyTable(val name: String, val columns: List<String>)

    private companion object {
        const val TAG = "RoomStorageMigration"
        val MIGRATION_COMPLETE = booleanPreferencesKey("known_good_to_room_v1_complete")
        val ENABLED_PACKAGES = stringPreferencesKey("enabled_packages_json")
        val PER_APP_CONFIG = stringPreferencesKey("per_app_config_json")
        val PER_APP_SEEN_CHANNELS = stringPreferencesKey("per_app_seen_channels_json")
        val LAST_SEEN_POST_TIME = longPreferencesKey("last_seen_post_time")
        val RECEIVED_NOTIFICATION_FILTERS = stringPreferencesKey("received_notification_filters_json")
        val ANCS_ENABLED_BUNDLES = stringPreferencesKey("ancs_enabled_bundles_json")
        val ANCS_DISCOVERED_APPS = stringPreferencesKey("ancs_discovered_apps_json")
        val SCREEN_MIRRORING_ENABLED = booleanPreferencesKey("screen_mirroring_enabled")
        val SCREEN_AUTHORIZED_PEERS = stringPreferencesKey("screen_mirror_authorized_peer_ids")
        val SCREEN_REQUEST_REPLAY = stringPreferencesKey("screen_mirror_request_replay_v1")
        val SCREEN_REPLAY_BLOCKED = booleanPreferencesKey("screen_mirror_request_replay_v1_blocked")
        val SCREEN_REPLAY_QUARANTINE_DIGEST =
            stringPreferencesKey("screen_mirror_request_replay_v1_quarantine_digest")
        val SCREEN_REPLAY_QUARANTINED_AT =
            longPreferencesKey("screen_mirror_request_replay_v1_quarantined_at")
        val SCREEN_CODEC_PREFERENCES = stringPreferencesKey("screen_mirror_codec_preferences_v1")
        val OPENPGP_ENABLED = booleanPreferencesKey("openpgp_sign_enabled")
        val OPENPGP_PROVIDER = stringPreferencesKey("openpgp_sign_provider")
        val OPENPGP_PROVIDER_REFERENCE = stringPreferencesKey("openpgp_sign_provider_reference")
        val OPENPGP_PRIMARY_KEY_ID = stringPreferencesKey("openpgp_sign_primary_key_id")
        val OPENPGP_DISPLAY_IDENTITY = stringPreferencesKey("openpgp_sign_display_identity")
        val OPENPGP_ENROLLED_AT = longPreferencesKey("openpgp_sign_enrolled_at")

        fun table(name: String, vararg columns: String) = LegacyTable(name, columns.toList())

        val CORE_SOURCES = listOf(
            LegacySource(
                LegacyDatabaseNames.MESSAGE_LEDGER,
                "message_core_source",
                listOf(
                    table("dedup", "message_id", "handled_at"),
                    table("pending_ack", "message_id", "queued_at"),
                    table(
                        "relay_inbox",
                        "message_id",
                        "envelope",
                        "accepted_at",
                        "delivery_mode",
                        "received_at",
                        "early_ack",
                    ),
                    table("message_meta", "name", "long_value"),
                ),
            ),
        )

        val OPERATIONAL_SOURCES = listOf(
            LegacySource(
                LegacyDatabaseNames.MESSAGE_LEDGER,
                "message_operational_source",
                listOf(
                    table("mirror_msg", "source_client", "source_key", "message_id", "recorded_at"),
                    table(
                        "mirror_lifecycle",
                        "source_client",
                        "source_key",
                        "post_time",
                        "dismissed_at",
                        "updated_at",
                    ),
                ),
            ),
            LegacySource(
                LegacyDatabaseNames.RUNS,
                "runs_source",
                listOf(
                    table(
                        "runs",
                        "host_client",
                        "run_id",
                        "revision",
                        "presented_revision",
                        "active",
                        "updated_at",
                        "ended_at",
                        "received_at",
                        "payload",
                    ),
                ),
            ),
            LegacySource(
                LegacyDatabaseNames.RUN_CONTROL_OUTBOX,
                "controls_source",
                listOf(table("controls", "request_id", "requested_at", "payload")),
            ),
            LegacySource(
                LegacyDatabaseNames.OPENPGP_SIGNING,
                "seal_source",
                listOf(
                    table(
                        "sign_requests",
                        "request_id",
                        "requester_client_id",
                        "sender_client_id",
                        "primary_key_id",
                        "issued_at",
                        "expires_at",
                        "payload_sha256",
                        "object_kind",
                        "payload",
                        "state",
                        "encoded_response",
                        "updated_at",
                        "commit_details",
                        "result",
                        "working_directory",
                    ),
                ),
            ),
            LegacySource(
                LegacyDatabaseNames.SSH_KEY_PROVIDER,
                "ssh_source",
                listOf(
                    table("provider_state", "singleton", "inventory_generation", "revision"),
                    table(
                        "ssh_keys",
                        "provider_key_id",
                        "public_blob",
                        "public_hash",
                        "algorithm",
                        "display_name",
                        "origin",
                        "approval_policy",
                        "created_at",
                        "expires_at",
                    ),
                    table(
                        "ssh_operational_keys",
                        "provider_key_id",
                        "provider_kind",
                        "key_alias",
                        "ciphertext",
                        "nonce",
                        "security_level",
                        "user_verification_policy",
                        "strongbox_attempted",
                        "strongbox_fallback",
                    ),
                    table(
                        "ssh_export_copies",
                        "provider_key_id",
                        "key_alias",
                        "ciphertext",
                        "nonce",
                        "security_level",
                        "backend_policy",
                        "authentication",
                        "strongbox_attempted",
                        "strongbox_fallback",
                        "last_verified_at",
                    ),
                    table(
                        "ssh_key_lifecycle",
                        "provider_key_id",
                        "operational_alias",
                        "state",
                        "created_at",
                        "operational_candidate_ciphertext",
                        "operational_candidate_nonce",
                        "operational_candidate_security_level",
                        "export_candidate_ciphertext",
                        "export_candidate_nonce",
                        "export_candidate_security_level",
                    ),
                    table(
                        "authorization_floors",
                        "requester_client_id",
                        "authorization_generation",
                        "invalidated_through_epoch",
                        "updated_at",
                    ),
                    table(
                        "ssh_remembered_authorizations",
                        "authorization_id",
                        "provider_key_id",
                        "requester_client_id",
                        "authorization_generation",
                        "authorization_epoch",
                        "scope",
                        "host_key_sha256",
                        "created_at",
                    ),
                    table(
                        "ssh_known_hosts",
                        "host_key_sha256",
                        "hostname",
                        "first_approved_at",
                        "last_approved_at",
                    ),
                    table(
                        "provider_requests",
                        "request_id",
                        "kind",
                        "requester_client_id",
                        "request_fingerprint",
                        "request_cbor",
                        "request_nonce",
                        "history_cbor",
                        "history_nonce",
                        "state",
                        "outcome",
                        "result_at",
                        "response_cbor",
                        "response_nonce",
                        "updated_at",
                    ),
                ),
            ),
        )
    }
}

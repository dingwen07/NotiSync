package net.extrawdw.apps.notisync.data.storage.migration

import android.content.Context
import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase as FrameworkSQLiteDatabase
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.extrawdw.apps.notisync.data.PerAppConfig
import net.extrawdw.apps.notisync.data.SeenChannel
import net.extrawdw.apps.notisync.data.storage.core.CoreDatabase
import net.extrawdw.apps.notisync.data.storage.core.CoreDatabaseFactory
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDatabase
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDatabaseFactory
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDatabaseEncryption
import net.extrawdw.apps.notisync.data.storage.operational.LegacyOperationalImportSchema
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.extrawdw.apps.notisync.sshkeyprovider.SshInventoryGeneration
import net.extrawdw.apps.notisync.ios.IosApp
import net.extrawdw.notisync.protocol.FilterSync
import net.extrawdw.notisync.protocol.ProtocolCodec

internal val Context.notiSyncDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "notisync")

internal object LegacyDatabaseNames {
    const val MESSAGE_LEDGER = "message_ledger.db"
    const val RUNS = "runs.db"
    const val RUN_CONTROL_OUTBOX = "run_control_outbox.db"
    const val OPENPGP_SIGNING = "openpgp_signing.db"
    const val SSH_KEY_PROVIDER = "ssh-key-provider.sqlite3"
}

/**
 * The only custom storage cutover. An absent flag in the existing Preferences DataStore rebuilds
 * both targets from known-good SQLite sources and application aggregates in DataStore. Inputs stay
 * recoverable until the targets pass validation and the completion flag selects Room as the sole
 * authority. Only then are obsolete plaintext inputs removed. Operational data is encrypted before
 * import; frozen v4 signing tables route legacy records through the same v5 history migration.
 */
internal class RoomStorageMigration(
    private val context: Context,
    private val preferences: DataStore<Preferences> = context.applicationContext.notiSyncDataStore,
) {
    private val appContext = context.applicationContext
    private val skippedCounts = mutableMapOf<String, Long>()

    suspend fun prepare(onMigrationRequired: () -> Unit = {}) {
        skippedCounts.clear()
        val legacyPreferences = preferences.data.first()
        if (legacyPreferences[MIGRATION_COMPLETE] == true) {
            openRoomStorage()
            removeObsoletePlaintextSources()
            return
        }

        // Report the cutover before any rebuild work begins so the launch UI can show migration
        // progress immediately. Keep this as a callback rather than a UI type: the importer remains
        // usable from instrumentation and other non-Compose entry points.
        onMigrationRequired()
        try {
            rebuildV1Targets(legacyPreferences)
            openRoomStorage()
            // Written only after both targets were copied, integrity-checked, and reopened by Room.
            preferences.edit { it[MIGRATION_COMPLETE] = true }
            if (skippedCounts.isNotEmpty()) {
                Log.w(TAG, "Skipped inconsistent legacy records: " +
                    skippedCounts.entries.joinToString { (reason, count) -> "$reason=$count" })
            }
        } catch (failure: Throwable) {
            Log.e(TAG, "Legacy-to-Room v1 migration failed", failure)
            closeAndDeleteTargets()
            throw failure
        }
        // Cleanup failure must never delete the now-authoritative targets. Retry it at next startup.
        removeObsoletePlaintextSources()
    }

    private suspend fun openRoomStorage() {
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
        prepareLegacyOperationalTargets()
        copyOperationalSources(legacyPreferences)
    }

    private fun prepareLegacyOperationalTargets() {
        OperationalDatabaseEncryption.open(appContext).use(LegacyOperationalImportSchema::prepareEmptyTarget)
    }

    private suspend fun removeObsoletePlaintextSources() {
        (CORE_SOURCES + OPERATIONAL_SOURCES).map { it.databaseName }.distinct().forEach { name ->
            val source = appContext.getDatabasePath(name)
            FrameworkSQLiteDatabase.deleteDatabase(source)
            check(listOf("", "-wal", "-shm", "-journal").none { File(source.path + it).exists() }) {
                "Could not remove obsolete plaintext database $name"
            }
        }
        appContext.cacheDir.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("$MIGRATION_SNAPSHOT_PREFIX-") }
            .forEach { check(it.deleteRecursively()) { "Could not remove obsolete migration snapshot" } }
        preferences.edit { migrated ->
            listOf(
                ENABLED_PACKAGES, PER_APP_CONFIG, PER_APP_SEEN_CHANNELS, RECEIVED_NOTIFICATION_FILTERS,
                ANCS_ENABLED_BUNDLES, ANCS_DISCOVERED_APPS, SCREEN_AUTHORIZED_PEERS, SCREEN_REQUEST_REPLAY,
                SCREEN_REPLAY_QUARANTINE_DIGEST, SCREEN_CODEC_PREFERENCES, OPENPGP_PROVIDER,
                OPENPGP_PROVIDER_REFERENCE, OPENPGP_PRIMARY_KEY_ID, OPENPGP_DISPLAY_IDENTITY,
            ).forEach { migrated.remove(it) }
            listOf(LAST_SEEN_POST_TIME, SCREEN_REPLAY_QUARANTINED_AT, OPENPGP_ENROLLED_AT)
                .forEach { migrated.remove(it) }
            listOf(SCREEN_MIRRORING_ENABLED, SCREEN_REPLAY_BLOCKED, OPENPGP_ENABLED)
                .forEach { migrated.remove(it) }
        }
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
        val snapshotRoot = File(
            appContext.cacheDir,
            "$MIGRATION_SNAPSHOT_PREFIX-${UUID.randomUUID()}",
        )
        check(snapshotRoot.mkdirs()) { "Could not create legacy migration snapshots" }
        try {
            val readableSources = sources.mapNotNull { readableSource(it, snapshotRoot) }
            val database = SQLiteDatabase.openDatabase(
                destinationFile.absolutePath,
                if (seedOperationalDefaults) OperationalDatabaseEncryption.password(appContext) else byteArrayOf(),
                null,
                SQLiteDatabase.OPEN_READWRITE,
                OperationalDatabaseEncryption.preserveOnCorruption,
                null,
            )
            val attached = mutableListOf<AttachedSource>()
            try {
                database.setForeignKeyConstraintsEnabled(true)
                readableSources.forEach { source ->
                    database.execSQL(
                        "ATTACH DATABASE ? AS ${identifier(source.spec.alias)} KEY ''",
                        arrayOf(source.file.absolutePath),
                    )
                    attached += source
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
                database.rawQuery("PRAGMA integrity_check", emptyArray()).use { cursor ->
                    check(cursor.moveToFirst() && cursor.getString(0) == "ok") {
                        "$databaseLabel v1 integrity check failed"
                    }
                }
                database.rawQuery("PRAGMA foreign_key_check", emptyArray()).use { cursor ->
                    check(!cursor.moveToFirst()) { "$databaseLabel v1 foreign-key check failed" }
                }
            } finally {
                attached.forEach { source ->
                    runCatching { database.execSQL("DETACH DATABASE ${identifier(source.spec.alias)}") }
                }
                database.close()
            }
        } finally {
            check(snapshotRoot.deleteRecursively() || !snapshotRoot.exists()) {
                "Could not delete legacy migration snapshots"
            }
        }
    }

    private fun readableSource(source: LegacySource, snapshotRoot: File): AttachedSource? {
        val sourceFile = appContext.getDatabasePath(source.databaseName)
        if (!sourceFile.isFile) return null
        val snapshotFile = File(snapshotRoot, source.databaseName)
        return run {
            DATABASE_FILE_SUFFIXES.forEach { suffix ->
                val input = File(sourceFile.absolutePath + suffix)
                if (input.isFile) input.copyTo(File(snapshotFile.absolutePath + suffix))
            }
            FrameworkSQLiteDatabase.openDatabase(
                snapshotFile.absolutePath,
                null,
                FrameworkSQLiteDatabase.OPEN_READWRITE or FrameworkSQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING,
            ).use { database ->
                database.rawQuery("PRAGMA quick_check(1)", emptyArray()).use { cursor ->
                    check(cursor.moveToFirst() && cursor.getString(0) == "ok")
                }
                database.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", emptyArray()).use { cursor ->
                    check(cursor.moveToFirst() && cursor.getInt(0) == 0) {
                        "Could not checkpoint the legacy migration snapshot"
                    }
                }
                database.rawQuery("PRAGMA quick_check(1)", emptyArray()).use { cursor ->
                    check(cursor.moveToFirst() && cursor.getString(0) == "ok")
                }
            }
            AttachedSource(source, snapshotFile)
        }
    }

    private fun copySource(database: SQLiteDatabase, source: LegacySource) {
        source.tables.forEach { table ->
            copyTable(database, source, table)
        }
        // The message ledger is intentionally split across two destinations. Their union defines
        // known tables; unknown legacy features are deliberately omitted from the current schema.
        val knownTables = (CORE_SOURCES + OPERATIONAL_SOURCES)
            .filter { it.databaseName == source.databaseName }.flatMap { it.tables }.map { it.name }.toSet()
        val unknownTables = database.rawQuery(
            "SELECT name FROM ${identifier(source.alias)}.sqlite_master WHERE type='table' " +
                "AND name NOT LIKE 'sqlite_%' AND name NOT IN ('android_metadata','room_master_table')",
            emptyArray(),
        ).use { cursor -> buildList {
            while (cursor.moveToNext()) cursor.getString(0).takeIf { it !in knownTables }?.let(::add)
        } }
        unknownTables.forEach { table ->
            recordSkipped("unknown_legacy_rows", rowCount(database, "${identifier(source.alias)}.${identifier(table)}"))
        }
    }

    private fun copyTable(database: SQLiteDatabase, source: LegacySource, table: LegacyTable) {
        if (!sourceTableExists(database, source.alias, table.name)) {
            Log.i(TAG, "Legacy table absent; skipping ${source.databaseName}/${table.name}")
            return
        }
        val actualColumns = sourceColumns(database, source.alias, table.name)
        val columns = table.columns.filter(actualColumns::contains)
        database.rawQuery(
            "SELECT * FROM ${identifier(source.alias)}.${identifier(table.name)}", emptyArray(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val values = cursor.typedValues()
                if (columns.isEmpty()) {
                    recordSkipped("incompatible_legacy_rows")
                    continue
                }
                val targetValues = ContentValues().apply {
                    columns.forEach { name ->
                        when (val value = values[name]) {
                            null -> putNull(name)
                            is String -> put(name, value)
                            is Long -> put(name, value)
                            is Double -> put(name, value)
                            is ByteArray -> put(name, value)
                        }
                    }
                }
                try {
                    database.insertOrThrow(table.name, null, targetValues)
                } catch (_: SQLiteConstraintException) {
                    recordSkipped("inconsistent_legacy_rows")
                }
            }
        }
    }

    private fun Cursor.typedValues(): Map<String, Any?> = columnNames.mapIndexed { index, name ->
        name to when (getType(index)) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_INTEGER -> getLong(index)
            Cursor.FIELD_TYPE_FLOAT -> getDouble(index)
            Cursor.FIELD_TYPE_BLOB -> getBlob(index)
            else -> getString(index)
        }
    }.toMap()

    private fun recordSkipped(reason: String, count: Long = 1) {
        if (count > 0) skippedCounts[reason] = (skippedCounts[reason] ?: 0) + count
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

    private fun identifier(value: String): String = "\"${value.replace("\"", "\"\"")}\""

    // Initial import targets the frozen v4 names; Room subsequently applies v5's SSH/Seal prefixes.
    private fun seedSshProviderState(database: SQLiteDatabase) {
        if (rowCount(database, "provider_state") != 0L) return
        database.execSQL(
            "INSERT INTO provider_state(singleton, inventory_generation, revision) VALUES(1, ?, 1)",
            arrayOf(SshInventoryGeneration.create()),
        )
    }

    private fun seedOperationalPreferences(database: SQLiteDatabase, values: Preferences) {
        database.execSQL(
            "INSERT INTO notification_capture_state(singleton_id, last_seen_post_time) VALUES(1, ?)",
            arrayOf(values[LAST_SEEN_POST_TIME] ?: 0L),
        )

        val enabledPackages = decodeStringSet(values[ENABLED_PACKAGES], "enabled Android packages")
            .filter(::validStorageKey)
            .toSet()
        val configs = decodeStringMap<PerAppConfig>(values[PER_APP_CONFIG], "Android app configurations")
            .filterKeys(::validStorageKey)
        val seenChannels = decodeStringMap<List<SeenChannel>>(
            values[PER_APP_SEEN_CHANNELS],
            "Android seen channels",
        ).filterKeys(::validStorageKey)
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

        decodeStringMap<FilterSync>(values[RECEIVED_NOTIFICATION_FILTERS], "notification filters")
            .filterKeys(::validStorageKey)
            .toSortedMap()
            .forEach { (requesterClientId, filter) ->
                database.execSQL(
                    "INSERT INTO incoming_notification_filters" +
                        "(requester_client_id, filter_json, updated_at) VALUES(?, ?, ?)",
                    arrayOf(requesterClientId, ProtocolCodec.encodeToJson(filter), filter.updatedAt),
                )
            }

        val enabledBundles = decodeStringSet(values[ANCS_ENABLED_BUNDLES], "enabled iOS bundles")
            .filter(::validStorageKey)
            .toSet()
        val discoveredApps = decodeStringMap<IosApp>(values[ANCS_DISCOVERED_APPS], "discovered iOS apps")
            .filterKeys(::validStorageKey)
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
                ProtocolCodec.encodeToJson<Set<String>>(
                    decodeStringSet(values[SCREEN_AUTHORIZED_PEERS], "screen authorizations")
                        .filter(::validStorageKey)
                        .toSet(),
                ),
                decodeStringMap<Long>(values[SCREEN_REQUEST_REPLAY], "screen replay rows")
                    .filter { (digest, expiresAt) ->
                        (validReplayDigest(digest) && expiresAt > 0L).also { valid ->
                            if (!valid) recordSkipped("invalid_replay_rows")
                        }
                    }
                    .takeIf(Map<String, Long>::isNotEmpty)
                    ?.let(ProtocolCodec::encodeToJson),
                if (values[SCREEN_REPLAY_BLOCKED] == true) 1 else 0,
                values[SCREEN_REPLAY_QUARANTINE_DIGEST],
                values[SCREEN_REPLAY_QUARANTINED_AT],
            ),
        )

        decodeStringMap<String>(values[SCREEN_CODEC_PREFERENCES], "screen codec preferences")
            .filter { (peerId, codec) ->
                (validStorageKey(peerId) && codec.lowercase() in VALID_SCREEN_CODECS).also { valid ->
                    if (!valid) recordSkipped("invalid_codec_preferences")
                }
            }
            .toSortedMap()
            .forEach { (peerId, codec) ->
                database.execSQL(
                    "INSERT INTO screen_codec_preferences(peer_id, codec) VALUES(?, ?)",
                    arrayOf(peerId, codec),
                )
            }

        val provider = values[OPENPGP_PROVIDER]
        val providerReference = values[OPENPGP_PROVIDER_REFERENCE]
        val primaryKeyId = values[OPENPGP_PRIMARY_KEY_ID]
        val displayIdentity = values[OPENPGP_DISPLAY_IDENTITY]
        val validEnrollment = values[OPENPGP_ENABLED] == true &&
            !provider.isNullOrBlank() && !providerReference.isNullOrBlank() &&
            primaryKeyId?.matches(OPENPGP_KEY_ID) == true && !displayIdentity.isNullOrBlank()
        if (values[OPENPGP_ENABLED] == true && !validEnrollment) {
            recordSkipped("incomplete_openpgp_enrollment")
        }
        database.execSQL(
            "INSERT INTO openpgp_enrollment(" +
                "singleton_id, enabled, provider_id, provider_key_reference, primary_key_id, " +
                "display_identity, enrolled_at" +
                ") VALUES(1, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(
                if (validEnrollment) 1 else 0,
                provider.takeIf { validEnrollment },
                providerReference.takeIf { validEnrollment },
                primaryKeyId.takeIf { validEnrollment },
                displayIdentity.takeIf { validEnrollment },
                values[OPENPGP_ENROLLED_AT].takeIf { validEnrollment },
            ),
        )
    }

    private fun decodeStringSet(encoded: String?, label: String): Set<String> {
        if (encoded == null) return emptySet()
        val array = decodeJsonContainer<JsonArray>(encoded, label) ?: return emptySet()
        return array.mapNotNull { element ->
            val value = (element as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
            if (value != null && validStorageKey(value)) value else {
                recordSkipped("invalid_preference_entries")
                null
            }
        }.toSet()
    }

    private inline fun <reified V> decodeStringMap(encoded: String?, label: String): Map<String, V> {
        if (encoded == null) return emptyMap()
        val value = decodeJsonContainer<JsonObject>(encoded, label) ?: return emptyMap()
        return buildMap {
            value.forEach { (key, element) ->
                if (!validStorageKey(key)) {
                    recordSkipped("invalid_preference_entries")
                } else {
                    runCatching { ProtocolCodec.decodeFromJson<V>(element.toString()) }
                        .onSuccess { put(key, it) }
                        .onFailure { recordSkipped("undecodable_preference_entries") }
                }
            }
        }
    }

    private inline fun <reified T> decodeJsonContainer(encoded: String, label: String): T? =
        runCatching { Json.parseToJsonElement(encoded) as? T }.getOrNull().also { decoded ->
            if (decoded == null) recordSkipped("undecodable_preferences")
        }

    private fun validStorageKey(value: String): Boolean =
        value.isNotBlank() && value.length <= MAX_STORAGE_KEY_LENGTH && value.none(Char::isISOControl)

    private fun validReplayDigest(value: String): Boolean =
        value.length == SHA256_BASE64URL_LENGTH && value.all { it in BASE64URL_CHARS }

    private data class AttachedSource(val spec: LegacySource, val file: File)

    private data class LegacySource(
        val databaseName: String,
        val alias: String,
        val tables: List<LegacyTable>,
    )

    private data class LegacyTable(val name: String, val columns: List<String>)

    private companion object {
        const val TAG = "RoomStorageMigration"
        const val MAX_STORAGE_KEY_LENGTH = 512
        const val SHA256_BASE64URL_LENGTH = 43
        const val BASE64URL_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        const val MIGRATION_SNAPSHOT_PREFIX = "notisync-room-v1-sources"
        // The shared-memory file contains transient locks/read marks from the old process.
        // Copy the durable WAL, but let SQLite rebuild its matching -shm file in the snapshot.
        val DATABASE_FILE_SUFFIXES = listOf("", "-journal", "-wal")
        val VALID_SCREEN_CODECS = setOf("av1", "h265", "h264")
        val OPENPGP_KEY_ID = Regex("[0-9A-F]{16}")
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
                        "ssh_webauthn_credentials",
                        "provider_key_id",
                        "credential_id",
                        "user_handle",
                        "rp_id",
                        "cose_public_key",
                        "backup_eligible",
                        "backup_state",
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

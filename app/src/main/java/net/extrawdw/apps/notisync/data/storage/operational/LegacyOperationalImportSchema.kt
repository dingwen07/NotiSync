package net.extrawdw.apps.notisync.data.storage.operational

import net.zetetic.database.sqlcipher.SQLiteDatabase

/**
 * Frozen v4 import boundary, matching the exported Room v4 schema. Only used on a newly created,
 * empty target during the original pre-Room cutover. The ordinary 4 -> 5 Room migration then decodes
 * legacy Run/signing blobs exactly once, with the same validation as an existing Room installation.
 */
internal object LegacyOperationalImportSchema {
    fun prepareEmptyTarget(database: SQLiteDatabase) {
        listOf("mirror_message", "runs", "run_revisions", "run_controls", "ssh_requests", "seal_requests",
            "ssh_provider_state", "ssh_authorization_floors", "seal_enrollment").forEach { table ->
            database.rawQuery("SELECT COUNT(*) FROM $table", emptyArray()).use { cursor ->
                check(cursor.moveToFirst() && cursor.getLong(0) == 0L) { "Legacy import target is not empty" }
            }
        }
        database.transaction {
            // Legacy source catalogs still target the v4 name; the ordinary migration renames it forward.
            execSQL("DROP INDEX mirror_message_recorded_at_idx")
            execSQL("ALTER TABLE mirror_message RENAME TO mirror_msg")
            execSQL("DROP TABLE run_revisions")
            execSQL("DROP TABLE runs")
            execSQL("DROP TABLE run_controls")
            execSQL("""
                CREATE TABLE runs (
                    host_client TEXT NOT NULL, run_id TEXT NOT NULL, revision INTEGER NOT NULL,
                    presented_revision INTEGER NOT NULL, active INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL, ended_at INTEGER, received_at INTEGER NOT NULL,
                    payload BLOB NOT NULL, PRIMARY KEY(host_client, run_id)
                )
            """.trimIndent())
            execSQL("CREATE INDEX runs_order_idx ON runs(active DESC, updated_at DESC)")
            execSQL("CREATE INDEX runs_retention_idx ON runs(active, received_at)")
            execSQL("""
                CREATE TABLE controls (
                    request_id TEXT NOT NULL PRIMARY KEY, requested_at INTEGER NOT NULL,
                    payload BLOB NOT NULL
                )
            """.trimIndent())
            execSQL("CREATE INDEX controls_order_idx ON controls(requested_at, request_id)")
            execSQL("DROP TABLE ssh_requests")
            execSQL("DROP TABLE seal_requests")
            // The importer writes the original v4 names. The normal v4 -> v5 migration then
            // applies the same canonical prefixes used for an already-installed Room database.
            execSQL("ALTER TABLE ssh_provider_state RENAME TO provider_state")
            execSQL("ALTER TABLE ssh_authorization_floors RENAME TO authorization_floors")
            execSQL("ALTER TABLE seal_enrollment RENAME TO openpgp_enrollment")
            execSQL(
                """
                CREATE TABLE `sign_requests` (
                    `request_id` TEXT NOT NULL, `requester_client_id` TEXT NOT NULL,
                    `sender_client_id` TEXT NOT NULL, `primary_key_id` TEXT NOT NULL,
                    `issued_at` INTEGER NOT NULL, `expires_at` INTEGER NOT NULL,
                    `payload_sha256` BLOB NOT NULL, `object_kind` TEXT NOT NULL, `payload` BLOB,
                    `state` TEXT NOT NULL, `encoded_response` BLOB, `updated_at` INTEGER NOT NULL,
                    `commit_details` BLOB, `result` TEXT, `working_directory` TEXT,
                    PRIMARY KEY(`request_id`)
                )
                """.trimIndent(),
            )
            execSQL("CREATE INDEX `sign_requests_state_idx` ON `sign_requests` (`state`, `updated_at`)")
            execSQL("CREATE INDEX `sign_requests_sender_idx` ON `sign_requests` (`sender_client_id`, `state`)")
            execSQL(
                """
                CREATE TABLE `provider_requests` (
                    `request_id` TEXT NOT NULL, `kind` TEXT NOT NULL, `requester_client_id` TEXT NOT NULL,
                    `request_fingerprint` BLOB NOT NULL, `request_cbor` BLOB, `request_nonce` BLOB,
                    `history_cbor` BLOB NOT NULL, `history_nonce` BLOB NOT NULL,
                    `state` TEXT NOT NULL, `outcome` TEXT, `result_at` INTEGER,
                    `response_cbor` BLOB, `response_nonce` BLOB, `updated_at` INTEGER NOT NULL,
                    PRIMARY KEY(`request_id`)
                )
                """.trimIndent(),
            )
            execSQL("CREATE INDEX `provider_requests_state_idx` ON `provider_requests` (`state`, `updated_at`)")
            execSQL("DELETE FROM room_master_table")
            version = 4
        }
    }
}

package net.extrawdw.apps.notisync.composition.bootstrap

import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteFullException
import androidx.room3.RoomDatabase
import androidx.room3.useReaderConnection
import androidx.room3.withReadTransaction
import androidx.sqlite.SQLiteException
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.CancellationException
import net.extrawdw.apps.notisync.composition.storage.StorageClock
import net.extrawdw.apps.notisync.data.storage.core.CoreDatabase
import net.extrawdw.apps.notisync.data.storage.core.toSnapshot
import net.extrawdw.apps.notisync.data.storage.importer.target.OperationalRebuildIdentity
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDatabase
import net.extrawdw.apps.notisync.data.storage.runtime.ObservedOperationalContinuityMarker
import net.extrawdw.apps.notisync.data.storage.runtime.OperationalContinuityMarkerSource

internal class RoomCoreBootstrapTargetSnapshotSource(
    private val database: CoreDatabase,
    private val clock: StorageClock,
) : CoreBootstrapTargetSnapshotSource {
    override suspend fun read(): CoreBootstrapTargetSnapshot = bootstrapStorageAccess("core") {
        database.withReadTransaction {
            val counts = database.readExactCounts(CORE_TABLES)
            val transport = database.transportStateDao().get()?.toSnapshot()
            val identity = database.identityMetadataDao().get()?.toSnapshot()
            val freshOperation = database.keystoreOperationDao()
                .find(StorageBootstrapContract.FRESH_IDENTITY_OPERATION_ID)
                ?.toSnapshot()
            counts.requirePresence("core_transport_state", transport != null)
            counts.requirePresence("identity_metadata", identity != null)
            if (counts.getValue("keystore_operation") == 0L && freshOperation != null) {
                blocked("core_operation_inventory_ambiguous")
            }
            CoreBootstrapTargetSnapshot(
                totalApplicationRowCount = counts.values.sumChecked(),
                keystoreOperationRowCount = counts.getValue("keystore_operation"),
                transport = transport,
                identity = identity,
                freshIdentityOperation = freshOperation,
                validatedAt = clock.requirePositiveTime(),
            )
        }
    }
}

internal class RoomOperationalRebuildIdentitySource(
    private val database: OperationalDatabase,
    private val clock: StorageClock,
) : OperationalRebuildIdentitySource {
    override suspend fun resolve(purpose: OperationalRebuildPurpose): OperationalRebuildIdentity =
        bootstrapStorageAccess("operational") {
            database.withReadTransaction {
                val counts = database.readExactCounts(OPERATIONAL_TABLES)
                val maintenanceRows = counts.getValue("maintenance_state")
                val applicationRows = counts.entries
                    .filterNot { it.key == "maintenance_state" }
                    .map { it.value }
                    .sumChecked()
                val marker = database.profileDao().readMaintenance()
                if (marker != null) {
                    if (maintenanceRows != 1L || marker.operationalGeneration != 1L ||
                        marker.postCutoverWriteAt != null || marker.updatedAt <= 0
                    ) {
                        blocked("operational_rebuild_marker_invalid")
                    }
                    return@withReadTransaction OperationalRebuildIdentity(
                        operationalGeneration = marker.operationalGeneration,
                        storageIncarnationId = marker.storageIncarnationId,
                        startedAt = clock.requirePositiveTime(),
                    )
                }
                if (maintenanceRows != 0L) blocked("operational_rebuild_marker_ambiguous")
                if (purpose == OperationalRebuildPurpose.FRESH && applicationRows != 0L) {
                    blocked("fresh_operational_target_not_pristine")
                }
                OperationalRebuildIdentity(
                    operationalGeneration = 1L,
                    storageIncarnationId = newIncarnationId(),
                    startedAt = clock.requirePositiveTime(),
                )
            }
        }

    private fun newIncarnationId(): String {
        val bytes = ByteArray(INCARNATION_BYTES)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private companion object {
        const val INCARNATION_BYTES = 24
    }
}

internal class RoomOperationalContinuityMarkerSource(
    private val database: OperationalDatabase,
) : OperationalContinuityMarkerSource {
    override suspend fun readMarker(): ObservedOperationalContinuityMarker? =
        bootstrapStorageAccess("operational") {
            database.profileDao().readMaintenance()?.let { marker ->
                ObservedOperationalContinuityMarker(
                    operationalGeneration = marker.operationalGeneration,
                    storageIncarnationId = marker.storageIncarnationId,
                    postCutoverWriteAt = marker.postCutoverWriteAt,
                )
            }
        }
}

private suspend fun RoomDatabase.readExactCounts(expectedTables: Set<String>): Map<String, Long> =
    useReaderConnection { connection ->
        val observedTables = connection.usePrepared(
            "SELECT name FROM sqlite_master WHERE type = 'table' " +
                "AND name NOT LIKE 'sqlite_%' AND name NOT IN ('room_master_table', 'android_metadata')",
        ) { statement ->
            buildSet { while (statement.step()) add(statement.getText(0)) }
        }
        if (observedTables != expectedTables) blocked("bootstrap_target_schema_inventory_mismatch")
        expectedTables.sorted().associateWith { table ->
            connection.usePrepared("SELECT COUNT(*) FROM `$table`") { statement ->
                if (!statement.step()) blocked("bootstrap_target_count_missing")
                val count = statement.getLong(0)
                if (count < 0 || statement.step()) blocked("bootstrap_target_count_invalid")
                count
            }
        }
    }

private fun Map<String, Long>.requirePresence(table: String, present: Boolean) {
    val count = getValue(table)
    if ((count == 0L) == present || count > 1L) blocked("bootstrap_target_singleton_inventory_invalid")
}

private fun Iterable<Long>.sumChecked(): Long = fold(0L) { total, value -> Math.addExact(total, value) }

private fun StorageClock.requirePositiveTime(): Long = nowMillis().also {
    if (it <= 0) blocked("bootstrap_clock_invalid")
}

private suspend inline fun <T> bootstrapStorageAccess(
    domain: String,
    crossinline block: suspend () -> T,
): T = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: StorageBootstrapFailure) {
    throw failure
} catch (failure: SQLiteFullException) {
    throw StorageBootstrapFailure(StorageBootstrapFailureDisposition.USER_RECOVERABLE, "${domain}_storage_full", failure)
} catch (failure: SQLiteDatabaseCorruptException) {
    throw StorageBootstrapFailure(
        StorageBootstrapFailureDisposition.SECURITY_BLOCKING,
        "${domain}_database_corrupt",
        failure,
    )
} catch (failure: SQLiteException) {
    throw StorageBootstrapFailure(
        StorageBootstrapFailureDisposition.RETRYABLE,
        "${domain}_database_temporarily_unavailable",
        failure,
    )
} catch (failure: ArithmeticException) {
    throw StorageBootstrapFailure(
        StorageBootstrapFailureDisposition.SECURITY_BLOCKING,
        "${domain}_row_count_overflow",
        failure,
    )
} catch (failure: IllegalArgumentException) {
    throw StorageBootstrapFailure(
        StorageBootstrapFailureDisposition.SECURITY_BLOCKING,
        "${domain}_bootstrap_invariant_invalid",
        failure,
    )
} catch (failure: IllegalStateException) {
    throw StorageBootstrapFailure(
        StorageBootstrapFailureDisposition.SECURITY_BLOCKING,
        "${domain}_bootstrap_consistency_failure",
        failure,
    )
}

private fun blocked(code: String): Nothing = throw StorageBootstrapFailure(
    StorageBootstrapFailureDisposition.SECURITY_BLOCKING,
    code,
)

private val CORE_TABLES = setOf(
    "broker_auth_token",
    "core_activity_outbox",
    "core_command_applied",
    "core_maintenance_state",
    "core_transport_state",
    "crypto_epoch",
    "identity_metadata",
    "keystore_operation",
    "trust_snapshot",
)

private val OPERATIONAL_TABLES = setOf(
    "activity_event",
    "android_app_policy",
    "android_seen_channel",
    "android_seen_group",
    "android_subscope_policy",
    "incoming_filter",
    "incoming_filter_rule",
    "ios_app_allowlist",
    "ios_seen_app",
    "local_profile",
    "maintenance_state",
    "message_dedup",
    "mirror_lifecycle",
    "notification_capture_state",
    "relay_batch_stage",
    "run_state",
    "screen_authorized_peer",
    "screen_codec_preference",
    "screen_replay_token",
    "screen_security_state",
    "seal_enrollment",
    "seal_enrollment_protected",
    "seal_pending_payload",
    "seal_request",
    "seal_response_custody",
    "ssh_authorization_floor",
    "ssh_export_copy",
    "ssh_host_authorization",
    "ssh_key",
    "ssh_key_lifecycle",
    "ssh_key_lifecycle_candidate",
    "ssh_known_host",
    "ssh_operational_key",
    "ssh_peer_authorization",
    "ssh_provider_pending_payload",
    "ssh_provider_request",
    "ssh_provider_response_custody",
    "ssh_provider_state",
    "ssh_reset_alias",
    "ssh_reset_journal",
    "ssh_wrapped_operational_material",
)

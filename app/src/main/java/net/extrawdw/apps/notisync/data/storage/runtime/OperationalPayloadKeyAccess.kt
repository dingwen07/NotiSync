package net.extrawdw.apps.notisync.data.storage.runtime

import android.database.SQLException
import android.database.sqlite.SQLiteAccessPermException
import android.database.sqlite.SQLiteCantOpenDatabaseException
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteFullException
import android.database.sqlite.SQLiteOutOfMemoryException
import android.database.sqlite.SQLiteReadOnlyDatabaseException
import android.database.sqlite.SQLiteTableLockedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.extrawdw.apps.notisync.data.storage.core.CoreFoundationRepository
import net.extrawdw.apps.notisync.data.storage.protection.AndroidKeystoreProtectedPayloadVault
import net.extrawdw.apps.notisync.data.storage.protection.ProtectedPayloadException
import net.extrawdw.apps.notisync.data.storage.protection.ProtectedPayloadFailureCode

/**
 * One process-wide lock shared by Operational generation/reset maintenance, feature-local protected-key access,
 * protected writers, and final ACK authorization. Android Keystore work happens outside Room transactions.
 */
internal class OperationalStorageMaintenanceGate {
    private val mutex = Mutex()

    suspend fun <T> withExclusiveAccess(block: suspend () -> T): T = mutex.withLock { block() }

    /** Rechecks generation inside the same process gate that serializes reset and protected writers. */
    suspend fun <T> withProtectedGeneration(
        expectedGeneration: Long,
        generationSource: OperationalGenerationSource,
        block: suspend () -> T,
    ): ProtectedGenerationResult<T> = withExclusiveAccess {
        val current = generationSource.currentGeneration()
            ?: return@withExclusiveAccess ProtectedGenerationResult.Missing
        if (current != expectedGeneration) {
            return@withExclusiveAccess ProtectedGenerationResult.Stale(current)
        }
        ProtectedGenerationResult.Executed(block())
    }
}

internal sealed interface ProtectedGenerationResult<out T> {
    data class Executed<T>(val value: T) : ProtectedGenerationResult<T>
    data class Stale(val currentGeneration: Long) : ProtectedGenerationResult<Nothing>
    data object Missing : ProtectedGenerationResult<Nothing>
}

internal fun interface OperationalGenerationSource {
    suspend fun currentGeneration(): Long?
}

internal class CoreOperationalGenerationSource(
    private val repository: CoreFoundationRepository,
) : OperationalGenerationSource {
    override suspend fun currentGeneration(): Long? = repository.transport.first()?.operationalGeneration
}

internal enum class OperationalPayloadKeyFailureKind {
    RETRYABLE,
    USER_RECOVERABLE,
    SECURITY_BLOCKING,
}

internal data class OperationalPayloadKeyFailure(
    val kind: OperationalPayloadKeyFailureKind,
    val code: String,
)

private fun SQLException.toPayloadKeyFailure(): OperationalPayloadKeyFailure = when (this) {
    is SQLiteFullException -> OperationalPayloadKeyFailure(
        OperationalPayloadKeyFailureKind.USER_RECOVERABLE,
        "core_storage_full",
    )
    is SQLiteDatabaseCorruptException -> OperationalPayloadKeyFailure(
        OperationalPayloadKeyFailureKind.SECURITY_BLOCKING,
        "core_database_corrupt",
    )
    is SQLiteCantOpenDatabaseException,
    is SQLiteDatabaseLockedException,
    is SQLiteDiskIOException,
    is SQLiteOutOfMemoryException,
    is SQLiteTableLockedException,
    -> OperationalPayloadKeyFailure(
        OperationalPayloadKeyFailureKind.RETRYABLE,
        "core_database_temporarily_unavailable",
    )
    is SQLiteAccessPermException,
    is SQLiteReadOnlyDatabaseException,
    -> OperationalPayloadKeyFailure(
        OperationalPayloadKeyFailureKind.USER_RECOVERABLE,
        "core_database_unwritable",
    )
    else -> OperationalPayloadKeyFailure(
        OperationalPayloadKeyFailureKind.SECURITY_BLOCKING,
        "core_database_failure",
    )
}

internal interface OperationalPayloadKeyVault {
    /** Idempotently creates or validates the deterministic alias for [generation]. */
    suspend fun create(generation: Long)

    /** Loads the existing key and exercises its exact hardware/policy/AEAD contract without creating it. */
    suspend fun selfTest(generation: Long)
}

/** Moves every blocking Android Keystore provider call off the main thread. */
internal class AndroidOperationalPayloadKeyVault(
    private val vault: AndroidKeystoreProtectedPayloadVault,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : OperationalPayloadKeyVault {
    override suspend fun create(generation: Long) = withContext(dispatcher) {
        vault.create(generation)
        Unit
    }

    override suspend fun selfTest(generation: Long) = withContext(dispatcher) {
        vault.selfTest(generation)
        Unit
    }
}

internal sealed interface OperationalPayloadKeyAvailability {
    data class Ready(val generation: Long) : OperationalPayloadKeyAvailability
    data class Unavailable(
        val generation: Long?,
        val failure: OperationalPayloadKeyFailure,
    ) : OperationalPayloadKeyAvailability
}

/**
 * Feature-local access to the deterministic Operational payload key.
 *
 * There is deliberately no Core journal: the alias is a pure function of generation, create is idempotent, and a
 * process death at any point is recovered by repeating create + self-test. Seal and SSH call this before accepting
 * work that needs protected custody; unrelated networking and features never depend on it.
 */
internal class OperationalPayloadKeyEnsurer(
    private val generationSource: OperationalGenerationSource,
    private val vault: OperationalPayloadKeyVault,
    private val maintenanceGate: OperationalStorageMaintenanceGate,
) {
    suspend fun ensureCurrent(): OperationalPayloadKeyAvailability = try {
        maintenanceGate.withExclusiveAccess {
            val generation = generationSource.currentGeneration()
                ?: return@withExclusiveAccess unavailable(null, SECURITY_BLOCKING, "operational_generation_missing")
            if (generation <= 0) {
                return@withExclusiveAccess unavailable(
                    generation,
                    SECURITY_BLOCKING,
                    "operational_generation_invalid",
                )
            }

            try {
                vault.create(generation)
                vault.selfTest(generation)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: ProtectedPayloadException) {
                return@withExclusiveAccess unavailable(
                    generation,
                    failure.code.failureKind(),
                    failure.code.readinessCode(),
                )
            }

            val after = generationSource.currentGeneration()
            if (after != generation) {
                return@withExclusiveAccess unavailable(
                    after,
                    RETRYABLE,
                    "operational_generation_changed",
                )
            }
            OperationalPayloadKeyAvailability.Ready(generation)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: SQLException) {
        OperationalPayloadKeyAvailability.Unavailable(null, failure.toPayloadKeyFailure())
    }

    private fun ProtectedPayloadFailureCode.failureKind(): OperationalPayloadKeyFailureKind = when (this) {
        ProtectedPayloadFailureCode.PROVIDER_FAILURE -> OperationalPayloadKeyFailureKind.RETRYABLE
        ProtectedPayloadFailureCode.INVALID_INPUT,
        ProtectedPayloadFailureCode.UNSUPPORTED_SCHEME,
        ProtectedPayloadFailureCode.UNSUPPORTED_PROTECTION_VERSION,
        ProtectedPayloadFailureCode.UNSUPPORTED_PAYLOAD_CODEC_VERSION,
        ProtectedPayloadFailureCode.PAYLOAD_BOUNDS_EXCEEDED,
        ProtectedPayloadFailureCode.KEY_REFERENCE_MISMATCH,
        ProtectedPayloadFailureCode.KEY_MISSING,
        ProtectedPayloadFailureCode.KEY_ALIAS_CONFLICT,
        ProtectedPayloadFailureCode.KEY_INVALIDATED,
        ProtectedPayloadFailureCode.KEY_POLICY_VIOLATION,
        ProtectedPayloadFailureCode.AUTHENTICATION_FAILED,
        ProtectedPayloadFailureCode.WRONG_THREAD,
        -> OperationalPayloadKeyFailureKind.SECURITY_BLOCKING
    }

    private fun ProtectedPayloadFailureCode.readinessCode(): String =
        "protected_payload_${name.lowercase()}"

    private fun unavailable(
        generation: Long?,
        kind: OperationalPayloadKeyFailureKind,
        code: String,
    ): OperationalPayloadKeyAvailability.Unavailable = OperationalPayloadKeyAvailability.Unavailable(
        generation,
        OperationalPayloadKeyFailure(kind, code),
    )

    private companion object {
        val RETRYABLE = OperationalPayloadKeyFailureKind.RETRYABLE
        val SECURITY_BLOCKING = OperationalPayloadKeyFailureKind.SECURITY_BLOCKING
    }
}

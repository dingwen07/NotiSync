package net.extrawdw.apps.notisync.data.storage.importer.legacy

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

/**
 * Read-only snapshot reader for the six OpenPgpEnrollmentStore keys shipped in v51.
 *
 * The coordinator passes the already-created DataStore instance.  This reader never opens or
 * parses the backing file and never calls edit(), so DataStore retains ownership of its atomic
 * snapshot and corruption/recovery behavior.  In particular, enrollment metadata is not inferred
 * from the current process state and no import-time clock is consulted here.
 */
internal class LegacySealEnrollmentDataStoreReader {
    suspend fun read(dataStore: DataStore<Preferences>): LegacySealEnrollmentSnapshot =
        read(dataStore.data.first())

    /** Pure decoder used by tests and by coordinators that already hold a DataStore snapshot. */
    internal fun read(preferences: Preferences): LegacySealEnrollmentSnapshot {
        val tuple = RawEnrollmentTuple(
            enabled = preferences.readTyped {
                preferences[booleanPreferencesKey(LegacySealEnrollmentSourceContract.ENABLED_KEY)]
            },
            provider = preferences.readTyped {
                preferences[stringPreferencesKey(LegacySealEnrollmentSourceContract.PROVIDER_KEY)]
            },
            providerReference = preferences.readTyped {
                preferences[stringPreferencesKey(LegacySealEnrollmentSourceContract.PROVIDER_REFERENCE_KEY)]
            },
            primaryKeyId = preferences.readTyped {
                preferences[stringPreferencesKey(LegacySealEnrollmentSourceContract.PRIMARY_KEY_ID_KEY)]
            },
            displayIdentity = preferences.readTyped {
                preferences[stringPreferencesKey(LegacySealEnrollmentSourceContract.DISPLAY_IDENTITY_KEY)]
            },
            enrolledAt = preferences.readTyped {
                preferences[longPreferencesKey(LegacySealEnrollmentSourceContract.ENROLLED_AT_KEY)]
            },
        )
        if (tuple.hasTypeError) {
            return recovery(
                tuple = tuple,
                failure = LegacySealEnrollmentFailure.UNSUPPORTED_KEY_TYPE,
            )
        }

        val hasMaterial = tuple.hasMaterial
        val enabled = tuple.enabled.value
        if (!tuple.enabled.present && !hasMaterial) {
            return disabled(tuple)
        }
        if (enabled == false && !hasMaterial) {
            return disabled(tuple)
        }
        if (enabled != true) {
            return recovery(
                tuple = tuple,
                failure = LegacySealEnrollmentFailure.PARTIAL_TUPLE,
            )
        }

        if (!tuple.provider.present || !tuple.providerReference.present ||
            !tuple.primaryKeyId.present || !tuple.displayIdentity.present || !tuple.enrolledAt.present
        ) {
            return recovery(
                tuple = tuple,
                failure = if (!tuple.enrolledAt.present && tuple.provider.present &&
                    tuple.providerReference.present && tuple.primaryKeyId.present &&
                    tuple.displayIdentity.present
                ) {
                    LegacySealEnrollmentFailure.INVALID_ENROLLED_AT
                } else {
                    LegacySealEnrollmentFailure.ENABLED_MISSING_MATERIAL
                },
            )
        }

        val provider = requireNotNull(tuple.provider.value)
        if (!provider.isBoundedIdentifier()) {
            return recovery(tuple, LegacySealEnrollmentFailure.INVALID_PROVIDER)
        }
        val providerReference = requireNotNull(tuple.providerReference.value)
        if (!providerReference.isBoundedIdentifier()) {
            return recovery(tuple, LegacySealEnrollmentFailure.INVALID_PROVIDER_REFERENCE)
        }
        val primaryKeyId = requireNotNull(tuple.primaryKeyId.value)
        if (!PRIMARY_KEY_PATTERN.matches(primaryKeyId)) {
            return recovery(tuple, LegacySealEnrollmentFailure.INVALID_PRIMARY_KEY_ID)
        }
        val displayIdentity = requireNotNull(tuple.displayIdentity.value)
        if (!displayIdentity.isBoundedDisplayIdentity()) {
            return recovery(tuple, LegacySealEnrollmentFailure.INVALID_DISPLAY_IDENTITY)
        }
        val enrolledAt = requireNotNull(tuple.enrolledAt.value)
        if (enrolledAt <= 0) {
            return recovery(tuple, LegacySealEnrollmentFailure.INVALID_ENROLLED_AT)
        }

        return LegacySealEnrollmentSnapshot(
            status = LegacySealEnrollmentStatus.READY,
            enrollment = LegacySealEnrollment(
                providerId = provider,
                providerKeyReference = providerReference,
                primaryKeyId = primaryKeyId,
                displayIdentity = displayIdentity,
                enrolledAt = enrolledAt,
            ),
            failure = null,
            presentKeyCount = tuple.presentKeyCount,
        )
    }

    private fun disabled(tuple: RawEnrollmentTuple): LegacySealEnrollmentSnapshot = LegacySealEnrollmentSnapshot(
        status = LegacySealEnrollmentStatus.DISABLED,
        enrollment = null,
        failure = null,
        presentKeyCount = tuple.presentKeyCount,
    )

    private fun recovery(
        tuple: RawEnrollmentTuple,
        failure: LegacySealEnrollmentFailure,
    ): LegacySealEnrollmentSnapshot = LegacySealEnrollmentSnapshot(
        status = LegacySealEnrollmentStatus.RECOVERY_REQUIRED,
        enrollment = null,
        failure = failure,
        presentKeyCount = tuple.presentKeyCount,
    )

    private data class RawEnrollmentTuple(
        val enabled: RawValue<Boolean>,
        val provider: RawValue<String>,
        val providerReference: RawValue<String>,
        val primaryKeyId: RawValue<String>,
        val displayIdentity: RawValue<String>,
        val enrolledAt: RawValue<Long>,
    ) {
        val values: List<Pair<String, RawValue<*>>>
            get() = listOf(
                LegacySealEnrollmentSourceContract.ENABLED_KEY to enabled,
                LegacySealEnrollmentSourceContract.PROVIDER_KEY to provider,
                LegacySealEnrollmentSourceContract.PROVIDER_REFERENCE_KEY to providerReference,
                LegacySealEnrollmentSourceContract.PRIMARY_KEY_ID_KEY to primaryKeyId,
                LegacySealEnrollmentSourceContract.DISPLAY_IDENTITY_KEY to displayIdentity,
                LegacySealEnrollmentSourceContract.ENROLLED_AT_KEY to enrolledAt,
            )

        val hasTypeError: Boolean get() = values.any { it.second.typeError }
        val hasMaterial: Boolean get() = values.drop(1).any { it.second.present }
        val presentKeyCount: Int get() = values.count { it.second.present }

    }

    private data class RawValue<T>(
        val present: Boolean,
        val value: T?,
        val typeError: Boolean,
    )

    private inline fun <reified T : Any> Preferences.readTyped(read: () -> T?): RawValue<T> =
        try {
            val value = read()
            RawValue(present = value != null, value = value, typeError = false)
        } catch (_: ClassCastException) {
            // Do not stringify a value of the wrong type; the source value never enters a
            // diagnostic and the digest records only a stable type-error marker.
            RawValue(present = true, value = null, typeError = true)
        }

    private fun String.isBoundedIdentifier(): Boolean =
        isNotBlank() && length <= MAX_IDENTIFIER_CHARS && none(Char::isISOControl) && '\u0000' !in this

    private fun String.isBoundedDisplayIdentity(): Boolean =
        isNotBlank() && length <= MAX_DISPLAY_IDENTITY_CHARS &&
            none(Char::isISOControl) && '\u0000' !in this

    private companion object {
        const val MAX_IDENTIFIER_CHARS = 256
        const val MAX_DISPLAY_IDENTITY_CHARS = 1_024
        val PRIMARY_KEY_PATTERN = Regex("[0-9A-F]{16}")
    }
}

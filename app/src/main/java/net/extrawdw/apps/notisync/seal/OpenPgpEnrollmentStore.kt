package net.extrawdw.apps.notisync.seal

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.notisync.data.storage.operational.OpenPgpEnrollmentEntity
import net.extrawdw.apps.notisync.data.storage.operational.OperationalApplicationState

data class OpenPgpEnrollment(
    val enabled: Boolean = false,
    val providerId: String? = null,
    val providerKeyReference: String? = null,
    val primaryKeyId: String? = null,
    val displayIdentity: String? = null,
    val enrolledAt: Long? = null,
)

class OpenPgpEnrollmentStore private constructor(
    private val dataStore: DataStore<Preferences>,
    scope: CoroutineScope,
    private val operationalState: OperationalApplicationState?,
    @Suppress("UNUSED_PARAMETER") constructorMarker: Unit,
) {
    constructor(dataStore: DataStore<Preferences>, scope: CoroutineScope) :
        this(dataStore, scope, null, Unit)

    internal constructor(
        dataStore: DataStore<Preferences>,
        scope: CoroutineScope,
        operationalState: OperationalApplicationState,
    ) : this(dataStore, scope, operationalState, Unit)

    private val roomEnrollment = MutableStateFlow(
        operationalState?.let { state ->
            runCatching { runBlocking { state.openPgpEnrollment() } }
                .getOrNull()
                ?.let(::decode)
                ?: OpenPgpEnrollment()
        } ?: OpenPgpEnrollment(),
    )

    val enrollment: StateFlow<OpenPgpEnrollment> = operationalState?.let { roomEnrollment }
        ?: dataStore.data.map(::decode).stateIn(
            scope,
            SharingStarted.Eagerly,
            OpenPgpEnrollment(),
        )

    suspend fun save(selection: OpenPgpKeySelection, enrolledAt: Long = System.currentTimeMillis()) {
        require(selection.primaryKeyId.matches(Regex("[0-9A-F]{16}")))
        if (operationalState != null) {
            val entity = OpenPgpEnrollmentEntity(
                enabled = true,
                providerId = selection.providerId,
                providerKeyReference = selection.providerKeyReference,
                primaryKeyId = selection.primaryKeyId,
                displayIdentity = selection.displayIdentity,
                enrolledAt = enrolledAt,
            )
            operationalState.replaceOpenPgpEnrollment(entity)
            roomEnrollment.value = decode(entity)
        } else {
            dataStore.edit { values ->
                values[ENABLED] = true
                values[PROVIDER] = selection.providerId
                values[PROVIDER_REFERENCE] = selection.providerKeyReference
                values[PRIMARY_KEY_ID] = selection.primaryKeyId
                values[DISPLAY_IDENTITY] = selection.displayIdentity
                values[ENROLLED_AT] = enrolledAt
            }
        }
    }

    suspend fun clear() {
        if (operationalState != null) {
            operationalState.replaceOpenPgpEnrollment(
                OpenPgpEnrollmentEntity(
                    enabled = false,
                    providerId = null,
                    providerKeyReference = null,
                    primaryKeyId = null,
                    displayIdentity = null,
                    enrolledAt = null,
                ),
            )
            roomEnrollment.value = OpenPgpEnrollment()
        } else {
            dataStore.edit { values ->
                values.remove(ENABLED)
                values.remove(PROVIDER)
                values.remove(PROVIDER_REFERENCE)
                values.remove(PRIMARY_KEY_ID)
                values.remove(DISPLAY_IDENTITY)
                values.remove(ENROLLED_AT)
            }
        }
    }

    private fun decode(values: Preferences): OpenPgpEnrollment {
        val enabled = values[ENABLED] == true
        val provider = values[PROVIDER]
        val reference = values[PROVIDER_REFERENCE]
        val primary = values[PRIMARY_KEY_ID]
        val identity = values[DISPLAY_IDENTITY]
        val complete = enabled && !provider.isNullOrBlank() && !reference.isNullOrBlank() &&
            primary?.matches(Regex("[0-9A-F]{16}")) == true && !identity.isNullOrBlank()
        return if (!complete) OpenPgpEnrollment() else OpenPgpEnrollment(
            enabled = true,
            providerId = provider,
            providerKeyReference = reference,
            primaryKeyId = primary,
            displayIdentity = identity,
            enrolledAt = values[ENROLLED_AT],
        )
    }

    private fun decode(values: OpenPgpEnrollmentEntity): OpenPgpEnrollment {
        val complete = values.enabled && !values.providerId.isNullOrBlank() &&
            !values.providerKeyReference.isNullOrBlank() &&
            values.primaryKeyId?.matches(Regex("[0-9A-F]{16}")) == true &&
            !values.displayIdentity.isNullOrBlank()
        return if (!complete) OpenPgpEnrollment() else OpenPgpEnrollment(
            enabled = true,
            providerId = values.providerId,
            providerKeyReference = values.providerKeyReference,
            primaryKeyId = values.primaryKeyId,
            displayIdentity = values.displayIdentity,
            enrolledAt = values.enrolledAt,
        )
    }

    private companion object {
        val ENABLED = booleanPreferencesKey("openpgp_sign_enabled")
        val PROVIDER = stringPreferencesKey("openpgp_sign_provider")
        val PROVIDER_REFERENCE = stringPreferencesKey("openpgp_sign_provider_reference")
        val PRIMARY_KEY_ID = stringPreferencesKey("openpgp_sign_primary_key_id")
        val DISPLAY_IDENTITY = stringPreferencesKey("openpgp_sign_display_identity")
        val ENROLLED_AT = longPreferencesKey("openpgp_sign_enrolled_at")
    }
}

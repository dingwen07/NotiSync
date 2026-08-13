package net.extrawdw.apps.notisync.sign

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class OpenPgpEnrollment(
    val enabled: Boolean = false,
    val providerId: String? = null,
    val providerKeyReference: String? = null,
    val primaryKeyId: String? = null,
    val displayIdentity: String? = null,
    val enrolledAt: Long? = null,
)

class OpenPgpEnrollmentStore(
    private val dataStore: DataStore<Preferences>,
    scope: CoroutineScope,
) {
    val enrollment: StateFlow<OpenPgpEnrollment> = dataStore.data.map(::decode).stateIn(
        scope,
        SharingStarted.Eagerly,
        OpenPgpEnrollment(),
    )

    suspend fun save(selection: OpenPgpKeySelection, enrolledAt: Long = System.currentTimeMillis()) {
        require(selection.primaryKeyId.matches(Regex("[0-9A-F]{16}")))
        dataStore.edit { values ->
            values[ENABLED] = true
            values[PROVIDER] = selection.providerId
            values[PROVIDER_REFERENCE] = selection.providerKeyReference
            values[PRIMARY_KEY_ID] = selection.primaryKeyId
            values[DISPLAY_IDENTITY] = selection.displayIdentity
            values[ENROLLED_AT] = enrolledAt
        }
    }

    suspend fun clear() {
        dataStore.edit { values ->
            values.remove(ENABLED)
            values.remove(PROVIDER)
            values.remove(PROVIDER_REFERENCE)
            values.remove(PRIMARY_KEY_ID)
            values.remove(DISPLAY_IDENTITY)
            values.remove(ENROLLED_AT)
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

    private companion object {
        val ENABLED = booleanPreferencesKey("openpgp_sign_enabled")
        val PROVIDER = stringPreferencesKey("openpgp_sign_provider")
        val PROVIDER_REFERENCE = stringPreferencesKey("openpgp_sign_provider_reference")
        val PRIMARY_KEY_ID = stringPreferencesKey("openpgp_sign_primary_key_id")
        val DISPLAY_IDENTITY = stringPreferencesKey("openpgp_sign_display_identity")
        val ENROLLED_AT = longPreferencesKey("openpgp_sign_enrolled_at")
    }
}

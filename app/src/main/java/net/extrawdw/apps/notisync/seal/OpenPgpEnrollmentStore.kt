package net.extrawdw.apps.notisync.seal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

class OpenPgpEnrollmentStore internal constructor(
    private val operationalState: OperationalApplicationState,
) {
    private val roomEnrollment = MutableStateFlow(
        runCatching { runBlocking { operationalState.openPgpEnrollment() } }
            .getOrNull()
            ?.let(::decode)
            ?: OpenPgpEnrollment(),
    )

    val enrollment: StateFlow<OpenPgpEnrollment> = roomEnrollment

    suspend fun save(selection: OpenPgpKeySelection, enrolledAt: Long = System.currentTimeMillis()) {
        require(selection.primaryKeyId.matches(Regex("[0-9A-F]{16}")))
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
    }

    suspend fun clear() {
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
}

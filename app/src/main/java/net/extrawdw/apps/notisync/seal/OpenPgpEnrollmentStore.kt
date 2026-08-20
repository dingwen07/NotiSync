package net.extrawdw.apps.notisync.seal

import kotlinx.coroutines.flow.StateFlow

/**
 * The locally selected OpenPGP provider identity. The provider reference and identity are read from
 * protected Room storage by [OpenPgpEnrollmentRepository]; callers never own a legacy Preferences
 * DataStore or a second source of truth.
 */
data class OpenPgpEnrollment(
    val enabled: Boolean = false,
    val providerId: String? = null,
    val providerKeyReference: String? = null,
    val primaryKeyId: String? = null,
    val displayIdentity: String? = null,
    val enrolledAt: Long? = null,
)

/** Room-backed enrollment boundary used by the UI and signing engine. */
interface OpenPgpEnrollmentRepository {
    val enrollment: StateFlow<OpenPgpEnrollment>

    suspend fun save(selection: OpenPgpKeySelection, enrolledAt: Long = System.currentTimeMillis())

    suspend fun clear()
}

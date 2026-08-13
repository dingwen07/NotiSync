package net.extrawdw.apps.notisync.seal

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.openintents.openpgp.IOpenPgpService2
import org.openintents.openpgp.util.OpenPgpApi
import org.openintents.openpgp.util.OpenPgpServiceConnection
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class OpenPgpKeySelection(
    val providerId: String,
    val providerKeyReference: String,
    val primaryKeyId: String,
    val displayIdentity: String,
)

sealed interface ProviderOutcome<out T> {
    data class Success<T>(val value: T) : ProviderOutcome<T>
    data class InteractionRequired(val pendingIntent: PendingIntent) : ProviderOutcome<Nothing>
    data object Cancelled : ProviderOutcome<Nothing>
    data object Unavailable : ProviderOutcome<Nothing>
    data object UnsupportedKey : ProviderOutcome<Nothing>
    data class Failure(val code: Int? = null) : ProviderOutcome<Nothing>
}

interface OpenPgpSigningProvider {
    val providerId: String
    fun isAvailable(): Boolean
    suspend fun checkPermission(continuation: Intent? = null): ProviderOutcome<Unit>
    suspend fun selectSigningKey(continuation: Intent? = null): ProviderOutcome<OpenPgpKeySelection>
    suspend fun detachedSign(
        providerKeyReference: String,
        exactPayload: ByteArray,
        continuation: Intent? = null,
    ): ProviderOutcome<String>
}

class OpenKeychainSigningProvider(private val context: Context) : OpenPgpSigningProvider {
    override val providerId: String = PROVIDER_PACKAGE

    override fun isAvailable(): Boolean = context.packageManager.resolveService(
        Intent(OpenPgpApi.SERVICE_INTENT_2).setPackage(PROVIDER_PACKAGE),
        0,
    ) != null

    override suspend fun checkPermission(continuation: Intent?): ProviderOutcome<Unit> = execute(
        requestFor(OpenPgpApi.ACTION_CHECK_PERMISSION, continuation),
        input = null,
    ) { ProviderOutcome.Success(Unit) }

    override suspend fun selectSigningKey(
        continuation: Intent?,
    ): ProviderOutcome<OpenPgpKeySelection> = execute(
        requestFor(OpenPgpApi.ACTION_GET_SIGN_KEY_ID, continuation),
        input = null,
    ) { result ->
        if (!result.hasExtra(OpenPgpApi.EXTRA_SIGN_KEY_ID)) return@execute ProviderOutcome.Failure()
        val keyId = result.getLongExtra(OpenPgpApi.EXTRA_SIGN_KEY_ID, 0L)
        val normalized = normalizeOpenPgpKeyId(keyId)
        ProviderOutcome.Success(
            OpenPgpKeySelection(
                providerId = providerId,
                providerKeyReference = java.lang.Long.toUnsignedString(keyId),
                primaryKeyId = normalized,
                displayIdentity = "OpenKeychain · 0x$normalized",
            )
        )
    }

    override suspend fun detachedSign(
        providerKeyReference: String,
        exactPayload: ByteArray,
        continuation: Intent?,
    ): ProviderOutcome<String> {
        val keyId = runCatching { java.lang.Long.parseUnsignedLong(providerKeyReference) }.getOrNull()
            ?: return ProviderOutcome.UnsupportedKey
        val request = requestFor(OpenPgpApi.ACTION_DETACHED_SIGN, continuation).apply {
            putExtra(OpenPgpApi.EXTRA_SIGN_KEY_ID, keyId)
            putExtra(OpenPgpApi.EXTRA_REQUEST_ASCII_ARMOR, true)
        }
        return execute(request, ByteArrayInputStream(exactPayload)) { result ->
            val signature = result.getByteArrayExtra(OpenPgpApi.RESULT_DETACHED_SIGNATURE)
                ?: return@execute ProviderOutcome.Failure()
            val armor = runCatching { signature.decodeToString(throwOnInvalidSequence = true) }.getOrNull()
                ?: return@execute ProviderOutcome.Failure()
            ProviderOutcome.Success(armor)
        }
    }

    /**
     * OpenPGP API interaction activities return the original operation Intent with the user's
     * selections added. Preserve those extras while reasserting the operation owned by NotiSync.
     */
    private fun requestFor(action: String, continuation: Intent?): Intent =
        Intent(continuation ?: Intent()).setAction(action)

    private suspend fun <T> execute(
        request: Intent,
        input: java.io.InputStream?,
        success: (Intent) -> ProviderOutcome<T>,
    ): ProviderOutcome<T> {
        if (!isAvailable()) return ProviderOutcome.Unavailable
        return try {
            val result = withContext(Dispatchers.IO) {
                withService { service -> OpenPgpApi(context, service).executeApi(request, input, null) }
            }
            when (result.getIntExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_ERROR)) {
                OpenPgpApi.RESULT_CODE_SUCCESS -> success(result)
                OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED -> {
                    val pending = result.getParcelableExtra(OpenPgpApi.RESULT_INTENT, PendingIntent::class.java)
                    if (pending == null) ProviderOutcome.Failure()
                    else ProviderOutcome.InteractionRequired(pending)
                }
                else -> ProviderOutcome.Failure()
            }
        } catch (_: Exception) {
            ProviderOutcome.Unavailable
        }
    }

    private suspend fun <T> withService(block: (IOpenPgpService2) -> T): T {
        val connectionRef = AtomicReference<OpenPgpServiceConnection>()
        val service = withTimeout(SERVICE_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine { continuation ->
                val connection = OpenPgpServiceConnection(
                    context,
                    PROVIDER_PACKAGE,
                    object : OpenPgpServiceConnection.OnBound {
                        override fun onBound(service: IOpenPgpService2) {
                            if (continuation.isActive) continuation.resume(service)
                        }

                        override fun onError(error: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    },
                )
                connectionRef.set(connection)
                continuation.invokeOnCancellation {
                    if (connection.isBound) runCatching { connection.unbindFromService() }
                }
                connection.bindToService()
            }
        }
        return try {
            block(service)
        } finally {
            connectionRef.get()?.takeIf { it.isBound }?.let { runCatching { it.unbindFromService() } }
        }
    }

    private companion object {
        const val PROVIDER_PACKAGE = "org.sufficientlysecure.keychain"
        const val SERVICE_TIMEOUT_MILLIS = 5_000L
    }
}

internal fun normalizeOpenPgpKeyId(keyId: Long): String =
    java.lang.Long.toUnsignedString(keyId, 16).uppercase().padStart(16, '0')

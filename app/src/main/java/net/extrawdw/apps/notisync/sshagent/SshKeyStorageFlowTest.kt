package net.extrawdw.apps.notisync.sshagent

import android.app.Activity
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.SshAgentLimits
import net.extrawdw.notisync.protocol.SshConnectionDirection
import net.extrawdw.notisync.protocol.SshDestinationContext
import net.extrawdw.notisync.protocol.SshDestinationProvenance
import net.extrawdw.notisync.protocol.SshExportCopyBackendPolicy
import net.extrawdw.notisync.protocol.SshKeyAlgorithm
import net.extrawdw.notisync.protocol.SshKeyDescriptor
import net.extrawdw.notisync.protocol.SshProcessContext
import net.extrawdw.notisync.protocol.SshProcessContextSource
import net.extrawdw.notisync.protocol.SshSignatureAlgorithm
import net.extrawdw.notisync.protocol.SshSignRequest
import net.extrawdw.notisync.protocol.SshSignResultKind
import net.extrawdw.notisync.protocol.SshUserVerificationPolicy
import net.extrawdw.notisync.ssh.core.SshSignatureMethod
import net.extrawdw.notisync.ssh.core.SshSignatureVerifier

/**
 * Self-contained Settings → Advanced flow test for the SSH Agent Android key storage.
 *
 * Enumerates every combination of the three key-generation options (export copy, TEE-only export backend,
 * per-use biometric verification), generates one Ed25519 key per combination (named after the options it
 * used), signs a test payload through the real provider sign path — including the system authentication
 * prompts the production flow triggers — and independently re-verifies the SSH signature. Each key is
 * deleted after its test so the store is left as found; each sign leaves a terminal "flowtest" record in
 * the SSH Agent history, which is the same residue the production sign path always keeps.
 *
 * Deliberately isolated: this file talks only to the public [SshKeyProviderStore] API and owns its own
 * prompt plumbing, so the whole feature can be removed without touching the production flows. Delete this
 * file together with `SshKeyStorageFlowTestCard` in the ui package and its one Settings list item.
 */
class SshKeyStorageFlowTest(
    private val activity: Activity,
    private val store: SshKeyProviderStore,
    private val providerClientId: ClientId,
) {
    /** One enumerated key-generation option combination (export copy, TEE-only backend, per-use biometrics). */
    data class Case(
        val export: Boolean,
        val teeOnly: Boolean,
        val perUseBio: Boolean,
    ) {
        /** Key display name encoding exactly the options used, e.g. "flowtest export+tee+bio". */
        val name: String = "flowtest " + listOfNotNull(
            "export".takeIf { export },
            "tee".takeIf { teeOnly },
            "bio".takeIf { perUseBio },
        ).joinToString("+").ifEmpty { "plain" }

        val exportCopyBackendPolicy: SshExportCopyBackendPolicy =
            if (teeOnly) SshExportCopyBackendPolicy.TEE_ONLY else SshExportCopyBackendPolicy.BEST_AVAILABLE

        val userVerificationPolicy: SshUserVerificationPolicy =
            if (perUseBio) SshUserVerificationPolicy.PER_USE else SshUserVerificationPolicy.NONE

        companion object {
            /** All 2×2×2 option combinations, ordered so system prompts appear progressively. */
            val ALL: List<Case> = listOf(
                Case(export = false, teeOnly = false, perUseBio = false),
                Case(export = false, teeOnly = false, perUseBio = true),
                Case(export = false, teeOnly = true, perUseBio = false),
                Case(export = false, teeOnly = true, perUseBio = true),
                Case(export = true, teeOnly = false, perUseBio = false),
                Case(export = true, teeOnly = false, perUseBio = true),
                Case(export = true, teeOnly = true, perUseBio = false),
                Case(export = true, teeOnly = true, perUseBio = true),
            )
        }
    }

    sealed interface CaseOutcome {
        val case: Case

        data class Passed(override val case: Case, val detail: String) : CaseOutcome
        data class Failed(override val case: Case, val message: String) : CaseOutcome
    }

    /**
     * Runs every case sequentially. A system authentication prompt dismissed by the user fails the current
     * case and stops the whole run (the remaining cases would only prompt again). Unexpected failures fail
     * only their case and the run continues.
     */
    suspend fun run(onCase: (CaseOutcome) -> Unit) {
        for (case in Case.ALL) {
            val outcome = try {
                runCase(case)
            } catch (cancelled: FlowTestCancelled) {
                onCase(CaseOutcome.Failed(case, cancelled.message ?: "Authentication cancelled"))
                return
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                CaseOutcome.Failed(case, failure.message ?: failure.javaClass.simpleName)
            }
            onCase(outcome)
        }
    }

    private suspend fun runCase(case: Case): CaseOutcome {
        var descriptor: SshKeyDescriptor? = null
        try {
            descriptor = generateKey(case)
            signAndVerify(case, descriptor)
            return CaseOutcome.Passed(case, passedDetail(descriptor))
        } finally {
            descriptor?.let { key ->
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { store.deleteKey(key.providerKeyId) }
                }
            }
        }
    }

    /** Generates until the provisioning pipeline converges, running each system prompt it asks for. */
    private suspend fun generateKey(case: Case): SshKeyDescriptor {
        var prepared: PreparedSshKeyStorage? = null
        try {
            var result = withContext(Dispatchers.IO) {
                store.generateKey(
                    algorithm = SshKeyAlgorithm.SSH_ED25519,
                    displayName = case.name,
                    now = System.currentTimeMillis(),
                    allowExport = case.export,
                    exportCopyBackendPolicy = case.exportCopyBackendPolicy,
                    userVerificationPolicy = case.userVerificationPolicy,
                )
            }
            var stage = 0
            while (result is SshKeyStorageResult.AuthenticationRequired) {
                stage += 1
                check(stage <= MAX_PROVISIONING_STAGES) { "key provisioning did not converge" }
                prepared = result.prepared
                val authenticated = awaitStorageAuthentication(prepared)
                result = withContext(Dispatchers.IO) {
                    store.completePreparedKeyStorage(
                        authenticated.prepared,
                        authenticated.cipher,
                        authenticated.signature,
                    )
                }
                prepared = null
            }
            return (result as SshKeyStorageResult.Stored).descriptor
        } finally {
            prepared?.let { pending ->
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { store.cancelPreparedKeyStorage(pending) }
                }
            }
        }
    }

    /** Signs through the production request path (approve or biometric per-use) and verifies the blob. */
    private suspend fun signAndVerify(case: Case, descriptor: SshKeyDescriptor) {
        val now = System.currentTimeMillis()
        val request = SshSignRequest(
            requestId = randomHexId(),
            requesterClientId = FLOW_TEST_REQUESTER,
            requestedAt = now,
            expiresAt = now + SshAgentLimits.MAX_SIGN_LIFETIME_MILLIS,
            publicKeyBlob = descriptor.publicKeyBlob,
            data = "NotiSync SSH key-storage flow test: ${case.name}".encodeToByteArray(),
            flags = 0,
            requestedSignatureAlgorithm = SshSignatureAlgorithm.SSH_ED25519,
            eligibleProviderClientIds = listOf(providerClientId),
            authorizationGeneration = randomHexId(),
            authorizationEpoch = 1,
            processContext = SshProcessContext(source = SshProcessContextSource.UNAVAILABLE),
            destinationContext = SshDestinationContext(
                provenance = SshDestinationProvenance.UNKNOWN,
                connectionDirection = SshConnectionDirection.UNKNOWN,
            ),
            connectionId = randomHexId(),
        )
        val requestDigest = sha256(ProtocolCodec.encodeToCbor(request))
        val accepted = withContext(Dispatchers.IO) { store.acceptSign(request, now) }
        check(
            accepted == SshProviderAcceptResult.STORED || accepted == SshProviderAcceptResult.DUPLICATE,
        ) { "test sign request was not accepted: $accepted" }
        var terminated = false
        try {
            val result = if (descriptor.operationalKey.userVerificationPolicy == SshUserVerificationPolicy.PER_USE) {
                val prepared = withContext(Dispatchers.IO) {
                    store.prepareUserVerifiedSignature(request.requestId, providerClientId, now)
                } ?: error("test signing could not be prepared")
                var preparedFinished = false
                try {
                    val authenticated = awaitSignatureAuthentication(prepared)
                    withContext(Dispatchers.IO) {
                        store.completeUserVerifiedSignature(
                            prepared,
                            authenticated.signature,
                            authenticated.cipher,
                            providerClientId,
                            now,
                        )
                    }.also { preparedFinished = true }
                } finally {
                    if (!preparedFinished) {
                        withContext(NonCancellable + Dispatchers.IO) {
                            runCatching { store.cancelPreparedSignature(prepared) }
                        }
                    }
                }
            } else {
                withContext(Dispatchers.IO) { store.approve(request.requestId, providerClientId, now) }
            } ?: error("test signing did not produce a result")
            withContext(Dispatchers.IO) { store.markSent(request.requestId, now) }
            terminated = true
            val signatureBlob = result.signature?.signatureBlob
            check(result.kind == SshSignResultKind.SIGNED && signatureBlob != null) {
                "test signing failed: ${result.kind}" +
                    (result.failure?.code?.let { " / $it" } ?: result.rejection?.reason?.let { " / $it" } ?: "")
            }
            check(
                SshSignatureVerifier.verify(
                    publicKeyBlob = descriptor.publicKeyBlob,
                    data = request.data,
                    signatureBlob = signatureBlob,
                    expectedMethod = SshSignatureMethod.ED25519,
                ),
            ) { "the produced SSH signature did not verify" }
        } finally {
            if (!terminated) {
                // Never leave a pending-review row behind: reconcile() would notify for the synthetic requester.
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching {
                        when (store.find(request.requestId)?.state) {
                            SshProviderRequestState.PENDING_REVIEW ->
                                store.cancelSign(request.requestId, FLOW_TEST_REQUESTER, requestDigest, now)

                            SshProviderRequestState.RESPONSE_PENDING_SEND -> store.markSent(request.requestId, now)
                            else -> Unit
                        }
                    }
                }
            }
        }
    }

    private fun passedDetail(descriptor: SshKeyDescriptor): String = buildString {
        val operational = descriptor.operationalKey
        append("operational ").append(operational.provider.name)
            .append(" / ").append(operational.securityLevel.name)
        if (operational.strongBoxFallback) append(" (StrongBox→TEE fallback)")
        append(" / uv=").append(operational.userVerificationPolicy.name)
        append("; ")
        append(
            descriptor.exportCopy?.let {
                "export copy ${it.securityLevel.name} / ${it.backendPolicy.name} / ${it.authentication.name}"
            } ?: "no export copy",
        )
        append("; signed and verified")
    }

    /** System authentication for a prepared key-storage stage, suspending until the prompt settles. */
    private suspend fun awaitStorageAuthentication(prepared: PreparedSshKeyStorage): AuthenticatedStorage =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val handled = AtomicBoolean(false)
                val signal = CancellationSignal()
                continuation.invokeOnCancellation {
                    if (handled.compareAndSet(false, true)) signal.cancel()
                }
                val builder = BiometricPrompt.Builder(activity)
                    .setTitle("SSH key-storage flow test")
                    .setSubtitle("Authenticate to continue generating the test key")
                    .setAllowedAuthenticators(prepared.promptAuthenticators)
                if (prepared.promptAuthenticators and BiometricManager.Authenticators.DEVICE_CREDENTIAL == 0) {
                    builder.setNegativeButton("Cancel", activity.mainExecutor) { _, _ ->
                        if (handled.compareAndSet(false, true)) {
                            continuation.resumeWithException(FlowTestCancelled("Key-generation authentication cancelled"))
                        }
                    }
                }
                val cryptoObject = prepared.signature?.let(BiometricPrompt::CryptoObject)
                    ?: BiometricPrompt.CryptoObject(requireNotNull(prepared.cipher))
                try {
                    builder.build().authenticate(
                        cryptoObject,
                        signal,
                        activity.mainExecutor,
                        object : BiometricPrompt.AuthenticationCallback() {
                            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                                val authenticated = result.cryptoObject
                                if (authenticated == null) {
                                    if (handled.compareAndSet(false, true)) {
                                        continuation.resumeWithException(
                                            IllegalStateException("Authenticated key-storage operation was lost"),
                                        )
                                    }
                                    return
                                }
                                if (handled.compareAndSet(false, true)) {
                                    continuation.resume(
                                        AuthenticatedStorage(prepared, authenticated.cipher, authenticated.signature),
                                    )
                                }
                            }

                            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                                if (handled.compareAndSet(false, true)) {
                                    continuation.resumeWithException(FlowTestCancelled(errString.toString()))
                                }
                            }
                        },
                    )
                } catch (failure: Exception) {
                    if (handled.compareAndSet(false, true)) continuation.resumeWithException(failure)
                }
            }
        }

    /** System authentication for a per-use sign, suspending until the prompt settles. */
    private suspend fun awaitSignatureAuthentication(prepared: PreparedSshSignature): AuthenticatedSignature =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val handled = AtomicBoolean(false)
                val signal = CancellationSignal()
                continuation.invokeOnCancellation {
                    if (handled.compareAndSet(false, true)) signal.cancel()
                }
                val cryptoObject = prepared.signature?.let(BiometricPrompt::CryptoObject)
                    ?: BiometricPrompt.CryptoObject(requireNotNull(prepared.cipher))
                try {
                    BiometricPrompt.Builder(activity)
                        .setTitle("SSH key-storage flow test")
                        .setSubtitle("Authorize one test signature with the per-use key")
                        .setAllowedAuthenticators(SshAuthenticationPolicy.SIGNING_PROMPT_AUTHENTICATORS)
                        .setNegativeButton("Cancel", activity.mainExecutor) { _, _ ->
                            if (handled.compareAndSet(false, true)) {
                                continuation.resumeWithException(FlowTestCancelled("Signature authentication cancelled"))
                            }
                        }
                        .build()
                        .authenticate(
                            cryptoObject,
                            signal,
                            activity.mainExecutor,
                            object : BiometricPrompt.AuthenticationCallback() {
                                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                                    val authenticated = result.cryptoObject
                                    if (authenticated == null) {
                                        if (handled.compareAndSet(false, true)) {
                                            continuation.resumeWithException(
                                                IllegalStateException("Authenticated signing operation was lost"),
                                            )
                                        }
                                        return
                                    }
                                    if (handled.compareAndSet(false, true)) {
                                        continuation.resume(
                                            AuthenticatedSignature(authenticated.signature, authenticated.cipher),
                                        )
                                    }
                                }

                                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                                    if (handled.compareAndSet(false, true)) {
                                        continuation.resumeWithException(FlowTestCancelled(errString.toString()))
                                    }
                                }
                            },
                        )
                } catch (failure: Exception) {
                    if (handled.compareAndSet(false, true)) continuation.resumeWithException(failure)
                }
            }
        }

    private data class AuthenticatedStorage(
        val prepared: PreparedSshKeyStorage,
        val cipher: Cipher?,
        val signature: Signature?,
    )

    private data class AuthenticatedSignature(
        val signature: Signature?,
        val cipher: Cipher?,
    )

    /** A system prompt was dismissed by the user (or the device cannot run it): fail the case, stop the run. */
    private class FlowTestCancelled(message: String) : Exception(message)

    private companion object {
        val FLOW_TEST_REQUESTER = ClientId("flowtest")
        const val MAX_PROVISIONING_STAGES = 8
    }

    private val random = SecureRandom()

    private fun randomHexId(): String = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}

package net.extrawdw.apps.notisync.data.storage.protection

import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.InvalidAlgorithmParameterException
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

internal enum class ProtectedPayloadKeyBacking { TRUSTED_ENVIRONMENT, STRONGBOX }

internal data class ProtectedPayloadKeyInspection(
    val alias: String,
    val generation: Long,
    val backing: ProtectedPayloadKeyBacking,
    val keySizeBits: Int,
    val purposes: Int,
    val blockModes: Set<String>,
    val encryptionPaddings: Set<String>,
    val userAuthenticationRequired: Boolean,
)

internal enum class ProtectedPayloadKeyCreationDisposition { CREATED, ALREADY_PRESENT }

internal data class ProtectedPayloadKeyCreationResult(
    val disposition: ProtectedPayloadKeyCreationDisposition,
    val inspection: ProtectedPayloadKeyInspection,
)

internal enum class ProtectedPayloadKeyDeletionResult { DELETED, ALREADY_ABSENT }

internal data class ProtectedPayloadKeySelfTestResult(
    val inspection: ProtectedPayloadKeyInspection,
    val providerGeneratedDistinctNonces: Boolean,
    val callerSuppliedEncryptionNonceRejected: Boolean,
)

/**
 * Operational protected-payload vault. Lifecycle journaling is deliberately outside this class: the reconciler
 * first commits its Core CREATE/DELETE intent and then calls these idempotent, generation-scoped operations.
 * Normal protect/open access loads an existing key and never generates a replacement.
 */
internal class AndroidKeystoreProtectedPayloadVault : ProtectedPayloadCipher {
    @Synchronized
    fun exists(generation: Long): Boolean {
        requireWorkerThread(ProtectedPayloadOperation.CHECK_EXISTS)
        val alias = OperationalPayloadKeyAlias.forGeneration(
            generation,
            ProtectedPayloadOperation.CHECK_EXISTS,
        )
        return providerCall(ProtectedPayloadOperation.CHECK_EXISTS) {
            androidKeyStore().containsAlias(alias)
        }
    }

    /**
     * Idempotent for reconciler restart: an existing alias is validated and returned, never overwritten.
     * Generation requests do not ask for StrongBox so the hot/background key normally lands in the TEE; exact
     * TEE or StrongBox backing is accepted only after [KeyInfo.securityLevel] inspection.
     */
    @Synchronized
    fun create(generation: Long): ProtectedPayloadKeyCreationResult {
        requireWorkerThread(ProtectedPayloadOperation.CREATE_KEY)
        val alias = OperationalPayloadKeyAlias.forGeneration(generation, ProtectedPayloadOperation.CREATE_KEY)
        val store = providerCall(ProtectedPayloadOperation.CREATE_KEY, ::androidKeyStore)
        if (providerCall(ProtectedPayloadOperation.CREATE_KEY) { store.containsAlias(alias) }) {
            val loaded = loadAndValidate(alias, ProtectedPayloadOperation.CREATE_KEY, aliasConflict = true)
            return ProtectedPayloadKeyCreationResult(
                ProtectedPayloadKeyCreationDisposition.ALREADY_PRESENT,
                loaded.inspection,
            )
        }

        providerCall(ProtectedPayloadOperation.CREATE_KEY) {
            val spec = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setKeySize(KEY_SIZE_BITS)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                // Do not set unlocked-device-required: background drains must remain available after boot.
                .build()
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
                init(spec)
                generateKey()
            }
        }

        return ProtectedPayloadKeyCreationResult(
            ProtectedPayloadKeyCreationDisposition.CREATED,
            loadAndValidate(alias, ProtectedPayloadOperation.CREATE_KEY).inspection,
        )
    }

    @Synchronized
    fun validate(generation: Long): ProtectedPayloadKeyInspection {
        requireWorkerThread(ProtectedPayloadOperation.VALIDATE_KEY)
        val alias = OperationalPayloadKeyAlias.forGeneration(generation, ProtectedPayloadOperation.VALIDATE_KEY)
        return loadAndValidate(alias, ProtectedPayloadOperation.VALIDATE_KEY).inspection
    }

    @Synchronized
    fun delete(generation: Long): ProtectedPayloadKeyDeletionResult {
        requireWorkerThread(ProtectedPayloadOperation.DELETE_KEY)
        val alias = OperationalPayloadKeyAlias.forGeneration(generation, ProtectedPayloadOperation.DELETE_KEY)
        val store = providerCall(ProtectedPayloadOperation.DELETE_KEY, ::androidKeyStore)
        if (!providerCall(ProtectedPayloadOperation.DELETE_KEY) { store.containsAlias(alias) }) {
            return ProtectedPayloadKeyDeletionResult.ALREADY_ABSENT
        }
        providerCall(ProtectedPayloadOperation.DELETE_KEY) { store.deleteEntry(alias) }
        if (providerCall(ProtectedPayloadOperation.DELETE_KEY) { store.containsAlias(alias) }) {
            throw ProtectedPayloadException(
                code = ProtectedPayloadFailureCode.PROVIDER_FAILURE,
                operation = ProtectedPayloadOperation.DELETE_KEY,
                detail = "provider retained the deleted alias",
            )
        }
        return ProtectedPayloadKeyDeletionResult.DELETED
    }

    /**
     * Exercises provider-generated randomized nonces, authenticated round trip, and rejection of a caller-supplied
     * encryption nonce. Call only after durable CREATE/PENDING state and before marking the key applied/ready.
     */
    @Synchronized
    fun selfTest(generation: Long): ProtectedPayloadKeySelfTestResult {
        requireWorkerThread(ProtectedPayloadOperation.SELF_TEST_KEY)
        val alias = OperationalPayloadKeyAlias.forGeneration(generation, ProtectedPayloadOperation.SELF_TEST_KEY)
        val loaded = loadAndValidate(alias, ProtectedPayloadOperation.SELF_TEST_KEY)
        val plaintext = ByteArray(SELF_TEST_PLAINTEXT_BYTES) { index -> (index * 17 + 3).toByte() }
        val aad = "notisync-protected-payload-self-test-v1:$generation".encodeToByteArray()
        var first: ProtectedCiphertext? = null
        var second: ProtectedCiphertext? = null
        var opened: ByteArray? = null
        try {
            first = encrypt(loaded.key, plaintext, aad, ProtectedPayloadOperation.SELF_TEST_KEY)
            second = encrypt(loaded.key, plaintext, aad, ProtectedPayloadOperation.SELF_TEST_KEY)
            val distinct = !MessageDigest.isEqual(first.nonce, second.nonce) &&
                !MessageDigest.isEqual(first.ciphertext, second.ciphertext)
            if (!distinct) policyFailure(
                ProtectedPayloadOperation.SELF_TEST_KEY,
                "provider repeated a randomized GCM result",
            )

            opened = decrypt(
                loaded.key,
                first.nonce,
                first.ciphertext,
                aad,
                ProtectedPayloadOperation.SELF_TEST_KEY,
            )
            if (!MessageDigest.isEqual(plaintext, opened)) policyFailure(
                ProtectedPayloadOperation.SELF_TEST_KEY,
                "provider self-test round trip changed plaintext",
            )

            val callerNonceRejected = rejectsCallerSuppliedEncryptionNonce(loaded.key, first.nonce)
            if (!callerNonceRejected) policyFailure(
                ProtectedPayloadOperation.SELF_TEST_KEY,
                "provider accepted a caller-supplied GCM encryption nonce",
            )
            return ProtectedPayloadKeySelfTestResult(
                inspection = loaded.inspection,
                providerGeneratedDistinctNonces = true,
                callerSuppliedEncryptionNonceRejected = true,
            )
        } finally {
            plaintext.fill(0)
            aad.fill(0)
            first?.nonce?.fill(0)
            first?.ciphertext?.fill(0)
            second?.nonce?.fill(0)
            second?.ciphertext?.fill(0)
            opened?.fill(0)
        }
    }

    /** Existing-key access only. [OperationalProtectedPayloadProtector] performs role-specific prevalidation. */
    override fun protect(alias: String, plaintext: ByteArray, aad: ByteArray): ProtectedCiphertext {
        requireWorkerThread(ProtectedPayloadOperation.PROTECT)
        OperationalPayloadKeyAlias.generationOf(alias, ProtectedPayloadOperation.PROTECT)
        if (plaintext.size > MAX_ANY_PLAINTEXT_BYTES || aad.isEmpty()) {
            throw ProtectedPayloadException(
                code = ProtectedPayloadFailureCode.INVALID_INPUT,
                operation = ProtectedPayloadOperation.PROTECT,
                detail = "invalid plaintext or AAD",
            )
        }
        val loaded = loadAndValidate(alias, ProtectedPayloadOperation.PROTECT)
        return encrypt(loaded.key, plaintext, aad, ProtectedPayloadOperation.PROTECT)
    }

    /** Existing-key access only. Authentication failures never delete rows or create a replacement alias. */
    override fun open(
        alias: String,
        nonce: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        requireWorkerThread(ProtectedPayloadOperation.OPEN)
        OperationalPayloadKeyAlias.generationOf(alias, ProtectedPayloadOperation.OPEN)
        if (
            nonce.size != ProtectedPayloadFormat.NONCE_BYTES ||
            ciphertext.size !in ProtectedPayloadFormat.GCM_TAG_BYTES..MAX_ANY_CIPHERTEXT_BYTES ||
            aad.isEmpty()
        ) {
            throw ProtectedPayloadException(
                code = ProtectedPayloadFailureCode.INVALID_INPUT,
                operation = ProtectedPayloadOperation.OPEN,
                detail = "invalid nonce, ciphertext, or AAD",
            )
        }
        val loaded = loadAndValidate(alias, ProtectedPayloadOperation.OPEN)
        return decrypt(loaded.key, nonce, ciphertext, aad, ProtectedPayloadOperation.OPEN)
    }

    private fun encrypt(
        key: SecretKey,
        plaintext: ByteArray,
        aad: ByteArray,
        operation: ProtectedPayloadOperation,
    ): ProtectedCiphertext = providerCall(operation) {
        val cipher = Cipher.getInstance(AES_TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key)
            updateAAD(aad)
        }
        val nonce = requireNotNull(cipher.iv).copyOf()
        val ciphertext = cipher.doFinal(plaintext)
        if (
            nonce.size != ProtectedPayloadFormat.NONCE_BYTES ||
            ciphertext.size != plaintext.size + ProtectedPayloadFormat.GCM_TAG_BYTES
        ) {
            nonce.fill(0)
            ciphertext.fill(0)
            policyFailure(operation, "provider returned a non-canonical GCM result")
        }
        ProtectedCiphertext(nonce, ciphertext)
    }

    private fun decrypt(
        key: SecretKey,
        nonce: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray,
        operation: ProtectedPayloadOperation,
    ): ByteArray = providerCall(operation) {
        Cipher.getInstance(AES_TRANSFORMATION).run {
            init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(ProtectedPayloadFormat.GCM_TAG_BITS, nonce),
            )
            updateAAD(aad)
            doFinal(ciphertext)
        }
    }

    private fun rejectsCallerSuppliedEncryptionNonce(key: SecretKey, nonce: ByteArray): Boolean = try {
        providerCall(ProtectedPayloadOperation.SELF_TEST_KEY) {
            Cipher.getInstance(AES_TRANSFORMATION).init(
                Cipher.ENCRYPT_MODE,
                key,
                GCMParameterSpec(ProtectedPayloadFormat.GCM_TAG_BITS, nonce),
            )
        }
        false
    } catch (failure: ProtectedPayloadException) {
        if (failure.cause?.causeChain()?.any { it is InvalidAlgorithmParameterException } == true) {
            true
        } else {
            throw failure
        }
    }

    private fun loadAndValidate(
        alias: String,
        operation: ProtectedPayloadOperation,
        aliasConflict: Boolean = false,
    ): LoadedKey {
        val generation = OperationalPayloadKeyAlias.generationOf(alias, operation)
        val entry = providerCall(operation) { androidKeyStore().getEntry(alias, null) }
            ?: throw ProtectedPayloadException(
                code = ProtectedPayloadFailureCode.KEY_MISSING,
                operation = operation,
                detail = "required generation alias is absent",
            )
        if (entry !is KeyStore.SecretKeyEntry) {
            throw ProtectedPayloadException(
                code = if (aliasConflict) {
                    ProtectedPayloadFailureCode.KEY_ALIAS_CONFLICT
                } else {
                    ProtectedPayloadFailureCode.KEY_POLICY_VIOLATION
                },
                operation = operation,
                detail = "generation alias has the wrong key type",
            )
        }
        val key = entry.secretKey
        if (key.algorithm != KeyProperties.KEY_ALGORITHM_AES || key.encoded != null) {
            policyFailure(operation, "generation alias is not a non-exportable AES key")
        }
        val info = providerCall(operation) {
            SecretKeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                .getKeySpec(key, KeyInfo::class.java) as KeyInfo
        }
        val requiredPurposes = KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        if (info.keystoreAlias != alias) policyFailure(operation, "KeyInfo alias mismatch")
        if (info.origin != KeyProperties.ORIGIN_GENERATED) policyFailure(operation, "key was not provider-generated")
        if (info.keySize != KEY_SIZE_BITS) policyFailure(operation, "key is not AES-256")
        if (info.purposes != requiredPurposes) policyFailure(operation, "key purposes are not encrypt/decrypt-only")
        if (info.blockModes.toSet() != setOf(KeyProperties.BLOCK_MODE_GCM)) {
            policyFailure(operation, "key block mode is not exactly GCM")
        }
        if (info.encryptionPaddings.toSet() != setOf(KeyProperties.ENCRYPTION_PADDING_NONE)) {
            policyFailure(operation, "key padding is not exactly NoPadding")
        }
        if (info.isUserAuthenticationRequired) policyFailure(operation, "key unexpectedly requires user auth")
        if (info.isUserConfirmationRequired) policyFailure(operation, "key unexpectedly requires confirmation")
        if (info.isTrustedUserPresenceRequired) policyFailure(operation, "key unexpectedly requires user presence")
        if (
            info.keyValidityStart != null ||
            info.keyValidityForOriginationEnd != null ||
            info.keyValidityForConsumptionEnd != null
        ) policyFailure(operation, "key unexpectedly has a validity window")
        if (info.remainingUsageCount != KeyProperties.UNRESTRICTED_USAGE_COUNT) {
            policyFailure(operation, "key unexpectedly has a usage-count limit")
        }
        val backing = when (info.securityLevel) {
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT ->
                ProtectedPayloadKeyBacking.TRUSTED_ENVIRONMENT
            KeyProperties.SECURITY_LEVEL_STRONGBOX -> ProtectedPayloadKeyBacking.STRONGBOX
            else -> policyFailure(operation, "key is not exactly TEE/StrongBox-backed")
        }
        return LoadedKey(
            key = key,
            inspection = ProtectedPayloadKeyInspection(
                alias = alias,
                generation = generation,
                backing = backing,
                keySizeBits = info.keySize,
                purposes = info.purposes,
                blockModes = info.blockModes.toSet(),
                encryptionPaddings = info.encryptionPaddings.toSet(),
                userAuthenticationRequired = info.isUserAuthenticationRequired,
            ),
        )
    }

    private fun androidKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun requireWorkerThread(operation: ProtectedPayloadOperation) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw ProtectedPayloadException(
                code = ProtectedPayloadFailureCode.WRONG_THREAD,
                operation = operation,
                detail = "Android Keystore access must run off the main thread",
            )
        }
    }

    private inline fun <T> providerCall(
        operation: ProtectedPayloadOperation,
        block: () -> T,
    ): T = try {
        block()
    } catch (failure: ProtectedPayloadException) {
        throw failure
    } catch (failure: Exception) {
        val root = failure.causeChain()
        val code = when {
            root.any { it is KeyPermanentlyInvalidatedException } ->
                ProtectedPayloadFailureCode.KEY_INVALIDATED
            root.any { it is AEADBadTagException || it is BadPaddingException } ->
                ProtectedPayloadFailureCode.AUTHENTICATION_FAILED
            else -> ProtectedPayloadFailureCode.PROVIDER_FAILURE
        }
        throw ProtectedPayloadException(
            code = code,
            operation = operation,
            providerFailure = ProtectedPayloadProviderFailure.from(failure),
            cause = failure,
        )
    }

    private fun policyFailure(operation: ProtectedPayloadOperation, detail: String): Nothing =
        throw ProtectedPayloadException(
            code = ProtectedPayloadFailureCode.KEY_POLICY_VIOLATION,
            operation = operation,
            detail = detail,
        )

    private data class LoadedKey(
        val key: SecretKey,
        val inspection: ProtectedPayloadKeyInspection,
    )

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256
        const val SELF_TEST_PLAINTEXT_BYTES = 32
        const val MAX_ANY_PLAINTEXT_BYTES =
            ProtectedPayloadStoragePolicy.SEAL_PENDING_MAX_PLAINTEXT_BYTES
        const val MAX_ANY_CIPHERTEXT_BYTES =
            ProtectedPayloadStoragePolicy.SEAL_PENDING_MAX_CIPHERTEXT_BYTES
    }
}

private fun Throwable.causeChain(): Sequence<Throwable> = sequence {
    val seen = mutableSetOf<Throwable>()
    var current: Throwable? = this@causeChain
    while (current != null && seen.add(current)) {
        yield(current)
        current = current.cause
    }
}

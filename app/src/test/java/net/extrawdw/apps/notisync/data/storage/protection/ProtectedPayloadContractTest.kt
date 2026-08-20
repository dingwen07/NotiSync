package net.extrawdw.apps.notisync.data.storage.protection

import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProtectedPayloadContractTest {
    @Test
    fun canonicalAadV1MatchesGoldenBytes() {
        val aad = CanonicalProtectedPayloadAadV1.encode(
            binding = ProtectedPayloadBinding.sealResponse("request-123"),
            protectionVersion = 1,
            generation = 7,
            payloadCodecVersion = 1,
        )

        assertEquals(
            "4e53505041414431000000010000000100000018616e64726f69645f6b657973746f72655f" +
                "6165735f67636d0000000100000000000000070000002e6e65742e65787472617764772e61" +
                "7070732e6e6f746973796e632e73746f726167652e6f7065726174696f6e616c0000000100" +
                "00001d7365616c5f726573706f6e73655f637573746f64792e7061796c6f61640000000100" +
                "00000a726571756573745f6964000000010000000b726571756573742d3132330000000300" +
                "00000d7365616c5f726573706f6e73650000002c6e6f746973796e632e70726f746f636f6c" +
                "2e6f70656e7067705f7369676e2e726573706f6e73652e63626f7200000001",
            aad.toHex(),
        )
    }

    @Test
    fun aadBindsDomainEveryRowKeyRoleAndCodecVersion() {
        fun encode(binding: ProtectedPayloadBinding, codecVersion: Int = 1) =
            CanonicalProtectedPayloadAadV1.encode(binding, 1, 4, codecVersion)

        val response = ProtectedPayloadBinding.sealResponse("id-1")
        val changedId = ProtectedPayloadBinding.sealResponse("id-2")
        val seal = ProtectedPayloadBinding.sealPending("id-1")

        assertFalse(MessageDigest.isEqual(encode(response), encode(changedId)))
        assertFalse(MessageDigest.isEqual(encode(response), encode(seal)))
        assertFalse(MessageDigest.isEqual(encode(response), encode(response, codecVersion = 2)))
    }

    @Test
    fun authenticatedOpenRejectsCrossDomainAndRowSubstitution() {
        val protector = OperationalProtectedPayloadProtector(JvmAesGcmCipher())
        val original = ProtectedPayloadBinding.sealResponse("id-1")
        val protected = protector.protect("private control".encodeToByteArray(), original, generation = 9)

        val changedRow = ProtectedPayloadBinding.sealResponse("id-2")
        expectFailure(ProtectedPayloadFailureCode.AUTHENTICATION_FAILED) {
            protector.open(protected, changedRow)
        }
        expectFailure(ProtectedPayloadFailureCode.AUTHENTICATION_FAILED) {
            protector.open(protected, ProtectedPayloadBinding.sealPending("id-1"))
        }
    }

    @Test
    fun openRejectsUnknownFormatsBeforeCipherAccess() {
        val cipher = CountingCipher()
        val protector = OperationalProtectedPayloadProtector(cipher)
        val binding = ProtectedPayloadBinding.sealPending("request-1")
        val valid = validStoredPayload(binding)

        expectFailure(ProtectedPayloadFailureCode.UNSUPPORTED_SCHEME) {
            protector.open(valid.copyForTest(scheme = "future_scheme"), binding)
        }
        expectFailure(ProtectedPayloadFailureCode.UNSUPPORTED_PROTECTION_VERSION) {
            protector.open(valid.copyForTest(protectionVersion = 2), binding)
        }
        expectFailure(ProtectedPayloadFailureCode.UNSUPPORTED_PAYLOAD_CODEC_VERSION) {
            protector.open(valid.copyForTest(payloadCodecVersion = 2), binding)
        }
        expectFailure(ProtectedPayloadFailureCode.KEY_REFERENCE_MISMATCH) {
            protector.open(valid.copyForTest(keyRef = OperationalPayloadKeyAlias.forGeneration(2)), binding)
        }
        assertEquals(0, cipher.openCalls)
    }

    @Test
    fun roleRegistryHasStableUniqueValuesAndExactTagOverhead() {
        assertEquals(640 * 1024, ProtectedPayloadStoragePolicy.SEAL_PENDING_MAX_PLAINTEXT_BYTES)
        assertEquals(192 * 1024, ProtectedPayloadStoragePolicy.SEAL_RESPONSE_MAX_PLAINTEXT_BYTES)
        assertEquals(384 * 1024, ProtectedPayloadStoragePolicy.SSH_REQUEST_MAX_PLAINTEXT_BYTES)
        assertEquals(128 * 1024, ProtectedPayloadStoragePolicy.SSH_RESPONSE_MAX_PLAINTEXT_BYTES)
        assertEquals(384 * 1024, ProtectedPayloadStoragePolicy.SSH_HISTORY_MAX_PLAINTEXT_BYTES)
        assertEquals(64 * 1024, ProtectedPayloadStoragePolicy.DISPLAY_MAX_PLAINTEXT_BYTES)
        assertEquals(8 * 1024, ProtectedPayloadStoragePolicy.SEAL_ENROLLMENT_MAX_PLAINTEXT_BYTES)
        assertEquals(
            ProtectedPayloadRole.entries.size,
            ProtectedPayloadRole.entries.map(ProtectedPayloadRole::code).distinct().size,
        )
        assertEquals(
            ProtectedPayloadRole.entries.size,
            ProtectedPayloadRole.entries.map(ProtectedPayloadRole::token).distinct().size,
        )
        ProtectedPayloadRole.entries.forEach { role ->
            assertEquals(
                role.maxPlaintextBytes + ProtectedPayloadFormat.GCM_TAG_BYTES,
                role.maxCiphertextBytes,
            )
        }
    }

    @Test
    fun protectAndOpenEnforceRoleBoundsBeforeProviderAccess() {
        val cipher = CountingCipher()
        val protector = OperationalProtectedPayloadProtector(cipher)
        val binding = ProtectedPayloadBinding.sealPending("request-1")
        val maximum = ByteArray(binding.role.maxPlaintextBytes)
        protector.protect(maximum, binding, generation = 1)
        assertEquals(1, cipher.protectCalls)

        expectFailure(ProtectedPayloadFailureCode.PAYLOAD_BOUNDS_EXCEEDED) {
            protector.protect(ByteArray(binding.role.maxPlaintextBytes + 1), binding, generation = 1)
        }
        expectFailure(ProtectedPayloadFailureCode.PAYLOAD_BOUNDS_EXCEEDED) {
            protector.open(
                ProtectedPayload.fromStorage(
                    scheme = ProtectedPayloadFormat.SCHEME,
                    protectionVersion = 1,
                    generation = 1,
                    keyRef = OperationalPayloadKeyAlias.forGeneration(1),
                    payloadCodecVersion = 1,
                    nonce = ByteArray(12),
                    ciphertext = ByteArray(binding.role.maxCiphertextBytes + 1),
                ),
                binding,
            )
        }
        assertEquals(0, cipher.openCalls)
    }

    @Test
    fun bindingRejectsInvalidSingletonRowIdentity() {
        expectFailure(ProtectedPayloadFailureCode.INVALID_INPUT) {
            ProtectedPayloadBinding.sealEnrollment(singletonId = 2)
        }
    }

    @Test
    fun SealEnrollmentBindingIsDomainRoleRowAndGenerationBound() {
        val enrollment = CanonicalProtectedPayloadAadV1.encode(
            ProtectedPayloadBinding.sealEnrollment(),
            protectionVersion = 1,
            generation = 3,
            payloadCodecVersion = 1,
        )
        val display = CanonicalProtectedPayloadAadV1.encode(
            ProtectedPayloadBinding.sealDisplay("1"),
            protectionVersion = 1,
            generation = 3,
            payloadCodecVersion = 1,
        )
        val otherGeneration = CanonicalProtectedPayloadAadV1.encode(
            ProtectedPayloadBinding.sealEnrollment(),
            protectionVersion = 1,
            generation = 4,
            payloadCodecVersion = 1,
        )

        assertFalse(MessageDigest.isEqual(enrollment, display))
        assertFalse(MessageDigest.isEqual(enrollment, otherGeneration))
        assertEquals(8, ProtectedPayloadRole.SEAL_ENROLLMENT.code)
        assertEquals(7, ProtectedPayloadDomain.SEAL_ENROLLMENT_PROTECTED.code)
    }

    @Test
    fun aliasesAreDeterministicCanonicalAndGenerationScoped() {
        val generation0 = OperationalPayloadKeyAlias.forGeneration(0)
        val generation1 = OperationalPayloadKeyAlias.forGeneration(1)
        assertNotEquals(generation0, generation1)
        assertTrue(generation1.contains("storage.operational"))
        assertTrue(generation1.contains("protected_payload"))
        assertTrue(generation1.contains("aes_gcm_v1"))
        assertEquals(1, OperationalPayloadKeyAlias.generationOf(generation1))
        expectFailure(ProtectedPayloadFailureCode.INVALID_INPUT) {
            OperationalPayloadKeyAlias.generationOf("$generation0${0}")
        }
    }

    @Test
    fun opaqueModelCopiesArraysAndRedactsBytes() {
        val nonce = ByteArray(12) { 1 }
        val ciphertext = ByteArray(32) { 2 }
        val payload = ProtectedPayload.fromStorage(
            scheme = "attacker-controlled-unknown-scheme",
            protectionVersion = 1,
            generation = 3,
            keyRef = OperationalPayloadKeyAlias.forGeneration(3),
            payloadCodecVersion = 1,
            nonce = nonce,
            ciphertext = ciphertext,
        )
        nonce.fill(9)
        ciphertext.fill(9)
        assertArrayEquals(ByteArray(12) { 1 }, payload.nonceCopy())
        assertArrayEquals(ByteArray(32) { 2 }, payload.ciphertextCopy())
        val exposed = payload.ciphertextCopy().also { it.fill(7) }
        assertFalse(MessageDigest.isEqual(exposed, payload.ciphertextCopy()))
        assertFalse(payload.toString().contains("["))
        assertFalse(payload.toString().contains(payload.keyRef))
        assertFalse(payload.toString().contains(payload.scheme))
    }

    private fun validStoredPayload(binding: ProtectedPayloadBinding): ProtectedPayload =
        ProtectedPayload.fromStorage(
            scheme = ProtectedPayloadFormat.SCHEME,
            protectionVersion = 1,
            generation = 1,
            keyRef = OperationalPayloadKeyAlias.forGeneration(1),
            payloadCodecVersion = 1,
            nonce = ByteArray(12),
            ciphertext = ByteArray(16),
        ).also { OperationalProtectedPayloadContract.validateOpen(it, binding) }

    private fun ProtectedPayload.copyForTest(
        scheme: String = this.scheme,
        protectionVersion: Int = this.protectionVersion,
        generation: Long = this.generation,
        keyRef: String = this.keyRef,
        payloadCodecVersion: Int = this.payloadCodecVersion,
    ): ProtectedPayload = ProtectedPayload.fromStorage(
        scheme = scheme,
        protectionVersion = protectionVersion,
        generation = generation,
        keyRef = keyRef,
        payloadCodecVersion = payloadCodecVersion,
        nonce = nonceCopy(),
        ciphertext = ciphertextCopy(),
    )

    private fun expectFailure(
        code: ProtectedPayloadFailureCode,
        block: () -> Unit,
    ): ProtectedPayloadException {
        try {
            block()
            fail("Expected protected-payload failure $code")
        } catch (failure: ProtectedPayloadException) {
            assertEquals(code, failure.code)
            return failure
        }
        error("unreachable")
    }

    private class CountingCipher : ProtectedPayloadCipher {
        var protectCalls = 0
        var openCalls = 0

        override fun protect(alias: String, plaintext: ByteArray, aad: ByteArray): ProtectedCiphertext {
            protectCalls++
            return ProtectedCiphertext(
                nonce = ByteArray(ProtectedPayloadFormat.NONCE_BYTES),
                ciphertext = ByteArray(plaintext.size + ProtectedPayloadFormat.GCM_TAG_BYTES),
            )
        }

        override fun open(
            alias: String,
            nonce: ByteArray,
            ciphertext: ByteArray,
            aad: ByteArray,
        ): ByteArray {
            openCalls++
            return ByteArray(ciphertext.size - ProtectedPayloadFormat.GCM_TAG_BYTES)
        }
    }

    private class JvmAesGcmCipher : ProtectedPayloadCipher {
        private val key = SecretKeySpec(ByteArray(32) { index -> (index + 1).toByte() }, "AES")
        private val counter = AtomicInteger()

        override fun protect(alias: String, plaintext: ByteArray, aad: ByteArray): ProtectedCiphertext {
            val nonce = ByteArray(12)
            val value = counter.incrementAndGet()
            nonce[8] = (value ushr 24).toByte()
            nonce[9] = (value ushr 16).toByte()
            nonce[10] = (value ushr 8).toByte()
            nonce[11] = value.toByte()
            val ciphertext = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
                updateAAD(aad)
                doFinal(plaintext)
            }
            return ProtectedCiphertext(nonce, ciphertext)
        }

        override fun open(
            alias: String,
            nonce: ByteArray,
            ciphertext: ByteArray,
            aad: ByteArray,
        ): ByteArray = try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
                updateAAD(aad)
                doFinal(ciphertext)
            }
        } catch (failure: AEADBadTagException) {
            throw ProtectedPayloadException(
                code = ProtectedPayloadFailureCode.AUTHENTICATION_FAILED,
                operation = ProtectedPayloadOperation.OPEN,
                providerFailure = ProtectedPayloadProviderFailure.from(failure),
                cause = failure,
            )
        }
    }
}

private fun ByteArray.toHex(): String = joinToString("") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}

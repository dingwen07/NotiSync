package net.extrawdw.apps.notisync.data.storage.protection

import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class AndroidKeystoreProtectedPayloadVaultTest {
    @Test
    fun hardwareKeyLifecycleRestartRoundTripTamperAndMissingKeyAreFailClosed() = runBlocking {
        withContext(Dispatchers.IO) {
            val generation = (System.nanoTime() and Long.MAX_VALUE).coerceAtLeast(1)
            val vault = AndroidKeystoreProtectedPayloadVault()
            var created = false
            try {
                assertFalse(vault.exists(generation))
                val binding = ProtectedPayloadBinding.sealResponse("instrumentation-$generation")
                val protector = OperationalProtectedPayloadProtector(vault)
                val plaintext = "device-bound protected payload".encodeToByteArray()

                expectFailure(ProtectedPayloadFailureCode.KEY_MISSING) {
                    protector.protect(plaintext, binding, generation)
                }
                assertFalse(vault.exists(generation))

                val creation = vault.create(generation)
                created = true
                assertEquals(ProtectedPayloadKeyCreationDisposition.CREATED, creation.disposition)
                assertHardwarePolicy(creation.inspection, generation)

                val selfTest = vault.selfTest(generation)
                assertHardwarePolicy(selfTest.inspection, generation)
                assertTrue(selfTest.providerGeneratedDistinctNonces)
                assertTrue(selfTest.callerSuppliedEncryptionNonceRejected)

                // A new vault instance models a restarted reconciler/runtime. Create is idempotent and validates
                // the existing alias instead of replacing it.
                val restartedVault = AndroidKeystoreProtectedPayloadVault()
                val restartedCreation = restartedVault.create(generation)
                assertEquals(
                    ProtectedPayloadKeyCreationDisposition.ALREADY_PRESENT,
                    restartedCreation.disposition,
                )
                assertHardwarePolicy(restartedVault.validate(generation), generation)

                val restartedProtector = OperationalProtectedPayloadProtector(restartedVault)
                val first = restartedProtector.protect(plaintext, binding, generation)
                val second = restartedProtector.protect(plaintext, binding, generation)
                assertFalse(MessageDigest.isEqual(first.nonceCopy(), second.nonceCopy()))
                assertArrayEquals(plaintext, restartedProtector.open(first, binding))

                val tamperedBytes = first.ciphertextCopy().also { bytes ->
                    bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
                }
                val tampered = ProtectedPayload.fromStorage(
                    scheme = first.scheme,
                    protectionVersion = first.protectionVersion,
                    generation = first.generation,
                    keyRef = first.keyRef,
                    payloadCodecVersion = first.payloadCodecVersion,
                    nonce = first.nonceCopy(),
                    ciphertext = tamperedBytes,
                )
                expectFailure(ProtectedPayloadFailureCode.AUTHENTICATION_FAILED) {
                    restartedProtector.open(tampered, binding)
                }
                expectFailure(ProtectedPayloadFailureCode.AUTHENTICATION_FAILED) {
                    restartedProtector.open(
                        first,
                        ProtectedPayloadBinding.sealResponse("substituted-$generation"),
                    )
                }

                assertEquals(ProtectedPayloadKeyDeletionResult.DELETED, restartedVault.delete(generation))
                created = false
                assertFalse(restartedVault.exists(generation))
                expectFailure(ProtectedPayloadFailureCode.KEY_MISSING) {
                    restartedProtector.open(first, binding)
                }
                assertFalse(restartedVault.exists(generation))
            } finally {
                if (created) runCatching { vault.delete(generation) }
            }
        }
    }

    private fun assertHardwarePolicy(inspection: ProtectedPayloadKeyInspection, generation: Long) {
        assertEquals(OperationalPayloadKeyAlias.forGeneration(generation), inspection.alias)
        assertEquals(generation, inspection.generation)
        assertTrue(
            inspection.backing == ProtectedPayloadKeyBacking.TRUSTED_ENVIRONMENT ||
                inspection.backing == ProtectedPayloadKeyBacking.STRONGBOX,
        )
        assertEquals(256, inspection.keySizeBits)
        assertFalse(inspection.userAuthenticationRequired)
    }

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
}

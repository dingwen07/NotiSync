package net.extrawdw.apps.notisync.data.storage.importer.target

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import net.extrawdw.apps.notisync.data.storage.protection.OperationalProtectedPayloadProtector
import net.extrawdw.apps.notisync.data.storage.protection.ProtectedCiphertext
import net.extrawdw.apps.notisync.data.storage.protection.ProtectedPayloadCipher
import net.extrawdw.apps.notisync.data.storage.protection.ProtectedPayloadFormat
import net.extrawdw.apps.notisync.data.storage.protection.ProtectedPayloadStoragePolicy
import net.extrawdw.apps.notisync.data.storage.runtime.OperationalPayloadKeyVault
import net.extrawdw.apps.notisync.data.storage.runtime.OperationalStorageMaintenanceGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SealImportPayloadMaterializerTest {
    @Test
    fun maximumEnrollmentFixtureFitsReviewedBoundAndSelfTestsBeforeReturning() = runTest {
        val material = SealEnrollmentImportMaterial(
            providerId = "p".repeat(256),
            providerKeyReference = "r".repeat(256),
            primaryKeyId = "89ABCDEF01234567",
            displayIdentity = "x".repeat(4 * 1024),
            enrolledAt = 1234,
        )
        val encoded = CanonicalSealImportPayloadCodecV1.encodeEnrollment(material)
        CanonicalSealImportPayloadCodecV1.validateEnrollment(encoded)
        val cipher = CopyingCipher()

        val protected = materializer(cipher).protectEnrollment(material, operationalGeneration = 3)

        assertTrue(encoded.size <= ProtectedPayloadStoragePolicy.SEAL_ENROLLMENT_MAX_PLAINTEXT_BYTES)
        assertEquals(1, cipher.protectCalls)
        assertEquals(1, cipher.openCalls)
        assertEquals(listOf(3L), cipher.vault.createdGenerations)
        assertEquals(listOf(3L), cipher.vault.selfTestedGenerations)
        assertEquals(3L, protected.generation)
        assertFalse(material.toString().contains(material.providerKeyReference))
        encoded.fill(0)
    }

    @Test
    fun oversizedCombinedDisplayDropsTrailingHeadersDeterministicallyAndVerifiesPlaintext() = runTest {
        val headers = (0 until 64).map { ordinal ->
            SealDisplayHeaderImportMaterial("header-$ordinal", "v".repeat(2 * 1024))
        }
        val material = SealTerminalDisplayImportMaterial(
            primaryKeyId = "89ABCDEF01234567",
            workingDirectory = "/private/repository",
            commit = SealCommitDisplayImportMaterial(
                treeId = "a".repeat(40),
                parentIds = listOf("b".repeat(40)),
                author = "Private Author",
                committer = "Private Committer",
                message = "private message",
                extraHeaders = headers,
                payloadBytes = 42,
                truncated = false,
            ),
        )
        val cipher = CopyingCipher()
        val materializer = materializer(cipher)

        val protected = materializer.protectDisplay("request-1", material, operationalGeneration = 3)

        assertTrue(protected.truncated)
        assertTrue(
            protected.payload.ciphertextSize <=
                ProtectedPayloadStoragePolicy.DISPLAY_MAX_PLAINTEXT_BYTES + ProtectedPayloadFormat.GCM_TAG_BYTES,
        )
        assertTrue(
            materializer.verifyDisplay(
                "request-1",
                protected.payload,
                protected.plaintextDigest.copyBytes(),
            ),
        )
        assertFalse(
            materializer.verifyDisplay(
                "request-1",
                protected.payload,
                ByteArray(ImportDigest.BYTES) { 9 },
            ),
        )
        assertFalse(material.toString().contains("/private/repository"))
    }

    @Test
    fun invalidAttemptGenerationFailsClosedBeforeCipherAndNeverFallsBackToPlaintext() = runTest {
        val cipher = CopyingCipher()
        val materializer = SealImportPayloadMaterializer(
            protector = OperationalProtectedPayloadProtector(cipher),
            payloadKeyVault = cipher.vault,
            maintenanceGate = OperationalStorageMaintenanceGate(),
            ioDispatcher = Dispatchers.Unconfined,
        )

        val failure = try {
            materializer.protectEnrollment(
                SealEnrollmentImportMaterial("provider", "reference", "89ABCDEF01234567", "identity", 1),
                operationalGeneration = 0,
            )
            fail("missing generation must fail closed")
            error("unreachable")
        } catch (expected: OperationalImportFailure) {
            expected
        }

        assertEquals(ImportFailureDisposition.BLOCKED, failure.disposition)
        assertEquals("seal_payload_encoding_invalid", failure.errorCode)
        assertEquals(0, cipher.protectCalls)
        assertTrue(cipher.vault.createdGenerations.isEmpty())
    }

    @Test
    fun sameGenerationIsPreparedOncePerProcessAndARecreatedMaterializerReconcilesTheSameAlias() = runTest {
        val cipher = CopyingCipher()
        val enrollment = SealEnrollmentImportMaterial(
            "provider",
            "reference",
            "89ABCDEF01234567",
            "identity",
            1,
        )

        val first = materializer(cipher)
        val firstPayload = first.protectEnrollment(enrollment, operationalGeneration = 1)
        val secondPayload = first.protectEnrollment(enrollment, operationalGeneration = 1)
        val afterRestart = materializer(cipher).protectEnrollment(enrollment, operationalGeneration = 1)

        assertEquals(firstPayload.keyRef, secondPayload.keyRef)
        assertEquals(firstPayload.keyRef, afterRestart.keyRef)
        assertEquals(listOf(1L, 1L), cipher.vault.createdGenerations)
        assertEquals(listOf(1L, 1L), cipher.vault.selfTestedGenerations)
    }

    private fun materializer(cipher: CopyingCipher) = SealImportPayloadMaterializer(
        protector = OperationalProtectedPayloadProtector(cipher),
        payloadKeyVault = cipher.vault,
        maintenanceGate = OperationalStorageMaintenanceGate(),
        ioDispatcher = Dispatchers.Unconfined,
    )

    private class CopyingCipher : ProtectedPayloadCipher {
        val vault = RecordingVault()
        var protectCalls = 0
        var openCalls = 0

        override fun protect(alias: String, plaintext: ByteArray, aad: ByteArray): ProtectedCiphertext {
            protectCalls++
            return ProtectedCiphertext(
                nonce = ByteArray(ProtectedPayloadFormat.NONCE_BYTES) { 7 },
                ciphertext = plaintext.copyOf(plaintext.size + ProtectedPayloadFormat.GCM_TAG_BYTES),
            )
        }

        override fun open(
            alias: String,
            nonce: ByteArray,
            ciphertext: ByteArray,
            aad: ByteArray,
        ): ByteArray {
            openCalls++
            return ciphertext.copyOf(ciphertext.size - ProtectedPayloadFormat.GCM_TAG_BYTES)
        }
    }

    private class RecordingVault : OperationalPayloadKeyVault {
        val createdGenerations = mutableListOf<Long>()
        val selfTestedGenerations = mutableListOf<Long>()

        override suspend fun create(generation: Long) {
            createdGenerations += generation
        }

        override suspend fun selfTest(generation: Long) {
            selfTestedGenerations += generation
        }
    }
}

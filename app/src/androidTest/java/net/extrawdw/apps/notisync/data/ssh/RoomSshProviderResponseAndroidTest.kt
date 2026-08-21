package net.extrawdw.apps.notisync.data.ssh

import android.content.Context
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDatabase
import net.extrawdw.apps.notisync.data.storage.protection.OperationalProtectedPayloadProtector
import net.extrawdw.apps.notisync.data.storage.protection.ProtectedCiphertext
import net.extrawdw.apps.notisync.data.storage.protection.ProtectedPayloadCipher
import net.extrawdw.apps.notisync.data.storage.runtime.OperationalGenerationSource
import net.extrawdw.apps.notisync.data.storage.runtime.OperationalPayloadKeyEnsurer
import net.extrawdw.apps.notisync.data.storage.runtime.OperationalPayloadKeyVault
import net.extrawdw.apps.notisync.data.storage.runtime.OperationalStorageMaintenanceGate
import net.extrawdw.apps.notisync.sshagent.SshProviderAcceptResult
import net.extrawdw.apps.notisync.sshagent.SshProviderRequestState
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.DesktopProcessContext
import net.extrawdw.notisync.protocol.DesktopProcessContextSource
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.SshConnectionDirection
import net.extrawdw.notisync.protocol.SshDestinationContext
import net.extrawdw.notisync.protocol.SshDestinationProvenance
import net.extrawdw.notisync.protocol.SshSignRequest
import net.extrawdw.notisync.protocol.SshSignResult
import net.extrawdw.notisync.protocol.SshSignResultKind
import net.extrawdw.notisync.protocol.SshSignatureAlgorithm
import net.extrawdw.notisync.protocol.SshUserRejectionReason
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomSshProviderResponseAndroidTest {
    @Test
    fun rejectedRequestRemainsSendableUntilResponseIsAccepted() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder<OperationalDatabase>(context)
            .setDriver(AndroidSQLiteDriver())
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()
        try {
            val gate = OperationalStorageMaintenanceGate()
            val repository = RoomSshProviderRepository(
                context = context,
                database = database,
                protector = OperationalProtectedPayloadProtector(FixtureCipher()),
                payloadKeyEnsurer = OperationalPayloadKeyEnsurer(
                    generationSource = OperationalGenerationSource { 1 },
                    vault = NoOpPayloadKeyVault,
                    maintenanceGate = gate,
                ),
                now = { 1 },
            )
            val request = signRequest()
            val provider = ClientId("android-provider")

            assertEquals(SshProviderAcceptResult.STORED, repository.acceptSign(request, 1))
            assertTrue(repository.reject(request.requestId, provider, 2))
            assertEquals(SshProviderRequestState.RESPONSE_PENDING_SEND, repository.find(request.requestId)?.state)
            assertEquals(listOf(request.requestId), repository.pendingResponses().map { it.requestId })

            val prepared = repository.prepareResponse(request.requestId, 3)
            assertNotNull(prepared)
            prepared!!
            assertFalse(prepared.durableCustody)
            val result = ProtocolCodec.decodeFromCbor<SshSignResult>(prepared.encodedBody)
            assertEquals(SshSignResultKind.REJECTED_BY_USER, result.kind)
            assertEquals(request.requestId, result.requestId)
            assertEquals(request.requesterClientId, result.requesterClientId)
            assertEquals(provider, result.providerClientId)
            assertArrayEquals(
                MessageDigest.getInstance("SHA-256").digest(request.publicKeyBlob),
                result.publicKeyBlobSha256,
            )
            assertEquals(SshUserRejectionReason.USER_TAPPED_REJECT, result.rejection?.reason)
            assertEquals(null, result.validationError())
            assertTrue(repository.completeResponse(prepared, 4))
            assertEquals(SshProviderRequestState.SENT, repository.find(request.requestId)?.state)
            assertTrue(repository.pendingResponses().isEmpty())
        } finally {
            database.close()
        }
    }

    private fun signRequest() = SshSignRequest(
        requestId = "2d81afaed24238ba81d8d1c7218dea36",
        requesterClientId = ClientId("desktop-requester"),
        requestedAt = 1,
        expiresAt = 10_000,
        publicKeyBlob = byteArrayOf(1, 2, 3),
        data = byteArrayOf(4, 5, 6),
        flags = 0,
        requestedSignatureAlgorithm = SshSignatureAlgorithm.SSH_ED25519,
        eligibleProviderClientIds = emptyList(),
        authorizationGeneration = "authorization-generation",
        authorizationEpoch = 0,
        processContext = DesktopProcessContext(DesktopProcessContextSource.UNAVAILABLE),
        destinationContext = SshDestinationContext(
            provenance = SshDestinationProvenance.UNKNOWN,
            connectionDirection = SshConnectionDirection.UNKNOWN,
        ),
        connectionId = "connection-id",
    )

    private class FixtureCipher : ProtectedPayloadCipher {
        override fun protect(alias: String, plaintext: ByteArray, aad: ByteArray): ProtectedCiphertext =
            ProtectedCiphertext(
                nonce = MessageDigest.getInstance("SHA-256").digest(alias.encodeToByteArray() + aad).copyOf(12),
                ciphertext = plaintext.copyOf() + ByteArray(16),
            )

        override fun open(
            alias: String,
            nonce: ByteArray,
            ciphertext: ByteArray,
            aad: ByteArray,
        ): ByteArray = ciphertext.copyOf(ciphertext.size - 16)
    }

    private object NoOpPayloadKeyVault : OperationalPayloadKeyVault {
        override suspend fun create(generation: Long) = Unit
        override suspend fun selfTest(generation: Long) = Unit
    }
}

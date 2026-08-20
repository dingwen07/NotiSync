package net.extrawdw.apps.notisync.data.storage.operational

import android.content.Context
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProviderResponseCustodyTransitionAndroidTest {
    private val databases = mutableListOf<OperationalDatabase>()

    @After
    fun tearDown() {
        databases.forEach { it.close() }
    }

    @Test
    fun sealEveryNegativeOutcomeCommitsTerminalHistoryWithoutCustodyOrPendingInput() = runBlocking {
        listOf(
            SealRequestOutcome.REJECTED to SealRequestState.FAILED,
            SealRequestOutcome.CANCELLED to SealRequestState.CANCELLED,
            SealRequestOutcome.EXPIRED to SealRequestState.EXPIRED,
            SealRequestOutcome.FAILED to SealRequestState.FAILED,
        ).forEachIndexed { index, (outcome, expectedState) ->
            val database = newDatabase()
            val requestId = "seal-negative-$index"
            val seal = database.sealDao()
            assertEquals(SealAcceptResult.STORED, seal.accept(
                sealRequest(requestId),
                sealPending(requestId),
                null,
                now = 2,
            ))

            assertTrue(
                seal.recordOutcomeAndQueueResponse(
                    SealOutcomeTransition(requestId, outcome, 3, responseCustody = null, activity = null),
                ),
            )
            val stored = requireNotNull(seal.findRequest(requestId))
            assertEquals(expectedState, stored.state)
            assertEquals(outcome, stored.outcome)
            assertNull(seal.findPendingPayload(requestId))
            assertNull(seal.findResponseCustody(requestId))
        }
    }

    @Test
    fun sshEveryNonSignedOutcomeCommitsTerminalHistoryWithoutCustodyOrPendingInput() = runBlocking {
        listOf(
            SshProviderRequestOutcome.IMPORTED to SshProviderRequestState.COMPLETED,
            SshProviderRequestOutcome.ALREADY_PRESENT to SshProviderRequestState.COMPLETED,
            SshProviderRequestOutcome.REJECTED to SshProviderRequestState.COMPLETED,
            SshProviderRequestOutcome.FAILED to SshProviderRequestState.COMPLETED,
            SshProviderRequestOutcome.CANCELLED to SshProviderRequestState.CANCELLED,
            SshProviderRequestOutcome.EXPIRED to SshProviderRequestState.EXPIRED,
        ).forEachIndexed { index, (outcome, expectedState) ->
            val database = newDatabase()
            val requestId = "ssh-negative-$index"
            val requests = database.sshRequestDao()
            assertEquals(
                SshProviderAcceptResult.STORED,
                requests.acceptProviderRequest(
                    sshRequest(requestId),
                    sshPending(requestId),
                    null,
                    now = 2,
                ),
            )

            assertTrue(
                requests.recordProviderOutcomeAndQueueResponse(
                    SshProviderOutcomeTransition(requestId, outcome, 3, responseCustody = null, activity = null),
                ),
            )
            val stored = requireNotNull(requests.findProviderRequest(requestId))
            assertEquals(expectedState, stored.state)
            assertEquals(outcome, stored.outcome)
            assertNull(requests.findProviderPendingPayload(requestId))
            assertNull(requests.findProviderResponseCustody(requestId))
        }
    }

    @Test
    fun mismatchedSealCustodyIsRejectedBeforeAnyWrite() = runBlocking {
        val database = newDatabase()
        val seal = database.sealDao()
        val requestId = "seal-mismatch"
        seal.accept(sealRequest(requestId), sealPending(requestId), null, now = 2)

        expectIllegalArgument {
            seal.recordOutcomeAndQueueResponse(
                SealOutcomeTransition(
                    requestId,
                    SealRequestOutcome.REJECTED,
                    3,
                    responseCustody = sealResponse(requestId, 3),
                    activity = null,
                ),
            )
        }
        assertEquals(SealRequestState.PENDING_REVIEW, seal.findRequest(requestId)?.state)
        assertNull(seal.findRequest(requestId)?.outcome)
        assertNotNull(seal.findPendingPayload(requestId))
        assertNull(seal.findResponseCustody(requestId))

        expectIllegalArgument {
            seal.recordOutcomeAndQueueResponse(
                SealOutcomeTransition(requestId, SealRequestOutcome.APPROVED, 3, null, null),
            )
        }
        assertEquals(SealRequestState.PENDING_REVIEW, seal.findRequest(requestId)?.state)
        assertNotNull(seal.findPendingPayload(requestId))
    }

    @Test
    fun mismatchedSshCustodyIsRejectedBeforeAnyWrite() = runBlocking {
        val database = newDatabase()
        val requests = database.sshRequestDao()
        val requestId = "ssh-mismatch"
        requests.acceptProviderRequest(sshRequest(requestId), sshPending(requestId), null, now = 2)

        expectIllegalArgument {
            requests.recordProviderOutcomeAndQueueResponse(
                SshProviderOutcomeTransition(
                    requestId,
                    SshProviderRequestOutcome.IMPORTED,
                    3,
                    responseCustody = sshResponse(requestId, 3),
                    activity = null,
                ),
            )
        }
        assertEquals(SshProviderRequestState.PENDING_REVIEW, requests.findProviderRequest(requestId)?.state)
        assertNull(requests.findProviderRequest(requestId)?.outcome)
        assertNotNull(requests.findProviderPendingPayload(requestId))
        assertNull(requests.findProviderResponseCustody(requestId))

        expectIllegalArgument {
            requests.recordProviderOutcomeAndQueueResponse(
                SshProviderOutcomeTransition(requestId, SshProviderRequestOutcome.SIGNED, 3, null, null),
            )
        }
        assertEquals(SshProviderRequestState.PENDING_REVIEW, requests.findProviderRequest(requestId)?.state)
        assertNotNull(requests.findProviderPendingPayload(requestId))
    }

    @Test
    fun sealSuccessfulCustodySurvivesClosePrepareCloseAndAcceptedSend() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "seal-custody-process-death-${System.nanoTime()}.db"
        context.deleteDatabase(name)
        try {
            val first = openFileDatabase(name)
            val requestId = "seal-process-death"
            val body = sealResponse(requestId, 3)
            try {
                val seal = first.sealDao()
                assertEquals(SealAcceptResult.STORED, seal.accept(sealRequest(requestId), sealPending(requestId), null, 2))
                assertTrue(seal.recordOutcomeAndQueueResponse(
                    SealOutcomeTransition(requestId, SealRequestOutcome.APPROVED, 3, body, null),
                ))
                assertEquals(SealRequestState.RESPONSE_QUEUED, seal.findRequest(requestId)?.state)
                assertSameSealCustody(body, seal.findResponseCustody(requestId))
                assertNull(seal.findPendingPayload(requestId))
            } finally {
                first.close()
            }

            val reopened = openFileDatabase(name)
            val prepared = sealResponse(requestId, 4, updatedAt = 4)
                .copy(payloadFormat = SealResponsePayloadFormat.PREPARED_ENVELOPE)
            try {
                val seal = reopened.sealDao()
                assertSameSealCustody(body, seal.findResponseCustody(requestId))
                assertEquals(SealResponsePrepareResult.UPDATED, seal.prepareResponse(body, prepared))
            } finally {
                reopened.close()
            }

            val final = openFileDatabase(name)
            try {
                val seal = final.sealDao()
                assertSameSealCustody(prepared, seal.findResponseCustody(requestId))
                assertEquals(SealResponseCompleteResult.SENT, seal.completeResponse(prepared, 5))
                assertEquals(SealRequestState.SENT, seal.findRequest(requestId)?.state)
                assertNull(seal.findResponseCustody(requestId))
            } finally {
                final.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun sshSignedCustodySurvivesClosePrepareCloseAndAcceptedSend() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "ssh-custody-process-death-${System.nanoTime()}.db"
        context.deleteDatabase(name)
        try {
            val first = openFileDatabase(name)
            val requestId = "ssh-process-death"
            val body = sshResponse(requestId, 3)
            try {
                val requests = first.sshRequestDao()
                assertEquals(
                    SshProviderAcceptResult.STORED,
                    requests.acceptProviderRequest(sshRequest(requestId), sshPending(requestId), null, 2),
                )
                assertTrue(requests.recordProviderOutcomeAndQueueResponse(
                    SshProviderOutcomeTransition(requestId, SshProviderRequestOutcome.SIGNED, 3, body, null),
                ))
                assertEquals(SshProviderRequestState.RESPONSE_QUEUED, requests.findProviderRequest(requestId)?.state)
                assertSameSshCustody(body, requests.findProviderResponseCustody(requestId))
                assertNull(requests.findProviderPendingPayload(requestId))
            } finally {
                first.close()
            }

            val reopened = openFileDatabase(name)
            val prepared = sshResponse(requestId, 4, updatedAt = 4)
                .copy(payloadFormat = SshProviderResponsePayloadFormat.PREPARED_ENVELOPE)
            try {
                val requests = reopened.sshRequestDao()
                assertSameSshCustody(body, requests.findProviderResponseCustody(requestId))
                assertEquals(SshProviderResponsePrepareResult.UPDATED, requests.prepareProviderResponse(body, prepared))
            } finally {
                reopened.close()
            }

            val final = openFileDatabase(name)
            try {
                val requests = final.sshRequestDao()
                assertSameSshCustody(prepared, requests.findProviderResponseCustody(requestId))
                assertEquals(SshProviderResponseCompleteResult.SENT, requests.completeProviderResponse(prepared, 5))
                assertEquals(SshProviderRequestState.SENT, requests.findProviderRequest(requestId)?.state)
                assertNull(requests.findProviderResponseCustody(requestId))
            } finally {
                final.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    private fun newDatabase(): OperationalDatabase = Room.inMemoryDatabaseBuilder<OperationalDatabase>(
        ApplicationProvider.getApplicationContext<Context>(),
    )
        .setDriver(AndroidSQLiteDriver())
        .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        .build()
        .also(databases::add)

    private fun openFileDatabase(name: String): OperationalDatabase = Room.databaseBuilder<OperationalDatabase>(
        ApplicationProvider.getApplicationContext<Context>(),
        name,
    )
        .setDriver(AndroidSQLiteDriver())
        .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        .build()

    private fun sealRequest(requestId: String) = SealRequestEntity(
        requestId = requestId,
        requesterClientId = "requester",
        senderClientId = "requester",
        requestFingerprint = fingerprint(10),
        issuedAt = 1,
        expiresAt = 100,
        payloadSha256 = fingerprint(11),
        objectKind = SealObjectKind.GIT_COMMIT,
        displayProtectionScheme = ProtectedBlobSchemes.ANDROID_KEYSTORE_AES_GCM,
        displayProtectionVersion = 1,
        displayProtectionKeyRef = "seal-display-key",
        displayProtectionGeneration = 1,
        displayPayloadCodecVersion = 1,
        displayCiphertext = ByteArray(32) { 1 },
        displayNonce = ByteArray(12) { 2 },
        displayTruncated = false,
        state = SealRequestState.PENDING_REVIEW,
        outcome = null,
        decisionAt = null,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun sealPending(requestId: String) = SealPendingPayloadEntity(
        requestId = requestId,
        protectionScheme = ProtectedBlobSchemes.ANDROID_KEYSTORE_AES_GCM,
        protectionVersion = 1,
        protectionKeyRef = "seal-pending-key",
        protectionGeneration = 1,
        payloadCodecVersion = 1,
        payloadCiphertext = ByteArray(32) { 3 },
        payloadNonce = ByteArray(12) { 4 },
        createdAt = 1,
        updatedAt = 1,
    )

    private fun sealResponse(requestId: String, fill: Int, updatedAt: Long = 3) = SealResponseCustodyEntity(
        requestId = requestId,
        payloadFormat = SealResponsePayloadFormat.BODY,
        protectionScheme = ProtectedBlobSchemes.ANDROID_KEYSTORE_AES_GCM,
        protectionVersion = 1,
        protectionKeyRef = "seal-response-key",
        protectionGeneration = 1,
        payloadCodecVersion = 1,
        payloadCiphertext = ByteArray(32) { fill.toByte() },
        payloadNonce = ByteArray(12) { (fill + 1).toByte() },
        createdAt = 3,
        updatedAt = updatedAt,
    )

    private fun sshRequest(requestId: String) = SshProviderRequestEntity(
        requestId = requestId,
        kind = SshProviderRequestKind.SIGN,
        requesterClientId = "requester",
        requestFingerprint = fingerprint(12),
        historyProtectionScheme = ProtectedBlobSchemes.ANDROID_KEYSTORE_AES_GCM,
        historyProtectionVersion = 1,
        historyProtectionKeyRef = "ssh-history-key",
        historyProtectionGeneration = 1,
        historyPayloadCodecVersion = 1,
        historyCiphertext = ByteArray(32) { 1 },
        historyNonce = ByteArray(12) { 2 },
        state = SshProviderRequestState.PENDING_REVIEW,
        outcome = null,
        resultAt = null,
        expiresAt = 100,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun sshPending(requestId: String) = SshProviderPendingPayloadEntity(
        requestId = requestId,
        protectionScheme = ProtectedBlobSchemes.ANDROID_KEYSTORE_AES_GCM,
        protectionVersion = 1,
        protectionKeyRef = "ssh-request-key",
        protectionGeneration = 1,
        payloadCodecVersion = 1,
        requestCiphertext = ByteArray(32) { 3 },
        requestNonce = ByteArray(12) { 4 },
        createdAt = 1,
    )

    private fun sshResponse(requestId: String, fill: Int, updatedAt: Long = 3) = SshProviderResponseCustodyEntity(
        requestId = requestId,
        payloadFormat = SshProviderResponsePayloadFormat.BODY,
        protectionScheme = ProtectedBlobSchemes.ANDROID_KEYSTORE_AES_GCM,
        protectionVersion = 1,
        protectionKeyRef = "ssh-response-key",
        protectionGeneration = 1,
        payloadCodecVersion = 1,
        payloadCiphertext = ByteArray(32) { fill.toByte() },
        payloadNonce = ByteArray(12) { (fill + 1).toByte() },
        createdAt = 3,
        updatedAt = updatedAt,
    )

    private fun fingerprint(fill: Int): ByteArray = ByteArray(32) { fill.toByte() }

    private fun assertSameSealCustody(
        expected: SealResponseCustodyEntity,
        actual: SealResponseCustodyEntity?,
    ) {
        assertNotNull(actual)
        requireNotNull(actual).also { stored ->
            assertEquals(expected.requestId, stored.requestId)
            assertEquals(expected.payloadFormat, stored.payloadFormat)
            assertEquals(expected.protectionScheme, stored.protectionScheme)
            assertEquals(expected.protectionVersion, stored.protectionVersion)
            assertEquals(expected.protectionKeyRef, stored.protectionKeyRef)
            assertEquals(expected.protectionGeneration, stored.protectionGeneration)
            assertEquals(expected.payloadCodecVersion, stored.payloadCodecVersion)
            assertTrue(expected.payloadCiphertext.contentEquals(stored.payloadCiphertext))
            assertTrue(expected.payloadNonce.contentEquals(stored.payloadNonce))
            assertEquals(expected.createdAt, stored.createdAt)
            assertEquals(expected.updatedAt, stored.updatedAt)
        }
    }

    private fun assertSameSshCustody(
        expected: SshProviderResponseCustodyEntity,
        actual: SshProviderResponseCustodyEntity?,
    ) {
        assertNotNull(actual)
        requireNotNull(actual).also { stored ->
            assertEquals(expected.requestId, stored.requestId)
            assertEquals(expected.payloadFormat, stored.payloadFormat)
            assertEquals(expected.protectionScheme, stored.protectionScheme)
            assertEquals(expected.protectionVersion, stored.protectionVersion)
            assertEquals(expected.protectionKeyRef, stored.protectionKeyRef)
            assertEquals(expected.protectionGeneration, stored.protectionGeneration)
            assertEquals(expected.payloadCodecVersion, stored.payloadCodecVersion)
            assertTrue(expected.payloadCiphertext.contentEquals(stored.payloadCiphertext))
            assertTrue(expected.payloadNonce.contentEquals(stored.payloadNonce))
            assertEquals(expected.createdAt, stored.createdAt)
            assertEquals(expected.updatedAt, stored.updatedAt)
        }
    }

    private suspend fun expectIllegalArgument(block: suspend () -> Unit) {
        var thrown = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            thrown = true
        }
        assertTrue("expected IllegalArgumentException", thrown)
    }
}

package net.extrawdw.apps.notisync.data.storage.core

import android.content.Context
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.executeSQL
import androidx.room3.useWriterConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.runBlocking
import net.extrawdw.notisync.protocol.crypto.SoftwareIdentitySigner
import net.extrawdw.notisync.protocol.crypto.TrustStoreSigning
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoreTrustRepositoryAndroidTest {
    @Test
    fun exactThreeAndFourSectionBlobsSurviveCloseReopenAndCasTransition() = runBlocking {
        withNamedDatabase { context, name, initial ->
            val signer = SoftwareIdentitySigner.generate()
            var clock = 10L
            var database = initial
            var repository = CoreFoundationRepository(CoreRoomStore.forDatabase(database)) { clock }
            assertEquals(IdentityMetadataSaveResult.SAVED, repository.saveIdentityMetadata(identityInput(signer)))

            val three = threeSectionInput(signer)
            assertEquals(TrustSnapshotWriteResult.APPLIED, repository.replaceTrustSnapshot(three))
            val first = requireNotNull(database.trustSnapshotDao().get())
            val firstDigest = first.snapshotDigest.copyOf()
            assertNull(first.epochsUtf8)
            assertArrayEquals(three.exactBytes().signatureBase64UrlUtf8, first.signatureBase64UrlUtf8)

            database.close()
            database = open(context, name)
            repository = CoreFoundationRepository(CoreRoomStore.forDatabase(database)) { clock }
            val reopenedThree = requireNotNull(repository.loadValidatedTrustSnapshot())
            assertEquals(TrustSignatureFormat.TRUSTSTORE_V1_THREE_SECTION, reopenedThree.signatureFormat)
            assertNull(reopenedThree.epochsUtf8)
            assertArrayEquals(firstDigest, reopenedThree.snapshotDigest)

            clock = 20L
            val four = fourSectionInput(signer)
            assertEquals(
                TrustSnapshotWriteResult.CONFLICT,
                repository.replaceTrustSnapshot(four, expectedSnapshotDigest = ByteArray(32) { 9 }),
            )
            assertArrayEquals(firstDigest, database.trustSnapshotDao().get()!!.snapshotDigest)
            assertEquals(
                TrustSnapshotWriteResult.APPLIED,
                repository.replaceTrustSnapshot(four, expectedSnapshotDigest = firstDigest),
            )
            val fourRow = requireNotNull(database.trustSnapshotDao().get())
            assertEquals(20L, fourRow.updatedAt)
            assertArrayEquals(four.exactBytes().epochsUtf8, fourRow.epochsUtf8)
            assertArrayEquals(four.exactBytes().signatureBase64UrlUtf8, fourRow.signatureBase64UrlUtf8)

            database.close()
            database = open(context, name)
            repository = CoreFoundationRepository(CoreRoomStore.forDatabase(database)) { 30L }
            val reopenedFour = requireNotNull(repository.loadValidatedTrustSnapshot())
            assertEquals(TrustSignatureFormat.TRUSTSTORE_V1_FOUR_SECTION, reopenedFour.signatureFormat)
            assertArrayEquals(four.exactBytes().epochsUtf8, reopenedFour.epochsUtf8)
            assertEquals(TrustSnapshotWriteResult.ALREADY_CURRENT, repository.replaceTrustSnapshot(four))
            assertEquals(20L, database.trustSnapshotDao().get()!!.updatedAt)
            database.close()
        }
    }

    @Test
    fun rawUnknownTokenAndSectionShapeMaterializeThenFailSemanticReadiness() = runBlocking {
        withNamedDatabase { _, _, database ->
            val signer = SoftwareIdentitySigner.generate()
            val repository = CoreFoundationRepository(CoreRoomStore.forDatabase(database)) { 1L }
            assertEquals(IdentityMetadataSaveResult.SAVED, repository.saveIdentityMetadata(identityInput(signer)))
            assertEquals(TrustSnapshotWriteResult.APPLIED, repository.replaceTrustSnapshot(threeSectionInput(signer)))

            database.useWriterConnection { connection ->
                connection.executeSQL("UPDATE trust_snapshot SET signature_format = 'FUTURE_FORMAT'")
            }
            assertEquals("FUTURE_FORMAT", database.trustSnapshotDao().get()!!.signatureFormat)
            assertTrustIssue(CoreTrustIntegrityIssue.UNKNOWN_SIGNATURE_FORMAT) {
                repository.loadValidatedTrustSnapshot()
            }

            database.useWriterConnection { connection ->
                connection.executeSQL(
                    "UPDATE trust_snapshot SET signature_format = 'TRUSTSTORE_V1_FOUR_SECTION', epochs = NULL",
                )
            }
            assertTrustIssue(CoreTrustIntegrityIssue.INVALID_SECTION_SHAPE) {
                repository.loadValidatedTrustSnapshot()
            }
        }
    }

    @Test
    fun identityAuthorityIsInsertExactIdempotentAndCannotBeReboundUnderTrust() = runBlocking {
        withNamedDatabase { _, _, database ->
            val firstSigner = SoftwareIdentitySigner.generate()
            val secondSigner = SoftwareIdentitySigner.generate()
            var clock = 1L
            val repository = CoreFoundationRepository(CoreRoomStore.forDatabase(database)) { clock }
            val first = identityInput(firstSigner)

            assertEquals(IdentityMetadataSaveResult.SAVED, repository.saveIdentityMetadata(first))
            clock = 2L
            assertEquals(IdentityMetadataSaveResult.ALREADY_CURRENT, repository.saveIdentityMetadata(first))
            assertEquals(1L, database.identityMetadataDao().get()!!.updatedAt)
            assertEquals(TrustSnapshotWriteResult.APPLIED, repository.replaceTrustSnapshot(threeSectionInput(firstSigner)))
            assertEquals(
                IdentityMetadataSaveResult.CONFLICT,
                repository.saveIdentityMetadata(identityInput(secondSigner)),
            )
            assertArrayEquals(firstSigner.publicKeySpki, database.identityMetadataDao().get()!!.publicSpki)
            assertEquals(TrustSignatureFormat.TRUSTSTORE_V1_THREE_SECTION,
                repository.loadValidatedTrustSnapshot()!!.signatureFormat)
        }
    }

    @Test
    fun exactSignatureAndRepositoryDigestCorruptionFailWithTypedValueFreeIssues() = runBlocking {
        withNamedDatabase { _, _, database ->
            val signer = SoftwareIdentitySigner.generate()
            val repository = CoreFoundationRepository(CoreRoomStore.forDatabase(database)) { 1L }
            assertEquals(IdentityMetadataSaveResult.SAVED, repository.saveIdentityMetadata(identityInput(signer)))
            assertEquals(TrustSnapshotWriteResult.APPLIED, repository.replaceTrustSnapshot(fourSectionInput(signer)))
            val valid = requireNotNull(database.trustSnapshotDao().get())

            val otherSignature = fourSectionInput(SoftwareIdentitySigner.generate())
                .exactBytes().signatureBase64UrlUtf8
            database.trustSnapshotDao().replace(valid.copy(signatureBase64UrlUtf8 = otherSignature))
            assertTrustIssue(CoreTrustIntegrityIssue.SIGNATURE_MISMATCH) {
                repository.loadValidatedTrustSnapshot()
            }

            database.trustSnapshotDao().replace(valid.copy(snapshotDigest = ByteArray(32)))
            assertTrustIssue(CoreTrustIntegrityIssue.DIGEST_MISMATCH) {
                repository.loadValidatedTrustSnapshot()
            }
        }
    }

    private suspend fun withNamedDatabase(
        block: suspend (Context, String, CoreDatabase) -> Unit,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "core-trust-${System.nanoTime()}.db"
        context.deleteDatabase(name)
        val database = open(context, name)
        try {
            block(context, name, database)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    private fun open(context: Context, name: String): CoreDatabase =
        Room.databaseBuilder<CoreDatabase>(context, name)
            .setDriver(AndroidSQLiteDriver())
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()

    private fun identityInput(signer: SoftwareIdentitySigner) = IdentityMetadataInput(
        keyAlias = "notisync.identity.v1",
        keyAliasVersion = 1,
        publicSpki = signer.publicKeySpki,
        securityLevel = IdentitySecurityLevel.TRUSTED_ENVIRONMENT,
        lifecycleState = IdentityLifecycleState.ACTIVE,
        createdAt = 1,
    )

    private fun threeSectionInput(signer: SoftwareIdentitySigner): TrustSnapshotInput.ThreeSection {
        val signature = signThree(signer, ENTRIES, CARDS, OVERLAYS)
        return TrustSnapshotInput.ThreeSection(
            ENTRIES.encodeToByteArray(),
            CARDS.encodeToByteArray(),
            OVERLAYS.encodeToByteArray(),
            signature.encodeToByteArray(),
        )
    }

    private fun fourSectionInput(signer: SoftwareIdentitySigner): TrustSnapshotInput.FourSection {
        val signature = TrustStoreSigning.sign(signer, ENTRIES, CARDS, OVERLAYS, EPOCHS)
        return TrustSnapshotInput.FourSection(
            ENTRIES.encodeToByteArray(),
            CARDS.encodeToByteArray(),
            OVERLAYS.encodeToByteArray(),
            EPOCHS.encodeToByteArray(),
            signature.encodeToByteArray(),
        )
    }

    private suspend fun assertTrustIssue(
        expected: CoreTrustIntegrityIssue,
        block: suspend () -> Unit,
    ) {
        try {
            block()
            fail("CoreTrustIntegrityException expected")
        } catch (failure: CoreTrustIntegrityException) {
            assertEquals(expected, failure.issue)
        }
    }

    private companion object {
        const val ENTRIES = "[{\"id\":\"exact-entry\"}]"
        const val CARDS = "{}"
        const val OVERLAYS = "{}"
        const val EPOCHS = "{\"selfEpoch\":1,\"peers\":{},\"pending\":null}"
    }
}

private fun signThree(
    signer: SoftwareIdentitySigner,
    entries: String,
    cards: String,
    overlays: String,
): String {
    val encoder = Base64.getUrlEncoder().withoutPadding()
    fun digest(value: String): String = encoder.encodeToString(
        MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()),
    )
    val canonical = buildString {
        append(TrustStoreSigning.VERSION).append('\n')
        append(signer.clientId.value).append('\n')
        append(digest(entries)).append('\n')
        append(digest(cards)).append('\n')
        append(digest(overlays))
    }.encodeToByteArray()
    return encoder.encodeToString(signer.sign(canonical))
}

package net.extrawdw.apps.notisync.data.storage.importer.legacy.core

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import net.extrawdw.notisync.protocol.crypto.SoftwareIdentitySigner
import net.extrawdw.notisync.protocol.crypto.TrustStoreSigning
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyCorePreferencesDataStoreReaderTest {
    private val files = mutableListOf<File>()
    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() {
        scopes.forEach(CoroutineScope::cancel)
        files.forEach(File::delete)
    }

    @Test
    fun fourSectionSnapshotPreservesExactStringsAndDoesNotMutateTheSource() = runBlocking {
        val (dataStore, file) = newDataStore()
        val signer = SoftwareIdentitySigner.generate()
        val sections = trustSections(epochs = EPOCHS_ONE)
        val signature = TrustStoreSigning.sign(
            signer,
            sections.entries,
            sections.cards,
            sections.overlays,
            requireNotNull(sections.epochs),
        )
        dataStore.edit { values ->
            values[BROKER_URL] = "https://broker.example.test"
            values[GROUP_ID] = "group-one"
            values[ROUTE_EPOCH] = 7
            values[FCM_ROUTE_REF] = "route-reference"
            values[LAST_SEEN] = 1234L
            values[TRUST_ENTRIES] = sections.entries
            values[TRUST_CARDS] = sections.cards
            values[TRUST_OVERLAYS] = sections.overlays
            values[TRUST_EPOCHS] = requireNotNull(sections.epochs)
            values[TRUST_SIGNATURE] = signature
        }
        val sourceBytes = file.readBytes()
        val sourceModifiedAt = file.lastModified()

        val result = LegacyCorePreferencesDataStoreReader().read(dataStore)

        assertEquals(LegacyCoreReadStatus.READY, result.status)
        assertEquals(10, result.presentKeyCount)
        val trust = requireNotNull(result.snapshot?.signedTrust)
        assertEquals(LegacySignedTrustFormat.FOUR_SECTION, trust.format)
        assertEquals(sections.entries, trust.entriesJson)
        assertEquals(sections.cards, trust.cardsJson)
        assertEquals(sections.overlays, trust.overlaysJson)
        assertEquals(sections.epochs, trust.epochsJson)
        assertEquals(1, trust.effectiveSelfEpoch)
        assertArrayEquals(sourceBytes, file.readBytes())
        assertEquals(sourceModifiedAt, file.lastModified())
        assertFalse(result.toString().contains("route-reference"))
        assertFalse(result.snapshot.toString().contains("broker.example.test"))
        assertFalse(trust.toString().contains(sections.entries))

        val signatureCopy = trust.signatureCopy()
        signatureCopy.fill(0)
        assertFalse(trust.signatureCopy().all { it == 0.toByte() })
    }

    @Test
    fun legacyThreeSectionSnapshotKeepsEpochSectionPhysicallyAbsent() = runBlocking {
        val (dataStore, _) = newDataStore()
        val signer = SoftwareIdentitySigner.generate()
        val sections = trustSections(epochs = null)
        dataStore.edit { values ->
            values[TRUST_ENTRIES] = sections.entries
            values[TRUST_CARDS] = sections.cards
            values[TRUST_OVERLAYS] = sections.overlays
            values[TRUST_SIGNATURE] = legacyThreeSectionSignature(signer, sections)
        }

        val result = LegacyCorePreferencesDataStoreReader().read(dataStore)

        assertEquals(LegacyCoreReadStatus.READY, result.status)
        val trust = requireNotNull(result.snapshot?.signedTrust)
        assertEquals(LegacySignedTrustFormat.LEGACY_THREE_SECTION, trust.format)
        assertNull(trust.epochsJson)
        assertNull(trust.epochsUtf8OrNull())
        assertEquals(1, trust.effectiveSelfEpoch)
    }

    @Test
    fun unrelatedPreferenceDoesNotEnterTheOwnedSourceDigest() = runBlocking {
        val (dataStore, _) = newDataStore()
        dataStore.edit { values -> values[BROKER_URL] = "https://broker.example.test" }
        val reader = LegacyCorePreferencesDataStoreReader()
        val before = reader.read(dataStore)

        dataStore.edit { values -> values[stringPreferencesKey("unrelated_private_value")] = "secret-value" }
        val after = reader.read(dataStore)

        assertArrayEquals(before.digests.contentDigest, after.digests.contentDigest)
        assertArrayEquals(before.digests.logicalFingerprint, after.digests.logicalFingerprint)
    }

    @Test
    fun wrongTypeAndPartialTrustProduceOnlyTypedValueFreeIssues() = runBlocking {
        val (dataStore, _) = newDataStore()
        dataStore.edit { values ->
            values[stringPreferencesKey(LegacyCorePreferencesSourceContract.ROUTE_EPOCH_KEY)] =
                "private-route-sentinel"
            values[TRUST_ENTRIES] = EMPTY_ENTRIES
        }

        val result = LegacyCorePreferencesDataStoreReader().read(dataStore)

        assertEquals(LegacyCoreReadStatus.RECOVERY_REQUIRED, result.status)
        assertNull(result.snapshot)
        assertTrue(
            LegacyCorePreferencesIssue(
                LegacyCorePreferencesIssueKind.WRONG_VALUE_TYPE,
                LegacyCorePreferenceField.ROUTE_EPOCH,
            ) in result.issues,
        )
        assertTrue(
            LegacyCorePreferencesIssue(LegacyCorePreferencesIssueKind.PARTIAL_SIGNED_TRUST) in result.issues,
        )
        assertFalse(result.toString().contains("private-route-sentinel"))
        assertFalse(result.toString().contains(EMPTY_ENTRIES))
    }

    @Test
    fun malformedJsonAndSignatureAreRejectedWithoutNormalization() = runBlocking {
        val (dataStore, _) = newDataStore()
        dataStore.edit { values ->
            values[TRUST_ENTRIES] = "not-json-private-sentinel"
            values[TRUST_CARDS] = EMPTY_OBJECT
            values[TRUST_OVERLAYS] = EMPTY_OBJECT
            values[TRUST_EPOCHS] = EPOCHS_ONE
            values[TRUST_SIGNATURE] = "not-base64***"
        }

        val result = LegacyCorePreferencesDataStoreReader().read(dataStore)

        assertEquals(LegacyCoreReadStatus.RECOVERY_REQUIRED, result.status)
        assertTrue(result.issues.any { it.kind == LegacyCorePreferencesIssueKind.MALFORMED_TRUST_SECTION })
        assertTrue(result.issues.any { it.kind == LegacyCorePreferencesIssueKind.MALFORMED_TRUST_SIGNATURE })
        assertFalse(result.toString().contains("not-json-private-sentinel"))
    }

    @Test
    fun cancellationAndIoFailuresRemainTypedAndDoNotRetainSensitiveCauses() {
        val reader = LegacyCorePreferencesDataStoreReader()
        assertThrows(CancellationException::class.java) {
            runBlocking { reader.read(ThrowingDataStore(CancellationException("cancel"))) }
        }

        val failure = assertThrows(LegacyCoreSourceReadException::class.java) {
            runBlocking { reader.read(ThrowingDataStore(IOException("private filesystem path"))) }
        }
        assertEquals(LegacyCoreSourceKind.PREFERENCES, failure.source)
        assertEquals(LegacyCoreSourceFailureKind.SOURCE_IO, failure.kind)
        assertNull(failure.cause)
        assertFalse(failure.toString().contains("private filesystem path"))
    }

    @Test
    fun suspendedDataStoreReadIsCancellationSafe() {
        val dataStore = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { awaitCancellation() }
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                error("read-only test source")
        }
        assertThrows(CancellationException::class.java) {
            runBlocking {
                val scope = this
                scope.cancel()
                LegacyCorePreferencesDataStoreReader().read(dataStore)
            }
        }
    }

    private fun newDataStore(): Pair<DataStore<Preferences>, File> {
        val file = File.createTempFile("legacy-core-${System.nanoTime()}", ".preferences_pb")
            .also { it.delete() }
        files += file
        val scope = CoroutineScope(Dispatchers.Unconfined)
        scopes += scope
        return PreferenceDataStoreFactory.create(scope = scope) { file } to file
    }

    private fun trustSections(epochs: String?): TrustSections = TrustSections(
        entries = EMPTY_ENTRIES,
        cards = EMPTY_OBJECT,
        overlays = EMPTY_OBJECT,
        epochs = epochs,
    )

    private fun legacyThreeSectionSignature(
        signer: SoftwareIdentitySigner,
        sections: TrustSections,
    ): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        fun digest(section: String): String = encoder.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(section.encodeToByteArray()),
        )
        val canonical = buildString {
            append(TrustStoreSigning.VERSION).append('\n')
            append(signer.clientId.value).append('\n')
            append(digest(sections.entries)).append('\n')
            append(digest(sections.cards)).append('\n')
            append(digest(sections.overlays))
        }.encodeToByteArray()
        return encoder.encodeToString(signer.sign(canonical))
    }

    private data class TrustSections(
        val entries: String,
        val cards: String,
        val overlays: String,
        val epochs: String?,
    )

    private class ThrowingDataStore(private val failure: Throwable) : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { throw failure }
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            error("read-only test source")
    }

    private companion object {
        const val EMPTY_ENTRIES = "[]"
        const val EMPTY_OBJECT = "{}"
        const val EPOCHS_ONE = "{\"selfEpoch\":1,\"peers\":{}}"

        val BROKER_URL = stringPreferencesKey(LegacyCorePreferencesSourceContract.BROKER_URL_KEY)
        val GROUP_ID = stringPreferencesKey(LegacyCorePreferencesSourceContract.GROUP_ID_KEY)
        val ROUTE_EPOCH = intPreferencesKey(LegacyCorePreferencesSourceContract.ROUTE_EPOCH_KEY)
        val FCM_ROUTE_REF = stringPreferencesKey(LegacyCorePreferencesSourceContract.FCM_ROUTE_REF_KEY)
        val LAST_SEEN = longPreferencesKey(LegacyCorePreferencesSourceContract.LAST_SEEN_POST_TIME_KEY)
        val TRUST_ENTRIES = stringPreferencesKey(LegacyCorePreferencesSourceContract.TRUST_ENTRIES_KEY)
        val TRUST_CARDS = stringPreferencesKey(LegacyCorePreferencesSourceContract.TRUST_CARDS_KEY)
        val TRUST_OVERLAYS = stringPreferencesKey(LegacyCorePreferencesSourceContract.TRUST_OVERLAYS_KEY)
        val TRUST_EPOCHS = stringPreferencesKey(LegacyCorePreferencesSourceContract.TRUST_EPOCHS_KEY)
        val TRUST_SIGNATURE = stringPreferencesKey(LegacyCorePreferencesSourceContract.TRUST_SIGNATURE_KEY)
    }
}

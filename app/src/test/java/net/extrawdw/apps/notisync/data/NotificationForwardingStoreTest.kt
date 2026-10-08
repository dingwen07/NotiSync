package net.extrawdw.apps.notisync.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.OriginPlatform
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NotificationForwardingStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val jobs = mutableListOf<Job>()
    private val local = OriginPlatform.ANDROID_LOCAL
    private val iphone = OriginPlatform.IOS_ANCS
    private val first = ClientId("first")
    private val second = ClientId("second")

    private fun open(file: File): NotificationForwardingStore {
        val job = Job().also(jobs::add)
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(job + Dispatchers.IO),
        ) { file }
        return NotificationForwardingStore(dataStore)
    }

    @After fun close() = runBlocking {
        jobs.forEach { it.cancelAndJoin() }
    }

    @Test fun defaultsOnAndPersistsIndependentChoicesBeforeRestart() = runBlocking {
        val file = File(temporaryFolder.root, "forwarding.preferences_pb")
        val store = open(file)
        for (peer in listOf(first, second)) {
            for (origin in OriginPlatform.entries) {
                assertTrue(store.preferences.value.isEnabled(peer, origin))
            }
        }
        store.setEnabled(first, local, false)
        store.setEnabled(second, iphone, false)
        jobs.last().cancelAndJoin()

        val restarted = open(file)
        assertFalse(restarted.preferences.value.isEnabled(first, local))
        assertTrue(restarted.preferences.value.isEnabled(first, iphone))
        assertTrue(restarted.preferences.value.isEnabled(second, local))
        assertFalse(restarted.preferences.value.isEnabled(second, iphone))
        restarted.setEnabled(first, local, true)
        assertTrue(restarted.recipientsToExclude(local).isEmpty())
        assertEquals(setOf(second), restarted.recipientsToExclude(iphone))
    }

    @Test fun concurrentTogglesPreserveOtherChoicesAndRosterCleanupOnlyForgetsRemovedPeers() = runBlocking {
        val store = open(File(temporaryFolder.root, "forwarding.preferences_pb"))
        listOf(first, second).flatMap { peer ->
            OriginPlatform.entries.map { origin ->
                launch(Dispatchers.Default) { store.setEnabled(peer, origin, false) }
            }
        }.forEach { it.join() }
        for (origin in OriginPlatform.entries) {
            assertEquals(setOf(first, second), store.recipientsToExclude(origin))
        }
        store.retainPeers(setOf(first.value, second.value))
        for (origin in OriginPlatform.entries) {
            assertEquals(setOf(first, second), store.recipientsToExclude(origin))
        }
        store.retainPeers(setOf(second.value))
        for (origin in OriginPlatform.entries) {
            assertTrue(store.preferences.value.isEnabled(first, origin))
            assertFalse(store.preferences.value.isEnabled(second, origin))
        }
        jobs.last().cancelAndJoin()
        val restarted = open(File(temporaryFolder.root, "forwarding.preferences_pb"))
        for (origin in OriginPlatform.entries) {
            assertEquals(setOf(second), restarted.recipientsToExclude(origin))
        }
    }
}

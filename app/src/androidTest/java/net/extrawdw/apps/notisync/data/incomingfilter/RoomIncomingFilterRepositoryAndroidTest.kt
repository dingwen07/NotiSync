package net.extrawdw.apps.notisync.data.incomingfilter

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDatabase
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomIncomingFilterRepositoryAndroidTest {
    private val databases = mutableListOf<OperationalDatabase>()
    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        databases.forEach { it.close() }
    }

    @Test
    fun replacementIsCanonicalLwwAndRelationReadIsAtomic() = runBlocking {
        val database = newDatabase()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also(scopes::add)
        val repository = RoomIncomingFilterRepository(database.incomingFilterDao(), scope)
        repository.awaitProjectionHydrated()

        val first = IncomingFilterUpdate(
            requesterClientId = "peer",
            updatedAt = 10,
            receivedAt = 11,
            rules = listOf(
                IncomingFilterRuleSpec(
                    origin = IncomingFilterOrigin.ANDROID_LOCAL,
                    appId = "com.example",
                    channelId = "updates",
                ),
            ),
        )
        assertEquals(IncomingFilterReplaceResult.INSERTED, repository.replace(first))
        val storedFirst = requireNotNull(repository.read("peer"))
        assertEquals(10L, storedFirst.updatedAt)
        assertEquals(11L, storedFirst.receivedAt)
        assertEquals(1, storedFirst.rules.size)
        val firstDigest = storedFirst.rules.single().digest.copyBytes()
        firstDigest[0] = (firstDigest[0].toInt() xor 0x7f).toByte()
        assertFalse(firstDigest.contentEquals(storedFirst.rules.single().digest.copyBytes()))

        assertEquals(IncomingFilterReplaceResult.UNCHANGED, repository.replace(first.copy(receivedAt = 99)))
        assertEquals(11L, requireNotNull(repository.read("peer")).receivedAt)

        val stale = first.copy(updatedAt = 9, receivedAt = 12)
        assertEquals(IncomingFilterReplaceResult.STALE, repository.replace(stale))
        assertEquals(10L, requireNotNull(repository.read("peer")).updatedAt)

        val conflict = first.copy(
            receivedAt = 13,
            rules = listOf(IncomingFilterRuleSpec(IncomingFilterOrigin.ANDROID_LOCAL, "com.other")),
        )
        assertEquals(IncomingFilterReplaceResult.CONFLICT, repository.replace(conflict))
        assertEquals("com.example", requireNotNull(repository.read("peer")).rules.single().appId)

        val replacement = first.copy(
            updatedAt = 20,
            receivedAt = 21,
            rules = listOf(IncomingFilterRuleSpec(IncomingFilterOrigin.IOS_ANCS, "com.apple.mail")),
        )
        assertEquals(IncomingFilterReplaceResult.REPLACED, repository.replace(replacement))
        val storedReplacement = requireNotNull(repository.observeAll().first().single())
        assertEquals(IncomingFilterOrigin.IOS_ANCS, storedReplacement.rules.single().origin)
        assertEquals(setOf("peer"), repository.projection.recipientsToExclude(
            IncomingFilterOrigin.IOS_ANCS,
            "com.apple.mail",
            null,
        ))

        assertTrue(repository.remove("peer"))
        assertNull(repository.read("peer"))
        assertNull(repository.projection.filterFor("peer"))
        assertEquals(IncomingFilterReplaceResult.INSERTED, repository.replace(replacement))
        assertEquals(replacement.updatedAt, requireNotNull(repository.projection.filterFor("peer")).updatedAt)
        assertEquals(IncomingFilterReplaceResult.UNCHANGED, repository.replace(replacement.copy(receivedAt = 99)))
        assertTrue(repository.remove("peer"))
        assertFalse(repository.remove("peer"))
    }

    @Test
    fun emptySnapshotIsAValidAggregateAndDelayedRowsCannotRepopulateAfterDelete() = runBlocking {
        val database = newDatabase()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also(scopes::add)
        val repository = RoomIncomingFilterRepository(database.incomingFilterDao(), scope)
        repository.awaitProjectionHydrated()

        val empty = IncomingFilterUpdate("peer-empty", updatedAt = 1, receivedAt = 2, rules = emptyList())
        assertEquals(IncomingFilterReplaceResult.INSERTED, repository.replace(empty))
        val stored = requireNotNull(repository.read("peer-empty"))
        assertTrue(stored.rules.isEmpty())
        assertArrayEquals(
            IncomingFilterCanonicalizer.canonicalize(emptyList()).digestCopy(),
            stored.ruleSetDigest.copyBytes(),
        )
        assertTrue(repository.projection.recipientsToExclude(
            IncomingFilterOrigin.ANDROID_LOCAL,
            "com.example",
            "updates",
        ).isEmpty())

        assertTrue(repository.remove("peer-empty"))
        // Replaying the detached row is the same shape as a delayed aggregate Flow emission.
        assertEquals(IncomingFilterProjectionResult.UNCHANGED, repository.projection.accept(stored))
        assertNull(repository.projection.filterFor("peer-empty"))
    }

    private fun newDatabase(): OperationalDatabase {
        val context: Context = ApplicationProvider.getApplicationContext()
        return Room.inMemoryDatabaseBuilder<OperationalDatabase>(context)
            .setDriver(AndroidSQLiteDriver())
            .build()
            .also(databases::add)
    }
}

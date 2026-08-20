package net.extrawdw.apps.notisync.screen

import android.content.Context
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.useReaderConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDatabase
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ScreenMirrorCodec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies that screen authorization, replay, and codec preferences survive a process-local reload. */
@RunWith(AndroidJUnit4::class)
class RoomScreenPersistenceAndroidTest {
    private lateinit var database: OperationalDatabase
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder<OperationalDatabase>(
            ApplicationProvider.getApplicationContext<Context>(),
        ).setDriver(AndroidSQLiteDriver())
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
    }

    @Test
    fun screenSecurityStateReplayAndCodecPreferencesAreRoomAuthorities() = runBlocking {
        val peer = ClientId("peer-a")
        val auth = ScreenMirrorAuthorizationStore(database.screenDao(), scope)
        val codecs = ScreenMirrorCodecPreferenceStore(database.screenDao(), scope)

        assertFalse(auth.screenMirroringEnabledNow())
        auth.setScreenMirroringEnabled(true)
        auth.setAuthorized(peer, true)
        assertTrue(auth.isAuthorized(peer))
        assertTrue(auth.consumeRequest("session-a", ByteArray(16) { 7 }, 10_000, 20_000, 10_000))
        assertFalse(auth.consumeRequest("session-a", ByteArray(16) { 7 }, 10_000, 20_000, 10_001))
        codecs.setPreferredCodec(peer, ScreenMirrorCodec.AV1)

        val reloadedAuth = ScreenMirrorAuthorizationStore(database.screenDao(), scope)
        val reloadedCodecs = ScreenMirrorCodecPreferenceStore(database.screenDao(), scope)
        assertTrue(reloadedAuth.screenMirroringEnabledNow())
        assertTrue(reloadedAuth.isAuthorized(peer))
        assertEquals(ScreenMirrorCodec.AV1, reloadedCodecs.preferredCodec(peer))
        assertEquals(2, database.useReaderConnection { connection ->
            connection.usePrepared("SELECT COUNT(*) FROM screen_replay_token") { statement ->
                check(statement.step())
                statement.getLong(0).toInt()
            }
        })
    }
}

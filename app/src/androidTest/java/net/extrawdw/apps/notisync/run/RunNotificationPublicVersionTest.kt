package net.extrawdw.apps.notisync.run

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.extrawdw.apps.notisync.R
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.RunBlockedReason
import net.extrawdw.notisync.protocol.RunPhase
import net.extrawdw.notisync.protocol.RunProgress
import net.extrawdw.notisync.protocol.RunPromptKind
import net.extrawdw.notisync.protocol.RunState
import net.extrawdw.notisync.protocol.RunTerminalSnapshot
import net.extrawdw.notisync.protocol.RunUpdateReason
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RunNotificationPublicVersionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val host = ClientId("run-public-version-host")
    private val key = RunKey(host.value, "run-public-version")

    @Before
    fun setUp() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        cleanUpNotification()
    }

    @After
    fun tearDown() {
        cleanUpNotification()
        manager.deleteNotificationChannel(RunNotificationChannels.channelId(host))
    }

    @Test
    fun runningPublicVersionKeepsProgressAndActionsButRedactsPrivateContent() {
        val notification = post(
            running().copy(progress = RunProgress(current = 25, total = 100))
        )

        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        val publicVersion = requireNotNull(notification.publicVersion)
        assertEquals(Notification.VISIBILITY_PUBLIC, publicVersion.visibility)
        assertEquals(
            context.getString(R.string.run_notification_subtext),
            publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
        )
        assertEquals(
            context.getString(R.string.run_status_progress, 25),
            publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )
        assertNull(publicVersion.extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
        assertNull(publicVersion.extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
        assertEquals(25, publicVersion.extras.getInt(Notification.EXTRA_PROGRESS))
        assertTrue(publicVersion.extras.containsKey(NotificationCompat.EXTRA_PROGRESS_SEGMENTS))
        assertNull(publicVersion.contentIntent)
        assertEquals(running().startedAt, publicVersion.`when`)
        assertTrue(publicVersion.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(publicVersion.extras.getBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING))
        assertEquals(
            notification.actions.map { it.title.toString() },
            publicVersion.actions.map { it.title.toString() },
        )

        assertTrue(removeRunNotificationActions(context, key))
        val replacement = activeNotification()
        assertTrue(replacement.actions.isNullOrEmpty())
        assertTrue(requireNotNull(replacement.publicVersion).actions.isNullOrEmpty())
    }

    @Test
    fun textPromptPublicVersionKeepsRemoteInputControlWithoutPromptOrTerminalText() {
        val notification = post(
            running().copy(
                revision = 2,
                phase = RunPhase.BLOCKED,
                updateReason = RunUpdateReason.BLOCKED,
                blockedReason = RunBlockedReason.TERMINAL_INPUT,
                prompt = RunPromptKind.TEXT,
                interactionGeneration = 1,
            )
        )

        val publicVersion = requireNotNull(notification.publicVersion)
        assertEquals(
            context.getString(R.string.run_status_waiting_input),
            publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )
        assertNull(publicVersion.extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
        assertEquals(
            listOf(
                context.getString(R.string.run_action_input),
                context.getString(R.string.run_action_interrupt),
                context.getString(R.string.run_action_terminate),
            ),
            publicVersion.actions.map { it.title.toString() },
        )
        val remoteInput = requireNotNull(publicVersion.actions.first().remoteInputs).single()
        assertEquals(context.getString(R.string.run_input_hint), remoteInput.label.toString())
    }

    @Test
    fun failedTerminalPublicVersionUsesGenericErrorAndCompletionTime() {
        val endedAt = 4_000L
        val notification = post(
            running().copy(
                revision = 2,
                phase = RunPhase.COMPLETED,
                updateReason = RunUpdateReason.COMPLETED,
                updatedAt = endedAt,
                endedAt = endedAt,
                durationMs = endedAt - running().startedAt,
                exitCode = 17,
                failureMessage = "private failure detail",
            )
        )

        val publicVersion = requireNotNull(notification.publicVersion)
        assertEquals(
            context.getString(R.string.run_notification_public_error),
            publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )
        assertEquals(endedAt, publicVersion.`when`)
        assertNull(publicVersion.extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
        assertTrue(publicVersion.actions.isNullOrEmpty())
        assertFalse(publicVersion.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertFalse(publicVersion.extras.getBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING))
    }

    private fun post(state: RunState): Notification {
        assertTrue(RunNotificationPresenter(context) { "Private Build Host" }.render(state))
        return activeNotification()
    }

    private fun activeNotification(): Notification = manager.activeNotifications.single {
        it.tag == runNotificationTag(key) && it.id == runNotificationId(key)
    }.notification

    private fun cleanUpNotification() {
        manager.cancel(runNotificationTag(key), runNotificationId(key))
    }

    private fun running() = RunState(
        hostClientId = host,
        runId = key.runId,
        revision = 1,
        phase = RunPhase.RUNNING,
        updateReason = RunUpdateReason.INITIAL,
        startedAt = 1_000,
        updatedAt = 1_000,
        argv = listOf("/private/path/secret-command"),
        cwd = "/private/worktree",
        usesPty = true,
        terminal = RunTerminalSnapshot(
            text = "private terminal output",
            truncated = false,
            rawBytesSeen = 23,
        ),
    )
}

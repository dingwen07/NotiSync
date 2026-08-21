package net.extrawdw.apps.notisync.run

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RunNotificationActionConsumptionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val key = RunKey("action-host", "action-run")

    @Before
    fun setUp() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Run action test", NotificationManager.IMPORTANCE_LOW)
        )
        manager.cancel(runNotificationTag(key), runNotificationId(key))
    }

    @After
    fun tearDown() {
        manager.cancel(runNotificationTag(key), runNotificationId(key))
        manager.deleteNotificationChannel(CHANNEL_ID)
    }

    @Test
    fun consumptionRepostsSameNotificationWithoutAnyActions() {
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            991,
            Intent(context, RunActionReceiver::class.java).setAction(RunActionReceiver.ACTION_CONTROL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Run")
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, android.R.drawable.ic_media_pause),
                    "Interrupt",
                    pendingIntent,
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, android.R.drawable.ic_delete),
                    "Terminate",
                    pendingIntent,
                ).build()
            )
            .build()
        manager.notify(runNotificationTag(key), runNotificationId(key), notification)
        assertEquals(2, activeNotification().notification.actions.size)

        assertTrue(removeRunNotificationActions(context, key))

        val replacement = activeNotification()
        assertEquals(runNotificationTag(key), replacement.tag)
        assertEquals(runNotificationId(key), replacement.id)
        assertTrue(replacement.notification.actions.isNullOrEmpty())
        assertFalse(removeRunNotificationActions(context, RunKey("missing", "run")))
    }

    private fun activeNotification() = manager.activeNotifications.single {
        it.tag == runNotificationTag(key) && it.id == runNotificationId(key)
    }

    private companion object {
        const val CHANNEL_ID = "run_action_consumption_test"
    }
}

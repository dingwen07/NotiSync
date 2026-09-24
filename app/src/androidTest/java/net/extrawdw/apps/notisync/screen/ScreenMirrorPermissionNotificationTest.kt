package net.extrawdw.apps.notisync.screen

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.extrawdw.apps.notisync.MainActivity
import net.extrawdw.apps.notisync.R
import net.extrawdw.notisync.protocol.ClientId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreenMirrorPermissionNotificationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val presenter = ScreenMirrorPermissionNotifications(context)
    // Equal Java hash codes must not cause one peer's action to replace another's.
    private val first = ClientId("FB")
    private val second = ClientId("Ea")

    @After
    fun cleanUp() {
        listOf(first, second).forEach { peer ->
            presenter.buildNotification(peer, null).contentIntent.cancel()
            presenter.dismiss(peer)
        }
    }

    @Test
    fun permissionChannelIsHighImportanceWithVibration() {
        presenter.ensureChannel()
        val channel = manager.getNotificationChannel(ScreenMirrorPermissionNotifications.CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertTrue(channel.shouldVibrate())
        assertEquals(Notification.VISIBILITY_PRIVATE, channel.lockscreenVisibility)
    }

    @Test
    fun notificationOpensExactPeerDetailsAndProvidesPrivateAuthenticatedAllowAction() {
        val details = ScreenMirrorPermissionNotifications.detailsIntent(context, first)
        assertEquals(ComponentName(context, MainActivity::class.java), details.component)
        assertEquals(MainActivity.ACTION_OPEN_DEVICE_DETAILS, details.action)
        assertEquals(first.value, details.getStringExtra(MainActivity.EXTRA_DEVICE_DETAILS_CLIENT_ID))

        val notification = presenter.buildNotification(first, "My computer")
        assertEquals(ScreenMirrorPermissionNotifications.CHANNEL_ID, notification.channelId)
        assertTrue(notification.contentIntent.isActivity)
        assertTrue(notification.contentIntent.isImmutable)
        val action = notification.actions.single()
        assertTrue(action.actionIntent.isBroadcast)
        assertTrue(action.actionIntent.isImmutable)
        assertTrue(action.isAuthenticationRequired)
        val receiver = context.packageManager.getReceiverInfo(
            ComponentName(context, ScreenMirrorPermissionReceiver::class.java), 0,
        )
        assertFalse(receiver.exported)
    }

    @Test
    fun retriesReuseSamePeerIntentsButOtherPeersStaySeparate() {
        assertEquals(first.value.hashCode(), second.value.hashCode())
        val initial = presenter.buildNotification(first, "First")
        val retry = presenter.buildNotification(first, "Renamed")
        val other = presenter.buildNotification(second, "Other")
        assertEquals(initial.contentIntent, retry.contentIntent)
        assertEquals(initial.actions.single().actionIntent, retry.actions.single().actionIntent)
        assertNotEquals(initial.contentIntent, other.contentIntent)
        assertNotEquals(initial.actions.single().actionIntent, other.actions.single().actionIntent)
    }

    @Test
    fun publicVersionHidesPeerIdentityAndPermissionAction() {
        val notification = presenter.buildNotification(first, "Private computer name")
        val publicVersion = notification.publicVersion
        assertNotNull(publicVersion)
        assertEquals(Notification.VISIBILITY_PUBLIC, publicVersion.visibility)
        assertEquals(
            context.getString(R.string.screen_mirror_permission_title),
            publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE).toString(),
        )
        assertEquals(
            context.getString(R.string.screen_mirror_permission_public_body),
            publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
        )
        assertTrue(publicVersion.actions.isNullOrEmpty())
        assertEquals(notification.contentIntent, publicVersion.contentIntent)
    }
}

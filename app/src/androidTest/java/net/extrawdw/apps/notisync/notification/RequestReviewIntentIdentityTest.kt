package net.extrawdw.apps.notisync.notification

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.extrawdw.apps.notisync.seal.OpenPgpSignReviewActivity
import net.extrawdw.apps.notisync.sshkeyprovider.SshKeyProviderReviewActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RequestReviewIntentIdentityTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun sealNotificationAndAutomaticLaunchesHaveDistinctIntentIdentities() {
        assertDistinctLaunchIdentities(
            notification = OpenPgpSignReviewActivity.intent(context, REQUEST_ID),
            automatic = OpenPgpSignReviewActivity.autoOpenIntent(context, REQUEST_ID),
            approval = OpenPgpSignReviewActivity.approveIntent(context, REQUEST_ID),
        )
    }

    @Test
    fun sshNotificationAndAutomaticLaunchesHaveDistinctIntentIdentities() {
        assertDistinctLaunchIdentities(
            notification = SshKeyProviderReviewActivity.intent(context, REQUEST_ID),
            automatic = SshKeyProviderReviewActivity.autoOpenIntent(context, REQUEST_ID),
            approval = SshKeyProviderReviewActivity.approveIntent(context, REQUEST_ID),
        )
    }

    private fun assertDistinctLaunchIdentities(
        notification: android.content.Intent,
        automatic: android.content.Intent,
        approval: android.content.Intent,
    ) {
        assertNull(notification.action)
        assertEquals(ACTION_AUTO_OPEN_REQUEST_PAGE, automatic.action)
        assertEquals(notification.component, automatic.component)
        assertEquals(notification.data, automatic.data)
        assertFalse(notification.filterEquals(automatic))
        assertFalse(notification.filterEquals(approval))
        assertFalse(automatic.filterEquals(approval))
    }

    private companion object {
        const val REQUEST_ID = "0123456789abcdef0123456789abcdef"
    }
}

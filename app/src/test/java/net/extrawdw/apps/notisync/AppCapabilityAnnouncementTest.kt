package net.extrawdw.apps.notisync

import net.extrawdw.notisync.protocol.Capability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AppCapabilityAnnouncementTest {
    @Test
    fun androidDeclaration_isCompleteAndExact() {
        assertEquals(
            listOf(
                Capability.CAPTURE,
                Capability.DISPLAY,
                Capability.DISMISS_SYNC,
                Capability.PROVIDE_ASSETS,
                Capability.BACKGROUND_WAKE,
                Capability.FOREGROUND_CONNECTION,
                Capability.CAPABILITY_ROUTING_V1,
                Capability.PUSH_FILTERING,
                Capability.DISPLAY_NOTIFICATION_UPDATES,
                Capability.DISPLAY_ANDROID_GROUP_SUMMARIES,
                Capability.RECEIVE_RUNS,
                Capability.OPENPGP_SIGN_V1,
                Capability.SSH_KEY_PROVIDER_V1,
            ),
            ANDROID_SELF_CAPABILITIES,
        )
        assertFalse(ANDROID_SELF_CAPABILITIES.contains(Capability.SSH_AGENT_V1))
    }
}

package net.extrawdw.apps.notisync.notification.mirror

import net.extrawdw.notisync.peer.trust.RosterDevice
import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.OriginPlatform
import net.extrawdw.notisync.protocol.TrustStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class MirrorNotificationOpenPolicyTest {
    @Test
    fun `every non-reply UI action rechecks screen routing when clicked`() {
        assertEquals(true, mirrorNotificationActionUsesUiTrampoline(true, false))
        assertEquals(false, mirrorNotificationActionUsesUiTrampoline(true, true))
        assertEquals(false, mirrorNotificationActionUsesUiTrampoline(false, false))
    }

    @Test
    fun `local origin remains locally actionable`() {
        val ownId = ClientId("this-device")

        assertEquals(
            MirrorNotificationOpenRoute.LOCAL_ORIGIN,
            mirrorNotificationOpenRoute(
                sourceClientId = ownId,
                ownClientId = ownId,
                sourceDevice = device(clientId = ownId),
                originPlatform = OriginPlatform.IOS_ANCS,
            ),
        )
    }

    @Test
    fun `trusted own screen source opens the viewer`() {
        val source = device()

        assertEquals(
            MirrorNotificationOpenRoute.SCREEN_MIRROR,
            mirrorNotificationOpenRoute(source.clientId, ClientId("this-device"), source),
        )
    }

    @Test
    fun `notification bridged from an iPhone does not open the Android bridge screen`() {
        val source = device()

        assertEquals(
            MirrorNotificationOpenRoute.REMOTE_ONLY,
            mirrorNotificationOpenRoute(
                sourceClientId = source.clientId,
                ownClientId = ClientId("this-device"),
                sourceDevice = source,
                originPlatform = OriginPlatform.IOS_ANCS,
            ),
        )
    }

    @Test
    fun `missing or mismatched roster entry keeps remote-only behavior`() {
        val sourceId = ClientId("source")

        assertEquals(
            MirrorNotificationOpenRoute.REMOTE_ONLY,
            mirrorNotificationOpenRoute(sourceId, ClientId("this-device"), null),
        )
        assertEquals(
            MirrorNotificationOpenRoute.REMOTE_ONLY,
            mirrorNotificationOpenRoute(sourceId, ClientId("this-device"), device(ClientId("other"))),
        )
    }

    @Test
    fun `every trust and identity gate is required`() {
        val ownId = ClientId("this-device")
        listOf(
            device(ownDevice = false),
            device(status = TrustStatus.PENDING_TRUST),
            device(keyAvailable = false),
            device(verified = false),
            device(currentEpoch = 0),
        ).forEach { source ->
            assertEquals(
                MirrorNotificationOpenRoute.REMOTE_ONLY,
                mirrorNotificationOpenRoute(source.clientId, ownId, source),
            )
        }
        val source = device()
        assertEquals(
            MirrorNotificationOpenRoute.REMOTE_ONLY,
            mirrorNotificationOpenRoute(
                source.clientId,
                ownId,
                source,
                originPlatform = OriginPlatform.ANDROID_LOCAL,
                trustQuarantined = true,
            ),
        )
    }

    @Test
    fun `complete source protocol and one hardware encoder are required`() {
        val ownId = ClientId("this-device")
        REQUIRED.forEach { missing ->
            val source = device(capabilities = FULL_CAPABILITIES - missing)
            assertEquals(
                MirrorNotificationOpenRoute.REMOTE_ONLY,
                mirrorNotificationOpenRoute(source.clientId, ownId, source),
            )
        }
        assertEquals(
            MirrorNotificationOpenRoute.REMOTE_ONLY,
            device(capabilities = REQUIRED).let {
                mirrorNotificationOpenRoute(it.clientId, ownId, it)
            },
        )
        ENCODERS.forEach { encoder ->
            val source = device(capabilities = REQUIRED + encoder)
            assertEquals(
                MirrorNotificationOpenRoute.SCREEN_MIRROR,
                mirrorNotificationOpenRoute(source.clientId, ownId, source),
            )
        }
    }

    private fun device(
        clientId: ClientId = ClientId("source"),
        ownDevice: Boolean = true,
        status: TrustStatus = TrustStatus.TRUSTED,
        keyAvailable: Boolean = true,
        verified: Boolean = true,
        currentEpoch: Int = 2,
        capabilities: Set<Capability> = FULL_CAPABILITIES,
    ) = RosterDevice(
        clientId = clientId,
        status = status,
        displayName = "Source",
        keyAvailable = keyAvailable,
        introducedByName = null,
        revokedAt = null,
        ownDevice = ownDevice,
        currentEpoch = currentEpoch,
        platform = "android",
        capabilities = capabilities.toList(),
        verified = verified,
    )

    private companion object {
        val REQUIRED = setOf(
            Capability.CAPABILITY_ROUTING_V1,
            Capability.SCREEN_MIRROR_SOURCE_V1,
            Capability.SCREEN_MIRROR_CONTROL_V1,
            Capability.SCREEN_MIRROR_CLIPBOARD_TEXT_V1,
        )
        val ENCODERS = setOf(
            Capability.SCREEN_MIRROR_ENCODER_H264_HW,
            Capability.SCREEN_MIRROR_ENCODER_H265_HW,
            Capability.SCREEN_MIRROR_ENCODER_AV1_HW,
        )
        val FULL_CAPABILITIES = REQUIRED + ENCODERS
    }
}

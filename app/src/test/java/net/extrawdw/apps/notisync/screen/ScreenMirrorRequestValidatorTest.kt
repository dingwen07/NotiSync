package net.extrawdw.apps.notisync.screen

import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ScreenMirrorAction
import net.extrawdw.notisync.protocol.ScreenMirrorCodec
import net.extrawdw.notisync.protocol.ScreenMirrorConnectionCandidate
import net.extrawdw.notisync.protocol.ScreenMirrorStatus
import net.extrawdw.notisync.protocol.ScreenMirrorSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenMirrorRequestValidatorTest {
    private val source = ClientId("source-peer")
    private val requester = ClientId("requester-peer")
    private val now = 1_000_000L

    private fun request(
        issuedAt: Long = now,
        expiresAt: Long = now + 300_000L,
        token: ByteArray = ByteArray(16) { 1 },
        psk: ByteArray = ByteArray(32) { 2 },
        codec: ScreenMirrorCodec = ScreenMirrorCodec.H264,
    ) = ScreenMirrorSync(
        action = ScreenMirrorAction.REQUEST,
        sessionId = "session-1",
        requesterPeerId = requester,
        sourcePeerId = source,
        issuedAt = issuedAt,
        expiresAt = expiresAt,
        routingToken = token,
        masterPsk = psk,
        codec = codec,
        requestControl = true,
        requestClipboard = true,
        maxDimension = 1920,
        maxFps = 60,
        videoBitrateBps = 8_000_000,
        candidates = listOf(
            ScreenMirrorConnectionCandidate(
                kind = ScreenMirrorConnectionCandidate.LAN_TCP,
                host = "192.168.1.9",
                port = 27183,
            ),
        ),
    )

    @Test
    fun validAuthorizedRequest_isAccepted() {
        assertNull(
            ScreenMirrorRequestValidator.validate(
                request(), requester, source, now, now, authorized = true, codecAvailable = true,
            ),
        )
    }

    @Test
    fun authenticatedSenderMustMatchRequest() {
        val failure = ScreenMirrorRequestValidator.validate(
            request(), ClientId("different"), source, now, now,
            authorized = true, codecAvailable = true,
        )
        assertEquals(ScreenMirrorStatus.UNAUTHORIZED, failure?.status)
    }

    @Test
    fun expiredAndOverlongRequests_areRejected() {
        val expired = ScreenMirrorRequestValidator.validate(
            request(issuedAt = now - 1_000, expiresAt = now), requester, source, now - 1_000, now,
            authorized = true, codecAvailable = true,
        )
        val tooLong = ScreenMirrorRequestValidator.validate(
            request(expiresAt = now + 300_001), requester, source, now, now,
            authorized = true, codecAvailable = true,
        )
        assertEquals(ScreenMirrorStatus.EXPIRED, expired?.status)
        assertEquals(ScreenMirrorStatus.EXPIRED, tooLong?.status)
    }

    @Test
    fun signedEnvelopeClockSkew_isRejected() {
        val failure = ScreenMirrorRequestValidator.validate(
            request(issuedAt = now + 1, expiresAt = now + 300_000),
            requester,
            source,
            now + 120_001,
            now,
            authorized = true,
            codecAvailable = true,
        )
        assertEquals(ScreenMirrorStatus.EXPIRED, failure?.status)
    }

    @Test
    fun malformedSecretsAndUnavailableCodec_failClosed() {
        val badSecret = ScreenMirrorRequestValidator.validate(
            request(token = ByteArray(15)), requester, source, now, now,
            authorized = true, codecAvailable = true,
        )
        val unavailable = ScreenMirrorRequestValidator.validate(
            request(), requester, source, now, now,
            authorized = true, codecAvailable = false,
        )
        assertEquals(ScreenMirrorStatus.TRANSPORT_FAILED, badSecret?.status)
        assertEquals(ScreenMirrorStatus.CODEC_UNAVAILABLE, unavailable?.status)
    }

    @Test
    fun unknownCandidateKind_isIgnoredWhenLanCandidateExists() {
        val mixed = request().copy(
            candidates = listOf(
                ScreenMirrorConnectionCandidate(kind = "FUTURE_P2P", serviceName = "future"),
                ScreenMirrorConnectionCandidate(
                    kind = ScreenMirrorConnectionCandidate.DNS_SD,
                    serviceName = "instance-1",
                ),
            ),
        )
        assertNull(
            ScreenMirrorRequestValidator.validate(
                mixed, requester, source, now, now, authorized = true, codecAvailable = true,
            ),
        )
    }

    @Test
    fun wifiAwareCandidate_requiresSignedPortAndCanonicalServiceName() {
        val aware = request().copy(
            candidates = listOf(
                ScreenMirrorConnectionCandidate(
                    kind = ScreenMirrorConnectionCandidate.WIFI_AWARE,
                    port = 31891,
                    serviceName = "notisync-screen-0123456789abcdef0123456789abcdef",
                ),
            ),
        )
        assertNull(
            ScreenMirrorRequestValidator.validate(
                aware, requester, source, now, now, authorized = true, codecAvailable = true,
            ),
        )

        listOf(
            aware.copy(candidates = listOf(aware.candidates.single().copy(port = null))),
            aware.copy(candidates = listOf(aware.candidates.single().copy(serviceName = "屏幕"))),
            aware.copy(candidates = listOf(aware.candidates.single().copy(serviceName = "notisync-screen-a1b2"))),
            aware.copy(candidates = listOf(aware.candidates.single().copy(interfaceName = "wlan0"))),
        ).forEach { malformed ->
            assertEquals(
                ScreenMirrorStatus.TRANSPORT_FAILED,
                ScreenMirrorRequestValidator.validate(
                    malformed, requester, source, now, now,
                    authorized = true, codecAvailable = true,
                )?.status,
            )
        }
    }
}

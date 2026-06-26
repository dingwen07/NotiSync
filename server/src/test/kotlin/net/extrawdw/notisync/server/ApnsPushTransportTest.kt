package net.extrawdw.notisync.server

import kotlinx.coroutines.runBlocking
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.RouteEnvironment
import net.extrawdw.notisync.protocol.TransportType
import net.extrawdw.notisync.protocol.Urgency
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.http.HttpRequest

class ApnsPushTransportTest {
    @Test
    fun tokenScopedApnsRejectionsInvalidateRoute() = runBlocking {
        val transport = apnsTransport(ApnsResponse(400, """{"reason":"BadDeviceToken"}"""))

        assertEquals(PushOutcome.ROUTE_INVALID, transport.wake(apnsRoute(), pushData(), Urgency.HIGH))
    }

    @Test
    fun providerAuthAndConfigRejectionsDoNotInvalidateRoute() = runBlocking {
        val expiredToken = apnsTransport(ApnsResponse(403, """{"reason":"ExpiredProviderToken"}"""))
        val badTopic = apnsTransport(ApnsResponse(400, """{"reason":"BadTopic"}"""))

        // 403 provider-token rejection is retryable (the token is re-minted); a 400 config error is permanent
        // (retrying as-sent can't fix it) — but neither implies the device token is invalid, so the route stays.
        assertEquals(PushOutcome.TRANSIENT_FAILURE, expiredToken.wake(apnsRoute(), pushData(), Urgency.HIGH))
        assertEquals(PushOutcome.PERMANENT_FAILURE, badTopic.wake(apnsRoute(), pushData(), Urgency.HIGH))
    }

    @Test
    fun providerTokenRejectionInvalidatesCachedToken() = runBlocking {
        var invalidations = 0
        val provider = object : ApnsTokenProvider {
            override fun token() = "jwt"
            override fun invalidate() { invalidations++ }
        }
        val transport = ApnsPushTransport(
            topic = APNS_TOPIC,
            ttlMillis = 60_000,
            tokenProvider = provider,
            client = ApnsClient { ApnsResponse(403, """{"reason":"ExpiredProviderToken"}""") },
        )

        assertEquals(PushOutcome.TRANSIENT_FAILURE, transport.wake(apnsRoute(), pushData(), Urgency.HIGH))
        assertEquals("a 403 must drop the cached provider token so the next send re-mints", 1, invalidations)
    }

    @Test
    fun malformedApnsRouteRefIsInvalidWithoutCallingProvider() = runBlocking {
        var calls = 0
        val transport = ApnsPushTransport(
            topic = APNS_TOPIC,
            ttlMillis = 60_000,
            tokenProvider = ApnsTokenProvider { "jwt" },
            client = ApnsClient {
                calls++
                ApnsResponse(200, "")
            },
        )

        assertEquals(PushOutcome.ROUTE_INVALID, transport.wake(apnsRoute(routeRef = "bad/token"), pushData(), Urgency.HIGH))
        assertEquals(0, calls)
    }

    @Test
    fun backgroundWakeUsesSandboxEndpointAndRequiredHeaders() = runBlocking {
        val requests = mutableListOf<HttpRequest>()
        val transport = apnsTransport(ApnsResponse(200, ""), requests)

        assertEquals(
            PushOutcome.DELIVERED,
            transport.wake(apnsRoute(routeRef = "ABCD1234", environment = RouteEnvironment.DEVELOPMENT), pushData(), Urgency.NORMAL),
        )

        val request = requests.single()
        assertEquals("https://api.sandbox.push.apple.com/3/device/ABCD1234", request.uri().toString())
        assertEquals("bearer jwt", request.headers().firstValue("authorization").orElse(null))
        assertEquals(APNS_TOPIC, request.headers().firstValue("apns-topic").orElse(null))
        assertEquals("background", request.headers().firstValue("apns-push-type").orElse(null))
        assertEquals("5", request.headers().firstValue("apns-priority").orElse(null))
        assertEquals("application/json", request.headers().firstValue("content-type").orElse(null))
    }

    private fun apnsTransport(
        response: ApnsResponse,
        requests: MutableList<HttpRequest> = mutableListOf(),
    ) = ApnsPushTransport(
        topic = APNS_TOPIC,
        ttlMillis = 60_000,
        tokenProvider = ApnsTokenProvider { "jwt" },
        client = ApnsClient { request ->
            requests += request
            response
        },
    )

    private fun apnsRoute(
        routeRef: String = "abcd1234",
        environment: RouteEnvironment = RouteEnvironment.PRODUCTION,
    ) = StoredRoute(
        clientId = ClientId("client-apns"),
        transport = TransportType.APNS,
        environment = environment,
        routeRef = routeRef,
        epoch = 1,
        inlinePayloadLimitBytes = 3072,
        signedBlob = ByteArray(0),
    )

    private fun pushData() = mapOf("typ" to "wake", "mid" to "01J0APNS001")

    private companion object {
        const val APNS_TOPIC = "net.extrawdw.apps.NotiSync"
    }
}

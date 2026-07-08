package net.extrawdw.notisync.server

import kotlinx.coroutines.runBlocking
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.RouteEnvironment
import net.extrawdw.notisync.protocol.TransportType
import net.extrawdw.notisync.protocol.Urgency
import net.extrawdw.notisync.server.delivery.PushOutcome
import net.extrawdw.notisync.server.delivery.push.ApnsClient
import net.extrawdw.notisync.server.delivery.push.ApnsPushTransport
import net.extrawdw.notisync.server.delivery.push.ApnsResponse
import net.extrawdw.notisync.server.delivery.push.ApnsTokenProvider
import net.extrawdw.notisync.server.data.StoredRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.net.http.HttpRequest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit

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
            tokenProviders = providers(provider),
            client = ApnsClient { ApnsResponse(403, """{"reason":"ExpiredProviderToken"}""") },
        )

        assertEquals(PushOutcome.TRANSIENT_FAILURE, transport.wake(apnsRoute(), pushData(), Urgency.HIGH))
        assertEquals("a 403 must drop the cached provider token so the next send re-mints", 1, invalidations)
    }

    @Test
    fun invalidProviderTokenDoesNotRemint() = runBlocking {
        var invalidations = 0
        val provider = object : ApnsTokenProvider {
            override fun token() = "jwt"
            override fun invalidate() { invalidations++ }
        }
        val transport = ApnsPushTransport(
            topic = APNS_TOPIC,
            ttlMillis = 60_000,
            tokenProviders = providers(provider),
            client = ApnsClient { ApnsResponse(403, """{"reason":"InvalidProviderToken"}""") },
        )

        // InvalidProviderToken is a key/kid/team misconfig — re-minting the same token on every send storms
        // APNs into 429 TooManyProviderTokenUpdates, so it must NOT invalidate the cached token.
        assertEquals(PushOutcome.TRANSIENT_FAILURE, transport.wake(apnsRoute(), pushData(), Urgency.HIGH))
        assertEquals("InvalidProviderToken must not re-mint", 0, invalidations)
    }

    @Test
    fun malformedApnsRouteRefIsInvalidWithoutCallingProvider() = runBlocking {
        var calls = 0
        val transport = ApnsPushTransport(
            topic = APNS_TOPIC,
            ttlMillis = 60_000,
            tokenProviders = providers(ApnsTokenProvider { "jwt" }),
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

    @Test
    fun notificationUsesAlertPushTypeAndPriority() = runBlocking {
        val requests = mutableListOf<HttpRequest>()
        val transport = apnsTransport(ApnsResponse(200, ""), requests)

        assertEquals(
            PushOutcome.DELIVERED,
            transport.wake(apnsRoute(routeRef = "ABCD1234"), notificationData(), Urgency.HIGH),
        )

        val request = requests.single()
        // A NOTIFICATION must be an alert push at priority 10 so the NSE wakes to decrypt + display.
        assertEquals("alert", request.headers().firstValue("apns-push-type").orElse(null))
        assertEquals("10", request.headers().firstValue("apns-priority").orElse(null))
        val body = requestBody(request)
        assertTrue(body.contains(""""mutable-content":1"""))
        assertTrue(body.contains(""""category":"notisync.mirror""""))
    }

    private fun apnsTransport(
        response: ApnsResponse,
        requests: MutableList<HttpRequest> = mutableListOf(),
    ) = ApnsPushTransport(
        topic = APNS_TOPIC,
        ttlMillis = 60_000,
        tokenProviders = providers(ApnsTokenProvider { "jwt" }),
        client = ApnsClient { request ->
            requests += request
            response
        },
    )

    private fun providers(p: ApnsTokenProvider) =
        mapOf(RouteEnvironment.PRODUCTION to p, RouteEnvironment.DEVELOPMENT to p)

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

    private fun notificationData() = mapOf("mtyp" to "NOTIFICATION", "typ" to "notif", "mid" to "01J0APNS002", "ct" to "AAEC")

    private fun requestBody(request: HttpRequest): String {
        val publisher = request.bodyPublisher().orElseThrow()
        val bytes = ByteArrayOutputStream()
        val done = CompletableFuture<Unit>()
        publisher.subscribe(object : Flow.Subscriber<ByteBuffer> {
            override fun onSubscribe(subscription: Flow.Subscription) {
                subscription.request(Long.MAX_VALUE)
            }

            override fun onNext(item: ByteBuffer) {
                val copy = item.slice()
                val chunk = ByteArray(copy.remaining())
                copy.get(chunk)
                bytes.write(chunk)
            }

            override fun onError(throwable: Throwable) {
                done.completeExceptionally(throwable)
            }

            override fun onComplete() {
                done.complete(Unit)
            }
        })
        done.get(5, TimeUnit.SECONDS)
        return bytes.toString(Charsets.UTF_8.name())
    }

    private companion object {
        const val APNS_TOPIC = "net.extrawdw.apps.NotiSync"
    }
}

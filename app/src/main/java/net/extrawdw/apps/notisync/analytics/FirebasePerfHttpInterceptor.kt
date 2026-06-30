package net.extrawdw.apps.notisync.analytics

import com.google.firebase.perf.FirebasePerformance
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Records a Firebase Performance HttpMetric for each broker call. We instrument the OkHttp engine
 * EXPLICITLY rather than rely on Firebase's automatic network monitoring for two reasons:
 *  1. the transport is Ktor-over-OkHttp, and the auto bytecode hook is unreliable for Ktor's async
 *     `enqueue` path, so requests would go largely unrecorded;
 *  2. the broker's REST paths embed per-message ids (clientId / opaque assetId / UUID messageId), which
 *     would blow past Firebase's ~100 URL-pattern cap and fragment the dashboard — so we normalise them.
 *
 * Safe by construction: every Firebase call is guarded, so a missing `FirebaseApp` can never break a
 * request, and the SDK drops the metric entirely once the user opts out (see [AnalyticsController]).
 */
class FirebasePerfHttpInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        // Skip the WebSocket upgrade (/v2/connect): an application interceptor would otherwise record it
        // as a long-lived 101 "request", and the dedicated `ws_connect` trace already times the handshake.
        if (request.header("Sec-WebSocket-Key") != null ||
            request.header("Upgrade").equals("websocket", ignoreCase = true)
        ) {
            return chain.proceed(request)
        }

        val metric = runCatching {
            FirebasePerformance.getInstance()
                .newHttpMetric(normalizeUrl(request.url), request.method)
                .also { it.start() }
        }.getOrNull()
        // RequestBody.contentLength() may throw (declared IOException); guard it.
        runCatching {
            request.body?.contentLength()?.takeIf { it >= 0 }?.let { metric?.setRequestPayloadSize(it) }
        }
        return try {
            chain.proceed(request).also { response ->
                runCatching {
                    metric?.setHttpResponseCode(response.code)
                    response.header("Content-Type")?.let { metric?.setResponseContentType(it) }
                    response.body?.contentLength()?.takeIf { it >= 0 }
                        ?.let { metric?.setResponsePayloadSize(it) }
                }
            }
        } finally {
            runCatching { metric?.stop() }
        }
    }

    /**
     * Collapse high-cardinality path segments (clientIds, opaque assetIds, UUID messageIds — all ≥16
     * chars) to a placeholder so requests aggregate per-route. `_id_` keeps the URL valid; `{id}` would
     * make Firebase reject the metric as a malformed URL and silently drop it. Static route segments
     * (`v2`, `send`, `assets`, `relay`, `integrity`, `healthz`, …) are all shorter and pass through.
     */
    private fun normalizeUrl(url: HttpUrl): String {
        val path = url.encodedPathSegments.joinToString("/") { seg ->
            if (seg.length >= 16) "_id_" else seg
        }
        return "${url.scheme}://${url.host}/$path"
    }
}

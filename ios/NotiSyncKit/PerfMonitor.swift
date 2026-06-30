import Foundation

#if canImport(FirebasePerformance)
import FirebasePerformance
#endif
#if canImport(FirebaseCrashlytics)
import FirebaseCrashlytics
#endif

/// A live custom trace. Wraps a Firebase `Trace` where the SDK is linked (the app target) and is an inert
/// no-op everywhere else (the Notification Service Extension does not link Firebase). Create one with
/// `PerfMonitor.startSpan(_:)`, annotate it, and `stop()` it. A span is NOT `Sendable` — keep it on the
/// actor/task that created it and never let it cross a `@Sendable` boundary (e.g. into a `Task.detached`
/// closure); for fire-and-forget measurements use `PerfMonitor.recordValueTrace(_:)` instead.
nonisolated struct PerfSpan {
    #if canImport(FirebasePerformance)
    fileprivate let trace: Trace?
    fileprivate init(trace: Trace?) { self.trace = trace }
    #else
    fileprivate init() {}
    #endif

    /// Set a low-cardinality string dimension (≤5 per trace). Never pass an id, token, name, or content.
    func attribute(_ name: String, _ value: String) {
        #if canImport(FirebasePerformance)
        trace?.setValue(value, forAttribute: name)
        #endif
    }

    /// Record an absolute Int64 metric value.
    func metric(_ name: String, _ value: Int64) {
        #if canImport(FirebasePerformance)
        trace?.setValue(value, forMetric: name)
        #endif
    }

    func increment(_ name: String, by amount: Int64 = 1) {
        #if canImport(FirebasePerformance)
        trace?.incrementMetric(name, by: amount)
        #endif
    }

    func stop() {
        #if canImport(FirebasePerformance)
        trace?.stop()
        #endif
    }
}

/// Firebase Performance Monitoring facade. Centralizes the (optional) Firebase dependency so the rest of the
/// app calls plain, Sendable-safe helpers and the Notification Service Extension — which does not link
/// Firebase — compiles unchanged (every call becomes a no-op).
///
/// Why this exists beyond convenience: NotiSync's networking uses the Swift concurrency `URLSession`
/// (`data(for:)` / `data(from:)`), which Firebase's *automatic* network instrumentation does not swizzle —
/// so none of the broker/relay/iTunes traffic is captured out of the box. `http(...)` wraps those calls in a
/// manual `HTTPMetric`, restoring visibility AND letting us template id-bearing paths (`/v2/relay/_id_`) so
/// they don't blow Firebase's per-URL-pattern budget.
nonisolated enum PerfMonitor {

    /// Apply the data-collection preference to the Firebase telemetry SDKs (Performance + Crashlytics). Call
    /// once after `FirebaseApp.configure()`; re-call when a user toggles the setting. Mirrors Android's
    /// `AnalyticsController`. When `false`, no traces, HTTP metrics, or crash reports are collected/uploaded.
    static func configure(dataCollectionEnabled: Bool) {
        #if canImport(FirebasePerformance)
        Performance.sharedInstance().isDataCollectionEnabled = dataCollectionEnabled
        #endif
        #if canImport(FirebaseCrashlytics)
        Crashlytics.crashlytics().setCrashlyticsCollectionEnabled(dataCollectionEnabled)
        #endif
    }

    static var isEnabled: Bool {
        #if canImport(FirebasePerformance)
        return Performance.sharedInstance().isDataCollectionEnabled
        #else
        return false
        #endif
    }

    /// Start a live custom trace. `stop()` the returned span to record it.
    static func startSpan(_ name: String) -> PerfSpan {
        #if canImport(FirebasePerformance)
        guard isEnabled else { return PerfSpan(trace: nil) }
        return PerfSpan(trace: Performance.startTrace(name: name))
        #else
        return PerfSpan()
        #endif
    }

    /// Record a one-shot trace with attributes + metric values already known. The trace's own wall-time is
    /// ~0; the measurement lives in the metrics (e.g. a `duration_ms` measured elsewhere). Used to replay
    /// metrics captured where a live span can't run — the NSE (no Firebase) and pre-`configure()` launch
    /// sites — after the producer hands them over via `PerfEventStore`.
    static func recordValueTrace(_ name: String, attributes: [String: String] = [:], metrics: [String: Int64] = [:]) {
        #if canImport(FirebasePerformance)
        guard isEnabled, let trace = Performance.startTrace(name: name) else { return }
        for (key, value) in attributes { trace.setValue(value, forAttribute: key) }
        for (key, value) in metrics { trace.setValue(value, forMetric: key) }
        trace.stop()
        #endif
    }

    /// Wrap an async `URLSession` call in a manual `HTTPMetric` so it appears in Performance Monitoring (the
    /// async/await `URLSession` API is not auto-instrumented). `template` is the normalized path used as the
    /// metric's URL (e.g. `/v2/relay/_id_`) so id-bearing requests aggregate to one pattern. The real request
    /// still goes wherever `perform` sends it — `template` only labels the metric.
    @discardableResult
    static func http(
        url: URL?,
        method: String,
        requestBytes: Int,
        template: String,
        _ perform: @Sendable () async throws -> (Data, URLResponse)
    ) async rethrows -> (Data, URLResponse) {
        #if canImport(FirebasePerformance)
        guard isEnabled, let url, let metricURL = displayURL(host: url, template: template) else {
            return try await perform()
        }
        let metric = HTTPMetric(url: metricURL, httpMethod: httpMethod(method))
        metric?.start()
        if requestBytes > 0 { metric?.requestPayloadSize = requestBytes }
        do {
            let (data, response) = try await perform()
            if let http = response as? HTTPURLResponse { metric?.responseCode = http.statusCode }
            metric?.responsePayloadSize = data.count
            metric?.stop()
            return (data, response)
        } catch {
            metric?.stop()
            throw error
        }
        #else
        return try await perform()
        #endif
    }

    #if canImport(FirebasePerformance)
    /// Build the metric's display URL: the real scheme/host/port + the normalized template path. Keeps the
    /// recognizable host while collapsing id segments, so the dashboard groups by endpoint.
    private static func displayURL(host url: URL, template: String) -> URL? {
        var components = URLComponents()
        components.scheme = url.scheme ?? "https"
        components.host = url.host
        components.port = url.port
        components.path = template.hasPrefix("/") ? template : "/" + template
        return components.url
    }

    private static func httpMethod(_ method: String) -> HTTPMethod {
        switch method.uppercased() {
        case "POST": return .post
        case "PUT": return .put
        case "DELETE": return .delete
        case "HEAD": return .head
        case "PATCH": return .patch
        case "OPTIONS": return .options
        case "TRACE": return .trace
        case "CONNECT": return .connect
        default: return .get
        }
    }
    #endif
}

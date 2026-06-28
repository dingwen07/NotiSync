import Foundation

/// Client integrity attestation, pluggable so the broker-side dispatch (Play Integrity / App Check /
/// App Attest) is mirrored here. The token is bound to the request by the identity signature, so a
/// cached token is fine. Returns nil when attestation is unavailable — the request still goes out
/// signed, and an attestation-disabled broker accepts it.
nonisolated protocol Attestor: Sendable {
    var attestationType: String { get }
    func token() async -> String?
}

nonisolated enum AttestorError: Error { case unavailable }

/// No attestation — used against an attestation-disabled broker, or before Firebase is configured.
nonisolated struct NoAttestor: Attestor {
    let attestationType = AttestationType.firebaseAppCheck
    func token() async -> String? { nil }
}

#if canImport(FirebaseAppCheck)
import FirebaseAppCheck
import FirebaseCore

/// Firebase App Check attestor. Requires `FirebaseApp.configure()` + a provider factory at launch
/// (see `FirebaseBootstrap.configure()`), a `GoogleService-Info.plist` for this iOS app, and the app's
/// `mobilesdk_app_id` added to the broker's `NOTISYNC_APPCHECK_APP_IDS`.
nonisolated struct FirebaseAppCheckAttestor: Attestor {
    let attestationType = AttestationType.firebaseAppCheck

    func token() async -> String? {
        await withCheckedContinuation { cont in
            AppCheck.appCheck().token(forcingRefresh: false) { token, _ in
                cont.resume(returning: token?.token)
            }
        }
    }
}

/// App Attest in release, the Firebase debug provider in DEBUG (register the printed debug token in the
/// Firebase console). DeviceCheck is the pre-App-Attest fallback.
final class NotiSyncAppCheckProviderFactory: NSObject, AppCheckProviderFactory {
    func createProvider(with app: FirebaseApp) -> (any AppCheckProvider)? {
        #if DEBUG
        return AppCheckDebugProvider(app: app)
        #else
        if #available(iOS 14.0, *) { return AppAttestProvider(app: app) }
        return DeviceCheckProvider(app: app)
        #endif
    }
}
#endif

/// Configure Firebase + App Check at launch. No-op until the Firebase SDK is linked. Call on the main thread.
nonisolated enum FirebaseBootstrap {
    #if canImport(FirebaseAppCheck)
    @MainActor private static var didConfigureDefaultApp = false
    #endif

    @MainActor
    static func configure() {
        #if canImport(FirebaseAppCheck)
        guard !didConfigureDefaultApp,
              Bundle.main.url(forResource: "GoogleService-Info", withExtension: "plist") != nil else { return }
        // Set the provider factory before configuring. Avoid probing FirebaseApp.app() here: Firebase logs
        // I-COR000003 when the default app has not been configured yet, even if configure() happens next.
        AppCheck.setAppCheckProviderFactory(NotiSyncAppCheckProviderFactory())
        FirebaseApp.configure()
        didConfigureDefaultApp = true
        #endif
    }

    /// The attestor the broker client should use — Firebase once configured, else a no-op (the request
    /// still goes out signed; an attestation-disabled broker accepts it).
    @MainActor
    static func attestor() -> Attestor {
        #if canImport(FirebaseAppCheck)
        if didConfigureDefaultApp { return FirebaseAppCheckAttestor() }
        #endif
        return NoAttestor()
    }
}

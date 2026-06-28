import Foundation

/// How an inbound envelope reached us — surfaced in the UI / diagnostics.
///
/// The raw values are STABLE tokens (not display text): they're persisted in the shared store and the
/// SwiftData Inbox, and shared between the app and the Notification Service Extension. Localization happens
/// at render time in `LocalizedText.deliveryMode(_:)` — never store user-facing English here.
nonisolated enum DeliveryMode: String {
    case localPreview
    case apnsInline
    case apnsRelayFetch
    case foregroundWebSocket
    case foregroundDrain
    case backgroundRefresh
    /// The NSE displayed the mirror from inline ciphertext carried by the alert push.
    case apnsAlertInline
    /// The NSE displayed the mirror after pulling an oversized envelope from the relay.
    case apnsAlertRelay
}

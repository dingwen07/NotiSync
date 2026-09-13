import Foundation

/// The single presentation seam between the model layer's stable tokens and user-facing, localized text.

enum LocalizedText {

    // MARK: This device

    static var pendingClientId: String {
        String(localized: "status.clientId.pending",
               defaultValue: "pending",
               comment: "Shown while this device's client identifier is still being created.")
    }

    /// Placeholder for an empty / not-yet-available diagnostic value.
    static var none: String {
        String(localized: "status.value.none",
               defaultValue: "None",
               comment: "Generic empty status value.")
    }

    /// Shown for the APNs row before a device token has been registered.
    static var unregistered: String {
        String(localized: "status.value.unregistered",
               defaultValue: "Unregistered",
               comment: "Generic unregistered status value.")
    }

    // MARK: Status enums

    static func pushStatus(_ status: PushStatus) -> String {
        switch status {
        case .unregistered:
            return String(localized: "status.value.unregistered",
                          defaultValue: "Unregistered",
                          comment: "Generic unregistered status value.")
        case .apnsRegistered:
            return String(localized: "status.push.apnsRegistered",
                          defaultValue: "APNs registered",
                          comment: "Push status when APNs registration succeeds.")
        case .apnsFailed:
            return String(localized: "status.push.apnsFailed",
                          defaultValue: "APNs failed",
                          comment: "Push status when APNs registration fails.")
        case .apnsUnregistered:
            return String(localized: "status.push.apnsUnregistered",
                          defaultValue: "APNs unregistered",
                          comment: "Push status when no APNs token is available.")
        case .routePublished:
            return String(localized: "status.push.routePublished",
                          defaultValue: "Route published",
                          comment: "Push status when the current APNs route was published.")
        case .routePending:
            return String(localized: "status.push.routePending",
                          defaultValue: "Route pending",
                          comment: "Push status when route publication is pending.")
        }
    }

    static func brokerStatus(reachability: BrokerReachability, version: String?) -> String {
        switch reachability {
        case .unknown:
            return String(localized: "status.value.unknown",
                          defaultValue: "Unknown",
                          comment: "Generic unknown status value.")
        case .unreachable:
            return String(localized: "status.broker.unreachable",
                          defaultValue: "Unreachable",
                          comment: "Broker status when the broker cannot be reached.")
        case .reachable:
            let format = String(localized: "status.broker.reachableVersion",
                                defaultValue: "Reachable • v%@",
                                comment: "Broker status when the broker health check succeeds. The placeholder is the broker version.")
            return String(format: format, locale: .current, version ?? "")
        case .verified:
            let format = String(localized: "status.broker.verifiedVersion",
                                defaultValue: "Verified • v%@",
                                comment: "Broker status when verification succeeds. The placeholder is the broker version.")
            return String(format: format, locale: .current, version ?? "")
        }
    }

    static func notificationPermission(_ status: NotificationPermissionStatus) -> String {
        switch status {
        case .unknown:
            return String(localized: "status.value.unknown",
                          defaultValue: "Unknown",
                          comment: "Generic unknown status value.")
        case .granted:
            return String(localized: "status.notification.granted",
                          defaultValue: "Granted",
                          comment: "Notification permission has been granted.")
        case .denied:
            return String(localized: "status.notification.denied",
                          defaultValue: "Denied",
                          comment: "Notification permission has been denied.")
        case .notRequested:
            return String(localized: "status.notification.notRequested",
                          defaultValue: "Not requested",
                          comment: "Notification permission has not been requested yet.")
        }
    }

    static func pairingStatus(_ status: PairingStatus) -> String {
        switch status {
        case .unpaired:
            return String(localized: "status.pairing.unpaired",
                          defaultValue: "Unpaired",
                          comment: "Pairing status when no peer device is paired.")
        case .paired:
            return String(localized: "status.pairing.paired",
                          defaultValue: "Paired",
                          comment: "Pairing status when at least one peer device is paired.")
        }
    }

    static func deliveryMode(_ raw: String) -> String {
        guard let mode = DeliveryMode(rawValue: raw) else { return raw }
        switch mode {
        case .localPreview:
            return String(localized: "delivery.localPreview",
                          defaultValue: "Local preview",
                          comment: "Delivery mode for a local preview notification.")
        case .apnsInline:
            return String(localized: "delivery.apnsInline",
                          defaultValue: "APNs Inline",
                          comment: "Delivery mode for an inline APNs payload.")
        case .apnsRelayFetch:
            return String(localized: "delivery.apnsRelayFetch",
                          defaultValue: "APNs + Fetch",
                          comment: "Delivery mode for an APNs wake followed by a relay fetch.")
        case .foregroundWebSocket:
            return String(localized: "delivery.foregroundWebSocket",
                          defaultValue: "Foreground WebSocket",
                          comment: "Delivery mode for foreground WebSocket delivery.")
        case .foregroundDrain:
            return String(localized: "delivery.foregroundDrain",
                          defaultValue: "Foreground relay drain",
                          comment: "Delivery mode for a foreground relay drain.")
        case .backgroundRefresh:
            return String(localized: "delivery.backgroundRefresh",
                          defaultValue: "Background refresh",
                          comment: "Delivery mode for background refresh delivery.")
        case .apnsAlertInline:
            return String(localized: "delivery.apnsAlertInline",
                          defaultValue: "APNs Inline (NSE)",
                          comment: "Delivery mode for an alerting APNs notification carrying inline ciphertext.")
        case .apnsAlertRelay:
            return String(localized: "delivery.apnsAlertRelayFetch",
                          defaultValue: "APNs + Fetch (NSE)",
                          comment: "Delivery mode for an alerting APNs notification followed by a relay fetch.")
        }
    }

    static func routeEnvironment(_ value: RouteEnvironment) -> String {
        routeEnvironment(rawValue: value.rawValue)
    }

    static func routeEnvironment(rawValue: String) -> String {
        switch rawValue {
        case "PRODUCTION":
            return String(localized: "routeEnvironment.production",
                          defaultValue: "Production",
                          comment: "APNs production environment.")
        case "DEVELOPMENT":
            return String(localized: "routeEnvironment.development",
                          defaultValue: "Development",
                          comment: "APNs development environment.")
        default:
            return rawValue
        }
    }

    static func platform(_ value: String) -> String {
        switch value.lowercased() {
        case "ios":
            return String(localized: "platform.ios",
                          defaultValue: "iOS",
                          comment: "Apple iOS platform label.")
        case "android":
            return String(localized: "platform.android",
                          defaultValue: "Android",
                          comment: "Android platform label.")
        default:
            return value
        }
    }

    static func trustStatus(_ status: TrustStatusRaw) -> String {
        switch status {
        case .thisDevice:
            return String(localized: "trustStatus.thisDevice",
                          defaultValue: "This device",
                          comment: "Device trust status for the current device.")
        case .pendingTrust:
            return String(localized: "trustStatus.pendingTrust",
                          defaultValue: "Pending trust",
                          comment: "Device trust status for a device awaiting approval.")
        case .trusted:
            return String(localized: "trustStatus.trusted",
                          defaultValue: "Trusted",
                          comment: "Device trust status for a trusted device.")
        case .pendingRevoke:
            return String(localized: "trustStatus.pendingRevoke",
                          defaultValue: "Pending removal",
                          comment: "Device trust status for a device awaiting removal confirmation.")
        case .revoked:
            return String(localized: "trustStatus.revoked",
                          defaultValue: "Removed",
                          comment: "Device trust status for a removed device.")
        case .quarantined:
            return String(localized: "trustStatus.quarantined",
                          defaultValue: "Quarantined",
                          comment: "Device trust status for a quarantined device.")
        }
    }

    // MARK: Activity log

    static func activityTitle(_ record: ActivityRecord) -> String {
        switch record.titleToken {
        case .appLabel:
            return record.titleArg
        case .error:
            return errorDomain(ErrorDomain(rawValue: record.titleArg))
        case .dismissedLocally:
            return String(localized: "activity.title.dismissedLocally",
                          defaultValue: "Dismissed locally",
                          comment: "Activity title for a notification dismissed only on this device.")
        case .dismissed:
            return String(localized: "activity.title.dismissed",
                          defaultValue: "Dismissed",
                          comment: "Activity title for a synced notification dismissal.")
        case .remoteDismissal:
            return String(localized: "activity.title.remoteDismissal",
                          defaultValue: "Remote dismissal",
                          comment: "Activity title for a dismissal received from another device.")
        case .actionSent:
            return String(localized: "activity.title.actionSent",
                          defaultValue: "Action sent",
                          comment: "Activity title for a mirrored-notification action (button press or tap) sent to the origin device.")
        case .readAll:
            return String(localized: "activity.title.readAll",
                          defaultValue: "Marked all as read",
                          comment: "Activity title for the Inbox Read All action.")
        case .relayDrained:
            return String(localized: "activity.title.relayDrained",
                          defaultValue: "Relay drained",
                          comment: "Activity title for draining queued relay messages.")
        case .assetSync:
            return String(localized: "activity.title.assetSync",
                          defaultValue: "Asset sync",
                          comment: "Activity title for syncing a notification asset.")
        case .renamed:
            return String(localized: "activity.title.renamed",
                          defaultValue: "Renamed",
                          comment: "Activity title for a device rename.")
        case .trustUpdated:
            return String(localized: "activity.title.trustUpdated",
                          defaultValue: "Trust updated",
                          comment: "Activity title for updated trust metadata.")
        case .rotationStarted:
            return String(localized: "activity.title.rotationStarted",
                          defaultValue: "Rotation started",
                          comment: "Activity title for starting key rotation.")
        case .rotationActivated:
            return String(localized: "activity.title.rotationActivated",
                          defaultValue: "Rotation activated",
                          comment: "Activity title for activating a key rotation.")
        case .rotationRetired:
            return String(localized: "activity.title.rotationRetired",
                          defaultValue: "Rotation retired",
                          comment: "Activity title for retiring an old key rotation epoch.")
        case .rotatedDebug:
            return String(localized: "activity.title.rotatedDebug",
                          defaultValue: "Rotated",
                          comment: "Activity title for a manual key rotation.")
        case .localStateRecovered:
            return String(localized: "activity.title.localStateRecovered",
                          defaultValue: "Recovered local state",
                          comment: "Activity title for recovering after local app state was cleared while Keychain remained.")
        case .paired:
            return String(localized: "activity.title.paired",
                          defaultValue: "Paired",
                          comment: "Activity title for pairing a device.")
        case .revoked:
            return String(localized: "activity.title.revoked",
                          defaultValue: "Revoked",
                          comment: "Activity title for revoking a device.")
        case .approved:
            return String(localized: "activity.title.approved",
                          defaultValue: "Approved",
                          comment: "Activity title for approving a trust request.")
        case .rejected:
            return String(localized: "activity.title.rejected",
                          defaultValue: "Rejected",
                          comment: "Activity title for rejecting a trust request.")
        case .revokeConfirmed:
            return String(localized: "activity.title.revokeConfirmed",
                          defaultValue: "Revoke confirmed",
                          comment: "Activity title for confirming a device revoke.")
        case .keptTrusted:
            return String(localized: "activity.title.keptTrusted",
                          defaultValue: "Kept trusted",
                          comment: "Activity title for keeping a device trusted.")
        case .apnsRegistered:
            // Same phrase as the push-status row — reuse its key so it's translated once.
            return pushStatus(.apnsRegistered)
        case .routePublished:
            return pushStatus(.routePublished)
        }
    }

    static func activityDetail(_ record: ActivityRecord) -> String {
        switch record.detailStyle {
        case .none:
            return ""
        case .text:
            return record.detailArg
        case .ongoingNotSynced:
            return String(localized: "activity.detail.ongoingNotSynced",
                          defaultValue: "Ongoing — not synced",
                          comment: "Activity detail for an ongoing notification that was not synced.")
        case .syncedToMesh:
            return String(localized: "activity.detail.syncedToMesh",
                          defaultValue: "Synced to other devices",
                          comment: "Activity detail for an action synced to peer devices.")
        case .noPeers:
            return String(localized: "activity.detail.noPeers",
                          defaultValue: "No peers",
                          comment: "Activity detail when there are no peer devices to sync with.")
        case .toEpoch:
            let format = String(localized: "activity.detail.toEpoch",
                                defaultValue: "→ epoch %lld",
                                comment: "Activity detail showing that a key rotation is moving to the given epoch.")
            return String(format: format, locale: .current, Int64(record.detailNum))
        case .nowEpoch:
            let format = String(localized: "activity.detail.nowEpoch",
                                defaultValue: "now epoch %lld",
                                comment: "Activity detail showing that the current key epoch changed immediately.")
            return String(format: format, locale: .current, Int64(record.detailNum))
        case .epoch:
            let format = String(localized: "activity.detail.epoch",
                                defaultValue: "epoch %lld",
                                comment: "Activity detail showing a key epoch number.")
            return String(format: format, locale: .current, Int64(record.detailNum))
        case .messageCount:
            return messageCount(record.detailNum)
        case .readAllCounts:
            // detailNum = rows marked read; detailArg = DismissEvents synced to the mesh (Read All caps
            // the sync to recent sources, so the two counts legitimately differ).
            let format = String(localized: "activity.detail.readAllCounts",
                                defaultValue: "%1$lld notifications · %2$lld synced",
                                comment: "Activity detail for Read All: notifications marked read, and dismissals synced to other devices.")
            return String(format: format, locale: .current, Int64(record.detailNum), Int64(record.detailArg) ?? 0)
        case .routeEnvironment:
            return routeEnvironment(rawValue: record.detailArg)
        case .deliveryMode:
            return deliveryMode(record.detailArg)
        }
    }

    static func errorDomain(_ domain: ErrorDomain?) -> String {
        switch domain {
        case .identity:
            return String(localized: "error.title.identity",
                          defaultValue: "Identity",
                          comment: "Error title for identity setup failures.")
        case .notificationPermission:
            return String(localized: "error.title.notificationPermission",
                          defaultValue: "Notification permission",
                          comment: "Error title for notification permission failures.")
        case .apnsRegistration:
            return String(localized: "error.title.apnsRegistration",
                          defaultValue: "APNs registration",
                          comment: "Error title for APNs registration failures.")
        case .routePublish:
            return String(localized: "error.title.routePublish",
                          defaultValue: "Route publish",
                          comment: "Error title for push route publication failures.")
        case .rotation:
            return String(localized: "error.title.rotation",
                          defaultValue: "Rotation",
                          comment: "Error title for key rotation failures.")
        case .trustBroadcast:
            return String(localized: "error.title.trustBroadcast",
                          defaultValue: "Trust broadcast",
                          comment: "Error title for trust broadcast failures.")
        case .profileBroadcast:
            return String(localized: "error.title.profileBroadcast",
                          defaultValue: "Profile broadcast",
                          comment: "Error title for profile broadcast failures.")
        case .envelopeDelivery:
            return String(localized: "error.title.envelopeDelivery",
                          defaultValue: "Envelope delivery",
                          comment: "Error title for envelope delivery failures.")
        case .dismissSync:
            return String(localized: "error.title.dismissSync",
                          defaultValue: "Dismiss sync",
                          comment: "Error title for dismissal synchronization failures.")
        case .pairing, .none:
            return String(localized: "error.title.pairing",
                          defaultValue: "Pairing",
                          comment: "Error title for pairing failures.")
        }
    }

    static func messageCount(_ count: Int) -> String {
        let format = String(localized: "activity.detail.messageCount",
                            defaultValue: "%lld messages",
                            comment: "Number of messages drained from the relay.")
        return String.localizedStringWithFormat(format, Int64(count))
    }

    static func rotationStatus(_ info: RotationKeyInfo, now: Date = .now) -> String {
        guard info.nextEventAtMillis > 0 else {
            return String(localized: "rotation.status.noneScheduled",
                          defaultValue: "No rotation scheduled",
                          comment: "Shown when there is no key rotation scheduled.")
        }
        let eventDate = Date(timeIntervalSince1970: TimeInterval(info.nextEventAtMillis) / 1000)
        let remaining = rotationCountdown(until: eventDate, now: now)
        if let target = info.pendingTargetEpoch {
            if info.pendingActivated {
                let format = String(localized: "rotation.status.retiringPreviousEpoch",
                                    defaultValue: "Disabling previous epoch in %@",
                                    comment: "Key rotation status. The placeholder is an exact days/hours/minutes countdown.")
                return String(format: format, locale: .current, remaining)
            }
            let format = String(localized: "rotation.status.activatingEpoch",
                                defaultValue: "Activating epoch %lld in %@",
                                comment: "Key rotation status. The placeholders are the target epoch and an exact days/hours/minutes countdown.")
            return String(format: format, locale: .current, Int64(target), remaining)
        }
        let format = String(localized: "rotation.status.nextRotation",
                            defaultValue: "Next rotation in %@",
                            comment: "Key rotation status. The placeholder is an exact days/hours/minutes countdown.")
        return String(format: format, locale: .current, remaining)
    }

    private static func rotationCountdown(until eventDate: Date, now: Date) -> String {
        let interval = eventDate.timeIntervalSince(now)
        guard interval > 0 else {
            return String(localized: "rotation.duration.now",
                          defaultValue: "now",
                          comment: "Countdown value for an event that is due now.")
        }
        guard interval >= 60 else {
            return String(localized: "rotation.duration.lessThanMinute",
                          defaultValue: "less than 1 minute",
                          comment: "Countdown value for an event due in under one minute.")
        }
        let formatter = DateComponentsFormatter()
        formatter.allowedUnits = [.day, .hour, .minute]
        formatter.unitsStyle = .full
        formatter.maximumUnitCount = 3
        formatter.zeroFormattingBehavior = .dropAll
        return formatter.string(from: interval) ?? String(localized: "rotation.duration.lessThanMinute",
                                                          defaultValue: "less than 1 minute",
                                                          comment: "Countdown value for an event due in under one minute.")
    }
}

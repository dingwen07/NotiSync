package net.extrawdw.apps.notisync.testsupport

import net.extrawdw.apps.notisync.data.ActivityText
import net.extrawdw.apps.notisync.data.TrustPrompt

object TestActivityText : ActivityText {
    override fun mirroredToDevices(count: Int) = "mirrored to $count device(s)"
    override fun syncedToDevices(count: Int) = "synced to $count device(s)"
    override fun fromDevice(name: String) = "from $name"
    override fun byDevice(name: String) = "by $name"
    override fun assetRepairTitle() = "Asset repair"
    override fun assetRepairDetail(count: Int, requester: String) = "re-sent $count to $requester"
    override fun rejectedTitle() = "Rejected"
    override fun badSignatureFrom(name: String) = "bad signature from $name"
    override fun fcmRouteTitle() = "FCM route"
    override fun fcmRouteRegistrationFailed(reason: String) = "registration failed: $reason"
    override fun fcmRouteRegistered() = "registered"
    override fun deviceNameTitle() = "Device name"
    override fun deviceNameUpdated(count: Int) = "updated on $count device(s)"
    override fun renamedWas(previousName: String) = "renamed (was $previousName)"
    override fun trustUpdateFrom(name: String, prompt: TrustPrompt) =
        "trust update from $name ($prompt)"

    override fun pairedTitle() = "Paired"
    override fun filtersUpdated(count: Int) = "updated filters ($count)"
    override fun filtersCleared() = "cleared filters"
    override fun actionToDevice(action: String?, name: String) =
        if (action != null) "\"$action\" sent to $name" else "opening on $name"

    override fun actionByDevice(action: String?, name: String) =
        if (action != null) "\"$action\" by $name" else "opened by $name"
}

package net.extrawdw.apps.notisync.data

import android.content.Context
import net.extrawdw.apps.notisync.R

/** Localized text used in privacy-safe Activity rows. */
interface ActivityText {
    fun mirroredToDevices(count: Int): String
    fun syncedToDevices(count: Int): String
    fun fromDevice(name: String): String
    fun byDevice(name: String): String
    fun assetRepairTitle(): String
    fun assetRepairDetail(count: Int, requester: String): String
    fun rejectedTitle(): String
    fun badSignatureFrom(name: String): String
    fun fcmRouteTitle(): String
    fun fcmRouteRegistrationFailed(reason: String): String
    fun fcmRouteRegistered(): String
    fun deviceNameTitle(): String
    fun deviceNameUpdated(count: Int): String
    fun renamedWas(previousName: String): String
    fun trustUpdateFrom(name: String, prompt: TrustPrompt): String
    fun pairedTitle(): String
    fun filtersUpdated(count: Int): String
    fun filtersCleared(): String
}

class AndroidActivityText(private val context: Context) : ActivityText {
    private val resources get() = context.resources

    override fun mirroredToDevices(count: Int): String =
        resources.getQuantityString(R.plurals.activity_detail_mirrored_to_devices, count, count)

    override fun syncedToDevices(count: Int): String =
        resources.getQuantityString(R.plurals.activity_detail_synced_to_devices, count, count)

    override fun fromDevice(name: String): String =
        context.getString(R.string.activity_detail_from_device, name)

    override fun byDevice(name: String): String =
        context.getString(R.string.activity_detail_by_device, name)

    override fun assetRepairTitle(): String =
        context.getString(R.string.activity_title_asset_repair)

    override fun assetRepairDetail(count: Int, requester: String): String =
        resources.getQuantityString(R.plurals.activity_detail_asset_repair, count, count, requester)

    override fun rejectedTitle(): String =
        context.getString(R.string.activity_title_rejected)

    override fun badSignatureFrom(name: String): String =
        context.getString(R.string.activity_detail_bad_signature_from, name)

    override fun fcmRouteTitle(): String =
        context.getString(R.string.activity_title_fcm_route)

    override fun fcmRouteRegistrationFailed(reason: String): String =
        context.getString(R.string.activity_detail_fcm_route_registration_failed, reason)

    override fun fcmRouteRegistered(): String =
        context.getString(R.string.activity_detail_fcm_route_registered)

    override fun deviceNameTitle(): String =
        context.getString(R.string.activity_title_device_name)

    override fun deviceNameUpdated(count: Int): String =
        resources.getQuantityString(R.plurals.activity_detail_device_name_updated, count, count)

    override fun renamedWas(previousName: String): String =
        context.getString(R.string.activity_detail_renamed_was, previousName)

    override fun trustUpdateFrom(name: String, prompt: TrustPrompt): String =
        context.getString(
            R.string.activity_detail_trust_update_from,
            name,
            trustPromptLabel(prompt)
        )

    override fun pairedTitle(): String =
        context.getString(R.string.activity_title_paired)

    override fun filtersUpdated(count: Int): String =
        resources.getQuantityString(R.plurals.activity_detail_filters_updated, count, count)

    override fun filtersCleared(): String =
        context.getString(R.string.activity_detail_filters_cleared)

    private fun trustPromptLabel(prompt: TrustPrompt): String = when (prompt) {
        TrustPrompt.NEW_TRUST -> context.getString(R.string.activity_trust_prompt_new_trust)
        TrustPrompt.RE_TRUST -> context.getString(R.string.activity_trust_prompt_re_trust)
        TrustPrompt.NEW_REVOKE -> context.getString(R.string.activity_trust_prompt_new_revoke)
        TrustPrompt.CONFLICT -> context.getString(R.string.activity_trust_prompt_conflict)
        TrustPrompt.OTHER_ADDED -> context.getString(R.string.activity_trust_prompt_other_added)
        TrustPrompt.OTHER_REMOVED -> context.getString(R.string.activity_trust_prompt_other_removed)
    }
}

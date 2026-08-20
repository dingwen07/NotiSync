package net.extrawdw.apps.notisync.data.notificationpolicy

import kotlinx.coroutines.flow.Flow

object AndroidNotificationPolicyLimits {
    const val MAX_PACKAGE_CHARS = 512
    const val MAX_IDENTIFIER_CHARS = 256
    const val MAX_DISPLAY_CHARS = 1_024
}

/** Closed domain scope; its persisted representation is owned by the Room adapter. */
enum class NotificationPolicyScope {
    CHANNEL,
    GROUP,
}

/** Per-package capture policy. An absent row is distinct from a disabled row and is handled by the caller. */
data class AndroidAppPolicy(
    val packageName: String,
    val enabled: Boolean,
    val mirrorOngoing: Boolean,
    val updateIntervalSeconds: Int,
    val mirrorOngoingToIos: Boolean,
    val mirrorMediaPlaybackToIos: Boolean,
    val ringForCalls: Boolean,
    val lastSeenAt: Long?,
    val updatedAt: Long,
) {
    init {
        requirePackageName(packageName)
        require(updateIntervalSeconds >= -1) {
            "notification update interval must be -1 or non-negative"
        }
        require(lastSeenAt == null || lastSeenAt > 0) {
            "notification app last-seen time must be positive"
        }
        require(updatedAt > 0) { "notification policy update time must be positive" }
    }
}

data class AndroidSubscopePolicy(
    val packageName: String,
    val scope: NotificationPolicyScope,
    val scopeId: String,
    val enabled: Boolean,
    val updatedAt: Long,
) {
    init {
        requirePackageName(packageName)
        requireIdentifier(scopeId, "notification subscope id")
        require(updatedAt > 0) { "notification subscope update time must be positive" }
    }
}

data class AndroidObservedGroup(
    val packageName: String,
    val groupId: String,
    val groupName: String?,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
) {
    init {
        requirePackageName(packageName)
        requireIdentifier(groupId, "seen notification group id")
        requireDisplayName(groupName, "notification group name")
        require(firstSeenAt > 0 && lastSeenAt >= firstSeenAt) {
            "seen notification group timestamps are invalid"
        }
    }
}

data class AndroidObservedChannel(
    val packageName: String,
    val channelId: String,
    val channelName: String?,
    val groupId: String?,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
) {
    init {
        requirePackageName(packageName)
        requireIdentifier(channelId, "seen notification channel id")
        requireDisplayName(channelName, "notification channel name")
        groupId?.let { requireIdentifier(it, "seen notification group id") }
        require(firstSeenAt > 0 && lastSeenAt >= firstSeenAt) {
            "seen notification channel timestamps are invalid"
        }
    }
}

/** Domain-facing owner of Android app, channel/group, and observed metadata policy. */
interface AndroidNotificationPolicyRepository {
    /** An empty list is a valid empty policy aggregate. Invalid persisted rows fail closed. */
    fun observeApps(): Flow<List<AndroidAppPolicy>>

    suspend fun findApp(packageName: String): AndroidAppPolicy?

    suspend fun replaceApp(policy: AndroidAppPolicy)

    fun observeSubscopePolicies(
        packageName: String,
        scope: NotificationPolicyScope,
    ): Flow<List<AndroidSubscopePolicy>>

    suspend fun replaceSubscopePolicy(policy: AndroidSubscopePolicy)

    suspend fun removeSubscopePolicy(
        packageName: String,
        scope: NotificationPolicyScope,
        scopeId: String,
    ): Boolean

    fun observeSeenChannels(packageName: String): Flow<List<AndroidObservedChannel>>

    fun observeSeenGroups(packageName: String): Flow<List<AndroidObservedGroup>>

    suspend fun recordSeenChannel(
        group: AndroidObservedGroup?,
        channel: AndroidObservedChannel,
    )

    suspend fun forgetSeenChannel(packageName: String, channelId: String): Boolean

    suspend fun forgetSeenGroup(packageName: String, groupId: String): Boolean

    suspend fun forgetApp(packageName: String): Boolean
}

private fun requirePackageName(packageName: String) {
    require(packageName.isNotBlank() && packageName.length <= AndroidNotificationPolicyLimits.MAX_PACKAGE_CHARS) {
        "package name is invalid"
    }
    require(packageName.none(Char::isISOControl)) { "package name contains a control character" }
}

private fun requireIdentifier(value: String, name: String) {
    require(value.isNotBlank() && value.length <= AndroidNotificationPolicyLimits.MAX_IDENTIFIER_CHARS) {
        "$name is invalid"
    }
    require(value.none(Char::isISOControl)) { "$name contains a control character" }
}

private fun requireDisplayName(value: String?, name: String) {
    require(value == null || value.length <= AndroidNotificationPolicyLimits.MAX_DISPLAY_CHARS) {
        "$name is too long"
    }
    require(value == null || value.none(Char::isISOControl)) {
        "$name contains a control character"
    }
}

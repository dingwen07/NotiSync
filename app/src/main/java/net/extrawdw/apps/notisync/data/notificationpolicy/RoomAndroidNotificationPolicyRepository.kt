package net.extrawdw.apps.notisync.data.notificationpolicy

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.extrawdw.apps.notisync.data.storage.operational.AndroidAppPolicyEntity
import net.extrawdw.apps.notisync.data.storage.operational.AndroidNotificationPolicyDao
import net.extrawdw.apps.notisync.data.storage.operational.AndroidPolicyScope
import net.extrawdw.apps.notisync.data.storage.operational.AndroidSeenChannelEntity
import net.extrawdw.apps.notisync.data.storage.operational.AndroidSeenGroupEntity
import net.extrawdw.apps.notisync.data.storage.operational.AndroidSubscopePolicyEntity

/** Sole Room adapter for [AndroidNotificationPolicyRepository]. */
internal class RoomAndroidNotificationPolicyRepository(
    private val dao: AndroidNotificationPolicyDao,
) : AndroidNotificationPolicyRepository {
    override fun observeApps(): Flow<List<AndroidAppPolicy>> =
        dao.observeApps().map { rows -> rows.map { it.toDomain() } }

    override suspend fun findApp(packageName: String): AndroidAppPolicy? {
        requirePackageNameForRead(packageName)
        return dao.findApp(packageName)?.toDomain()
    }

    override suspend fun replaceApp(policy: AndroidAppPolicy) {
        dao.upsertApp(policy.toEntity())
    }

    override fun observeSubscopePolicies(
        packageName: String,
        scope: NotificationPolicyScope,
    ): Flow<List<AndroidSubscopePolicy>> {
        requirePackageNameForRead(packageName)
        return dao.observeSubscopes(packageName, scope.toStorage()).map { rows ->
            rows.map { it.toDomain() }
        }
    }

    override suspend fun replaceSubscopePolicy(policy: AndroidSubscopePolicy) {
        dao.upsertSubscope(policy.toEntity())
    }

    override suspend fun removeSubscopePolicy(
        packageName: String,
        scope: NotificationPolicyScope,
        scopeId: String,
    ): Boolean {
        requirePackageNameForRead(packageName)
        requireScopeIdForRead(scopeId)
        return dao.deleteSubscopePolicy(packageName, scope.toStorage(), scopeId) == 1
    }

    override fun observeSeenChannels(packageName: String): Flow<List<AndroidObservedChannel>> {
        requirePackageNameForRead(packageName)
        return dao.observeSeenChannels(packageName).map { rows -> rows.map { it.toDomain() } }
    }

    override fun observeSeenGroups(packageName: String): Flow<List<AndroidObservedGroup>> {
        requirePackageNameForRead(packageName)
        return dao.observeSeenGroups(packageName).map { rows -> rows.map { it.toDomain() } }
    }

    override suspend fun recordSeenChannel(
        group: AndroidObservedGroup?,
        channel: AndroidObservedChannel,
    ) {
        require(group == null || group.packageName == channel.packageName) {
            "seen notification group does not own the channel package"
        }
        require((channel.groupId == null) == (group == null)) {
            "seen notification channel and group must be present together"
        }
        require(group == null || group.groupId == channel.groupId) {
            "seen notification group does not own the channel"
        }
        dao.recordSeenChannel(group?.toEntity(), channel.toEntity())
    }

    override suspend fun forgetSeenChannel(packageName: String, channelId: String): Boolean {
        requirePackageNameForRead(packageName)
        requireScopeIdForRead(channelId)
        return dao.forgetSeenChannel(packageName, channelId) == 1
    }

    override suspend fun forgetSeenGroup(packageName: String, groupId: String): Boolean {
        requirePackageNameForRead(packageName)
        requireScopeIdForRead(groupId)
        return dao.forgetSeenGroup(packageName, groupId) == 1
    }

    override suspend fun forgetApp(packageName: String): Boolean {
        requirePackageNameForRead(packageName)
        return dao.forgetApp(packageName) == 1
    }
}

private fun AndroidAppPolicyEntity.toDomain(): AndroidAppPolicy = try {
    AndroidAppPolicy(
        packageName = packageName,
        enabled = enabled,
        mirrorOngoing = mirrorOngoing,
        updateIntervalSeconds = updateIntervalSeconds,
        mirrorOngoingToIos = mirrorOngoingToIos,
        mirrorMediaPlaybackToIos = mirrorMediaPlaybackToIos,
        ringForCalls = ringForCalls,
        lastSeenAt = lastSeenAt,
        updatedAt = updatedAt,
    )
} catch (error: IllegalArgumentException) {
    throw IllegalStateException("Persisted Android app policy is invalid", error)
}

private fun AndroidAppPolicy.toEntity(): AndroidAppPolicyEntity = AndroidAppPolicyEntity(
    packageName = packageName,
    enabled = enabled,
    mirrorOngoing = mirrorOngoing,
    updateIntervalSeconds = updateIntervalSeconds,
    mirrorOngoingToIos = mirrorOngoingToIos,
    mirrorMediaPlaybackToIos = mirrorMediaPlaybackToIos,
    ringForCalls = ringForCalls,
    lastSeenAt = lastSeenAt,
    updatedAt = updatedAt,
)

private fun AndroidSubscopePolicyEntity.toDomain(): AndroidSubscopePolicy = try {
    AndroidSubscopePolicy(
        packageName = packageName,
        scope = when (scopeKind) {
            AndroidPolicyScope.CHANNEL -> NotificationPolicyScope.CHANNEL
            AndroidPolicyScope.GROUP -> NotificationPolicyScope.GROUP
        },
        scopeId = scopeId,
        enabled = enabled,
        updatedAt = updatedAt,
    )
} catch (error: IllegalArgumentException) {
    throw IllegalStateException("Persisted Android subscope policy is invalid", error)
}

private fun AndroidSubscopePolicy.toEntity(): AndroidSubscopePolicyEntity = AndroidSubscopePolicyEntity(
    packageName = packageName,
    scopeKind = scope.toStorage(),
    scopeId = scopeId,
    enabled = enabled,
    updatedAt = updatedAt,
)

private fun AndroidSeenGroupEntity.toDomain(): AndroidObservedGroup = try {
    AndroidObservedGroup(
        packageName = packageName,
        groupId = groupId,
        groupName = groupName,
        firstSeenAt = firstSeenAt,
        lastSeenAt = lastSeenAt,
    )
} catch (error: IllegalArgumentException) {
    throw IllegalStateException("Persisted Android seen-group metadata is invalid", error)
}

private fun AndroidObservedGroup.toEntity(): AndroidSeenGroupEntity = AndroidSeenGroupEntity(
    packageName = packageName,
    groupId = groupId,
    groupName = groupName,
    firstSeenAt = firstSeenAt,
    lastSeenAt = lastSeenAt,
)

private fun AndroidSeenChannelEntity.toDomain(): AndroidObservedChannel = try {
    AndroidObservedChannel(
        packageName = packageName,
        channelId = channelId,
        channelName = channelName,
        groupId = groupId,
        firstSeenAt = firstSeenAt,
        lastSeenAt = lastSeenAt,
    )
} catch (error: IllegalArgumentException) {
    throw IllegalStateException("Persisted Android seen-channel metadata is invalid", error)
}

private fun AndroidObservedChannel.toEntity(): AndroidSeenChannelEntity = AndroidSeenChannelEntity(
    packageName = packageName,
    channelId = channelId,
    channelName = channelName,
    groupId = groupId,
    firstSeenAt = firstSeenAt,
    lastSeenAt = lastSeenAt,
)

private fun NotificationPolicyScope.toStorage(): AndroidPolicyScope = when (this) {
    NotificationPolicyScope.CHANNEL -> AndroidPolicyScope.CHANNEL
    NotificationPolicyScope.GROUP -> AndroidPolicyScope.GROUP
}

private fun requirePackageNameForRead(packageName: String) {
    require(packageName.isNotBlank() && packageName.length <= AndroidNotificationPolicyLimits.MAX_PACKAGE_CHARS) {
        "package name is invalid"
    }
    require(packageName.none(Char::isISOControl)) { "package name contains a control character" }
}

private fun requireScopeIdForRead(scopeId: String) {
    require(scopeId.isNotBlank() && scopeId.length <= AndroidNotificationPolicyLimits.MAX_IDENTIFIER_CHARS) {
        "notification scope id is invalid"
    }
    require(scopeId.none(Char::isISOControl)) { "notification scope id contains a control character" }
}

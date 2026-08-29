package net.extrawdw.apps.notisync.seal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import net.extrawdw.apps.notisync.ui.icons.material.outlined.arrow_back as ArrowBackIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.label as LabelIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.account_tree as AccountTreeIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.cancel as CancelIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.check_circle as CheckCircleIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.chevron_right as ChevronRightIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.commit as CommitIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.error_outline as ErrorOutlineIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.fingerprint as FingerprintIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.folder as FolderIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.info as InfoIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.key as KeyIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.person as PersonIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.schedule as ScheduleIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.sync as SyncIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.verified_user as VerifiedUserIcon
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import net.extrawdw.apps.notisync.R
import net.extrawdw.apps.notisync.ui.RequestDeviceSubCard
import net.extrawdw.notisync.protocol.OpenPgpObjectKind

internal enum class SealDisplayStatus {
    WAITING,
    SIGNING,
    SIGNED,
    REJECTED,
    CANCELED,
    EXPIRED,
    FAILED,
    LEGACY_FINISHED,
}

internal fun StoredOpenPgpRequest.sealDisplayStatus(): SealDisplayStatus = when (result) {
    OpenPgpRequestResult.APPROVED -> SealDisplayStatus.SIGNED
    OpenPgpRequestResult.REJECTED -> SealDisplayStatus.REJECTED
    OpenPgpRequestResult.CANCELED -> SealDisplayStatus.CANCELED
    OpenPgpRequestResult.EXPIRED -> SealDisplayStatus.EXPIRED
    OpenPgpRequestResult.FAILED -> SealDisplayStatus.FAILED
    null -> when (state) {
        OpenPgpRequestState.PENDING_REVIEW -> SealDisplayStatus.WAITING
        OpenPgpRequestState.USER_APPROVED,
        OpenPgpRequestState.PROVIDER_INTERACTION,
        OpenPgpRequestState.SIGNED_PENDING_SEND,
        OpenPgpRequestState.REJECTED_PENDING_SEND,
        -> SealDisplayStatus.SIGNING
        OpenPgpRequestState.CANCELLED -> SealDisplayStatus.CANCELED
        OpenPgpRequestState.EXPIRED -> SealDisplayStatus.EXPIRED
        OpenPgpRequestState.FAILED -> SealDisplayStatus.FAILED
        OpenPgpRequestState.SENT -> SealDisplayStatus.LEGACY_FINISHED
    }
}

internal fun StoredOpenPgpRequest.isSealActive(): Boolean = state in setOf(
    OpenPgpRequestState.PENDING_REVIEW,
    OpenPgpRequestState.USER_APPROVED,
    OpenPgpRequestState.PROVIDER_INTERACTION,
    OpenPgpRequestState.SIGNED_PENDING_SEND,
    OpenPgpRequestState.REJECTED_PENDING_SEND,
)

internal fun StoredOpenPgpRequest.opensSealReview(): Boolean = state in setOf(
    OpenPgpRequestState.PENDING_REVIEW,
    OpenPgpRequestState.USER_APPROVED,
    OpenPgpRequestState.PROVIDER_INTERACTION,
)

internal fun StoredOpenPgpRequest.shouldCloseAutoOpenedReview(autoLaunchOwned: Boolean): Boolean =
    autoLaunchOwned && sealDisplayStatus() in setOf(
        SealDisplayStatus.CANCELED,
        SealDisplayStatus.EXPIRED,
    )

@Composable
internal fun sealStatusLabel(status: SealDisplayStatus): String = stringResource(
    when (status) {
        SealDisplayStatus.WAITING -> R.string.seal_status_waiting
        SealDisplayStatus.SIGNING -> R.string.seal_status_signing
        SealDisplayStatus.SIGNED -> R.string.seal_result_signed
        SealDisplayStatus.REJECTED -> R.string.seal_result_rejected
        SealDisplayStatus.CANCELED -> R.string.seal_result_canceled
        SealDisplayStatus.EXPIRED -> R.string.seal_result_expired
        SealDisplayStatus.FAILED -> R.string.seal_result_failed
        SealDisplayStatus.LEGACY_FINISHED -> R.string.seal_result_legacy
    }
)

@Composable
internal fun SigningRequestListItem(
    stored: StoredOpenPgpRequest,
    requesterName: String,
    onClick: () -> Unit,
) {
    val status = stored.sealDisplayStatus()
    val commit = stored.commit
    val tag = stored.tag
    val headline = when (stored.request.objectKind) {
        OpenPgpObjectKind.GIT_COMMIT -> commit?.message?.commitSubject().orEmpty().ifBlank {
            stringResource(R.string.seal_commit_untitled)
        }
        OpenPgpObjectKind.GIT_TAG -> tag?.tagName.orEmpty().ifBlank {
            stringResource(R.string.seal_tag_untitled)
        }
    }
    val time = rememberShortTimeFormatter().format(Date(stored.updatedAt))
    val base = commit?.parentIds?.firstOrNull()?.shortObjectId()
        ?: commit?.treeId?.shortObjectId()
        ?: tag?.objectId?.shortObjectId()
    val workingDirectory = stored.request.workingDirectory?.workingDirectoryName()

    Surface {
        ListItem(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
            leadingContent = { SealStatusIcon(status) },
            supportingContent = {
                Column {
                    Text(
                        sealStatusLabel(status),
                        color = sealStatusColor(status),
                        maxLines = 1,
                    )
                    Text(
                        listOfNotNull(
                            requesterName,
                            workingDirectory,
                            base,
                            time,
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            trailingContent = {
                Icon(ChevronRightIcon, contentDescription = null)
            },
        ) {
            Text(headline, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    HorizontalDivider()
}

@Composable
internal fun SigningRequestDetail(
    stored: StoredOpenPgpRequest,
    requesterName: String,
    requesterIdentityKeyFingerprint: String?,
    signingIdentity: String,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
    showSheetHeader: Boolean = false,
    onBack: () -> Unit = {},
) {
    val status = stored.sealDisplayStatus()
    val commit = stored.commit
    val tag = stored.tag
    val formatter = rememberDateTimeFormatter()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (showSheetHeader) {
            item {
                CenteredDetailItem {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(
                                ArrowBackIcon,
                                contentDescription = stringResource(R.string.seal_back_to_history),
                            )
                        }
                        Text(
                            stringResource(R.string.seal_name),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                    }
                }
            }
        }

        item {
            CenteredDetailItem {
                SealHero(
                    status = status,
                    subject = when (stored.request.objectKind) {
                        OpenPgpObjectKind.GIT_COMMIT -> commit?.message?.commitSubject().orEmpty().ifBlank {
                            stringResource(R.string.seal_commit_untitled)
                        }
                        OpenPgpObjectKind.GIT_TAG -> tag?.tagName.orEmpty().ifBlank {
                            stringResource(R.string.seal_tag_untitled)
                        }
                    },
                    requesterName = requesterName,
                    workingDirectory = stored.request.workingDirectory,
                    reference = commit?.parentIds?.firstOrNull()?.shortObjectId()
                        ?: commit?.treeId?.shortObjectId()
                        ?: tag?.objectId?.shortObjectId(),
                    shortHash = stored.request.payloadSha256.toHex().take(7),
                )
            }
        }

        if (stored.state == OpenPgpRequestState.PENDING_REVIEW) {
            item {
                CenteredDetailItem {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Icon(InfoIcon, contentDescription = null)
                            Text(
                                stringResource(
                                    when (stored.request.objectKind) {
                                        OpenPgpObjectKind.GIT_COMMIT -> R.string.seal_review_guidance
                                        OpenPgpObjectKind.GIT_TAG -> R.string.seal_tag_review_guidance
                                    }
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }

        if (commit == null && tag == null) {
            item {
                CenteredDetailItem {
                    SealCard(
                        title = stringResource(
                            when (stored.request.objectKind) {
                                OpenPgpObjectKind.GIT_COMMIT -> R.string.seal_commit_section
                                OpenPgpObjectKind.GIT_TAG -> R.string.seal_tag_section
                            }
                        ),
                        icon = AccountTreeIcon,
                    ) {
                        Text(
                            stringResource(R.string.seal_details_unavailable),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else if (commit != null) {
            item {
                CenteredDetailItem {
                    CommitCard(commit)
                }
            }
        } else if (tag != null) {
            item {
                CenteredDetailItem {
                    TagCard(tag)
                }
            }
        }

        item {
            CenteredDetailItem {
                SealCard(
                    title = stringResource(R.string.seal_approval_section),
                    icon = VerifiedUserIcon,
                ) {
                    stored.request.workingDirectory?.let { workingDirectory ->
                        SealDetailLine(
                            icon = FolderIcon,
                            label = stringResource(R.string.seal_working_directory),
                            value = workingDirectory,
                            valueMonospace = true,
                        )
                        HorizontalDivider()
                    }
                    SealDetailLine(
                        icon = KeyIcon,
                        label = stringResource(R.string.seal_signing_key),
                        value = signingIdentity,
                        supporting = stored.request.primaryKeyId.formattedKeyId(),
                        supportingMonospace = true,
                    )
                }
            }
        }

        item {
            CenteredDetailItem {
                SealCard(
                    title = stringResource(R.string.seal_request_section),
                    icon = FingerprintIcon,
                ) {
                    RequestDeviceSubCard(
                        deviceName = requesterName,
                        verificationNumber = stored.senderClientId.value,
                        identityKeyFingerprint = requesterIdentityKeyFingerprint,
                    )
                    SealRecordLine(
                        stringResource(R.string.seal_requested_at),
                        formatter.format(Date(stored.request.issuedAt)),
                    )
                    SealRecordLine(
                        stringResource(R.string.seal_updated_at),
                        formatter.format(Date(stored.updatedAt)),
                    )
                    SealRecordLine(
                        stringResource(R.string.seal_request_id),
                        stored.request.requestId.take(8),
                        monospace = true,
                    )
                    SealRecordLine(
                        stringResource(R.string.seal_sha256),
                        stored.request.payloadSha256.toHex(),
                        monospace = true,
                    )
                    SealRecordLine(
                        stringResource(R.string.seal_payload),
                        pluralStringResource(
                            R.plurals.seal_payload_value,
                            commit?.payloadBytes ?: tag?.payloadBytes ?: 0,
                            commit?.payloadBytes ?: tag?.payloadBytes ?: 0,
                        ),
                        monospace = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun SealHero(
    status: SealDisplayStatus,
    subject: String,
    requesterName: String,
    workingDirectory: String?,
    reference: String?,
    shortHash: String,
) {
    val container = sealStatusContainer(status)
    val content = sealStatusContent(status)
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(28.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = content.copy(alpha = 0.12f),
                contentColor = content,
            ) {
                Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                    SealStatusIcon(status, modifier = Modifier.size(30.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    sealStatusLabel(status),
                    style = MaterialTheme.typography.labelLarge,
                )
                SelectionContainer {
                    Text(
                        subject,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    listOfNotNull(
                        requesterName,
                        workingDirectory?.workingDirectoryName(),
                        reference,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.seal_hash, shortHash),
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun CommitCard(commit: GitCommitDisplaySnapshot) {
    val subject = commit.message.commitSubject().ifBlank {
        stringResource(R.string.seal_commit_untitled)
    }
    val body = commit.message.commitBody()
    SealCard(
        title = stringResource(R.string.seal_commit_section),
        icon = AccountTreeIcon,
    ) {
        SelectionContainer {
            Text(
                subject,
                style = MaterialTheme.typography.titleLarge,
            )
        }
        if (body.isNotEmpty()) {
            SelectionContainer {
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider()
        IdentityLine(PersonIcon, stringResource(R.string.seal_author), commit.author)
        HorizontalDivider()
        IdentityLine(VerifiedUserIcon, stringResource(R.string.seal_created_by), commit.committer)
        HorizontalDivider()
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ReferencePill(
                label = commit.treeId.shortObjectId(),
                icon = AccountTreeIcon,
                iconContentDescription = stringResource(R.string.seal_tree),
            )
            if (commit.parentIds.isEmpty()) {
                ReferencePill(
                    label = stringResource(R.string.seal_commit_initial),
                    icon = CommitIcon,
                )
            } else {
                commit.parentIds.forEach { parent ->
                    ReferencePill(
                        label = parent.shortObjectId(),
                        icon = CommitIcon,
                        iconContentDescription = stringResource(R.string.seal_parent),
                    )
                }
            }
        }
        if (commit.extraHeaders.isNotEmpty()) {
            HorizontalDivider()
            commit.extraHeaders.forEach { header ->
                SealRecordLine(
                    stringResource(R.string.seal_extra_header, header.name),
                    header.value,
                    monospace = true,
                )
            }
        }
        if (commit.truncated) {
            HorizontalDivider()
            Text(
                stringResource(R.string.seal_history_details_truncated),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TagCard(tag: GitTagDisplaySnapshot) {
    SealCard(
        title = stringResource(R.string.seal_tag_section),
        icon = LabelIcon,
    ) {
        SelectionContainer {
            Text(tag.tagName, style = MaterialTheme.typography.titleLarge)
        }
        val subject = tag.message.commitSubject()
        val body = tag.message.commitBody()
        if (subject.isNotEmpty()) {
            SelectionContainer {
                Text(subject, style = MaterialTheme.typography.titleMedium)
            }
        }
        if (body.isNotEmpty()) {
            SelectionContainer {
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider()
        IdentityLine(PersonIcon, stringResource(R.string.seal_created_by), tag.tagger)
        HorizontalDivider()
        SealRecordLine(stringResource(R.string.seal_tag_target_type), tag.objectType)
        SealRecordLine(stringResource(R.string.seal_tag_target), tag.objectId, monospace = true)
        if (tag.truncated) {
            HorizontalDivider()
            Text(
                stringResource(R.string.seal_history_details_truncated),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun IdentityLine(icon: ImageVector, label: String, raw: String) {
    val identity = remember(raw) { GitIdentity.parse(raw) }
    val formatter = rememberDateTimeFormatter()
    SealDetailLine(
        icon = icon,
        label = label,
        value = identity?.name ?: raw,
        supporting = identity?.let {
            "${it.email} · ${formatter.format(Date(it.timestampSeconds * 1_000))}"
        },
    )
}

@Composable
private fun SealCard(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

@Composable
private fun ReferencePill(
    label: String,
    icon: ImageVector? = null,
    iconContentDescription: String? = null,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = iconContentDescription,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun SealDetailLine(
    icon: ImageVector,
    label: String,
    value: String,
    supporting: String? = null,
    valueMonospace: Boolean = false,
    supportingMonospace: Boolean = false,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.padding(top = 2.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SelectionContainer {
                Text(value, fontFamily = if (valueMonospace) FontFamily.Monospace else null)
            }
            supporting?.let {
                SelectionContainer {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = if (supportingMonospace) FontFamily.Monospace else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun SealRecordLine(label: String, value: String, monospace: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer {
            Text(value, fontFamily = if (monospace) FontFamily.Monospace else null)
        }
    }
}

@Composable
private fun CenteredDetailItem(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.fillMaxWidth().widthIn(max = 680.dp)) { content() }
    }
}

@Composable
private fun SealStatusIcon(status: SealDisplayStatus, modifier: Modifier = Modifier) {
    Icon(
        imageVector = when (status) {
            SealDisplayStatus.WAITING -> ScheduleIcon
            SealDisplayStatus.SIGNING -> SyncIcon
            SealDisplayStatus.SIGNED -> CheckCircleIcon
            SealDisplayStatus.REJECTED, SealDisplayStatus.CANCELED -> CancelIcon
            SealDisplayStatus.EXPIRED -> ScheduleIcon
            SealDisplayStatus.FAILED -> ErrorOutlineIcon
            SealDisplayStatus.LEGACY_FINISHED -> InfoIcon
        },
        contentDescription = sealStatusLabel(status),
        modifier = modifier,
        tint = sealStatusColor(status),
    )
}

@Composable
private fun sealStatusColor(status: SealDisplayStatus): Color = when (status) {
    SealDisplayStatus.WAITING, SealDisplayStatus.SIGNING, SealDisplayStatus.SIGNED ->
        MaterialTheme.colorScheme.primary
    SealDisplayStatus.REJECTED, SealDisplayStatus.FAILED -> MaterialTheme.colorScheme.error
    SealDisplayStatus.CANCELED, SealDisplayStatus.LEGACY_FINISHED -> MaterialTheme.colorScheme.onSurfaceVariant
    SealDisplayStatus.EXPIRED -> MaterialTheme.colorScheme.tertiary
}

@Composable
private fun sealStatusContainer(status: SealDisplayStatus): Color = when (status) {
    SealDisplayStatus.WAITING, SealDisplayStatus.SIGNING, SealDisplayStatus.SIGNED ->
        MaterialTheme.colorScheme.primaryContainer
    SealDisplayStatus.REJECTED, SealDisplayStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
    SealDisplayStatus.CANCELED, SealDisplayStatus.LEGACY_FINISHED ->
        MaterialTheme.colorScheme.surfaceContainerHighest
    SealDisplayStatus.EXPIRED -> MaterialTheme.colorScheme.tertiaryContainer
}

@Composable
private fun sealStatusContent(status: SealDisplayStatus): Color = when (status) {
    SealDisplayStatus.WAITING, SealDisplayStatus.SIGNING, SealDisplayStatus.SIGNED ->
        MaterialTheme.colorScheme.onPrimaryContainer
    SealDisplayStatus.REJECTED, SealDisplayStatus.FAILED -> MaterialTheme.colorScheme.onErrorContainer
    SealDisplayStatus.CANCELED, SealDisplayStatus.LEGACY_FINISHED -> MaterialTheme.colorScheme.onSurface
    SealDisplayStatus.EXPIRED -> MaterialTheme.colorScheme.onTertiaryContainer
}

private data class GitIdentity(val name: String, val email: String, val timestampSeconds: Long) {
    companion object {
        private val PATTERN = Regex("^(.*) <([^<>]+)> ([0-9]+) [+-][0-9]{4}$")

        fun parse(raw: String): GitIdentity? {
            val match = PATTERN.matchEntire(raw) ?: return null
            return GitIdentity(
                name = match.groupValues[1],
                email = match.groupValues[2],
                timestampSeconds = match.groupValues[3].toLongOrNull() ?: return null,
            )
        }
    }
}

internal fun String.shortObjectId(): String = take(7)

private fun String.formattedKeyId(): String = "0x" + chunked(4).joinToString(" ")


@Composable
private fun rememberShortTimeFormatter(): DateFormat = remember {
    DateFormat.getTimeInstance(DateFormat.SHORT)
}

@Composable
private fun rememberDateTimeFormatter(): DateFormat = remember {
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
}

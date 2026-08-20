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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Commit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.VerifiedUser
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

internal enum class SealDisplayStatus {
    WAITING,
    SIGNING,
    APPROVED,
    REJECTED,
    CANCELED,
    EXPIRED,
    FAILED,
    LEGACY_FINISHED,
}

internal fun StoredOpenPgpRequest.sealDisplayStatus(): SealDisplayStatus = when (result) {
    OpenPgpRequestResult.APPROVED -> SealDisplayStatus.APPROVED
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

@Composable
internal fun sealStatusLabel(status: SealDisplayStatus): String = stringResource(
    when (status) {
        SealDisplayStatus.WAITING -> R.string.seal_status_waiting
        SealDisplayStatus.SIGNING -> R.string.seal_status_signing
        SealDisplayStatus.APPROVED -> R.string.seal_result_approved
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
    val headline = commit?.message?.commitSubject().orEmpty().ifBlank {
        stringResource(R.string.seal_commit_untitled)
    }
    val time = rememberShortTimeFormatter().format(Date(stored.updatedAt))
    val base = commit?.parentIds?.firstOrNull()?.shortObjectId()
        ?: commit?.treeId?.shortObjectId()
    val workingDirectory = stored.request.workingDirectory?.workingDirectoryName()

    Surface {
        ListItem(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
            leadingContent = { SealStatusIcon(status) },
            headlineContent = {
                Text(headline, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
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
                Icon(Icons.Outlined.ChevronRight, contentDescription = null)
            },
        )
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
                                Icons.AutoMirrored.Outlined.ArrowBack,
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
                    subject = commit?.message?.commitSubject().orEmpty().ifBlank {
                        stringResource(R.string.seal_commit_untitled)
                    },
                    requesterName = requesterName,
                    workingDirectory = stored.request.workingDirectory,
                    reference = commit?.parentIds?.firstOrNull()?.shortObjectId()
                        ?: commit?.treeId?.shortObjectId(),
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
                            Icon(Icons.Outlined.Info, contentDescription = null)
                            Text(
                                stringResource(R.string.seal_review_guidance),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }

        if (commit == null) {
            item {
                CenteredDetailItem {
                    SealCard(
                        title = stringResource(R.string.seal_commit_section),
                        icon = Icons.Outlined.AccountTree,
                    ) {
                        Text(
                            stringResource(R.string.seal_details_unavailable),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            item {
                CenteredDetailItem {
                    CommitCard(commit)
                }
            }
        }

        item {
            CenteredDetailItem {
                SealCard(
                    title = stringResource(R.string.seal_approval_section),
                    icon = Icons.Outlined.VerifiedUser,
                ) {
                    stored.request.workingDirectory?.let { workingDirectory ->
                        SealDetailLine(
                            icon = Icons.Outlined.Folder,
                            label = stringResource(R.string.seal_working_directory),
                            value = workingDirectory,
                            valueMonospace = true,
                        )
                        HorizontalDivider()
                    }
                    SealDetailLine(
                        icon = Icons.Outlined.Key,
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
                    icon = Icons.Outlined.Fingerprint,
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
                            commit?.payloadBytes ?: 0,
                            commit?.payloadBytes ?: 0,
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
        icon = Icons.Outlined.AccountTree,
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
        IdentityLine(Icons.Outlined.Person, stringResource(R.string.seal_author), commit.author)
        HorizontalDivider()
        IdentityLine(Icons.Outlined.VerifiedUser, stringResource(R.string.seal_committer), commit.committer)
        HorizontalDivider()
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ReferencePill(
                label = commit.treeId.shortObjectId(),
                icon = Icons.Outlined.AccountTree,
                iconContentDescription = stringResource(R.string.seal_tree),
            )
            if (commit.parentIds.isEmpty()) {
                ReferencePill(
                    label = stringResource(R.string.seal_commit_initial),
                    icon = Icons.Outlined.Commit,
                )
            } else {
                commit.parentIds.forEach { parent ->
                    ReferencePill(
                        label = parent.shortObjectId(),
                        icon = Icons.Outlined.Commit,
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
            SealDisplayStatus.WAITING -> Icons.Outlined.Schedule
            SealDisplayStatus.SIGNING -> Icons.Outlined.Sync
            SealDisplayStatus.APPROVED -> Icons.Outlined.CheckCircle
            SealDisplayStatus.REJECTED, SealDisplayStatus.CANCELED -> Icons.Outlined.Cancel
            SealDisplayStatus.EXPIRED -> Icons.Outlined.Schedule
            SealDisplayStatus.FAILED -> Icons.Outlined.ErrorOutline
            SealDisplayStatus.LEGACY_FINISHED -> Icons.Outlined.Info
        },
        contentDescription = sealStatusLabel(status),
        modifier = modifier,
        tint = sealStatusColor(status),
    )
}

@Composable
private fun sealStatusColor(status: SealDisplayStatus): Color = when (status) {
    SealDisplayStatus.WAITING, SealDisplayStatus.SIGNING, SealDisplayStatus.APPROVED ->
        MaterialTheme.colorScheme.primary
    SealDisplayStatus.REJECTED, SealDisplayStatus.FAILED -> MaterialTheme.colorScheme.error
    SealDisplayStatus.CANCELED, SealDisplayStatus.LEGACY_FINISHED -> MaterialTheme.colorScheme.onSurfaceVariant
    SealDisplayStatus.EXPIRED -> MaterialTheme.colorScheme.tertiary
}

@Composable
private fun sealStatusContainer(status: SealDisplayStatus): Color = when (status) {
    SealDisplayStatus.WAITING, SealDisplayStatus.SIGNING, SealDisplayStatus.APPROVED ->
        MaterialTheme.colorScheme.primaryContainer
    SealDisplayStatus.REJECTED, SealDisplayStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
    SealDisplayStatus.CANCELED, SealDisplayStatus.LEGACY_FINISHED ->
        MaterialTheme.colorScheme.surfaceContainerHighest
    SealDisplayStatus.EXPIRED -> MaterialTheme.colorScheme.tertiaryContainer
}

@Composable
private fun sealStatusContent(status: SealDisplayStatus): Color = when (status) {
    SealDisplayStatus.WAITING, SealDisplayStatus.SIGNING, SealDisplayStatus.APPROVED ->
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

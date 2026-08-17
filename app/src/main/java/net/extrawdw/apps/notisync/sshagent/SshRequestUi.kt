package net.extrawdw.apps.notisync.sshagent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import net.extrawdw.apps.notisync.R
import net.extrawdw.apps.notisync.ui.RequestDeviceSubCard
import net.extrawdw.apps.notisync.ui.SignatureIcon
import net.extrawdw.apps.notisync.ui.SshKeyPreviewCard
import net.extrawdw.apps.notisync.ui.SshKeyStorageOptions
import net.extrawdw.apps.notisync.ui.SshKeyStorageSelection
import net.extrawdw.notisync.protocol.SshImportSourceType
import net.extrawdw.notisync.protocol.SshProcessIdentity

internal enum class SshRequestDisplayStatus {
    WAITING,
    PROCESSING,
    SIGNED,
    IMPORTED,
    ALREADY_PRESENT,
    REJECTED,
    CANCELED,
    EXPIRED,
    FAILED,
    UNKNOWN,
}

internal fun StoredSshProviderRequest.displayStatus(): SshRequestDisplayStatus = when (outcome) {
    SshProviderRequestOutcome.SIGNED -> SshRequestDisplayStatus.SIGNED
    SshProviderRequestOutcome.IMPORTED -> SshRequestDisplayStatus.IMPORTED
    SshProviderRequestOutcome.ALREADY_PRESENT -> SshRequestDisplayStatus.ALREADY_PRESENT
    SshProviderRequestOutcome.REJECTED -> SshRequestDisplayStatus.REJECTED
    SshProviderRequestOutcome.FAILED -> SshRequestDisplayStatus.FAILED
    SshProviderRequestOutcome.CANCELLED -> SshRequestDisplayStatus.CANCELED
    SshProviderRequestOutcome.EXPIRED -> SshRequestDisplayStatus.EXPIRED
    null -> when (state) {
        SshProviderRequestState.PENDING_REVIEW -> SshRequestDisplayStatus.WAITING
        SshProviderRequestState.RESPONSE_PENDING_SEND -> SshRequestDisplayStatus.PROCESSING
        SshProviderRequestState.CANCELLED -> SshRequestDisplayStatus.CANCELED
        SshProviderRequestState.EXPIRED -> SshRequestDisplayStatus.EXPIRED
        SshProviderRequestState.SENT -> SshRequestDisplayStatus.UNKNOWN
    }
}

internal fun StoredSshProviderRequest.isActiveRequest(): Boolean =
    state == SshProviderRequestState.PENDING_REVIEW || state == SshProviderRequestState.RESPONSE_PENDING_SEND

@Composable
internal fun SshRequestListItem(
    request: StoredSshProviderRequest,
    requesterName: String,
    onClick: () -> Unit,
) {
    val status = request.displayStatus()
    val time = rememberShortTimeFormatter().format(Date(request.resultAt ?: request.updatedAt))
    Surface {
        ListItem(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
            leadingContent = { SshStatusIcon(status) },
            headlineContent = {
                Text(request.headline(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            supportingContent = {
                Column {
                    Text(statusLabel(status), color = statusColor(status), maxLines = 1)
                    Text(
                        listOfNotNull(request.contextLabel(), requesterName, time).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            trailingContent = { Icon(Icons.Outlined.ChevronRight, contentDescription = null) },
        )
    }
    HorizontalDivider()
}

@Composable
internal fun SshHistoryRequestDetail(
    request: StoredSshProviderRequest,
    requesterName: String,
    requesterIdentityKeyFingerprint: String?,
    onBack: () -> Unit,
) {
    val keyPreview = remember(request.requestId) {
        request.history.publicKeyBlob?.let { blob ->
            runCatching { SshImportPreviewParser.preview(blob) }.getOrNull()
        }
    }
    SshRequestDetail(
        details = SshReviewScreenState.Details(
            request = request,
            rememberScopes = emptySet(),
            encryptedImport = request.history.encryptedImport,
            keyPreview = keyPreview,
            keyName = request.history.keyName
                ?: request.history.suggestedName
                ?: stringResource(R.string.ssh_agent_imported_key_default),
            requesterName = requesterName,
            requesterIdentityKeyFingerprint = requesterIdentityKeyFingerprint,
        ),
        storage = SshKeyStorageSelection(),
        passphrase = "",
        busy = false,
        onStorageChange = {},
        onPassphraseChange = {},
        onPreviewImport = {},
        onRemember = {},
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
        showSheetHeader = true,
        onBack = onBack,
    )
}

@Composable
internal fun SshReviewContent(
    state: SshReviewScreenState,
    storage: SshKeyStorageSelection,
    passphrase: String,
    busy: Boolean,
    onStorageChange: (SshKeyStorageSelection) -> Unit,
    onPassphraseChange: (String) -> Unit,
    onPreviewImport: () -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onRemember: () -> Unit,
    onClose: () -> Unit,
) {
    val details = state as? SshReviewScreenState.Details
    val pending = details?.request?.state == SshProviderRequestState.PENDING_REVIEW
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ssh_agent_name)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.ssh_agent_close))
                    }
                },
            )
        },
        bottomBar = {
            when {
                details == null -> Unit
                pending -> BottomAppBar(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(
                                if (details.request.kind == SshProviderRequestKind.IMPORT) {
                                    R.string.ssh_agent_review_import_title
                                } else {
                                    R.string.ssh_agent_review_sign_title
                                },
                            ),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            stringResource(R.string.ssh_agent_expiry_time, formatTime(details.request.expiresAt())),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = onReject, enabled = !busy) {
                        Text(stringResource(R.string.action_reject))
                    }
                    Spacer(Modifier.size(10.dp))
                    Button(
                        onClick = onApprove,
                        enabled = !busy && details.canApprove(passphrase),
                    ) {
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(8.dp))
                        }
                        Text(
                            stringResource(
                                if (details.request.kind == SshProviderRequestKind.IMPORT) {
                                    R.string.ssh_agent_import_action
                                } else {
                                    R.string.action_approve
                                },
                            ),
                        )
                    }
                }
                else -> BottomAppBar(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Spacer(Modifier.weight(1f))
                    Button(onClick = onClose) { Text(stringResource(R.string.ssh_agent_close)) }
                }
            }
        },
    ) { padding ->
        when (state) {
            SshReviewScreenState.Loading -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            is SshReviewScreenState.Error -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    Modifier.padding(24.dp).widthIn(max = 520.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                    Button(onClick = onClose) { Text(stringResource(R.string.ssh_agent_close)) }
                }
            }
            is SshReviewScreenState.Details -> SshRequestDetail(
                details = state,
                storage = storage,
                passphrase = passphrase,
                busy = busy,
                onStorageChange = onStorageChange,
                onPassphraseChange = onPassphraseChange,
                onPreviewImport = onPreviewImport,
                onRemember = onRemember,
                contentPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 16.dp,
                ),
            )
        }
    }
}

@Composable
internal fun SshRequestDetail(
    details: SshReviewScreenState.Details,
    storage: SshKeyStorageSelection,
    passphrase: String,
    busy: Boolean,
    onStorageChange: (SshKeyStorageSelection) -> Unit,
    onPassphraseChange: (String) -> Unit,
    onPreviewImport: () -> Unit,
    onRemember: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    showSheetHeader: Boolean = false,
    onBack: () -> Unit = {},
) {
    val request = details.request
    val status = request.displayStatus()
    val pending = request.state == SshProviderRequestState.PENDING_REVIEW
    val formatter = rememberDateTimeFormatter()
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (showSheetHeader) {
            item {
                CenteredRequestItem {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = stringResource(R.string.ssh_agent_back_to_history),
                            )
                        }
                        Text(
                            stringResource(R.string.ssh_agent_name),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                    }
                }
            }
        }
        item {
            CenteredRequestItem {
                SshRequestHero(request, details.requesterName, status)
            }
        }
        if (pending) {
            item {
                CenteredRequestItem {
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
                                stringResource(
                                    if (request.kind == SshProviderRequestKind.IMPORT) {
                                        R.string.ssh_agent_import_guidance
                                    } else {
                                        R.string.ssh_agent_sign_guidance
                                    },
                                ),
                            )
                        }
                    }
                }
            }
        }
        item {
            CenteredRequestItem {
                when (request.kind) {
                    SshProviderRequestKind.SIGN -> SignRequestCard(request)
                    SshProviderRequestKind.IMPORT -> ImportRequestCard(
                        details = details,
                        storage = storage,
                        passphrase = passphrase,
                        pending = pending,
                        onStorageChange = onStorageChange,
                        onPassphraseChange = onPassphraseChange,
                    )
                }
            }
        }
        item {
            CenteredRequestItem {
                SshKeyPreviewCard(
                    name = details.keyName,
                    preview = details.keyPreview,
                    showFullPublicKey = request.kind == SshProviderRequestKind.IMPORT,
                    titleIcon = Icons.Outlined.Key,
                    emptyContent = {
                        if (pending && request.kind == SshProviderRequestKind.IMPORT) {
                            OutlinedButton(
                                onClick = onPreviewImport,
                                enabled = !busy && (!details.encryptedImport || passphrase.isNotBlank()),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                if (busy) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.size(8.dp))
                                }
                                Text(stringResource(R.string.ssh_agent_review_key))
                            }
                        } else {
                            Text(
                                stringResource(R.string.ssh_agent_key_preview_unavailable),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                )
            }
        }
        if (pending && details.rememberScopes.isNotEmpty()) {
            item {
                CenteredRequestItem {
                    OutlinedButton(onClick = onRemember, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.ssh_agent_approve_remember))
                    }
                }
            }
        }
        item {
            CenteredRequestItem {
                RequestCard(
                    title = stringResource(R.string.ssh_agent_request_section),
                    icon = Icons.Outlined.Fingerprint,
                ) {
                    RequestDeviceSubCard(
                        deviceName = details.requesterName,
                        verificationNumber = request.requesterClientId.value,
                        identityKeyFingerprint = details.requesterIdentityKeyFingerprint,
                    )
                    RecordLine(stringResource(R.string.ssh_agent_requested_at), formatter.format(Date(request.requestedAt())))
                    RecordLine(stringResource(R.string.ssh_agent_updated_at), formatter.format(Date(request.resultAt ?: request.updatedAt)))
                    RecordLine(stringResource(R.string.ssh_agent_request_id), request.requestId.take(8), monospace = true)
                    RecordLine(stringResource(R.string.seal_sha256), request.requestFingerprint.toHex(), monospace = true)
                    RecordLine(
                        stringResource(R.string.ssh_agent_payload),
                        pluralStringResource(
                            R.plurals.ssh_agent_payload_bytes,
                            request.payloadSize(),
                            request.payloadSize(),
                        ),
                        monospace = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun SshRequestHero(
    request: StoredSshProviderRequest,
    requesterName: String,
    status: SshRequestDisplayStatus,
) {
    val content = statusColor(status)
    Surface(
        color = statusContainer(status),
        contentColor = content,
        shape = RoundedCornerShape(28.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = CircleShape, color = content.copy(alpha = 0.12f), contentColor = content) {
                Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                    SshStatusIcon(status, Modifier.size(30.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(statusLabel(status), style = MaterialTheme.typography.labelLarge)
                Text(request.headline(), style = MaterialTheme.typography.titleLarge)
                Text(
                    listOfNotNull(requesterName, request.contextLabel()).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.seal_hash, request.requestFingerprint.toHex().take(7)),
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun SignRequestCard(request: StoredSshProviderRequest) {
    val history = request.history
    val processLineage = request.processLineageForDisplay()
    RequestCard(stringResource(R.string.ssh_agent_request_sign), Icons.Outlined.Terminal) {
        DetailLine(
            Icons.Outlined.Computer,
            stringResource(R.string.ssh_agent_destination),
            request.destinationLabel() ?: stringResource(R.string.ssh_agent_unavailable),
            true,
        )
        HorizontalDivider()
        DetailLine(
            Icons.Outlined.Person,
            stringResource(R.string.ssh_agent_destination_username),
            history.destinationUsername ?: stringResource(R.string.ssh_agent_unavailable),
            true,
        )
        HorizontalDivider()
        DetailLine(
            Icons.Outlined.Fingerprint,
            stringResource(R.string.ssh_agent_destination_host_key_fingerprint),
            history.destinationHostKeyFingerprint ?: stringResource(R.string.ssh_agent_unavailable),
            true,
        )
        HorizontalDivider()
        ProcessLineageLine(
            processLineage = processLineage,
            unavailable = stringResource(R.string.ssh_agent_unavailable),
            reportedByRequester = stringResource(R.string.ssh_agent_process_reported_by_requester),
        )
        HorizontalDivider()
        DetailLine(
            SignatureIcon,
            stringResource(R.string.ssh_agent_signature_algorithm),
            history.signatureAlgorithm?.name ?: stringResource(R.string.ssh_agent_unavailable),
            true,
        )
    }
}

@Composable
private fun ProcessLineageLine(
    processLineage: List<SshProcessIdentity>,
    unavailable: String,
    reportedByRequester: String,
) {
    var showFullPaths by remember(processLineage) { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Outlined.Terminal,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(R.string.ssh_agent_process),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SelectionContainer {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .clickable(enabled = processLineage.isNotEmpty()) { showFullPaths = !showFullPaths },
                ) {
                    Text(
                        text = processLineage.toProcessTreeText(showFullPaths).ifEmpty { unavailable },
                        fontFamily = FontFamily.Monospace,
                        softWrap = false,
                    )
                }
            }
            Text(
                reportedByRequester,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ImportRequestCard(
    details: SshReviewScreenState.Details,
    storage: SshKeyStorageSelection,
    passphrase: String,
    pending: Boolean,
    onStorageChange: (SshKeyStorageSelection) -> Unit,
    onPassphraseChange: (String) -> Unit,
) {
    val history = details.request.history
    RequestCard(stringResource(R.string.ssh_agent_request_import), Icons.Outlined.Key) {
        DetailLine(
            Icons.Outlined.Terminal,
            stringResource(R.string.ssh_agent_import_source),
            stringResource(
                if (history.importSourceType == SshImportSourceType.AGENT_IDENTITY) {
                    R.string.ssh_agent_import_source_agent
                } else {
                    R.string.ssh_agent_import_source_file
                },
            ),
        )
        if (pending && details.encryptedImport) {
            HorizontalDivider()
            OutlinedTextField(
                value = passphrase,
                onValueChange = onPassphraseChange,
                label = { Text(stringResource(R.string.ssh_agent_passphrase)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (pending) {
            HorizontalDivider()
            SshKeyStorageOptions(storage, onStorageChange)
            Text(
                stringResource(R.string.ssh_agent_import_storage_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        details.errorMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun RequestCard(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null)
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

@Composable
private fun DetailLine(
    icon: ImageVector,
    label: String,
    value: String,
    monospace: Boolean = false,
    supporting: String? = null,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectionContainer {
                Text(value, fontFamily = if (monospace) FontFamily.Monospace else null)
            }
            supporting?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun RecordLine(label: String, value: String, monospace: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer {
            Text(value, fontFamily = if (monospace) FontFamily.Monospace else null)
        }
    }
}

@Composable
private fun CenteredRequestItem(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.fillMaxWidth().widthIn(max = 720.dp)) { content() }
    }
}

@Composable
private fun SshStatusIcon(status: SshRequestDisplayStatus, modifier: Modifier = Modifier) {
    Icon(statusIcon(status), contentDescription = statusLabel(status), modifier = modifier, tint = statusColor(status))
}

private fun statusIcon(status: SshRequestDisplayStatus): ImageVector = when (status) {
    SshRequestDisplayStatus.WAITING -> Icons.Outlined.Schedule
    SshRequestDisplayStatus.PROCESSING -> Icons.Outlined.Sync
    SshRequestDisplayStatus.SIGNED,
    SshRequestDisplayStatus.IMPORTED,
    SshRequestDisplayStatus.ALREADY_PRESENT,
    -> Icons.Outlined.CheckCircle
    SshRequestDisplayStatus.REJECTED,
    SshRequestDisplayStatus.CANCELED,
    -> Icons.Outlined.Cancel
    SshRequestDisplayStatus.EXPIRED -> Icons.Outlined.Schedule
    SshRequestDisplayStatus.FAILED,
    SshRequestDisplayStatus.UNKNOWN,
    -> Icons.Outlined.ErrorOutline
}

@Composable
private fun statusLabel(status: SshRequestDisplayStatus): String = stringResource(
    when (status) {
        SshRequestDisplayStatus.WAITING -> R.string.ssh_agent_state_pending
        SshRequestDisplayStatus.PROCESSING -> R.string.ssh_agent_state_sending
        SshRequestDisplayStatus.SIGNED -> R.string.ssh_agent_result_signed
        SshRequestDisplayStatus.IMPORTED -> R.string.ssh_agent_result_imported
        SshRequestDisplayStatus.ALREADY_PRESENT -> R.string.ssh_agent_result_already_present
        SshRequestDisplayStatus.REJECTED -> R.string.ssh_agent_result_rejected
        SshRequestDisplayStatus.CANCELED -> R.string.ssh_agent_state_cancelled
        SshRequestDisplayStatus.EXPIRED -> R.string.ssh_agent_state_expired
        SshRequestDisplayStatus.FAILED -> R.string.ssh_agent_result_failed
        SshRequestDisplayStatus.UNKNOWN -> R.string.ssh_agent_result_unknown
    },
)

@Composable
private fun statusColor(status: SshRequestDisplayStatus): Color = when (status) {
    SshRequestDisplayStatus.REJECTED,
    SshRequestDisplayStatus.FAILED,
    -> MaterialTheme.colorScheme.error
    SshRequestDisplayStatus.WAITING,
    SshRequestDisplayStatus.PROCESSING,
    SshRequestDisplayStatus.EXPIRED,
    SshRequestDisplayStatus.CANCELED,
    SshRequestDisplayStatus.UNKNOWN,
    -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.colorScheme.primary
}

@Composable
private fun statusContainer(status: SshRequestDisplayStatus): Color = when (status) {
    SshRequestDisplayStatus.REJECTED,
    SshRequestDisplayStatus.FAILED,
    -> MaterialTheme.colorScheme.errorContainer
    SshRequestDisplayStatus.WAITING,
    SshRequestDisplayStatus.PROCESSING,
    -> MaterialTheme.colorScheme.secondaryContainer
    else -> MaterialTheme.colorScheme.surfaceContainerHighest
}

@Composable
private fun StoredSshProviderRequest.headline(): String = when (kind) {
    SshProviderRequestKind.SIGN -> destinationLabel() ?: stringResource(R.string.ssh_agent_request_sign)
    SshProviderRequestKind.IMPORT -> history.suggestedName ?: stringResource(R.string.ssh_agent_imported_key_default)
}

private fun StoredSshProviderRequest.contextLabel(): String? = when (kind) {
    SshProviderRequestKind.SIGN -> processLineageLeafFirst().mainCallerLabel()
    SshProviderRequestKind.IMPORT -> if (history.importSourceType == SshImportSourceType.AGENT_IDENTITY) {
        "ssh-add"
    } else null
}

internal fun StoredSshProviderRequest.destinationLabel(): String? {
    val host = history.destinationHost ?: return null
    return history.destinationUsername?.let { "$it@$host" } ?: host
}

/** Returns the available caller chain from the system root to the SSH client process. */
internal fun StoredSshProviderRequest.processLineageForDisplay(): List<SshProcessIdentity> {
    return processLineageLeafFirst().asReversed()
}

private fun StoredSshProviderRequest.processLineageLeafFirst(): List<SshProcessIdentity> =
    signRequest?.processContext?.processLineage ?: history.processLineage

internal fun List<SshProcessIdentity>.toProcessTreeText(showFullPaths: Boolean = false): String =
    mapIndexed { index, process ->
    val branch = if (index == 0) "" else "  ".repeat(index - 1) + "└─ "
    val name = if (showFullPaths) process.executablePath else process.shortProcessName()
    "$branch$name (${process.pid})"
}.joinToString("\n")

private fun StoredSshProviderRequest.requestedAt(): Long = history.requestedAt
private fun StoredSshProviderRequest.expiresAt(): Long = history.expiresAt
private fun StoredSshProviderRequest.payloadSize(): Int = history.payloadSize

private fun SshReviewScreenState.Details.canApprove(passphrase: String): Boolean =
    request.kind == SshProviderRequestKind.SIGN ||
        (keyPreview != null && (!encryptedImport || passphrase.isNotBlank()))

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

@Composable
private fun rememberShortTimeFormatter(): DateFormat = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }

@Composable
private fun rememberDateTimeFormatter(): DateFormat =
    remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

private fun formatTime(epochMillis: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(epochMillis))

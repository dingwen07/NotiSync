package net.extrawdw.apps.notisync.sshkeyprovider

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import net.extrawdw.apps.notisync.ui.icons.material.outlined.arrow_back as ArrowBackIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.cancel as CancelIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.check_circle as CheckCircleIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.chevron_right as ChevronRightIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.close as CloseIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.computer as ComputerIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.error_outline as ErrorOutlineIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.fingerprint as FingerprintIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.info as InfoIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.key as KeyIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.person as PersonIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.schedule as ScheduleIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.sync as SyncIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.terminal as TerminalIcon
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ProvideTextStyle
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import java.text.DateFormat
import java.util.Date
import net.extrawdw.apps.notisync.R
import net.extrawdw.apps.notisync.ui.RequestDeviceSubCard
import net.extrawdw.apps.notisync.ui.SignatureIcon
import net.extrawdw.apps.notisync.ui.SshKeyPreviewCard
import net.extrawdw.notisync.protocol.DesktopProcessIdentity
import net.extrawdw.notisync.protocol.SshImportSourceType

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

internal fun StoredSshProviderRequest.shouldCloseAutoOpenedReview(autoLaunchOwned: Boolean): Boolean =
    autoLaunchOwned && displayStatus() in setOf(
        SshRequestDisplayStatus.CANCELED,
        SshRequestDisplayStatus.EXPIRED,
    )

@Composable
internal fun SshRequestListItem(
    request: StoredSshProviderRequest,
    requesterName: String,
    knownHostname: String? = null,
    onClick: () -> Unit,
) {
    val status = request.displayStatus()
    val time = rememberShortTimeFormatter().format(Date(request.resultAt ?: request.updatedAt))
    Surface {
        ListItem(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
            leadingContent = { SshStatusIcon(status) },
            supportingContent = {
                Column {
                    Text(
                        listOfNotNull(statusLabel(status), request.approvalLabel()).joinToString(" · "),
                        color = statusColor(status),
                        maxLines = 1,
                    )
                    Text(
                        listOfNotNull(request.contextLabel(), requesterName, time).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            trailingContent = { Icon(ChevronRightIcon, contentDescription = null) },
        ) {
            Text(request.headline(knownHostname), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    HorizontalDivider()
}

@Composable
internal fun SshHistoryRequestDetail(
    request: StoredSshProviderRequest,
    requesterName: String,
    requesterIdentityKeyFingerprint: String?,
    knownHostname: String?,
    contentPadding: PaddingValues,
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
            rememberChoices = emptySet(),
            rememberApplication = null,
            encryptedImport = request.history.encryptedImport,
            keyPreview = keyPreview,
            keyName = request.history.keyName
                ?: request.history.suggestedName
                ?: stringResource(R.string.ssh_key_provider_imported_key_default),
            requesterName = requesterName,
            requesterIdentityKeyFingerprint = requesterIdentityKeyFingerprint,
            destinationHostname = knownHostname,
        ),
        contentPadding = contentPadding,
        showSheetHeader = true,
        onBack = onBack,
    )
}

@Composable
internal fun SshReviewContent(
    state: SshReviewScreenState,
    busy: Boolean,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onRemember: (SshRememberAuthorizationChoice) -> Unit,
    onClose: () -> Unit,
) {
    val details = state as? SshReviewScreenState.Details
    val pending = details?.request?.state == SshProviderRequestState.PENDING_REVIEW
    val canRemember = details?.let {
        pending && it.request.kind == SshProviderRequestKind.SIGN && it.rememberChoices.isNotEmpty()
    } == true
    var showRememberOptions by remember(details?.request?.requestId) { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ssh_agent_name)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(CloseIcon, contentDescription = stringResource(R.string.ssh_key_provider_close))
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
                                    R.string.ssh_key_provider_review_import_title
                                } else {
                                    R.string.ssh_key_provider_review_sign_title
                                },
                            ),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            stringResource(R.string.ssh_key_provider_expiry_time, formatTime(details.request.expiresAt())),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = onReject, enabled = !busy) {
                        Text(stringResource(R.string.action_reject))
                    }
                    Spacer(Modifier.size(10.dp))
                    LongClickButton(
                        onClick = onApprove,
                        onLongClick = if (canRemember) {
                            { showRememberOptions = true }
                        } else {
                            null
                        },
                        enabled = !busy,
                    ) {
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(8.dp))
                        }
                        Text(
                            stringResource(
                                R.string.action_approve,
                            ),
                        )
                    }
                }
                else -> BottomAppBar(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Spacer(Modifier.weight(1f))
                    Button(onClick = onClose) { Text(stringResource(R.string.ssh_key_provider_close)) }
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
                    Icon(ErrorOutlineIcon, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                    Button(onClick = onClose) { Text(stringResource(R.string.ssh_key_provider_close)) }
                }
            }
            is SshReviewScreenState.Details -> SshRequestDetail(
                details = state,
                contentPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 16.dp,
                ),
            )
        }
    }
    if (showRememberOptions && canRemember) {
        val rememberDetails = requireNotNull(details)
        ModalBottomSheet(onDismissRequest = { showRememberOptions = false }) {
            RememberAuthorizationSheet(
                details = rememberDetails,
                busy = busy,
                onRemember = { scope ->
                    showRememberOptions = false
                    onRemember(scope)
                },
            )
        }
    }
}

@Composable
private fun LongClickButton(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    enabled: Boolean,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = ButtonDefaults.buttonColors()
    val containerColor = if (enabled) colors.containerColor else colors.disabledContainerColor
    val contentColor = if (enabled) colors.contentColor else colors.disabledContentColor
    Surface(
        modifier = Modifier.combinedClickable(
            enabled = enabled,
            role = Role.Button,
            onLongClick = onLongClick,
            onClick = onClick,
        ),
        shape = ButtonDefaults.shape,
        color = containerColor,
        contentColor = contentColor,
    ) {
        ProvideTextStyle(MaterialTheme.typography.labelLarge) {
            Row(
                modifier = Modifier
                    .defaultMinSize(
                        minWidth = ButtonDefaults.MinWidth,
                        minHeight = ButtonDefaults.MinHeight,
                    )
                    .padding(ButtonDefaults.ContentPadding),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }
}

@Composable
internal fun SshRequestDetail(
    details: SshReviewScreenState.Details,
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
                                ArrowBackIcon,
                                contentDescription = stringResource(R.string.ssh_key_provider_back_to_history),
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
                SshRequestHero(
                    request,
                    details.requesterName,
                    details.destinationHostname,
                    status,
                    approvalPresentation = pending,
                )
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
                            Icon(InfoIcon, contentDescription = null)
                            Text(
                                stringResource(
                                    if (request.kind == SshProviderRequestKind.IMPORT) {
                                        R.string.ssh_key_provider_import_guidance
                                    } else {
                                        R.string.ssh_key_provider_sign_guidance
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
                    SshProviderRequestKind.SIGN -> SignRequestCard(details)
                    SshProviderRequestKind.IMPORT -> ImportRequestCard(details)
                }
            }
        }
        item {
            CenteredRequestItem {
                SshKeyPreviewCard(
                    name = details.keyName,
                    preview = details.keyPreview,
                    showFullPublicKey = request.kind == SshProviderRequestKind.IMPORT,
                    titleIcon = KeyIcon,
                    emptyContent = {
                        Text(
                            stringResource(R.string.ssh_key_provider_key_preview_unavailable),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }
        }
        item {
            CenteredRequestItem {
                RequestCard(
                    title = stringResource(R.string.ssh_key_provider_request_section),
                    icon = FingerprintIcon,
                ) {
                    RequestDeviceSubCard(
                        deviceName = details.requesterName,
                        verificationNumber = request.requesterClientId.value,
                        identityKeyFingerprint = details.requesterIdentityKeyFingerprint,
                    )
                    RecordLine(stringResource(R.string.ssh_key_provider_requested_at), formatter.format(Date(request.requestedAt())))
                    RecordLine(stringResource(R.string.ssh_key_provider_updated_at), formatter.format(Date(request.resultAt ?: request.updatedAt)))
                    request.approvalLabel()?.let {
                        RecordLine(stringResource(R.string.ssh_key_provider_approval_method), it)
                    }
                    RecordLine(stringResource(R.string.ssh_key_provider_request_id), request.requestId.take(8), monospace = true)
                    RecordLine(stringResource(R.string.seal_sha256), request.requestFingerprint.toHex(), monospace = true)
                    RecordLine(
                        stringResource(R.string.ssh_key_provider_payload),
                        pluralStringResource(
                            R.plurals.ssh_key_provider_payload_bytes,
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
private fun RememberAuthorizationSheet(
    details: SshReviewScreenState.Details,
    busy: Boolean,
    onRemember: (SshRememberAuthorizationChoice) -> Unit,
) {
    val choices = details.rememberChoices
    LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(stringResource(R.string.ssh_key_provider_approve_remember), style = MaterialTheme.typography.headlineSmall)
        }
        item {
            RequestCard(stringResource(R.string.ssh_key_provider_request_section), FingerprintIcon) {
                RequestDeviceSubCard(
                    deviceName = details.requesterName,
                    verificationNumber = details.request.requesterClientId.value,
                    identityKeyFingerprint = details.requesterIdentityKeyFingerprint,
                )
            }
        }
        item {
            RequestCard(stringResource(R.string.ssh_key_provider_request_sign), TerminalIcon) {
                DestinationDetailLine(details)
                HorizontalDivider()
                HostKeyDetailLine(details)
            }
        }
        item {
            Text(
                stringResource(R.string.ssh_key_provider_remember_pending_help),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val application = details.rememberApplication
        if (SshRememberAuthorizationChoice.APPLICATION_HOST in choices && application != null) {
            item {
                RememberApplicationButton(
                    application,
                    SshRememberAuthorizationChoice.APPLICATION_HOST,
                    busy,
                    onRemember,
                )
            }
        }
        if (SshRememberAuthorizationChoice.APPLICATION in choices && application != null) {
            item {
                RememberApplicationButton(
                    application,
                    SshRememberAuthorizationChoice.APPLICATION,
                    busy,
                    onRemember,
                )
            }
        }
        if (SshRememberAuthorizationChoice.PEER_HOST in choices) {
            item {
                OutlinedButton(
                    onClick = { onRemember(SshRememberAuthorizationChoice.PEER_HOST) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(R.string.ssh_key_provider_remember_peer_host, details.requesterName),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        if (SshRememberAuthorizationChoice.PEER in choices) {
            item {
                OutlinedButton(
                    onClick = { onRemember(SshRememberAuthorizationChoice.PEER) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(R.string.ssh_key_provider_remember_peer, details.requesterName),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun RememberApplicationButton(
    application: SshApplicationAnchor,
    choice: SshRememberAuthorizationChoice,
    busy: Boolean,
    onRemember: (SshRememberAuthorizationChoice) -> Unit,
) {
    val label = when (choice) {
        SshRememberAuthorizationChoice.APPLICATION -> R.string.ssh_key_provider_remember_application
        SshRememberAuthorizationChoice.APPLICATION_HOST -> R.string.ssh_key_provider_remember_application_host
        else -> error("application remember button requires an application choice")
    }
    OutlinedButton(
        onClick = { onRemember(choice) },
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(stringResource(label, application.displayName))
            Text(
                application.identity.executablePath,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SshRequestHero(
    request: StoredSshProviderRequest,
    requesterName: String,
    knownHostname: String?,
    status: SshRequestDisplayStatus,
    approvalPresentation: Boolean,
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
                Text(
                    request.headline(knownHostname, approvalPresentation),
                    style = MaterialTheme.typography.titleLarge,
                )
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
private fun SignRequestCard(
    details: SshReviewScreenState.Details,
) {
    val request = details.request
    val history = request.history
    val processLineage = request.processLineageForDisplay()
    RequestCard(stringResource(R.string.ssh_key_provider_request_sign), TerminalIcon) {
        DestinationDetailLine(details)
        HorizontalDivider()
        DetailLine(
            PersonIcon,
            stringResource(R.string.ssh_key_provider_destination_username),
            history.destinationUsername ?: stringResource(R.string.ssh_key_provider_unavailable),
            true,
        )
        HorizontalDivider()
        HostKeyDetailLine(details)
        HorizontalDivider()
        ProcessLineageLine(
            processLineage = processLineage,
            unavailable = stringResource(R.string.ssh_key_provider_unavailable),
            reportedByRequester = stringResource(R.string.ssh_key_provider_process_reported_by_requester),
        )
        HorizontalDivider()
        DetailLine(
            SignatureIcon,
            stringResource(R.string.ssh_key_provider_signature_algorithm),
            history.signatureAlgorithm?.name ?: stringResource(R.string.ssh_key_provider_unavailable),
            true,
        )
    }
}

@Composable
private fun ProcessLineageLine(
    processLineage: List<DesktopProcessIdentity>,
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
            TerminalIcon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(R.string.ssh_key_provider_process),
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
private fun DestinationDetailLine(
    details: SshReviewScreenState.Details,
) {
    DetailLine(
        ComputerIcon,
        stringResource(R.string.ssh_key_provider_destination),
        details.request.approvalDestinationLabel(details.destinationHostname)
            ?: stringResource(R.string.ssh_key_provider_unknown),
        true,
    )
}

@Composable
private fun HostKeyDetailLine(details: SshReviewScreenState.Details) {
    DetailLine(
        FingerprintIcon,
        stringResource(R.string.ssh_key_provider_destination_host_key_fingerprint),
        details.request.history.destinationHostKeyFingerprint ?: stringResource(R.string.ssh_key_provider_unavailable),
        true,
    )
}

@Composable
private fun ImportRequestCard(
    details: SshReviewScreenState.Details,
) {
    val history = details.request.history
    RequestCard(stringResource(R.string.ssh_key_provider_request_import), KeyIcon) {
        DetailLine(
            TerminalIcon,
            stringResource(R.string.ssh_key_provider_import_source),
            stringResource(
                if (history.importSourceType == SshImportSourceType.AGENT_IDENTITY) {
                    R.string.ssh_key_provider_import_source_agent
                } else {
                    R.string.ssh_key_provider_import_source_file
                },
            ),
        )
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
    SshRequestDisplayStatus.WAITING -> ScheduleIcon
    SshRequestDisplayStatus.PROCESSING -> SyncIcon
    SshRequestDisplayStatus.SIGNED,
    SshRequestDisplayStatus.IMPORTED,
    SshRequestDisplayStatus.ALREADY_PRESENT,
    -> CheckCircleIcon
    SshRequestDisplayStatus.REJECTED,
    SshRequestDisplayStatus.CANCELED,
    -> CancelIcon
    SshRequestDisplayStatus.EXPIRED -> ScheduleIcon
    SshRequestDisplayStatus.FAILED,
    SshRequestDisplayStatus.UNKNOWN,
    -> ErrorOutlineIcon
}

@Composable
private fun statusLabel(status: SshRequestDisplayStatus): String = stringResource(
    when (status) {
        SshRequestDisplayStatus.WAITING -> R.string.ssh_key_provider_state_pending
        SshRequestDisplayStatus.PROCESSING -> R.string.ssh_key_provider_state_sending
        SshRequestDisplayStatus.SIGNED -> R.string.ssh_key_provider_result_signed
        SshRequestDisplayStatus.IMPORTED -> R.string.ssh_key_provider_result_imported
        SshRequestDisplayStatus.ALREADY_PRESENT -> R.string.ssh_key_provider_result_already_present
        SshRequestDisplayStatus.REJECTED -> R.string.ssh_key_provider_result_rejected
        SshRequestDisplayStatus.CANCELED -> R.string.ssh_key_provider_state_cancelled
        SshRequestDisplayStatus.EXPIRED -> R.string.ssh_key_provider_state_expired
        SshRequestDisplayStatus.FAILED -> R.string.ssh_key_provider_result_failed
        SshRequestDisplayStatus.UNKNOWN -> R.string.ssh_key_provider_result_unknown
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
private fun StoredSshProviderRequest.headline(
    knownHostname: String? = null,
    approvalPresentation: Boolean = false,
): String = when (kind) {
    SshProviderRequestKind.SIGN -> (if (approvalPresentation) {
        approvalDestinationLabel(knownHostname)
    } else {
        destinationLabel(knownHostname)
    }) ?: stringResource(R.string.ssh_key_provider_request_sign)
    SshProviderRequestKind.IMPORT -> history.suggestedName ?: stringResource(R.string.ssh_key_provider_imported_key_default)
}

@Composable
private fun StoredSshProviderRequest.approvalLabel(): String? = when (history.approvalKind) {
    SshRequestApprovalKind.MANUAL -> stringResource(R.string.ssh_key_provider_approval_manual)
    SshRequestApprovalKind.REMEMBERED_AUTHORIZATION ->
        stringResource(R.string.ssh_key_provider_approval_remembered)
    null -> null
}

private fun StoredSshProviderRequest.contextLabel(): String? = when (kind) {
    SshProviderRequestKind.SIGN -> processLineageLeafFirst().mainCallerLabel()
    SshProviderRequestKind.IMPORT -> if (history.importSourceType == SshImportSourceType.AGENT_IDENTITY) {
        "ssh-add"
    } else null
}

internal fun StoredSshProviderRequest.destinationLabel(knownHostname: String? = null): String? {
    val host = knownHostname ?: history.destinationHost ?: return null
    return history.destinationUsername?.let { "$it@$host" } ?: host
}

internal fun StoredSshProviderRequest.approvalDestinationLabel(knownHostname: String?): String? {
    return knownHostname
}

/** Returns the available caller chain from the system root to the SSH client process. */
internal fun StoredSshProviderRequest.processLineageForDisplay(): List<DesktopProcessIdentity> {
    return processLineageLeafFirst().asReversed()
}

private fun StoredSshProviderRequest.processLineageLeafFirst(): List<DesktopProcessIdentity> =
    signRequest?.processContext?.processLineage ?: history.processLineage

internal fun List<DesktopProcessIdentity>.toProcessTreeText(showFullPaths: Boolean = false): String =
    mapIndexed { index, process ->
        val branch = if (index == 0) "" else "  ".repeat(index - 1) + "└─ "
        val name = if (showFullPaths) {
            process.executablePath ?: process.displayName?.takeIf(String::isNotBlank)
        } else {
            process.shortProcessName()
        }
        if (name == null || name == "PID ${process.pid}") {
            "${branch}PID ${process.pid}"
        } else {
            "$branch$name (${process.pid})"
        }
    }.joinToString("\n")

private fun StoredSshProviderRequest.requestedAt(): Long = history.requestedAt
private fun StoredSshProviderRequest.expiresAt(): Long = history.expiresAt
private fun StoredSshProviderRequest.payloadSize(): Int = history.payloadSize

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

@Composable
private fun rememberShortTimeFormatter(): DateFormat = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }

@Composable
private fun rememberDateTimeFormatter(): DateFormat =
    remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

private fun formatTime(epochMillis: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(epochMillis))

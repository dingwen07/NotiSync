package net.extrawdw.apps.notisync.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import net.extrawdw.apps.notisync.R
import net.extrawdw.apps.notisync.sshkeyprovider.BUILT_IN_DESKTOP_APPLICATIONS
import net.extrawdw.apps.notisync.sshkeyprovider.DesktopApplicationDraft
import net.extrawdw.apps.notisync.sshkeyprovider.DesktopApplicationDraftError
import net.extrawdw.apps.notisync.sshkeyprovider.DesktopApplicationIcon
import net.extrawdw.apps.notisync.sshkeyprovider.DesktopApplicationRemoteIcons
import net.extrawdw.apps.notisync.sshkeyprovider.DesktopApplicationIconSource
import net.extrawdw.apps.notisync.sshkeyprovider.DesktopApplicationIconNotFoundException
import net.extrawdw.apps.notisync.sshkeyprovider.DesktopApplicationIconDrafts
import net.extrawdw.apps.notisync.sshkeyprovider.DesktopApplicationLineageTraversal
import net.extrawdw.apps.notisync.sshkeyprovider.DesktopApplicationRepository
import net.extrawdw.apps.notisync.sshkeyprovider.DesktopApplicationSnapshot
import net.extrawdw.apps.notisync.sshkeyprovider.DuplicateDesktopApplicationIdException
import net.extrawdw.apps.notisync.sshkeyprovider.KnownDesktopApplication
import net.extrawdw.apps.notisync.ui.icons.material.outlined.add as AddIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.close as CloseIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.search as SearchIcon

@Composable
internal fun DesktopApplicationRegistryScreen(onDismiss: () -> Unit) {
    val repository = rememberGraph().desktopApplications
    val snapshot by repository.snapshot.collectAsStateWithLifecycle()
    var search by rememberSaveable { mutableStateOf("") }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    val editing = creating || editingId != null
    val entries = remember(snapshot, search) {
        snapshot.registry.applications.filter { application ->
            search.isBlank() || listOf(application.id, application.displayName)
                .plus(application.acceptedNames).plus(application.acceptedPaths)
                .any { it.contains(search.trim(), ignoreCase = true) }
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })
    }
    PredictiveBackDialog(onDismiss = onDismiss, dismissEnabled = !editing) { requestClose ->
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.desktop_applications_title)) },
                    navigationIcon = {
                        IconButton(onClick = requestClose) {
                            Icon(CloseIcon, stringResource(R.string.ssh_key_provider_close))
                        }
                    },
                    actions = {
                        IconButton(onClick = { creating = true }) {
                            Icon(AddIcon, stringResource(R.string.desktop_application_add))
                        }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                OutlinedTextField(
                    search, { search = it },
                    Modifier.fillMaxWidth().padding(start = 16.dp, top = 0.dp, end = 16.dp, bottom = 8.dp),
                    placeholder = { Text(stringResource(R.string.desktop_application_search)) },
                    leadingIcon = { Icon(SearchIcon, contentDescription = null) },
                    trailingIcon = {
                        if (search.isNotEmpty()) {
                            IconButton(onClick = { search = "" }) {
                                Icon(CloseIcon, stringResource(R.string.apps_clear_search))
                            }
                        }
                    },
                    singleLine = true,
                    shape = CircleShape,
                )
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
                    if (entries.isEmpty()) item {
                        Text(stringResource(R.string.desktop_application_no_results), Modifier.padding(24.dp))
                    }
                    items(entries, key = { it.id }) { application ->
                        ListItem(
                            modifier = Modifier.clickable { editingId = application.id },
                            supportingContent = {
                                Column {
                                    Text(application.id, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        application.acceptedNames.joinToString(", "),
                                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            },
                            trailingContent = {
                                DesktopApplicationIcon(snapshot.icon(application.id), Modifier.size(48.dp))
                            },
                        ) { Text(application.displayName) }
                    }
                }
            }
        }
        if (editing) {
            key(editingId) {
                DesktopApplicationEditor(
                    application = snapshot.registry.applications.firstOrNull { it.id == editingId },
                    snapshot = snapshot,
                    repository = repository,
                    onDismiss = { creating = false; editingId = null },
                )
            }
        }
    }
}

@Composable
private fun DesktopApplicationEditor(
    application: KnownDesktopApplication?,
    snapshot: DesktopApplicationSnapshot,
    repository: DesktopApplicationRepository,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val original = remember { application }
    val previousId = original?.id
    var id by rememberSaveable { mutableStateOf(application?.id.orEmpty()) }
    var displayName by rememberSaveable { mutableStateOf(application?.displayName.orEmpty()) }
    val fallbackDisplayName = original?.displayName ?: id.trim()
    var priority by rememberSaveable { mutableStateOf((application?.priority ?: 600).toString()) }
    var traversal by rememberSaveable { mutableStateOf(application?.traversal ?: DesktopApplicationLineageTraversal.CANDIDATE) }
    var names by rememberSaveable { mutableStateOf(application?.acceptedNames?.joinToString("\n").orEmpty()) }
    var paths by rememberSaveable { mutableStateOf(application?.acceptedPaths?.joinToString("\n").orEmpty()) }
    var iconToken by rememberSaveable { mutableStateOf<String?>(null) }
    var iconRemoved by rememberSaveable { mutableStateOf(false) }
    var iconSource by rememberSaveable { mutableStateOf("") }
    var fetchingIcon by remember { mutableStateOf<Job?>(null) }
    var iconSourceError by remember { mutableStateOf<Int?>(null) }
    val remoteIcons = remember { DesktopApplicationRemoteIcons() }
    DisposableEffect(remoteIcons) { onDispose { remoteIcons.close() } }
    var iconData by remember { mutableStateOf(snapshot.icon(previousId)) }
    var busy by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    var validation by remember { mutableStateOf<DesktopApplicationDraftError?>(null) }
    var error by remember { mutableStateOf<Int?>(null) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val builtIn = BUILT_IN_DESKTOP_APPLICATIONS.applications.any { it.id == previousId }
    val userEntry = previousId != null && snapshot.isUserEntry(previousId)
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        confirmValueChange = { !busy || closing },
    )

    LaunchedEffect(iconToken, iconRemoved) {
        try {
            iconData = if (iconRemoved) null else iconToken?.let { DesktopApplicationIconDrafts.read(context, it) }
                ?: snapshot.icon(previousId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = R.string.desktop_application_icon_error
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            error = null
            scope.launch {
                try {
                    val token = DesktopApplicationIconDrafts.import(context, uri)
                    DesktopApplicationIconDrafts.delete(context, iconToken)
                    iconToken = token
                    iconRemoved = false
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    error = R.string.desktop_application_icon_error
                } finally {
                    busy = false
                }
            }
        }
    }
    suspend fun finish() {
        closing = true
        sheetState.hide()
        DesktopApplicationIconDrafts.delete(context, iconToken)
        onDismiss()
    }
    fun close() {
        if (!busy) {
            busy = true
            scope.launch {
                try { finish() } finally { busy = false }
            }
        }
    }
    ModalBottomSheet(
        onDismissRequest = ::close,
        sheetState = sheetState,
        sheetGesturesEnabled = !busy,
        contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Top) },
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = !busy, shouldDismissOnClickOutside = !busy),
    ) {
        Column(
            Modifier.fillMaxWidth()
                .fillMaxHeight(0.92f)
                // Size the sheet before keyboard padding so focus changes cannot move its top edge.
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(if (original == null) R.string.desktop_application_add else R.string.desktop_application_edit),
                    Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall,
                )
                IconButton(onClick = ::close, enabled = !busy) {
                    Icon(CloseIcon, stringResource(R.string.ssh_key_provider_close))
                }
            }
            LazyColumn(
                Modifier.weight(1f), contentPadding = PaddingValues(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        DesktopApplicationIcon(iconData, Modifier.size(88.dp))
                        Column {
                            TextButton(onClick = { picker.launch(arrayOf("image/*")) }, enabled = !busy) {
                                Text(stringResource(R.string.desktop_application_choose_icon))
                            }
                            if (iconData != null || iconToken != null) TextButton(
                                onClick = {
                                    DesktopApplicationIconDrafts.delete(context, iconToken)
                                    iconToken = null
                                    iconRemoved = true
                                    iconData = null
                                }, enabled = !busy,
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            ) { Text(stringResource(R.string.desktop_application_remove_icon)) }
                        }
                    }
                }
                item {
                    OutlinedTextField(
                        value = iconSource,
                        onValueChange = { iconSource = it; iconSourceError = null },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.desktop_application_icon_source)) },
                        supportingText = {
                            Text(stringResource(iconSourceError ?: R.string.desktop_application_icon_source_hint))
                        },
                        singleLine = true,
                        enabled = !busy,
                        isError = iconSourceError != null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(
                            enabled = fetchingIcon != null || (!busy && iconSource.isNotBlank()),
                            onClick = {
                                if (fetchingIcon != null) {
                                    fetchingIcon?.cancel()
                                } else {
                                    val source = DesktopApplicationIconSource.parse(iconSource)
                                    iconSourceError = if (source == null) R.string.desktop_application_icon_source_error else null
                                    if (source != null) {
                                        busy = true
                                        fetchingIcon = scope.launch(start = CoroutineStart.LAZY) {
                                            try {
                                                val bytes = remoteIcons.load(source)
                                                val token = DesktopApplicationIconDrafts.import(context, bytes)
                                                DesktopApplicationIconDrafts.delete(context, iconToken)
                                                iconToken = token
                                                iconRemoved = false
                                                error = null
                                            } catch (_: TimeoutCancellationException) {
                                                iconSourceError = R.string.desktop_application_icon_load_error
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (_: DesktopApplicationIconNotFoundException) {
                                                iconSourceError = R.string.desktop_application_icon_not_found
                                            } catch (_: Exception) {
                                                iconSourceError = R.string.desktop_application_icon_load_error
                                            } finally {
                                                fetchingIcon = null
                                                busy = false
                                            }
                                        }
                                        fetchingIcon?.start()
                                    }
                                }
                            },
                        ) {
                            Text(stringResource(if (fetchingIcon != null) R.string.action_cancel else R.string.desktop_application_load_icon))
                        }
                    }
                }
                item {
                    EditorField(id, { id = it }, R.string.desktop_application_id, !busy,
                        validation in setOf(DesktopApplicationDraftError.ID, DesktopApplicationDraftError.DUPLICATE_ID))
                }
                item {
                    EditorField(displayName, { displayName = it }, R.string.desktop_application_display_name,
                        !busy, isError = false, placeholder = fallbackDisplayName)
                }
                item {
                    EditorField(priority, { priority = it }, R.string.desktop_application_priority,
                        !busy, validation == DesktopApplicationDraftError.PRIORITY,
                        hint = R.string.desktop_application_priority_hint, keyboardType = KeyboardType.Ascii)
                }
                item { TraversalField(traversal, { traversal = it }, !busy) }
                item {
                    EditorField(names, { names = it }, R.string.desktop_application_names,
                        !busy, validation == DesktopApplicationDraftError.NAMES, multiline = true,
                        hint = R.string.desktop_application_names_hint)
                }
                item {
                    EditorField(paths, { paths = it }, R.string.desktop_application_paths,
                        !busy, validation == DesktopApplicationDraftError.PATHS, multiline = true,
                        hint = R.string.desktop_application_paths_hint)
                }
                if (userEntry) item {
                    TextButton(onClick = { confirmDelete = true }, enabled = !busy) {
                        Text(
                            stringResource(if (builtIn) R.string.desktop_application_reset else R.string.desktop_application_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            HorizontalDivider()
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
                val message = validation?.messageResource() ?: error
                if (message != null) Text(stringResource(message), color = MaterialTheme.colorScheme.error)
                FlowRow(
                    Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(24.dp))
                    TextButton(onClick = ::close, enabled = !busy) { Text(stringResource(R.string.action_cancel)) }
                    Button(enabled = !busy, onClick = {
                        val draft = DesktopApplicationDraft(id, displayName, priority, traversal, names, paths)
                        validation = draft.validate(snapshot.registry.applications.map { it.id }.toSet(), previousId)
                        if (validation == null) {
                            busy = true
                            error = null
                            scope.launch {
                                try {
                                    val data = if (iconRemoved) null else iconToken?.let {
                                        DesktopApplicationIconDrafts.read(context, it)
                                    } ?: snapshot.icon(previousId)
                                    repository.save(draft.application(original?.displayName), data, previousId)
                                    finish()
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: DuplicateDesktopApplicationIdException) {
                                    validation = DesktopApplicationDraftError.DUPLICATE_ID
                                } catch (_: Exception) {
                                    error = R.string.desktop_application_save_error
                                } finally { busy = false }
                            }
                        }
                    }) { Text(stringResource(R.string.action_save)) }
                }
            }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { if (!busy) confirmDelete = false },
        title = { Text(stringResource(if (builtIn) R.string.desktop_application_reset else R.string.desktop_application_delete)) },
        text = { Text(stringResource(if (builtIn) R.string.desktop_application_reset_confirm else R.string.desktop_application_delete_confirm, fallbackDisplayName)) },
        dismissButton = {
            TextButton(onClick = { confirmDelete = false }, enabled = !busy) { Text(stringResource(R.string.action_cancel)) }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try {
                        repository.delete(requireNotNull(previousId))
                        confirmDelete = false
                        finish()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        confirmDelete = false
                        error = R.string.desktop_application_save_error
                    } finally { busy = false }
                }
            }) { Text(stringResource(if (builtIn) R.string.desktop_application_reset else R.string.desktop_application_delete)) }
        },
    )
}

@Composable
private fun EditorField(
    value: String,
    onChange: (String) -> Unit,
    label: Int,
    enabled: Boolean,
    isError: Boolean,
    multiline: Boolean = false,
    hint: Int? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    placeholder: String? = null,
) {
    OutlinedTextField(
        value, onChange, Modifier.fillMaxWidth(), enabled = enabled, isError = isError,
        label = { Text(stringResource(label)) }, singleLine = !multiline,
        minLines = if (multiline) 2 else 1, maxLines = if (multiline) 5 else 1,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, autoCorrectEnabled = false),
        supportingText = hint?.let { { Text(stringResource(it)) } },
        placeholder = placeholder?.let { { Text(it) } },
    )
}

@Composable
private fun TraversalField(
    value: DesktopApplicationLineageTraversal,
    onChange: (DesktopApplicationLineageTraversal) -> Unit,
    enabled: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded, { if (enabled) expanded = it }) {
        OutlinedTextField(
            stringResource(value.labelResource()), {},
            Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled),
            readOnly = true, enabled = enabled,
            label = { Text(stringResource(R.string.desktop_application_traversal)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            supportingText = { Text(stringResource(value.hintResource())) },
        )
        ExposedDropdownMenu(expanded, { expanded = false }) {
            DesktopApplicationLineageTraversal.entries.forEach { traversal ->
                DropdownMenuItem(text = { Text(stringResource(traversal.labelResource())) }, onClick = {
                    onChange(traversal)
                    expanded = false
                })
            }
        }
    }
}

private fun DesktopApplicationLineageTraversal.labelResource() = when (this) {
    DesktopApplicationLineageTraversal.CANDIDATE -> R.string.desktop_application_candidate
    DesktopApplicationLineageTraversal.SKIP -> R.string.desktop_application_skip
    DesktopApplicationLineageTraversal.STOP -> R.string.desktop_application_stop
}

private fun DesktopApplicationLineageTraversal.hintResource() = when (this) {
    DesktopApplicationLineageTraversal.CANDIDATE -> R.string.desktop_application_candidate_hint
    DesktopApplicationLineageTraversal.SKIP -> R.string.desktop_application_skip_hint
    DesktopApplicationLineageTraversal.STOP -> R.string.desktop_application_stop_hint
}

private fun DesktopApplicationDraftError.messageResource() = when (this) {
    DesktopApplicationDraftError.ID -> R.string.desktop_application_id_error
    DesktopApplicationDraftError.DUPLICATE_ID -> R.string.desktop_application_duplicate_id
    DesktopApplicationDraftError.PRIORITY -> R.string.desktop_application_priority_error
    DesktopApplicationDraftError.NAMES -> R.string.desktop_application_names_error
    DesktopApplicationDraftError.PATHS -> R.string.desktop_application_paths_error
}

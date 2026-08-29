package net.extrawdw.apps.notisync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.extrawdw.apps.notisync.R
import net.extrawdw.apps.notisync.sshkeyprovider.SshKeyPreview

internal enum class SshKeyImportSheetStep {
    CLIPBOARD,
    PASSPHRASE,
    REVIEW,
}

internal fun sshKeyImportSheetStep(
    privateKeyText: String?,
    encrypted: Boolean,
    preview: SshKeyPreview?,
): SshKeyImportSheetStep = when {
    privateKeyText != null -> SshKeyImportSheetStep.CLIPBOARD
    encrypted && preview == null -> SshKeyImportSheetStep.PASSPHRASE
    else -> SshKeyImportSheetStep.REVIEW
}

/** One Material 3 sheet for clipboard, SAF, and approved remote SSH key imports. */
@Composable
internal fun SshKeyImportSheet(
    privateKeyText: String?,
    encrypted: Boolean,
    preview: SshKeyPreview?,
    name: String,
    passphrase: String,
    storage: SshKeyStorageSelection,
    error: String?,
    previewing: Boolean,
    importing: Boolean,
    onPrivateKeyTextChange: (String) -> Unit,
    onPaste: () -> Unit,
    onNameChange: (String) -> Unit,
    onPassphraseChange: (String) -> Unit,
    onStorageChange: (SshKeyStorageSelection) -> Unit,
    onContinueClipboard: () -> Unit,
    onPreview: () -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    val step = sshKeyImportSheetStep(privateKeyText, encrypted, preview)
    val busy = previewing || importing
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = androidx.compose.ui.res.stringResource(R.string.ssh_key_provider_import_title),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                style = MaterialTheme.typography.headlineSmall,
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 24.dp,
                    end = 24.dp,
                    top = 8.dp,
                    bottom = 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (step) {
                    SshKeyImportSheetStep.CLIPBOARD -> {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = androidx.compose.ui.res.stringResource(R.string.ssh_key_provider_private_key_text),
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                TextButton(onClick = onPaste, enabled = !busy) {
                                    Text(androidx.compose.ui.res.stringResource(R.string.action_paste))
                                }
                            }
                        }
                        item {
                            OutlinedTextField(
                                value = privateKeyText.orEmpty(),
                                onValueChange = onPrivateKeyTextChange,
                                minLines = 8,
                                maxLines = 16,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 420.dp),
                                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            )
                        }
                    }

                    SshKeyImportSheetStep.PASSPHRASE -> {
                        item {
                            Text(
                                androidx.compose.ui.res.stringResource(R.string.ssh_key_provider_import_passphrase_prompt),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        item {
                            OutlinedTextField(
                                value = passphrase,
                                onValueChange = onPassphraseChange,
                                label = { Text(androidx.compose.ui.res.stringResource(R.string.ssh_key_provider_passphrase)) },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(
                                    autoCorrectEnabled = false,
                                    keyboardType = KeyboardType.Password,
                                    imeAction = ImeAction.Done,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    SshKeyImportSheetStep.REVIEW -> {
                        item {
                            OutlinedTextField(
                                value = name,
                                onValueChange = onNameChange,
                                label = { Text(androidx.compose.ui.res.stringResource(R.string.ssh_key_provider_key_name)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().onFocusChanged { focusState ->
                                    if (focusState.isFocused) scope.launch { sheetState.expand() }
                                },
                            )
                        }
                        preview?.let { keyPreview ->
                            item {
                                SshKeyPreviewCard(
                                    name = name,
                                    preview = keyPreview,
                                    showFullPublicKey = true,
                                )
                            }
                        }
                        item { SshKeyStorageOptions(storage, onStorageChange) }
                        item {
                            Text(
                                androidx.compose.ui.res.stringResource(R.string.ssh_key_provider_import_storage_help),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                error?.let { message ->
                    item {
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss, enabled = !busy) {
                    Text(androidx.compose.ui.res.stringResource(R.string.action_cancel))
                }
                Button(
                    onClick = when (step) {
                        SshKeyImportSheetStep.CLIPBOARD -> onContinueClipboard
                        SshKeyImportSheetStep.PASSPHRASE -> onPreview
                        SshKeyImportSheetStep.REVIEW -> onImport
                    },
                    enabled = !busy && when (step) {
                        SshKeyImportSheetStep.CLIPBOARD -> !privateKeyText.isNullOrBlank()
                        SshKeyImportSheetStep.PASSPHRASE -> passphrase.isNotBlank()
                        SshKeyImportSheetStep.REVIEW -> name.isNotBlank() && preview != null
                    },
                ) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        androidx.compose.ui.res.stringResource(
                            when (step) {
                                SshKeyImportSheetStep.CLIPBOARD -> R.string.action_continue
                                SshKeyImportSheetStep.PASSPHRASE -> R.string.ssh_key_provider_review_key
                                SshKeyImportSheetStep.REVIEW -> R.string.ssh_key_provider_import_action
                            },
                        ),
                    )
                }
            }
        }
    }
}

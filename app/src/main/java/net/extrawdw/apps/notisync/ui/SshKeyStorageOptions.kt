package net.extrawdw.apps.notisync.ui

import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.extrawdw.apps.notisync.R
import net.extrawdw.notisync.protocol.SshExportCopyBackendPolicy
import net.extrawdw.notisync.protocol.SshUserVerificationPolicy

data class SshKeyStorageSelection(
    val allowExport: Boolean = false,
    val exportCopyBackendPolicy: SshExportCopyBackendPolicy = SshExportCopyBackendPolicy.BEST_AVAILABLE,
    val userVerificationPolicy: SshUserVerificationPolicy = SshUserVerificationPolicy.NONE,
)

/** Shared generation/import storage selector. Unsupported choices remain visible but disabled. */
@Composable
fun SshKeyStorageOptions(
    selection: SshKeyStorageSelection,
    onSelectionChange: (SshKeyStorageSelection) -> Unit,
    allowExportable: Boolean = true,
    allowStrongBox: Boolean = true,
    allowPerUseAuthentication: Boolean = true,
) {
    val context = LocalContext.current
    val strongBoxAvailable = allowStrongBox &&
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
    val strongBiometricAvailable = allowPerUseAuthentication &&
        context.getSystemService(BiometricManager::class.java)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
    val exportAuthenticationAvailable = context.getSystemService(BiometricManager::class.java)
        .canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL,
        ) == BiometricManager.BIOMETRIC_SUCCESS
    val keepExportCopy = selection.allowExport
    Column {
        Text(stringResource(R.string.ssh_agent_export_settings), style = MaterialTheme.typography.labelLarge)
        ToggleRow(
            checked = keepExportCopy,
            enabled = allowExportable && exportAuthenticationAvailable,
            label = stringResource(R.string.ssh_agent_keep_export_backup),
            onCheckedChange = {
                onSelectionChange(
                    selection.copy(allowExport = it),
                )
            },
        )
        if (keepExportCopy) {
            ToggleRow(
                checked = selection.exportCopyBackendPolicy == SshExportCopyBackendPolicy.TEE_ONLY,
                enabled = strongBoxAvailable,
                label = stringResource(R.string.ssh_agent_export_copy_force_tee),
                onCheckedChange = {
                    onSelectionChange(
                        selection.copy(
                            exportCopyBackendPolicy = if (it) {
                                SshExportCopyBackendPolicy.TEE_ONLY
                            } else {
                                SshExportCopyBackendPolicy.BEST_AVAILABLE
                            },
                        ),
                    )
                },
            )
            Text(
                stringResource(R.string.ssh_agent_export_copy_auth_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(stringResource(R.string.ssh_agent_signing_storage), style = MaterialTheme.typography.labelLarge)
        ToggleRow(
            checked = selection.userVerificationPolicy == SshUserVerificationPolicy.PER_USE,
            enabled = strongBiometricAvailable,
            label = stringResource(R.string.ssh_agent_require_biometric_each_use),
            onCheckedChange = {
                onSelectionChange(
                    selection.copy(
                        userVerificationPolicy = if (it) {
                            SshUserVerificationPolicy.PER_USE
                        } else {
                            SshUserVerificationPolicy.NONE
                        },
                    ),
                )
            },
        )
        if (!strongBiometricAvailable) {
            Text(
                stringResource(R.string.ssh_agent_strong_biometric_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!exportAuthenticationAvailable) {
            Text(
                stringResource(R.string.ssh_agent_export_auth_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ToggleRow(
    checked: Boolean,
    enabled: Boolean,
    label: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Text(label, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

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
import net.extrawdw.notisync.protocol.SshExportability
import net.extrawdw.notisync.protocol.SshUserVerificationPolicy

data class SshKeyStorageSelection(
    val exportability: SshExportability = SshExportability.NON_EXPORTABLE,
    val preferStrongBox: Boolean = true,
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
    val keepExportBackup = selection.exportability == SshExportability.EXPORTABLE
    Column {
        Text(stringResource(R.string.ssh_agent_export_settings), style = MaterialTheme.typography.labelLarge)
        ToggleRow(
            checked = keepExportBackup,
            enabled = allowExportable,
            label = stringResource(R.string.ssh_agent_keep_export_backup),
            onCheckedChange = {
                onSelectionChange(
                    selection.copy(
                        exportability = if (it) SshExportability.EXPORTABLE else SshExportability.NON_EXPORTABLE,
                    ),
                )
            },
        )
        Text(stringResource(R.string.ssh_agent_storage), style = MaterialTheme.typography.labelLarge)
        ToggleRow(
            checked = selection.preferStrongBox,
            enabled = strongBoxAvailable,
            label = stringResource(R.string.ssh_agent_prefer_strongbox),
            onCheckedChange = { onSelectionChange(selection.copy(preferStrongBox = it)) },
        )
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

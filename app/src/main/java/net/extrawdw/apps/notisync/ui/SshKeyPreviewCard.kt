package net.extrawdw.apps.notisync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.security.interfaces.RSAPublicKey
import net.extrawdw.apps.notisync.R
import net.extrawdw.apps.notisync.sshkeyprovider.SshKeyPreview
import net.extrawdw.notisync.protocol.SshKeyAlgorithm
import net.extrawdw.notisync.ssh.core.SshPublicKeyCodec

/** Shared public key identity for signing and import review. */
@Composable
internal fun SshKeyPreviewCard(
    name: String,
    preview: SshKeyPreview?,
    showFullPublicKey: Boolean,
    modifier: Modifier = Modifier,
    titleIcon: ImageVector? = null,
    emptyContent: (@Composable () -> Unit)? = null,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                titleIcon?.let { Icon(it, contentDescription = null) }
                Text(
                    stringResource(R.string.ssh_key_provider_ssh_key),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            PreviewValue(
                label = stringResource(R.string.ssh_key_provider_key_name),
                value = name,
                monospace = false,
            )
            if (preview != null) {
                PreviewValue(
                    label = stringResource(R.string.ssh_key_provider_algorithm),
                    value = preview.algorithm.displayName(preview),
                    monospace = false,
                )
                PreviewValue(
                    label = stringResource(R.string.ssh_key_provider_public_key),
                    value = preview.authorizedKey,
                    showFullValue = showFullPublicKey,
                )
                PreviewValue(
                    label = stringResource(R.string.ssh_key_provider_fingerprint),
                    value = preview.fingerprint,
                )
            } else {
                emptyContent?.invoke()
            }
        }
    }
}

@Composable
private fun PreviewValue(
    label: String,
    value: String,
    monospace: Boolean = true,
    showFullValue: Boolean = true,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        SelectionContainer {
            if (showFullValue) {
                Text(
                    value,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
                )
            } else {
                Text(
                    value,
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
                )
            }
        }
    }
}

private fun SshKeyAlgorithm.displayName(preview: SshKeyPreview): String = when (this) {
    SshKeyAlgorithm.SSH_ED25519 -> "Ed25519"
    SshKeyAlgorithm.SSH_RSA -> runCatching {
        (SshPublicKeyCodec.decode(preview.publicKeyBlob).publicKey as RSAPublicKey).modulus.bitLength()
    }.getOrNull()?.let { "RSA $it" } ?: "RSA"
    SshKeyAlgorithm.ECDSA_NISTP256 -> "ECDSA P-256"
    SshKeyAlgorithm.WEBAUTHN_SK_ECDSA_NISTP256 -> "WebAuthn ECDSA-SK P-256"
}

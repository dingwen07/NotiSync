package net.extrawdw.apps.notisync.sshagent

import java.util.Base64
import net.extrawdw.notisync.protocol.SshImportRequest
import net.extrawdw.notisync.protocol.SshImportSourceType
import net.extrawdw.notisync.protocol.SshKeyAlgorithm
import net.extrawdw.notisync.ssh.core.AgentAddIdentityParser
import net.extrawdw.notisync.ssh.core.SshFingerprint
import net.extrawdw.notisync.ssh.core.SshKeyType
import net.extrawdw.notisync.ssh.core.SshPublicKeyCodec

/** Public-only result of fully parsing and validating private SSH key input. */
data class SshKeyPreview(
    val algorithm: SshKeyAlgorithm,
    val publicKeyBlob: ByteArray,
    val authorizedKey: String,
    val fingerprint: String,
    val comment: String? = null,
)

data class InspectedSshPrivateKeyFile(
    val encrypted: Boolean,
    val preview: SshKeyPreview?,
    val comment: String? = null,
)

object SshImportPreviewParser {
    fun parse(request: SshImportRequest, passphrase: CharArray?): SshKeyPreview = when (request.sourceType) {
        SshImportSourceType.AGENT_IDENTITY -> {
            val parsed = AgentAddIdentityParser.parse(
                requireNotNull(request.agentIdentity),
                constrained = request.constraints != null,
            )
            require(parsed.constraints.lifetimeSeconds == request.constraints?.lifetimeSeconds) {
                "SSH import lifetime constraints do not match the parsed key"
            }
            require(parsed.constraints.confirm == (request.constraints?.confirmationRequired ?: false)) {
                "SSH import confirmation constraints do not match the parsed key"
            }
            preview(parsed.type.toProtocol(), parsed.publicKeyBlob).copy(
                comment = parsed.comment.trim().takeIf(String::isNotEmpty),
            )
        }
        SshImportSourceType.PRIVATE_KEY_FILE ->
            SshPrivateKeyFileParser.preview(requireNotNull(request.fileBytes), passphrase)
    }

    fun preview(algorithm: SshKeyAlgorithm, publicKeyBlob: ByteArray): SshKeyPreview {
        val blob = publicKeyBlob.copyOf()
        val decoded = SshPublicKeyCodec.decode(blob)
        require(decoded.type.toProtocol() == algorithm) { "SSH public-key algorithm does not match the request" }
        return SshKeyPreview(
            algorithm = algorithm,
            publicKeyBlob = blob,
            authorizedKey = "${decoded.wireName} ${Base64.getEncoder().encodeToString(blob)}",
            fingerprint = SshFingerprint.sha256(blob),
        )
    }

    fun preview(publicKeyBlob: ByteArray): SshKeyPreview {
        val type = SshPublicKeyCodec.decode(publicKeyBlob).type
        return preview(type.toProtocol(), publicKeyBlob)
    }

    private fun SshKeyType.toProtocol(): SshKeyAlgorithm = when (this) {
        SshKeyType.ED25519 -> SshKeyAlgorithm.SSH_ED25519
        SshKeyType.RSA -> SshKeyAlgorithm.SSH_RSA
        SshKeyType.ECDSA_NISTP256 -> SshKeyAlgorithm.ECDSA_NISTP256
    }
}

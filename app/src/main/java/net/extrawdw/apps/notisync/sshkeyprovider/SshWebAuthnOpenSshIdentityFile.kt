package net.extrawdw.apps.notisync.sshkeyprovider

import java.security.SecureRandom
import java.util.Base64
import net.extrawdw.notisync.protocol.SshAgentLimits
import net.extrawdw.notisync.ssh.core.SshKeyType
import net.extrawdw.notisync.ssh.core.SshPublicKeyCodec
import net.extrawdw.notisync.ssh.core.SshWireReader
import net.extrawdw.notisync.ssh.core.SshWireWriter

/** Encodes an authenticator-bound WebAuthn credential as an OpenSSH security-key identity file. */
object SshWebAuthnOpenSshIdentityFile {
    const val DEFAULT_FILE_NAME = "id_ecdsa_sk"

    fun encode(
        credential: RegisteredSshWebAuthnCredential,
        comment: String,
        random: SecureRandom = SecureRandom(),
    ): ByteArray = encode(
        credential = credential,
        comment = comment,
        checkValue = random.nextInt().toLong() and UINT32_MAX,
    )

    internal fun encode(
        credential: RegisteredSshWebAuthnCredential,
        comment: String,
        checkValue: Long,
    ): ByteArray {
        require(!credential.backupEligible && !credential.backupState) {
            "only authenticator-bound WebAuthn credentials can be exported as OpenSSH identities"
        }
        require(credential.rpId == SshWebAuthnCredential.RP_ID) { "unsupported WebAuthn RP ID" }
        require(credential.credentialId.isNotEmpty() && credential.credentialId.size <= MAX_CREDENTIAL_ID_BYTES) {
            "WebAuthn credential ID is outside the allowed bounds"
        }
        require(checkValue in 0..UINT32_MAX) { "OpenSSH identity check value is outside uint32" }
        val boundedComment = comment.trim()
        require(
            boundedComment.isNotEmpty() &&
                boundedComment.encodeToByteArray().size <= SshAgentLimits.MAX_DISPLAY_NAME_UTF8_BYTES,
        ) { "OpenSSH identity comment is outside the allowed bounds" }

        val decoded = SshPublicKeyCodec.decode(credential.publicKeyBlob)
        require(
            decoded.type == SshKeyType.WEBAUTHN_SK_ECDSA_NISTP256 &&
                decoded.application == credential.rpId,
        ) { "WebAuthn credential does not contain the expected OpenSSH ECDSA-SK public key" }

        val publicFields = SshWireReader(credential.publicKeyBlob)
        require(publicFields.readUtf8(MAX_KEY_TYPE_BYTES) == SshKeyType.WEBAUTHN_SK_ECDSA_NISTP256.wireName)
        val curve = publicFields.readUtf8(MAX_CURVE_NAME_BYTES)
        require(curve == ECDSA_CURVE) { "unsupported OpenSSH security-key curve" }
        val publicPoint = publicFields.readString(MAX_PUBLIC_POINT_BYTES)
        require(publicFields.readUtf8(MAX_APPLICATION_BYTES) == credential.rpId)
        publicFields.requireEnd()

        val privateFields = SshWireWriter(MAX_IDENTITY_BYTES)
            .writeUInt32(checkValue)
            .writeUInt32(checkValue)
            .writeUtf8(SshKeyType.WEBAUTHN_SK_ECDSA_NISTP256.wireName)
            .writeUtf8(curve)
            .writeString(publicPoint)
            .writeUtf8(credential.rpId)
            .writeByte(OPENSSH_SECURITY_KEY_FLAGS)
            .writeString(credential.credentialId)
            .writeString(ByteArray(0))
            .writeUtf8(boundedComment)
            .toByteArray()
        val paddingLength = (OPENSSH_NONE_BLOCK_SIZE - privateFields.size % OPENSSH_NONE_BLOCK_SIZE) %
            OPENSSH_NONE_BLOCK_SIZE
        val paddedPrivateFields = SshWireWriter(MAX_IDENTITY_BYTES)
            .writeRaw(privateFields)
            .also { writer -> repeat(paddingLength) { writer.writeByte(it + 1) } }
            .toByteArray()
        val binary = SshWireWriter(MAX_IDENTITY_BYTES)
            .writeRaw(OPENSSH_AUTH_MAGIC)
            .writeUtf8("none")
            .writeUtf8("none")
            .writeString(ByteArray(0))
            .writeUInt32(1)
            .writeString(credential.publicKeyBlob)
            .writeString(paddedPrivateFields)
            .toByteArray()
        val encoded = Base64.getMimeEncoder(OPENSSH_BASE64_LINE_LENGTH, byteArrayOf('\n'.code.toByte()))
            .encodeToString(binary)
        return buildString(encoded.length + 80) {
            append(OPENSSH_BEGIN)
            append('\n')
            append(encoded)
            append('\n')
            append(OPENSSH_END)
            append('\n')
        }.encodeToByteArray()
    }

    private const val MAX_CREDENTIAL_ID_BYTES = 1024
    private const val MAX_KEY_TYPE_BYTES = 128
    private const val MAX_CURVE_NAME_BYTES = 32
    private const val MAX_PUBLIC_POINT_BYTES = 65
    private const val MAX_APPLICATION_BYTES = 1024
    private const val MAX_IDENTITY_BYTES = 64 * 1024
    private const val OPENSSH_NONE_BLOCK_SIZE = 8
    private const val OPENSSH_BASE64_LINE_LENGTH = 70
    private const val UINT32_MAX = 0xffff_ffffL
    private const val ECDSA_CURVE = "nistp256"
    private const val SSH_SK_USER_PRESENCE_REQUIRED = 0x01
    private const val SSH_SK_USER_VERIFICATION_REQUIRED = 0x04
    private const val SSH_SK_RESIDENT_KEY = 0x20
    private const val OPENSSH_SECURITY_KEY_FLAGS =
        SSH_SK_USER_PRESENCE_REQUIRED or SSH_SK_USER_VERIFICATION_REQUIRED or SSH_SK_RESIDENT_KEY
    private const val OPENSSH_BEGIN = "-----BEGIN OPENSSH PRIVATE KEY-----"
    private const val OPENSSH_END = "-----END OPENSSH PRIVATE KEY-----"
    private val OPENSSH_AUTH_MAGIC = "openssh-key-v1\u0000".encodeToByteArray()
}

package net.extrawdw.notisync.ssh.core

import java.security.MessageDigest

data class SshUserAuthData(
    val sessionIdentifier: ByteArray,
    val username: String,
    val service: String,
    val method: String,
    val publicKeyAlgorithm: String,
    val publicKeyBlob: ByteArray,
    val serverHostKeyBlob: ByteArray?,
)

/** Conservative parser for the exact SSH user-authentication preimage passed to an agent. */
object SshUserAuthParser {
    private const val SSH_MSG_USERAUTH_REQUEST = 50
    private const val SSH_CONNECTION_SERVICE = "ssh-connection"
    const val PUBLIC_KEY_METHOD = "publickey"
    const val HOST_BOUND_METHOD = "publickey-hostbound-v00@openssh.com"

    fun parse(data: ByteArray): SshUserAuthData? = runCatching {
        if (data.isEmpty() || data.size > AgentMessageCodec.MAX_SIGN_DATA_SIZE) return null
        val reader = SshWireReader(data, AgentMessageCodec.MAX_SIGN_DATA_SIZE)
        val sessionIdentifier = reader.readString(16 * 1024)
        if (sessionIdentifier.isEmpty() || reader.readByte() != SSH_MSG_USERAUTH_REQUEST) return null
        val username = reader.readUtf8(1024)
        val service = reader.readUtf8(1024)
        if (service != SSH_CONNECTION_SERVICE) return null
        val method = reader.readUtf8(128)
        if (method != PUBLIC_KEY_METHOD && method != HOST_BOUND_METHOD) return null
        if (!reader.readBoolean()) return null
        val publicKeyAlgorithm = reader.readUtf8(128)
        val publicKeyBlob = reader.readString(SshPublicKeyCodec.MAXIMUM_PUBLIC_KEY_BLOB_SIZE)
        val decoded = SshPublicKeyCodec.decode(publicKeyBlob)
        val algorithmMatches = if (decoded.type == SshKeyType.WEBAUTHN_SK_ECDSA_NISTP256) {
            publicKeyAlgorithm == decoded.wireName
        } else {
            SshSignatureMethod.entries.firstOrNull { it.wireName == publicKeyAlgorithm }
                ?.let { SshSignatureVerifier.methodMatchesKey(it, decoded.type) } == true
        }
        if (!algorithmMatches) return null
        val serverHostKeyBlob = if (method == HOST_BOUND_METHOD) {
            reader.readString(SshPublicKeyCodec.MAXIMUM_PUBLIC_KEY_BLOB_SIZE).also(SshPublicKeyCodec::decode)
        } else null
        reader.requireEnd()
        SshUserAuthData(
            sessionIdentifier,
            username,
            service,
            method,
            publicKeyAlgorithm,
            publicKeyBlob,
            serverHostKeyBlob,
        )
    }.getOrNull()
}

data class VerifiedSessionBind(
    val hostKeyBlob: ByteArray,
    val hostKeyFingerprint: String,
    val sessionIdentifier: ByteArray,
    val forwarded: Boolean,
)

object OpenSshSessionBind {
    const val EXTENSION_NAME = "session-bind@openssh.com"
    const val QUERY_EXTENSION_NAME = "query"

    /** Parse and cryptographically verify one OpenSSH session binding extension payload. */
    fun parseAndVerify(contents: ByteArray): VerifiedSessionBind {
        val reader = SshWireReader(contents, AgentMessageCodec.MAX_SIGN_DATA_SIZE)
        val hostKeyBlob = reader.readString(SshPublicKeyCodec.MAXIMUM_PUBLIC_KEY_BLOB_SIZE)
        val sessionIdentifier = reader.readString(16 * 1024)
        val signatureBlob = reader.readString(16 * 1024)
        val forwarded = reader.readBoolean()
        reader.requireEnd()
        if (sessionIdentifier.isEmpty()) throw SshWireException("session binding identifier must not be empty")
        val decodedSignature = SshSignatureCodec.decode(signatureBlob)
        val verified = SshSignatureVerifier.verify(
            publicKeyBlob = hostKeyBlob,
            data = sessionIdentifier,
            signatureBlob = signatureBlob,
            expectedMethod = decodedSignature.method,
            allowLegacyRsaSha1 = true,
        )
        if (!verified) throw SshWireException("invalid OpenSSH session binding signature")
        return VerifiedSessionBind(
            hostKeyBlob.copyOf(),
            SshFingerprint.sha256(hostKeyBlob),
            sessionIdentifier.copyOf(),
            forwarded,
        )
    }

    fun encodeContents(
        hostKeyBlob: ByteArray,
        sessionIdentifier: ByteArray,
        signatureBlob: ByteArray,
        forwarded: Boolean,
    ): ByteArray = SshWireWriter(AgentMessageCodec.MAX_SIGN_DATA_SIZE)
        .writeString(hostKeyBlob)
        .writeString(sessionIdentifier)
        .writeString(signatureBlob)
        .writeBoolean(forwarded)
        .toByteArray()

    fun sessionIdSha256(binding: VerifiedSessionBind): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(binding.sessionIdentifier)
}

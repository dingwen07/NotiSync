package net.extrawdw.notisync.ssh.core

object AgentNumbers {
    const val SSH_AGENT_FAILURE = 5
    const val SSH_AGENT_SUCCESS = 6
    const val SSH_AGENTC_REQUEST_IDENTITIES = 11
    const val SSH_AGENT_IDENTITIES_ANSWER = 12
    const val SSH_AGENTC_SIGN_REQUEST = 13
    const val SSH_AGENT_SIGN_RESPONSE = 14
    const val SSH_AGENTC_ADD_IDENTITY = 17
    const val SSH_AGENTC_REMOVE_IDENTITY = 18
    const val SSH_AGENTC_REMOVE_ALL_IDENTITIES = 19
    const val SSH_AGENTC_ADD_SMARTCARD_KEY = 20
    const val SSH_AGENTC_REMOVE_SMARTCARD_KEY = 21
    const val SSH_AGENTC_LOCK = 22
    const val SSH_AGENTC_UNLOCK = 23
    const val SSH_AGENTC_ADD_ID_CONSTRAINED = 25
    const val SSH_AGENTC_ADD_SMARTCARD_KEY_CONSTRAINED = 26
    const val SSH_AGENTC_EXTENSION = 27
    const val SSH_AGENT_EXTENSION_FAILURE = 28
    const val SSH_AGENT_EXTENSION_RESPONSE = 29

    const val SSH_AGENT_CONSTRAIN_LIFETIME = 1
    const val SSH_AGENT_CONSTRAIN_CONFIRM = 2
    const val SSH_AGENT_CONSTRAIN_EXTENSION = 255

    const val SSH_AGENT_RSA_SHA2_256 = 2L
    const val SSH_AGENT_RSA_SHA2_512 = 4L
}

sealed interface AgentRequest {
    data object RequestIdentities : AgentRequest
    data class Sign(val publicKeyBlob: ByteArray, val data: ByteArray, val flags: Long) : AgentRequest
    data class AddIdentity(val identityPayload: ByteArray, val constrained: Boolean) : AgentRequest
    data class RemoveIdentity(val publicKeyBlob: ByteArray) : AgentRequest
    data object RemoveAllIdentities : AgentRequest
    data class Lock(val passphrase: ByteArray) : AgentRequest
    data class Unlock(val passphrase: ByteArray) : AgentRequest
    data class Extension(val name: String, val contents: ByteArray) : AgentRequest
    data class Unsupported(val type: Int, val payload: ByteArray) : AgentRequest
}

data class AgentIdentity(val publicKeyBlob: ByteArray, val comment: String)

object AgentMessageCodec {
    const val MAX_KEY_BLOB_SIZE = 16 * 1024
    const val MAX_SIGN_DATA_SIZE = 256 * 1024
    const val MAX_IDENTITY_PAYLOAD_SIZE = 256 * 1024
    const val MAX_LOCK_PASSPHRASE_SIZE = 16 * 1024
    const val MAX_EXTENSION_NAME_SIZE = 256

    fun decodeRequest(body: ByteArray): AgentRequest {
        if (body.isEmpty()) throw SshWireException("empty SSH agent message")
        val reader = SshWireReader(body, SshAgentFrameCodec.DEFAULT_MAXIMUM_FRAME_SIZE)
        val type = reader.readByte()
        return when (type) {
            AgentNumbers.SSH_AGENTC_REQUEST_IDENTITIES -> {
                reader.requireEnd()
                AgentRequest.RequestIdentities
            }
            AgentNumbers.SSH_AGENTC_SIGN_REQUEST -> AgentRequest.Sign(
                publicKeyBlob = reader.readString(MAX_KEY_BLOB_SIZE),
                data = reader.readString(MAX_SIGN_DATA_SIZE),
                flags = reader.readUInt32(),
            ).also { reader.requireEnd() }
            AgentNumbers.SSH_AGENTC_ADD_IDENTITY,
            AgentNumbers.SSH_AGENTC_ADD_ID_CONSTRAINED,
            -> AgentRequest.AddIdentity(
                identityPayload = reader.readRemaining().also {
                    if (it.isEmpty() || it.size > MAX_IDENTITY_PAYLOAD_SIZE) {
                        throw SshWireException("SSH agent identity payload is outside the allowed bounds")
                    }
                },
                constrained = type == AgentNumbers.SSH_AGENTC_ADD_ID_CONSTRAINED,
            )
            AgentNumbers.SSH_AGENTC_REMOVE_IDENTITY -> AgentRequest.RemoveIdentity(
                reader.readString(MAX_KEY_BLOB_SIZE),
            ).also { reader.requireEnd() }
            AgentNumbers.SSH_AGENTC_REMOVE_ALL_IDENTITIES -> {
                reader.requireEnd()
                AgentRequest.RemoveAllIdentities
            }
            AgentNumbers.SSH_AGENTC_LOCK -> AgentRequest.Lock(
                reader.readString(MAX_LOCK_PASSPHRASE_SIZE),
            ).also { reader.requireEnd() }
            AgentNumbers.SSH_AGENTC_UNLOCK -> AgentRequest.Unlock(
                reader.readString(MAX_LOCK_PASSPHRASE_SIZE),
            ).also { reader.requireEnd() }
            AgentNumbers.SSH_AGENTC_EXTENSION -> AgentRequest.Extension(
                name = reader.readUtf8(MAX_EXTENSION_NAME_SIZE),
                contents = reader.readRemaining(),
            )
            else -> AgentRequest.Unsupported(type, reader.readRemaining())
        }
    }

    fun success(): ByteArray = byteArrayOf(AgentNumbers.SSH_AGENT_SUCCESS.toByte())
    fun failure(): ByteArray = byteArrayOf(AgentNumbers.SSH_AGENT_FAILURE.toByte())
    fun extensionFailure(): ByteArray = byteArrayOf(AgentNumbers.SSH_AGENT_EXTENSION_FAILURE.toByte())

    fun identitiesAnswer(identities: List<AgentIdentity>): ByteArray {
        val writer = SshWireWriter()
            .writeByte(AgentNumbers.SSH_AGENT_IDENTITIES_ANSWER)
            .writeUInt32(identities.size.toLong())
        identities.forEach {
            if (it.publicKeyBlob.isEmpty() || it.publicKeyBlob.size > MAX_KEY_BLOB_SIZE) {
                throw SshWireException("identity public key is outside the allowed bounds")
            }
            writer.writeString(it.publicKeyBlob).writeUtf8(it.comment)
        }
        return writer.toByteArray()
    }

    fun signResponse(signatureBlob: ByteArray): ByteArray {
        if (signatureBlob.isEmpty() || signatureBlob.size > MAX_KEY_BLOB_SIZE) {
            throw SshWireException("signature blob is outside the allowed bounds")
        }
        return SshWireWriter().writeByte(AgentNumbers.SSH_AGENT_SIGN_RESPONSE).writeString(signatureBlob).toByteArray()
    }

    /** RFC 9987 `query` response: response type, `query`, then zero or more extension-name strings. */
    fun extensionQueryResponse(extensionNames: List<String>): ByteArray {
        val writer = SshWireWriter()
            .writeByte(AgentNumbers.SSH_AGENT_EXTENSION_RESPONSE)
            .writeUtf8("query")
        extensionNames.forEach { writer.writeUtf8(it) }
        return writer.toByteArray()
    }
}

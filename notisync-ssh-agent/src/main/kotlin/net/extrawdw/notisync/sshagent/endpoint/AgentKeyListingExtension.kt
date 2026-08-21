package net.extrawdw.notisync.sshagent.endpoint

import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.ssh.core.AgentNumbers
import net.extrawdw.notisync.ssh.core.SshWireException
import net.extrawdw.notisync.ssh.core.SshWireReader
import net.extrawdw.notisync.ssh.core.SshWireWriter
import net.extrawdw.notisync.sshagent.cache.CachedProviderKeyRow

internal object AgentKeyListingExtension {
    const val NAME = "keys@notisync.extrawdw.net"

    fun request(): ByteArray = SshWireWriter()
        .writeByte(AgentNumbers.SSH_AGENTC_EXTENSION)
        .writeUtf8(NAME)
        .toByteArray()

    fun response(rows: List<CachedProviderKeyRow>): ByteArray = SshWireWriter()
        .writeByte(AgentNumbers.SSH_AGENT_EXTENSION_RESPONSE)
        .writeRaw(ProtocolCodec.encodeToCbor(rows))
        .toByteArray()

    fun decodeResponse(response: ByteArray): List<CachedProviderKeyRow> {
        val reader = SshWireReader(response)
        return when (val type = reader.readByte()) {
            AgentNumbers.SSH_AGENT_EXTENSION_RESPONSE ->
                ProtocolCodec.decodeFromCbor(reader.readRemaining())
            AgentNumbers.SSH_AGENT_EXTENSION_FAILURE, AgentNumbers.SSH_AGENT_FAILURE ->
                throw SshWireException("running SSH Agent does not support key-row listing")
            else -> throw SshWireException("unexpected SSH Agent key-row response type $type")
        }
    }
}

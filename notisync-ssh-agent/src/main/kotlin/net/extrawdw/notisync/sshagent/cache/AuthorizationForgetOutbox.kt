package net.extrawdw.notisync.sshagent.cache

import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.SshForgetAuthorization

class AuthorizationForgetOutbox(private val database: AgentDatabase) {
    fun enqueue(forget: SshForgetAuthorization) = database.transaction { connection ->
        connection.prepareStatement(
            "INSERT OR IGNORE INTO authorization_forget_outbox(request_id, request_cbor, created_at) VALUES(?,?,?)",
        ).use { statement ->
            statement.setString(1, forget.requestId)
            statement.setBytes(2, ProtocolCodec.encodeToCbor(forget))
            statement.setLong(3, forget.requestedAt)
            statement.executeUpdate()
        }
    }

    fun pending(): List<SshForgetAuthorization> = database.read { connection ->
        connection.prepareStatement(
            "SELECT request_cbor FROM authorization_forget_outbox ORDER BY created_at, request_id",
        ).use { statement ->
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        runCatching {
                            ProtocolCodec.decodeFromCbor<SshForgetAuthorization>(result.getBytes(1))
                        }.getOrNull()?.let(::add)
                    }
                }
            }
        }
    }

    fun markAccepted(requestId: String) = database.transaction { connection ->
        connection.prepareStatement("DELETE FROM authorization_forget_outbox WHERE request_id=?").use {
            it.setString(1, requestId)
            it.executeUpdate()
        }
    }
}

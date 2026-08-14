package net.extrawdw.notisync.sshagent.cache

import java.security.SecureRandom

data class AuthorizationNamespace(val generation: String, val epoch: Long)

class AgentMetadataStore(private val database: AgentDatabase) {
    fun authorizationNamespace(): AuthorizationNamespace = database.transaction { connection ->
        val values = mutableMapOf<String, String>()
        connection.prepareStatement(
            "SELECT key, value FROM agent_metadata WHERE key IN (?,?)",
        ).use { statement ->
            statement.setString(1, AUTHORIZATION_GENERATION)
            statement.setString(2, AUTHORIZATION_EPOCH)
            statement.executeQuery().use { result ->
                while (result.next()) values[result.getString(1)] = result.getString(2)
            }
        }
        val generation = values[AUTHORIZATION_GENERATION]?.takeIf(OPERATION_ID::matches) ?: randomId()
        val epoch = values[AUTHORIZATION_EPOCH]?.toLongOrNull()?.takeIf { it >= 0 } ?: 0L
        put(connection, AUTHORIZATION_GENERATION, generation)
        put(connection, AUTHORIZATION_EPOCH, epoch.toString())
        AuthorizationNamespace(generation, epoch)
    }

    /** Persistently advances the namespace before any post-lock request can be accepted. */
    fun advanceAuthorizationEpoch(): Pair<AuthorizationNamespace, AuthorizationNamespace> =
        database.transaction { connection ->
            val old = authorizationNamespaceWithin(connection)
            val next = if (old.epoch == Long.MAX_VALUE) AuthorizationNamespace(randomId(), 0) else old.copy(epoch = old.epoch + 1)
            put(connection, AUTHORIZATION_GENERATION, next.generation)
            put(connection, AUTHORIZATION_EPOCH, next.epoch.toString())
            old to next
        }

    private fun authorizationNamespaceWithin(connection: java.sql.Connection): AuthorizationNamespace {
        val values = mutableMapOf<String, String>()
        connection.prepareStatement(
            "SELECT key, value FROM agent_metadata WHERE key IN (?,?)",
        ).use { statement ->
            statement.setString(1, AUTHORIZATION_GENERATION)
            statement.setString(2, AUTHORIZATION_EPOCH)
            statement.executeQuery().use { result ->
                while (result.next()) values[result.getString(1)] = result.getString(2)
            }
        }
        return AuthorizationNamespace(
            values[AUTHORIZATION_GENERATION]?.takeIf(OPERATION_ID::matches) ?: randomId(),
            values[AUTHORIZATION_EPOCH]?.toLongOrNull()?.takeIf { it >= 0 } ?: 0,
        )
    }

    private fun put(connection: java.sql.Connection, key: String, value: String) {
        connection.prepareStatement("INSERT OR REPLACE INTO agent_metadata(key, value) VALUES(?,?)").use {
            it.setString(1, key)
            it.setString(2, value)
            it.executeUpdate()
        }
    }

    private fun randomId(): String = ByteArray(16).also(RANDOM::nextBytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val AUTHORIZATION_GENERATION = "authorization_generation"
        const val AUTHORIZATION_EPOCH = "authorization_epoch"
        val OPERATION_ID = Regex("[0-9a-f]{32}")
        val RANDOM = SecureRandom()
    }
}

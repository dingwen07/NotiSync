package net.extrawdw.apps.notisync.data.storage.core

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.notisync.crypto.KeyVault
import net.extrawdw.notisync.peer.transport.AuthTokenStore
import net.extrawdw.notisync.protocol.IntegrityVerificationResponse
import net.extrawdw.notisync.protocol.ProtocolCodec

/** Broker-token cache backed only by Core Room; the existing wrapping key supplies at-rest encryption. */
internal class RoomCoreAuthTokenStore(
    private val repository: CoreFoundationRepository,
    private val vault: KeyVault,
) : AuthTokenStore {
    @Synchronized
    override fun load(): IntegrityVerificationResponse? = runBlocking {
        val stored = repository.brokerAuthToken.first() ?: return@runBlocking null
        if (stored.encodingVersion != ENCODING_VERSION) return@runBlocking null
        runCatching {
            ProtocolCodec.decodeFromJson<IntegrityVerificationResponse>(
                vault.unwrap(stored.wrappedToken).decodeToString(),
            )
        }.getOrNull()
    }

    @Synchronized
    override fun save(token: IntegrityVerificationResponse?) = runBlocking {
        if (token == null) {
            repository.clearBrokerAuthToken()
            return@runBlocking
        }
        val transport = requireNotNull(repository.transport.first()) { "Core transport authority is missing" }
        val result = repository.saveBrokerAuthToken(
            BrokerAuthTokenInput(
                wrappedToken = vault.wrap(ProtocolCodec.encodeToJson(token).encodeToByteArray()),
                encodingVersion = ENCODING_VERSION,
                expiresAt = token.expiresAt,
                expectedBrokerEndpointRevision = transport.brokerEndpointRevision,
            ),
        )
        check(result == BrokerAuthTokenSaveResult.SAVED) { "Broker endpoint changed while saving its token" }
    }

    private companion object {
        const val ENCODING_VERSION = 1
    }
}

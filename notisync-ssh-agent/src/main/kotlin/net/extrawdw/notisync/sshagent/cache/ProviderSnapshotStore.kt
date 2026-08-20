package net.extrawdw.notisync.sshagent.cache

import java.security.MessageDigest
import java.util.Base64
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.SshApprovalPolicy
import net.extrawdw.notisync.protocol.SshKeyDescriptor
import net.extrawdw.notisync.protocol.SshKeysSnapshot
import net.extrawdw.notisync.protocol.SshUserVerificationPolicy
import net.extrawdw.notisync.ssh.core.SshFingerprint

enum class SnapshotApplyResult { APPLIED, IDEMPOTENT, STALE, RETIRED_GENERATION, CONFLICT }

data class ProviderCandidate(
    val providerClientId: ClientId,
    val descriptor: SshKeyDescriptor,
)

data class AggregateIdentity(
    val publicKeyBlob: ByteArray,
    val publicKeyBlobSha256: ByteArray,
    val fingerprint: String,
    val comment: String,
    val candidates: List<ProviderCandidate>,
    val remembered: Boolean,
    val canRemember: Boolean,
)

class ProviderSnapshotStore(private val database: AgentDatabase) {
    fun apply(
        authenticatedProvider: ClientId,
        snapshot: SshKeysSnapshot,
        receivedAt: Long,
    ): SnapshotApplyResult {
        require(snapshot.providerClientId == authenticatedProvider) {
            "snapshot provider must equal its authenticated signer"
        }
        require(snapshot.validationError(::sha256) == null) {
            snapshot.validationError(::sha256) ?: "invalid provider snapshot"
        }
        val canonicalHash = sha256(ProtocolCodec.encodeToCbor(snapshot))
        return database.transaction { connection ->
            connection.prepareStatement(
                "SELECT inventory_generation, revision, canonical_hash FROM provider_snapshots WHERE provider_id = ?",
            ).use { statement ->
                statement.setString(1, authenticatedProvider.value)
                statement.executeQuery().use { result ->
                    if (result.next()) {
                        val generation = result.getString(1)
                        val revision = result.getLong(2)
                        val hash = result.getBytes(3)
                        if (generation == snapshot.inventoryGeneration) {
                            if (snapshot.revision < revision) return@transaction SnapshotApplyResult.STALE
                            if (snapshot.revision == revision) {
                                return@transaction if (hash.contentEquals(canonicalHash)) {
                                    SnapshotApplyResult.IDEMPOTENT
                                } else SnapshotApplyResult.CONFLICT
                            }
                        } else {
                            if (isRetired(connection, authenticatedProvider, snapshot.inventoryGeneration)) {
                                return@transaction SnapshotApplyResult.RETIRED_GENERATION
                            }
                            connection.prepareStatement(
                                "INSERT OR IGNORE INTO retired_inventory_generations(provider_id, generation, retired_at) VALUES(?,?,?)",
                            ).use { retired ->
                                retired.setString(1, authenticatedProvider.value)
                                retired.setString(2, generation)
                                retired.setLong(3, receivedAt)
                                retired.executeUpdate()
                            }
                        }
                    }
                }
            }

            connection.prepareStatement(
                """
                INSERT INTO provider_snapshots(
                    provider_id, inventory_generation, revision, generated_at, received_at, canonical_hash, provider_health
                ) VALUES(?,?,?,?,?,?,?)
                ON CONFLICT(provider_id) DO UPDATE SET
                    inventory_generation=excluded.inventory_generation,
                    revision=excluded.revision,
                    generated_at=excluded.generated_at,
                    received_at=excluded.received_at,
                    canonical_hash=excluded.canonical_hash,
                    provider_health=excluded.provider_health
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, authenticatedProvider.value)
                statement.setString(2, snapshot.inventoryGeneration)
                statement.setLong(3, snapshot.revision)
                statement.setLong(4, snapshot.generatedAt)
                statement.setLong(5, receivedAt)
                statement.setBytes(6, canonicalHash)
                statement.setString(7, snapshot.providerHealth.name)
                statement.executeUpdate()
            }
            connection.prepareStatement("DELETE FROM provider_keys WHERE provider_id = ?").use { statement ->
                statement.setString(1, authenticatedProvider.value)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO provider_keys(provider_id, provider_key_id, public_blob_hash, public_blob, descriptor_cbor) VALUES(?,?,?,?,?)",
            ).use { statement ->
                snapshot.keys.forEach { key ->
                    statement.setString(1, authenticatedProvider.value)
                    statement.setString(2, key.providerKeyId)
                    statement.setBytes(3, key.publicKeyBlobSha256)
                    statement.setBytes(4, key.publicKeyBlob)
                    statement.setBytes(5, ProtocolCodec.encodeToCbor(key))
                    statement.addBatch()
                }
                statement.executeBatch()
            }
            SnapshotApplyResult.APPLIED
        }
    }

    fun aggregate(
        activeProviderIds: Set<ClientId>,
        requesterClientId: ClientId,
        authorizationGeneration: String,
        authorizationEpoch: Long,
        now: Long,
    ): List<AggregateIdentity> = database.read { connection ->
        if (activeProviderIds.isEmpty()) return@read emptyList()
        val hidden = hiddenHashes(connection, now)
        val candidates = mutableListOf<ProviderCandidate>()
        connection.prepareStatement("SELECT provider_id, descriptor_cbor FROM provider_keys").use { statement ->
            statement.executeQuery().use { result ->
                while (result.next()) {
                    val provider = ClientId(result.getString(1))
                    if (provider !in activeProviderIds) continue
                    val descriptor = runCatching {
                        ProtocolCodec.decodeFromCbor<SshKeyDescriptor>(result.getBytes(2))
                    }.getOrNull() ?: continue
                    if (descriptor.publicKeyBlobSha256.toHex() in hidden) continue
                    candidates += ProviderCandidate(provider, descriptor)
                }
            }
        }
        candidates.groupBy { Base64.getEncoder().encodeToString(it.descriptor.publicKeyBlob) }
            .map { (_, grouped) ->
                val stable = grouped.sortedWith(compareBy({ it.providerClientId.value }, { it.descriptor.providerKeyId }))
                val key = stable.first().descriptor
                val remembered = stable.any { candidate ->
                    candidate.descriptor.rememberedNamespaces.any { namespace ->
                        namespace.requesterClientId == requesterClientId &&
                            namespace.authorizationGeneration == authorizationGeneration &&
                            namespace.authorizationEpoch == authorizationEpoch &&
                            namespace.scopes.isNotEmpty()
                    }
                }
                val canRemember = stable.any {
                    it.descriptor.approvalPolicy == SshApprovalPolicy.ALLOW_REMEMBER &&
                        it.descriptor.operationalKey.userVerificationPolicy == SshUserVerificationPolicy.NONE
                }
                AggregateIdentity(
                    key.publicKeyBlob.copyOf(),
                    key.publicKeyBlobSha256.copyOf(),
                    SshFingerprint.sha256(key.publicKeyBlob),
                    stable.first().descriptor.displayName,
                    stable,
                    remembered,
                    canRemember,
                )
            }
            .sortedWith(
                compareByDescending<AggregateIdentity> { it.remembered }
                    .thenByDescending { it.canRemember }
                    .thenBy { it.comment.lowercase() }
                    .thenBy { it.fingerprint },
            )
    }

    fun hide(publicKeyBlob: ByteArray, reason: String, hiddenAt: Long, expiresAt: Long? = null) {
        val hash = sha256(publicKeyBlob)
        database.transaction { connection ->
            connection.prepareStatement(
                "INSERT OR REPLACE INTO local_visibility(public_blob_hash, hidden_at, reason, expires_at) VALUES(?,?,?,?)",
            ).use { statement ->
                statement.setBytes(1, hash)
                statement.setLong(2, hiddenAt)
                statement.setString(3, reason)
                if (expiresAt == null) statement.setNull(4, java.sql.Types.BIGINT) else statement.setLong(4, expiresAt)
                statement.executeUpdate()
            }
        }
    }

    private fun hiddenHashes(connection: java.sql.Connection, now: Long): Set<String> = buildSet {
        connection.prepareStatement(
            "SELECT public_blob_hash FROM local_visibility WHERE expires_at IS NULL OR expires_at > ?",
        ).use { statement ->
            statement.setLong(1, now)
            statement.executeQuery().use { result -> while (result.next()) add(result.getBytes(1).toHex()) }
        }
    }

    private fun isRetired(connection: java.sql.Connection, provider: ClientId, generation: String): Boolean =
        connection.prepareStatement(
            "SELECT 1 FROM retired_inventory_generations WHERE provider_id = ? AND generation = ?",
        ).use { statement ->
            statement.setString(1, provider.value)
            statement.setString(2, generation)
            statement.executeQuery().use { it.next() }
        }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

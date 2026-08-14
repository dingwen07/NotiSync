package net.extrawdw.notisync.sshagent.cache

import java.nio.file.Files
import java.security.MessageDigest
import java.security.KeyPairGenerator
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.SshApprovalPolicy
import net.extrawdw.notisync.protocol.SshExportability
import net.extrawdw.notisync.protocol.SshKeyAlgorithm
import net.extrawdw.notisync.protocol.SshKeyDescriptor
import net.extrawdw.notisync.protocol.SshKeyOrigin
import net.extrawdw.notisync.protocol.SshKeysSnapshot
import net.extrawdw.notisync.protocol.SshProviderHealth
import net.extrawdw.notisync.protocol.SshStorageBackend
import net.extrawdw.notisync.protocol.SshStorageSecurityLevel
import net.extrawdw.notisync.protocol.SshUserVerificationPolicy
import net.extrawdw.notisync.ssh.core.SshPublicKeyCodec
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderSnapshotStoreTest {
    @Test
    fun aggregatesDuplicatePublicBlobsAndReplacesFullSnapshots() {
        withDatabase { database ->
            val store = ProviderSnapshotStore(database)
            val first = ClientId("b".repeat(52))
            val second = ClientId("c".repeat(52))
            val blob = SshPublicKeyCodec.encode(KeyPairGenerator.getInstance("Ed25519").generateKeyPair().public)
            assertEquals(SnapshotApplyResult.APPLIED, store.apply(first, snapshot(first, "1".repeat(32), 1, key("2".repeat(32), blob, "First")), 2_000))
            assertEquals(SnapshotApplyResult.APPLIED, store.apply(second, snapshot(second, "3".repeat(32), 1, key("4".repeat(32), blob, "Second")), 2_000))

            val aggregate = store.aggregate(setOf(first, second), ClientId("a".repeat(52)), "5".repeat(32), 0, 3_000)
            assertEquals(1, aggregate.size)
            assertEquals(2, aggregate.single().candidates.size)
            assertEquals("First", aggregate.single().comment)

            assertEquals(SnapshotApplyResult.APPLIED, store.apply(first, snapshot(first, "1".repeat(32), 2), 4_000))
            assertEquals(1, store.aggregate(setOf(first, second), ClientId("a".repeat(52)), "5".repeat(32), 0, 5_000).single().candidates.size)
        }
    }

    @Test
    fun rejectsConflictsStaleRevisionsAndRetiredGenerations() {
        withDatabase { database ->
            val store = ProviderSnapshotStore(database)
            val provider = ClientId("b".repeat(52))
            val first = snapshot(provider, "1".repeat(32), 2)
            assertEquals(SnapshotApplyResult.APPLIED, store.apply(provider, first, 1_000))
            assertEquals(SnapshotApplyResult.IDEMPOTENT, store.apply(provider, first, 1_001))
            assertEquals(SnapshotApplyResult.STALE, store.apply(provider, first.copy(revision = 1), 1_002))
            assertEquals(
                SnapshotApplyResult.CONFLICT,
                store.apply(provider, first.copy(keys = listOf(key("2".repeat(32), byteArrayOf(9), "Conflict"))), 1_003),
            )
            assertEquals(SnapshotApplyResult.APPLIED, store.apply(provider, snapshot(provider, "3".repeat(32), 1), 1_004))
            assertEquals(SnapshotApplyResult.RETIRED_GENERATION, store.apply(provider, first.copy(revision = 3), 1_005))
        }
    }

    private fun snapshot(
        provider: ClientId,
        generation: String,
        revision: Long,
        vararg keys: SshKeyDescriptor,
    ) = SshKeysSnapshot(provider, generation, revision, 1_000, keys = keys.toList(), providerHealth = SshProviderHealth.HEALTHY)

    private fun key(id: String, blob: ByteArray, name: String) = SshKeyDescriptor(
        id,
        blob,
        MessageDigest.getInstance("SHA-256").digest(blob),
        SshKeyAlgorithm.SSH_ED25519,
        name,
        SshKeyOrigin.GENERATED,
        SshExportability.NON_EXPORTABLE,
        SshStorageBackend.ANDROID_KEYSTORE,
        SshStorageSecurityLevel.TRUSTED_ENVIRONMENT,
        SshApprovalPolicy.ALWAYS_ASK,
        SshUserVerificationPolicy.NONE,
        createdAt = 1_000,
    )

    private fun withDatabase(block: (AgentDatabase) -> Unit) {
        val directory = Files.createTempDirectory("notisync-ssh-agent-test-")
        try {
            AgentDatabase(directory.resolve("agent.sqlite3")).use(block)
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}

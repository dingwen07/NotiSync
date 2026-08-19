package net.extrawdw.notisync.sshagent.cache

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.KeyPairGenerator
import java.sql.DriverManager
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.SshApprovalPolicy
import net.extrawdw.notisync.protocol.SshKeyAlgorithm
import net.extrawdw.notisync.protocol.SshKeyDescriptor
import net.extrawdw.notisync.protocol.SshKeyOrigin
import net.extrawdw.notisync.protocol.SshKeysSnapshot
import net.extrawdw.notisync.protocol.SshOperationalKeyProtection
import net.extrawdw.notisync.protocol.SshOperationalKeyProvider
import net.extrawdw.notisync.protocol.SshProviderHealth
import net.extrawdw.notisync.protocol.SshRememberedNamespace
import net.extrawdw.notisync.protocol.SshRememberScope
import net.extrawdw.notisync.protocol.SshStorageSecurityLevel
import net.extrawdw.notisync.protocol.SshUserVerificationPolicy
import net.extrawdw.notisync.ssh.core.SshPublicKeyCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderSnapshotStoreTest {
    @Test
    fun newDatabaseUsesReleaseSchemaOne() {
        withDatabase { database ->
            val version = database.read { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA user_version").use { result ->
                        assertTrue(result.next())
                        result.getInt(1)
                    }
                }
            }
            assertEquals(1, version)
        }
    }

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

    @Test
    fun hostScopedNamespaceMarksTheMatchingRequesterEpochAsRemembered() {
        withDatabase { database ->
            val store = ProviderSnapshotStore(database)
            val provider = ClientId("b".repeat(52))
            val requester = ClientId("a".repeat(52))
            val generation = "5".repeat(32)
            val blob = SshPublicKeyCodec.encode(KeyPairGenerator.getInstance("Ed25519").generateKeyPair().public)
            val remembered = SshRememberedNamespace(
                requesterClientId = requester,
                authorizationGeneration = generation,
                authorizationEpoch = 7,
                scopes = listOf(SshRememberScope.PEER_HOST_KEY),
            )
            store.apply(
                provider,
                snapshot(
                    provider,
                    "1".repeat(32),
                    1,
                    key(
                        "2".repeat(32),
                        blob,
                        "Host-scoped",
                        approvalPolicy = SshApprovalPolicy.ALLOW_REMEMBER,
                        rememberedNamespaces = listOf(remembered),
                    ),
                ),
                2_000,
            )

            assertTrue(store.aggregate(setOf(provider), requester, generation, 7, 3_000).single().remembered)
            assertTrue(!store.aggregate(setOf(provider), requester, generation, 8, 3_000).single().remembered)
        }
    }

    @Test
    fun incompatibleDatabaseFailsClosedWithoutDeletingOrRewritingIt() {
        withTemporaryDatabasePath { path ->
            DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE TABLE retained_marker(value TEXT NOT NULL)")
                    statement.execute("INSERT INTO retained_marker(value) VALUES('keep')")
                    statement.execute("PRAGMA user_version=2")
                }
            }

            val failure = assertThrows(IllegalStateException::class.java) { AgentDatabase(path) }

            assertTrue(failure.message.orEmpty().contains("database was not modified"))
            DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA user_version").use { result ->
                        assertTrue(result.next())
                        assertEquals(2, result.getInt(1))
                    }
                    statement.executeQuery("SELECT value FROM retained_marker").use { result ->
                        assertTrue(result.next())
                        assertEquals("keep", result.getString(1))
                    }
                }
            }
        }
    }

    private fun snapshot(
        provider: ClientId,
        generation: String,
        revision: Long,
        vararg keys: SshKeyDescriptor,
    ) = SshKeysSnapshot(provider, generation, revision, 1_000, keys = keys.toList(), providerHealth = SshProviderHealth.HEALTHY)

    private fun key(
        id: String,
        blob: ByteArray,
        name: String,
        approvalPolicy: SshApprovalPolicy = SshApprovalPolicy.ALWAYS_ASK,
        rememberedNamespaces: List<SshRememberedNamespace> = emptyList(),
    ) = SshKeyDescriptor(
        id,
        blob,
        MessageDigest.getInstance("SHA-256").digest(blob),
        SshKeyAlgorithm.SSH_ED25519,
        name,
        SshKeyOrigin.GENERATED,
        SshOperationalKeyProtection(
            SshOperationalKeyProvider.ANDROID_KEYSTORE_PRIVATE_KEY,
            SshStorageSecurityLevel.TRUSTED_ENVIRONMENT,
            SshUserVerificationPolicy.NONE,
            strongBoxAttempted = false,
            strongBoxFallback = false,
        ),
        null,
        approvalPolicy,
        rememberedNamespaces,
        createdAt = 1_000,
    )

    private fun withDatabase(block: (AgentDatabase) -> Unit) {
        withTemporaryDatabasePath { path -> AgentDatabase(path).use(block) }
    }

    private fun withTemporaryDatabasePath(block: (Path) -> Unit) {
        // macOS exposes /var as a symlink to /private/var. These tests deliberately exercise a database
        // implementation that rejects symlink path components, so keep their temporary files under the build.
        val testRoot = Path.of("build", "tmp", "provider-snapshot-store-test").toAbsolutePath()
        Files.createDirectories(testRoot)
        val directory = Files.createTempDirectory(testRoot, "notisync-ssh-agent-test-")
        try {
            block(directory.resolve("agent.sqlite3"))
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}

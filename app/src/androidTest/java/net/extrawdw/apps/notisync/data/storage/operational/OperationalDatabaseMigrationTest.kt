package net.extrawdw.apps.notisync.data.storage.operational

import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class OperationalDatabaseMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val databaseFile: File = context.getDatabasePath(DATABASE_NAME)

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        instrumentation = instrumentation,
        file = databaseFile,
        driver = AndroidSQLiteDriver(),
        databaseClass = OperationalDatabase::class,
    )

    @After
    fun deleteDatabase() {
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun migrationOneToTwoPreservesExistingSshKeysAndAddsWebAuthnCredentials() = runBlocking {
        context.deleteDatabase(DATABASE_NAME)
        migrationHelper.createDatabase(1).use { connection ->
            connection.execSQL(
                """
                INSERT INTO ssh_keys(
                    provider_key_id, public_blob, public_hash, algorithm, display_name,
                    origin, approval_policy, created_at, expires_at
                ) VALUES (
                    'existing-key', X'0102', X'0304', 'SSH_ED25519', 'Existing key',
                    'GENERATED', 'ALLOW_REMEMBER', 123, NULL
                )
                """.trimIndent(),
            )
            connection.execSQL(
                """
                INSERT INTO ssh_operational_keys(
                    provider_key_id, provider_kind, key_alias, ciphertext, nonce,
                    security_level, user_verification_policy, strongbox_attempted,
                    strongbox_fallback
                ) VALUES (
                    'existing-key', 'ANDROID_KEYSTORE', 'existing-alias', NULL, NULL,
                    'TEE', 'PER_SESSION', 0, 0
                )
                """.trimIndent(),
            )
        }

        migrationHelper.runMigrationsAndValidate(
            version = 2,
            migrations = listOf(OperationalDatabase.MIGRATION_1_2),
        ).use { connection ->
            connection.prepare(
                """
                SELECT k.display_name, o.key_alias
                FROM ssh_keys k
                JOIN ssh_operational_keys o USING(provider_key_id)
                WHERE k.provider_key_id = 'existing-key'
                """.trimIndent(),
            ).use { statement ->
                assertTrue(statement.step())
                assertEquals("Existing key", statement.getText(0))
                assertEquals("existing-alias", statement.getText(1))
                assertFalse(statement.step())
            }
            connection.prepare(
                "SELECT COUNT(*) FROM sqlite_master " +
                    "WHERE type='table' AND name='ssh_webauthn_credentials'",
            ).use { statement ->
                assertTrue(statement.step())
                assertEquals(1L, statement.getLong(0))
            }
        }
    }

    private companion object {
        const val DATABASE_NAME = "operational-migration-test.db"
    }
}

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

    @Test
    fun migrationTwoToThreePreservesWebAuthnCredentialAndRemovesCreatedOrigin() = runBlocking {
        context.deleteDatabase(DATABASE_NAME)
        migrationHelper.createDatabase(2).use { connection ->
            connection.execSQL(
                """
                INSERT INTO ssh_keys(
                    provider_key_id, public_blob, public_hash, algorithm, display_name,
                    origin, approval_policy, created_at, expires_at
                ) VALUES (
                    'webauthn-key', X'0102', X'0304', 'WEBAUTHN_SK_ECDSA_NISTP256', 'WebAuthn key',
                    'WEBAUTHN_CREATED', 'ALWAYS_ASK', 123, NULL
                )
                """.trimIndent(),
            )
            connection.execSQL(
                """
                INSERT INTO ssh_webauthn_credentials(
                    provider_key_id, credential_id, user_handle, rp_id, cose_public_key,
                    created_origin, backup_eligible, backup_state
                ) VALUES (
                    'webauthn-key', X'0506', X'0708', 'notisync.apps.extrawdw.net', X'090A',
                    'android:apk-key-hash:old-signer', 1, 1
                )
                """.trimIndent(),
            )
        }

        migrationHelper.runMigrationsAndValidate(
            version = 3,
            migrations = listOf(OperationalDatabase.MIGRATION_2_3),
        ).use { connection ->
            connection.prepare(
                "SELECT credential_id, backup_eligible, backup_state " +
                    "FROM ssh_webauthn_credentials WHERE provider_key_id='webauthn-key'",
            ).use { statement ->
                assertTrue(statement.step())
                assertTrue(statement.getBlob(0).contentEquals(byteArrayOf(5, 6)))
                assertEquals(1L, statement.getLong(1))
                assertEquals(1L, statement.getLong(2))
                assertFalse(statement.step())
            }
            val columns = connection.prepare("PRAGMA table_info(ssh_webauthn_credentials)").use { statement ->
                buildSet {
                    while (statement.step()) add(statement.getText(1))
                }
            }
            assertFalse("created_origin" in columns)
            assertTrue("cose_public_key" in columns)
        }
    }

    private companion object {
        const val DATABASE_NAME = "operational-migration-test.db"
    }
}

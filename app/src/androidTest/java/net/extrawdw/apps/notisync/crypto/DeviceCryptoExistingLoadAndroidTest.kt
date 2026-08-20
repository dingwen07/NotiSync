package net.extrawdw.apps.notisync.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.extrawdw.notisync.protocol.crypto.SoftwareIdentitySigner
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceCryptoExistingLoadAndroidTest {
    @Test
    fun strictExistingLoadsDoNotCreateMissingAliases() = runBlocking {
        val suffix = UUID.randomUUID().toString()
        val identityAlias = "notisync.test.identity.$suffix"
        val operationalAlias = "notisync.test.operational.$suffix"
        val wrappingAlias = "notisync.test.wrapping.$suffix"
        val aliases = listOf(identityAlias, operationalAlias, wrappingAlias)

        withContext(Dispatchers.IO) {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER).apply { load(null) }
            aliases.forEach { alias -> assertFalse(keyStore.containsAlias(alias)) }

            assertNull(AndroidIdentitySigner.loadExisting(identityAlias))
            assertNull(
                AndroidOperationalSigner.loadExisting(
                    clientId = SoftwareIdentitySigner.generate().clientId,
                    epoch = 1,
                    alias = operationalAlias,
                ),
            )
            assertNull(KeyVault.loadExisting(wrappingAlias))

            keyStore.load(null)
            aliases.forEach { alias -> assertFalse(keyStore.containsAlias(alias)) }
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE_PROVIDER = "AndroidKeyStore"
    }
}

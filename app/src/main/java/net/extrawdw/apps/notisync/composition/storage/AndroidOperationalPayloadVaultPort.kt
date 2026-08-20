package net.extrawdw.apps.notisync.composition.storage

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import net.extrawdw.apps.notisync.data.storage.protection.AndroidKeystoreProtectedPayloadVault
import net.extrawdw.apps.notisync.data.storage.protection.ProtectedCiphertext

/**
 * Explicit Android adapter for the unified vault port. Construction is still a composition-root decision; this type
 * does not create a key, and lifecycle calls are dispatched away from the main thread.
 */
internal class AndroidOperationalPayloadVaultPort(
    private val vault: AndroidKeystoreProtectedPayloadVault,
    private val ioDispatcher: CoroutineDispatcher,
) : OperationalPayloadVaultPort {
    override fun protect(alias: String, plaintext: ByteArray, aad: ByteArray): ProtectedCiphertext =
        vault.protect(alias, plaintext, aad)

    override fun open(
        alias: String,
        nonce: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray,
    ): ByteArray = vault.open(alias, nonce, ciphertext, aad)

    override suspend fun create(generation: Long) = withContext(ioDispatcher) {
        vault.create(generation)
        Unit
    }

    override suspend fun selfTest(generation: Long) = withContext(ioDispatcher) {
        vault.selfTest(generation)
        Unit
    }
}

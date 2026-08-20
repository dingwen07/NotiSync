package net.extrawdw.notisync.sshagent.endpoint

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

class AgentLockState(private val nanoTime: () -> Long = System::nanoTime) {
    private var verifier: ByteArray? = null
    private var salt: ByteArray? = null
    private var failures = 0
    private var nextAttemptAt = 0L

    @Synchronized
    fun isLocked(): Boolean = verifier != null

    @Synchronized
    fun lock(passphrase: ByteArray): Boolean {
        if (verifier != null || passphrase.isEmpty()) {
            passphrase.fill(0)
            return false
        }
        val newSalt = ByteArray(16).also(RANDOM::nextBytes)
        val newVerifier = derive(passphrase, newSalt)
        salt = newSalt
        verifier = newVerifier
        failures = 0
        nextAttemptAt = 0
        passphrase.fill(0)
        return true
    }

    @Synchronized
    fun unlock(passphrase: ByteArray): Boolean {
        val expected = verifier ?: return false.also { passphrase.fill(0) }
        if (nanoTime() < nextAttemptAt) return false.also { passphrase.fill(0) }
        val candidate = derive(passphrase, requireNotNull(salt))
        passphrase.fill(0)
        val matches = MessageDigest.isEqual(expected, candidate)
        candidate.fill(0)
        if (matches) {
            verifier?.fill(0)
            salt?.fill(0)
            verifier = null
            salt = null
            failures = 0
            nextAttemptAt = 0
            return true
        }
        failures = (failures + 1).coerceAtMost(10)
        val delayMillis = (250L shl (failures - 1)).coerceAtMost(30_000L)
        nextAttemptAt = nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMillis)
        return false
    }

    private fun derive(passphrase: ByteArray, salt: ByteArray): ByteArray {
        val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt)
            .withIterations(3)
            .withMemoryAsKB(64 * 1024)
            .withParallelism(1)
            .build()
        return ByteArray(32).also { output ->
            Argon2BytesGenerator().apply { init(parameters) }.generateBytes(passphrase, output)
        }
    }

    private companion object {
        val RANDOM = SecureRandom()
    }
}

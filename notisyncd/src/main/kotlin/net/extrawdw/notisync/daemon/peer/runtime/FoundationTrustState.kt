package net.extrawdw.notisync.daemon.peer.runtime

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import net.extrawdw.notisync.peer.channel.RetryableDeliveryException
import net.extrawdw.notisync.peer.ports.TrustPersistence
import net.extrawdw.notisync.peer.trust.IncomingTrustResult
import net.extrawdw.notisync.peer.trust.PendingRotation
import net.extrawdw.notisync.peer.trust.TrustPrompt
import net.extrawdw.notisync.peer.trust.TrustState
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ProfileUpdate
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.TrustTable

/** Identifies a failed durable trust transaction without treating malformed peer data as retryable. */
internal class TrustPersistenceWriteException(cause: Throwable) :
    RuntimeException("could not persist signed trust state", cause)

/** Adds a typed boundary around a platform persistence implementation's otherwise-untyped failures. */
internal class ClassifiedTrustPersistence(
    private val delegate: TrustPersistence,
) : TrustPersistence {
    override fun read(key: String): String? = delegate.read(key)

    override fun write(values: Map<String, String?>) {
        try {
            delegate.write(values)
        } catch (error: TrustPersistenceWriteException) {
            throw error
        } catch (error: Exception) {
            throw TrustPersistenceWriteException(error)
        }
    }
}

/**
 * Serializes Foundation and UDS trust mutations, translating only durable-write failures into the
 * SecureChannel retry signal. Malformed or unauthorized peer data remains a handled no-op.
 */
internal class FoundationTrustState(
    private val delegate: TrustState,
    private val mutationLock: ReentrantLock,
) : TrustState by delegate {
    override fun applyProfile(update: ProfileUpdate): Boolean = mutate { delegate.applyProfile(update) }

    override fun applyIncomingTable(sender: ClientId, table: TrustTable): IncomingTrustResult =
        mutate { delegate.applyIncomingTable(sender, table) }

    override fun resolveIncomingPrompt(clientId: ClientId, prompt: TrustPrompt, now: Long): Boolean =
        mutate { delegate.resolveIncomingPrompt(clientId, prompt, now) }

    override fun applyCard(clientId: ClientId, cardBlob: SignedBlob): Boolean =
        mutate { delegate.applyCard(clientId, cardBlob) }

    override fun applyKeyEpoch(clientId: ClientId, keyEpochBlob: SignedBlob): Boolean =
        mutate { delegate.applyKeyEpoch(clientId, keyEpochBlob) }

    override fun advanceSelfEpoch(to: Int): Int = mutate { delegate.advanceSelfEpoch(to) }

    override fun setPendingRotation(pending: PendingRotation?) = mutate {
        delegate.setPendingRotation(pending)
    }

    private fun <T> mutate(block: () -> T): T = retryableTrustMutation(mutationLock, block)
}

internal fun <T> retryableTrustMutation(mutationLock: ReentrantLock, block: () -> T): T =
    mutationLock.withLock {
        try {
            block()
        } catch (error: TrustPersistenceWriteException) {
            throw RetryableDeliveryException("signed trust-state persistence failed", error)
        }
    }

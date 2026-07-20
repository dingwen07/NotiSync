package net.extrawdw.notisync.screen

import java.io.IOException
import java.security.MessageDigest
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean

class PskRegistry(
    private val clock: Clock = Clock.systemUTC(),
    private val maximumSessions: Int = 128,
    private val maximumAttemptsPerChannel: Int = 4,
) : AutoCloseable {
    private val sessions = LinkedHashMap<String, Entry>()

    init {
        require(maximumSessions > 0)
        require(maximumAttemptsPerChannel > 0)
    }

    @Synchronized
    fun register(
        descriptor: SessionDescriptor,
        routingToken: ByteArray,
        masterPsk: ByteArray,
    ) {
        require(routingToken.size == ROUTING_TOKEN_BYTES)
        require(masterPsk.size == MASTER_PSK_BYTES)
        val now = clock.millis()
        require(descriptor.expiresAtEpochMillis > now) { "cannot register an expired session" }
        require(descriptor.expiresAtEpochMillis - now in 1..MAX_SESSION_LIFETIME_MILLIS) {
            "cannot register a session more than five minutes ahead"
        }
        purgeExpired(now)
        require(sessions.size < maximumSessions) { "too many pending screen sessions" }
        require(descriptor.sessionId !in sessions) { "session is already registered" }
        require(sessions.values.none { MessageDigest.isEqual(it.routingToken, routingToken) }) {
            "routing token is already registered"
        }
        sessions[descriptor.sessionId] = Entry(descriptor, routingToken.copyOf(), masterPsk.copyOf())
    }

    @Synchronized
    internal fun reserve(offeredIdentities: List<ByteArray>): PskLease? {
        val now = clock.millis()
        purgeExpired(now)
        for (offeredIdentity in offeredIdentities) {
            val parsed = RoutingIdentity.parse(offeredIdentity) ?: continue
            val entry = sessions.values.firstOrNull {
                MessageDigest.isEqual(it.routingToken, parsed.token)
            } ?: continue
            val state = entry.channels.getValue(parsed.channel)
            if (state.consumed || state.reserved || state.attempts >= maximumAttemptsPerChannel) continue
            state.reserved = true
            val key = SessionKeyDeriver.derive(
                entry.masterPsk,
                entry.routingToken,
                entry.descriptor,
                parsed.channel,
            )
            return PskLease(this, entry.descriptor, parsed.channel, offeredIdentity.copyOf(), key)
        }
        return null
    }

    @Synchronized
    private fun markAuthenticated(lease: PskLease) {
        val entry = sessions[lease.descriptor.sessionId]
            ?: throw IOException("screen session is no longer registered")
        val state = entry.channels.getValue(lease.channel)
        if (!state.reserved || state.consumed || state.attempts >= maximumAttemptsPerChannel) {
            throw IOException("screen channel is no longer available")
        }
        // Do not spend this budget merely because an unauthenticated peer copied the
        // plaintext routing identity from the LAN. The TLS PSK binder has been verified
        // before this method is called.
        state.attempts++
    }

    @Synchronized
    private fun release(lease: PskLease, success: Boolean) {
        val entry = sessions[lease.descriptor.sessionId] ?: return
        val state = entry.channels.getValue(lease.channel)
        if (!state.reserved) return
        state.reserved = false
        if (success) state.consumed = true
        if (entry.channels.values.all { it.consumed }) {
            sessions.remove(lease.descriptor.sessionId)?.destroy()
        }
    }

    @Synchronized
    fun cancel(sessionId: String): Boolean = sessions.remove(sessionId)?.let {
        it.destroy()
        true
    } ?: false

    @Synchronized
    fun purgeExpired(nowEpochMillis: Long = clock.millis()) {
        val iterator = sessions.iterator()
        while (iterator.hasNext()) {
            val (_, entry) = iterator.next()
            if (entry.descriptor.expiresAtEpochMillis <= nowEpochMillis) {
                iterator.remove()
                entry.destroy()
            }
        }
    }

    @Synchronized
    override fun close() {
        sessions.values.forEach(Entry::destroy)
        sessions.clear()
    }

    private data class Entry(
        val descriptor: SessionDescriptor,
        val routingToken: ByteArray,
        val masterPsk: ByteArray,
        val channels: MutableMap<ScreenChannel, ChannelState> = ScreenChannel.entries
            .associateWith { ChannelState() }.toMutableMap(),
    ) {
        fun destroy() {
            routingToken.fill(0)
            masterPsk.fill(0)
        }
    }

    private data class ChannelState(
        var reserved: Boolean = false,
        var consumed: Boolean = false,
        var attempts: Int = 0,
    )

    internal class PskLease(
        private val registry: PskRegistry,
        val descriptor: SessionDescriptor,
        val channel: ScreenChannel,
        val identity: ByteArray,
        val key: ByteArray,
    ) : AutoCloseable {
        private val finished = AtomicBoolean()
        private val authenticated = AtomicBoolean()

        fun markAuthenticated() {
            if (!authenticated.compareAndSet(false, true)) return
            try {
                registry.markAuthenticated(this)
            } catch (error: Throwable) {
                authenticated.set(false)
                throw error
            }
        }

        fun finish(success: Boolean) {
            if (!finished.compareAndSet(false, true)) return
            try {
                registry.release(this, success)
            } finally {
                identity.fill(0)
                key.fill(0)
            }
        }

        override fun close() = finish(false)
    }
}

internal fun requireBinding(actual: ChannelBinding, expected: ChannelBinding) {
    if (actual != expected) throw IOException("screen channel binding mismatch")
}

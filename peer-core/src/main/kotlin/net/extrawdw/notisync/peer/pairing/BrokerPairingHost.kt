package net.extrawdw.notisync.peer.pairing

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.extrawdw.notisync.protocol.BrokerPairing

/** Owns rotating broker-assisted host sessions for one pairing screen. No device is trusted here. */
class BrokerPairingHost(
    private val sessionMillis: Long = BrokerPairing.SESSION_MILLIS,
    private val rotationLeadMillis: Long = 30_000,
    private val retryMillis: Long = 2_000,
    private val exchange: suspend (onReady: (BrokerPairingLink) -> Unit) -> String,
) {
    init {
        require(rotationLeadMillis in 1 until sessionMillis && retryMillis > 0)
    }

    /** Returns only the first authenticated CARD, after closing every other session. */
    suspend fun awaitCard(
        onLink: (BrokerPairingLink?) -> Unit,
        onUnavailable: () -> Unit,
    ): String = coroutineScope {
        val events = Channel<Event>(Channel.UNLIMITED)
        val sessions = mutableMapOf<Int, Job>()
        val links = mutableMapOf<Int, BrokerPairingLink>()
        val expired = mutableSetOf<Int>()
        var newest = 0
        var displayed: Int? = null
        var retry: Job? = null
        var retryDelay = retryMillis
        var rotationDue = false

        fun displayLatest() {
            val next = links.keys.maxOrNull()
            if (next != displayed) {
                displayed = next
                onLink(next?.let(links::getValue))
            }
        }

        fun startSession() {
            check(sessions.size < 2)
            rotationDue = false
            val id = ++newest
            sessions[id] = launch {
                // Start timers before connecting, so network/setup delays cannot extend the QR lifetime.
                val rotate = launch {
                    delay(sessionMillis - rotationLeadMillis)
                    events.send(Event.Rotate(id))
                    delay(rotationLeadMillis)
                    events.send(Event.Expired(id))
                }
                var payload: String? = null
                try {
                    payload = withTimeout(sessionMillis) {
                        exchange { link -> events.trySend(Event.Ready(id, link)) }
                    }
                } catch (_: TimeoutCancellationException) {
                    // Expiry is normal; the next session may already be displayed.
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Authentication/network failures burn this session. A retry gets a fresh secret.
                } finally {
                    rotate.cancel()
                    events.trySend(Event.Finished(id, payload))
                }
            }
        }

        try {
            onLink(null)
            startSession()
            var received: String? = null
            while (received == null) {
                when (val event = events.receive()) {
                    is Event.Ready -> if (event.id in sessions && event.id !in expired) {
                        links[event.id] = event.link
                        retryDelay = retryMillis
                        displayLatest()
                    }
                    is Event.Rotate -> if (event.id == newest) {
                        rotationDue = true
                        if (sessions.size < 2) startSession()
                    }
                    is Event.Expired -> if (event.id in sessions) {
                        expired += event.id
                        links.remove(event.id)
                        displayLatest()
                        if (links.isEmpty()) onUnavailable()
                    }
                    is Event.Finished -> {
                        sessions.remove(event.id)?.join()
                        links.remove(event.id)
                        expired.remove(event.id)
                        if (event.payload != null) {
                            received = event.payload
                        } else {
                            displayLatest()
                            if (links.isEmpty()) onUnavailable()
                            if (rotationDue && newest in sessions && sessions.size < 2) {
                                // A closing older socket may have occupied the second slot at rotation time.
                                startSession()
                            } else if (event.id == newest && retry == null) {
                                val waitMillis = retryDelay
                                retry = launch { delay(waitMillis); events.send(Event.Retry) }
                                retryDelay = (retryDelay * 2).coerceAtMost(30_000)
                            }
                        }
                    }
                    Event.Retry -> {
                        retry = null
                        if (sessions.size < 2) startSession()
                    }
                }
            }
            received
        } finally {
            // Cancel both first, then wait for their sockets to close before returning a candidate.
            sessions.values.forEach(Job::cancel)
            retry?.cancel()
            withContext(NonCancellable) {
                sessions.values.forEach { it.join() }
                retry?.cancelAndJoin()
            }
            events.cancel()
            onLink(null)
        }
    }

    private sealed interface Event {
        data class Ready(val id: Int, val link: BrokerPairingLink) : Event
        data class Rotate(val id: Int) : Event
        data class Expired(val id: Int) : Event
        data class Finished(val id: Int, val payload: String?) : Event
        data object Retry : Event
    }
}

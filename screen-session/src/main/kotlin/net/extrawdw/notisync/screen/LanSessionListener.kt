package net.extrawdw.notisync.screen

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.StandardProtocolFamily
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.time.Duration
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

interface ScreenSessionListener : AutoCloseable {
    val candidates: List<ScreenConnectionCandidate>

    fun acceptPair(
        sessionId: String,
        registry: PskRegistry,
        timeout: Duration,
        handshakeTimeout: Duration = DEFAULT_HANDSHAKE_TIMEOUT,
        maximumAcceptedSockets: Int = 8,
    ): SecureChannelPair
}

class LanSessionListener private constructor(
    private val bindings: List<Binding>,
    private val selector: Selector,
) : ScreenSessionListener {
    private val closed = AtomicBoolean()
    private val activeAccept = AtomicReference<AcceptContext?>()

    override val candidates: List<ScreenConnectionCandidate> = bindings.map { binding ->
        val local = binding.channel.localAddress as InetSocketAddress
        ScreenConnectionCandidate(
            kind = ScreenConnectionCandidate.LAN_TCP,
            host = local.address.hostAddress.substringBefore('%'),
            port = local.port,
            interfaceName = binding.address.interfaceName,
        )
    }

    @Throws(IOException::class)
    override fun acceptPair(
        sessionId: String,
        registry: PskRegistry,
        timeout: Duration,
        handshakeTimeout: Duration,
        maximumAcceptedSockets: Int,
    ): SecureChannelPair {
        requireUtf8(sessionId, 1, 128, "sessionId")
        require(!timeout.isZero && !timeout.isNegative)
        require(!handshakeTimeout.isZero && !handshakeTimeout.isNegative)
        require(maximumAcceptedSockets in 2..MAX_PENDING_HANDSHAKES)

        val timeoutNanos = timeout.toNanosSaturated()
        val deadlineNanos = monotonicDeadline(timeoutNanos)
        val context = AcceptContext(
            selector = selector,
            maximumPendingSockets = maximumAcceptedSockets,
        )
        if (!activeAccept.compareAndSet(null, context)) {
            context.cancel()
            throw IOException("screen session listener already has an active accept")
        }

        val accepted = mutableMapOf<ScreenChannel, SecureSessionChannel>()
        var pairReturned = false
        try {
            if (closed.get()) throw IOException("screen session listener is closed")
            while (accepted.size < ScreenChannel.entries.size) {
                var remainingNanos = remainingUntil(deadlineNanos)
                if (remainingNanos <= 0) throw IOException("screen session listener timed out")
                drainCompletions(context, sessionId, accepted)
                if (accepted.size == ScreenChannel.entries.size) {
                    if (remainingUntil(deadlineNanos) <= 0) {
                        throw IOException("screen session listener timed out")
                    }
                    break
                }

                remainingNanos = remainingUntil(deadlineNanos)
                if (remainingNanos <= 0) throw IOException("screen session listener timed out")
                if (closed.get() || context.cancelled.get()) {
                    throw IOException("screen session listener is closed")
                }

                val selectedCount = try {
                    selector.select(remainingNanos.asCeilingMillis())
                } catch (error: Exception) {
                    if (closed.get()) throw IOException("screen session listener is closed", error)
                    throw error
                }
                drainCompletions(context, sessionId, accepted)
                if (selectedCount == 0) continue

                var acceptedThisCycle = 0
                val selected = selector.selectedKeys().iterator()
                while (selected.hasNext()) {
                    val key = selected.next()
                    selected.remove()
                    if (!key.isValid || !key.isAcceptable) continue
                    val binding = key.attachment() as? Binding ?: continue
                    val server = binding.channel
                    var acceptedThisBinding = 0
                    while (acceptedThisCycle < MAX_ACCEPTS_PER_SELECT &&
                        acceptedThisBinding < MAX_ACCEPTS_PER_BINDING_PER_SELECT
                    ) {
                        val socketChannel = server.accept() ?: break
                        acceptedThisCycle++
                        acceptedThisBinding++
                        admit(
                            binding = binding,
                            socketChannel = socketChannel,
                            registry = registry,
                            handshakeTimeout = handshakeTimeout,
                            deadlineNanos = deadlineNanos,
                            context = context,
                        )
                    }
                    if (acceptedThisCycle >= MAX_ACCEPTS_PER_SELECT) break
                }
            }

            pairReturned = true
            return SecureChannelPair(
                video = requireNotNull(accepted[ScreenChannel.VIDEO]),
                control = requireNotNull(accepted[ScreenChannel.CONTROL]),
            )
        } finally {
            if (!pairReturned) accepted.values.forEach(SecureSessionChannel::close)
            context.cancel()
            activeAccept.compareAndSet(context, null)
        }
    }

    private fun admit(
        binding: Binding,
        socketChannel: SocketChannel,
        registry: PskRegistry,
        handshakeTimeout: Duration,
        deadlineNanos: Long,
        context: AcceptContext,
    ) {
        val address = ((runCatching { socketChannel.remoteAddress }.getOrNull() as? InetSocketAddress)?.address)
        if (address == null || !binding.address.admits(address) || closed.get() || context.cancelled.get()) {
            runCatching { socketChannel.close() }
            return
        }
        val admission = context.admission.tryAcquire(address, System.nanoTime())
        if (!admission) {
            runCatching { socketChannel.close() }
            return
        }

        try {
            socketChannel.configureBlocking(true)
            socketChannel.socket().tcpNoDelay = true
        } catch (_: Exception) {
            context.admission.release(address)
            runCatching { socketChannel.close() }
            return
        }

        if (!context.submit(
                socketChannel = socketChannel,
                address = address,
                registry = registry,
                requestedHandshakeTimeout = handshakeTimeout,
                deadlineNanos = deadlineNanos,
                listenerClosed = closed,
            )
        ) {
            context.admission.release(address)
            runCatching { socketChannel.close() }
        }
    }

    private fun drainCompletions(
        context: AcceptContext,
        sessionId: String,
        accepted: MutableMap<ScreenChannel, SecureSessionChannel>,
    ) {
        while (true) {
            val completed = context.completions.poll() ?: return
            val channel = try {
                completed.get()
            } catch (error: ExecutionException) {
                val cause = error.cause
                if (cause is Error) throw cause
                continue
            } catch (_: Exception) {
                continue
            } ?: continue
            context.claimProduced(channel)
            if (channel.descriptor.sessionId != sessionId || accepted.putIfAbsent(channel.channel, channel) != null) {
                channel.close()
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        activeAccept.get()?.cancel()
        bindings.forEach { runCatching { it.channel.close() } }
        runCatching { selector.wakeup() }
        runCatching { selector.close() }
    }

    companion object {
        @Throws(IOException::class)
        fun open(
            addressProvider: LanAddressProvider = SystemLanAddressProvider(),
            backlog: Int = 8,
        ): LanSessionListener {
            require(backlog > 0)
            val addresses = addressProvider.addresses()
            require(addresses.isNotEmpty()) { "no eligible LAN interfaces" }
            val selector = Selector.open()
            val bindings = mutableListOf<Binding>()
            val failures = mutableListOf<Exception>()
            try {
                for (address in addresses) {
                    val family = if (address.address.address.size == 4) {
                        StandardProtocolFamily.INET
                    } else {
                        StandardProtocolFamily.INET6
                    }
                    var channel: ServerSocketChannel? = null
                    try {
                        val opened = ServerSocketChannel.open(family)
                        channel = opened
                        opened.bind(InetSocketAddress(address.address, 0), backlog)
                        opened.configureBlocking(false)
                        val binding = Binding(address, opened)
                        opened.register(selector, SelectionKey.OP_ACCEPT, binding)
                        bindings += binding
                    } catch (error: Exception) {
                        runCatching { channel?.close() }
                        failures += error
                    }
                }
                if (bindings.isEmpty()) {
                    throw IOException("failed to bind any eligible LAN interface", failures.firstOrNull())
                }
                return LanSessionListener(bindings, selector)
            } catch (error: Throwable) {
                bindings.forEach { runCatching { it.channel.close() } }
                selector.close()
                throw error
            }
        }
    }

    private data class Binding(
        val address: LanAddress,
        val channel: ServerSocketChannel,
    )

    private class AcceptContext(
        private val selector: Selector,
        maximumPendingSockets: Int,
    ) {
        val cancelled = AtomicBoolean()
        val admission = AdmissionController(maximumPendingSockets)
        private val sockets = java.util.concurrent.ConcurrentHashMap.newKeySet<SocketChannel>()
        private val produced = java.util.concurrent.ConcurrentHashMap.newKeySet<SecureSessionChannel>()
        private val producedLock = Any()
        private val executor = ThreadPoolExecutor(
            maximumPendingSockets,
            maximumPendingSockets,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(maximumPendingSockets),
            { runnable ->
                Thread(runnable, "notisync-screen-handshake-${HANDSHAKE_THREAD_ID.incrementAndGet()}").apply {
                    isDaemon = true
                }
            },
            ThreadPoolExecutor.AbortPolicy(),
        )
        val completions = ExecutorCompletionService<SecureSessionChannel?>(executor)

        fun submit(
            socketChannel: SocketChannel,
            address: InetAddress,
            registry: PskRegistry,
            requestedHandshakeTimeout: Duration,
            deadlineNanos: Long,
            listenerClosed: AtomicBoolean,
        ): Boolean {
            if (cancelled.get()) return false
            sockets += socketChannel
            if (cancelled.get()) {
                sockets -= socketChannel
                return false
            }
            return try {
                completions.submit handshake@{
                    var channel: SecureSessionChannel? = null
                    try {
                        val remainingNanos = remainingUntil(deadlineNanos)
                        if (remainingNanos <= 0 || cancelled.get() || listenerClosed.get()) return@handshake null
                        val effectiveTimeout = Duration.ofNanos(
                            minOf(requestedHandshakeTimeout.toNanosSaturated(), remainingNanos),
                        )
                        channel = try {
                            PskTlsServer.accept(socketChannel.socket(), registry, effectiveTimeout)
                        } catch (_: Exception) {
                            null
                        }
                        if (channel == null ||
                            remainingUntil(deadlineNanos) <= 0 ||
                            cancelled.get() ||
                            listenerClosed.get()
                        ) {
                            channel?.close()
                            return@handshake null
                        }
                        if (!publish(channel)) {
                            channel.close()
                            return@handshake null
                        }
                        channel
                    } finally {
                        sockets -= socketChannel
                        admission.release(address)
                        runCatching { selector.wakeup() }
                    }
                }
                true
            } catch (_: RejectedExecutionException) {
                sockets -= socketChannel
                false
            }
        }

        fun claimProduced(channel: SecureSessionChannel) {
            synchronized(producedLock) {
                produced -= channel
            }
        }

        private fun publish(channel: SecureSessionChannel): Boolean = synchronized(producedLock) {
            if (cancelled.get()) return@synchronized false
            produced += channel
            true
        }

        fun cancel() {
            if (!cancelled.compareAndSet(false, true)) return
            sockets.forEach { runCatching { it.close() } }
            synchronized(producedLock) {
                produced.forEach { runCatching { it.close() } }
                produced.clear()
            }
            executor.shutdownNow()
            runCatching { selector.wakeup() }
        }
    }

    private class AdmissionController(private val maximumPendingSockets: Int) {
        private val maximumPerAddress = minOf(MAX_PENDING_PER_ADDRESS, maximumPendingSockets)
        private val globalAttempts = ArrayDeque<Long>()
        private val addresses = mutableMapOf<InetAddress, AddressState>()
        private var globalPending = 0

        @Synchronized
        fun tryAcquire(address: InetAddress, nowNanos: Long): Boolean {
            purge(globalAttempts, nowNanos)
            purgeAddresses(nowNanos)
            if (globalAttempts.size >= MAX_CONNECTIONS_PER_RATE_WINDOW) return false
            globalAttempts.addLast(nowNanos)

            val state = addresses.getOrPut(address) { AddressState() }
            if (state.attempts.size >= MAX_CONNECTIONS_PER_ADDRESS_PER_RATE_WINDOW) return false
            state.attempts.addLast(nowNanos)
            if (globalPending >= maximumPendingSockets || state.pending >= maximumPerAddress) return false
            globalPending++
            state.pending++
            return true
        }

        @Synchronized
        fun release(address: InetAddress) {
            val state = addresses[address] ?: return
            if (state.pending > 0) {
                state.pending--
                globalPending--
            }
        }

        private fun purgeAddresses(nowNanos: Long) {
            val iterator = addresses.iterator()
            while (iterator.hasNext()) {
                val (_, state) = iterator.next()
                purge(state.attempts, nowNanos)
                if (state.pending == 0 && state.attempts.isEmpty()) iterator.remove()
            }
        }

        private fun purge(attempts: ArrayDeque<Long>, nowNanos: Long) {
            while (attempts.isNotEmpty() && nowNanos - attempts.first() >= RATE_WINDOW_NANOS) {
                attempts.removeFirst()
            }
        }

        private class AddressState(
            val attempts: ArrayDeque<Long> = ArrayDeque(),
            var pending: Int = 0,
        )
    }
}

data class SecureChannelPair(
    val video: SecureSessionChannel,
    val control: SecureSessionChannel,
) : AutoCloseable {
    override fun close() {
        video.close()
        control.close()
    }
}

private const val MAX_ACCEPTS_PER_SELECT = 32
private const val MAX_ACCEPTS_PER_BINDING_PER_SELECT = 8
private const val MAX_PENDING_HANDSHAKES = 32
private const val MAX_PENDING_PER_ADDRESS = 4
private const val MAX_CONNECTIONS_PER_RATE_WINDOW = 128
private const val MAX_CONNECTIONS_PER_ADDRESS_PER_RATE_WINDOW = 32
private val RATE_WINDOW_NANOS = Duration.ofSeconds(1).toNanos()
private val HANDSHAKE_THREAD_ID = AtomicInteger()

private fun Duration.toNanosSaturated(): Long = try {
    toNanos()
} catch (_: ArithmeticException) {
    Long.MAX_VALUE
}

private fun monotonicDeadline(timeoutNanos: Long): Long {
    val now = System.nanoTime()
    return try {
        Math.addExact(now, timeoutNanos)
    } catch (_: ArithmeticException) {
        Long.MAX_VALUE
    }
}

private fun remainingUntil(deadlineNanos: Long): Long {
    if (deadlineNanos == Long.MAX_VALUE) return Long.MAX_VALUE
    return deadlineNanos - System.nanoTime()
}

private fun Long.asCeilingMillis(): Long {
    val whole = this / 1_000_000L
    return (whole + if (this % 1_000_000L == 0L) 0L else 1L).coerceAtLeast(1L)
}

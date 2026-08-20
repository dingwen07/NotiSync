package net.extrawdw.notisync.sshagent.endpoint

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

interface AgentEndpoint : AutoCloseable {
    /** Blocks until the endpoint closes. */
    fun run(onReady: () -> Unit = {})
}

/** Selects a fallback only when the preferred listener fails before becoming ready. */
class PreferredAgentEndpoint(
    private val preferred: AgentEndpoint,
    private val fallback: AgentEndpoint,
    private val mayFallback: (Throwable) -> Boolean,
) : AgentEndpoint {
    private val closed = AtomicBoolean(false)

    @Volatile
    var selection: Selection? = null
        private set

    enum class Selection { PREFERRED, FALLBACK }

    override fun run(onReady: () -> Unit) {
        check(!closed.get())
        try {
            preferred.run {
                selection = Selection.PREFERRED
                onReady()
            }
            return
        } catch (failure: Throwable) {
            if (closed.get() || selection != null || !mayFallback(failure)) throw failure
            preferred.close()
        }
        if (closed.get()) return
        fallback.run {
            selection = Selection.FALLBACK
            onReady()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        preferred.close()
        fallback.close()
    }
}

/** Runs multiple explicitly selected endpoints as one lifecycle boundary. */
class CompositeAgentEndpoint(private val endpoints: List<AgentEndpoint>) : AgentEndpoint {
    private val closed = AtomicBoolean(false)
    private val executor = Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name("notisync-ssh-agent-listener-", 0).factory(),
    )

    init {
        require(endpoints.size >= 2) { "composite endpoint requires at least two listeners" }
    }

    override fun run(onReady: () -> Unit) {
        check(!closed.get())
        val stopped = CountDownLatch(1)
        val ready = CountDownLatch(endpoints.size)
        val readySignalled = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>()
        endpoints.forEach { endpoint ->
            executor.submit {
                try {
                    endpoint.run {
                        ready.countDown()
                        if (ready.count == 0L && readySignalled.compareAndSet(false, true)) onReady()
                    }
                } catch (error: Throwable) {
                    if (!closed.get()) failure.compareAndSet(null, error)
                } finally {
                    stopped.countDown()
                }
            }
        }
        try {
            stopped.await()
        } finally {
            close()
        }
        failure.get()?.let { throw it }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        endpoints.forEach { runCatching { it.close() } }
        executor.shutdownNow()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }
}

package net.extrawdw.notisync.daemon.peer.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

/** One serialized handler, bounded admission, FIFO within each lane, at most eight live items per replay. */
internal class LivePriorityDispatcher<T, R>(scope: CoroutineScope, private val handle: suspend (T) -> R) {
    private class Work<T, R>(val value: T, val result: CompletableDeferred<R>)
    private val live = Channel<Work<T, R>>(capacity = 1)
    private val replay = Channel<Work<T, R>>(capacity = 1)

    init {
        scope.launch {
            var liveStreak = 0
            try {
                while (isActive) {
                    val next = if (liveStreak >= 8) replay.tryReceive().getOrNull()?.let { it to false } else null
                    val (work, isLive) = next ?: select {
                        live.onReceive { it to true }
                        replay.onReceive { it to false }
                    }
                    if (!work.result.isActive) continue
                    liveStreak = if (isLive) (liveStreak + 1).coerceAtMost(8) else 0
                    try {
                        currentCoroutineContext().ensureActive()
                        work.result.complete(handle(work.value))
                    } catch (error: Throwable) {
                        work.result.completeExceptionally(error)
                        currentCoroutineContext().ensureActive()
                    }
                }
            } finally {
                live.close()
                replay.close()
                while (true) (live.tryReceive().getOrNull() ?: break).result.cancel()
                while (true) (replay.tryReceive().getOrNull() ?: break).result.cancel()
            }
        }
    }

    suspend fun submit(value: T, isLive: Boolean): R {
        val work = Work(value, CompletableDeferred<R>())
        try {
            (if (isLive) live else replay).send(work)
            return work.result.await()
        } finally {
            work.result.cancel()
        }
    }
}

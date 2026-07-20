package net.extrawdw.apps.notisync.trust

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DurableTrustMutationsTest {
    @Test
    fun launch_containsFailureAndReportsIt() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { io ->
            val reported = mutableListOf<Throwable>()
            val shown = mutableListOf<Throwable>()
            val mutations = DurableTrustMutations(
                applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                ioDispatcher = io,
                callbackDispatcher = Dispatchers.Unconfined,
                reportFailure = reported::add,
            )
            val failure = IllegalStateException("disk full")

            val job = mutations.launch(onFailure = shown::add) { throw failure }
            job.join()

            assertTrue(job.isCompleted)
            assertFalse(job.isCancelled)
            assertEquals(1, reported.size)
            assertEquals(IllegalStateException::class.java, reported.single()::class.java)
            assertEquals("disk full", reported.single().message)
            assertEquals(reported, shown)
        }
    }

    @Test
    fun concurrentMutationsAreSerialized() = runBlocking {
        Executors.newFixedThreadPool(2).asCoroutineDispatcher().use { io ->
            val mutations = DurableTrustMutations(
                applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                ioDispatcher = io,
                callbackDispatcher = Dispatchers.Unconfined,
                reportFailure = {},
            )
            val firstStarted = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)
            val secondStarted = CountDownLatch(1)

            val first = mutations.launch {
                firstStarted.countDown()
                check(releaseFirst.await(2, TimeUnit.SECONDS))
            }
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS))
            val second = mutations.launch { secondStarted.countDown() }

            assertFalse(secondStarted.await(100, TimeUnit.MILLISECONDS))
            releaseFirst.countDown()
            first.join()
            second.join()
            assertTrue(secondStarted.await(2, TimeUnit.SECONDS))
        }
    }
}

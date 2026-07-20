package net.extrawdw.apps.notisync.screen

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ScreenMirrorAction
import net.extrawdw.notisync.protocol.ScreenMirrorSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenMirrorSessionOwnershipTest {
    private val source = ClientId("source-peer")
    private val requester = ClientId("requester-peer")
    private val otherRequester = ClientId("other-requester")

    private fun sync(
        action: ScreenMirrorAction,
        sessionId: String = "session-1",
        requesterPeerId: ClientId = requester,
        sourcePeerId: ClientId = source,
    ) = ScreenMirrorSync(
        action = action,
        sessionId = sessionId,
        requesterPeerId = requesterPeerId,
        sourcePeerId = sourcePeerId,
        issuedAt = 1_000L,
    )

    @Test
    fun authenticatedStopMustMatchRecordedRequesterAndSession() {
        val active = sync(ScreenMirrorAction.REQUEST)

        assertTrue(
            AuthenticatedScreenStop(
                request = sync(ScreenMirrorAction.CANCEL),
                senderId = requester,
                senderOwnDevice = true,
            ).permits(active, source),
        )
        assertTrue(
            AuthenticatedScreenStop(
                request = sync(ScreenMirrorAction.END),
                senderId = requester,
                senderOwnDevice = true,
            ).permits(active, source),
        )

        // A different trusted own device cannot stop the active controller merely by copying the
        // session ID and naming itself in an otherwise valid signed CANCEL.
        assertFalse(
            AuthenticatedScreenStop(
                request = sync(
                    ScreenMirrorAction.CANCEL,
                    requesterPeerId = otherRequester,
                ),
                senderId = otherRequester,
                senderOwnDevice = true,
            ).permits(active, source),
        )
        assertFalse(
            AuthenticatedScreenStop(
                request = sync(ScreenMirrorAction.CANCEL, sessionId = "session-2"),
                senderId = requester,
                senderOwnDevice = true,
            ).permits(active, source),
        )
    }

    @Test
    fun authenticatedStopRejectsBodySubstitutionAndNonOwnSender() {
        val active = sync(ScreenMirrorAction.REQUEST)

        assertFalse(
            AuthenticatedScreenStop(
                request = sync(ScreenMirrorAction.CANCEL),
                senderId = otherRequester,
                senderOwnDevice = true,
            ).permits(active, source),
        )
        assertFalse(
            AuthenticatedScreenStop(
                request = sync(ScreenMirrorAction.CANCEL, sourcePeerId = ClientId("other-source")),
                senderId = requester,
                senderOwnDevice = true,
            ).permits(active, source),
        )
        assertFalse(
            AuthenticatedScreenStop(
                request = sync(ScreenMirrorAction.CANCEL),
                senderId = requester,
                senderOwnDevice = false,
            ).permits(active, source),
        )
        assertFalse(
            AuthenticatedScreenStop(
                request = sync(ScreenMirrorAction.STATUS),
                senderId = requester,
                senderOwnDevice = true,
            ).permits(active, source),
        )
    }

    @Test
    fun exactForegroundLeaseSwitchIgnoresOldCompletionEvenWhenRemoteSessionIdIsReused() {
        val ownership = ScreenMirrorForegroundOwnership()
        val oldKey = ScreenMirrorForegroundLeaseKey("same-remote-session", "local-lease-old")
        val newKey = ScreenMirrorForegroundLeaseKey("same-remote-session", "local-lease-new")

        assertEquals(
            ScreenMirrorForegroundOwnership.StartDecision.ACQUIRED,
            ownership.onStart(oldKey, startId = 10),
        )
        assertTrue(ownership.markForegroundOwned(oldKey))
        assertEquals(
            ScreenMirrorForegroundOwnership.StartDecision.DUPLICATE,
            ownership.onStart(oldKey, startId = 11),
        )
        assertEquals(
            ScreenMirrorForegroundOwnership.StartDecision.SWITCHED,
            ownership.onStart(newKey, startId = 12),
        )
        assertTrue(ownership.markForegroundOwned(newKey))

        assertNull(ownership.finish(oldKey))
        assertTrue(ownership.isOwnedBy(newKey))

        val newLease = ownership.finish(newKey)
        assertEquals(12, newLease?.latestStartId)
        assertTrue(newLease?.foregroundOwned == true)
        assertFalse(ownership.isOwnedBy(newKey))
    }

    @Test
    fun rejectedStaleForegroundCommandIsAbsorbedWithoutChangingExactOwner() {
        val ownership = ScreenMirrorForegroundOwnership()
        val current = ScreenMirrorForegroundLeaseKey("session-new", "lease-new")
        val stale = ScreenMirrorForegroundLeaseKey("session-old", "lease-old")

        assertEquals(
            ScreenMirrorForegroundOwnership.StartDecision.ACQUIRED,
            ownership.onStart(current, startId = 20),
        )
        assertTrue(ownership.markForegroundOwned(current))

        // The service deliberately does not call onStart(stale): its controller validation failed.
        // It still absorbs Android's newer startId so stopSelf(21) cannot stop the valid owner.
        assertTrue(ownership.noteCommand(startId = 21))
        assertTrue(ownership.isOwnedBy(current))
        assertFalse(ownership.isOwnedBy(stale))

        val currentLease = ownership.finish(current)
        assertEquals(21, currentLease?.latestStartId)
        assertTrue(currentLease?.foregroundOwned == true)
    }

    @Test
    fun ineligibleRequestCannotEvictActiveOrQueuedController() {
        val active = Any()
        val queued = Any()
        val unauthorized = Any()
        val slots = ScreenMirrorSessionSlot<Any>()

        assertEquals(
            ScreenMirrorSessionSlot.Disposition.ACTIVE,
            slots.offer(active, eligible = true).disposition,
        )
        assertEquals(
            ScreenMirrorSessionSlot.Disposition.QUEUED,
            slots.offer(queued, eligible = true).disposition,
        )

        val rejected = slots.offer(unauthorized, eligible = false)

        assertEquals(ScreenMirrorSessionSlot.Disposition.REJECTED, rejected.disposition)
        assertTrue(slots.active === active)
        assertTrue(slots.replacement === queued)
    }

    @Test
    fun newestValidatedControllerReplacesQueuedRequestAndPromotesAfterOldCompletion() {
        val active = Any()
        val firstReplacement = Any()
        val newestReplacement = Any()
        val slots = ScreenMirrorSessionSlot<Any>()
        slots.offer(active, eligible = true)
        slots.offer(firstReplacement, eligible = true)

        val replacement = slots.offer(newestReplacement, eligible = true)
        assertEquals(ScreenMirrorSessionSlot.Disposition.QUEUED, replacement.disposition)
        assertTrue(replacement.active === active)
        assertTrue(replacement.displacedReplacement === firstReplacement)

        val completion = slots.complete(active)
        assertTrue(completion.owned)
        assertTrue(completion.replacement === newestReplacement)
        assertNull(slots.active)
        assertNull(slots.replacement)

        assertEquals(
            ScreenMirrorSessionSlot.Disposition.ACTIVE,
            slots.offer(checkNotNull(completion.replacement), eligible = true).disposition,
        )
        assertTrue(slots.active === newestReplacement)
    }

    @Test
    fun staleCompletionCannotClearPromotedController() {
        val old = Any()
        val replacement = Any()
        val slots = ScreenMirrorSessionSlot<Any>()
        slots.offer(old, eligible = true)
        slots.offer(replacement, eligible = true)
        val completion = slots.complete(old)
        slots.offer(checkNotNull(completion.replacement), eligible = true)

        val stale = slots.complete(old)

        assertFalse(stale.owned)
        assertTrue(slots.active === replacement)
        assertNull(slots.replacement)
    }

    @Test
    fun privilegedTeardownHasOneExactOwnerAcrossConcurrentStopPaths() {
        val claim = ScreenMirrorTeardownClaim()
        val executor = Executors.newFixedThreadPool(8)
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        val winners = AtomicInteger()
        try {
            val tasks = List(8) {
                executor.submit {
                    ready.countDown()
                    assertTrue(start.await(2, TimeUnit.SECONDS))
                    if (claim.tryClaim()) winners.incrementAndGet()
                }
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            start.countDown()
            tasks.forEach { it.get(2, TimeUnit.SECONDS) }

            assertEquals(1, winners.get())
            assertFalse(claim.tryClaim())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun atomicSessionOwnerCleansUpWhenCancelledBeforeFirstDispatch() {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val blockerStarted = CountDownLatch(1)
        val releaseDispatcher = CountDownLatch(1)
        val cleanupFinished = CountDownLatch(1)
        val cleanupCount = AtomicInteger()
        val bodyRan = AtomicBoolean(false)
        try {
            executor.execute {
                blockerStarted.countDown()
                releaseDispatcher.await(2, TimeUnit.SECONDS)
            }
            assertTrue(blockerStarted.await(2, TimeUnit.SECONDS))

            val owner = CoroutineScope(SupervisorJob()).launchAtomicScreenMirrorSession(
                dispatcher = dispatcher,
                body = { bodyRan.set(true) },
                cleanup = {
                    cleanupCount.incrementAndGet()
                    cleanupFinished.countDown()
                },
            )
            owner.releaseAfterInstallation()
            owner.job.cancel()
            releaseDispatcher.countDown()

            assertTrue(cleanupFinished.await(2, TimeUnit.SECONDS))
            runBlocking { owner.job.join() }
            assertFalse(bodyRan.get())
            assertEquals(1, cleanupCount.get())
        } finally {
            releaseDispatcher.countDown()
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun atomicSessionOwnerCleansUpWhenCancelledBeforeInstallationGateOpens() {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val blockerStarted = CountDownLatch(1)
        val releaseDispatcher = CountDownLatch(1)
        val cleanupFinished = CountDownLatch(1)
        val cleanupCount = AtomicInteger()
        val bodyRan = AtomicBoolean(false)
        try {
            executor.execute {
                blockerStarted.countDown()
                releaseDispatcher.await(2, TimeUnit.SECONDS)
            }
            assertTrue(blockerStarted.await(2, TimeUnit.SECONDS))

            val owner = CoroutineScope(SupervisorJob()).launchAtomicScreenMirrorSession(
                dispatcher = dispatcher,
                body = { bodyRan.set(true) },
                cleanup = {
                    cleanupCount.incrementAndGet()
                    cleanupFinished.countDown()
                },
            )
            owner.job.cancel()
            owner.releaseAfterInstallation()
            releaseDispatcher.countDown()

            assertTrue(cleanupFinished.await(2, TimeUnit.SECONDS))
            runBlocking { owner.job.join() }
            assertFalse(bodyRan.get())
            assertEquals(1, cleanupCount.get())
        } finally {
            releaseDispatcher.countDown()
            dispatcher.close()
            executor.shutdownNow()
        }
    }

}

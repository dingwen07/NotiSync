package net.extrawdw.apps.notisync.work

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayWorkersTest {
    @Test
    fun runtimeSurfaceContainsOnlyExactFetchAndFiniteDrain() {
        assertEquals(
            setOf("drainFiniteBatch", "fetchAndProcessExact"),
            RelayWorkerRuntime::class.java.declaredMethods
                .map { it.name.substringBefore('-') }
                .toSet(),
        )
        assertEquals(
            setOf(RelayWorkerExecutionResult.COMPLETE, RelayWorkerExecutionResult.RETRY_REQUIRED),
            RelayWorkerExecutionResult.entries.toSet(),
        )
        val runtime = object : RelayWorkerRuntime {
            override suspend fun fetchAndProcessExact(messageId: String) = RelayWorkerExecutionResult.COMPLETE
            override suspend fun drainFiniteBatch() = RelayWorkerExecutionResult.COMPLETE
        }
        assertSame(runtime, RelayWorkerRuntimeAvailability.Ready(runtime).runtime)
    }

    @Test
    fun workerOwnsSchedulingButNoMessageCustodyOrAckJournal() {
        val source = sourceFile().readText()
        val forbidden = listOf(
            "MessageStore",
            "RelayInboxReconciler",
            "pendingAcks",
            "enqueueAck",
            "clearAcks",
            "relay_inbox",
            "relay_ack_outbox",
            "stageBatchItem",
            "fetchRelayBatch",
            "ackRelayMessages",
            "DeliveryOutcome",
            "scheduleAfterDeferredQuiet",
            "remainingDeferredQuietDelay",
            "acceptedAt",
            "RetryLater",
        )

        forbidden.forEach { token -> assertFalse("worker retained obsolete lifecycle token $token", source.contains(token)) }
        assertTrue(source.contains("setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)"))
        assertTrue(source.contains("ExistingWorkPolicy.KEEP"))
        assertTrue(source.contains("RelayDrainWorker.enqueueNormal(context)"))
        assertTrue(
            source.contains(
                "RelayWorkerRuntimeAvailability.Unavailable -> ListenableWorker.Result.success()",
            ),
        )
    }

    @Test
    fun applicationBridgeExposesNoGraphRoomTransportOrPeerCoreType() {
        val exposed = listOf(RelayWorkerRuntime::class.java, RelayWorkerRuntimeProvider::class.java)
            .flatMap { type ->
                type.declaredMethods.flatMap { method ->
                    listOf(method.genericReturnType.typeName) + method.genericParameterTypes.map { it.typeName }
                }
            }
            .joinToString("\n")

        listOf("AppGraph", "Room", "Dao", "BrokerClient", "peer.channel").forEach { token ->
            assertFalse("worker bridge leaked $token: $exposed", exposed.contains(token))
        }
    }

    private fun sourceFile(): File = listOf(
        File("src/main/java/net/extrawdw/apps/notisync/work/RelayWorkers.kt"),
        File("app/src/main/java/net/extrawdw/apps/notisync/work/RelayWorkers.kt"),
    ).firstOrNull(File::isFile).also {
        assertNotNull("RelayWorkers production source is unavailable", it)
    }!!
}

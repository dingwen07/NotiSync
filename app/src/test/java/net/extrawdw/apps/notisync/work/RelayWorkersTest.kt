package net.extrawdw.apps.notisync.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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
}

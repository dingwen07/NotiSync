package net.extrawdw.notisync.daemon.peer.runtime

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import net.extrawdw.notisync.daemon.LocalSessionRegistry
import net.extrawdw.notisync.daemon.ProcessIdentityResolver
import net.extrawdw.notisync.daemon.peer.storage.FileKeyMaterialProvider
import net.extrawdw.notisync.daemon.storage.DaemonStorageLayout
import net.extrawdw.notisync.daemon.storage.StorageTestSupport
import net.extrawdw.notisync.desktop.config.NotisyncdConfig
import net.extrawdw.notisync.localapi.DeviceAction
import net.extrawdw.notisync.localapi.DeviceActionRequest
import net.extrawdw.notisync.localapi.DeviceClassification
import net.extrawdw.notisync.localapi.DeviceListResponse
import net.extrawdw.notisync.localapi.DeviceTrustStatus
import net.extrawdw.notisync.localapi.PairingAcceptRequest
import net.extrawdw.notisync.localapi.PairingInspectRequest
import net.extrawdw.notisync.peer.ports.AuthTokenRepository
import net.extrawdw.notisync.peer.ports.MessageDedupRepository
import net.extrawdw.notisync.peer.ports.TrustPersistence
import net.extrawdw.notisync.protocol.IntegrityVerificationResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopPeerRuntimeTest : StorageTestSupport() {
    @Test
    fun `pairing payload reads the current configured device name`() {
        var configuredName = "Initial Hostname"
        val changing = runtime("changing-name") {
            testConfig(configuredName)
        }
        val inspector = runtime("name-inspector", "Inspector")
        try {
            assertEquals(
                "Initial Hostname",
                inspector.inspectPairing(PairingInspectRequest(changing.pairingPayload().payload)).name,
            )

            configuredName = "Configured Workstation"
            assertEquals(
                "Configured Workstation",
                inspector.inspectPairing(PairingInspectRequest(changing.pairingPayload().payload)).name,
            )
        } finally {
            changing.close()
            inspector.close()
        }
    }

    @Test
    fun `pairing administration verifies payload and manages trusted device`() {
        val first = runtime("first", "Workstation")
        val second = runtime("second", "Laptop")
        try {
            val payload = second.pairingPayload()
            assertTrue(payload.deepLink.startsWith("https://notisync.apps.extrawdw.net/pair?payload="))

            val inspected = first.inspectPairing(PairingInspectRequest(payload.payload))
            assertEquals("Laptop", inspected.name)
            assertEquals(EXACT_CAPABILITIES, inspected.capabilities)

            val accepted = first.acceptPairing(
                PairingAcceptRequest(payload.payload, DeviceClassification.OWN),
            )
            assertEquals(inspected, accepted)
            val trusted = first.devices().devices.single()
            assertEquals(inspected.clientId, trusted.clientId)
            assertEquals(DeviceClassification.OWN, trusted.classification)
            assertEquals(DeviceTrustStatus.TRUSTED, trusted.trustStatus)
            assertEquals(EXACT_CAPABILITIES, trusted.capabilities)
            assertTrue(trusted.keyAvailable)
            assertTrue(trusted.verified)
            assertEquals(1, trusted.currentEpoch)
            assertTrue(trusted.identityFingerprint != "unavailable")
            assertTrue(!trusted.signingKeyFingerprint.isNullOrBlank())
            assertTrue(!trusted.hpkeKeyFingerprint.isNullOrBlank())
            assertEquals(setOf(DeviceAction.REVOKE), trusted.allowedActions)

            assertThrows(IllegalArgumentException::class.java) {
                first.deviceAction(trusted.clientId, DeviceActionRequest(DeviceAction.APPROVE))
            }
            val revoked = first.deviceAction(
                trusted.clientId,
                DeviceActionRequest(DeviceAction.REVOKE),
            ).devices.single()
            assertEquals(DeviceTrustStatus.REVOKED, revoked.trustStatus)
            assertEquals(setOf(DeviceAction.RESTORE), revoked.allowedActions)

            val restored = first.deviceAction(
                trusted.clientId,
                DeviceActionRequest(DeviceAction.RESTORE),
            ).devices.single()
            assertEquals(DeviceTrustStatus.TRUSTED, restored.trustStatus)
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun `concurrent administration reads stay coherent during a transition`() {
        val first = runtime("concurrent-first", "Workstation")
        val second = runtime("concurrent-second", "Laptop")
        val executor = Executors.newFixedThreadPool(8)
        try {
            val payload = second.pairingPayload().payload
            val accepted = first.acceptPairing(
                PairingAcceptRequest(payload, DeviceClassification.OWN),
            )
            val reads = (1..100).map {
                executor.submit<DeviceTrustStatus> {
                    first.devices().devices.single().trustStatus
                }
            }
            val transition = executor.submit<DeviceListResponse> {
                first.deviceAction(accepted.clientId, DeviceActionRequest(DeviceAction.REVOKE))
            }
            reads.forEach { future ->
                assertTrue(
                    future.get(10, TimeUnit.SECONDS) in
                        setOf(DeviceTrustStatus.TRUSTED, DeviceTrustStatus.REVOKED),
                )
            }
            assertEquals(
                DeviceTrustStatus.REVOKED,
                transition.get(10, TimeUnit.SECONDS).devices.single().trustStatus,
            )
        } finally {
            executor.shutdownNow()
            first.close()
            second.close()
        }
    }

    private fun runtime(directory: String, name: String): DesktopPeerRuntime {
        return runtime(directory) { testConfig(name) }
    }

    private fun runtime(directory: String, configProvider: () -> NotisyncdConfig): DesktopPeerRuntime {
        val layout = DaemonStorageLayout(temporaryDirectory.resolve(directory))
        return DesktopPeerRuntime(
            configProvider = configProvider,
            keyMaterial = FileKeyMaterialProvider(layout),
            trustPersistence = MemoryTrustPersistence(),
            authTokens = MemoryAuthTokens(),
            deduplication = MemoryDeduplication(),
            sessions = LocalSessionRegistry(ProcessIdentityResolver()),
        )
    }

    private fun testConfig(name: String) = NotisyncdConfig(
        brokerUrl = "ws://127.0.0.1:1",
        deviceName = name,
        platformName = "test-desktop",
    )

    private class MemoryTrustPersistence : TrustPersistence {
        private val lock = Any()
        private val values = linkedMapOf<String, String>()

        override fun read(key: String): String? = synchronized(lock) { values[key] }

        override fun write(values: Map<String, String?>) = synchronized(lock) {
            values.forEach { (key, value) ->
                if (value == null) this.values.remove(key) else this.values[key] = value
            }
        }
    }

    private class MemoryAuthTokens : AuthTokenRepository {
        @Volatile private var value: IntegrityVerificationResponse? = null
        override fun load(): IntegrityVerificationResponse? = value
        override fun save(token: IntegrityVerificationResponse?) { value = token }
    }

    private class MemoryDeduplication : MessageDedupRepository {
        private val values = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        override fun seen(messageId: String): Boolean = messageId in values
        override fun record(messageId: String) { values += messageId }
    }

    private companion object {
        val EXACT_CAPABILITIES = setOf(
            "CAPTURE",
            "FOREGROUND_CONNECTION",
            "CAPABILITY_ROUTING_V1",
        )
    }
}

package net.extrawdw.notisync.daemon.peer.runtime

import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import net.extrawdw.notisync.daemon.ApplicationProfilePublicationState
import net.extrawdw.notisync.daemon.ApplicationProfilePublicationStateStore
import net.extrawdw.notisync.daemon.ApplicationReceiveRouter
import net.extrawdw.notisync.daemon.LocalPeer
import net.extrawdw.notisync.daemon.PendingSend
import net.extrawdw.notisync.daemon.ProcessIdentityResolver
import net.extrawdw.notisync.daemon.RegisteredApplicationLookup
import net.extrawdw.notisync.daemon.peer.storage.FileKeyMaterialProvider
import net.extrawdw.notisync.daemon.storage.DaemonStorageLayout
import net.extrawdw.notisync.daemon.storage.StorageTestSupport
import net.extrawdw.notisync.desktop.config.NotisyncdConfig
import net.extrawdw.notisync.desktop.config.NOTISYNCD_PLATFORM_NAME
import net.extrawdw.notisync.localapi.DeviceAction
import net.extrawdw.notisync.localapi.DeviceActionRequest
import net.extrawdw.notisync.localapi.DeviceClassification
import net.extrawdw.notisync.localapi.DeviceListResponse
import net.extrawdw.notisync.localapi.DeviceTrustStatus
import net.extrawdw.notisync.localapi.PairingAcceptRequest
import net.extrawdw.notisync.localapi.PairingInspectRequest
import net.extrawdw.notisync.localapi.ReceiveRequest
import net.extrawdw.notisync.peer.channel.DeliveryOutcome
import net.extrawdw.notisync.peer.channel.Recipients
import net.extrawdw.notisync.peer.channel.SignerSelection
import net.extrawdw.notisync.peer.ports.AuthTokenRepository
import net.extrawdw.notisync.peer.ports.MessageDedupRepository
import net.extrawdw.notisync.peer.ports.TrustPersistence
import net.extrawdw.notisync.peer.pairing.PairingPayloadCodec
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.IntegrityVerificationResponse
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.Urgency
import net.extrawdw.notisync.protocol.crypto.EnvelopeCrypto
import net.extrawdw.notisync.protocol.crypto.RecipientKey
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
    fun `pairing cards read the current generic application capability union`() {
        val advertised = mutableListOf(
            Capability.FOREGROUND_CONNECTION,
            Capability.CAPABILITY_ROUTING_V1,
        )
        val profileState = MemoryProfileState()
        val changing = runtimeHarness(
            directory = "changing-capabilities",
            name = "Workstation",
            capabilitiesProvider = { advertised.toList() },
            profileState = profileState,
        )
        val inspector = runtime("capability-inspector", "Inspector")
        try {
            assertEquals(
                setOf("FOREGROUND_CONNECTION", "CAPABILITY_ROUTING_V1"),
                inspector.inspectPairing(
                    PairingInspectRequest(changing.runtime.pairingPayload().payload),
                ).capabilities,
            )
            val initial = profileState.profilePublicationState()

            advertised.add(0, Capability.CAPTURE)
            advertised += Capability.PUBLISH_RUNS
            changing.runtime.requestProfilePublication()

            assertEquals(
                setOf("CAPTURE", "FOREGROUND_CONNECTION", "CAPABILITY_ROUTING_V1", "PUBLISH_RUNS"),
                inspector.inspectPairing(
                    PairingInspectRequest(changing.runtime.pairingPayload().payload),
                ).capabilities,
            )
            val changed = profileState.profilePublicationState()
            assertEquals(initial.publicationRevision + 1, changed.publicationRevision)
            assertTrue(changed.profileUpdatedAtEpochMillis!! > initial.profileUpdatedAtEpochMillis!!)
            assertEquals(changed.publicationRevision, changed.pendingPublicationRevision)
        } finally {
            changing.close()
            inspector.close()
        }
    }

    @Test
    fun `failed forced profile reannouncement remains pending for the next maintenance retry`() = runBlocking {
        val profileState = MemoryProfileState()
        profileState.updateProfilePublicationState {
            ApplicationProfilePublicationState(
                cardCreatedAtFloorEpochMillis = 10,
                profileFingerprint = "profile-a",
                profileUpdatedAtEpochMillis = 20,
                publicationRevision = 4,
            )
        }
        var attempts = 0

        val failure = runCatching {
            publishDurableProfileIfNeeded(
                profileState = profileState,
                force = true,
                buildProfile = ::testProfile,
                publish = {
                    attempts++
                    assertEquals(4L, profileState.profilePublicationState().pendingPublicationRevision)
                    throw IllegalStateException("broker unavailable")
                },
            )
        }

        assertTrue(failure.exceptionOrNull() is IllegalStateException)
        assertEquals(1, attempts)
        assertEquals(4L, profileState.profilePublicationState().pendingPublicationRevision)

        assertTrue(
            publishDurableProfileIfNeeded(
                profileState = profileState,
                force = false,
                buildProfile = ::testProfile,
                publish = { attempts++ },
            ),
        )
        assertEquals(2, attempts)
        assertEquals(null, profileState.profilePublicationState().pendingPublicationRevision)
    }

    @Test
    fun `profile success clears only the revision that was actually published`() = runBlocking {
        val profileState = MemoryProfileState()
        profileState.updateProfilePublicationState {
            ApplicationProfilePublicationState(
                cardCreatedAtFloorEpochMillis = 10,
                profileFingerprint = "profile-a",
                profileUpdatedAtEpochMillis = 20,
                publicationRevision = 4,
                pendingPublicationRevision = 4,
            )
        }

        assertTrue(
            publishDurableProfileIfNeeded(
                profileState = profileState,
                force = false,
                buildProfile = ::testProfile,
                publish = {
                    profileState.updateProfilePublicationState { current ->
                        current.copy(
                            profileFingerprint = "profile-b",
                            profileUpdatedAtEpochMillis = 21,
                            publicationRevision = 5,
                            pendingPublicationRevision = 5,
                        )
                    }
                },
            ),
        )

        assertEquals(5L, profileState.profilePublicationState().pendingPublicationRevision)
    }

    @Test
    fun `authenticated inbound payload is bridged unchanged to a generic receiver`() {
        val receiver = runtimeHarness("generic-receiver", "Receiver")
        val sender = runtimeHarness("generic-sender", "Sender")
        try {
            receiver.runtime.acceptPairing(
                PairingAcceptRequest(sender.runtime.pairingPayload().payload, DeviceClassification.OWN),
            )
            val identityResolver = ProcessIdentityResolver()
            val pid = ProcessHandle.current().pid()
            val handle = receiver.router.open(
                LocalPeer(
                    uid = identityResolver.currentUid(temporaryDirectory),
                    pid = pid,
                    startTime = requireNotNull(identityResolver.startTime(pid)),
                ),
                ReceiveRequest("app", messageTypes = listOf(MessageType.NOTIFICATION)),
            )
            val opaqueBody = byteArrayOf(0x01, 0x02, 0x7f)
            val envelope = EnvelopeCrypto.seal(
                signer = sender.keyMaterial.currentOperationalSigner(),
                typ = MessageType.NOTIFICATION,
                bodyPlaintext = opaqueBody,
                recipients = listOf(
                    RecipientKey(
                        ClientId(requireNotNull(receiver.runtime.clientId)),
                        receiver.keyMaterial.hpkePublicKeyset(1),
                        recipientEpoch = 1,
                    ),
                ),
                messageId = "generic-inbound",
                seq = 1,
                createdAt = 1,
            )

            assertEquals(DeliveryOutcome.HANDLED, receiver.runtime.secureChannel.deliver(envelope))
            val record = requireNotNull(handle.pollRecord())
            assertEquals("generic-inbound", record.envelopeId)
            assertEquals(MessageType.NOTIFICATION, record.messageType)
            assertTrue(record.senderOwnDevice == true)
            assertTrue(Base64.getDecoder().decode(requireNotNull(record.body)).contentEquals(opaqueBody))
        } finally {
            receiver.close()
            sender.close()
        }
    }

    @Test
    fun `strict daemon sender rejects empty or non-consecutive batches before transport`() {
        val runtime = runtime("strict-validation", "Workstation")
        try {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { runtime.send(emptyList()) { } }
            }
            val first = pending("one", MessageType.DATA_SYNC, Urgency.NORMAL)
            val second = pending("two", MessageType.NOTIFICATION, Urgency.HIGH)
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { runtime.send(listOf(first, second)) { } }
            }
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `pairing administration verifies payload and manages trusted device`() {
        val first = runtime("first", "Workstation")
        val second = runtime("second", "Laptop")
        try {
            val payload = second.pairingPayload()
            assertTrue(payload.deepLink.startsWith("https://notisync.apps.extrawdw.net/pair?payload="))
            val announcedCard = PairingPayloadCodec(ClientId(requireNotNull(first.clientId)))
                .decode(payload.payload).getOrThrow().card
            assertEquals(NOTISYNCD_PLATFORM_NAME, announcedCard.platform)

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
            assertEquals(NOTISYNCD_PLATFORM_NAME, trusted.platform)
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
        return runtimeHarness(directory, configProvider = configProvider).runtime
    }

    private fun runtimeHarness(
        directory: String,
        name: String? = null,
        configProvider: (() -> NotisyncdConfig)? = null,
        capabilitiesProvider: () -> List<Capability> = { EXACT_CAPABILITY_ENUMS },
        profileState: ApplicationProfilePublicationStateStore = MemoryProfileState(),
    ): RuntimeHarness {
        val layout = DaemonStorageLayout(temporaryDirectory.resolve(directory))
        val keys = FileKeyMaterialProvider(layout)
        val router = ApplicationReceiveRouter(
            applications = RegisteredApplicationLookup { it == "app" },
            identityResolver = ProcessIdentityResolver(),
        )
        val runtime = DesktopPeerRuntime(
            configProvider = configProvider ?: { testConfig(requireNotNull(name)) },
            keyMaterial = keys,
            trustPersistence = MemoryTrustPersistence(),
            authTokens = MemoryAuthTokens(),
            deduplication = MemoryDeduplication(),
            receiveRouter = router,
            capabilitiesProvider = capabilitiesProvider,
            profileState = profileState,
        )
        return RuntimeHarness(runtime, router, keys)
    }

    private fun pending(id: String, type: MessageType, urgency: Urgency) = PendingSend(
        messageId = id,
        acceptedAtEpochMillis = 1,
        applicationId = "app",
        messageType = type,
        body = byteArrayOf(1),
        scope = Recipients.OwnMesh,
        urgency = urgency,
        signWith = SignerSelection.OPERATIONAL,
    )

    private fun testConfig(name: String) = NotisyncdConfig(
        brokerUrl = "ws://127.0.0.1:1",
        deviceName = name,
    )

    private fun testProfile(updatedAt: Long) = net.extrawdw.notisync.protocol.ProfileUpdate(
        clientId = ClientId("desktop"),
        displayName = "Desktop",
        platform = NOTISYNCD_PLATFORM_NAME,
        capabilities = EXACT_CAPABILITY_ENUMS,
        updatedAt = updatedAt,
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

    private class MemoryProfileState : ApplicationProfilePublicationStateStore {
        private var value = ApplicationProfilePublicationState()

        override fun profilePublicationState(): ApplicationProfilePublicationState = synchronized(this) { value }

        override fun updateProfilePublicationState(
            transform: (ApplicationProfilePublicationState) -> ApplicationProfilePublicationState,
        ): ApplicationProfilePublicationState = synchronized(this) {
            transform(value).also { value = it }
        }
    }

    private data class RuntimeHarness(
        val runtime: DesktopPeerRuntime,
        val router: ApplicationReceiveRouter,
        val keyMaterial: FileKeyMaterialProvider,
    ) : AutoCloseable {
        override fun close() = runtime.close()
    }

    private companion object {
        val EXACT_CAPABILITY_ENUMS = listOf(
            Capability.FOREGROUND_CONNECTION,
            Capability.CAPABILITY_ROUTING_V1,
        )
        val EXACT_CAPABILITIES = EXACT_CAPABILITY_ENUMS.mapTo(linkedSetOf()) { it.name }
    }
}

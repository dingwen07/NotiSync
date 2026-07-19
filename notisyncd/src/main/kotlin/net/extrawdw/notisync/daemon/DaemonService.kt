package net.extrawdw.notisync.daemon

import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import net.extrawdw.notisync.desktop.config.NotisyncdConfig
import net.extrawdw.notisync.desktop.config.NotisyncdConfigStore
import net.extrawdw.notisync.desktop.config.NOTISYNCD_PLATFORM_NAME
import net.extrawdw.notisync.localapi.AcceptedResponse
import net.extrawdw.notisync.localapi.ActionSendRequest
import net.extrawdw.notisync.localapi.CreateSessionRequest
import net.extrawdw.notisync.localapi.DaemonConfigPatch
import net.extrawdw.notisync.localapi.DaemonConfigView
import net.extrawdw.notisync.localapi.DaemonConnectionState
import net.extrawdw.notisync.localapi.DaemonStatus
import net.extrawdw.notisync.localapi.DeviceActionRequest
import net.extrawdw.notisync.localapi.DeviceListResponse
import net.extrawdw.notisync.localapi.DismissalRequest
import net.extrawdw.notisync.localapi.EventAckRequest
import net.extrawdw.notisync.localapi.EventCompletionRequest
import net.extrawdw.notisync.localapi.LocalEvent
import net.extrawdw.notisync.localapi.NotificationRequest
import net.extrawdw.notisync.localapi.RunStateRequest
import net.extrawdw.notisync.localapi.PairingAcceptRequest
import net.extrawdw.notisync.localapi.PairingCandidate
import net.extrawdw.notisync.localapi.PairingInspectRequest
import net.extrawdw.notisync.localapi.PairingPayloadResponse
import net.extrawdw.notisync.localapi.QuarantineActionRequest
import net.extrawdw.notisync.localapi.SessionResponse

interface PeerAdministration {
    val clientId: String?
    val connectionState: DaemonConnectionState
    val trustStoreQuarantined: Boolean
    val statusMessage: String?

    fun pairingPayload(): PairingPayloadResponse
    fun inspectPairing(request: PairingInspectRequest): PairingCandidate
    fun acceptPairing(request: PairingAcceptRequest): PairingCandidate
    fun devices(): DeviceListResponse
    fun deviceAction(clientId: String, request: DeviceActionRequest): DeviceListResponse
    fun quarantineAction(request: QuarantineActionRequest): DeviceListResponse
}

interface GenericMeshControl {
    suspend fun sendDismissal(request: DismissalRequest, sourceKey: String): String
    suspend fun sendAction(request: ActionSendRequest, sourceKey: String): String
}

class PeerUnavailableException(message: String) : RuntimeException(message)

class UnavailablePeerAdministration(
    override val statusMessage: String = "peer identity is not initialized",
) : PeerAdministration {
    override val clientId: String? = null
    override val connectionState: DaemonConnectionState = DaemonConnectionState.CONNECTING
    override val trustStoreQuarantined: Boolean = false

    override fun pairingPayload(): PairingPayloadResponse = unavailable()
    override fun inspectPairing(request: PairingInspectRequest): PairingCandidate = unavailable()
    override fun acceptPairing(request: PairingAcceptRequest): PairingCandidate = unavailable()
    override fun devices(): DeviceListResponse = DeviceListResponse(emptyList())
    override fun deviceAction(clientId: String, request: DeviceActionRequest): DeviceListResponse = unavailable()
    override fun quarantineAction(request: QuarantineActionRequest): DeviceListResponse = unavailable()

    private fun <T> unavailable(): T = throw PeerUnavailableException(statusMessage)
}

class DaemonService(
    private val configStore: NotisyncdConfigStore,
    private val sessions: LocalSessionRegistry,
    private val dispatcher: NotificationDispatcher,
    private val runDispatcher: RunDispatcher? = null,
    peerAdministration: PeerAdministration = UnavailablePeerAdministration(),
    private val genericControl: GenericMeshControl? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val version: String = "0.1.0",
) {
    private val peer = AtomicReference(peerAdministration)
    private val stopping = AtomicBoolean(false)

    fun installPeer(value: PeerAdministration) {
        peer.set(value)
    }

    fun status(): DaemonStatus {
        val config = configStore.load()
        val current = peer.get()
        return DaemonStatus(
            version = version,
            clientId = current.clientId,
            deviceName = config.deviceName,
            connectionState = if (stopping.get()) DaemonConnectionState.STOPPED else current.connectionState,
            brokerUrl = config.brokerUrl,
            capabilities = DAEMON_CAPABILITIES,
            trustStoreQuarantined = current.trustStoreQuarantined,
            message = current.statusMessage,
        )
    }

    fun config(): DaemonConfigView = configStore.load().view()

    fun patchConfig(patch: DaemonConfigPatch): DaemonConfigView {
        val old = configStore.load()
        val updated = old.copy(
            brokerUrl = patch.brokerUrl ?: old.brokerUrl,
            deviceName = patch.deviceName ?: old.deviceName,
            automaticallyApplyTrustedDeviceTables = patch.automaticallyApplyTrustedDeviceTables
                ?: old.automaticallyApplyTrustedDeviceTables,
            logLevel = patch.logLevel ?: old.logLevel,
            websocketPingSeconds = patch.websocketPingSeconds ?: old.websocketPingSeconds,
        ).validate()
        configStore.save(updated)
        return updated.view()
    }

    fun pairingPayload(): PairingPayloadResponse = peer.get().pairingPayload()
    fun inspectPairing(request: PairingInspectRequest): PairingCandidate = peer.get().inspectPairing(request)
    fun acceptPairing(request: PairingAcceptRequest): PairingCandidate = peer.get().acceptPairing(request)
    fun devices(): DeviceListResponse = peer.get().devices()
    fun deviceAction(clientId: String, request: DeviceActionRequest): DeviceListResponse =
        peer.get().deviceAction(clientId, request)
    fun quarantineAction(request: QuarantineActionRequest): DeviceListResponse =
        peer.get().quarantineAction(request)

    fun createSession(peer: LocalPeer, request: CreateSessionRequest): SessionResponse = sessions.create(peer, request)

    fun closeSession(peer: LocalPeer, bearer: String?, sessionId: String) = sessions.close(sessionId, bearer, peer)

    fun postNotification(peer: LocalPeer, bearer: String?, request: NotificationRequest): AcceptedResponse =
        dispatcher.accept(request, bearer, peer)

    fun postRunState(peer: LocalPeer, bearer: String?, request: RunStateRequest): AcceptedResponse =
        (runDispatcher ?: throw PeerUnavailableException("Run transport is not initialized"))
            .accept(request, bearer, peer)

    suspend fun postDismissal(peer: LocalPeer, bearer: String?, request: DismissalRequest): AcceptedResponse {
        val session = sessions.authorizeNotificationGeneration(
            request.sessionId,
            request.generation,
            bearer,
            peer,
        )
        val control = genericControl ?: throw PeerUnavailableException("mesh control is not initialized")
        val id = control.sendDismissal(request, session.sourceKey)
        return AcceptedResponse(id, clock.millis())
    }

    suspend fun postAction(peer: LocalPeer, bearer: String?, request: ActionSendRequest): AcceptedResponse {
        val session = sessions.authorize(request.sessionId, bearer, peer)
        val control = genericControl ?: throw PeerUnavailableException("mesh control is not initialized")
        val id = control.sendAction(request, session.sourceKey)
        return AcceptedResponse(id, clock.millis())
    }

    fun awaitEvent(peer: LocalPeer, bearer: String?, sessionId: String, waitMillis: Long): LocalEvent? =
        sessions.awaitEvent(sessionId, bearer, peer, waitMillis)

    fun acknowledgeEvent(peer: LocalPeer, bearer: String?, eventId: String, request: EventAckRequest) {
        sessions.acknowledge(request.sessionId, eventId, bearer, peer)
    }

    suspend fun completeEvent(
        peer: LocalPeer,
        bearer: String?,
        eventId: String,
        request: EventCompletionRequest,
    ) {
        (runDispatcher ?: throw PeerUnavailableException("Run transport is not initialized"))
            .complete(eventId, request, bearer, peer)
    }

    fun requestShutdown() {
        if (stopping.compareAndSet(false, true)) sessions.shutdownEvents()
    }

    fun isStopping(): Boolean = stopping.get()

    companion object {
        val DAEMON_CAPABILITIES = setOf(
            "CAPTURE",
            "FOREGROUND_CONNECTION",
            "CAPABILITY_ROUTING_V1",
        )
    }
}

private fun NotisyncdConfig.view() = DaemonConfigView(
    brokerUrl = brokerUrl,
    deviceName = deviceName,
    platformName = NOTISYNCD_PLATFORM_NAME,
    automaticallyApplyTrustedDeviceTables = automaticallyApplyTrustedDeviceTables,
    logLevel = logLevel,
    websocketPingSeconds = websocketPingSeconds,
)

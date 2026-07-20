package net.extrawdw.notisync.daemon

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.encodeToString
import net.extrawdw.notisync.daemon.logging.DaemonLogger
import net.extrawdw.notisync.desktop.config.NOTISYNCD_PLATFORM_NAME
import net.extrawdw.notisync.desktop.config.NotisyncdConfig
import net.extrawdw.notisync.desktop.config.NotisyncdConfigStore
import net.extrawdw.notisync.localapi.ApplicationEventCompletionRequest
import net.extrawdw.notisync.localapi.ApplicationListResponse
import net.extrawdw.notisync.localapi.ApplicationRegistrationRequest
import net.extrawdw.notisync.localapi.ApplicationView
import net.extrawdw.notisync.localapi.DaemonConfigPatch
import net.extrawdw.notisync.localapi.DaemonConfigView
import net.extrawdw.notisync.localapi.DaemonConnectionState
import net.extrawdw.notisync.localapi.DaemonStatus
import net.extrawdw.notisync.localapi.DeviceActionRequest
import net.extrawdw.notisync.localapi.DeviceListResponse
import net.extrawdw.notisync.localapi.PairingAcceptRequest
import net.extrawdw.notisync.localapi.PairingCandidate
import net.extrawdw.notisync.localapi.PairingInspectRequest
import net.extrawdw.notisync.localapi.PairingPayloadResponse
import net.extrawdw.notisync.localapi.QuarantineActionRequest
import net.extrawdw.notisync.localapi.ReceiveRequest
import net.extrawdw.notisync.localapi.SendAccepted
import net.extrawdw.notisync.localapi.SendRequest

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

/** Generic local application bridge plus the existing administrative surface. */
class DaemonService(
    private val configStore: NotisyncdConfigStore,
    private val applications: ApplicationRegistry,
    private val receiver: ApplicationReceiveRouter,
    private val sendResolver: GenericSendResolver,
    private val sendDispatcher: GenericSendDispatcher,
    peerAdministration: PeerAdministration = UnavailablePeerAdministration(),
    private val logger: DaemonLogger = DaemonLogger("WARN"),
    private val version: String = "0.1.0",
    private val onConfigChanged: (NotisyncdConfig, NotisyncdConfig) -> Unit = { _, _ -> },
    private val onMaterialProfileChanged: () -> Unit = {},
) {
    private data class CompletionKey(val applicationId: String, val envelopeId: String)

    private val peer = AtomicReference(peerAdministration)
    private val stopping = AtomicBoolean(false)
    /** Serializes app-level ACK with completion and remembers successful completions for this daemon life. */
    private val eventLock = Any()
    private val completedEvents = mutableSetOf<CompletionKey>()

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
            capabilities = applications.effectiveCapabilities().mapTo(linkedSetOf()) { it.name },
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
        onConfigChanged(old, updated)
        if (old.deviceName != updated.deviceName) onMaterialProfileChanged()
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

    fun registerApplication(
        applicationId: String,
        registration: ApplicationRegistrationRequest,
    ): ApplicationView = synchronized(eventLock) {
        val result = applications.register(applicationId, registration)
        logger.info(
            "Registered application $applicationId " +
                "(${result.application.capabilities.joinToString { it.name }})",
        )
        if (result.capabilitiesChanged) onMaterialProfileChanged()
        result.application
    }

    fun applications(): ApplicationListResponse = applications.list()

    fun removeApplication(applicationId: String) = synchronized(eventLock) {
        val before = applications.effectiveCapabilities()
        val removed = applications.delete(applicationId)
        sendDispatcher.removeApplication(applicationId)
        receiver.removeApplication(applicationId)
        completedEvents.removeIf { it.applicationId == applicationId }
        if (removed) logger.info("Removed application $applicationId")
        if (before != applications.effectiveCapabilities()) onMaterialProfileChanged()
    }

    fun acceptSends(requests: List<SendRequest>): List<SendAccepted> = synchronized(eventLock) {
        sendDispatcher.accepted(sendResolver.resolveAll(requests))
    }

    fun openReceive(
        peer: LocalPeer,
        request: ReceiveRequest,
    ): ApplicationReceiveRouter.ReceiverHandle = synchronized(eventLock) {
        val handle = receiver.open(peer, request)
        logger.info(
            "Registered receive interest for application ${request.applicationId}: " +
                net.extrawdw.notisync.localapi.LocalApiJson.encodeToString(request),
        )
        handle
    }

    fun unregisterReceive(peer: LocalPeer, request: ReceiveRequest) = synchronized(eventLock) {
        if (receiver.unregister(peer, request)) {
            logger.info("Unregistered receive interest for application ${request.applicationId}")
        }
    }

    fun acknowledgeEvent(applicationId: String, envelopeId: String) = synchronized(eventLock) {
        requireApplication(applicationId)
        if (receiver.ack(applicationId, envelopeId)) {
            logger.info("Acknowledged $envelopeId for application $applicationId")
        }
    }

    /** Atomically accept response sends before removing the application-level pending reference. */
    fun completeEvent(envelopeId: String, completion: ApplicationEventCompletionRequest) = synchronized(eventLock) {
        requireApplication(completion.applicationId)
        val key = CompletionKey(completion.applicationId, envelopeId)
        if (key in completedEvents) return@synchronized
        if (!receiver.hasPending(completion.applicationId, envelopeId)) {
            throw LocalConflictException("event is not pending for application ${completion.applicationId}")
        }
        require(completion.sends.all { it.applicationId == completion.applicationId }) {
            "completion sends must use the acknowledging applicationId"
        }
        if (completion.sends.isNotEmpty()) acceptSends(completion.sends)
        check(receiver.ack(completion.applicationId, envelopeId)) {
            "pending application event disappeared during completion"
        }
        completedEvents += key
        logger.info(
            "Completed $envelopeId for application ${completion.applicationId} " +
                "with ${completion.sends.size} response send(s)",
        )
    }

    fun requestShutdown() {
        stopping.set(true)
    }

    fun isStopping(): Boolean = stopping.get()

    private fun requireApplication(applicationId: String) {
        if (applications.find(applicationId) == null) throw ApplicationNotRegisteredException(applicationId)
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

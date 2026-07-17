package net.extrawdw.notisync.daemon.peer.runtime

import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.extrawdw.notisync.daemon.GenericMeshControl
import net.extrawdw.notisync.daemon.LocalSessionRegistry
import net.extrawdw.notisync.daemon.NotificationMeshSender
import net.extrawdw.notisync.daemon.PeerAdministration
import net.extrawdw.notisync.daemon.PeerUnavailableException
import net.extrawdw.notisync.daemon.SecureChannelNotificationSender
import net.extrawdw.notisync.desktop.config.NotisyncdConfig
import net.extrawdw.notisync.localapi.ActionSendRequest
import net.extrawdw.notisync.localapi.DaemonConnectionState
import net.extrawdw.notisync.localapi.DeviceAction
import net.extrawdw.notisync.localapi.DeviceActionRequest
import net.extrawdw.notisync.localapi.DeviceClassification
import net.extrawdw.notisync.localapi.DeviceListResponse
import net.extrawdw.notisync.localapi.DeviceTrustStatus
import net.extrawdw.notisync.localapi.DeviceView
import net.extrawdw.notisync.localapi.DismissalRequest
import net.extrawdw.notisync.localapi.PairingAcceptRequest
import net.extrawdw.notisync.localapi.PairingCandidate
import net.extrawdw.notisync.localapi.PairingInspectRequest
import net.extrawdw.notisync.localapi.PairingPayloadResponse
import net.extrawdw.notisync.localapi.QuarantineAction
import net.extrawdw.notisync.localapi.QuarantineActionRequest
import net.extrawdw.notisync.peer.channel.ChannelLogger
import net.extrawdw.notisync.peer.channel.DeliveryOutcome
import net.extrawdw.notisync.peer.channel.InboundMessage
import net.extrawdw.notisync.peer.channel.MessageDedup
import net.extrawdw.notisync.peer.channel.Recipients
import net.extrawdw.notisync.peer.channel.RetryableDeliveryException
import net.extrawdw.notisync.peer.channel.SecureChannel
import net.extrawdw.notisync.peer.channel.safeToAck
import net.extrawdw.notisync.peer.foundation.FoundationEngine
import net.extrawdw.notisync.peer.foundation.TrustPeerDirectory
import net.extrawdw.notisync.peer.pairing.PairingDeepLinks
import net.extrawdw.notisync.peer.pairing.KeyEpochStatus
import net.extrawdw.notisync.peer.pairing.PairingPayloadCodec
import net.extrawdw.notisync.peer.ports.AuthTokenRepository
import net.extrawdw.notisync.peer.ports.IncomingTrustPolicy
import net.extrawdw.notisync.peer.ports.KeyMaterialProvider
import net.extrawdw.notisync.peer.ports.MessageDedupRepository
import net.extrawdw.notisync.peer.ports.NoIntegrityEvidenceProvider
import net.extrawdw.notisync.peer.ports.PeerTelemetry
import net.extrawdw.notisync.peer.ports.TrustPersistence
import net.extrawdw.notisync.peer.transport.AuthTokenStore
import net.extrawdw.notisync.peer.transport.BrokerClient
import net.extrawdw.notisync.peer.transport.DeliveryMode
import net.extrawdw.notisync.peer.trust.RosterDevice
import net.extrawdw.notisync.peer.trust.TrustState
import net.extrawdw.notisync.peer.trust.TrustStore
import net.extrawdw.notisync.protocol.ActionEvent
import net.extrawdw.notisync.protocol.ActionKind
import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.ClientCard
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.DismissEvent
import net.extrawdw.notisync.protocol.LiveDeliveryDisposition
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.ProfileUpdate
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.SignedType
import net.extrawdw.notisync.protocol.TrustStatus
import net.extrawdw.notisync.protocol.Urgency

/**
 * The desktop NotiSync peer graph.
 *
 * This class deliberately owns no file paths or configuration format. The daemon supplies repositories
 * and a [configProvider], allowing the initial private-file implementations to be replaced by a system
 * keychain or a different configuration parser without changing the secure-channel composition.
 *
 * The runtime is also the notification sender installed in [net.extrawdw.notisync.daemon.NotificationDispatcher]
 * and the administration surface installed in [net.extrawdw.notisync.daemon.DaemonService]. All of those
 * references lead to this single [SecureChannel], preserving one sender sequence and one durable dedup domain.
 */
class DesktopPeerRuntime(
    private val configProvider: () -> NotisyncdConfig,
    private val keyMaterial: KeyMaterialProvider,
    trustPersistence: TrustPersistence,
    authTokens: AuthTokenRepository,
    deduplication: MessageDedupRepository,
    private val sessions: LocalSessionRegistry,
    parentScope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    private val clock: Clock = Clock.systemUTC(),
    private val channelLogger: ChannelLogger = ChannelLogger { message ->
        System.err.println("notisyncd: $message")
    },
    telemetry: PeerTelemetry = PeerTelemetry.None,
    private val healthPollMillis: Long = DEFAULT_HEALTH_POLL_MILLIS,
    private val antiEntropyMillis: Long = DEFAULT_ANTI_ENTROPY_MILLIS,
) : PeerAdministration, GenericMeshControl, AutoCloseable {
    private val lifecycle = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(
        parentScope.coroutineContext + lifecycle + CoroutineName("notisyncd-peer"),
    )
    private val started = AtomicBoolean(false)
    private val state = AtomicReference(DaemonConnectionState.STARTING)
    private val webSocketConnected = AtomicBoolean(false)
    private val connectionMessage = AtomicReference<String?>(null)
    private val trustMessage = AtomicReference<String?>(null)

    private val trustMutationLock = ReentrantLock()
    private val trustStore = TrustStore(ClassifiedTrustPersistence(trustPersistence), keyMaterial.identity)
    /**
     * Foundation receives the state through this serialization adapter. TrustStore publishes immutable
     * snapshots safely, but its read-copy-write mutations are intentionally synchronous and otherwise could
     * lose an update when a UDS administration call races a WebSocket trust table.
     */
    private val trustState: TrustState = FoundationTrustState(trustStore, trustMutationLock)
    private val directory = TrustPeerDirectory(trustState)
    private val pairing = PairingPayloadCodec(keyMaterial.identity.clientId)
    private val createdAt = clock.millis()

    private val broker = BrokerClient(
        signer = keyMaterial.identity,
        operationalSigner = keyMaterial::currentOperationalSigner,
        baseUrlProvider = { configProvider().brokerUrl },
        integrity = NoIntegrityEvidenceProvider,
        clientKeyEpochProvider = keyMaterial::currentKeyEpoch,
        tokenStore = RepositoryAuthTokenStore(authTokens),
        scope = scope,
        telemetry = telemetry,
        webSocketPingSeconds = configProvider().websocketPingSeconds.toLong(),
        onWebSocketConnectionChanged = { connected ->
            webSocketConnected.set(connected)
            if (connected) {
                state.set(DaemonConnectionState.CONNECTED)
                connectionMessage.set(null)
            } else if (state.get() != DaemonConnectionState.UNSUPPORTED_INTEGRITY) {
                state.set(DaemonConnectionState.BACKING_OFF)
                connectionMessage.set("WebSocket disconnected; reconnecting")
            }
        },
    )

    private lateinit var foundation: FoundationEngine

    val secureChannel = SecureChannel(
        signer = keyMaterial.identity,
        operationalSigner = keyMaterial::currentOperationalSigner,
        myHpkePrivate = keyMaterial::hpkePrivateKeyset,
        transport = broker,
        directory = directory,
        log = channelLogger,
        onUnresolvedSender = { sender ->
            if (::foundation.isInitialized) {
                scope.launch { runCatching { foundation.onUnresolvedSender(sender) } }
            }
        },
        dedup = RepositoryMessageDedup(deduplication),
        now = clock::millis,
        telemetry = telemetry,
    )

    /** Install this adapter in [net.extrawdw.notisync.daemon.NotificationDispatcher]. */
    val notificationMeshSender: NotificationMeshSender = SecureChannelNotificationSender(secureChannel)

    init {
        require(healthPollMillis > 0) { "healthPollMillis must be positive" }
        require(antiEntropyMillis > 0) { "antiEntropyMillis must be positive" }

        // The signed trust section owns the monotonic self floor. Reconcile it with the durable active key
        // before either the broker or a peer sees an envelope from this process.
        trustState.advanceSelfEpoch(keyMaterial.currentOperationalSigner().signerEpoch)

        foundation = FoundationEngine(
            channel = secureChannel,
            trust = trustState,
            scope = scope,
            onTrustPrompt = { subject, prompt, introducedBy ->
                trustMessage.set(
                    "Trust decision required for ${subject.shortForm()} ($prompt), introduced by $introducedBy",
                )
            },
            onAsset = { _, _ -> Unit },
            onFilter = { _, _ -> Unit },
            onNotificationSync = { _, _ -> Unit },
            incomingTrustPolicy = IncomingTrustPolicy { change ->
                configProvider().automaticallyApplyTrustedDeviceTables &&
                    change.senderIsTrustedOwnDevice
            },
            selfKeyEpoch = keyMaterial::currentKeyEpoch,
            fetchKeyEpoch = broker::fetchKeyEpoch,
            now = clock::millis,
        )
        foundation.register()

        // notisyncd has CAPTURE but no DISPLAY. A misrouted notification is consumed and ignored so the
        // relay does not retry it forever; ACTION and DISMISSAL are the only application messages it handles.
        secureChannel.onMessage(MessageType.NOTIFICATION, ::onUnsupportedNotification)
        secureChannel.onMessage(MessageType.ACTION, ::onAction)
        secureChannel.onMessage(MessageType.DISMISSAL, ::onDismissal)
    }

    override val clientId: String = keyMaterial.identity.clientId.value
    override val connectionState: DaemonConnectionState get() = state.get()
    override val trustStoreQuarantined: Boolean get() = trustStore.quarantined.value
    override val statusMessage: String?
        get() = trustMessage.get() ?: connectionMessage.get()

    /** Start the permanent reconnecting WebSocket and broker/foundation anti-entropy loop. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        state.set(DaemonConnectionState.CONNECTING)
        scope.launch(CoroutineName("notisyncd-websocket")) { runLiveDeliveryForever() }
        scope.launch(CoroutineName("notisyncd-maintenance")) { runMaintenance() }
    }

    override fun pairingPayload(): PairingPayloadResponse {
        val payload = pairing.encode(buildClientCard(), keyMaterial.currentKeyEpoch())
        return PairingPayloadResponse(payload, PairingDeepLinks.create(payload))
    }

    override fun inspectPairing(request: PairingInspectRequest): PairingCandidate =
        inspectCandidate(request.payload)

    override fun acceptPairing(request: PairingAcceptRequest): PairingCandidate {
        val candidate = inspectCandidate(request.payload)
        val delivery = pairing.decode(request.payload).getOrElse {
            throw IllegalArgumentException("invalid pairing payload: ${it.message}", it)
        }
        val ownDevice = request.classification == DeviceClassification.OWN
        trustMutationLock.withLock {
            require(trustStore.addLocal(delivery.cardBlob, clock.millis(), ownDevice)) {
                "pairing card verification failed"
            }
            // A stripped QR key-epoch is verified against the now-pinned card identity. If it is absent or
            // invalid the card remains keyless and Foundation's broker convergence repairs it later.
            delivery.epochBlob?.let { trustStore.applyKeyEpoch(delivery.card.clientId, it) }
        }
        trustMessage.set(null)
        scope.launch {
            runCatching { broker.publishKeyEpoch(keyMaterial.currentKeyEpoch()) }
            runCatching { foundation.broadcastTrust() }
            runCatching { foundation.refetchKeyEpoch(delivery.card.clientId) }
        }
        return candidate
    }

    override fun devices(): DeviceListResponse = trustMutationLock.withLock {
        val quarantined = trustStore.quarantined.value
        DeviceListResponse(trustStore.roster.value.map { it.toApi(quarantined) })
    }

    override fun deviceAction(clientId: String, request: DeviceActionRequest): DeviceListResponse =
        trustMutationLock.withLock {
            val id = ClientId(clientId)
            val before = trustStore.statusOf(id)
                ?: throw IllegalArgumentException("unknown device $clientId")
            val rosterDevice = trustStore.roster.value.first { it.clientId == id }
            require(
                request.action in rosterDevice.allowedActions(clock.millis()) ||
                    (before == TrustStatus.PENDING_REVOKE && request.action == DeviceAction.DECLINE_REVOKE),
            ) { "${request.action} is not valid for a $before device" }
            val shouldBroadcast = when (request.action) {
                DeviceAction.APPROVE -> trustStore.approveTrust(id, clock.millis())
                DeviceAction.REJECT -> trustStore.rejectTrust(id, clock.millis())
                DeviceAction.REVOKE -> trustStore.revokeLocal(id, clock.millis())
                DeviceAction.CONFIRM_REVOKE -> trustStore.confirmRevoke(id, clock.millis())
                DeviceAction.DECLINE_REVOKE, DeviceAction.KEEP -> trustStore.keepTrusted(id, clock.millis())
                DeviceAction.RESTORE -> trustStore.restoreTrust(id, clock.millis())
                DeviceAction.PURGE -> trustStore.purgeRevoked(id)
            }
            val after = trustStore.statusOf(id)
            require(after != before) {
                "${request.action} is not valid for a $before device"
            }
            refreshTrustMessage()
            if (shouldBroadcast) scope.launch { runCatching { foundation.broadcastTrust() } }
            devices()
        }

    override fun quarantineAction(request: QuarantineActionRequest): DeviceListResponse =
        trustMutationLock.withLock {
            require(trustStore.quarantined.value) { "trust store is not quarantined" }
            when (request.action) {
                QuarantineAction.APPROVE_AND_RESIGN -> trustStore.approveQuarantine()
                QuarantineAction.CLEAR -> trustStore.clearQuarantine()
            }
            trustMessage.set(null)
            scope.launch { runCatching { foundation.broadcastTrust() } }
            devices()
        }

    override suspend fun sendDismissal(request: DismissalRequest, sourceKey: String): String {
        val id = UUID.randomUUID().toString()
        secureChannel.send(
            typ = MessageType.DISMISSAL,
            body = ProtocolCodec.encodeToCbor(
                DismissEvent(secureChannel.clientId, sourceKey, clock.millis()),
            ),
            scope = Recipients.OwnMesh,
            urgency = Urgency.NORMAL,
        )
        return id
    }

    override suspend fun sendAction(request: ActionSendRequest, sourceKey: String): String {
        require(sourceKey.isNotBlank()) { "local session source is missing" }
        require(request.sourceClientId.isNotBlank() && request.sourceClientId.length <= 128) {
            "sourceClientId must contain 1..128 characters"
        }
        require(request.sourceKey.isNotBlank() && request.sourceKey.length <= 512) {
            "sourceKey must contain 1..512 characters"
        }
        require(request.actionTitle.isNotBlank() && request.actionTitle.length <= 80) {
            "actionTitle must contain 1..80 characters"
        }
        require((request.inputText?.length ?: 0) <= 64 * 1024) {
            "inputText is too long"
        }
        val origin = ClientId(request.sourceClientId)
        require(origin != secureChannel.clientId) { "a local source cannot send a wire action to itself" }
        val event = ActionEvent(
            sourceClientId = origin,
            sourceKey = request.sourceKey,
            kind = ActionKind.PERFORM,
            actionIndex = request.actionIndex,
            actionTitle = request.actionTitle,
            remoteInputText = request.inputText,
            actedAt = clock.millis(),
            actionGeneration = request.actionGeneration,
            actionToken = request.actionToken,
        )
        val recipients = secureChannel.send(
            typ = MessageType.ACTION,
            body = ProtocolCodec.encodeToCbor(event),
            scope = Recipients.Only(origin),
            urgency = Urgency.HIGH,
        )
        if (recipients == 0) {
            throw PeerUnavailableException("action origin is not a sealable trusted own device")
        }
        return UUID.randomUUID().toString()
    }

    override fun close() {
        if (state.getAndSet(DaemonConnectionState.STOPPED) == DaemonConnectionState.STOPPED) return
        lifecycle.cancel()
        broker.close()
    }

    private suspend fun runLiveDeliveryForever() {
        while (currentCoroutineContext().isActive) {
            try {
                broker.runLiveDelivery { envelope ->
                    val outcome = secureChannel.deliver(envelope, DeliveryMode.WEBSOCKET)
                    outcome.toLiveDisposition()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                state.set(DaemonConnectionState.BACKING_OFF)
                connectionMessage.set("WebSocket delivery failed: ${error.conciseMessage()}")
                delay(1_000)
            }
        }
    }

    private suspend fun runMaintenance() {
        var lastAntiEntropyAt = Long.MIN_VALUE
        var lastProfileFingerprint: String? = null
        while (currentCoroutineContext().isActive) {
            try {
                val verification = broker.fetchVerificationStatus()
                if (verification?.integrityRequired == true) {
                    state.set(DaemonConnectionState.UNSUPPORTED_INTEGRITY)
                    connectionMessage.set(
                        "The broker requires platform integrity evidence, which this desktop peer does not provide",
                    )
                    delay(healthPollMillis)
                    continue
                }

                val health = broker.fetchHealth()
                if (health == null) {
                    state.set(DaemonConnectionState.BACKING_OFF)
                    connectionMessage.set("Broker is unreachable; reconnecting")
                    delay(healthPollMillis)
                    continue
                }

                val now = clock.millis()
                if (lastAntiEntropyAt == Long.MIN_VALUE || now - lastAntiEntropyAt >= antiEntropyMillis) {
                    broker.publishKeyEpoch(keyMaterial.currentKeyEpoch())
                    foundation.convergeKeyEpochs()
                    foundation.broadcastTrust()
                    lastAntiEntropyAt = now
                }

                val config = configProvider()
                val profileFingerprint = profileFingerprint(config)
                if (profileFingerprint != lastProfileFingerprint) {
                    foundation.broadcastProfile(buildProfile(config, now))
                    lastProfileFingerprint = profileFingerprint
                }
                if (webSocketConnected.get()) {
                    state.set(DaemonConnectionState.CONNECTED)
                    connectionMessage.set(null)
                } else {
                    state.set(DaemonConnectionState.CONNECTING)
                    connectionMessage.set("Broker reachable; WebSocket connecting")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                state.set(DaemonConnectionState.BACKING_OFF)
                connectionMessage.set("Broker synchronization failed: ${error.conciseMessage()}")
            }
            delay(healthPollMillis)
        }
    }

    private fun onAction(message: InboundMessage) {
        if (!message.senderOwnDevice) return
        val event = ProtocolCodec.decodeFromCbor<ActionEvent>(message.body)
        if (event.sourceClientId != secureChannel.clientId || event.kind != ActionKind.PERFORM) return
        try {
            sessions.deliverWireAction(
                sourceKey = event.sourceKey,
                actionIndex = event.actionIndex,
                actionTitle = event.actionTitle,
                inputText = event.remoteInputText,
                senderClientId = message.senderId.value,
                senderIsTrustedOwnDevice = true,
                actionGeneration = event.actionGeneration,
                actionToken = event.actionToken,
                relayMessageId = message.messageId,
            )
        } catch (error: Exception) {
            throw RetryableDeliveryException("could not persist local action event", error)
        }
    }

    private fun onDismissal(message: InboundMessage) {
        if (!message.senderOwnDevice) return
        val event = ProtocolCodec.decodeFromCbor<DismissEvent>(message.body)
        if (event.sourceClientId != secureChannel.clientId) return
        try {
            sessions.deliverDismissal(
                sourceKey = event.sourceKey,
                senderClientId = message.senderId.value,
                senderIsTrustedOwnDevice = true,
                relayMessageId = message.messageId,
            )
        } catch (error: Exception) {
            throw RetryableDeliveryException("could not persist local dismissal event", error)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onUnsupportedNotification(message: InboundMessage) = Unit

    private fun buildClientCard(): SignedBlob {
        val config = configProvider()
        val card = ClientCard(
            clientId = keyMaterial.identity.clientId,
            identityPublicKey = keyMaterial.identity.publicKeySpki,
            displayName = config.deviceName,
            platform = config.platformName,
            capabilities = DAEMON_CAPABILITIES,
            createdAt = createdAt,
        )
        val payload = ProtocolCodec.encodeToCbor(card)
        return SignedBlob(
            typ = SignedType.CLIENT_CARD,
            signerId = keyMaterial.identity.clientId,
            payload = payload,
            sig = keyMaterial.identity.sign(payload),
        )
    }

    private fun buildProfile(config: NotisyncdConfig, updatedAt: Long) = ProfileUpdate(
        clientId = keyMaterial.identity.clientId,
        displayName = config.deviceName,
        platform = config.platformName,
        capabilities = DAEMON_CAPABILITIES,
        updatedAt = updatedAt,
    )

    private fun inspectCandidate(payload: String): PairingCandidate {
        val inspected = pairing.inspect(payload).getOrElse {
            throw IllegalArgumentException("invalid pairing payload: ${it.message}", it)
        }
        require(inspected.keyEpochStatus != KeyEpochStatus.INVALID) {
            "pairing payload carries an invalid operational key epoch"
        }
        val card = pairing.decode(payload).getOrElse {
            throw IllegalArgumentException("invalid pairing payload: ${it.message}", it)
        }.card
        return PairingCandidate(
            clientId = inspected.clientId.value,
            name = inspected.displayName,
            identityFingerprint = inspected.identityKeyFingerprint,
            capabilities = card.capabilities.mapTo(linkedSetOf()) { it.name },
        )
    }

    private fun refreshTrustMessage() {
        val pending = trustStore.roster.value.count {
            it.status == TrustStatus.PENDING_TRUST || it.status == TrustStatus.PENDING_REVOKE
        }
        trustMessage.set(
            if (pending == 0) null else "$pending trusted-device change(s) require review",
        )
    }

    private fun profileFingerprint(config: NotisyncdConfig): String = buildString {
        append(config.deviceName).append('\u0000').append(config.platformName)
        DAEMON_CAPABILITIES.forEach { append('\u0000').append(it.name) }
    }

    private fun RosterDevice.toApi(quarantined: Boolean): DeviceView = DeviceView(
        clientId = clientId.value,
        name = displayName ?: clientId.shortForm(),
        classification = if (ownDevice) DeviceClassification.OWN else DeviceClassification.OTHER,
        trustStatus = if (quarantined) DeviceTrustStatus.QUARANTINED else status.toApi(),
        capabilities = capabilities.mapTo(linkedSetOf()) { it.name },
        identityFingerprint = identityKeyFingerprint ?: "unavailable",
        keyAvailable = keyAvailable,
        verified = verified,
        currentEpoch = currentEpoch,
        signingKeyFingerprint = keyEpoch?.signingKeyFingerprint,
        hpkeKeyFingerprint = keyEpoch?.encryptionKeyFingerprint,
        introducedBy = introducedByName,
        allowedActions = if (quarantined) emptySet() else allowedActions(clock.millis()),
    )

    private fun TrustStatus.toApi(): DeviceTrustStatus = when (this) {
        TrustStatus.PENDING_TRUST -> DeviceTrustStatus.PENDING
        TrustStatus.TRUSTED -> DeviceTrustStatus.TRUSTED
        TrustStatus.PENDING_REVOKE -> DeviceTrustStatus.REVOKE_PENDING
        TrustStatus.REVOKED -> DeviceTrustStatus.REVOKED
    }

    private fun RosterDevice.allowedActions(now: Long): Set<DeviceAction> = when (status) {
        TrustStatus.PENDING_TRUST -> setOf(DeviceAction.APPROVE, DeviceAction.REJECT)
        TrustStatus.TRUSTED -> setOf(DeviceAction.REVOKE)
        TrustStatus.PENDING_REVOKE -> setOf(DeviceAction.CONFIRM_REVOKE, DeviceAction.KEEP)
        TrustStatus.REVOKED -> buildSet {
            add(DeviceAction.RESTORE)
            val revoked = this@allowedActions.revokedAt
            if (revoked != null && now - revoked >= TrustStore.REVOKE_PURGE_DELAY_MS) {
                add(DeviceAction.PURGE)
            }
        }
    }

    companion object {
        /** Complete declaration: do not add DISPLAY, push, wake, asset, dismissal-sync, or update rendering. */
        val DAEMON_CAPABILITIES: List<Capability> = listOf(
            Capability.CAPTURE,
            Capability.FOREGROUND_CONNECTION,
            Capability.CAPABILITY_ROUTING_V1,
        )

        private const val DEFAULT_HEALTH_POLL_MILLIS = 15_000L
        private const val DEFAULT_ANTI_ENTROPY_MILLIS = 5 * 60_000L
    }
}

private class RepositoryAuthTokenStore(
    private val repository: AuthTokenRepository,
) : AuthTokenStore {
    override fun load() = repository.load()
    override fun save(token: net.extrawdw.notisync.protocol.IntegrityVerificationResponse?) =
        repository.save(token)
}

private class RepositoryMessageDedup(
    private val repository: MessageDedupRepository,
) : MessageDedup {
    override fun seen(messageId: String): Boolean = repository.seen(messageId)
    override fun record(messageId: String) = repository.record(messageId)
}

private fun DeliveryOutcome.toLiveDisposition(): LiveDeliveryDisposition =
    if (safeToAck) LiveDeliveryDisposition.ACK else LiveDeliveryDisposition.RETRY

private fun Throwable.conciseMessage(): String =
    message?.takeIf(String::isNotBlank) ?: javaClass.simpleName

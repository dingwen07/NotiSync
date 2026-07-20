package net.extrawdw.notisync.daemon.peer.runtime

import java.time.Clock
import java.security.MessageDigest
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.extrawdw.notisync.daemon.ActionOriginPolicy
import net.extrawdw.notisync.daemon.ApplicationProfilePublicationStateStore
import net.extrawdw.notisync.daemon.ApplicationReceiveRouter
import net.extrawdw.notisync.daemon.GenericBatchSender
import net.extrawdw.notisync.daemon.LocalEventQueueFullException
import net.extrawdw.notisync.daemon.PendingSend
import net.extrawdw.notisync.daemon.PeerAdministration
import net.extrawdw.notisync.daemon.logging.DaemonLogger
import net.extrawdw.notisync.desktop.config.NotisyncdConfig
import net.extrawdw.notisync.desktop.config.NOTISYNCD_PLATFORM_NAME
import net.extrawdw.notisync.localapi.DaemonConnectionState
import net.extrawdw.notisync.localapi.DeviceAction
import net.extrawdw.notisync.localapi.DeviceActionRequest
import net.extrawdw.notisync.localapi.DeviceClassification
import net.extrawdw.notisync.localapi.DeviceListResponse
import net.extrawdw.notisync.localapi.DeviceTrustStatus
import net.extrawdw.notisync.localapi.DeviceView
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
import net.extrawdw.notisync.peer.channel.OutboundItem
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
import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.ClientCard
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.LiveDeliveryDisposition
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.ProfileUpdate
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.SignedType
import net.extrawdw.notisync.protocol.TrustStatus

/**
 * The desktop NotiSync peer graph.
 *
 * This class deliberately owns no file paths or configuration format. The daemon supplies repositories
 * and a [configProvider], allowing the initial private-file implementations to be replaced by a system
 * keychain or a different configuration parser without changing the secure-channel composition.
 *
 * The runtime is the daemon's generic strict sender, authenticated inbound bridge, and administration
 * surface. All references lead to this single [SecureChannel], preserving one sender sequence and one
 * durable dedup domain without teaching the daemon application-level Run or notification semantics.
 */
class DesktopPeerRuntime(
    private val configProvider: () -> NotisyncdConfig,
    private val keyMaterial: KeyMaterialProvider,
    trustPersistence: TrustPersistence,
    authTokens: AuthTokenRepository,
    deduplication: MessageDedupRepository,
    private val receiveRouter: ApplicationReceiveRouter,
    private val capabilitiesProvider: () -> List<Capability>,
    private val profileState: ApplicationProfilePublicationStateStore,
    parentScope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    private val clock: Clock = Clock.systemUTC(),
    private val logger: DaemonLogger = DaemonLogger(configProvider().logLevel),
    private val channelLogger: ChannelLogger = ChannelLogger(logger::warn),
    telemetry: PeerTelemetry = PeerTelemetry.None,
    private val onUnverifiedDeviceCleanupV1Completed: () -> Unit = {},
    private val healthPollMillis: Long = DEFAULT_HEALTH_POLL_MILLIS,
    private val antiEntropyMillis: Long = DEFAULT_ANTI_ENTROPY_MILLIS,
) : PeerAdministration, GenericBatchSender, ActionOriginPolicy, AutoCloseable {
    private val lifecycle = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(
        parentScope.coroutineContext + lifecycle + CoroutineName("notisyncd-peer"),
    )
    private val started = AtomicBoolean(false)
    private val state = AtomicReference(DaemonConnectionState.STARTING)
    private val webSocketConnected = AtomicBoolean(false)
    private val connectionMessage = AtomicReference<String?>(null)
    private val trustMessage = AtomicReference<String?>(null)
    private val profileWake = Channel<Unit>(Channel.CONFLATED)

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
    private val createdAt: Long = profileState.updateProfilePublicationState { current ->
        if (current.cardCreatedAtFloorEpochMillis != null) current
        else current.copy(cardCreatedAtFloorEpochMillis = clock.millis())
    }.cardCreatedAtFloorEpochMillis ?: error("profile card creation floor was not initialized")

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
            val changed = webSocketConnected.getAndSet(connected) != connected
            if (connected) {
                state.set(DaemonConnectionState.CONNECTED)
                connectionMessage.set(null)
                if (changed) logger.info("Broker WebSocket connected")
            } else if (state.get() != DaemonConnectionState.UNSUPPORTED_INTEGRITY) {
                state.set(DaemonConnectionState.BACKING_OFF)
                connectionMessage.set("WebSocket disconnected; reconnecting")
                if (changed) logger.warn("Broker WebSocket disconnected; reconnecting")
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

    init {
        require(healthPollMillis > 0) { "healthPollMillis must be positive" }
        require(antiEntropyMillis > 0) { "antiEntropyMillis must be positive" }

        if (!configProvider().unverifiedDeviceCleanupV1Completed) {
            val removed = trustMutationLock.withLock { trustStore.removeUnverifiedDevices() }
            if (removed != null) {
                onUnverifiedDeviceCleanupV1Completed()
                if (removed.isNotEmpty()) {
                    logger.info("Removed ${removed.size} unverified device(s) during NS2 upgrade")
                }
            }
        }

        // The signed trust section owns the monotonic self floor. Reconcile it with the durable active key
        // before either the broker or a peer sees an envelope from this process.
        trustState.advanceSelfEpoch(keyMaterial.currentOperationalSigner().signerEpoch)

        foundation = FoundationEngine(
            channel = secureChannel,
            trust = trustState,
            scope = scope,
            onTrustPrompt = { subject, prompt, introducedBy ->
                val message = "Trust decision required for ${subject.shortForm()} ($prompt), introduced by $introducedBy"
                trustMessage.set(message)
                logger.warn(message)
            },
            onAsset = { _, _ -> Unit },
            onFilter = { _, _ -> Unit },
            onNotificationSync = { _, _ -> Unit },
            onRunSync = { _, _ -> Unit },
            onDecodedDataSync = ::routeDecodedDataSync,
            onMalformedDataSync = ::routeInbound,
            incomingTrustPolicy = IncomingTrustPolicy { change ->
                configProvider().automaticallyApplyTrustedDeviceTables &&
                    change.senderIsTrustedOwnDevice
            },
            selfKeyEpoch = keyMaterial::currentKeyEpoch,
            fetchKeyEpoch = broker::fetchKeyEpoch,
            now = clock::millis,
        )
        foundation.register()
        secureChannel.onMessage(MessageType.NOTIFICATION, ::routeInbound)
        secureChannel.onMessage(MessageType.ACTION, ::routeInbound)
        secureChannel.onMessage(MessageType.DISMISSAL, ::routeInbound)
        recordDesiredProfile()
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
        logger.info("Starting desktop peer $clientId")
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
        logger.info(
            "Accepted pairing with ${candidate.clientId} as ${request.classification.name.lowercase()}",
        )
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
            logger.info("Device ${id.shortForm()} changed from $before to $after via ${request.action}")
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
            logger.warn("Trust-store quarantine resolved with ${request.action}")
            scope.launch { runCatching { foundation.broadcastTrust() } }
            devices()
        }

    override suspend fun send(batch: List<PendingSend>, onAccepted: (PendingSend) -> Unit): Int {
        require(batch.isNotEmpty()) { "strict send batch must not be empty" }
        val first = batch.first()
        require(batch.all { first.belongsToSameDispatchGroup(it) }) {
            "strict send batch contains different dispatch groups"
        }
        val byId = batch.associateBy(PendingSend::messageId)
        return secureChannel.sendAllStrict(
            typ = first.messageType,
            items = batch.map { OutboundItem(it.messageId, it.body) },
            scope = first.scope,
            urgency = first.urgency,
            signWith = first.signWith,
        ) { accepted -> onAccepted(checkNotNull(byId[accepted.messageId])) }
    }

    override fun isTrustedOwnCapturePeer(clientId: ClientId): Boolean = trustMutationLock.withLock {
        trustStore.roster.value.any { device ->
            device.clientId == clientId &&
                device.ownDevice &&
                device.status == TrustStatus.TRUSTED &&
                Capability.CAPTURE in device.capabilities
        }
    }

    /** Record a material capability/name change and attempt publication without waiting for maintenance. */
    fun requestProfilePublication() {
        recordDesiredProfile()
        profileWake.trySend(Unit)
        if (started.get()) scope.launch(CoroutineName("notisyncd-profile")) {
            runCatching { publishProfileIfNeeded(force = false) }
                .onFailure { logger.warn("Profile publication failed; retrying: ${it.conciseMessage()}") }
        }
    }

    override fun close() {
        if (state.getAndSet(DaemonConnectionState.STOPPED) == DaemonConnectionState.STOPPED) return
        logger.info("Stopping desktop peer $clientId")
        lifecycle.cancel()
        profileWake.close()
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
                val message = "WebSocket delivery failed: ${error.conciseMessage()}"
                setConnectionWarning(message, "$message; retrying")
                delay(1_000)
            }
        }
    }

    private suspend fun runMaintenance() {
        var lastAntiEntropyAt = Long.MIN_VALUE
        while (currentCoroutineContext().isActive) {
            try {
                val verification = broker.fetchVerificationStatus()
                if (verification?.integrityRequired == true) {
                    state.set(DaemonConnectionState.UNSUPPORTED_INTEGRITY)
                    val message = "The broker requires platform integrity evidence, which this desktop peer does not provide"
                    if (connectionMessage.getAndSet(message) != message) {
                        logger.error("Broker requires unsupported platform integrity evidence")
                    }
                    delay(healthPollMillis)
                    continue
                }

                val health = broker.fetchHealth()
                if (health == null) {
                    setConnectionWarning("Broker is unreachable; reconnecting", "Broker health check failed; reconnecting")
                    delay(healthPollMillis)
                    continue
                }

                val now = clock.millis()
                val antiEntropyDue = lastAntiEntropyAt == Long.MIN_VALUE || now - lastAntiEntropyAt >= antiEntropyMillis
                if (antiEntropyDue) {
                    broker.publishKeyEpoch(keyMaterial.currentKeyEpoch())
                    foundation.convergeKeyEpochs()
                    foundation.broadcastTrust()
                    lastAntiEntropyAt = now
                    logger.debug("Completed broker trust and key convergence")
                }
                recordDesiredProfile()
                publishProfileIfNeeded(force = antiEntropyDue)
                val expiredInterests = receiveRouter.cleanupDeadProcesses()
                if (expiredInterests > 0) {
                    logger.info("Removed $expiredInterests receive interest(s) for exited local processes")
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
                val message = "Broker synchronization failed: ${error.conciseMessage()}"
                setConnectionWarning(message)
            }
            // A capability registration wakes maintenance immediately; timeout remains the health cadence.
            kotlinx.coroutines.withTimeoutOrNull(healthPollMillis) { profileWake.receiveCatching() }
        }
    }

    private fun routeInbound(message: InboundMessage) {
        try {
            val matched = receiveRouter.accept(message)
            logger.info(
                "Received ${message.typ} ${message.messageId} from ${message.senderId.shortForm()}; " +
                    if (matched) "fanned out to local applications" else "no live local interest",
            )
        } catch (full: LocalEventQueueFullException) {
            throw RetryableDeliveryException("local application inbox is full", full)
        }
    }

    private fun routeDecodedDataSync(
        message: InboundMessage,
        sync: net.extrawdw.notisync.protocol.DataSync,
    ) {
        try {
            val matched = receiveRouter.accept(message, sync)
            logger.info(
                "Received DATA_SYNC/${sync.kind.name} ${message.messageId} " +
                    "from ${message.senderId.shortForm()}; " +
                    if (matched) "fanned out to local applications" else "no live local interest",
            )
        } catch (full: LocalEventQueueFullException) {
            throw RetryableDeliveryException("local application inbox is full", full)
        }
    }

    private fun buildClientCard(): SignedBlob {
        val config = configProvider()
        val card = ClientCard(
            clientId = keyMaterial.identity.clientId,
            identityPublicKey = keyMaterial.identity.publicKeySpki,
            displayName = config.deviceName,
            platform = NOTISYNCD_PLATFORM_NAME,
            capabilities = capabilitiesProvider(),
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
        platform = NOTISYNCD_PLATFORM_NAME,
        capabilities = capabilitiesProvider(),
        updatedAt = updatedAt,
    )

    /** Atomically advance the desired profile only for a material name/capability change. */
    private fun recordDesiredProfile() {
        val fingerprint = profileFingerprint(configProvider())
        profileState.updateProfilePublicationState { current ->
            if (current.profileFingerprint == fingerprint && current.profileUpdatedAtEpochMillis != null) {
                current
            } else {
                val updatedAt = maxOf(
                    clock.millis(),
                    (current.profileUpdatedAtEpochMillis ?: Long.MIN_VALUE).let {
                        if (it == Long.MAX_VALUE) it else it + 1
                    },
                    0,
                )
                val revision = current.publicationRevision + 1
                current.copy(
                    profileFingerprint = fingerprint,
                    profileUpdatedAtEpochMillis = updatedAt,
                    publicationRevision = revision,
                    pendingPublicationRevision = revision,
                )
            }
        }
    }

    private suspend fun publishProfileIfNeeded(force: Boolean) {
        val published = publishDurableProfileIfNeeded(
            profileState = profileState,
            force = force,
            buildProfile = { updatedAt -> buildProfile(configProvider(), updatedAt) },
            publish = foundation::broadcastProfile,
        )
        if (!published) return
        logger.info(
            "Published desktop profile for ${configProvider().deviceName}; " +
                "capabilities=${capabilitiesProvider().joinToString { it.name }}",
        )
    }

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

    private fun setConnectionWarning(statusMessage: String, logMessage: String = statusMessage) {
        state.set(DaemonConnectionState.BACKING_OFF)
        if (connectionMessage.getAndSet(statusMessage) != statusMessage) logger.warn(logMessage)
    }

    private fun profileFingerprint(config: NotisyncdConfig): String {
        val material = buildString {
            append(config.deviceName).append('\u0000').append(NOTISYNCD_PLATFORM_NAME)
            capabilitiesProvider().forEach { append('\u0000').append(it.name) }
        }.encodeToByteArray()
        return MessageDigest.getInstance("SHA-256").digest(material)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun RosterDevice.toApi(quarantined: Boolean): DeviceView = DeviceView(
        clientId = clientId.value,
        name = displayName ?: clientId.shortForm(),
        classification = if (ownDevice) DeviceClassification.OWN else DeviceClassification.OTHER,
        trustStatus = if (quarantined) DeviceTrustStatus.QUARANTINED else status.toApi(),
        platform = platform,
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
        private const val DEFAULT_HEALTH_POLL_MILLIS = 15_000L
        private const val DEFAULT_ANTI_ENTROPY_MILLIS = 5 * 60_000L
    }
}

/**
 * Publish one desired profile revision while retaining a durable retry marker until the broker send
 * succeeds. A forced startup/anti-entropy announcement marks the current material revision pending
 * before attempting I/O, so a transient failure is retried by the next ordinary maintenance pass.
 */
internal suspend fun publishDurableProfileIfNeeded(
    profileState: ApplicationProfilePublicationStateStore,
    force: Boolean,
    buildProfile: (updatedAt: Long) -> ProfileUpdate,
    publish: suspend (ProfileUpdate) -> Unit,
): Boolean {
    val snapshot = if (force) {
        profileState.updateProfilePublicationState { current ->
            if (
                current.pendingPublicationRevision == null &&
                current.publicationRevision > 0 &&
                current.profileUpdatedAtEpochMillis != null
            ) {
                current.copy(pendingPublicationRevision = current.publicationRevision)
            } else {
                current
            }
        }
    } else {
        profileState.profilePublicationState()
    }
    val pendingRevision = snapshot.pendingPublicationRevision ?: return false
    val updatedAt = snapshot.profileUpdatedAtEpochMillis ?: return false
    publish(buildProfile(updatedAt))
    profileState.updateProfilePublicationState { current ->
        if (current.pendingPublicationRevision == pendingRevision) {
            current.copy(pendingPublicationRevision = null)
        } else {
            current
        }
    }
    return true
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

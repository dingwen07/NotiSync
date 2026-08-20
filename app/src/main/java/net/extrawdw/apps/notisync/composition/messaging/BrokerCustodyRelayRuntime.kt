package net.extrawdw.apps.notisync.composition.messaging

import net.extrawdw.apps.notisync.data.continuity.OperationalContinuityRepository
import net.extrawdw.apps.notisync.data.continuity.RoomOperationalContinuityRepository
import net.extrawdw.apps.notisync.data.relay.RelayDeliveryMode
import net.extrawdw.apps.notisync.data.relay.RelayOperationalContinuity
import net.extrawdw.apps.notisync.data.relay.RelayRepository
import net.extrawdw.apps.notisync.data.relay.RoomRelayBatchSessionRepository
import net.extrawdw.apps.notisync.data.relay.RoomRelayRepository
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDatabase
import net.extrawdw.apps.notisync.data.storage.runtime.OperationalStorageMaintenanceGate
import net.extrawdw.apps.notisync.messaging.InboundMessageRouter
import net.extrawdw.apps.notisync.messaging.inbound.CanonicalInboundEnvelopeFingerprint
import net.extrawdw.apps.notisync.messaging.inbound.CoreInboundDispatchPort
import net.extrawdw.apps.notisync.messaging.inbound.HandledInboundReplayPort
import net.extrawdw.apps.notisync.messaging.inbound.InboundAcknowledgementCoordinator
import net.extrawdw.apps.notisync.messaging.inbound.InboundCoordinatorClock
import net.extrawdw.apps.notisync.messaging.inbound.InboundDeliveryCoordinator
import net.extrawdw.apps.notisync.messaging.inbound.InboundEnvelopeArrival
import net.extrawdw.apps.notisync.messaging.inbound.InboundEnvelopeCodec
import net.extrawdw.apps.notisync.messaging.inbound.InboundEnvelopeFingerprint
import net.extrawdw.apps.notisync.messaging.inbound.InboundNetworkAcknowledgementPort
import net.extrawdw.apps.notisync.messaging.inbound.MutexInboundProcessGate
import net.extrawdw.apps.notisync.messaging.inbound.OperationalInboundDispatchPort
import net.extrawdw.apps.notisync.messaging.inbound.PeerCoreInboundEnvelopePort
import net.extrawdw.apps.notisync.messaging.inbound.ProtocolInboundEnvelopeCodec
import net.extrawdw.apps.notisync.messaging.inbound.RelayBatchDrainCoordinator
import net.extrawdw.apps.notisync.messaging.inbound.RelayExactFetchPort
import net.extrawdw.apps.notisync.messaging.inbound.RelayExactFetchResult
import net.extrawdw.apps.notisync.messaging.inbound.RelayFiniteBatchFetchPort
import net.extrawdw.apps.notisync.messaging.inbound.RelayFiniteBatchFetchResult
import net.extrawdw.apps.notisync.messaging.inbound.SerializedDirectInboundProcessor
import net.extrawdw.apps.notisync.work.BrokerCustodyRelayWorkerRuntime
import net.extrawdw.apps.notisync.work.RelayWorkerContinuityPort
import net.extrawdw.apps.notisync.work.RelayWorkerExactFetchPort
import net.extrawdw.apps.notisync.work.RelayWorkerRuntime
import net.extrawdw.notisync.peer.channel.SecureEnvelopeTransport
import net.extrawdw.notisync.peer.transport.BrokerClient
import net.extrawdw.notisync.peer.transport.RelayBatchFetchResult
import net.extrawdw.notisync.peer.transport.RelayDeliveryFetchResult
import net.extrawdw.notisync.protocol.Envelope
import net.extrawdw.notisync.protocol.LiveDeliveryDisposition
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.RelayBatchKind

/** The foreground relay loop has the same custody/ACK boundary as WorkManager, but is not a second receiver. */
internal fun interface BrokerCustodyLiveDelivery {
    suspend fun run()
}

/**
 * The complete broker-custody composition produced after Room authority is ready.
 *
 * [workerRuntime] is passed to WorkManager through the existing application provider. [liveDelivery] is invoked by
 * the foreground lifecycle. Both paths share the exact same [SerializedDirectInboundProcessor] and process mutex.
 */
internal data class BrokerCustodyRelayRuntimeComponents(
    val workerRuntime: RelayWorkerRuntime,
    val liveDelivery: BrokerCustodyLiveDelivery,
)

/**
 * Small composition-only factory for the one inbound authority.
 *
 * This factory owns adapters, not policy: Core and Operational dispatchers are supplied by their feature owners and
 * the broker remains the only retry journal. No Room inbox, retry clock, or readiness state is created here.
 */
internal object BrokerCustodyRelayRuntimeFactory {
    fun create(
        broker: BrokerClient,
        secureEnvelopeTransport: SecureEnvelopeTransport,
        operationalDatabase: OperationalDatabase,
        maintenanceGate: OperationalStorageMaintenanceGate,
        operationalDispatch: OperationalInboundDispatchPort,
        coreDispatch: CoreInboundDispatchPort,
        handledReplay: HandledInboundReplayPort,
        clock: InboundCoordinatorClock = InboundCoordinatorClock(System::currentTimeMillis),
        envelopeCodec: InboundEnvelopeCodec = ProtocolInboundEnvelopeCodec,
        fingerprint: InboundEnvelopeFingerprint = CanonicalInboundEnvelopeFingerprint,
        router: InboundMessageRouter = InboundMessageRouter(),
        pageSize: Int = DEFAULT_BATCH_PAGE_SIZE,
    ): BrokerCustodyRelayRuntimeComponents {
        val continuityRepository: OperationalContinuityRepository =
            RoomOperationalContinuityRepository(operationalDatabase.profileDao())
        val continuity = RoomRelayWorkerContinuityPort(continuityRepository)
        val relayRepository: RelayRepository = RoomRelayRepository(operationalDatabase.relayDao())
        val processGate = MutexInboundProcessGate()
        val acknowledgement = InboundAcknowledgementCoordinator(
            relayRepository = relayRepository,
            resetGate = net.extrawdw.apps.notisync.messaging.inbound.OperationalMaintenanceInboundResetGate(
                maintenanceGate,
            ),
            network = BrokerClientNetworkAcknowledgementPort(broker),
        )
        val coordinator = InboundDeliveryCoordinator(
            relayRepository = relayRepository,
            envelopeCodec = envelopeCodec,
            fingerprint = fingerprint,
            inboundEnvelopePort = PeerCoreInboundEnvelopePort(secureEnvelopeTransport),
            router = router,
            operationalDispatch = operationalDispatch,
            coreDispatch = coreDispatch,
            handledReplay = handledReplay,
            clock = clock,
        )
        val direct = SerializedDirectInboundProcessor(
            processGate = processGate,
            delivery = coordinator,
            acknowledgements = acknowledgement,
        )
        val exact = BrokerClientRelayExactFetchPort(broker, clock)
        val batch = RelayBatchDrainCoordinator(
            processGate = processGate,
            scratch = RoomRelayBatchSessionRepository(operationalDatabase.relayBatchStageDao()),
            delivery = coordinator,
            acknowledgements = acknowledgement,
            batchSource = BrokerClientRelayFiniteBatchFetchPort(broker, continuity),
            exactSource = exact,
            pageSize = pageSize,
        )
        return BrokerCustodyRelayRuntimeComponents(
            workerRuntime = BrokerCustodyRelayWorkerRuntime(
                continuity = continuity,
                exactSource = exact,
                direct = direct,
                batch = batch,
            ),
            liveDelivery = BrokerClientRelayLiveDeliveryPort(
                broker = broker,
                continuity = continuity,
                direct = direct,
                clock = clock,
            ),
        )
    }

    private const val DEFAULT_BATCH_PAGE_SIZE = 64
}

/** Reads the immutable Room continuity marker for each new worker/live delivery. */
private class RoomRelayWorkerContinuityPort(
    private val repository: OperationalContinuityRepository,
) : RelayWorkerContinuityPort {
    override suspend fun current(): RelayOperationalContinuity? = repository.readMaintenance()?.let { state ->
        RelayOperationalContinuity(
            generation = state.operationalGeneration,
            storageIncarnationId = state.storageIncarnationId,
        )
    }
}

/** Exact broker lookup. `Missing` is returned only for a broker 404; transport/decoding failures retry. */
private class BrokerClientRelayExactFetchPort(
    private val broker: BrokerClient,
    private val clock: InboundCoordinatorClock,
) : RelayExactFetchPort, RelayWorkerExactFetchPort {
    override suspend fun fetch(
        messageId: String,
        continuity: RelayOperationalContinuity,
    ): RelayExactFetchResult = when (val result = broker.fetchRelayDeliveryResult(messageId)) {
        is RelayDeliveryFetchResult.Found -> RelayExactFetchResult.Found(
            result.delivery.toArrival(
                locatorMessageId = messageId,
                deliveryMode = RelayDeliveryMode.FCM_RELAY_FETCH,
                continuity = continuity,
                clock = clock,
            ),
        )
        RelayDeliveryFetchResult.Missing -> RelayExactFetchResult.Missing
        RelayDeliveryFetchResult.Failed -> RelayExactFetchResult.RetryRequired
    }
}

/** Finite source adapter; the broker validates START/ITEM/END framing before this callback sees an item. */
private class BrokerClientRelayFiniteBatchFetchPort(
    private val broker: BrokerClient,
    private val continuity: RelayWorkerContinuityPort,
) : RelayFiniteBatchFetchPort {
    override suspend fun fetch(consume: suspend (InboundEnvelopeArrival) -> Unit): RelayFiniteBatchFetchResult {
        val capturedContinuity = continuity.current() ?: return RelayFiniteBatchFetchResult.INCOMPLETE_RETRY
        val result = broker.fetchRelayBatchSuspending { frame ->
                require(frame.kind == RelayBatchKind.ITEM) { "relay batch source exposed a non-item frame" }
                val messageId = requireNotNull(frame.messageId)
                val acceptedAt = requireNotNull(frame.acceptedAt)
                val envelope = requireNotNull(frame.envelope)
                consume(
                    InboundEnvelopeArrival(
                        messageId = messageId,
                        encodedEnvelope = envelope,
                        acceptedAt = acceptedAt,
                        deliveryMode = RelayDeliveryMode.RELAY_DRAIN,
                        continuity = capturedContinuity,
                    ),
                )
            }
        return when (result) {
            is RelayBatchFetchResult.Complete -> RelayFiniteBatchFetchResult.COMPLETE
            RelayBatchFetchResult.Unsupported -> RelayFiniteBatchFetchResult.UNSUPPORTED_RETRY
            RelayBatchFetchResult.Failed -> RelayFiniteBatchFetchResult.INCOMPLETE_RETRY
        }
    }

}

/** Network ACK adapter. The coordinator has already closed the Room read before this call begins. */
private class BrokerClientNetworkAcknowledgementPort(
    private val broker: BrokerClient,
) : InboundNetworkAcknowledgementPort {
    override suspend fun acknowledge(messageId: String): Boolean = broker.ackRelayMessages(listOf(messageId))
}

/** Live WebSocket adapter; broker ACK happens only after [SerializedDirectInboundProcessor] reports success. */
private class BrokerClientRelayLiveDeliveryPort(
    private val broker: BrokerClient,
    private val continuity: RelayWorkerContinuityPort,
    private val direct: SerializedDirectInboundProcessor,
    private val clock: InboundCoordinatorClock,
) : BrokerCustodyLiveDelivery {
    override suspend fun run() {
        broker.runLiveDeliveryWithMetadataSuspending { envelope, acceptedAt ->
            val current = continuity.current()
                ?: return@runLiveDeliveryWithMetadataSuspending LiveDeliveryDisposition.RETRY
            val result = direct.process(
                envelope.toArrival(
                    locatorMessageId = envelope.messageId,
                    deliveryMode = RelayDeliveryMode.WEBSOCKET,
                    continuity = current,
                    clock = clock,
                    acceptedAt = acceptedAt,
                ),
            )
            if (result.acknowledgement == net.extrawdw.apps.notisync.messaging.inbound.InboundAcknowledgementResult.Acknowledged) {
                LiveDeliveryDisposition.ACK
            } else {
                LiveDeliveryDisposition.RETRY
            }
        }
    }
}

private fun net.extrawdw.notisync.peer.transport.RelayDelivery.toArrival(
    locatorMessageId: String,
    deliveryMode: RelayDeliveryMode,
    continuity: RelayOperationalContinuity,
    clock: InboundCoordinatorClock,
): InboundEnvelopeArrival = envelope.toArrival(
    locatorMessageId = locatorMessageId,
    deliveryMode = deliveryMode,
    continuity = continuity,
    clock = clock,
    acceptedAt = acceptedAt,
)

private fun Envelope.toArrival(
    locatorMessageId: String,
    deliveryMode: RelayDeliveryMode,
    continuity: RelayOperationalContinuity,
    clock: InboundCoordinatorClock,
    acceptedAt: Long? = null,
): InboundEnvelopeArrival {
    val encoded = ProtocolCodec.encodeToCbor(this)
    return try {
        InboundEnvelopeArrival(
            messageId = locatorMessageId,
            encodedEnvelope = encoded,
            acceptedAt = acceptedAt ?: clock.nowChecked(),
            deliveryMode = deliveryMode,
            continuity = continuity,
        )
    } finally {
        encoded.fill(0)
    }
}

private fun InboundCoordinatorClock.nowChecked(): Long = now().also {
    require(it > 0) { "relay arrival clock must be positive" }
}

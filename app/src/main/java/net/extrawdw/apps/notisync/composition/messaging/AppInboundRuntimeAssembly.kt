package net.extrawdw.apps.notisync.composition.messaging

import net.extrawdw.apps.notisync.crypto.AndroidIdentitySigner
import net.extrawdw.apps.notisync.crypto.EpochHpkeKeyring
import net.extrawdw.apps.notisync.data.corecommand.RepositoryCoreCommandAuthority
import net.extrawdw.apps.notisync.data.corecommand.RoomCoreCommandReceiptFinalizer
import net.extrawdw.apps.notisync.data.corecommand.preparation.AndroidExistingIdentitySignerLoader
import net.extrawdw.apps.notisync.data.corecommand.preparation.DefaultCoreCommandPreparation
import net.extrawdw.apps.notisync.data.corecommand.preparation.RepositoryCoreTrustPreparationSnapshotReader
import net.extrawdw.apps.notisync.data.seal.RoomSealRepository
import net.extrawdw.apps.notisync.data.ssh.RoomSshProviderRepository
import net.extrawdw.apps.notisync.data.storage.core.CoreFoundationRepository
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDatabase
import net.extrawdw.apps.notisync.data.storage.runtime.OperationalStorageMaintenanceGate
import net.extrawdw.apps.notisync.data.TrustStore
import net.extrawdw.apps.notisync.messaging.core.CoreCommandProcessor
import net.extrawdw.apps.notisync.messaging.core.CoreInboundDispatch
import net.extrawdw.apps.notisync.messaging.inbound.OperationalInboundCompatibilityEffects
import net.extrawdw.apps.notisync.messaging.inbound.OperationalInboundEffects
import net.extrawdw.apps.notisync.messaging.inbound.RoomOperationalInboundDispatch
import net.extrawdw.apps.notisync.messaging.inbound.SealInboundReceiptPort
import net.extrawdw.apps.notisync.messaging.inbound.SshInboundReceiptPort
import net.extrawdw.notisync.peer.channel.SecureEnvelopeTransport
import net.extrawdw.notisync.peer.foundation.TrustPeerDirectory
import net.extrawdw.notisync.peer.ports.IncomingTrustPolicy
import net.extrawdw.notisync.peer.transport.BrokerClient
import net.extrawdw.notisync.protocol.crypto.OperationalSigner

/** Builds the one authenticated inbound authority after the Room-backed feature owners are ready. */
internal fun assembleBrokerCustodyInboundRuntime(
    broker: BrokerClient,
    identity: AndroidIdentitySigner,
    operationalSigner: () -> OperationalSigner,
    hpkeKeyring: EpochHpkeKeyring,
    trust: TrustStore,
    coreRepository: CoreFoundationRepository,
    operationalDatabase: OperationalDatabase,
    maintenanceGate: OperationalStorageMaintenanceGate,
    sealRepository: RoomSealRepository,
    sshRepository: RoomSshProviderRepository,
    effects: OperationalInboundEffects,
    compatibilityEffects: OperationalInboundCompatibilityEffects,
): BrokerCustodyRelayRuntimeComponents {
    val operationalDispatch = RoomOperationalInboundDispatch(
        database = operationalDatabase,
        effects = effects,
        compatibilityEffects = compatibilityEffects,
        sealPort = SealInboundReceiptPort(sealRepository::acceptWithReceipt),
        sshPort = SshInboundReceiptPort(sshRepository::acceptWithReceipt),
    )
    val coreProcessor = CoreCommandProcessor(
        preparation = DefaultCoreCommandPreparation(
            snapshotReader = RepositoryCoreTrustPreparationSnapshotReader(coreRepository),
            identitySignerLoader = AndroidExistingIdentitySignerLoader(),
            incomingTrustPolicy = IncomingTrustPolicy.MANUAL,
        ),
        core = RepositoryCoreCommandAuthority(coreRepository),
        finalizer = RoomCoreCommandReceiptFinalizer.forDatabase(operationalDatabase),
    )
    return BrokerCustodyRelayRuntimeFactory.create(
        broker = broker,
        secureEnvelopeTransport = SecureEnvelopeTransport(
            identitySigner = identity,
            operationalSigner = operationalSigner,
            myHpkePrivate = hpkeKeyring::privateKeyset,
            transport = broker,
            directory = TrustPeerDirectory(trust),
        ),
        operationalDatabase = operationalDatabase,
        maintenanceGate = maintenanceGate,
        operationalDispatch = operationalDispatch,
        coreDispatch = CoreInboundDispatch(coreProcessor),
        handledReplay = operationalDispatch,
    )
}

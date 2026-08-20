package net.extrawdw.apps.notisync.composition.runtime

import java.security.MessageDigest
import kotlinx.coroutines.flow.first
import net.extrawdw.apps.notisync.crypto.AndroidIdentitySigner
import net.extrawdw.apps.notisync.crypto.AndroidOperationalSigner
import net.extrawdw.apps.notisync.crypto.EpochHpkeKeyring
import net.extrawdw.apps.notisync.crypto.KeyVault
import net.extrawdw.apps.notisync.data.TrustStore
import net.extrawdw.apps.notisync.data.storage.core.CoreFoundationRepository
import net.extrawdw.apps.notisync.data.storage.core.RoomCoreAuthTokenStore
import net.extrawdw.apps.notisync.data.storage.core.CoreTransportSnapshot
import net.extrawdw.apps.notisync.data.storage.core.CryptoEpochSnapshot
import net.extrawdw.apps.notisync.data.storage.core.CryptoEpochState
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.peer.transport.AuthTokenStore

/** Exact load-existing crypto and signed-trust authority needed by the post-migration runtime. */
internal class CoreRoomRuntime private constructor(
    val identity: AndroidIdentitySigner,
    val operational: AndroidOperationalSigner,
    val activeEpoch: Int,
    val transport: CoreTransportSnapshot,
    val trust: TrustStore,
    val authTokenStore: AuthTokenStore,
    private val hpkePrivateByEpoch: Map<Int, ByteArray>,
    private val hpkePublicByEpoch: Map<Int, ByteArray>,
) : EpochHpkeKeyring {
    fun hpkePrivate(epoch: Int): ByteArray? = hpkePrivateByEpoch[epoch]?.copyOf()
    fun hpkePublic(epoch: Int): ByteArray? = hpkePublicByEpoch[epoch]?.copyOf()

    override fun loadOrCreate(epoch: Int): ByteArray =
        hpkePublic(epoch) ?: error("Room runtime cannot create an unjournaled HPKE epoch")

    override fun publicKeyset(epoch: Int): ByteArray? = hpkePublic(epoch)

    override fun privateKeyset(epoch: Int): ByteArray? = hpkePrivate(epoch)

    override fun retainedEpochs(): List<Int> = hpkePrivateByEpoch.keys.sorted()

    override fun prune(keep: Set<Int>) {
        check(hpkePrivateByEpoch.keys.all(keep::contains)) {
            "Room runtime cannot delete Core crypto epochs outside a reconciled transition"
        }
    }

    companion object {
        suspend fun loadExisting(repository: CoreFoundationRepository): CoreRoomRuntime {
            val identityRow = requireNotNull(repository.identity.first()) { "Core identity authority is missing" }
            val transport = requireNotNull(repository.transport.first()) { "Core transport authority is missing" }
            val identity = requireNotNull(AndroidIdentitySigner.loadExisting(identityRow.keyAlias)) {
                "Core identity alias is missing"
            }
            check(identity.clientId.value == identityRow.clientId)
            check(MessageDigest.isEqual(identity.publicKeySpki, identityRow.publicSpki))

            val epochs = repository.cryptoEpochs.first()
            val active = epochs.singleOrNull { it.lifecycleState == CryptoEpochState.ACTIVE }
                ?: error("Core must have exactly one active crypto epoch")
            val operational = requireNotNull(
                AndroidOperationalSigner.loadExisting(
                    clientId = ClientId(identityRow.clientId),
                    epoch = active.epoch,
                    alias = active.operationalSignerAlias,
                ),
            ) { "Core operational signer alias is missing" }
            check(MessageDigest.isEqual(
                operational.operationalPublicKeySpki,
                active.operationalSignerPublicSpki,
            ))

            val vault = requireNotNull(KeyVault.loadExisting()) { "Core wrapping-key alias is missing" }
            val privateKeys = epochs.associate { epoch ->
                epoch.epoch to vault.unwrap(requireNotNull(epoch.hpkePrivateKeysetWrapped))
            }
            val publicKeys = epochs.associate { it.epoch to it.hpkePublicKeyset.copyOf() }
            check(privateKeys.containsKey(active.epoch) && publicKeys.containsKey(active.epoch))

            return CoreRoomRuntime(
                identity = identity,
                operational = operational,
                activeEpoch = active.epoch,
                transport = transport,
                trust = TrustStore(repository, identity),
                authTokenStore = RoomCoreAuthTokenStore(repository, vault),
                hpkePrivateByEpoch = privateKeys.defensiveCopy(),
                hpkePublicByEpoch = publicKeys.defensiveCopy(),
            )
        }

        private fun Map<Int, ByteArray>.defensiveCopy(): Map<Int, ByteArray> =
            mapValues { (_, bytes) -> bytes.copyOf() }
    }
}

package net.extrawdw.apps.notisync.foundation

import net.extrawdw.apps.notisync.channel.PeerDirectory
import net.extrawdw.apps.notisync.channel.PeerKeys
import net.extrawdw.apps.notisync.channel.Recipients
import net.extrawdw.apps.notisync.data.TrustState
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.crypto.RecipientKey
import java.util.Base64

/**
 * [PeerDirectory] over the trust roster — the foundation's implementation of the channel's key port.
 *
 * Reads [TrustStore.activePeers] (TRUSTED ∩ card-held) at call time and NEVER caches, so a newly
 * paired or just-revoked peer is reflected immediately — matching the old `peersProvider` snapshot
 * semantics exactly. The base64 key fields are decoded here so the channel deals only in bytes.
 */
class TrustPeerDirectory(private val trust: TrustState) : PeerDirectory {
    private val b64 = Base64.getDecoder()

    override fun lookup(id: ClientId): PeerKeys? =
        trust.activePeers.value.firstOrNull { it.clientId == id }?.let {
            PeerKeys(
                identitySpki = b64.decode(it.identityPublicKeyB64),
                hpkePublicKeyset = b64.decode(it.hpkePublicKeysetB64),
                ownDevice = it.ownDevice,
            )
        }

    override fun recipients(scope: Recipients): List<RecipientKey> {
        val peers = trust.activePeers.value
        val selected = when (scope) {
            Recipients.OwnMesh -> peers.filter { it.ownDevice }
            Recipients.AllTrusted -> peers
            // Unicast is own-mesh only, matching the original sendCard/sendAssetSync `&& it.ownDevice`
            // guard: a body-controlled id (e.g. a notification's sourceClientId) must never cause a send
            // to a trusted "other" (non-own) contact device.
            is Recipients.Only -> peers.filter { it.clientId == scope.id && it.ownDevice }
        }
        return selected.map { RecipientKey(it.clientId, b64.decode(it.hpkePublicKeysetB64)) }
    }
}

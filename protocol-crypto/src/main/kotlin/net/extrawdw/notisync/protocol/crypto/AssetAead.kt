package net.extrawdw.notisync.protocol.crypto

import net.extrawdw.notisync.protocol.AssetAad
import net.extrawdw.notisync.protocol.Base32
import net.extrawdw.notisync.protocol.PrivateAssetRef
import net.extrawdw.notisync.protocol.ProtocolCodec

/**
 * AEAD for a single private notification graphic. Reuses [BodyAead] (AES-256-GCM, `nonce‖ct`, JCA)
 * — no new cipher, no Tink for asset bytes — keyed by a fresh per-asset [PrivateAssetRef.assetKey].
 *
 * The additional authenticated data is the CBOR encoding of [AssetAad] (suite, sourceClientId,
 * assetId, mimeType, sizeBytes, role), built verbatim from the same [PrivateAssetRef] on both ends.
 * Unlike the envelope signature (verified over the exact transmitted bytes), this AAD is *re-encoded*
 * on each side, so it relies on CBOR being deterministic for a fixed class + config — which the
 * pinned kotlinx-serialization gives us, and a cross-stack test pins.
 *
 * Encrypt-once-per-asset: each call uses a fresh random GCM nonce (safe), and a given [assetKey] is
 * bound 1:1 to one plaintext ([PrivateAssetRef.assetHash]) — never reuse a key for other bytes.
 */
object AssetAead {

    /** A fresh 256-bit per-asset key. */
    fun generateAssetKey(): ByteArray = BodyAead.generateDek()

    /** A fresh opaque 192-bit server key id (Base32, ~39 chars), unrelated to the asset's content. */
    fun generateAssetId(): String = Base32.encode(ByteArray(24).also { secureRandom.nextBytes(it) })

    /** The exact AAD bytes bound into the blob — exposed so both ends (and tests) build them identically. */
    fun aadBytes(ref: PrivateAssetRef): ByteArray = ProtocolCodec.encodeToCbor(
        AssetAad(ref.suite, ref.sourceClientId, ref.assetId, ref.mimeType, ref.sizeBytes, ref.role)
    )

    /** Seal [plaintext] under [ref]'s key + AAD. Returns `nonce(12)‖ciphertext+tag`. */
    fun seal(ref: PrivateAssetRef, plaintext: ByteArray): ByteArray =
        BodyAead.seal(ref.assetKey, plaintext, aadBytes(ref))

    /** Open a [sealed] blob for [ref]. Throws on a wrong key / corrupt blob (GCM auth failure). */
    fun open(ref: PrivateAssetRef, sealed: ByteArray): ByteArray =
        BodyAead.open(ref.assetKey, sealed, aadBytes(ref))
}

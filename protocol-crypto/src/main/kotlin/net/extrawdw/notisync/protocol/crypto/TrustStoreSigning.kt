package net.extrawdw.notisync.protocol.crypto

import net.extrawdw.notisync.protocol.ClientId
import java.security.MessageDigest
import java.util.Base64

/**
 * Detached signature over the on-disk trust store, binding the persisted roster to this device's
 * identity key. Without it the roster is unauthenticated JSON: a local attacker (rooted device,
 * backup/restore, or anything that can write app storage) could promote an arbitrary device to
 * TRUSTED and have us seal notifications to — and accept them from — their keys.
 *
 * Mirrors [HttpRequestSigning]: a versioned, newline-delimited canonical string is signed with the
 * ECDSA-P256 identity key. The persisted sections are folded in by SHA-256 digest (not raw
 * JSON), so the signed input is fixed-size and unambiguous regardless of section contents. Two
 * deliberate properties:
 *  - The [VERSION] tag domain-separates this from HTTP-request signatures and from CBOR `SignedBlob`
 *    payloads, so a signature minted in one context can never be replayed as another.
 *  - Binding [ClientId] ties the roster to *this* device's identity, so a roster lifted from another
 *    device (or surviving an identity-key regen) fails verification.
 *
 * Verifying on load is a pure-software public-key check ([IdentityVerifier.verify]) — no Keystore
 * round-trip — which is why reusing the identity key is cheaper here than a Keystore-held HMAC.
 */
object TrustStoreSigning {
    const val VERSION = "notisync-truststore-sign-v1"

    private val b64Url: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val b64UrlDecoder: Base64.Decoder = Base64.getUrlDecoder()

    private fun digest(section: String): String =
        b64Url.encodeToString(MessageDigest.getInstance("SHA-256").digest(section.toByteArray(Charsets.UTF_8)))

    /**
     * The exact bytes signed/verified: version, signer id, then a digest of each persisted section. NS2
     * folds in a fourth section ([epochsJson]) — the anti-rollback epoch floor + per-peer generation ring
     * (§6) — so the floor is tamper-proof (covered by the same identity signature + tamper-quarantine) and
     * can live neither on the disposable broker nor in a strippable sidecar.
     */
    fun canonical(
        selfId: ClientId,
        entriesJson: String,
        cardsJson: String,
        overlaysJson: String,
        epochsJson: String,
    ): ByteArray =
        buildString {
            append(VERSION).append('\n')
            append(selfId.value).append('\n')
            append(digest(entriesJson)).append('\n')
            append(digest(cardsJson)).append('\n')
            append(digest(overlaysJson)).append('\n')
            append(digest(epochsJson))
        }.toByteArray(Charsets.UTF_8)

    /** Sign the current sections; returns a base64url DER signature to persist alongside them. */
    fun sign(
        signer: IdentitySigner,
        entriesJson: String,
        cardsJson: String,
        overlaysJson: String,
        epochsJson: String,
    ): String =
        b64Url.encodeToString(signer.sign(canonical(signer.clientId, entriesJson, cardsJson, overlaysJson, epochsJson)))

    /** True iff [signatureB64] is this device's signature over exactly these (four) sections. */
    fun verify(
        publicKeySpki: ByteArray,
        selfId: ClientId,
        entriesJson: String,
        cardsJson: String,
        overlaysJson: String,
        epochsJson: String,
        signatureB64: String,
    ): Boolean {
        val sig = runCatching { b64UrlDecoder.decode(signatureB64) }.getOrNull() ?: return false
        return IdentityVerifier.verify(publicKeySpki, canonical(selfId, entriesJson, cardsJson, overlaysJson, epochsJson), sig)
    }

    /**
     * Migration fallback: verify a pre-NS2 roster signed over only the original THREE sections (no epoch
     * section existed). Used by [net.extrawdw.notisync.protocol.crypto] consumers on load when no epoch
     * section is persisted yet — a valid legacy roster is accepted (not quarantined) and re-signed as a
     * four-section store on its next write. Domain-separated from [canonical] purely by section count.
     */
    fun verifyLegacyThreeSection(
        publicKeySpki: ByteArray,
        selfId: ClientId,
        entriesJson: String,
        cardsJson: String,
        overlaysJson: String,
        signatureB64: String,
    ): Boolean {
        val sig = runCatching { b64UrlDecoder.decode(signatureB64) }.getOrNull() ?: return false
        val legacy = buildString {
            append(VERSION).append('\n')
            append(selfId.value).append('\n')
            append(digest(entriesJson)).append('\n')
            append(digest(cardsJson)).append('\n')
            append(digest(overlaysJson))
        }.toByteArray(Charsets.UTF_8)
        return IdentityVerifier.verify(publicKeySpki, legacy, sig)
    }
}

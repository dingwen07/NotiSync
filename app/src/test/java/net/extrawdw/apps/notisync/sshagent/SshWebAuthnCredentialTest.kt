package net.extrawdw.apps.notisync.sshagent

import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.extrawdw.notisync.ssh.core.SshKeyType
import net.extrawdw.notisync.ssh.core.SshPublicKeyCodec
import net.extrawdw.notisync.ssh.core.SshSignatureMethod
import net.extrawdw.notisync.ssh.core.SshSignatureVerifier
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SshWebAuthnCredentialTest {
    @Test
    fun registrationAndAssertionProduceOpenSshWebAuthnCredential() {
        val keyPair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
        val origin = "android:apk-key-hash:${base64Url(ByteArray(32) { it.toByte() })}"
        val credentialId = ByteArray(32) { (it + 1).toByte() }
        val prepared = SshWebAuthnCredential.prepareRegistration("Test WebAuthn key")
        val registered = SshWebAuthnCredential.parseRegistration(
            prepared,
            registrationResponse(prepared, keyPair, credentialId, origin),
            setOf(origin),
        )

        val decoded = SshPublicKeyCodec.decode(registered.publicKeyBlob)
        assertEquals(SshKeyType.WEBAUTHN_SK_ECDSA_NISTP256, decoded.type)
        assertEquals(SshWebAuthnCredential.RP_ID, decoded.application)
        assertArrayEquals(credentialId, registered.credentialId)
        assertTrue(registered.backupEligible)
        assertTrue(registered.backupState)

        val challenge = "exact SSH2_AGENTC_SIGN_REQUEST bytes".encodeToByteArray()
        val stored = StoredSshWebAuthnCredential(
            providerKeyId = "1".repeat(32),
            publicKeyBlob = registered.publicKeyBlob,
            credentialId = registered.credentialId,
            userHandle = registered.userHandle,
            rpId = registered.rpId,
            cosePublicKey = registered.cosePublicKey,
            createdOrigin = registered.createdOrigin,
            backupEligible = registered.backupEligible,
            backupState = registered.backupState,
        )
        val assertion = SshWebAuthnCredential.parseAssertion(
            stored,
            challenge,
            assertionResponse(stored, challenge, keyPair, origin),
            setOf(origin),
        )

        assertTrue(
            SshSignatureVerifier.verify(
                registered.publicKeyBlob,
                challenge,
                assertion.signatureBlob,
                SshSignatureMethod.WEBAUTHN_SK_ECDSA_NISTP256,
            ),
        )
        assertTrue(assertion.backupEligible)
        assertTrue(assertion.backupState)
        assertFalse(
            SshSignatureVerifier.verify(
                registered.publicKeyBlob,
                challenge + 0,
                assertion.signatureBlob,
                SshSignatureMethod.WEBAUTHN_SK_ECDSA_NISTP256,
            ),
        )
    }

    @Test
    fun assertionRejectsOriginCredentialAndUserVerificationMismatches() {
        val keyPair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
        val origin = "android:apk-key-hash:${base64Url(ByteArray(32) { 7 })}"
        val prepared = SshWebAuthnCredential.prepareRegistration("Test WebAuthn key")
        val registered = SshWebAuthnCredential.parseRegistration(
            prepared,
            registrationResponse(prepared, keyPair, ByteArray(32) { 9 }, origin),
            setOf(origin),
        )
        val stored = StoredSshWebAuthnCredential(
            providerKeyId = "2".repeat(32),
            publicKeyBlob = registered.publicKeyBlob,
            credentialId = registered.credentialId,
            userHandle = registered.userHandle,
            rpId = registered.rpId,
            cosePublicKey = registered.cosePublicKey,
            createdOrigin = registered.createdOrigin,
            backupEligible = true,
            backupState = true,
        )
        val challenge = ByteArray(64) { it.toByte() }

        assertTrue(
            runCatching {
                SshWebAuthnCredential.parseAssertion(
                    stored,
                    challenge,
                    assertionResponse(stored, challenge, keyPair, origin),
                    setOf("android:apk-key-hash:untrusted"),
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                SshWebAuthnCredential.parseAssertion(
                    stored.copy(credentialId = ByteArray(32) { 8 }),
                    challenge,
                    assertionResponse(stored, challenge, keyPair, origin),
                    setOf(origin),
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                SshWebAuthnCredential.parseAssertion(
                    stored,
                    challenge,
                    assertionResponse(stored, challenge, keyPair, origin, flags = FLAG_UP or FLAG_BE or FLAG_BS),
                    setOf(origin),
                )
            }.isFailure,
        )
    }

    private fun registrationResponse(
        prepared: PreparedSshWebAuthnRegistration,
        keyPair: KeyPair,
        credentialId: ByteArray,
        origin: String,
    ): String {
        val publicKey = keyPair.public as ECPublicKey
        val coseKey = cborMap(
            cborInteger(1) to cborInteger(2),
            cborInteger(3) to cborInteger(-7),
            cborInteger(-1) to cborInteger(1),
            cborInteger(-2) to cborBytes(publicKey.w.affineX.fixedUnsigned(32)),
            cborInteger(-3) to cborBytes(publicKey.w.affineY.fixedUnsigned(32)),
        )
        val authData = sha256(SshWebAuthnCredential.RP_ID.encodeToByteArray()) +
            byteArrayOf((FLAG_UP or FLAG_UV or FLAG_BE or FLAG_BS or FLAG_AT).toByte()) +
            counter(0) + ByteArray(16) +
            byteArrayOf((credentialId.size ushr 8).toByte(), credentialId.size.toByte()) +
            credentialId + coseKey
        val attestationObject = cborMap(
            cborText("fmt") to cborText("none"),
            cborText("attStmt") to cborMap(),
            cborText("authData") to cborBytes(authData),
        )
        val clientData = buildJsonObject {
            put("type", "webauthn.create")
            put("challenge", base64Url(prepared.challenge))
            put("origin", origin)
            put("crossOrigin", false)
        }.toString().encodeToByteArray()
        return buildJsonObject {
            put("id", base64Url(credentialId))
            put("rawId", base64Url(credentialId))
            put("type", "public-key")
            put("response", buildJsonObject {
                put("clientDataJSON", base64Url(clientData))
                put("attestationObject", base64Url(attestationObject))
            })
        }.toString()
    }

    private fun assertionResponse(
        stored: StoredSshWebAuthnCredential,
        challenge: ByteArray,
        keyPair: KeyPair,
        origin: String,
        flags: Int = FLAG_UP or FLAG_UV or FLAG_BE or FLAG_BS,
    ): String {
        val clientData = buildJsonObject {
            put("type", "webauthn.get")
            put("challenge", base64Url(challenge))
            put("origin", origin)
            put("crossOrigin", false)
        }.toString().encodeToByteArray()
        val authenticatorData = sha256(stored.rpId.encodeToByteArray()) +
            byteArrayOf(flags.toByte()) + counter(7)
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(authenticatorData + sha256(clientData))
            sign()
        }
        return buildJsonObject {
            put("id", base64Url(stored.credentialId))
            put("rawId", base64Url(stored.credentialId))
            put("type", "public-key")
            put("response", buildJsonObject {
                put("clientDataJSON", base64Url(clientData))
                put("authenticatorData", base64Url(authenticatorData))
                put("signature", base64Url(signature))
                put("userHandle", base64Url(stored.userHandle))
            })
        }.toString()
    }

    private fun cborMap(vararg entries: Pair<ByteArray, ByteArray>): ByteArray =
        cborHead(5, entries.size) + entries.flatMap { listOf(it.first, it.second) }.fold(ByteArray(0), ByteArray::plus)

    private fun cborText(value: String): ByteArray =
        value.encodeToByteArray().let { cborHead(3, it.size) + it }

    private fun cborBytes(value: ByteArray): ByteArray = cborHead(2, value.size) + value

    private fun cborInteger(value: Int): ByteArray = if (value >= 0) {
        cborHead(0, value)
    } else {
        cborHead(1, -1 - value)
    }

    private fun cborHead(major: Int, value: Int): ByteArray = when {
        value < 24 -> byteArrayOf(((major shl 5) or value).toByte())
        value <= 0xff -> byteArrayOf(((major shl 5) or 24).toByte(), value.toByte())
        else -> byteArrayOf(((major shl 5) or 25).toByte(), (value ushr 8).toByte(), value.toByte())
    }

    private fun counter(value: Int) = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun BigInteger.fixedUnsigned(size: Int): ByteArray {
        val encoded = toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        require(encoded.size <= size)
        return ByteArray(size - encoded.size) + encoded
    }

    private fun base64Url(value: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    private fun sha256(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)

    private companion object {
        const val FLAG_UP = 0x01
        const val FLAG_UV = 0x04
        const val FLAG_BE = 0x08
        const val FLAG_BS = 0x10
        const val FLAG_AT = 0x40
    }
}

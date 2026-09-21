package net.extrawdw.apps.notisync.sshkeyprovider

import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
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
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.signers.StandardDSAEncoding

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
        val printableUserId = prepared.userId.decodeToString()
        assertEquals(printableUserId, SshWebAuthnCredential.passwordRecordId(prepared.userId))
        assertTrue(printableUserId.startsWith("notisync-ssh:"))
        assertEquals(56, prepared.userId.size)
        assertTrue(prepared.requestJson.contains("\"id\":\"${base64Url(prepared.userId)}\""))
        assertTrue(prepared.requestJson.contains("\"name\":\"$printableUserId\""))
        assertArrayEquals(prepared.userId, registered.userHandle)

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

    @Test
    fun recoveryRecordRoundTripsAndMatchesTheSameWebAuthnCredential() {
        val keyPair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
        val origin = "android:apk-key-hash:${base64Url(ByteArray(32) { 3 })}"
        val credentialId = ByteArray(32) { (it + 11).toByte() }
        val registration = SshWebAuthnCredential.prepareRegistration("Recovery key")
        val registered = SshWebAuthnCredential.parseRegistration(
            registration,
            registrationResponse(registration, keyPair, credentialId, origin),
            setOf(origin),
        )
        val encoded = SshWebAuthnCredential.encodeRecoveryRecord(
            registered,
            "Recovery key",
            1_725_000_000_000L,
        )
        assertTrue(encoded.contains("\"version\":2"))
        assertFalse(encoded.contains("createdOrigin"))
        val record = SshWebAuthnCredential.decodeRecoveryRecord(encoded)

        assertArrayEquals(credentialId, record.credentialId)
        assertEquals("Recovery key", record.displayName)
        assertEquals(
            SshWebAuthnCredential.passwordRecordId(registered.userHandle),
            SshWebAuthnCredential.passwordRecordId(record.userHandle),
        )

        val recovery = SshWebAuthnCredential.prepareRecovery()
        assertFalse(recovery.requestJson.contains("allowCredentials"))
        val assertionResponse = assertionResponse(
            record.storedCredential(),
            recovery.challenge,
            keyPair,
            origin,
        )
        assertArrayEquals(
            credentialId,
            SshWebAuthnCredential.assertionCredentialId(assertionResponse),
        )
        assertArrayEquals(
            registered.userHandle,
            SshWebAuthnCredential.assertionUserHandle(assertionResponse),
        )
        val assertion = SshWebAuthnCredential.parseAssertion(
            record.storedCredential(),
            recovery.challenge,
            assertionResponse,
            setOf(origin),
        )
        val recovered = record.registeredCredential(assertion)
        assertArrayEquals(registered.publicKeyBlob, recovered.publicKeyBlob)
        assertArrayEquals(registered.credentialId, recovered.credentialId)
        assertTrue(recovered.backupEligible)
        assertTrue(recovered.backupState)
    }

    @Test
    fun legacyRecoveryRecordIgnoresHistoricalCreatedOrigin() {
        val keyPair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
        val origin = "android:apk-key-hash:${base64Url(ByteArray(32) { 8 })}"
        val registration = SshWebAuthnCredential.prepareRegistration("Legacy recovery key")
        val registered = SshWebAuthnCredential.parseRegistration(
            registration,
            registrationResponse(registration, keyPair, ByteArray(32) { 14 }, origin),
            setOf(origin),
        )
        val current = SshWebAuthnCredential.encodeRecoveryRecord(registered, "Legacy recovery key", 789L)
        val legacy = current.replace(
            "\"version\":2",
            "\"version\":1,\"createdOrigin\":\"$origin\"",
        )

        val decoded = SshWebAuthnCredential.decodeRecoveryRecord(legacy)

        assertArrayEquals(registered.credentialId, decoded.credentialId)
        assertEquals("Legacy recovery key", decoded.displayName)
    }

    @Test
    fun recoveryRecordSupportsSingleDeviceWebAuthnCredential() {
        val keyPair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
        val origin = "android:apk-key-hash:${base64Url(ByteArray(32) { 4 })}"
        val registration = SshWebAuthnCredential.prepareRegistration("Security key")
        val registered = SshWebAuthnCredential.parseRegistration(
            registration,
            registrationResponse(registration, keyPair, ByteArray(32) { 12 }, origin),
            setOf(origin),
        ).copy(
            backupEligible = false,
            backupState = false,
        )

        val record = SshWebAuthnCredential.decodeRecoveryRecord(
            SshWebAuthnCredential.encodeRecoveryRecord(registered, "Security key", 456L),
        )

        assertFalse(record.backupEligible)
        assertFalse(record.backupState)
        assertFalse(record.registeredCredential().backupEligible)
    }

    @Test
    fun recoveryRecordRejectsMismatchedPublicMetadata() {
        val keyPair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
        val origin = "android:apk-key-hash:${base64Url(ByteArray(32) { 5 })}"
        val registration = SshWebAuthnCredential.prepareRegistration("Recovery key")
        val registered = SshWebAuthnCredential.parseRegistration(
            registration,
            registrationResponse(registration, keyPair, ByteArray(32) { 6 }, origin),
            setOf(origin),
        )
        val encoded = SshWebAuthnCredential.encodeRecoveryRecord(registered, "Recovery key", 123L)
        val publicBlob = base64Url(registered.publicKeyBlob)
        val changedBlob = registered.publicKeyBlob.copyOf().also {
            it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
        }
        val tampered = encoded.replace(publicBlob, base64Url(changedBlob))

        assertTrue(runCatching { SshWebAuthnCredential.decodeRecoveryRecord(tampered) }.isFailure)
    }

    @Test
    fun twoAssertionsRecoverTheOriginalSshIdentityAndAnImportableRecord() {
        for (flags in listOf(FLAG_UP or FLAG_UV, FLAG_UP or FLAG_UV or FLAG_BE, FLAG_UP or FLAG_UV or FLAG_BE or FLAG_BS)) {
            val (keyPair, stored) = recoveryFixture()
            val prepared = SshWebAuthnCredential.prepareRecovery()
            val first = assertionResponse(stored, prepared.challenge, keyPair, RECOVERY_ORIGIN, flags)
            val pending = SshWebAuthnCredential.prepareKeyRecovery(prepared, first, setOf(RECOVERY_ORIGIN))
            assertFalse(prepared.challenge.contentEquals(pending.challenge))
            assertTrue(pending.requestJson.contains("\"id\":\"${base64Url(stored.credentialId)}\""))
            assertTrue(pending.requestJson.contains("\"userVerification\":\"required\""))
            val recovered = SshWebAuthnCredential.completeKeyRecovery(
                pending, assertionResponse(stored, pending.challenge, keyPair, RECOVERY_ORIGIN, flags),
            )
            assertArrayEquals(stored.publicKeyBlob, recovered.publicKeyBlob)
            assertArrayEquals(stored.cosePublicKey, recovered.cosePublicKey)
            assertArrayEquals(stored.credentialId, recovered.credentialId)
            assertArrayEquals(stored.userHandle, recovered.userHandle)
            assertEquals(flags and FLAG_BE != 0, recovered.backupEligible)
            assertEquals(flags and FLAG_BS != 0, recovered.backupState)
            val record = SshWebAuthnCredential.decodeRecoveryRecord(
                SshWebAuthnCredential.encodeRecoveryRecord(recovered, "Recovered key", 456L),
            )
            assertArrayEquals(stored.publicKeyBlob, record.publicKeyBlob)
            // Recovery must preserve the key used for subsequent real SSH assertions.
            val challenge = "SSH request after recovery".encodeToByteArray()
            SshWebAuthnCredential.parseAssertion(
                record.storedCredential(), challenge,
                assertionResponse(stored, challenge, keyPair, RECOVERY_ORIGIN, flags), setOf(RECOVERY_ORIGIN),
            )
        }
    }

    @Test
    fun recoveryRejectsInvalidFirstAssertionContextAndScalars() {
        val (keyPair, stored) = recoveryFixture()
        val prepared = SshWebAuthnCredential.prepareRecovery()
        val valid = assertionResponse(stored, prepared.challenge, keyPair, RECOVERY_ORIGIN)
        val badResponses = listOf(
            assertionResponse(stored, prepared.challenge + 0, keyPair, RECOVERY_ORIGIN),
            assertionResponse(stored, prepared.challenge, keyPair, "https://untrusted.example"),
            assertionResponse(stored.copy(rpId = "untrusted.example"), prepared.challenge, keyPair, RECOVERY_ORIGIN),
            assertionResponse(stored, prepared.challenge, keyPair, RECOVERY_ORIGIN, FLAG_UP),
            assertionResponse(stored, prepared.challenge, keyPair, RECOVERY_ORIGIN, FLAG_UV),
            assertionResponse(stored, prepared.challenge, keyPair, RECOVERY_ORIGIN, FLAG_UP or FLAG_UV or FLAG_BS),
            assertionResponse(stored, prepared.challenge, keyPair, RECOVERY_ORIGIN, FLAG_UP or FLAG_UV or FLAG_AT),
            assertionResponse(stored, prepared.challenge, keyPair, RECOVERY_ORIGIN, FLAG_UP or FLAG_UV or 0x80),
            responseField(valid, "userHandle", JsonNull),
            responseField(valid, "userHandle", JsonPrimitive(base64Url("foreign user".encodeToByteArray()))),
            responseField(valid, "signature", JsonPrimitive(base64Url(byteArrayOf(0x30, 6, 2, 1, 0, 2, 1, 1)))),
            responseField(valid, "signature", JsonPrimitive(base64Url(byteArrayOf(0x30, 1, 0)))),
        )
        badResponses.forEachIndexed { index, response ->
            assertTrue("accepted invalid first assertion $index", runCatching {
                SshWebAuthnCredential.prepareKeyRecovery(prepared, response, setOf(RECOVERY_ORIGIN))
            }.isFailure)
        }
    }

    @Test
    fun recoveryRejectsReplayDifferentCredentialsAndChangedSigningKeys() {
        val (keyPair, stored) = recoveryFixture()
        val prepared = SshWebAuthnCredential.prepareRecovery()
        val first = assertionResponse(stored, prepared.challenge, keyPair, RECOVERY_ORIGIN)
        val pending = SshWebAuthnCredential.prepareKeyRecovery(prepared, first, setOf(RECOVERY_ORIGIN))
        val (otherKey, otherStored) = recoveryFixture()
        val badResponses = listOf(
            first,
            assertionResponse(stored, pending.challenge, otherKey, RECOVERY_ORIGIN),
            assertionResponse(stored.copy(credentialId = otherStored.credentialId), pending.challenge, keyPair, RECOVERY_ORIGIN),
            assertionResponse(stored.copy(userHandle = otherStored.userHandle), pending.challenge, keyPair, RECOVERY_ORIGIN),
            assertionResponse(stored, pending.challenge, keyPair, "https://untrusted.example"),
            assertionResponse(stored.copy(rpId = "untrusted.example"), pending.challenge, keyPair, RECOVERY_ORIGIN),
            assertionResponse(stored, pending.challenge, keyPair, RECOVERY_ORIGIN, FLAG_UP),
            assertionResponse(stored, pending.challenge, keyPair, RECOVERY_ORIGIN, FLAG_UP or FLAG_UV),
        )
        badResponses.forEachIndexed { index, response ->
            assertTrue("accepted invalid confirmation $index", runCatching {
                SshWebAuthnCredential.completeKeyRecovery(pending, response)
            }.isFailure)
        }
    }

    @Test
    fun recoveryAcceptsMissingUserHandleOnTargetedConfirmationAndUpdatesBackupState() {
        val (keyPair, stored) = recoveryFixture()
        val prepared = SshWebAuthnCredential.prepareRecovery()
        val pending = SshWebAuthnCredential.prepareKeyRecovery(
            prepared, assertionResponse(stored, prepared.challenge, keyPair, RECOVERY_ORIGIN), setOf(RECOVERY_ORIGIN),
        )
        val confirmation = assertionResponse(stored, pending.challenge, keyPair, RECOVERY_ORIGIN, FLAG_UP or FLAG_UV or FLAG_BE)
        val recovered = SshWebAuthnCredential.completeKeyRecovery(pending, responseField(confirmation, "userHandle", JsonNull))
        assertArrayEquals(stored.publicKeyBlob, recovered.publicKeyBlob)
        assertTrue(recovered.backupEligible)
        assertFalse(recovered.backupState)
    }

    @Test
    fun recoveryNeverUsesTheSameChallengeTwice() {
        val (keyPair, stored) = recoveryFixture()
        val prepared = SshWebAuthnCredential.prepareRecovery()
        val repeatingRandom = object : SecureRandom() {
            override fun nextBytes(bytes: ByteArray) { prepared.challenge.copyInto(bytes) }
        }
        assertTrue(runCatching {
            SshWebAuthnCredential.prepareKeyRecovery(
                prepared, assertionResponse(stored, prepared.challenge, keyPair, RECOVERY_ORIGIN),
                setOf(RECOVERY_ORIGIN), repeatingRandom,
            )
        }.isFailure)
    }

    @Test
    fun recoveryWorksWithHighAndLowSWithoutDependingOnSignatureMalleability() {
        val (keyPair, stored) = recoveryFixture()
        val prepared = SshWebAuthnCredential.prepareRecovery()
        fun withSForm(response: String, high: Boolean): String {
            val encoded = Json.parseToJsonElement(response).jsonObject.getValue("response").jsonObject
                .getValue("signature").let { (it as JsonPrimitive).content }
            val n = SECNamedCurves.getByName("secp256r1").n
            val (r, s) = StandardDSAEncoding.INSTANCE.decode(n, Base64.getUrlDecoder().decode(encoded))
            val alternate = n.subtract(s)
            val chosen = if (high) s.max(alternate) else s.min(alternate)
            return responseField(response, "signature", JsonPrimitive(base64Url(StandardDSAEncoding.INSTANCE.encode(n, r, chosen))))
        }
        val first = withSForm(assertionResponse(stored, prepared.challenge, keyPair, RECOVERY_ORIGIN), high = true)
        val pending = SshWebAuthnCredential.prepareKeyRecovery(prepared, first, setOf(RECOVERY_ORIGIN))
        val second = withSForm(assertionResponse(stored, pending.challenge, keyPair, RECOVERY_ORIGIN), high = false)
        assertArrayEquals(stored.publicKeyBlob, SshWebAuthnCredential.completeKeyRecovery(pending, second).publicKeyBlob)
        // Flipping s on a replayed first response must still fail the fresh challenge check.
        assertTrue(runCatching { SshWebAuthnCredential.completeKeyRecovery(pending, withSForm(first, high = false)) }.isFailure)
    }

    private fun recoveryFixture(): Pair<KeyPair, StoredSshWebAuthnCredential> {
        val keyPair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
        val registration = SshWebAuthnCredential.prepareRegistration("Original name")
        val credentialId = ByteArray(32).also(SecureRandom()::nextBytes)
        val credential = SshWebAuthnCredential.parseRegistration(
            registration, registrationResponse(registration, keyPair, credentialId, RECOVERY_ORIGIN), setOf(RECOVERY_ORIGIN),
        )
        val stored = SshWebAuthnCredential.decodeRecoveryRecord(
            SshWebAuthnCredential.encodeRecoveryRecord(credential, "Original name", 123L),
        ).storedCredential()
        return keyPair to stored
    }

    private fun responseField(json: String, name: String, value: kotlinx.serialization.json.JsonElement): String {
        val root = Json.parseToJsonElement(json).jsonObject
        return JsonObject(root + ("response" to JsonObject(root.getValue("response").jsonObject + (name to value)))).toString()
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
        const val RECOVERY_ORIGIN = "android:apk-key-hash:test-recovery-app"
        const val FLAG_UP = 0x01
        const val FLAG_UV = 0x04
        const val FLAG_BE = 0x08
        const val FLAG_BS = 0x10
        const val FLAG_AT = 0x40
    }
}

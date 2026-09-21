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
import net.extrawdw.notisync.ssh.core.WebAuthnSshSignatureCodec
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
    fun registrationIgnoresUnrequestedAttestationAndPreservesSshIdentity() {
        val (keyPair, stored) = recoveryFixture()
        val prepared = SshWebAuthnCredential.prepareRegistration("Provider key")
        assertEquals("none", Json.parseToJsonElement(prepared.requestJson).jsonObject["attestation"]?.let {
            (it as JsonPrimitive).content
        })
        // These opaque statements deliberately contain no valid manufacturer evidence.
        // With attestation="none", that evidence must neither be trusted nor required.
        val selfStatement = cborMap(cborText("alg") to cborInteger(-7), cborText("sig") to cborBytes(byteArrayOf(0)))
        val certificateStatement = cborMap(
            cborText("alg") to cborInteger(-257), cborText("sig") to cborBytes(byteArrayOf(0)),
            cborText("x5c") to (cborHead(4, 1) + cborBytes(byteArrayOf(0))),
        )
        val attestations = listOf(
            "none" to cborMap(), "packed" to selfStatement, "packed" to certificateStatement,
            "android-key" to certificateStatement, "vendor-format" to certificateStatement,
        )
        for ((format, statement) in attestations) {
            val response = registrationResponse(
                prepared, keyPair, stored.credentialId, RECOVERY_ORIGIN,
                attestationFormat = format, aaguid = ByteArray(16) { 1 }, attestationStatement = statement,
            )
            val registered = SshWebAuthnCredential.parseRegistration(prepared, response, setOf(RECOVERY_ORIGIN))
            assertArrayEquals(stored.publicKeyBlob, registered.publicKeyBlob)
            val restored = SshWebAuthnCredential.decodeRecoveryRecord(
                SshWebAuthnCredential.encodeRecoveryRecord(registered, "Provider key", 123L),
            ).storedCredential()
            val challenge = ByteArray(32) { it.toByte() }
            val assertion = SshWebAuthnCredential.parseAssertion(
                restored, challenge, assertionResponse(restored, challenge, keyPair, RECOVERY_ORIGIN), setOf(RECOVERY_ORIGIN),
            )
            assertTrue(SshSignatureVerifier.verify(
                restored.publicKeyBlob, challenge, assertion.signatureBlob, SshSignatureMethod.WEBAUTHN_SK_ECDSA_NISTP256,
            ))
            val (otherKey, _) = recoveryFixture()
            assertTrue(runCatching {
                SshWebAuthnCredential.parseAssertion(
                    restored, challenge, assertionResponse(restored, challenge, otherKey, RECOVERY_ORIGIN), setOf(RECOVERY_ORIGIN),
                )
            }.isFailure)
        }
    }

    @Test
    fun registrationStillRejectsInvalidCredentialDataWhenAttestationIsIgnored() {
        val (keyPair, stored) = recoveryFixture()
        val prepared = SshWebAuthnCredential.prepareRegistration("Attestation policy test")
        val invalidResponses = listOf(
            registrationResponse(prepared, keyPair, stored.credentialId, "https://untrusted.example", "packed"),
            registrationResponse(prepared.copy(challenge = ByteArray(32)), keyPair, stored.credentialId, RECOVERY_ORIGIN, "packed"),
            registrationResponse(prepared, keyPair, stored.credentialId, RECOVERY_ORIGIN, "packed", authenticatorRpId = "wrong.example"),
            registrationResponse(prepared, keyPair, stored.credentialId, RECOVERY_ORIGIN, "packed", flags = FLAG_UV or FLAG_AT),
            registrationResponse(prepared, keyPair, stored.credentialId, RECOVERY_ORIGIN, "packed", flags = FLAG_UP or FLAG_AT),
            registrationResponse(prepared, keyPair, stored.credentialId, RECOVERY_ORIGIN, "packed", flags = FLAG_UP or FLAG_UV),
            registrationResponse(prepared, keyPair, stored.credentialId, RECOVERY_ORIGIN, "packed", flags = FLAG_UP or FLAG_UV or FLAG_AT or FLAG_BS),
            registrationResponse(prepared, keyPair, stored.credentialId, RECOVERY_ORIGIN, "packed", coseAlgorithm = -257),
            registrationResponse(prepared, keyPair, stored.credentialId, RECOVERY_ORIGIN, "packed", responseCredentialId = ByteArray(32)),
        )
        invalidResponses.forEachIndexed { index, response ->
            assertTrue("accepted invalid registration $index", runCatching {
                SshWebAuthnCredential.parseRegistration(prepared, response, setOf(RECOVERY_ORIGIN))
            }.isFailure)
        }
    }

    @Test
    fun registrationStillRequiresWellFormedAttestationEnvelope() {
        val (keyPair, stored) = recoveryFixture()
        val prepared = SshWebAuthnCredential.prepareRegistration("Attestation test")
        val responses = listOf(
            registrationResponse(prepared, keyPair, stored.credentialId, RECOVERY_ORIGIN, attestationFormat = ""),
            registrationResponse(prepared, keyPair, stored.credentialId, RECOVERY_ORIGIN, attestationStatement = cborBytes(byteArrayOf(0))),
        )
        responses.forEach { response ->
            assertTrue(runCatching {
                SshWebAuthnCredential.parseRegistration(prepared, response, setOf(RECOVERY_ORIGIN))
            }.isFailure)
        }
    }

    @Test
    fun recoveryConfirmationPreservesReceivedAndExpectedOriginInError() {
        val (keyPair, stored) = recoveryFixture()
        val prepared = SshWebAuthnCredential.prepareRecovery()
        val pending = SshWebAuthnCredential.prepareKeyRecovery(
            prepared, assertionResponse(stored, prepared.challenge, keyPair, RECOVERY_ORIGIN), setOf(RECOVERY_ORIGIN),
        )
        val returnedOrigin = "https://unexpected.example"
        val failure = runCatching {
            SshWebAuthnCredential.completeKeyRecovery(
                pending, assertionResponse(stored, pending.challenge, keyPair, returnedOrigin),
            )
        }.exceptionOrNull()
        assertTrue(failure is SshWebAuthnException.UntrustedOrigin)
        val diagnostic = failure as SshWebAuthnException.UntrustedOrigin
        assertEquals(JsonPrimitive(returnedOrigin).toString(), diagnostic.received)
        assertEquals(JsonPrimitive(RECOVERY_ORIGIN).toString(), diagnostic.expected)
    }

    @Test
    fun registrationAndAssertionAcceptEquivalentAndroidCertificateHashEncodings() {
        val (keyPair, stored) = recoveryFixture()
        val certificateHash = ByteArray(32) { if (it % 2 == 0) 0xfb.toByte() else 0xff.toByte() }
        val expectedOrigin = "android:apk-key-hash:${base64Url(certificateHash)}"
        val encodedHashes = listOf(
            Base64.getEncoder().encodeToString(certificateHash),
            Base64.getEncoder().withoutPadding().encodeToString(certificateHash),
            Base64.getUrlEncoder().encodeToString(certificateHash),
            base64Url(certificateHash),
        )
        val prepared = SshWebAuthnCredential.prepareRegistration("Provider origin test")
        val challenge = ByteArray(32) { it.toByte() }
        for (encodedHash in encodedHashes) {
            val origin = "android:apk-key-hash:$encodedHash"
            val registered = SshWebAuthnCredential.parseRegistration(
                prepared, registrationResponse(prepared, keyPair, stored.credentialId, origin), setOf(expectedOrigin),
            )
            assertArrayEquals(stored.publicKeyBlob, registered.publicKeyBlob)
            val response = assertionResponse(stored, challenge, keyPair, origin)
            val parsed = SshWebAuthnCredential.parseAssertion(stored, challenge, response, setOf(expectedOrigin))
            val sshSignature = WebAuthnSshSignatureCodec.decode(parsed.signatureBlob)
            assertEquals(origin, sshSignature.origin)
            val originalClientData = Json.parseToJsonElement(response).jsonObject.getValue("response").jsonObject
                .getValue("clientDataJSON").let { Base64.getUrlDecoder().decode((it as JsonPrimitive).content) }
            assertArrayEquals(originalClientData, sshSignature.clientDataJson)
            assertTrue(SshSignatureVerifier.verify(
                stored.publicKeyBlob, challenge, parsed.signatureBlob, SshSignatureMethod.WEBAUTHN_SK_ECDSA_NISTP256,
            ))
        }
    }

    @Test
    fun recoveryAcceptsEquivalentOriginEncodingsAcrossBothAuthentications() {
        val (keyPair, stored) = recoveryFixture()
        val certificateHash = ByteArray(32) { 0xff.toByte() }
        val expectedOrigin = "android:apk-key-hash:${base64Url(certificateHash)}"
        val providerOrigin = "android:apk-key-hash:${Base64.getEncoder().withoutPadding().encodeToString(certificateHash)}"
        for ((firstOrigin, secondOrigin) in listOf(providerOrigin to expectedOrigin, expectedOrigin to providerOrigin)) {
            val prepared = SshWebAuthnCredential.prepareRecovery()
            val pending = SshWebAuthnCredential.prepareKeyRecovery(
                prepared, assertionResponse(stored, prepared.challenge, keyPair, firstOrigin), setOf(expectedOrigin),
            )
            val recovered = SshWebAuthnCredential.completeKeyRecovery(
                pending, assertionResponse(stored, pending.challenge, keyPair, secondOrigin),
            )
            assertArrayEquals(stored.publicKeyBlob, recovered.publicKeyBlob)
        }
    }

    @Test
    fun originCompatibilityRejectsDifferentHashesAndMalformedEncodings() {
        val (keyPair, stored) = recoveryFixture()
        val certificateHash = ByteArray(32) { if (it % 2 == 0) 0xfb.toByte() else 0xff.toByte() }
        val encodedHash = Base64.getEncoder().withoutPadding().encodeToString(certificateHash)
        assertTrue(encodedHash.contains('+') && encodedHash.contains('/'))
        val expectedOrigin = "android:apk-key-hash:${base64Url(certificateHash)}"
        val providerOrigin = "android:apk-key-hash:$encodedHash"
        val changedHash = certificateHash.copyOf().also { it[0] = 0 }
        // The last character has two unused bits. A permissive decoder would ignore changing these.
        val nonCanonicalTail = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".let {
            encodedHash.dropLast(1) + it[it.indexOf(encodedHash.last()) + 1]
        }
        val invalidOrigins = listOf(
            "android:apk-key-hash:${Base64.getEncoder().withoutPadding().encodeToString(changedHash)}",
            "android:apk-key-hash:${Base64.getEncoder().withoutPadding().encodeToString(ByteArray(31))}",
            "android:apk-key-hash:$nonCanonicalTail",
            "android:apk-key-hash:${encodedHash.replace('+', '-')}", // mixed alphabets
            "$providerOrigin==",
            "$providerOrigin\n",
            " $providerOrigin",
            "$providerOrigin/",
            providerOrigin.replace("android:", "Android:"),
            "https://$encodedHash",
        )
        val prepared = SshWebAuthnCredential.prepareRegistration("Rejected origin test")
        val challenge = ByteArray(32) { it.toByte() }
        for (origin in invalidOrigins) {
            val registrationFailure = runCatching {
                SshWebAuthnCredential.parseRegistration(
                    prepared, registrationResponse(prepared, keyPair, stored.credentialId, origin), setOf(expectedOrigin),
                )
            }.exceptionOrNull()
            assertTrue("accepted registration origin $origin", registrationFailure is SshWebAuthnException.UntrustedOrigin)
            val assertionFailure = runCatching {
                SshWebAuthnCredential.parseAssertion(
                    stored, challenge, assertionResponse(stored, challenge, keyPair, origin), setOf(expectedOrigin),
                )
            }.exceptionOrNull()
            assertTrue("accepted assertion origin $origin", assertionFailure is SshWebAuthnException.UntrustedOrigin)
        }
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

    @Test
    fun recoveryReportsOpenSshIncompatibilityForSignedProviderFieldOrder() {
        val (keyPair, stored) = recoveryFixture()
        val prepared = SshWebAuthnCredential.prepareRecovery()
        fun providerResponse(challenge: ByteArray) = assertionResponse(
            stored, challenge, keyPair, RECOVERY_ORIGIN,
        ) { original ->
            val fields = Json.parseToJsonElement(original).jsonObject
            // Observed Xiaomi ordering, using synthetic credentials and package name.
            buildJsonObject {
                put("androidPackageName", "example.credential.client")
                put("challenge", fields.getValue("challenge"))
                put("origin", fields.getValue("origin"))
                put("type", fields.getValue("type"))
            }.toString()
        }
        val response = providerResponse(prepared.challenge)
        val fields = Json.parseToJsonElement(response).jsonObject.getValue("response").jsonObject
        fun bytes(name: String) = Base64.getUrlDecoder().decode((fields.getValue(name) as JsonPrimitive).content)
        assertTrue(Signature.getInstance("SHA256withECDSA").run {
            initVerify(keyPair.public)
            update(bytes("authenticatorData") + sha256(bytes("clientDataJSON")))
            verify(bytes("signature"))
        })
        val pending = SshWebAuthnCredential.prepareKeyRecovery(
            prepared, assertionResponse(stored, prepared.challenge, keyPair, RECOVERY_ORIGIN), setOf(RECOVERY_ORIGIN),
        )
        val failures = listOf(
            runCatching { // Recovery record import and ordinary signing.
                SshWebAuthnCredential.parseAssertion(stored, prepared.challenge, response, setOf(RECOVERY_ORIGIN))
            },
            runCatching {
                SshWebAuthnCredential.prepareKeyRecovery(prepared, response, setOf(RECOVERY_ORIGIN))
            },
            runCatching {
                SshWebAuthnCredential.completeKeyRecovery(pending, providerResponse(pending.challenge))
            },
        )
        for (failure in failures) {
            assertTrue(failure.exceptionOrNull() is SshWebAuthnException.IncompatibleClientData)
        }
    }

    @Test
    fun assertionDistinguishesIncompatibleFormattingFromInvalidSignatures() {
        val (keyPair, stored) = recoveryFixture()
        val challenge = ByteArray(32) { it.toByte() }
        val origin = "android:apk-key-hash:${Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32) { -1 })}"
        val incompatibleFormats: List<(String) -> String> = listOf(
            { it.replace(",\"challenge\"", ", \"challenge\"") },
            { it.replace("/", "\\/") },
        )
        for (format in incompatibleFormats) {
            val response = assertionResponse(stored, challenge, keyPair, origin, clientDataTransform = format)
            val failure = runCatching {
                SshWebAuthnCredential.parseAssertion(stored, challenge, response, setOf(origin))
            }.exceptionOrNull()
            assertTrue(failure is SshWebAuthnException.IncompatibleClientData)
            val originalClientData = Json.parseToJsonElement(response).jsonObject.getValue("response").jsonObject
                .getValue("clientDataJSON").let { Base64.getUrlDecoder().decode((it as JsonPrimitive).content) }
            assertEquals(originalClientData.decodeToString(), (failure as SshWebAuthnException.IncompatibleClientData).clientDataJson)
        }
        val (otherKey, _) = recoveryFixture()
        val failure = runCatching {
            SshWebAuthnCredential.parseAssertion(
                stored, challenge, assertionResponse(stored, challenge, otherKey, origin), setOf(origin),
            )
        }.exceptionOrNull()
        assertTrue(failure is SshWebAuthnException.InvalidAssertionSignature)
    }

    @Test
    fun assertionPreservesProviderFieldsAfterTheRequiredOpenSshPrefix() {
        val (keyPair, stored) = recoveryFixture()
        val challenge = ByteArray(32) { it.toByte() }
        val response = assertionResponse(stored, challenge, keyPair, RECOVERY_ORIGIN) { original ->
            val fields = Json.parseToJsonElement(original).jsonObject
            JsonObject(fields + ("androidPackageName" to JsonPrimitive("example.credential.client"))).toString()
        }
        val parsed = SshWebAuthnCredential.parseAssertion(stored, challenge, response, setOf(RECOVERY_ORIGIN))
        val clientData = Json.parseToJsonElement(response).jsonObject.getValue("response").jsonObject
            .getValue("clientDataJSON").let { Base64.getUrlDecoder().decode((it as JsonPrimitive).content) }
        assertArrayEquals(clientData, WebAuthnSshSignatureCodec.decode(parsed.signatureBlob).clientDataJson)
        assertTrue(SshSignatureVerifier.verify(
            stored.publicKeyBlob, challenge, parsed.signatureBlob, SshSignatureMethod.WEBAUTHN_SK_ECDSA_NISTP256,
        ))
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
        attestationFormat: String = "none",
        aaguid: ByteArray = ByteArray(16),
        attestationStatement: ByteArray = cborMap(),
        authenticatorRpId: String = SshWebAuthnCredential.RP_ID,
        flags: Int = FLAG_UP or FLAG_UV or FLAG_BE or FLAG_BS or FLAG_AT,
        coseAlgorithm: Int = -7,
        responseCredentialId: ByteArray = credentialId,
    ): String {
        val publicKey = keyPair.public as ECPublicKey
        val coseKey = cborMap(
            cborInteger(1) to cborInteger(2),
            cborInteger(3) to cborInteger(coseAlgorithm),
            cborInteger(-1) to cborInteger(1),
            cborInteger(-2) to cborBytes(publicKey.w.affineX.fixedUnsigned(32)),
            cborInteger(-3) to cborBytes(publicKey.w.affineY.fixedUnsigned(32)),
        )
        val authData = sha256(authenticatorRpId.encodeToByteArray()) +
            byteArrayOf(flags.toByte()) +
            counter(0) + aaguid +
            byteArrayOf((credentialId.size ushr 8).toByte(), credentialId.size.toByte()) +
            credentialId + coseKey
        val clientData = buildJsonObject {
            put("type", "webauthn.create")
            put("challenge", base64Url(prepared.challenge))
            put("origin", origin)
            put("crossOrigin", false)
        }.toString().encodeToByteArray()
        val attestationObject = cborMap(
            cborText("fmt") to cborText(attestationFormat),
            cborText("attStmt") to attestationStatement,
            cborText("authData") to cborBytes(authData),
        )
        return buildJsonObject {
            put("id", base64Url(responseCredentialId))
            put("rawId", base64Url(responseCredentialId))
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
        clientDataTransform: (String) -> String = { it },
    ): String {
        val clientData = buildJsonObject {
            put("type", "webauthn.get")
            put("challenge", base64Url(challenge))
            put("origin", origin)
            put("crossOrigin", false)
        }.toString().let(clientDataTransform).encodeToByteArray()
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

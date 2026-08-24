package net.extrawdw.notisync.ssh.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.InvalidKeyException
import java.security.Key
import java.security.KeyFactory
import java.security.KeyFactorySpi
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Provider
import java.security.PublicKey
import java.security.Security
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.EdECPrivateKey
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.InvalidKeySpecException
import java.security.spec.KeySpec
import java.util.Base64

class SshCoreTest {
    @Test
    fun wireAndFrameCodecsHandleFragmentationAndBounds() {
        val body = SshWireWriter().writeByte(13).writeString(byteArrayOf(1, 2, 3)).writeUInt32(4).toByteArray()
        val frame = SshAgentFrameCodec.encode(body)
        val fragmented = object : InputStream() {
            private val delegate = ByteArrayInputStream(frame)
            override fun read(): Int = delegate.read()
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                if (length == 0) 0 else delegate.read(bytes, offset, 1)
        }

        assertArrayEquals(body, SshAgentFrameCodec.read(fragmented))
        assertThrows(SshWireException::class.java) {
            SshAgentFrameCodec.read(ByteArrayInputStream(byteArrayOf(0, 0, 0, 0)))
        }
        assertThrows(SshWireException::class.java) {
            SshWireReader(byteArrayOf(0, 0, 0, 5, 1)).readString(4)
        }
        assertThrows(SshWireException::class.java) {
            SshWireReader(byteArrayOf(0, 0, 0, 1, 0)).readMpInt()
        }
    }

    @Test
    fun agentMessageCodecParsesSignAndBuildsResponses() {
        val keyBlob = SshPublicKeyCodec.encode(ed25519.public)
        val requestBody = SshWireWriter()
            .writeByte(AgentNumbers.SSH_AGENTC_SIGN_REQUEST)
            .writeString(keyBlob)
            .writeString("payload".encodeToByteArray())
            .writeUInt32(0)
            .toByteArray()

        val request = AgentMessageCodec.decodeRequest(requestBody) as AgentRequest.Sign

        assertArrayEquals(keyBlob, request.publicKeyBlob)
        assertArrayEquals("payload".encodeToByteArray(), request.data)
        assertEquals(0L, request.flags)
        assertEquals(AgentNumbers.SSH_AGENT_FAILURE.toByte(), AgentMessageCodec.failure().single())
        assertEquals(
            AgentNumbers.SSH_AGENT_IDENTITIES_ANSWER,
            AgentMessageCodec.identitiesAnswer(listOf(AgentIdentity(keyBlob, "test"))).first().toInt() and 0xff,
        )

        val queryResponse = SshWireReader(
            AgentMessageCodec.extensionQueryResponse(listOf(OpenSshSessionBind.EXTENSION_NAME)),
        )
        assertEquals(AgentNumbers.SSH_AGENT_EXTENSION_RESPONSE, queryResponse.readByte())
        assertEquals("query", queryResponse.readUtf8())
        assertEquals(OpenSshSessionBind.EXTENSION_NAME, queryResponse.readUtf8())
        queryResponse.requireEnd()
    }

    @Test
    fun publicKeyRoundTripsAndSignatureVerificationCoverAllRequiredAlgorithms() {
        val cases = listOf(
            Triple(ed25519, SshSignatureMethod.ED25519, 0L),
            Triple(rsa, SshSignatureMethod.RSA_SHA2_256, AgentNumbers.SSH_AGENT_RSA_SHA2_256),
            Triple(rsa, SshSignatureMethod.RSA_SHA2_512, AgentNumbers.SSH_AGENT_RSA_SHA2_512),
            Triple(ec, SshSignatureMethod.ECDSA_NISTP256, 0L),
        )
        val data = "notisync ssh signature vector".encodeToByteArray()

        cases.forEach { (keyPair, method, flags) ->
            val publicBlob = SshPublicKeyCodec.encode(keyPair.public)
            val decoded = SshPublicKeyCodec.decode(publicBlob)
            val rawJca = sign(method, keyPair, data)
            val sshSignature = if (method == SshSignatureMethod.ECDSA_NISTP256) {
                EcdsaSignatureTranscoder.derToSsh(rawJca)
            } else rawJca
            val signatureBlob = SshSignatureCodec.encode(method, sshSignature)

            assertArrayEquals(publicBlob, SshPublicKeyCodec.encode(decoded.publicKey))
            assertEquals(method, SshSignatureVerifier.methodFor(decoded.type, flags))
            assertTrue(SshSignatureVerifier.verify(publicBlob, data, signatureBlob, method))
            assertFalse(SshSignatureVerifier.verify(publicBlob, data + byteArrayOf(0), signatureBlob, method))
            assertTrue(SshFingerprint.sha256(publicBlob).startsWith("SHA256:"))
        }
        assertThrows(SshWireException::class.java) {
            SshSignatureVerifier.methodFor(SshKeyType.RSA, 0)
        }
        assertThrows(SshWireException::class.java) {
            SshSignatureVerifier.methodFor(
                SshKeyType.RSA,
                AgentNumbers.SSH_AGENT_RSA_SHA2_256 or AgentNumbers.SSH_AGENT_RSA_SHA2_512,
            )
        }
    }

    @Test
    fun webAuthnSecurityKeyCodecAndVerifierMatchOpenSshWireFormat() {
        val application = "notisync.apps.extrawdw.net"
        val origin = "android:apk-key-hash:test-signing-certificate"
        val data = "exact SSH agent signing request".encodeToByteArray()
        val publicBlob = SshPublicKeyCodec.encodeWebAuthnEcdsaP256(ec.public, application)
        val decoded = SshPublicKeyCodec.decode(publicBlob)
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(data)
        val clientData =
            "{\"type\":\"webauthn.get\",\"challenge\":\"$challenge\",\"origin\":\"$origin\",\"crossOrigin\":false}"
                .encodeToByteArray()
        val flags = WebAuthnSshSignatureCodec.FLAG_USER_PRESENT or
            WebAuthnSshSignatureCodec.FLAG_USER_VERIFIED
        val counter = 7L
        val authenticatorData = java.security.MessageDigest.getInstance("SHA-256").digest(application.encodeToByteArray()) +
            SshWireWriter(5).writeByte(flags).writeUInt32(counter).toByteArray()
        val signed = authenticatorData +
            java.security.MessageDigest.getInstance("SHA-256").digest(clientData)
        val derSignature = Signature.getInstance("SHA256withECDSA").run {
            initSign(ec.private)
            update(signed)
            sign()
        }
        val sshEcdsaSignature = EcdsaSignatureTranscoder.derToSsh(derSignature)
        val signatureBlob = WebAuthnSshSignatureCodec.encode(
            WebAuthnSshSignature(
                ecdsaSignature = sshEcdsaSignature,
                flags = flags,
                counter = counter,
                origin = origin,
                clientDataJson = clientData,
                extensions = byteArrayOf(),
            ),
        )

        assertEquals(SshKeyType.WEBAUTHN_SK_ECDSA_NISTP256, decoded.type)
        assertEquals(application, decoded.application)
        assertEquals(SshSignatureMethod.WEBAUTHN_SK_ECDSA_NISTP256, SshSignatureCodec.decode(signatureBlob).method)
        SshWireReader(signatureBlob).also { wire ->
            assertEquals(SshSignatureMethod.WEBAUTHN_SK_ECDSA_NISTP256.wireName, wire.readUtf8())
            assertArrayEquals(sshEcdsaSignature, wire.readString())
            assertEquals(flags, wire.readByte())
            assertEquals(counter, wire.readUInt32())
            assertEquals(origin, wire.readUtf8())
            assertArrayEquals(clientData, wire.readString())
            assertArrayEquals(byteArrayOf(), wire.readString())
            wire.requireEnd()
        }
        assertEquals(
            SshSignatureMethod.WEBAUTHN_SK_ECDSA_NISTP256,
            SshSignatureVerifier.methodFor(decoded.type, 0),
        )
        assertTrue(
            SshSignatureVerifier.verify(
                publicBlob,
                data,
                signatureBlob,
                SshSignatureMethod.WEBAUTHN_SK_ECDSA_NISTP256,
            ),
        )
        assertFalse(
            SshSignatureVerifier.verify(
                publicBlob,
                data + byteArrayOf(0),
                signatureBlob,
                SshSignatureMethod.WEBAUTHN_SK_ECDSA_NISTP256,
            ),
        )

        val missingUv = WebAuthnSshSignatureCodec.encode(
            WebAuthnSshSignature(
                EcdsaSignatureTranscoder.derToSsh(derSignature),
                WebAuthnSshSignatureCodec.FLAG_USER_PRESENT,
                counter,
                origin,
                clientData,
                byteArrayOf(),
            ),
        )
        assertFalse(
            SshSignatureVerifier.verify(
                publicBlob,
                data,
                missingUv,
                SshSignatureMethod.WEBAUTHN_SK_ECDSA_NISTP256,
            ),
        )
        assertThrows(SshWireException::class.java) {
            WebAuthnSshSignatureCodec.encode(
                WebAuthnSshSignature(
                    EcdsaSignatureTranscoder.derToSsh(derSignature),
                    flags,
                    counter,
                    origin,
                    clientData,
                    byteArrayOf(1),
                ),
            )
        }
    }

    @Test
    fun ed25519EncodingAcceptsProviderSpecificPublicKeyClass() {
        val providerSpecificKey = object : PublicKey {
            override fun getAlgorithm(): String = "Ed25519"
            override fun getFormat(): String = "X.509"
            override fun getEncoded(): ByteArray = ed25519.public.encoded.copyOf()
        }

        assertArrayEquals(
            SshPublicKeyCodec.encode(ed25519.public),
            SshPublicKeyCodec.encode(providerSpecificKey),
        )

        val androidKeystoreStyleKey = object : PublicKey {
            // Some Android Keystore releases expose an Ed25519 key under the generic EC algorithm name.
            override fun getAlgorithm(): String = "EC"
            override fun getFormat(): String = "X.509"
            override fun getEncoded(): ByteArray = ed25519.public.encoded.copyOf()
        }
        assertArrayEquals(
            SshPublicKeyCodec.encode(ed25519.public),
            SshPublicKeyCodec.encode(androidKeystoreStyleKey),
        )

        val p256 = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
        assertThrows(SshWireException::class.java) {
            SshPublicKeyCodec.encode(p256.public, SshKeyType.ED25519)
        }
    }

    @Test
    fun ecdsaDerAndSshConversionsPreserveLeadingZeroCases() {
        val der = byteArrayOf(
            0x30, 0x08, 0x02, 0x02, 0x00, 0x80.toByte(), 0x02, 0x02, 0x00, 0xff.toByte(),
        )

        val ssh = EcdsaSignatureTranscoder.derToSsh(der)

        assertArrayEquals(der, EcdsaSignatureTranscoder.sshToDer(ssh))
        assertThrows(SshWireException::class.java) {
            EcdsaSignatureTranscoder.derToSsh(der + byteArrayOf(0))
        }
    }

    @Test
    fun userAuthAndSessionBindParsingRetainCryptographicProvenance() {
        val hostBlob = SshPublicKeyCodec.encode(rsa.public)
        val userBlob = SshPublicKeyCodec.encode(ed25519.public)
        val sessionId = ByteArray(32) { it.toByte() }
        val signData = SshWireWriter()
            .writeString(sessionId)
            .writeByte(50)
            .writeUtf8("git")
            .writeUtf8("ssh-connection")
            .writeUtf8(SshUserAuthParser.HOST_BOUND_METHOD)
            .writeBoolean(true)
            .writeUtf8(SshKeyType.ED25519.wireName)
            .writeString(userBlob)
            .writeString(hostBlob)
            .toByteArray()
        val parsed = SshUserAuthParser.parse(signData)!!
        val hostSignature = SshSignatureCodec.encode(
            SshSignatureMethod.RSA_SHA2_256,
            sign(SshSignatureMethod.RSA_SHA2_256, rsa, sessionId),
        )
        val contents = OpenSshSessionBind.encodeContents(hostBlob, sessionId, hostSignature, forwarded = false)

        assertEquals("git", parsed.username)
        assertArrayEquals(userBlob, parsed.publicKeyBlob)
        assertArrayEquals(hostBlob, parsed.serverHostKeyBlob)
        val binding = OpenSshSessionBind.parseAndVerify(contents)
        assertArrayEquals(sessionId, binding.sessionIdentifier)
        assertTrue(binding.hostKeyFingerprint.startsWith("SHA256:"))
        assertThrows(SshWireException::class.java) {
            OpenSshSessionBind.parseAndVerify(
                OpenSshSessionBind.encodeContents(hostBlob, sessionId + byteArrayOf(1), hostSignature, forwarded = false),
            )
        }
    }

    @Test
    fun ordinarySignedUserAuthPayloadExposesUsername() {
        val userBlob = SshPublicKeyCodec.encode(ed25519.public)
        val signData = SshWireWriter()
            .writeString(ByteArray(32) { it.toByte() })
            .writeByte(50)
            .writeUtf8("deploy")
            .writeUtf8("ssh-connection")
            .writeUtf8(SshUserAuthParser.PUBLIC_KEY_METHOD)
            .writeBoolean(true)
            .writeUtf8(SshKeyType.ED25519.wireName)
            .writeString(userBlob)
            .toByteArray()

        val parsed = requireNotNull(SshUserAuthParser.parse(signData))

        assertEquals("deploy", parsed.username)
        assertEquals("ssh-connection", parsed.service)
        assertEquals(SshUserAuthParser.PUBLIC_KEY_METHOD, parsed.method)
        assertArrayEquals(userBlob, parsed.publicKeyBlob)
    }

    @Test
    fun rsaSha2SignedUserAuthPayloadExposesUsername() {
        val userBlob = SshPublicKeyCodec.encode(rsa.public)

        listOf(SshSignatureMethod.RSA_SHA2_256, SshSignatureMethod.RSA_SHA2_512).forEach { method ->
            val signData = SshWireWriter()
                .writeString(ByteArray(32) { it.toByte() })
                .writeByte(50)
                .writeUtf8("deploy")
                .writeUtf8("ssh-connection")
                .writeUtf8(SshUserAuthParser.PUBLIC_KEY_METHOD)
                .writeBoolean(true)
                .writeUtf8(method.wireName)
                .writeString(userBlob)
                .toByteArray()

            val parsed = requireNotNull(SshUserAuthParser.parse(signData))

            assertEquals(method.wireName, parsed.publicKeyAlgorithm)
            assertEquals("deploy", parsed.username)
            assertArrayEquals(userBlob, parsed.publicKeyBlob)
        }
    }

    @Test
    fun agentAddParserValidatesAllRequiredPrivateKeyTypesAndConstraints() {
        val rsaPrivate = rsa.private as RSAPrivateCrtKey
        val rsaPayload = SshWireWriter()
            .writeUtf8(SshKeyType.RSA.wireName)
            .writeMpInt(rsaPrivate.modulus)
            .writeMpInt(rsaPrivate.publicExponent)
            .writeMpInt(rsaPrivate.privateExponent)
            .writeMpInt(rsaPrivate.crtCoefficient)
            .writeMpInt(rsaPrivate.primeP)
            .writeMpInt(rsaPrivate.primeQ)
            .writeUtf8("RSA")
            .writeByte(AgentNumbers.SSH_AGENT_CONSTRAIN_LIFETIME)
            .writeUInt32(60)
            .writeByte(AgentNumbers.SSH_AGENT_CONSTRAIN_CONFIRM)
            .toByteArray()
        val rsaParsed = AgentAddIdentityParser.parse(rsaPayload, constrained = true)
        assertEquals(SshKeyType.RSA, rsaParsed.type)
        assertEquals(AgentAddConstraints(60, true), rsaParsed.constraints)

        val edPublicBlob = SshPublicKeyCodec.encode(ed25519.public)
        val edPublic = SshWireReader(edPublicBlob).run { readUtf8(); readString() }
        val edSeed = (ed25519.private as EdECPrivateKey).bytes.orElseThrow()
        val edPayload = SshWireWriter()
            .writeUtf8(SshKeyType.ED25519.wireName)
            .writeString(edPublic)
            .writeString(edSeed + edPublic)
            .writeUtf8("Ed25519")
            .toByteArray()
        assertEquals(SshKeyType.ED25519, AgentAddIdentityParser.parse(edPayload, false).type)

        val ecPrivate = ec.private as ECPrivateKey
        val ecBlobReader = SshWireReader(SshPublicKeyCodec.encode(ec.public))
        ecBlobReader.readUtf8()
        val curve = ecBlobReader.readUtf8()
        val point = ecBlobReader.readString()
        val ecPayload = SshWireWriter()
            .writeUtf8(SshKeyType.ECDSA_NISTP256.wireName)
            .writeUtf8(curve)
            .writeString(point)
            .writeMpInt(ecPrivate.s)
            .writeUtf8("P-256")
            .toByteArray()
        assertEquals(SshKeyType.ECDSA_NISTP256, AgentAddIdentityParser.parse(ecPayload, false).type)

        assertThrows(SshWireException::class.java) {
            AgentAddIdentityParser.parse(rsaPayload.dropLast(1).toByteArray() + byteArrayOf(99), constrained = true)
        }
    }

    @Test
    fun agentAddParserDoesNotUseTheDefaultEd25519KeyFactory() {
        val edPublicBlob = SshPublicKeyCodec.encode(ed25519.public)
        val edPublic = SshWireReader(edPublicBlob).run { readUtf8(); readString() }
        val edSeed = (ed25519.private as EdECPrivateKey).bytes.orElseThrow()
        val payload = SshWireWriter()
            .writeUtf8(SshKeyType.ED25519.wireName)
            .writeString(edPublic)
            .writeString(edSeed + edPublic)
            .writeUtf8("Ed25519")
            .toByteArray()
        val rejectingProvider = RejectingEd25519KeyFactoryProvider()

        synchronized(PROVIDER_ORDER_TEST_LOCK) {
            assertEquals(1, Security.insertProviderAt(rejectingProvider, 1))
            try {
                assertEquals(rejectingProvider.name, KeyFactory.getInstance("Ed25519").provider.name)
                assertEquals(SshKeyType.ED25519, AgentAddIdentityParser.parse(payload, false).type)
            } finally {
                Security.removeProvider(rejectingProvider.name)
            }
        }
    }

    private fun sign(method: SshSignatureMethod, keyPair: KeyPair, data: ByteArray): ByteArray =
        Signature.getInstance(method.jcaName).run {
            initSign(keyPair.private)
            update(data)
            sign()
        }

    private companion object {
        val PROVIDER_ORDER_TEST_LOCK = Any()
        val ed25519: KeyPair by lazy { KeyPairGenerator.getInstance("Ed25519").generateKeyPair() }
        val rsa: KeyPair by lazy {
            KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        }
        val ec: KeyPair by lazy {
            KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        }
    }
}

@Suppress("DEPRECATION")
class RejectingEd25519KeyFactoryProvider : Provider(
    "RejectingEd25519KeyFactory",
    1.0,
    "Test provider that models an OEM-owned default Ed25519 KeyFactory",
) {
    init {
        put("KeyFactory.Ed25519", RejectingEd25519KeyFactorySpi::class.java.name)
    }
}

class RejectingEd25519KeyFactorySpi : KeyFactorySpi() {
    override fun engineGeneratePublic(keySpec: KeySpec): PublicKey =
        throw InvalidKeySpecException("default provider must not reconstruct SSH software keys")

    override fun engineGeneratePrivate(keySpec: KeySpec): PrivateKey =
        throw InvalidKeySpecException("default provider must not reconstruct SSH software keys")

    override fun <T : KeySpec> engineGetKeySpec(key: Key, keySpec: Class<T>): T =
        throw InvalidKeySpecException("default provider must not reconstruct SSH software keys")

    override fun engineTranslateKey(key: Key): Key =
        throw InvalidKeyException("default provider must not reconstruct SSH software keys")
}

package net.extrawdw.apps.notisync.sshagent

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.extrawdw.notisync.ssh.core.EcdsaSignatureTranscoder
import net.extrawdw.notisync.ssh.core.SshPublicKeyCodec
import net.extrawdw.notisync.ssh.core.SshSignatureMethod
import net.extrawdw.notisync.ssh.core.SshSignatureVerifier
import net.extrawdw.notisync.ssh.core.SshWireWriter
import net.extrawdw.notisync.ssh.core.WebAuthnSshSignature
import net.extrawdw.notisync.ssh.core.WebAuthnSshSignatureCodec

data class PreparedSshWebAuthnRegistration(
    val requestJson: String,
    val rpId: String,
    val challenge: ByteArray,
    val userId: ByteArray,
)

data class RegisteredSshWebAuthnCredential(
    val publicKeyBlob: ByteArray,
    val credentialId: ByteArray,
    val userHandle: ByteArray,
    val rpId: String,
    val cosePublicKey: ByteArray,
    val createdOrigin: String,
    val backupEligible: Boolean,
    val backupState: Boolean,
)

data class StoredSshWebAuthnCredential(
    val providerKeyId: String,
    val publicKeyBlob: ByteArray,
    val credentialId: ByteArray,
    val userHandle: ByteArray,
    val rpId: String,
    val cosePublicKey: ByteArray,
    val createdOrigin: String,
    val backupEligible: Boolean,
    val backupState: Boolean,
)

data class ParsedSshWebAuthnAssertion(
    val signatureBlob: ByteArray,
    val backupEligible: Boolean,
    val backupState: Boolean,
)

data class PreparedSshWebAuthnRecovery(
    val requestJson: String,
    val challenge: ByteArray,
)

data class SshWebAuthnRecoveryRecord(
    val credentialId: ByteArray,
    val userHandle: ByteArray,
    val rpId: String,
    val cosePublicKey: ByteArray,
    val publicKeyBlob: ByteArray,
    val createdOrigin: String,
    val displayName: String,
    val createdAt: Long,
    val backupEligible: Boolean,
    val backupState: Boolean,
) {
    fun storedCredential(providerKeyId: String = "recovery") = StoredSshWebAuthnCredential(
        providerKeyId = providerKeyId,
        publicKeyBlob = publicKeyBlob.copyOf(),
        credentialId = credentialId.copyOf(),
        userHandle = userHandle.copyOf(),
        rpId = rpId,
        cosePublicKey = cosePublicKey.copyOf(),
        createdOrigin = createdOrigin,
        backupEligible = backupEligible,
        backupState = backupState,
    )

    fun registeredCredential(assertion: ParsedSshWebAuthnAssertion) = RegisteredSshWebAuthnCredential(
        publicKeyBlob = publicKeyBlob.copyOf(),
        credentialId = credentialId.copyOf(),
        userHandle = userHandle.copyOf(),
        rpId = rpId,
        cosePublicKey = cosePublicKey.copyOf(),
        createdOrigin = createdOrigin,
        backupEligible = assertion.backupEligible,
        backupState = assertion.backupState,
    )

    fun registeredCredential() = RegisteredSshWebAuthnCredential(
        publicKeyBlob = publicKeyBlob.copyOf(),
        credentialId = credentialId.copyOf(),
        userHandle = userHandle.copyOf(),
        rpId = rpId,
        cosePublicKey = cosePublicKey.copyOf(),
        createdOrigin = createdOrigin,
        backupEligible = backupEligible,
        backupState = backupState,
    )
}

/** Pure request/response codec for the Android Credential Manager WebAuthn boundary. */
object SshWebAuthnCredential {
    const val RP_ID = "notisync.apps.extrawdw.net"

    fun prepareRegistration(
        displayName: String,
        excludedCredentialIds: List<ByteArray> = emptyList(),
        random: SecureRandom = SecureRandom(),
    ): PreparedSshWebAuthnRegistration {
        val boundedName = displayName.trim()
        require(boundedName.isNotEmpty() && boundedName.encodeToByteArray().size <= 256) {
            "WebAuthn credential name is outside the allowed bounds"
        }
        require(excludedCredentialIds.size <= 512 && excludedCredentialIds.all { it.isNotEmpty() && it.size <= 1024 }) {
            "excluded WebAuthn credential IDs are outside the allowed bounds"
        }
        val challenge = ByteArray(32).also(random::nextBytes)
        val userId = generateWebAuthnUserId(random)
        val printableUserId = printableWebAuthnUserId(userId)
        val request = buildJsonObject {
            put("rp", buildJsonObject {
                put("id", RP_ID)
                put("name", "NotiSync SSH")
            })
            put("user", buildJsonObject {
                put("id", base64Url(userId))
                put("name", printableUserId)
                put("displayName", boundedName)
            })
            put("challenge", base64Url(challenge))
            put("pubKeyCredParams", buildJsonArray {
                add(buildJsonObject {
                    put("type", PUBLIC_KEY_TYPE)
                    put("alg", ES256_COSE_ALGORITHM)
                })
            })
            put("timeout", CREDENTIAL_TIMEOUT_MILLIS)
            if (excludedCredentialIds.isNotEmpty()) {
                put("excludeCredentials", credentialDescriptors(excludedCredentialIds))
            }
            put("authenticatorSelection", buildJsonObject {
                put("residentKey", "required")
                put("requireResidentKey", true)
                put("userVerification", "required")
            })
            put("attestation", "none")
        }
        return PreparedSshWebAuthnRegistration(request.toString(), RP_ID, challenge, userId)
    }

    fun assertionRequestJson(stored: StoredSshWebAuthnCredential, challenge: ByteArray): String {
        require(stored.rpId == RP_ID) { "unsupported WebAuthn credential RP ID" }
        require(challenge.isNotEmpty() && challenge.size <= 256 * 1024) { "SSH challenge is outside the allowed bounds" }
        return buildJsonObject {
            put("rpId", stored.rpId)
            put("challenge", base64Url(challenge))
            put("allowCredentials", credentialDescriptors(listOf(stored.credentialId)))
            put("userVerification", "required")
            put("timeout", CREDENTIAL_TIMEOUT_MILLIS)
        }.toString()
    }

    fun prepareRecovery(random: SecureRandom = SecureRandom()): PreparedSshWebAuthnRecovery {
        val challenge = ByteArray(32).also(random::nextBytes)
        val request = buildJsonObject {
            put("rpId", RP_ID)
            put("challenge", base64Url(challenge))
            put("userVerification", "required")
            put("timeout", CREDENTIAL_TIMEOUT_MILLIS)
        }
        return PreparedSshWebAuthnRecovery(request.toString(), challenge)
    }

    fun assertionCredentialId(responseJson: String): ByteArray {
        val root = parseJsonObject(responseJson, "WebAuthn credential recovery assertion")
        require(root.requiredString("type") == PUBLIC_KEY_TYPE) { "unexpected WebAuthn credential type" }
        return root.credentialId()
    }

    fun assertionUserHandle(responseJson: String): ByteArray {
        val root = parseJsonObject(responseJson, "WebAuthn credential recovery assertion")
        require(root.requiredString("type") == PUBLIC_KEY_TYPE) { "unexpected WebAuthn credential type" }
        return root.requiredObject("response").requiredBase64Url("userHandle", 64).also(::passwordRecordId)
    }

    fun passwordRecordId(userId: ByteArray): String = printableWebAuthnUserId(userId)

    private fun printableWebAuthnUserId(userId: ByteArray): String {
        require(userId.isNotEmpty() && userId.size <= 64) { "WebAuthn user ID is invalid" }
        val printableUserId = userId.decodeToString()
        require(
            printableUserId.encodeToByteArray().contentEquals(userId) &&
                printableUserId.startsWith(WEBAUTHN_USER_ID_PREFIX),
        ) {
            "WebAuthn user ID is invalid"
        }
        val randomToken = printableUserId.removePrefix(WEBAUTHN_USER_ID_PREFIX)
        require(decodeBase64Url(randomToken).size == WEBAUTHN_USER_ID_RANDOM_BYTES) {
            "WebAuthn user ID is invalid"
        }
        return printableUserId
    }

    fun encodeRecoveryRecord(
        credential: RegisteredSshWebAuthnCredential,
        displayName: String,
        createdAt: Long,
    ): String {
        val record = SshWebAuthnRecoveryRecord(
            credentialId = credential.credentialId.copyOf(),
            userHandle = credential.userHandle.copyOf(),
            rpId = credential.rpId,
            cosePublicKey = credential.cosePublicKey.copyOf(),
            publicKeyBlob = credential.publicKeyBlob.copyOf(),
            createdOrigin = credential.createdOrigin,
            displayName = displayName.trim(),
            createdAt = createdAt,
            backupEligible = credential.backupEligible,
            backupState = credential.backupState,
        )
        validateRecoveryRecord(record)
        return buildJsonObject {
            put("version", RECOVERY_RECORD_VERSION)
            put("credentialId", base64Url(record.credentialId))
            put("userHandle", base64Url(record.userHandle))
            put("rpId", record.rpId)
            put("cosePublicKey", base64Url(record.cosePublicKey))
            put("publicKeyBlob", base64Url(record.publicKeyBlob))
            put("createdOrigin", record.createdOrigin)
            put("displayName", record.displayName)
            put("createdAt", record.createdAt)
            put("backupEligible", record.backupEligible)
            put("backupState", record.backupState)
        }.toString().also {
            require(it.encodeToByteArray().size <= MAX_RECOVERY_RECORD_BYTES) {
                "WebAuthn recovery record is too large"
            }
        }
    }

    fun decodeRecoveryRecord(encoded: String): SshWebAuthnRecoveryRecord {
        require(encoded.encodeToByteArray().size <= MAX_RECOVERY_RECORD_BYTES) {
            "WebAuthn recovery record is too large"
        }
        val json = parseJsonObject(encoded, "WebAuthn recovery record")
        require(json.keys == RECOVERY_RECORD_FIELDS) { "WebAuthn recovery record has unexpected fields" }
        require(json["version"]?.jsonPrimitive?.content?.toIntOrNull() == RECOVERY_RECORD_VERSION) {
            "unsupported WebAuthn recovery record version"
        }
        return SshWebAuthnRecoveryRecord(
            credentialId = json.requiredBase64Url("credentialId", 1024),
            userHandle = json.requiredBase64Url("userHandle", 64),
            rpId = json.requiredString("rpId"),
            cosePublicKey = json.requiredBase64Url("cosePublicKey", 2048),
            publicKeyBlob = json.requiredBase64Url("publicKeyBlob", 16 * 1024),
            createdOrigin = json.requiredString("createdOrigin"),
            displayName = json.requiredString("displayName"),
            createdAt = json["createdAt"]?.jsonPrimitive?.content?.toLongOrNull()
                ?: error("WebAuthn recovery record has an invalid creation time"),
            backupEligible = json["backupEligible"]?.jsonPrimitive?.booleanOrNull
                ?: error("WebAuthn recovery record has an invalid backup eligibility value"),
            backupState = json["backupState"]?.jsonPrimitive?.booleanOrNull
                ?: error("WebAuthn recovery record has an invalid backup state value"),
        ).also(::validateRecoveryRecord)
    }

    fun parseRegistration(
        prepared: PreparedSshWebAuthnRegistration,
        responseJson: String,
        allowedOrigins: Set<String>,
    ): RegisteredSshWebAuthnCredential {
        require(prepared.rpId == RP_ID) { "unsupported WebAuthn credential RP ID" }
        val root = parseJsonObject(responseJson, "WebAuthn credential registration")
        require(root.requiredString("type") == PUBLIC_KEY_TYPE) { "unexpected WebAuthn credential type" }
        val credentialId = root.credentialId()
        val response = root.requiredObject("response")
        val clientData = response.requiredBase64Url("clientDataJSON", MAX_CLIENT_DATA_BYTES)
        val client = validateClientData(clientData, "webauthn.create", prepared.challenge, allowedOrigins)
        val attestationObject = response.requiredBase64Url("attestationObject", MAX_ATTESTATION_BYTES)
        val attestation = CborReader.decodeExact(attestationObject).asTextMap("attestation object")
        require(attestation.requiredText("fmt") == "none") { "only none WebAuthn credential attestation is supported" }
        require(attestation.requiredMap("attStmt").isEmpty()) { "none WebAuthn credential attestation statement must be empty" }
        val authData = attestation.requiredBytes("authData")
        val parsed = parseRegistrationAuthenticatorData(authData, prepared.rpId)
        require(MessageDigest.isEqual(credentialId, parsed.credentialId)) {
            "WebAuthn credential ID does not match authenticator data"
        }
        return RegisteredSshWebAuthnCredential(
            publicKeyBlob = parsed.publicKeyBlob,
            credentialId = credentialId,
            userHandle = prepared.userId.copyOf(),
            rpId = prepared.rpId,
            cosePublicKey = parsed.cosePublicKey,
            createdOrigin = client.origin,
            backupEligible = parsed.backupEligible,
            backupState = parsed.backupState,
        )
    }

    fun parseAssertion(
        stored: StoredSshWebAuthnCredential,
        challenge: ByteArray,
        responseJson: String,
        allowedOrigins: Set<String>,
    ): ParsedSshWebAuthnAssertion {
        val root = parseJsonObject(responseJson, "WebAuthn credential assertion")
        require(root.requiredString("type") == PUBLIC_KEY_TYPE) { "unexpected WebAuthn credential type" }
        require(MessageDigest.isEqual(root.credentialId(), stored.credentialId)) {
            "WebAuthn credential assertion selected a different credential"
        }
        val response = root.requiredObject("response")
        response["userHandle"]?.let { value ->
            if (value !is kotlinx.serialization.json.JsonNull) {
                val returned = value.jsonPrimitive.contentOrNull?.let(::decodeBase64Url)
                    ?: error("invalid WebAuthn credential user handle")
                require(MessageDigest.isEqual(returned, stored.userHandle)) { "WebAuthn credential user handle does not match" }
            }
        }
        val clientData = response.requiredBase64Url("clientDataJSON", MAX_CLIENT_DATA_BYTES)
        val client = validateClientData(clientData, "webauthn.get", challenge, allowedOrigins)
        val authenticatorData = response.requiredBase64Url("authenticatorData", MAX_AUTHENTICATOR_DATA_BYTES)
        val auth = parseAssertionAuthenticatorData(authenticatorData, stored.rpId)
        val derSignature = response.requiredBase64Url("signature", 256)
        val signatureBlob = WebAuthnSshSignatureCodec.encode(
            WebAuthnSshSignature(
                ecdsaSignature = EcdsaSignatureTranscoder.derToSsh(derSignature),
                flags = auth.flags,
                counter = auth.counter,
                origin = client.origin,
                clientDataJson = clientData,
                extensions = auth.extensions,
            ),
        )
        require(
            SshSignatureVerifier.verify(
                publicKeyBlob = stored.publicKeyBlob,
                data = challenge,
                signatureBlob = signatureBlob,
                expectedMethod = SshSignatureMethod.WEBAUTHN_SK_ECDSA_NISTP256,
            ),
        ) { "WebAuthn credential assertion signature is invalid" }
        return ParsedSshWebAuthnAssertion(signatureBlob, auth.backupEligible, auth.backupState)
    }

    private fun credentialDescriptors(ids: List<ByteArray>): JsonArray = buildJsonArray {
        ids.forEach { id ->
            add(buildJsonObject {
                put("type", PUBLIC_KEY_TYPE)
                put("id", base64Url(id))
            })
        }
    }

    private fun parseRegistrationAuthenticatorData(bytes: ByteArray, rpId: String): RegistrationAuthData {
        require(bytes.size >= AUTHENTICATOR_DATA_FIXED_BYTES + 18) { "WebAuthn credential registration authenticator data is truncated" }
        verifyRpIdHash(bytes, rpId)
        val flags = bytes[32].toInt() and 0xff
        requireRequiredFlags(flags, attestedData = true)
        val credentialIdLength = ((bytes[53].toInt() and 0xff) shl 8) or (bytes[54].toInt() and 0xff)
        require(credentialIdLength in 1..1024 && 55 + credentialIdLength < bytes.size) {
            "WebAuthn credential ID is outside the allowed bounds"
        }
        val credentialId = bytes.copyOfRange(55, 55 + credentialIdLength)
        val coseStart = 55 + credentialIdLength
        val cose = CborReader.decodeFirst(bytes, coseStart)
        val coseBytes = bytes.copyOfRange(coseStart, cose.nextOffset)
        val extensions = bytes.copyOfRange(cose.nextOffset, bytes.size)
        validateExtensionBytes(flags, extensions)
        val publicBlob = coseEcdsaP256PublicBlob(cose.value, rpId)
        return RegistrationAuthData(
            publicKeyBlob = publicBlob,
            credentialId = credentialId,
            cosePublicKey = coseBytes,
            backupEligible = flags and WebAuthnSshSignatureCodec.FLAG_BACKUP_ELIGIBLE != 0,
            backupState = flags and WebAuthnSshSignatureCodec.FLAG_BACKUP_STATE != 0,
        )
    }

    private fun parseAssertionAuthenticatorData(bytes: ByteArray, rpId: String): AssertionAuthData {
        require(bytes.size >= AUTHENTICATOR_DATA_FIXED_BYTES) { "WebAuthn credential assertion authenticator data is truncated" }
        verifyRpIdHash(bytes, rpId)
        val flags = bytes[32].toInt() and 0xff
        requireRequiredFlags(flags, attestedData = false)
        val counter = ((bytes[33].toLong() and 0xff) shl 24) or
            ((bytes[34].toLong() and 0xff) shl 16) or
            ((bytes[35].toLong() and 0xff) shl 8) or
            (bytes[36].toLong() and 0xff)
        val extensions = bytes.copyOfRange(AUTHENTICATOR_DATA_FIXED_BYTES, bytes.size)
        validateExtensionBytes(flags, extensions)
        return AssertionAuthData(
            flags,
            counter,
            extensions,
            flags and WebAuthnSshSignatureCodec.FLAG_BACKUP_ELIGIBLE != 0,
            flags and WebAuthnSshSignatureCodec.FLAG_BACKUP_STATE != 0,
        )
    }

    private fun requireRequiredFlags(flags: Int, attestedData: Boolean) {
        require(flags and WebAuthnSshSignatureCodec.FLAG_USER_PRESENT != 0) { "WebAuthn credential user presence is required" }
        require(flags and WebAuthnSshSignatureCodec.FLAG_USER_VERIFIED != 0) { "WebAuthn credential user verification is required" }
        require(
            (flags and WebAuthnSshSignatureCodec.FLAG_ATTESTED_CREDENTIAL_DATA != 0) == attestedData,
        ) { "WebAuthn credential attested-credential flag is invalid" }
        require(
            flags and WebAuthnSshSignatureCodec.FLAG_BACKUP_STATE == 0 ||
                flags and WebAuthnSshSignatureCodec.FLAG_BACKUP_ELIGIBLE != 0,
        ) { "WebAuthn credential backup state requires backup eligibility" }
    }

    private fun validateExtensionBytes(flags: Int, extensions: ByteArray) {
        val hasExtensions = flags and WebAuthnSshSignatureCodec.FLAG_EXTENSION_DATA != 0
        require(hasExtensions == extensions.isNotEmpty()) { "WebAuthn credential extension flag does not match extension data" }
        if (extensions.isNotEmpty()) CborReader.decodeExact(extensions)
    }

    private fun verifyRpIdHash(bytes: ByteArray, rpId: String) {
        val expected = MessageDigest.getInstance("SHA-256").digest(rpId.encodeToByteArray())
        require(MessageDigest.isEqual(bytes.copyOfRange(0, 32), expected)) { "WebAuthn credential RP ID hash does not match" }
    }

    private fun coseEcdsaP256PublicBlob(value: CborValue, rpId: String): ByteArray {
        val map = value.asIntegerMap("COSE public key")
        require(map.requiredInteger(1) == 2L) { "WebAuthn credential COSE key must use EC2" }
        require(map.requiredInteger(3) == ES256_COSE_ALGORITHM.toLong()) { "WebAuthn credential COSE key must use ES256" }
        require(map.requiredInteger(-1) == 1L) { "WebAuthn credential COSE key must use P-256" }
        val x = map.requiredBytes(-2)
        val y = map.requiredBytes(-3)
        require(x.size == 32 && y.size == 32) { "WebAuthn credential P-256 coordinates must be 32 bytes" }
        val ordinaryBlob = SshWireWriter()
            .writeUtf8("ecdsa-sha2-nistp256")
            .writeUtf8("nistp256")
            .writeString(byteArrayOf(4) + x + y)
            .toByteArray()
        val publicKey = SshPublicKeyCodec.decode(ordinaryBlob).publicKey
        return SshPublicKeyCodec.encodeWebAuthnEcdsaP256(publicKey, rpId)
    }

    private fun validateRecoveryRecord(record: SshWebAuthnRecoveryRecord) {
        require(record.rpId == RP_ID) { "unsupported WebAuthn recovery RP ID" }
        require(record.credentialId.isNotEmpty() && record.credentialId.size <= 1024) {
            "WebAuthn recovery credential ID is outside the allowed bounds"
        }
        passwordRecordId(record.userHandle)
        require(record.cosePublicKey.isNotEmpty() && record.cosePublicKey.size <= 2048) {
            "WebAuthn recovery public key is outside the allowed bounds"
        }
        val publicFromCose = coseEcdsaP256PublicBlob(CborReader.decodeExact(record.cosePublicKey), record.rpId)
        require(MessageDigest.isEqual(publicFromCose, record.publicKeyBlob)) {
            "WebAuthn recovery public keys do not match"
        }
        val decoded = SshPublicKeyCodec.decode(record.publicKeyBlob)
        require(decoded.type == net.extrawdw.notisync.ssh.core.SshKeyType.WEBAUTHN_SK_ECDSA_NISTP256 &&
            decoded.application == record.rpId
        ) { "WebAuthn recovery SSH key is invalid" }
        require(record.createdOrigin.isNotBlank() && record.createdOrigin.encodeToByteArray().size <= 1024) {
            "WebAuthn recovery creation origin is outside the allowed bounds"
        }
        require(record.displayName.isNotBlank() && record.displayName.encodeToByteArray().size <= 256) {
            "WebAuthn recovery display name is outside the allowed bounds"
        }
        require(record.createdAt > 0) { "WebAuthn recovery creation time is invalid" }
        require(!record.backupState || record.backupEligible) {
            "WebAuthn recovery backup state requires backup eligibility"
        }
    }

    private fun validateClientData(
        bytes: ByteArray,
        expectedType: String,
        expectedChallenge: ByteArray,
        allowedOrigins: Set<String>,
    ): ClientData {
        require(allowedOrigins.isNotEmpty()) { "no trusted Android WebAuthn credential origins are configured" }
        val text = bytes.decodeToString()
        require(text.encodeToByteArray().contentEquals(bytes)) { "WebAuthn client data is not valid UTF-8" }
        val json = parseJsonObject(text, "WebAuthn credential client data")
        require(json.requiredString("type") == expectedType) { "WebAuthn credential client-data type does not match" }
        val challenge = decodeBase64Url(json.requiredString("challenge"))
        require(MessageDigest.isEqual(challenge, expectedChallenge)) { "WebAuthn credential challenge does not match" }
        val origin = json.requiredString("origin")
        require(origin in allowedOrigins) { "WebAuthn credential origin is not trusted" }
        require(json["crossOrigin"]?.jsonPrimitive?.booleanOrNull != true) { "cross-origin WebAuthn credential response is not allowed" }
        return ClientData(origin)
    }

    private fun JsonObject.credentialId(): ByteArray {
        val rawId = requiredBase64Url("rawId", 1024)
        val id = decodeBase64Url(requiredString("id"))
        require(MessageDigest.isEqual(rawId, id)) { "WebAuthn credential id and rawId do not match" }
        return rawId
    }

    private fun JsonObject.requiredString(name: String): String =
        this[name]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotEmpty)
            ?: error("WebAuthn credential response is missing $name")

    private fun JsonObject.requiredObject(name: String): JsonObject =
        this[name]?.let { it as? JsonObject } ?: error("WebAuthn credential response is missing $name")

    private fun JsonObject.requiredBase64Url(name: String, maximumBytes: Int): ByteArray =
        decodeBase64Url(requiredString(name)).also {
            require(it.isNotEmpty() && it.size <= maximumBytes) { "$name is outside the allowed bounds" }
        }

    private fun parseJsonObject(text: String, description: String): JsonObject {
        require(text.encodeToByteArray().size <= MAX_RESPONSE_JSON_BYTES) { "$description is too large" }
        return Json.parseToJsonElement(text) as? JsonObject ?: error("$description must be a JSON object")
    }

    private fun decodeBase64Url(encoded: String): ByteArray {
        require(encoded.isNotEmpty() && '=' !in encoded && encoded.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            "invalid base64url value"
        }
        val decoded = Base64.getUrlDecoder().decode(encoded)
        require(base64Url(decoded) == encoded) { "non-canonical base64url value" }
        return decoded
    }

    private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private data class ClientData(val origin: String)
    private data class RegistrationAuthData(
        val publicKeyBlob: ByteArray,
        val credentialId: ByteArray,
        val cosePublicKey: ByteArray,
        val backupEligible: Boolean,
        val backupState: Boolean,
    )
    private data class AssertionAuthData(
        val flags: Int,
        val counter: Long,
        val extensions: ByteArray,
        val backupEligible: Boolean,
        val backupState: Boolean,
    )

    private const val PUBLIC_KEY_TYPE = "public-key"
    private const val ES256_COSE_ALGORITHM = -7
    private const val CREDENTIAL_TIMEOUT_MILLIS = 5 * 60_000
    private const val AUTHENTICATOR_DATA_FIXED_BYTES = 37
    private const val MAX_RESPONSE_JSON_BYTES = 128 * 1024
    private const val MAX_CLIENT_DATA_BYTES = 12 * 1024
    private const val MAX_ATTESTATION_BYTES = 64 * 1024
    private const val MAX_AUTHENTICATOR_DATA_BYTES = 16 * 1024
    private const val MAX_RECOVERY_RECORD_BYTES = 64 * 1024
    private const val RECOVERY_RECORD_VERSION = 1
    private const val WEBAUTHN_USER_ID_PREFIX = "notisync-ssh:"
    private const val WEBAUTHN_USER_ID_RANDOM_BYTES = 32
    private val RECOVERY_RECORD_FIELDS = setOf(
        "version", "credentialId", "userHandle", "rpId", "cosePublicKey", "publicKeyBlob",
        "createdOrigin", "displayName", "createdAt", "backupEligible", "backupState",
    )

    private fun generateWebAuthnUserId(random: SecureRandom): ByteArray {
        val randomBytes = ByteArray(WEBAUTHN_USER_ID_RANDOM_BYTES).also(random::nextBytes)
        return (WEBAUTHN_USER_ID_PREFIX + base64Url(randomBytes)).encodeToByteArray()
    }
}

private sealed interface CborValue {
    data class Integer(val value: Long) : CborValue
    data class Bytes(val value: ByteArray) : CborValue
    data class Text(val value: String) : CborValue
    data class Array(val values: List<CborValue>) : CborValue
    data class Map(val entries: List<Pair<CborValue, CborValue>>) : CborValue
    data class Simple(val value: Int) : CborValue
}

private class CborReader private constructor(private val bytes: ByteArray, private var offset: Int) {
    data class Result(val value: CborValue, val nextOffset: Int)

    private var itemCount = 0

    private fun read(depth: Int): CborValue {
        require(depth <= MAX_DEPTH && ++itemCount <= MAX_ITEMS && offset < bytes.size) { "CBOR value is outside bounds" }
        val initial = bytes[offset++].toInt() and 0xff
        val major = initial ushr 5
        val additional = initial and 0x1f
        return when (major) {
            0 -> CborValue.Integer(readLength(additional))
            1 -> CborValue.Integer(-1L - readLength(additional))
            2 -> CborValue.Bytes(readSizedBytes(readLength(additional)))
            3 -> {
                val raw = readSizedBytes(readLength(additional))
                val text = raw.decodeToString()
                require(text.encodeToByteArray().contentEquals(raw)) { "CBOR text is not canonical UTF-8" }
                CborValue.Text(text)
            }
            4 -> CborValue.Array(List(readCollectionSize(additional)) { read(depth + 1) })
            5 -> CborValue.Map(List(readCollectionSize(additional)) { read(depth + 1) to read(depth + 1) })
            7 -> when (additional) {
                20, 21, 22 -> CborValue.Simple(additional)
                else -> error("unsupported CBOR simple value")
            }
            else -> error("unsupported CBOR major type")
        }
    }

    private fun readCollectionSize(additional: Int): Int {
        val length = readLength(additional)
        require(length <= MAX_ITEMS.toLong()) { "CBOR collection is too large" }
        return length.toInt()
    }

    private fun readLength(additional: Int): Long = when (additional) {
        in 0..23 -> additional.toLong()
        24 -> readUnsigned(1).also { require(it >= 24) { "non-canonical CBOR integer" } }
        25 -> readUnsigned(2).also { require(it > 0xff) { "non-canonical CBOR integer" } }
        26 -> readUnsigned(4).also { require(it > 0xffff) { "non-canonical CBOR integer" } }
        27 -> readUnsigned(8).also { require(it > 0xffff_ffffL) { "non-canonical CBOR integer" } }
        else -> error("indefinite or reserved CBOR length is unsupported")
    }

    private fun readUnsigned(count: Int): Long {
        require(offset + count <= bytes.size) { "truncated CBOR integer" }
        var value = 0L
        repeat(count) {
            val next = bytes[offset++].toLong() and 0xff
            require(count < 8 || it > 0 || next <= 0x7f) { "CBOR integer exceeds signed bounds" }
            value = (value shl 8) or next
        }
        return value
    }

    private fun readSizedBytes(length: Long): ByteArray {
        require(length <= MAX_BYTE_STRING_BYTES && length <= bytes.size - offset) { "CBOR byte string is outside bounds" }
        return bytes.copyOfRange(offset, offset + length.toInt()).also { offset += length.toInt() }
    }

    companion object {
        fun decodeExact(bytes: ByteArray): CborValue {
            val result = decodeFirst(bytes, 0)
            require(result.nextOffset == bytes.size) { "unexpected trailing CBOR data" }
            return result.value
        }

        fun decodeFirst(bytes: ByteArray, offset: Int): Result {
            require(bytes.isNotEmpty() && bytes.size <= MAX_BYTE_STRING_BYTES && offset in bytes.indices) {
                "CBOR input is outside bounds"
            }
            val reader = CborReader(bytes, offset)
            return Result(reader.read(0), reader.offset)
        }

        private const val MAX_DEPTH = 12
        private const val MAX_ITEMS = 256
        private const val MAX_BYTE_STRING_BYTES = 64 * 1024L
    }
}

private fun CborValue.asTextMap(description: String): Map<String, CborValue> {
    val entries = (this as? CborValue.Map)?.entries ?: error("$description must be a CBOR map")
    val result = linkedMapOf<String, CborValue>()
    entries.forEach { (key, value) ->
        val text = (key as? CborValue.Text)?.value ?: error("$description keys must be text")
        require(result.put(text, value) == null) { "$description contains duplicate keys" }
    }
    return result
}

private fun CborValue.asIntegerMap(description: String): Map<Long, CborValue> {
    val entries = (this as? CborValue.Map)?.entries ?: error("$description must be a CBOR map")
    val result = linkedMapOf<Long, CborValue>()
    entries.forEach { (key, value) ->
        val integer = (key as? CborValue.Integer)?.value ?: error("$description keys must be integers")
        require(result.put(integer, value) == null) { "$description contains duplicate keys" }
    }
    return result
}

private fun Map<String, CborValue>.requiredText(key: String): String =
    (get(key) as? CborValue.Text)?.value ?: error("CBOR map is missing text $key")

private fun Map<String, CborValue>.requiredBytes(key: String): ByteArray =
    (get(key) as? CborValue.Bytes)?.value ?: error("CBOR map is missing bytes $key")

private fun Map<String, CborValue>.requiredMap(key: String): List<Pair<CborValue, CborValue>> =
    (get(key) as? CborValue.Map)?.entries ?: error("CBOR map is missing map $key")

private fun Map<Long, CborValue>.requiredInteger(key: Long): Long =
    (get(key) as? CborValue.Integer)?.value ?: error("COSE key is missing integer $key")

private fun Map<Long, CborValue>.requiredBytes(key: Long): ByteArray =
    (get(key) as? CborValue.Bytes)?.value ?: error("COSE key is missing bytes $key")

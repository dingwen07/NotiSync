package net.extrawdw.notisync.server.auth

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.server.ServerConfig
import net.extrawdw.notisync.server.crypto.Es256
import java.io.File
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

data class AuthPrincipal(val clientId: ClientId, val expiresAtMillis: Long)

class JwtIssuer private constructor(
    private val issuer: String,
    private val ttlMillis: Long,
    private val keyPair: KeyPair,
) {
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val b64d = Base64.getUrlDecoder()
    // RFC 7517 (JWKS) requires kty; RFC 7515 requires the JWT header alg. Encode defaults so the
    // published JWKS and issued tokens are accepted by standards-compliant external verifiers.
    // Backward-compatible: tokens are verified over their literal received header/claims bytes.
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val kid = b64.encodeToString(sha256(keyPair.public.encoded).copyOf(10))

    fun issue(clientId: ClientId, nowMillis: Long = System.currentTimeMillis()): IssuedToken {
        val expiresAtMillis = nowMillis + ttlMillis
        val header = JwtHeader(kid = kid)
        val claims = JwtClaims(
            iss = issuer,
            sub = clientId.value,
            client_id = clientId.value,
            iat = nowMillis / 1000,
            exp = expiresAtMillis / 1000,
        )
        val signingInput = "${encode(header)}.${encode(claims)}"
        val signature = sign(signingInput.toByteArray(Charsets.US_ASCII))
        return IssuedToken("$signingInput.${b64.encodeToString(signature)}", expiresAtMillis)
    }

    fun verify(token: String, nowMillis: Long = System.currentTimeMillis()): AuthPrincipal? {
        val parts = token.split('.')
        if (parts.size != 3) return null
        val header = runCatching { json.decodeFromString<JwtHeader>(String(b64d.decode(parts[0]), Charsets.UTF_8)) }
            .getOrNull() ?: return null
        if (header.alg != "ES256" || header.kid != kid) return null
        val signature = runCatching { b64d.decode(parts[2]) }.getOrNull() ?: return null
        val signingInput = "${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII)
        if (!verifySignature(signingInput, signature)) return null
        val claims = runCatching { json.decodeFromString<JwtClaims>(String(b64d.decode(parts[1]), Charsets.UTF_8)) }
            .getOrNull() ?: return null
        if (claims.iss != issuer || claims.sub != claims.client_id) return null
        val nowSeconds = nowMillis / 1000
        if (claims.exp <= nowSeconds || claims.iat > nowSeconds + 60) return null
        return AuthPrincipal(ClientId(claims.client_id), claims.exp * 1000)
    }

    fun jwksJson(): String {
        val ec = keyPair.public as ECPublicKey
        return json.encodeToString(
            Jwks(
                keys = listOf(
                    Jwk(
                        kid = kid,
                        x = b64.encodeToString(ec.w.affineX.toFixedUnsigned(32)),
                        y = b64.encodeToString(ec.w.affineY.toFixedUnsigned(32)),
                    )
                )
            )
        )
    }

    private inline fun <reified T> encode(value: T): String =
        b64.encodeToString(json.encodeToString(value).toByteArray(Charsets.UTF_8))

    private fun sign(data: ByteArray): ByteArray = Es256.sign(keyPair.private, data)

    private fun verifySignature(data: ByteArray, joseSignature: ByteArray): Boolean {
        if (joseSignature.size != 64) return false
        return Signature.getInstance("SHA256withECDSA").run {
            initVerify(keyPair.public)
            update(data)
            verify(Es256.joseToDer(joseSignature))
        }
    }

    data class IssuedToken(val token: String, val expiresAtMillis: Long)

    companion object {
        fun load(config: ServerConfig): JwtIssuer =
            JwtIssuer(config.jwtIssuer, config.jwtTtlMillis, loadOrCreateKeyPair(config.jwtPrivateKeyPath))

        private fun loadOrCreateKeyPair(path: String): KeyPair {
            val file = File(path)
            val publicFile = File("$path.pub")
            if (file.isFile) {
                // The private key is the source of truth and is NEVER overwritten. If the public
                // sidecar is missing, derive it from the private key rather than regenerating — a lost
                // .pub must not silently rotate the key and invalidate every outstanding JWT.
                val privateKey = Es256.loadEcPrivateKeyPem(file.readText()) as ECPrivateKey
                val publicKey = if (publicFile.isFile) {
                    readPublicKey(publicFile.readText())
                } else {
                    derivePublicKey(privateKey).also {
                        publicFile.writeText(pem("PUBLIC KEY", it.encoded))
                        restrictPublicFile(publicFile)
                    }
                }
                return KeyPair(publicKey, privateKey)
            }
            file.absoluteFile.parentFile?.mkdirs()
            val generated = KeyPairGenerator.getInstance("EC").run {
                initialize(ECGenParameterSpec("secp256r1"))
                generateKeyPair()
            }
            // Create the private file owner-only BEFORE writing the secret, so it is never briefly
            // world-readable (umask) in the window between writing and chmod.
            writePrivatePem(file, pem("PRIVATE KEY", generated.private.encoded))
            publicFile.writeText(pem("PUBLIC KEY", generated.public.encoded))
            restrictPublicFile(publicFile)
            return generated
        }

        private fun writePrivatePem(file: File, content: String) {
            val nioPath = file.toPath()
            Files.deleteIfExists(nioPath)
            val createdRestricted = runCatching {
                Files.createFile(
                    nioPath,
                    PosixFilePermissions.asFileAttribute(
                        setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    ),
                )
            }.isSuccess
            if (!createdRestricted) {
                // Non-POSIX filesystem: best-effort create-then-restrict (small exposure window).
                file.createNewFile()
                file.setReadable(false, false); file.setReadable(true, true)
                file.setWritable(false, false); file.setWritable(true, true)
            }
            file.writeText(content) // writes into the already-created, owner-only file
        }

        private fun restrictPublicFile(file: File) {
            file.setReadable(true, false) // world-readable: it is the public key
            file.setWritable(false, false)
            file.setWritable(true, true)
        }

        /** Recompute the EC public key W = s·G from the private scalar (pure JCA + BigInteger). */
        private fun derivePublicKey(priv: ECPrivateKey): ECPublicKey {
            val params = priv.params
            val p = (params.curve.field as ECFieldFp).p
            val w = scalarMultiply(priv.s, params.generator, params.curve.a, p)
            return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(w, params)) as ECPublicKey
        }

        private fun scalarMultiply(k: BigInteger, g: ECPoint, a: BigInteger, p: BigInteger): ECPoint {
            var result = ECPoint.POINT_INFINITY
            var addend = g
            var n = k
            while (n.signum() > 0) {
                if (n.testBit(0)) result = pointAdd(result, addend, a, p)
                addend = pointDouble(addend, a, p)
                n = n.shiftRight(1)
            }
            return result
        }

        private fun pointAdd(p1: ECPoint, p2: ECPoint, a: BigInteger, p: BigInteger): ECPoint {
            if (p1 == ECPoint.POINT_INFINITY) return p2
            if (p2 == ECPoint.POINT_INFINITY) return p1
            val x1 = p1.affineX; val y1 = p1.affineY; val x2 = p2.affineX; val y2 = p2.affineY
            if (x1 == x2) {
                return if ((y1 + y2).mod(p).signum() == 0) ECPoint.POINT_INFINITY else pointDouble(p1, a, p)
            }
            val lambda = ((y2 - y1).mod(p) * (x2 - x1).mod(p).modInverse(p)).mod(p)
            val x3 = (lambda * lambda - x1 - x2).mod(p)
            val y3 = (lambda * (x1 - x3) - y1).mod(p)
            return ECPoint(x3, y3)
        }

        private fun pointDouble(pt: ECPoint, a: BigInteger, p: BigInteger): ECPoint {
            if (pt == ECPoint.POINT_INFINITY) return pt
            val x = pt.affineX; val y = pt.affineY
            if (y.signum() == 0) return ECPoint.POINT_INFINITY
            val two = BigInteger.TWO; val three = BigInteger.valueOf(3)
            val lambda = ((three * x * x + a).mod(p) * (two * y).mod(p).modInverse(p)).mod(p)
            val x3 = (lambda * lambda - two * x).mod(p)
            val y3 = (lambda * (x - x3) - y).mod(p)
            return ECPoint(x3, y3)
        }

        private fun readPublicKey(pem: String): PublicKey {
            val body = pem.lineSequence()
                .filterNot { it.startsWith("-----") }
                .joinToString("")
            val bytes = Base64.getMimeDecoder().decode(body)
            return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
        }

        private fun pem(label: String, bytes: ByteArray): String =
            buildString {
                append("-----BEGIN ").append(label).append("-----\n")
                append(Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(bytes))
                append("\n-----END ").append(label).append("-----\n")
            }

        private fun sha256(bytes: ByteArray): ByteArray =
            java.security.MessageDigest.getInstance("SHA-256").digest(bytes)

        private fun BigInteger.toFixedUnsigned(size: Int): ByteArray {
            val raw = toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
            return ByteArray(size - raw.size.coerceAtMost(size)) + raw.takeLast(size).toByteArray()
        }
    }
}

@Serializable
private data class JwtHeader(
    val alg: String = "ES256",
    val typ: String = "JWT",
    val kid: String,
)

@Serializable
private data class JwtClaims(
    val iss: String,
    val sub: String,
    val client_id: String,
    val iat: Long,
    val exp: Long,
)

@Serializable
private data class Jwks(val keys: List<Jwk>)

@Serializable
private data class Jwk(
    val kty: String = "EC",
    val use: String = "sig",
    val alg: String = "ES256",
    val crv: String = "P-256",
    val kid: String,
    val x: String,
    val y: String,
)

package net.extrawdw.notisync.gpg

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import net.extrawdw.notisync.desktop.PrivateFiles
import net.extrawdw.notisync.protocol.OpenPgpSignLimits

data class VerifiedSignature(
    val armor: String,
    val sigCreatedStatus: String,
    val signingFingerprint: String,
    val primaryFingerprint: String,
)

internal data class ParsedValidSignature(
    val sigCreatedStatus: String,
    val signingFingerprint: String,
    val primaryFingerprint: String,
)

class GpgSignatureVerifier(
    private val realGpgPath: Path,
    private val dataDirectory: Path,
) {
    fun verify(
        armor: String,
        payload: ByteArray,
        certificate: ResolvedOpenPgpCertificate,
        issuedAtMillis: Long,
        expiresAtMillis: Long,
    ): VerifiedSignature {
        require(armor.startsWith("-----BEGIN PGP SIGNATURE-----")) { "response is not an armored signature" }
        require(armor.trimEnd().endsWith("-----END PGP SIGNATURE-----")) {
            "response has incomplete signature armor"
        }
        val parent = PrivateFiles.ensureDirectory(dataDirectory)
        val temporary = Files.createTempDirectory(parent, ".notisync-gpg-verify-")
        PrivateFiles.ensureDirectory(temporary)
        val signature = temporary.resolve("signature.asc")
        try {
            PrivateFiles.atomicWrite(signature, armor.encodeToByteArray())
            val result = ProcessExecution.capture(
                command = listOf(
                    realGpgPath.toString(),
                    "--batch",
                    "--no-tty",
                    "--no-auto-key-retrieve",
                    "--status-fd=1",
                    "--verify",
                    signature.toString(),
                    "-",
                ),
                stdin = payload,
                timeout = Duration.ofSeconds(30),
            )
            require(result.exitCode == 0) { "real GPG rejected the returned signature" }
            val parsed = parseValidSignatureStatus(
                result.output.decodeToString(),
                certificate,
                issuedAtMillis,
                expiresAtMillis,
            )

            return VerifiedSignature(
                armor = armor,
                sigCreatedStatus = parsed.sigCreatedStatus,
                signingFingerprint = parsed.signingFingerprint,
                primaryFingerprint = parsed.primaryFingerprint,
            )
        } finally {
            runCatching { Files.deleteIfExists(signature) }
            runCatching { Files.deleteIfExists(temporary) }
        }
    }
}

internal fun parseValidSignatureStatus(
    status: String,
    certificate: ResolvedOpenPgpCertificate,
    issuedAtMillis: Long,
    expiresAtMillis: Long,
): ParsedValidSignature {
    val valid = status.lineSequence()
        .map(String::trim)
        .filter { it.startsWith("[GNUPG:] VALIDSIG ") }
        .toList()
    require(valid.size == 1) { "real GPG did not report exactly one valid signature" }
    val fields = valid.single().split(Regex("\\s+"))
    require(fields.size >= 11) { "real GPG returned a malformed VALIDSIG record" }
    val signingFingerprint = fields[2].uppercase()
    val creationSeconds = fields[4].toLongOrNull()
        ?: throw IllegalArgumentException("real GPG returned an invalid signature timestamp")
    val publicKeyAlgorithm = fields[8]
    val hashAlgorithm = fields[9]
    val signatureClass = fields[10].uppercase()
    val primaryFingerprint = fields.getOrNull(11)?.takeIf { it.isNotBlank() }?.uppercase()
        ?: signingFingerprint
    require(signatureClass == "00") { "returned signature is not a detached document signature" }
    require(primaryFingerprint == certificate.primaryFingerprint.uppercase()) {
        "returned signature belongs to a different primary certificate"
    }
    require(primaryFingerprint.takeLast(16) == certificate.primaryKeyId) {
        "returned signature has a different primary key ID"
    }
    val creationMillis = Math.multiplyExact(creationSeconds, 1_000L)
    require(
        creationMillis >= issuedAtMillis - OpenPgpSignLimits.CLOCK_SKEW_MILLIS &&
            creationMillis <= expiresAtMillis + OpenPgpSignLimits.CLOCK_SKEW_MILLIS
    ) { "returned signature was created outside the request lifetime" }

    return ParsedValidSignature(
        sigCreatedStatus = "[GNUPG:] SIG_CREATED D $publicKeyAlgorithm $hashAlgorithm 00 " +
            "$creationSeconds $signingFingerprint",
        signingFingerprint = signingFingerprint,
        primaryFingerprint = primaryFingerprint,
    )
}

package net.extrawdw.notisync.gpg

import java.nio.file.Path

data class ResolvedOpenPgpCertificate(
    val primaryFingerprint: String,
    val primaryKeyId: String,
    val selectorNamedSubkey: Boolean,
)

class GpgKeyResolver(private val realGpgPath: Path) {
    fun resolve(selector: String): ResolvedOpenPgpCertificate {
        val result = ProcessExecution.capture(
            listOf(
                realGpgPath.toString(),
                "--batch",
                "--no-tty",
                "--with-colons",
                "--fixed-list-mode",
                "--fingerprint",
                "--fingerprint",
                "--list-keys",
                selector,
            )
        )
        require(result.exitCode == 0) { "real GPG could not resolve the requested public certificate" }

        return resolveFromColonListing(selector, result.output.decodeToString())
    }

    internal fun resolveFromColonListing(
        selector: String,
        listing: String,
    ): ResolvedOpenPgpCertificate {
        val normalized = selector.removePrefix("0x").removePrefix("0X").uppercase()

        val certificates = mutableListOf<Certificate>()
        var current: Certificate? = null
        var lastRecord: String? = null
        listing.lineSequence().forEach { line ->
            val fields = line.split(':')
            when (fields.firstOrNull()) {
                "pub" -> {
                    current = Certificate(
                        primaryKeyId = fields.getOrNull(4).orEmpty().uppercase(),
                        primaryUsable = fields.usableRecord(),
                    )
                    certificates += current!!
                    current!!.identifiers += current!!.primaryKeyId
                    if (fields.usableSigningRecord()) current!!.hasUsableSigningKey = true
                    lastRecord = "pub"
                }
                "sub" -> {
                    current?.identifiers?.add(fields.getOrNull(4).orEmpty().uppercase())
                    if (fields.usableSigningRecord()) current?.hasUsableSigningKey = true
                    lastRecord = "sub"
                }
                "fpr" -> {
                    val fingerprint = fields.getOrNull(9).orEmpty().uppercase()
                    if (fingerprint.matches(FINGERPRINT)) {
                        current?.identifiers?.add(fingerprint)
                        if (lastRecord == "pub" && current?.primaryFingerprint == null) {
                            current?.primaryFingerprint = fingerprint
                        }
                    }
                    lastRecord = "fpr"
                }
                else -> Unit
            }
        }

        val matches = certificates.filter { normalized in it.identifiers }
        require(matches.size == 1) {
            if (matches.isEmpty()) "the selector is missing from the public keyring"
            else "the selector is ambiguous in the public keyring"
        }
        val certificate = matches.single()
        val fingerprint = requireNotNull(certificate.primaryFingerprint) {
            "real GPG did not report the primary fingerprint"
        }
        val primaryId = fingerprint.takeLast(16)
        require(certificate.primaryKeyId.takeLast(16) == primaryId) {
            "real GPG reported inconsistent primary certificate identity"
        }
        require(certificate.primaryUsable && certificate.hasUsableSigningKey) {
            "the selected certificate has no usable signing key"
        }
        return ResolvedOpenPgpCertificate(
            primaryFingerprint = fingerprint,
            primaryKeyId = primaryId,
            selectorNamedSubkey = normalized != primaryId && normalized != fingerprint,
        )
    }

    private data class Certificate(
        val primaryKeyId: String,
        val primaryUsable: Boolean,
        var primaryFingerprint: String? = null,
        var hasUsableSigningKey: Boolean = false,
        val identifiers: MutableSet<String> = linkedSetOf(),
    )

    private fun List<String>.usableRecord(): Boolean =
        getOrNull(1).orEmpty().lowercase() !in setOf("r", "e", "d", "i")

    private fun List<String>.usableSigningRecord(): Boolean =
        usableRecord() && getOrNull(11).orEmpty().contains('s')

    private companion object {
        val FINGERPRINT = Regex("(?:[0-9A-F]{40}|[0-9A-F]{64})")
    }
}

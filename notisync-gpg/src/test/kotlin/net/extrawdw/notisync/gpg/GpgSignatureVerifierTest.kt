package net.extrawdw.notisync.gpg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GpgSignatureVerifierTest {
    private val primary = "0123456789ABCDEF0123456789ABCDEF89ABCDEF"
    private val signing = "FEDCBA9876543210FEDCBA987654321001234567"
    private val certificate = ResolvedOpenPgpCertificate(primary, primary.takeLast(16), true)

    @Test
    fun derivesGitStatusFromVerifiedSigningSubkeyAndPrimary() {
        val parsed = parseValidSignatureStatus(
            "[GNUPG:] VALIDSIG $signing 20231114 1700000000 0 4 0 22 8 00 $primary\n",
            certificate,
            issuedAtMillis = 1_699_999_990_000,
            expiresAtMillis = 1_700_000_010_000,
        )

        assertEquals(signing, parsed.signingFingerprint)
        assertEquals(primary, parsed.primaryFingerprint)
        assertEquals("[GNUPG:] SIG_CREATED D 22 8 00 1700000000 $signing", parsed.sigCreatedStatus)
    }

    @Test
    fun rejectsWrongPrimaryClassAndTimestamp() {
        val base = "[GNUPG:] VALIDSIG $signing 20231114 1700000000 0 4 0 22 8 00 $primary\n"
        assertThrows(IllegalArgumentException::class.java) {
            parseValidSignatureStatus(base.replace(primary, "F".repeat(40)), certificate, 1_699_999_990_000, 1_700_000_010_000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseValidSignatureStatus(base.replace(" 00 $primary", " 01 $primary"), certificate, 1_699_999_990_000, 1_700_000_010_000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseValidSignatureStatus(base, certificate, 1_800_000_000_000, 1_800_000_010_000)
        }
    }
}

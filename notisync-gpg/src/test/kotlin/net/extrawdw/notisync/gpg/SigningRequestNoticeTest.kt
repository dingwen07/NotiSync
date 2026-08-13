package net.extrawdw.notisync.gpg

import java.io.ByteArrayOutputStream
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.OpenPgpObjectKind
import net.extrawdw.notisync.protocol.OpenPgpSignAction
import net.extrawdw.notisync.protocol.OpenPgpSignSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SigningRequestNoticeTest {
    @Test
    fun writesOnlyAShortComparisonCodeToTheControllingTerminal() {
        val terminal = ByteArrayOutputStream()
        val request = OpenPgpSignSync(
            action = OpenPgpSignAction.REQUEST,
            requestId = "0123456789abcdef0123456789abcdef",
            requesterClientId = ClientId("desktop"),
            issuedAt = 1,
            expiresAt = 2,
            primaryKeyId = "0123456789ABCDEF",
            payloadSha256 = byteArrayOf(0x66, 0x48, 0xf0.toByte(), 0xd8.toByte()) + ByteArray(28),
            objectKind = OpenPgpObjectKind.GIT_COMMIT,
            payload = byteArrayOf(1),
        )

        assertTrue(SigningRequestNotice { terminal }.show(request))
        assertEquals(
            "NotiSync Seal: compare verification code 6648f0d on your phone (request 01234567)\n",
            terminal.toString(Charsets.UTF_8),
        )
    }
}

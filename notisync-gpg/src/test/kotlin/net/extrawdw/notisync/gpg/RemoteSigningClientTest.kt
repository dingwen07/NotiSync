package net.extrawdw.notisync.gpg

import java.nio.file.Path
import java.util.Base64
import net.extrawdw.notisync.desktop.DesktopPaths
import net.extrawdw.notisync.desktop.api.DaemonLocalApi
import net.extrawdw.notisync.desktop.api.ReceiveStream
import net.extrawdw.notisync.localapi.ApplicationListResponse
import net.extrawdw.notisync.localapi.ApplicationRegistrationRequest
import net.extrawdw.notisync.localapi.ApplicationView
import net.extrawdw.notisync.localapi.DaemonConnectionState
import net.extrawdw.notisync.localapi.DaemonStatus
import net.extrawdw.notisync.localapi.ReceiveRecord
import net.extrawdw.notisync.localapi.ReceiveRecordType
import net.extrawdw.notisync.localapi.ReceiveRequest
import net.extrawdw.notisync.localapi.SendAccepted
import net.extrawdw.notisync.localapi.SendRequest
import net.extrawdw.notisync.protocol.DataSync
import net.extrawdw.notisync.protocol.DataSyncKind
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.OpenPgpRejectReason
import net.extrawdw.notisync.protocol.OpenPgpSignAction
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.Urgency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteSigningClientTest {
    @Test
    fun opensInterestBeforeRequestAndAlwaysCompletesCancelsAndUnregisters() {
        val api = FakeDaemonApi()
        val paths = DesktopPaths(Path.of("build/test-remote-signing"))
        val config = NotisyncGpgConfig(Path.of("unused-gpg"), timeoutSeconds = 30)
        val certificate = ResolvedOpenPgpCertificate(
            primaryFingerprint = "0123456789ABCDEF0123456789ABCDEF89ABCDEF",
            primaryKeyId = "89ABCDEF89ABCDEF",
            selectorNamedSubkey = false,
        )

        val outcome = RemoteSigningClient(paths, config, api = api, now = { 1_000 }).sign(
            validCommit(),
            certificate,
        )

        assertEquals(
            RemoteSigningOutcome.Rejected(OpenPgpRejectReason.USER_REJECTED.name),
            outcome,
        )
        assertEquals(listOf("status", "register", "open", "send", "next", "complete", "send", "close", "unregister"), api.calls)
        assertEquals(Urgency.HIGH, api.sends.first().urgency)
        assertEquals(Urgency.NORMAL, api.sends.last().urgency)
        assertTrue(api.sends.last().submissionId!!.endsWith("-cancel"))
    }

    private fun validCommit() = (
        "tree ${"0".repeat(40)}\n" +
            "author A <a@example.com> 1 +0000\n" +
            "committer A <a@example.com> 1 +0000\n\nmessage\n"
        ).encodeToByteArray()

    private class FakeDaemonApi : DaemonLocalApi {
        val calls = mutableListOf<String>()
        val sends = mutableListOf<SendRequest>()
        private lateinit var interest: ReceiveRequest

        override fun status(): DaemonStatus {
            calls += "status"
            return DaemonStatus("test", "desktop", connectionState = DaemonConnectionState.CONNECTED)
        }

        override fun putApplication(
            applicationId: String,
            request: ApplicationRegistrationRequest,
        ): ApplicationView {
            calls += "register"
            return ApplicationView(applicationId, request.displayName, request.version, emptyList(), 1_000)
        }

        override fun openReceive(request: ReceiveRequest): ReceiveStream {
            calls += "open"
            interest = request
            return object : ReceiveStream {
                private var delivered = false

                override fun next(): ReceiveRecord? {
                    calls += "next"
                    if (delivered) return null
                    delivered = true
                    val requestSync = decode(sends.first()).openPgpSign!!
                    val response = requestSync.copy(
                        action = OpenPgpSignAction.REJECT,
                        payload = null,
                        rejectReason = OpenPgpRejectReason.USER_REJECTED,
                        actionAt = 1_100,
                    )
                    return ReceiveRecord(
                        recordType = ReceiveRecordType.MESSAGE,
                        applicationId = "notisync-gpg",
                        envelopeId = "envelope",
                        messageType = MessageType.DATA_SYNC,
                        body = Base64.getEncoder().encodeToString(
                            ProtocolCodec.encodeToCbor(
                                DataSync(DataSyncKind.OPENPGP_SIGN, openPgpSign = response)
                            )
                        ),
                        senderClientId = "phone",
                        senderOwnDevice = true,
                        signerEpoch = 1,
                        receivedAtEpochMillis = 1_100,
                    )
                }

                override fun close() {
                    calls += "close"
                }
            }
        }

        override fun send(request: SendRequest): SendAccepted {
            calls += "send"
            sends += request
            return SendAccepted("message-${sends.size}", 1_000, request.submissionId)
        }

        override fun complete(applicationId: String, envelopeId: String, sends: List<SendRequest>) {
            calls += "complete"
        }

        override fun unregisterReceive(request: ReceiveRequest) {
            calls += "unregister"
            assertEquals(interest, request)
        }

        override fun listApplications() = ApplicationListResponse(emptyList(), emptyList())
        override fun deleteApplication(applicationId: String) = Unit
        override fun sendAll(requests: List<SendRequest>) = requests.map(::send)
        override fun ack(applicationId: String, envelopeId: String) = Unit

        private fun decode(request: SendRequest): DataSync = ProtocolCodec.decodeFromCbor(
            Base64.getDecoder().decode(request.body)
        )
    }
}

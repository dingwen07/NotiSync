package net.extrawdw.notisync.sshagent

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.Comparator
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import net.extrawdw.notisync.desktop.api.DaemonLocalApi
import net.extrawdw.notisync.desktop.api.ReceiveStream
import net.extrawdw.notisync.localapi.ApplicationListResponse
import net.extrawdw.notisync.localapi.ApplicationRegistrationRequest
import net.extrawdw.notisync.localapi.ApplicationView
import net.extrawdw.notisync.localapi.DaemonConnectionState
import net.extrawdw.notisync.localapi.DaemonStatus
import net.extrawdw.notisync.localapi.DeviceClassification
import net.extrawdw.notisync.localapi.DeviceListResponse
import net.extrawdw.notisync.localapi.DeviceTrustStatus
import net.extrawdw.notisync.localapi.DeviceView
import net.extrawdw.notisync.localapi.ReceiveRecord
import net.extrawdw.notisync.localapi.ReceiveRecordType
import net.extrawdw.notisync.localapi.ReceiveRequest
import net.extrawdw.notisync.localapi.SendAccepted
import net.extrawdw.notisync.localapi.SendRequest
import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.DataSync
import net.extrawdw.notisync.protocol.DataSyncKind
import net.extrawdw.notisync.protocol.DesktopProcessContext
import net.extrawdw.notisync.protocol.DesktopProcessContextSource
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.SshApprovalPolicy
import net.extrawdw.notisync.protocol.SshAgentSync
import net.extrawdw.notisync.protocol.SshAgentSyncKind
import net.extrawdw.notisync.protocol.SshKeyAlgorithm
import net.extrawdw.notisync.protocol.SshKeyDescriptor
import net.extrawdw.notisync.protocol.SshKeyOrigin
import net.extrawdw.notisync.protocol.SshKeysSnapshot
import net.extrawdw.notisync.protocol.SshOperationalKeyProtection
import net.extrawdw.notisync.protocol.SshOperationalKeyProvider
import net.extrawdw.notisync.protocol.SshProviderHealth
import net.extrawdw.notisync.protocol.SshRememberDisposition
import net.extrawdw.notisync.protocol.SshSignResult
import net.extrawdw.notisync.protocol.SshSignResultKind
import net.extrawdw.notisync.protocol.SshSignatureAlgorithm
import net.extrawdw.notisync.protocol.SshSignatureResult
import net.extrawdw.notisync.protocol.SshStorageSecurityLevel
import net.extrawdw.notisync.protocol.SshUserVerificationPolicy
import net.extrawdw.notisync.protocol.SshWebAuthnCredentialProtection
import net.extrawdw.notisync.ssh.core.AgentNumbers
import net.extrawdw.notisync.ssh.core.EcdsaSignatureTranscoder
import net.extrawdw.notisync.ssh.core.SshAgentFrameCodec
import net.extrawdw.notisync.ssh.core.SshPublicKeyCodec
import net.extrawdw.notisync.ssh.core.SshSignatureMethod
import net.extrawdw.notisync.ssh.core.SshSignatureVerifier
import net.extrawdw.notisync.ssh.core.SshUserAuthParser
import net.extrawdw.notisync.ssh.core.SshWireReader
import net.extrawdw.notisync.ssh.core.SshWireWriter
import net.extrawdw.notisync.ssh.core.WebAuthnSshSignature
import net.extrawdw.notisync.ssh.core.WebAuthnSshSignatureCodec
import net.extrawdw.notisync.sshagent.bridge.InboundSshSyncLoop
import net.extrawdw.notisync.sshagent.bridge.ProviderRoster
import net.extrawdw.notisync.sshagent.bridge.SshApplicationBridge
import net.extrawdw.notisync.sshagent.cache.AgentDatabase
import net.extrawdw.notisync.sshagent.cache.AgentMetadataStore
import net.extrawdw.notisync.sshagent.cache.AuthorizationForgetOutbox
import net.extrawdw.notisync.sshagent.cache.ProviderSnapshotStore
import net.extrawdw.notisync.sshagent.cache.SnapshotApplyResult
import net.extrawdw.notisync.sshagent.endpoint.AgentConnectionHandler
import net.extrawdw.notisync.sshagent.endpoint.AgentLockState
import net.extrawdw.notisync.sshagent.endpoint.AuthorizationLockCoordinator
import net.extrawdw.notisync.sshagent.endpoint.LocalCallerSnapshot
import net.extrawdw.notisync.sshagent.signing.SignCoordinator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebAuthnSshAgentEndToEndTest {
    @Test
    fun `desktop lists a WebAuthn key and returns a verified OpenSSH signature`() {
        withTemporaryDatabase { database ->
            val requester = ClientId("a".repeat(52))
            val provider = ClientId("b".repeat(52))
            val api = FakeApi(requester, provider)
            val roster = ProviderRoster(api)
            val snapshots = ProviderSnapshotStore(database)
            val metadata = AgentMetadataStore(database)
            val bridge = SshApplicationBridge(api, roster, now = { NOW })
            val signing = SignCoordinator(
                requesterClientId = requester,
                config = AgentConfig(signTimeoutSeconds = 5),
                roster = roster,
                snapshots = snapshots,
                metadata = metadata,
                bridge = bridge,
                database = database,
                now = { NOW },
            )
            val lock = AuthorizationLockCoordinator(
                requesterClientId = requester,
                lockState = AgentLockState(),
                metadata = metadata,
                roster = roster,
                bridge = bridge,
                signing = signing,
                forgetOutbox = AuthorizationForgetOutbox(database),
                now = { NOW },
            )
            val keyPair = KeyPairGenerator.getInstance("EC").run {
                initialize(ECGenParameterSpec("secp256r1"))
                generateKeyPair()
            }
            val publicKeyBlob = SshPublicKeyCodec.encodeWebAuthnEcdsaP256(keyPair.public, RP_ID)
            val descriptor = webAuthnDescriptor(publicKeyBlob)
            assertEquals(
                SnapshotApplyResult.APPLIED,
                snapshots.apply(
                    provider,
                    SshKeysSnapshot(
                        providerClientId = provider,
                        inventoryGeneration = "1".repeat(32),
                        revision = 1,
                        generatedAt = NOW,
                        keys = listOf(descriptor),
                        providerHealth = SshProviderHealth.HEALTHY,
                    ),
                    NOW,
                ),
            )

            val signData = userAuthData(publicKeyBlob)
            val requests = ByteArrayOutputStream().also { input ->
                SshAgentFrameCodec.write(
                    input,
                    byteArrayOf(AgentNumbers.SSH_AGENTC_REQUEST_IDENTITIES.toByte()),
                )
                SshAgentFrameCodec.write(
                    input,
                    SshWireWriter()
                        .writeByte(AgentNumbers.SSH_AGENTC_SIGN_REQUEST)
                        .writeString(publicKeyBlob)
                        .writeString(signData)
                        .writeUInt32(0)
                        .toByteArray(),
                )
            }.toByteArray()
            val responses = ByteArrayOutputStream()
            val handler = AgentConnectionHandler(signing, snapshots, lock)
            val executor = Executors.newSingleThreadExecutor()
            try {
                val handled = executor.submit {
                    handler.handle(
                        ByteArrayInputStream(requests),
                        responses,
                        LocalCallerSnapshot(
                            DesktopProcessContext(DesktopProcessContextSource.UNAVAILABLE),
                            null,
                        ),
                    )
                }
                val outbound = api.outbound.poll(5, TimeUnit.SECONDS)
                    ?: error("desktop did not send the WebAuthn signing request")
                val signRequest = requireNotNull(decode(outbound).sshAgent?.signRequest)
                assertEquals(SshSignatureAlgorithm.WEBAUTHN_SK_ECDSA_NISTP256, signRequest.requestedSignatureAlgorithm)
                assertArrayEquals(publicKeyBlob, signRequest.publicKeyBlob)
                assertArrayEquals(signData, signRequest.data)

                val signatureBlob = webAuthnSignature(keyPair.private, signData)
                InboundSshSyncLoop(api, snapshots, signing, now = { NOW }).process(
                    resultRecord(
                        provider,
                        SshSignResult(
                            requestId = signRequest.requestId,
                            requesterClientId = requester,
                            publicKeyBlobSha256 = sha256(publicKeyBlob),
                            kind = SshSignResultKind.SIGNED,
                            resultAt = NOW,
                            providerClientId = provider,
                            signature = SshSignatureResult(
                                signatureBlob = signatureBlob,
                                rememberDisposition = SshRememberDisposition.NONE,
                                authorizationGeneration = signRequest.authorizationGeneration,
                                authorizationEpoch = signRequest.authorizationEpoch,
                            ),
                        ),
                    ),
                )
                handled.get(5, TimeUnit.SECONDS)

                val output = ByteArrayInputStream(responses.toByteArray())
                val identities = SshWireReader(requireNotNull(SshAgentFrameCodec.read(output)))
                assertEquals(AgentNumbers.SSH_AGENT_IDENTITIES_ANSWER, identities.readByte())
                assertEquals(1L, identities.readUInt32())
                assertArrayEquals(publicKeyBlob, identities.readString(16 * 1024))
                assertEquals(DISPLAY_NAME, identities.readUtf8(1024))
                identities.requireEnd()

                val signed = SshWireReader(requireNotNull(SshAgentFrameCodec.read(output)))
                assertEquals(AgentNumbers.SSH_AGENT_SIGN_RESPONSE, signed.readByte())
                val returnedSignature = signed.readString(16 * 1024)
                signed.requireEnd()
                assertArrayEquals(signatureBlob, returnedSignature)
                assertTrue(
                    SshSignatureVerifier.verify(
                        publicKeyBlob,
                        signData,
                        returnedSignature,
                        SshSignatureMethod.WEBAUTHN_SK_ECDSA_NISTP256,
                    ),
                )
                assertEquals(null, SshAgentFrameCodec.read(output))
                assertEquals(listOf("result-envelope"), api.completed)
            } finally {
                executor.shutdownNow()
            }
        }
    }

    private fun webAuthnDescriptor(publicKeyBlob: ByteArray) = SshKeyDescriptor(
        providerKeyId = "2".repeat(32),
        publicKeyBlob = publicKeyBlob,
        publicKeyBlobSha256 = sha256(publicKeyBlob),
        algorithm = SshKeyAlgorithm.WEBAUTHN_SK_ECDSA_NISTP256,
        displayName = DISPLAY_NAME,
        origin = SshKeyOrigin.WEBAUTHN_CREATED,
        operationalKey = SshOperationalKeyProtection(
            provider = SshOperationalKeyProvider.CREDENTIAL_MANAGER_WEBAUTHN,
            securityLevel = SshStorageSecurityLevel.CREDENTIAL_PROVIDER,
            userVerificationPolicy = SshUserVerificationPolicy.PER_USE,
            strongBoxAttempted = false,
            strongBoxFallback = false,
        ),
        exportCopy = null,
        approvalPolicy = SshApprovalPolicy.ALWAYS_ASK,
        createdAt = NOW,
        webAuthn = SshWebAuthnCredentialProtection(
            rpId = RP_ID,
            backupEligible = true,
            backupState = true,
        ),
    )

    private fun userAuthData(publicKeyBlob: ByteArray): ByteArray = SshWireWriter()
        .writeString(ByteArray(32) { it.toByte() })
        .writeByte(50)
        .writeUtf8("deploy")
        .writeUtf8("ssh-connection")
        .writeUtf8(SshUserAuthParser.PUBLIC_KEY_METHOD)
        .writeBoolean(true)
        .writeUtf8("sk-ecdsa-sha2-nistp256@openssh.com")
        .writeString(publicKeyBlob)
        .toByteArray()

    private fun webAuthnSignature(privateKey: java.security.PrivateKey, data: ByteArray): ByteArray {
        val flags = WebAuthnSshSignatureCodec.FLAG_USER_PRESENT or
            WebAuthnSshSignatureCodec.FLAG_USER_VERIFIED or
            WebAuthnSshSignatureCodec.FLAG_BACKUP_ELIGIBLE or
            WebAuthnSshSignatureCodec.FLAG_BACKUP_STATE
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(data)
        val clientData =
            "{\"type\":\"webauthn.get\",\"challenge\":\"$challenge\",\"origin\":\"$ORIGIN\",\"crossOrigin\":false}"
                .encodeToByteArray()
        val authenticatorData = SshWireWriter()
            .writeRaw(sha256(RP_ID.encodeToByteArray()))
            .writeByte(flags)
            .writeUInt32(COUNTER)
            .toByteArray()
        val derSignature = Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(authenticatorData + sha256(clientData))
            sign()
        }
        return WebAuthnSshSignatureCodec.encode(
            WebAuthnSshSignature(
                ecdsaSignature = EcdsaSignatureTranscoder.derToSsh(derSignature),
                flags = flags,
                counter = COUNTER,
                origin = ORIGIN,
                clientDataJson = clientData,
                extensions = byteArrayOf(),
            ),
        )
    }

    private fun resultRecord(provider: ClientId, result: SshSignResult): ReceiveRecord {
        val sync = DataSync(
            kind = DataSyncKind.SSH_AGENT,
            sshAgent = SshAgentSync(kind = SshAgentSyncKind.SIGN_RESULT, signResult = result),
        )
        return ReceiveRecord(
            recordType = ReceiveRecordType.MESSAGE,
            applicationId = SshApplicationBridge.APPLICATION_ID,
            envelopeId = "result-envelope",
            messageType = MessageType.DATA_SYNC,
            body = Base64.getEncoder().encodeToString(ProtocolCodec.encodeToCbor(sync)),
            senderClientId = provider.value,
            senderOwnDevice = true,
            receivedAtEpochMillis = NOW,
        )
    }

    private fun decode(request: SendRequest): DataSync = ProtocolCodec.decodeFromCbor(
        Base64.getDecoder().decode(request.body),
    )

    private fun withTemporaryDatabase(block: (AgentDatabase) -> Unit) {
        val root = Path.of("build", "tmp", "webauthn-ssh-agent-end-to-end-test").toAbsolutePath()
        Files.createDirectories(root)
        val directory = Files.createTempDirectory(root, "notisync-ssh-agent-test-")
        try {
            AgentDatabase(directory.resolve("agent.sqlite3")).use(block)
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    private class FakeApi(
        private val requester: ClientId,
        provider: ClientId,
    ) : DaemonLocalApi {
        val outbound = LinkedBlockingQueue<SendRequest>()
        val completed = mutableListOf<String>()
        private val devices = DeviceListResponse(
            listOf(
                DeviceView(
                    clientId = provider.value,
                    name = "Android provider",
                    classification = DeviceClassification.OWN,
                    trustStatus = DeviceTrustStatus.TRUSTED,
                    capabilities = setOf(
                        Capability.CAPABILITY_ROUTING_V1.name,
                        Capability.SSH_KEY_PROVIDER_V1.name,
                        Capability.PUSH_FILTERING.name,
                    ),
                    identityFingerprint = "provider-fingerprint",
                    keyAvailable = true,
                    verified = true,
                ),
            ),
        )

        override fun status() = DaemonStatus(
            version = "test",
            clientId = requester.value,
            connectionState = DaemonConnectionState.CONNECTED,
        )

        override fun devices() = devices

        override fun putApplication(applicationId: String, request: ApplicationRegistrationRequest) =
            ApplicationView(applicationId, request.displayName, request.version, request.capabilities.toList(), 1)

        override fun listApplications() = ApplicationListResponse(emptyList(), emptyList())
        override fun deleteApplication(applicationId: String) = Unit
        override fun send(request: SendRequest): SendAccepted = sendAll(listOf(request)).single()

        override fun sendAll(requests: List<SendRequest>): List<SendAccepted> {
            requests.forEach(outbound::put)
            return requests.mapIndexed { index, request -> SendAccepted("message-$index", NOW, request.submissionId) }
        }

        override fun openReceive(request: ReceiveRequest): ReceiveStream = error("not used")
        override fun unregisterReceive(request: ReceiveRequest) = Unit
        override fun ack(applicationId: String, envelopeId: String) = Unit

        override fun complete(applicationId: String, envelopeId: String, sends: List<SendRequest>) {
            completed += envelopeId
        }
    }

    private companion object {
        const val NOW = 2_000L
        const val COUNTER = 7L
        const val RP_ID = "notisync.apps.extrawdw.net"
        const val ORIGIN = "android:apk-key-hash:unit-test"
        const val DISPLAY_NAME = "Android WebAuthn SSH"

        fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    }
}

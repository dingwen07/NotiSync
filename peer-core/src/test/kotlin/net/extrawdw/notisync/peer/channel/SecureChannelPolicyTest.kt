package net.extrawdw.notisync.peer.channel

import kotlinx.coroutines.runBlocking
import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.DataSync
import net.extrawdw.notisync.protocol.DataSyncKind
import net.extrawdw.notisync.protocol.DesktopProcessContext
import net.extrawdw.notisync.protocol.DesktopProcessContextSource
import net.extrawdw.notisync.protocol.Envelope
import net.extrawdw.notisync.protocol.LiveDeliveryDisposition
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.OpenPgpObjectKind
import net.extrawdw.notisync.protocol.OpenPgpSignAction
import net.extrawdw.notisync.protocol.OpenPgpSignSync
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.ScreenMirrorAction
import net.extrawdw.notisync.protocol.ScreenMirrorCodec
import net.extrawdw.notisync.protocol.ScreenMirrorConnectionCandidate
import net.extrawdw.notisync.protocol.ScreenMirrorSync
import net.extrawdw.notisync.protocol.SendResult
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.SshAgentLimits
import net.extrawdw.notisync.protocol.SshAgentSync
import net.extrawdw.notisync.protocol.SshAgentSyncKind
import net.extrawdw.notisync.protocol.SshConnectionDirection
import net.extrawdw.notisync.protocol.SshDestinationContext
import net.extrawdw.notisync.protocol.SshDestinationProvenance
import net.extrawdw.notisync.protocol.SshKeysRequest
import net.extrawdw.notisync.protocol.SshSignRequest
import net.extrawdw.notisync.protocol.SshSignatureAlgorithm
import net.extrawdw.notisync.protocol.Transport
import net.extrawdw.notisync.protocol.TransportType
import net.extrawdw.notisync.protocol.Urgency
import net.extrawdw.notisync.protocol.crypto.IdentitySigner
import net.extrawdw.notisync.protocol.crypto.OperationalSigner
import net.extrawdw.notisync.protocol.crypto.RecipientKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Test
import java.security.MessageDigest

class SecureChannelPolicyTest {
    @Test
    fun highSshStartupInventoryRequiresBodyBoundExactProviderSet() = runBlocking {
        val requester = ClientId("a".repeat(52))
        val first = ClientId("b".repeat(52))
        val second = ClientId("c".repeat(52))
        val request = SshKeysRequest(
            requestId = "1".repeat(32),
            requesterClientId = requester,
            requestedAt = 1_000,
            expiresAt = 61_000,
            startup = true,
            targetProviderClientIds = listOf(first, second),
            requesterInventoryNonce = ByteArray(32),
        )
        val body = ProtocolCodec.encodeToCbor(
            DataSync(
                DataSyncKind.SSH_AGENT,
                sshAgent = SshAgentSync(kind = SshAgentSyncKind.KEYS_REQUEST, keysRequest = request),
            ),
        )
        val exact = Recipients.OnlyCapableSet(setOf(first, second), SshAgentLimits.HIGH_PROVIDER_CAPABILITIES)

        assertEquals(0, channel().send(MessageType.DATA_SYNC, body, exact, Urgency.HIGH))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                channel().send(
                    MessageType.DATA_SYNC,
                    body,
                    Recipients.OnlyCapableSet(
                        setOf(first),
                        setOf(Capability.CAPABILITY_ROUTING_V1, Capability.SSH_KEY_PROVIDER_V1),
                    ),
                    Urgency.HIGH,
                )
            }
        }
        Unit
    }

    @Test
    fun highSshSignAllowsOnlyAnExactHighPartitionOfEligibleProviders() = runBlocking {
        val requester = ClientId("a".repeat(52))
        val highProvider = ClientId("b".repeat(52))
        val normalProvider = ClientId("c".repeat(52))
        val request = SshSignRequest(
            requestId = "2".repeat(32),
            requesterClientId = requester,
            requestedAt = 1_000,
            expiresAt = 121_000,
            publicKeyBlob = byteArrayOf(1),
            data = byteArrayOf(2),
            flags = 0,
            requestedSignatureAlgorithm = SshSignatureAlgorithm.SSH_ED25519,
            eligibleProviderClientIds = listOf(highProvider, normalProvider),
            authorizationGeneration = "3".repeat(32),
            authorizationEpoch = 0,
            processContext = DesktopProcessContext(DesktopProcessContextSource.UNAVAILABLE),
            destinationContext = SshDestinationContext(
                SshDestinationProvenance.UNKNOWN,
                SshConnectionDirection.UNKNOWN,
            ),
            connectionId = "4".repeat(32),
        )
        val body = ProtocolCodec.encodeToCbor(
            DataSync(
                DataSyncKind.SSH_AGENT,
                sshAgent = SshAgentSync(kind = SshAgentSyncKind.SIGN_REQUEST, signRequest = request),
            ),
        )
        val exact = Recipients.OnlyCapableSet(setOf(highProvider), SshAgentLimits.HIGH_PROVIDER_CAPABILITIES)

        assertEquals(0, channel().send(MessageType.DATA_SYNC, body, exact, Urgency.HIGH))
        assertEquals(
            0,
            channel().send(
                MessageType.DATA_SYNC,
                body,
                Recipients.OnlyCapableSet(
                    setOf(ClientId("d".repeat(52))),
                    SshAgentLimits.HIGH_PROVIDER_CAPABILITIES,
                ),
                Urgency.HIGH,
            ),
        )
        Unit
    }

    @Test
    fun highOpenPgpRequestRequiresExactCapabilityRoutedOwnMesh() = runBlocking {
        val requester = ClientId("a".repeat(52))
        val payload = (
            "tree ${"0".repeat(40)}\n" +
                "author A <a@example.com> 1 +0000\n" +
                "committer A <a@example.com> 1 +0000\n\nmessage\n"
            ).encodeToByteArray()
        val request = OpenPgpSignSync(
            action = OpenPgpSignAction.REQUEST,
            requestId = "0123456789abcdef0123456789abcdef",
            requesterClientId = requester,
            issuedAt = 1_000,
            expiresAt = 121_000,
            primaryKeyId = "89ABCDEF01234567",
            payloadSha256 = MessageDigest.getInstance("SHA-256").digest(payload),
            objectKind = OpenPgpObjectKind.GIT_COMMIT,
            payload = payload,
        )
        val body = ProtocolCodec.encodeToCbor(
            DataSync(DataSyncKind.OPENPGP_SIGN, openPgpSign = request)
        )
        val exact = Recipients.OwnMeshFiltered(
            requiredCapabilities = request.requiredSignerCapabilities(),
            requireCapabilityRoutingV1 = true,
        )

        assertEquals(0, channel().send(MessageType.DATA_SYNC, body, exact, Urgency.HIGH))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                channel().send(
                    MessageType.DATA_SYNC,
                    body,
                    exact.copy(requiredCapabilities = exact.requiredCapabilities - Capability.OPENPGP_SIGN_V1),
                    Urgency.HIGH,
                )
            }
        }

        val tagPayload = (
            "object ${"0".repeat(40)}\n" +
                "type commit\n" +
                "tag v1.0.0\n" +
                "tagger A <a@example.com> 1 +0000\n\nRelease v1.0.0\n"
            ).encodeToByteArray()
        val tagRequest = request.copy(
            requestId = "fedcba9876543210fedcba9876543210",
            payloadSha256 = MessageDigest.getInstance("SHA-256").digest(tagPayload),
            objectKind = OpenPgpObjectKind.GIT_TAG,
            payload = tagPayload,
        )
        val tagBody = ProtocolCodec.encodeToCbor(
            DataSync(DataSyncKind.OPENPGP_SIGN, openPgpSign = tagRequest)
        )
        val tagExact = exact.copy(requiredCapabilities = tagRequest.requiredSignerCapabilities())

        assertEquals(0, channel().send(MessageType.DATA_SYNC, tagBody, tagExact, Urgency.HIGH))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                channel().send(
                    MessageType.DATA_SYNC,
                    tagBody,
                    tagExact.copy(
                        requiredCapabilities = tagExact.requiredCapabilities -
                            Capability.OPENPGP_SIGN_GIT_TAG_V1,
                    ),
                    Urgency.HIGH,
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                channel().send(
                    MessageType.DATA_SYNC,
                    ProtocolCodec.encodeToCbor(
                        DataSync(
                            DataSyncKind.OPENPGP_SIGN,
                            openPgpSign = request.copy(
                                action = OpenPgpSignAction.REJECT,
                                payload = null,
                                rejectReason = net.extrawdw.notisync.protocol.OpenPgpRejectReason.USER_REJECTED,
                                actionAt = 2_000,
                            ),
                        )
                    ),
                    exact,
                    Urgency.HIGH,
                )
            }
        }
        Unit
    }

    @Test
    fun highScreenRequestRequiresBodyBoundExactCapableSource() = runBlocking {
        val requester = ClientId("a".repeat(52))
        val source = ClientId("b".repeat(52))
        val request = screenRequest(requester, source)
        val encoded = ProtocolCodec.encodeToCbor(
            DataSync(DataSyncKind.SCREEN_MIRRORING, screenMirror = request),
        )
        val validScope = Recipients.OnlyCapable(source, request.requiredSourceCapabilities())

        assertEquals(
            0,
            channel().send(MessageType.DATA_SYNC, encoded, validScope, Urgency.HIGH),
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                channel().send(
                    MessageType.DATA_SYNC,
                    encoded,
                    validScope.copy(
                        requiredCapabilities = validScope.requiredCapabilities -
                            Capability.SCREEN_MIRROR_CLIPBOARD_TEXT_V1,
                    ),
                    Urgency.HIGH,
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                channel().send(
                    MessageType.DATA_SYNC,
                    ProtocolCodec.encodeToCbor(
                        DataSync(
                            DataSyncKind.SCREEN_MIRRORING,
                            screenMirror = request.copy(action = ScreenMirrorAction.STATUS),
                        ),
                    ),
                    validScope,
                    Urgency.HIGH,
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                val forged = request.copy(requesterPeerId = ClientId("c".repeat(52)))
                channel().send(
                    MessageType.DATA_SYNC,
                    ProtocolCodec.encodeToCbor(
                        DataSync(DataSyncKind.SCREEN_MIRRORING, screenMirror = forged),
                    ),
                    Recipients.OnlyCapable(source, forged.requiredSourceCapabilities()),
                    Urgency.HIGH,
                )
            }
        }
        Unit
    }

    @Test
    fun highDataSyncRequiresFilteringDisplayWakeAudience() = runBlocking {
        val channel = channel()

        try {
            channel.send(MessageType.DATA_SYNC, byteArrayOf(1), Recipients.OwnMesh, Urgency.HIGH)
            fail("HIGH DATA_SYNC without the capability-filtered audience must be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }

        try {
            channel.send(
                MessageType.DATA_SYNC,
                byteArrayOf(1),
                Recipients.OwnMeshFiltered(
                    requiredCapabilities = setOf(
                        Capability.DISPLAY,
                        Capability.BACKGROUND_WAKE,
                        Capability.PUSH_FILTERING,
                    ),
                ),
                Urgency.HIGH,
            )
            fail("HIGH DATA_SYNC must not use the legacy capability-routing fallback")
        } catch (_: IllegalArgumentException) {
            // expected
        }

        val count = channel.send(
            MessageType.DATA_SYNC,
            byteArrayOf(1),
            Recipients.OwnMeshFiltered(
                requiredCapabilities = setOf(
                    Capability.DISPLAY,
                    Capability.BACKGROUND_WAKE,
                    Capability.PUSH_FILTERING,
                ),
                requireCapabilityRoutingV1 = true,
            ),
            Urgency.HIGH,
        )
        assertEquals(0, count)

        try {
            channel.sendAllStrict(
                MessageType.DATA_SYNC,
                listOf(OutboundItem("high-data-sync", byteArrayOf(1))),
                Recipients.OwnMesh,
                Urgency.HIGH,
            ) { }
            fail("strict HIGH DATA_SYNC must enforce the same audience policy")
        } catch (_: IllegalArgumentException) {
            // expected
        }

        val emptyStrictBatch = channel.sendAllStrict(
            MessageType.DATA_SYNC,
            emptyList(),
            Recipients.OwnMeshFiltered(
                requiredCapabilities = setOf(
                    Capability.DISPLAY,
                    Capability.BACKGROUND_WAKE,
                    Capability.PUSH_FILTERING,
                ),
                requireCapabilityRoutingV1 = true,
            ),
            Urgency.HIGH,
        ) { }
        assertEquals(0, emptyStrictBatch)
    }

    private fun channel(): SecureChannel {
        val id = ClientId("a".repeat(52))
        val identity = object : IdentitySigner {
            override val publicKeySpki = byteArrayOf(1)
            override val clientId = id
            override fun sign(data: ByteArray) = byteArrayOf(1)
        }
        val operational = object : OperationalSigner {
            override val operationalPublicKeySpki = byteArrayOf(1)
            override val clientId = id
            override val signerEpoch = 1
            override fun sign(data: ByteArray) = byteArrayOf(1)
        }
        val directory = object : PeerDirectory {
            override fun resolveSender(id: ClientId, signerEpoch: Int): SenderKey? = null
            override fun recipients(scope: Recipients): List<RecipientKey> = emptyList()
        }
        return SecureChannel(
            signer = identity,
            operationalSigner = { operational },
            myHpkePrivate = { null },
            transport = NoopTransport,
            directory = directory,
            log = ChannelLogger { },
        )
    }

    private fun screenRequest(requester: ClientId, source: ClientId) = ScreenMirrorSync(
        action = ScreenMirrorAction.REQUEST,
        sessionId = "session",
        requesterPeerId = requester,
        sourcePeerId = source,
        issuedAt = 1_000,
        expiresAt = 301_000,
        routingToken = ByteArray(16),
        masterPsk = ByteArray(32),
        codec = ScreenMirrorCodec.H264,
        requestControl = true,
        requestClipboard = true,
        maxDimension = 1_920,
        maxFps = 60,
        videoBitrateBps = 8_000_000,
        candidates = listOf(
            ScreenMirrorConnectionCandidate(
                ScreenMirrorConnectionCandidate.LAN_TCP,
                host = "192.0.2.10",
                port = 27_171,
            ),
        ),
    )

    private object NoopTransport : Transport {
        override val type = TransportType.WEBSOCKET
        override suspend fun publishKeyEpoch(keyEpoch: SignedBlob) = Unit
        override suspend fun publishRoutes(routes: List<SignedBlob>) = Unit
        override suspend fun fetchKeyEpoch(clientId: ClientId, epoch: Int?): SignedBlob? = null
        override suspend fun send(envelope: Envelope, urgency: Urgency) = SendResult(accepted = true)
        override suspend fun runLiveDelivery(onEnvelope: (Envelope) -> LiveDeliveryDisposition) = Unit
        override suspend fun uploadPrivateAsset(sourceClientId: ClientId, assetId: String, ciphertext: ByteArray) = false
        override suspend fun fetchPrivateAsset(sourceClientId: ClientId, assetId: String): ByteArray? = null
    }
}

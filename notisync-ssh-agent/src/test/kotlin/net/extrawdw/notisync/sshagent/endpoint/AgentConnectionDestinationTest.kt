package net.extrawdw.notisync.sshagent.endpoint

import java.security.KeyPairGenerator
import java.security.Signature
import net.extrawdw.notisync.protocol.SshDestinationProvenance
import net.extrawdw.notisync.ssh.core.OpenSshSessionBind
import net.extrawdw.notisync.ssh.core.SshSignatureCodec
import net.extrawdw.notisync.ssh.core.SshSignatureMethod
import net.extrawdw.notisync.ssh.core.SshPublicKeyCodec
import net.extrawdw.notisync.ssh.core.SshUserAuthParser
import net.extrawdw.notisync.ssh.core.SshWireWriter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConnectionDestinationTest {
    @Test
    fun `RSA SHA-2 user authentication correlates with verified session binding`() {
        val userKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val hostKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val userKeyBlob = SshPublicKeyCodec.encode(userKey.public)
        val hostKeyBlob = SshPublicKeyCodec.encode(hostKey.public)
        val sessionId = ByteArray(32) { it.toByte() }
        val hostSignature = Signature.getInstance(SshSignatureMethod.ED25519.jcaName).run {
            initSign(hostKey.private)
            update(sessionId)
            sign()
        }
        val state = AgentConnectionHandler.ConnectionDestinationState()

        assertTrue(
            state.bind(
                OpenSshSessionBind.encodeContents(
                    hostKeyBlob = hostKeyBlob,
                    sessionIdentifier = sessionId,
                    signatureBlob = SshSignatureCodec.encode(SshSignatureMethod.ED25519, hostSignature),
                    forwarded = false,
                ),
            ),
        )

        val signData = SshWireWriter()
            .writeString(sessionId)
            .writeByte(50)
            .writeUtf8("deploy")
            .writeUtf8("ssh-connection")
            .writeUtf8(SshUserAuthParser.PUBLIC_KEY_METHOD)
            .writeBoolean(true)
            .writeUtf8(SshSignatureMethod.RSA_SHA2_512.wireName)
            .writeString(userKeyBlob)
            .toByteArray()

        val destination = requireNotNull(state.resolve(userKeyBlob, signData))

        assertEquals(SshDestinationProvenance.VERIFIED_SESSION_BIND, destination.provenance)
        assertEquals("deploy", destination.username)
        assertArrayEquals(hostKeyBlob, destination.serverHostKeyBlob)
    }
}

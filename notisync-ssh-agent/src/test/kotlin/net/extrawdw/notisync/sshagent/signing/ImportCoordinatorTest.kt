package net.extrawdw.notisync.sshagent.signing

import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.SshImportConstraints
import net.extrawdw.notisync.protocol.SshImportRequest
import net.extrawdw.notisync.protocol.SshImportSourceType
import net.extrawdw.notisync.ssh.core.AgentAddConstraints
import net.extrawdw.notisync.sshagent.AgentConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Guards the optional ssh-add constraint representation used on the wire. */
class ImportCoordinatorTest {
    @Test
    fun defaultProviderIsReadAgainForEachImport() {
        val first = ClientId("a".repeat(52))
        val second = ClientId("b".repeat(52))
        val active = setOf(first, second)
        var config = AgentConfig(defaultProviderClientId = first.value)

        assertEquals(first, activeDefaultProvider({ config }, active))

        config = config.copy(defaultProviderClientId = second.value)
        assertEquals(second, activeDefaultProvider({ config }, active))

        config = config.copy(defaultProviderClientId = null)
        assertNull(activeDefaultProvider({ config }, active))
    }

    @Test
    fun allDefaultConstraintsCollapseToNull() {
        assertNull(importConstraints(AgentAddConstraints()))
        assertEquals(
            SshImportConstraints(3600L, false),
            importConstraints(AgentAddConstraints(lifetimeSeconds = 3600L)),
        )
        assertEquals(
            SshImportConstraints(null, true),
            importConstraints(AgentAddConstraints(confirm = true)),
        )
    }

    @Test
    fun constraintRepresentationsAreByteStableAcrossWireRoundTrip() {
        for (constraints in listOf(
            null,
            SshImportConstraints(null, false),
            SshImportConstraints(null, true),
            SshImportConstraints(3600L, false),
        )) {
            val request = importRequest(constraints)
            val encoded = ProtocolCodec.encodeToCbor(request)
            val decoded = ProtocolCodec.decodeFromCbor<SshImportRequest>(encoded)
            assertArrayEquals(
                "wire encoding must survive the round trip for constraints=$constraints",
                encoded,
                ProtocolCodec.encodeToCbor(decoded),
            )
            assertEquals(constraints, decoded.constraints)
        }
    }

    private fun importRequest(constraints: SshImportConstraints?): SshImportRequest {
        val requestedAt = System.currentTimeMillis()
        return SshImportRequest(
            requestId = "0123456789abcdef0123456789abcdef",
            requesterClientId = ClientId("n23khekpcl6tedbosshwh74sq4xi2hq6"),
            requestedAt = requestedAt,
            expiresAt = requestedAt + 5 * 60_000L,
            sourceType = SshImportSourceType.AGENT_IDENTITY,
            agentIdentity = ByteArray(64) { it.toByte() },
            constraints = constraints,
            suggestedName = "id_rsa_automation",
        )
    }
}

package net.extrawdw.apps.notisync.data.storage.importer.legacy

import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.RUN_TERMINAL_MAX_UTF8_BYTES
import net.extrawdw.notisync.protocol.RunBlockedReason
import net.extrawdw.notisync.protocol.RunLlmSummary
import net.extrawdw.notisync.protocol.RunPhase
import net.extrawdw.notisync.protocol.RunProgress
import net.extrawdw.notisync.protocol.RunPromptKind
import net.extrawdw.notisync.protocol.RunState
import net.extrawdw.notisync.protocol.RunTerminalSnapshot
import net.extrawdw.notisync.protocol.RunUpdateReason
import org.junit.Assert.assertTrue
import org.junit.Test

class RunPayloadBoundTest {
    @Test
    fun canonicalMaximumShapeFitsReviewedLegacyRowHeapCeiling() {
        val state = RunState(
            hostClientId = ClientId("a".repeat(32)),
            runId = "r".repeat(256),
            revision = Long.MAX_VALUE,
            phase = RunPhase.BLOCKED,
            updateReason = RunUpdateReason.BLOCKED,
            startedAt = 1,
            updatedAt = Long.MAX_VALUE,
            argv = List(4) { "a".repeat(16 * 1024) },
            cwd = "/" + "c".repeat(16 * 1024 - 1),
            usesPty = true,
            terminal = RunTerminalSnapshot(
                text = "t".repeat(RUN_TERMINAL_MAX_UTF8_BYTES),
                truncated = true,
                rawBytesSeen = Long.MAX_VALUE,
            ),
            interactionGeneration = Long.MAX_VALUE,
            blockedReason = RunBlockedReason.TERMINAL_INPUT,
            prompt = RunPromptKind.TEXT,
            progress = RunProgress(current = Long.MAX_VALUE - 1, total = Long.MAX_VALUE),
            llmSummary = RunLlmSummary(
                title = "l".repeat(160),
                text = "s".repeat(512),
                expandedText = "e".repeat(2 * 1024),
            ),
            responseToRequestId = "01234567-89ab-cdef-0123-456789abcdef",
        )

        val encoded = ProtocolCodec.encodeToCbor(state)

        assertTrue(
            "max-shape CBOR ${encoded.size} exceeds ${LegacyRunsV2Reader.MAX_LEGACY_RUN_PAYLOAD_BYTES}",
            encoded.size.toLong() <= LegacyRunsV2Reader.MAX_LEGACY_RUN_PAYLOAD_BYTES,
        )
    }
}

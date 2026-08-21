package net.extrawdw.apps.notisync.run

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.RunBlockedReason
import net.extrawdw.notisync.protocol.RunLlmSummary
import net.extrawdw.notisync.protocol.RunPhase
import net.extrawdw.notisync.protocol.RunPromptKind
import net.extrawdw.notisync.protocol.RunState
import net.extrawdw.notisync.protocol.RunTerminalSnapshot
import net.extrawdw.notisync.protocol.RunUpdateReason

class RunPresentationPolicyTest {
    @Test
    fun initialLifecycleStateIsSilentAndOffersTwoSignals() {
        val state = running()

        assertTrue(RunPresentationPolicy.active(state))
        assertTrue(RunPresentationPolicy.silent(state))
        assertEquals(
            listOf(RunShadeAction.INTERRUPT, RunShadeAction.TERMINATE),
            RunPresentationPolicy.shadeActions(state),
        )
    }

    @Test
    fun initialAndBackgroundRefreshKindsAreSilent() {
        assertTrue(RunPresentationPolicy.silent(running()))
        assertTrue(RunPresentationPolicy.silent(running().copy(updateReason = RunUpdateReason.PERIODIC)))
        assertTrue(RunPresentationPolicy.silent(running().copy(updateReason = RunUpdateReason.LLM_SUMMARY)))
        assertTrue(RunPresentationPolicy.silent(running().copy(updateReason = RunUpdateReason.REFRESH)))
        assertFalse(RunPresentationPolicy.silent(running().copy(updateReason = RunUpdateReason.RESUMED)))
    }

    @Test
    fun stickySummaryBodyIsHiddenOnDeterministicLifecycleSnapshots() {
        val summary = RunLlmSummary(
            title = "Sticky title",
            text = "Old model status",
            expandedText = "Old model explanation",
        )
        val completed = running().copy(
            phase = RunPhase.COMPLETED,
            updateReason = RunUpdateReason.COMPLETED,
            updatedAt = 2_000,
            endedAt = 2_000,
            exitCode = 1,
            llmSummary = summary,
        )

        assertNull(RunPresentationPolicy.summaryBody(completed))
        assertEquals(
            summary,
            RunPresentationPolicy.summaryBody(
                completed.copy(updateReason = RunUpdateReason.LLM_SUMMARY)
            ),
        )
    }

    @Test
    fun yesNoPromptUsesEntireThreeActionBudget() {
        val state = running().copy(
            phase = RunPhase.BLOCKED,
            updateReason = RunUpdateReason.BLOCKED,
            blockedReason = RunBlockedReason.TERMINAL_INPUT,
            prompt = RunPromptKind.YES_NO,
            interactionGeneration = 3,
        )

        assertFalse(RunPresentationPolicy.silent(state))
        assertTrue(RunPresentationPolicy.blockedNeedsInput(state))
        assertEquals(
            listOf(RunShadeAction.YES, RunShadeAction.NO, RunShadeAction.INTERRUPT),
            RunPresentationPolicy.shadeActions(state),
        )
    }

    @Test
    fun textPromptUsesRemoteInputAndTwoSignals() {
        val state = running().copy(
            phase = RunPhase.BLOCKED,
            updateReason = RunUpdateReason.BLOCKED,
            blockedReason = RunBlockedReason.TERMINAL_INPUT,
            prompt = RunPromptKind.TEXT,
            interactionGeneration = 4,
        )

        assertEquals(
            listOf(RunShadeAction.INPUT, RunShadeAction.INTERRUPT, RunShadeAction.TERMINATE),
            RunPresentationPolicy.shadeActions(state),
        )
    }

    @Test
    fun blockedWithoutPromptOffersSignalsOnly() {
        val state = running().copy(
            phase = RunPhase.BLOCKED,
            updateReason = RunUpdateReason.BLOCKED,
            blockedReason = RunBlockedReason.OUTPUT_AND_CPU_IDLE,
        )

        assertEquals(
            listOf(RunShadeAction.INTERRUPT, RunShadeAction.TERMINATE),
            RunPresentationPolicy.shadeActions(state),
        )
        assertFalse(RunPresentationPolicy.blockedNeedsInput(state))
    }

    @Test
    fun terminalRunHasNoShadeActions() {
        val state = running().copy(
            phase = RunPhase.COMPLETED,
            updateReason = RunUpdateReason.COMPLETED,
            updatedAt = 2_000,
            endedAt = 2_000,
            exitCode = 0,
        )

        assertFalse(RunPresentationPolicy.active(state))
        assertFalse(RunPresentationPolicy.silent(state))
        assertTrue(RunPresentationPolicy.shadeActions(state).isEmpty())
    }

    @Test
    fun runKeyRoundTripsWithoutDelimiterAmbiguity() {
        val key = RunKey("host", "run/with punctuation")
        assertEquals(key, RunKey.decode(key.encoded()))
        assertEquals(null, RunKey.decode("malformed"))
    }

    @Test
    fun notificationIdentityIsStableAndScopedToTheExactRun() {
        val key = RunKey("host", "run/with punctuation")
        val repeated = RunKey("host", "run/with punctuation")
        val other = RunKey("host", "another-run")

        assertEquals(runNotificationTag(key), runNotificationTag(repeated))
        assertEquals(runNotificationId(key), runNotificationId(repeated))
        assertNotEquals(runNotificationTag(key), runNotificationTag(other))
        assertEquals(runNotificationTag(key).hashCode(), runNotificationId(key))
    }

    @Test
    fun perPeerChannelIdsAreStableSafeAndDistinct() {
        val first = RunNotificationChannels.channelId(ClientId("peer/one?unsafe"))
        val repeated = RunNotificationChannels.channelId(ClientId("peer/one?unsafe"))
        val second = RunNotificationChannels.channelId(ClientId("peer-two"))

        assertEquals("NotiSync Run", RunNotificationChannels.GROUP_NAME)
        assertEquals(first, repeated)
        assertNotEquals(first, second)
        assertTrue(first.startsWith(RunNotificationChannels.CHANNEL_ID_PREFIX))
        assertTrue(first.all { it.isLowerCase() || it.isDigit() || it == '_' })
        assertFalse("peer/one?unsafe" in first)
    }

    @Test
    fun peerChannelNamePrefersTrimmedDeviceNameAndFallsBackToShortId() {
        val peer = ClientId("1234567890abcdef")
        assertEquals("Build Mac", RunNotificationChannels.channelName("  Build Mac  ", peer))
        assertEquals(peer.shortForm(), RunNotificationChannels.channelName("  ", peer))
    }

    private fun running() = RunState(
        hostClientId = ClientId("host"),
        runId = "run-1",
        revision = 1,
        phase = RunPhase.RUNNING,
        updateReason = RunUpdateReason.INITIAL,
        startedAt = 1_000,
        updatedAt = 1_000,
        argv = listOf("/usr/bin/make", "test"),
        cwd = "/work",
        usesPty = true,
        terminal = RunTerminalSnapshot("building", truncated = false, rawBytesSeen = 8),
    )
}

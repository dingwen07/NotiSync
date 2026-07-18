package net.extrawdw.apps.notisync.run

import net.extrawdw.notisync.protocol.RunBlockedReason
import net.extrawdw.notisync.protocol.RunPhase
import net.extrawdw.notisync.protocol.RunPromptKind
import net.extrawdw.notisync.protocol.RunState
import net.extrawdw.notisync.protocol.RunUpdateReason

internal enum class RunShadeAction { YES, NO, INPUT, INTERRUPT, TERMINATE }

/** Pure presentation rules shared by the Android Run notification tests and renderer. */
internal object RunPresentationPolicy {
    fun active(state: RunState): Boolean =
        state.phase == RunPhase.RUNNING || state.phase == RunPhase.BLOCKED

    fun silent(state: RunState): Boolean = state.updateReason in SILENT_REASONS

    fun blockedNeedsInput(state: RunState): Boolean =
        state.phase == RunPhase.BLOCKED && state.blockedReason == RunBlockedReason.TERMINAL_INPUT

    fun shadeActions(state: RunState): List<RunShadeAction> = when {
        !active(state) -> emptyList()
        state.phase != RunPhase.BLOCKED ->
            listOf(RunShadeAction.INTERRUPT, RunShadeAction.TERMINATE)
        state.prompt == RunPromptKind.YES_NO ->
            listOf(RunShadeAction.YES, RunShadeAction.NO, RunShadeAction.INTERRUPT)
        state.prompt == RunPromptKind.TEXT ->
            listOf(RunShadeAction.INPUT, RunShadeAction.INTERRUPT, RunShadeAction.TERMINATE)
        else -> listOf(RunShadeAction.INTERRUPT, RunShadeAction.TERMINATE)
    }

    private val SILENT_REASONS = setOf(
        RunUpdateReason.PERIODIC,
        RunUpdateReason.LLM_SUMMARY,
        RunUpdateReason.REFRESH,
    )
}

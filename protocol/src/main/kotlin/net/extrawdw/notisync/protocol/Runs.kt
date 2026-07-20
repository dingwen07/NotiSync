package net.extrawdw.notisync.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.CborLabel

/** Maximum UTF-8 size of the authoritative, sanitized terminal tail carried in a [RunState]. */
const val RUN_TERMINAL_MAX_UTF8_BYTES: Int = 64 * 1024

private const val RUN_ID_MAX_UTF8_BYTES = 256
private const val RUN_PATH_MAX_UTF8_BYTES = 16 * 1024
private const val RUN_ARG_MAX_UTF8_BYTES = 16 * 1024
private const val RUN_ARGV_MAX_UTF8_BYTES = 64 * 1024
private const val RUN_INPUT_MAX_UTF8_BYTES = 64 * 1024
private const val RUN_FAILURE_MAX_UTF8_BYTES = 2 * 1024
private const val RUN_RESULT_MESSAGE_MAX_UTF8_BYTES = 2 * 1024
private const val RUN_LLM_TITLE_MAX_UTF8_BYTES = 160
private const val RUN_LLM_TEXT_MAX_UTF8_BYTES = 512
private const val RUN_LLM_EXPANDED_TEXT_MAX_UTF8_BYTES = 2 * 1024
private const val RUN_ENV_VALUE_MAX_UTF8_BYTES = 64 * 1024

/** Selects the single populated body in [RunSync]. Append-only. */
@Serializable
enum class RunSyncKind { STATE, CONTROL, CONTROL_RESULT, COMMAND_REQUEST }

/** Stable lifecycle state. Presentation-only refinements do not change this value. Append-only. */
@Serializable
enum class RunPhase { RUNNING, BLOCKED, COMPLETED, FAILED_TO_START }

/** Why a complete [RunState] snapshot was published. Append-only. */
@Serializable
enum class RunUpdateReason {
    INITIAL,
    PERIODIC,
    BLOCKED,
    RESUMED,
    COMPLETED,
    FAILED,
    LLM_SUMMARY,
    REFRESH,
}

/** Why an active Run appears to need attention. Append-only. */
@Serializable
enum class RunBlockedReason { TERMINAL_INPUT, OUTPUT_AND_CPU_IDLE }

/** The contextual input UI a blocked Run can accept. Append-only. */
@Serializable
enum class RunPromptKind { YES_NO, TEXT }

/**
 * A bounded, terminal-like projection of the Run output. Producers apply CR/backspace/tab behavior
 * and strip ANSI CSI/OSC and unsafe control characters before constructing this value. Raw output
 * remains local to the host and is never reconstructed from this lossy snapshot.
 */
@Serializable
data class RunTerminalSnapshot(
    @CborLabel(0) val text: String,
    @CborLabel(1) val truncated: Boolean,
    @CborLabel(2) val rawBytesSeen: Long,
) {
    init {
        require(text.utf8Size() <= RUN_TERMINAL_MAX_UTF8_BYTES) {
            "Run terminal text exceeds $RUN_TERMINAL_MAX_UTF8_BYTES UTF-8 bytes"
        }
        require(text.isSanitizedRunTerminalText()) {
            "Run terminal text must be sanitized"
        }
        require(rawBytesSeen >= 0) { "rawBytesSeen must be non-negative" }
    }
}

/** Normalized Run progress. Determinate progress always carries a bounded current/total pair. */
@Serializable
data class RunProgress(
    @CborLabel(0) val current: Long? = null,
    @CborLabel(1) val total: Long? = null,
    @CborLabel(2) val indeterminate: Boolean = false,
) {
    init {
        if (indeterminate) {
            require(current == null && total == null) {
                "indeterminate progress must not carry current or total"
            }
        } else {
            require(current != null && total != null && total > 0 && current in 0..total) {
                "determinate progress requires current in 0..total and a positive total"
            }
        }
    }
}

/** Optional model-generated presentation, kept separate from deterministic state and terminal output. */
@Serializable
data class RunLlmSummary(
    @CborLabel(0) val title: String,
    @CborLabel(1) val text: String,
    @CborLabel(2) val expandedText: String? = null,
) {
    init {
        require(title.isNotBlank() && title.utf8Size() <= RUN_LLM_TITLE_MAX_UTF8_BYTES) {
            "LLM summary title must contain 1..$RUN_LLM_TITLE_MAX_UTF8_BYTES UTF-8 bytes"
        }
        require(title.isSanitizedRunSingleLine()) { "LLM summary title must be sanitized and single-line" }
        require(text.isNotBlank() && text.utf8Size() <= RUN_LLM_TEXT_MAX_UTF8_BYTES) {
            "LLM summary text must contain 1..$RUN_LLM_TEXT_MAX_UTF8_BYTES UTF-8 bytes"
        }
        require(text.isSanitizedRunMultiline()) { "LLM summary text must be sanitized" }
        require(
            expandedText == null || (
                expandedText.isNotBlank() &&
                    expandedText.utf8Size() <= RUN_LLM_EXPANDED_TEXT_MAX_UTF8_BYTES &&
                    expandedText.isSanitizedRunMultiline()
                ),
        ) {
            "LLM expanded text exceeds $RUN_LLM_EXPANDED_TEXT_MAX_UTF8_BYTES UTF-8 bytes"
        }
    }
}

/**
 * A complete, authoritative Run snapshot. Consumers apply it last-writer-wins on [revision] for
 * ([hostClientId], [runId]); no earlier snapshot is required to render or control the Run.
 */
@Serializable
data class RunState(
    @CborLabel(0) val hostClientId: ClientId,
    @CborLabel(1) val runId: String,
    @CborLabel(2) val revision: Long,
    @CborLabel(3) val phase: RunPhase,
    @CborLabel(4) val updateReason: RunUpdateReason,
    @CborLabel(5) val startedAt: Long,
    @CborLabel(6) val updatedAt: Long,
    @CborLabel(7) val argv: List<String>,
    @CborLabel(8) val cwd: String,
    @CborLabel(9) val usesPty: Boolean,
    @CborLabel(10) val terminal: RunTerminalSnapshot,
    /** Changes only when the contextual prompt/input contract changes; zero means no context yet. */
    @CborLabel(11) val interactionGeneration: Long = 0,
    @CborLabel(12) val endedAt: Long? = null,
    /** Monotonic elapsed time from the launch attempt until child exit; absent while active and on legacy states. */
    @CborLabel(13) val durationMs: Long? = null,
    @CborLabel(14) val blockedReason: RunBlockedReason? = null,
    @CborLabel(15) val prompt: RunPromptKind? = null,
    @CborLabel(16) val progress: RunProgress? = null,
    @CborLabel(17) val exitCode: Int? = null,
    @CborLabel(18) val failureMessage: String? = null,
    @CborLabel(19) val llmSummary: RunLlmSummary? = null,
    /** [RunControl.requestId] when this snapshot is the immediate answer to a refresh. */
    @CborLabel(20) val responseToRequestId: String? = null,
) {
    init {
        require(hostClientId.value.isNotBlank()) { "hostClientId must not be blank" }
        validateRunId(runId)
        require(revision > 0) { "revision must be positive" }
        require(startedAt >= 0 && updatedAt >= startedAt) {
            "Run timestamps must be non-negative and ordered"
        }
        validateArgv(argv)
        validateAbsolutePath(cwd)
        require(interactionGeneration >= 0) { "interactionGeneration must be non-negative" }
        require(durationMs == null || durationMs >= 0) { "durationMs must be non-negative" }
        require(responseToRequestId == null || responseToRequestId.isUuid()) {
            "responseToRequestId must be a UUID"
        }
        require(failureMessage == null || failureMessage.utf8Size() <= RUN_FAILURE_MAX_UTF8_BYTES) {
            "failureMessage exceeds $RUN_FAILURE_MAX_UTF8_BYTES UTF-8 bytes"
        }

        when (phase) {
            RunPhase.RUNNING -> require(
                endedAt == null && blockedReason == null && prompt == null &&
                    durationMs == null && exitCode == null && failureMessage == null,
            ) { "RUNNING state cannot carry blocked or terminal outcome fields" }
            RunPhase.BLOCKED -> {
                require(
                    endedAt == null && durationMs == null && blockedReason != null &&
                        exitCode == null && failureMessage == null,
                ) {
                    "BLOCKED state requires a reason and cannot carry terminal outcome fields"
                }
                require(prompt == null || blockedReason == RunBlockedReason.TERMINAL_INPUT) {
                    "a prompt requires TERMINAL_INPUT"
                }
            }
            RunPhase.COMPLETED -> require(
                endedAt != null && endedAt >= startedAt && blockedReason == null && prompt == null &&
                    progress == null && exitCode != null && failureMessage == null,
            ) { "COMPLETED state requires endedAt/exitCode and no active or failure fields" }
            RunPhase.FAILED_TO_START -> require(
                endedAt != null && endedAt >= startedAt && blockedReason == null && prompt == null &&
                    durationMs == null && progress == null && exitCode == null && !failureMessage.isNullOrBlank(),
            ) { "FAILED_TO_START requires endedAt/failureMessage and no active outcome fields" }
        }
        require(endedAt == null || updatedAt >= endedAt) { "updatedAt must not precede endedAt" }

        when (updateReason) {
            RunUpdateReason.INITIAL, RunUpdateReason.RESUMED ->
                require(phase == RunPhase.RUNNING) { "$updateReason requires RUNNING" }
            // PERIODIC also serves as the low-frequency liveness snapshot for a blocked Run. It must never
            // revive a terminal phase, but preserving BLOCKED lets consumers keep their local activity lease
            // alive without manufacturing a RESUMED transition.
            RunUpdateReason.PERIODIC -> require(
                phase == RunPhase.RUNNING || phase == RunPhase.BLOCKED,
            ) { "PERIODIC requires an active phase" }
            RunUpdateReason.BLOCKED ->
                require(phase == RunPhase.BLOCKED) { "BLOCKED update requires BLOCKED phase" }
            RunUpdateReason.COMPLETED ->
                require(phase == RunPhase.COMPLETED) { "COMPLETED update requires COMPLETED phase" }
            RunUpdateReason.FAILED ->
                require(phase == RunPhase.FAILED_TO_START) { "FAILED update requires FAILED_TO_START phase" }
            RunUpdateReason.LLM_SUMMARY, RunUpdateReason.REFRESH -> Unit
        }
    }
}

/** One command/control request sent from a Run consumer to its authenticated host. Append-only. */
@Serializable
enum class RunControlKind { REFRESH, WRITE_INPUT, SIGNAL }

@Serializable
data class RunControl(
    @CborLabel(0) val requestId: String,
    @CborLabel(1) val hostClientId: ClientId,
    @CborLabel(2) val runId: String,
    @CborLabel(3) val kind: RunControlKind,
    @CborLabel(4) val requestedAt: Long,
    @CborLabel(5) val interactionGeneration: Long? = null,
    @CborLabel(6) val inputText: String? = null,
    /** Arbitrary signal name or number, not an enum; examples include INT, TERM, KILL, and RTMIN+1. */
    @CborLabel(7) val signal: String? = null,
) {
    init {
        require(requestId.isUuid()) { "requestId must be a UUID" }
        require(hostClientId.value.isNotBlank()) { "hostClientId must not be blank" }
        validateRunId(runId)
        require(requestedAt >= 0) { "requestedAt must be non-negative" }
        require(interactionGeneration == null || interactionGeneration >= 0) {
            "interactionGeneration must be non-negative"
        }
        require(inputText == null || inputText.utf8Size() <= RUN_INPUT_MAX_UTF8_BYTES) {
            "inputText exceeds $RUN_INPUT_MAX_UTF8_BYTES UTF-8 bytes"
        }

        when (kind) {
            RunControlKind.REFRESH -> require(
                interactionGeneration == null && inputText == null && signal == null,
            ) { "REFRESH cannot carry input or signal fields" }
            RunControlKind.WRITE_INPUT -> require(
                interactionGeneration != null && inputText != null && signal == null,
            ) { "WRITE_INPUT requires interactionGeneration/inputText and cannot carry signal" }
            RunControlKind.SIGNAL -> require(
                interactionGeneration == null && inputText == null && signal.isValidSignal(),
            ) { "SIGNAL requires one safe, non-empty signal name or number" }
        }
    }
}

/** The host's durable disposition of one [RunControl]. Append-only. */
@Serializable
enum class RunControlResultStatus { APPLIED, REJECTED, NOT_ACTIVE, STALE, FAILED }

@Serializable
data class RunControlResult(
    @CborLabel(0) val requestId: String,
    @CborLabel(1) val runId: String,
    @CborLabel(2) val status: RunControlResultStatus,
    @CborLabel(3) val respondedAt: Long,
    @CborLabel(4) val message: String? = null,
) {
    init {
        require(requestId.isUuid()) { "requestId must be a UUID" }
        validateRunId(runId)
        require(respondedAt >= 0) { "respondedAt must be non-negative" }
        require(message == null || message.utf8Size() <= RUN_RESULT_MESSAGE_MAX_UTF8_BYTES) {
            "control result message exceeds $RUN_RESULT_MESSAGE_MAX_UTF8_BYTES UTF-8 bytes"
        }
    }
}

/**
 * Ordered environment mutation in a schema-only [RunCommandRequest]. A future executor starts with
 * its inherited environment and applies changes sequentially: SET replaces, UNSET removes, APPEND
 * joins old + separator + value, and PREPEND joins value + separator + old. The separator is omitted
 * when either side is empty. Values and separators are literal: there is no shell or variable expansion.
 * Append-only.
 */
@Serializable
enum class RunEnvironmentOperation { SET, UNSET, APPEND, PREPEND }

@Serializable
data class RunEnvironmentChange(
    @CborLabel(0) val name: String,
    @CborLabel(1) val operation: RunEnvironmentOperation,
    @CborLabel(2) val value: String? = null,
    /** Literal join text for APPEND/PREPEND; omitted when either side is empty by a future executor. */
    @CborLabel(3) val separator: String = "",
) {
    init {
        require(name.isNotBlank() && name.utf8Size() <= 1024 && '=' !in name && '\u0000' !in name) {
            "environment name is invalid"
        }
        require(value == null || (value.utf8Size() <= RUN_ENV_VALUE_MAX_UTF8_BYTES && '\u0000' !in value)) {
            "environment value is invalid or too large"
        }
        require(separator.utf8Size() <= 1024 && '\u0000' !in separator) {
            "environment separator is invalid or too large"
        }
        when (operation) {
            RunEnvironmentOperation.UNSET -> require(value == null && separator.isEmpty()) {
                "UNSET cannot carry value or separator"
            }
            RunEnvironmentOperation.SET -> require(value != null && separator.isEmpty()) {
                "SET requires value and cannot carry separator"
            }
            RunEnvironmentOperation.APPEND, RunEnvironmentOperation.PREPEND -> require(value != null) {
                "$operation requires value"
            }
        }
    }
}

/**
 * Schema reservation for future remote command launching. Current clients must not construct, send,
 * route, execute, or expose UI for this body; receivers safely ignore it.
 */
@Serializable
data class RunCommandRequest(
    @CborLabel(0) val requestId: String,
    @CborLabel(1) val hostClientId: ClientId,
    @CborLabel(2) val argv: List<String>,
    @CborLabel(3) val cwd: String,
    @CborLabel(4) val environmentChanges: List<RunEnvironmentChange>,
    @CborLabel(5) val requestedAt: Long,
    @CborLabel(6) val expiresAt: Long,
) {
    init {
        require(requestId.isUuid()) { "requestId must be a UUID" }
        require(hostClientId.value.isNotBlank()) { "hostClientId must not be blank" }
        validateArgv(argv)
        validateAbsolutePath(cwd)
        require(environmentChanges.size <= 512) { "too many environment changes" }
        require(requestedAt >= 0 && expiresAt >= requestedAt) {
            "command request timestamps must be non-negative and ordered"
        }
    }
}

/**
 * A non-polymorphic tagged union carried by [DataSync.run]. Exactly one body is populated, avoiding
 * fragile try-decode behavior across Kotlin/JVM and Kotlin/Native.
 */
@Serializable
data class RunSync(
    @CborLabel(0) val kind: RunSyncKind,
    @CborLabel(1) val state: RunState? = null,
    @CborLabel(2) val control: RunControl? = null,
    @CborLabel(3) val controlResult: RunControlResult? = null,
    @CborLabel(4) val commandRequest: RunCommandRequest? = null,
) {
    init {
        val populated = listOfNotNull(state, control, controlResult, commandRequest).size
        require(populated == 1) { "RunSync must carry exactly one body" }
        require(
            when (kind) {
                RunSyncKind.STATE -> state != null
                RunSyncKind.CONTROL -> control != null
                RunSyncKind.CONTROL_RESULT -> controlResult != null
                RunSyncKind.COMMAND_REQUEST -> commandRequest != null
            },
        ) { "RunSync body does not match kind $kind" }
    }
}

private fun String.utf8Size(): Int = encodeToByteArray().size

/** Common-code validation matching the host sanitizer, including surrogate and bidi safety. */
private fun String.isSanitizedRunTerminalText(): Boolean {
    // Terminal snapshots preserve printable Unicode exactly. U+2028/U+2029 are valid child
    // output; unlike model-authored presentation copy, they do not need canonical paragraph
    // separators.
    return isSanitizedRunText(allowNewline = true, requireCanonicalNewline = false)
}

private fun String.isSanitizedRunSingleLine(): Boolean =
    isSanitizedRunText(allowNewline = false, requireCanonicalNewline = true)

private fun String.isSanitizedRunMultiline(): Boolean =
    isSanitizedRunText(allowNewline = true, requireCanonicalNewline = true)

private fun String.isSanitizedRunText(allowNewline: Boolean, requireCanonicalNewline: Boolean): Boolean {
    var index = 0
    while (index < length) {
        val first = this[index].code
        val codePoint: Int
        if (first in 0xd800..0xdbff) {
            if (index + 1 >= length) return false
            val second = this[index + 1].code
            if (second !in 0xdc00..0xdfff) return false
            codePoint = 0x10000 + ((first - 0xd800) shl 10) + (second - 0xdc00)
            index += 2
        } else {
            if (first in 0xdc00..0xdfff) return false
            codePoint = first
            index++
        }
        if (codePoint == '\n'.code) {
            if (allowNewline) continue else return false
        }
        if (requireCanonicalNewline && (codePoint == 0x2028 || codePoint == 0x2029)) return false
        if (codePoint < 0x20 || codePoint == 0x7f || codePoint in 0x80..0x9f) return false
        if (
            codePoint == 0x061c || codePoint in 0x200e..0x200f ||
            codePoint in 0x202a..0x202e || codePoint in 0x2066..0x2069 ||
            codePoint in 0x206a..0x206f
        ) return false
    }
    return true
}

private fun validateRunId(runId: String) {
    require(runId.isNotBlank() && runId.utf8Size() <= RUN_ID_MAX_UTF8_BYTES && '\u0000' !in runId) {
        "runId must contain 1..$RUN_ID_MAX_UTF8_BYTES UTF-8 bytes"
    }
}

private fun validateArgv(argv: List<String>) {
    require(argv.isNotEmpty() && argv.size <= 256) { "argv must contain 1..256 entries" }
    require(argv.first().isNotEmpty()) { "argv[0] must identify a command" }
    require(argv.all { '\u0000' !in it && it.utf8Size() <= RUN_ARG_MAX_UTF8_BYTES }) {
        "argv entries must be NUL-free and bounded"
    }
    require(argv.sumOf { it.utf8Size() } <= RUN_ARGV_MAX_UTF8_BYTES) {
        "argv exceeds $RUN_ARGV_MAX_UTF8_BYTES UTF-8 bytes"
    }
}

private fun validateAbsolutePath(path: String) {
    val windowsDrive = path.length >= 3 && path[0].isLetter() && path[1] == ':' &&
        (path[2] == '\\' || path[2] == '/')
    val absolute = path.startsWith('/') || path.startsWith("\\\\") || windowsDrive
    require(absolute && '\u0000' !in path && path.utf8Size() <= RUN_PATH_MAX_UTF8_BYTES) {
        "cwd must be an absolute, NUL-free path no larger than $RUN_PATH_MAX_UTF8_BYTES UTF-8 bytes"
    }
}

private fun String.isUuid(): Boolean {
    if (length != 36 || this[8] != '-' || this[13] != '-' || this[18] != '-' || this[23] != '-') return false
    return indices.all { index ->
        index in setOf(8, 13, 18, 23) || this[index].digitToIntOrNull(16) != null
    }
}

private fun String?.isValidSignal(): Boolean {
    val value = this ?: return false
    if (value.isEmpty() || value.length > 64) return false
    if (value.all(Char::isDigit)) return true
    return value.first().isLetter() && value.all { it.isLetterOrDigit() || it == '_' || it == '+' || it == '-' }
}

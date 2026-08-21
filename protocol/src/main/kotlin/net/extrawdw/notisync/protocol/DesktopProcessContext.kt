package net.extrawdw.notisync.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.CborLabel

/** Stable bounds for requester-reported desktop process context shared across protocol features. */
object DesktopProcessContextLimits {
    const val MAX_LINEAGE = 16
    const val MAX_DISPLAY_NAME_UTF8_BYTES = 256
    const val MAX_EXECUTABLE_PATH_UTF8_BYTES = 1_024
}

/** How the requesting desktop learned the process at the leaf of [DesktopProcessContext.processLineage]. */
@Serializable
enum class DesktopProcessContextSource {
    /** Kernel credentials attached to an accepted local socket. */
    PEER_CREDENTIALS,

    /** A client PID returned by the operating system for an accepted named-pipe connection. */
    NAMED_PIPE_CLIENT_PID,

    /** The component inspected its own process lineage. */
    CURRENT_PROCESS,

    /** A platform bridge supplied the process lineage when direct inspection was not possible. */
    BRIDGE_REPORTED,

    UNAVAILABLE,
}

/**
 * A requester-reported snapshot of one desktop process. Other peers may render this as review context,
 * but must not treat it as independently verified identity or as an authorization boundary.
 */
@Serializable
data class DesktopProcessIdentity(
    @CborLabel(0) val pid: Long,
    @CborLabel(1) val executablePath: String,
    @CborLabel(2) val displayName: String? = null,
) {
    fun validationError(): String? = when {
        pid <= 0 -> "process pid must be positive"
        !executablePath.isBoundedDesktopExecutablePath() -> "process executable path is invalid"
        displayName != null && !displayName.isBoundedDesktopProcessText(
            DesktopProcessContextLimits.MAX_DISPLAY_NAME_UTF8_BYTES,
        ) -> "process display name is invalid"
        else -> null
    }
}

/**
 * A leaf-first, contiguous process lineage reported by a requesting desktop for display on other peers.
 * The source describes the requester's local provenance; it does not make the context remotely trusted.
 */
@Serializable
data class DesktopProcessContext(
    @CborLabel(0) val source: DesktopProcessContextSource,
    @CborLabel(1) val processLineage: List<DesktopProcessIdentity> = emptyList(),
    /** Linux kernel boot ID for the process-lineage snapshot. */
    @CborLabel(2) val bootId: String? = null,
) {
    val leaf: DesktopProcessIdentity? get() = processLineage.firstOrNull()

    fun validationError(): String? = when {
        source == DesktopProcessContextSource.UNAVAILABLE && processLineage.isNotEmpty() ->
            "unavailable process context must not carry identities"
        source == DesktopProcessContextSource.UNAVAILABLE && bootId != null ->
            "unavailable process context must not carry a boot ID"
        source != DesktopProcessContextSource.UNAVAILABLE && processLineage.isEmpty() ->
            "available process context requires a process lineage"
        bootId != null && !DESKTOP_BOOT_ID.matches(bootId) -> "process boot ID is invalid"
        processLineage.size > DesktopProcessContextLimits.MAX_LINEAGE -> "process lineage is too long"
        processLineage.any { it.validationError() != null } -> "invalid process identity"
        processLineage.map(DesktopProcessIdentity::pid).distinct().size != processLineage.size ->
            "duplicate process identity"
        else -> null
    }
}

private val DESKTOP_BOOT_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

private fun String.isBoundedDesktopProcessText(maxUtf8Bytes: Int): Boolean =
    encodeToByteArray().size <= maxUtf8Bytes && none(Char::isISOControl)

private fun String.isBoundedDesktopExecutablePath(): Boolean {
    if (
        isBlank() ||
        encodeToByteArray().size > DesktopProcessContextLimits.MAX_EXECUTABLE_PATH_UTF8_BYTES ||
        any(Char::isISOControl)
    ) {
        return false
    }
    val windowsDrive = length >= 3 && this[0].isLetter() && this[1] == ':' && (this[2] == '\\' || this[2] == '/')
    return startsWith('/') || startsWith("\\\\") || windowsDrive
}

package net.extrawdw.notisync.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.CborLabel

/** Stable bounds for requester-reported desktop process context shared across protocol features. */
object DesktopProcessContextLimits {
    const val MAX_LINEAGE = 16
    const val MAX_DISPLAY_NAME_UTF8_BYTES = 256
    const val MAX_EXECUTABLE_PATH_UTF8_BYTES = 1_024
    const val MAX_USERNAME_UTF8_BYTES = 256
    const val MAX_SID_LENGTH = 192
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

@Serializable
enum class DesktopWindowsElevationType { DEFAULT, FULL, LIMITED }

/** Descriptive fields from a Windows process's primary token; no token handles or credentials. */
@Serializable
data class DesktopWindowsTokenInfo(
    @CborLabel(0) val elevated: Boolean? = null,
    @CborLabel(1) val elevationType: DesktopWindowsElevationType? = null,
    /** Mandatory integrity SID's final RID, retained numerically for uncommon integrity levels. */
    @CborLabel(2) val integrityLevel: Long? = null,
    @CborLabel(3) val appContainer: Boolean? = null,
) {
    fun validationError(): String? = when {
        elevated == null && elevationType == null && integrityLevel == null && appContainer == null ->
            "empty Windows token context must be omitted"
        integrityLevel != null && integrityLevel !in 0..0xffff_ffffL -> "invalid Windows integrity level"
        else -> null
    }
}

/**
 * A requester-reported snapshot of one desktop process. Other peers may render this as review context,
 * but must not treat it as independently verified identity or as an authorization boundary.
 */
@Serializable
data class DesktopProcessIdentity(
    @CborLabel(0) val pid: Long,
    /** Best-effort executable path. Some platforms restrict this for processes owned by another user. */
    @CborLabel(1) val executablePath: String? = null,
    @CborLabel(2) val displayName: String? = null,
    /** Best-effort account name for this process's effective user (token user on Windows). */
    @CborLabel(3) val username: String? = null,
    /** POSIX effective user ID, local to the requesting computer's user namespace. */
    @CborLabel(4) val uid: Long? = null,
    /** Windows token user SID. Mutually exclusive with [uid]. */
    @CborLabel(5) val sid: String? = null,
    @CborLabel(6) val windowsTokenInfo: DesktopWindowsTokenInfo? = null,
) {
    fun validationError(): String? = when {
        pid <= 0 -> "process pid must be positive"
        executablePath != null && !executablePath.isBoundedDesktopExecutablePath() ->
            "process executable path is invalid"
        displayName != null && !displayName.isBoundedDesktopProcessText(
            DesktopProcessContextLimits.MAX_DISPLAY_NAME_UTF8_BYTES,
        ) -> "process display name is invalid"
        username != null && (username.isBlank() || !username.isBoundedDesktopProcessText(
            DesktopProcessContextLimits.MAX_USERNAME_UTF8_BYTES,
        )) -> "process username is invalid"
        uid != null && uid !in 0..0xffff_ffffL -> "process uid is invalid"
        sid != null && (sid.length > DesktopProcessContextLimits.MAX_SID_LENGTH || !DESKTOP_SID.matches(sid)) ->
            "process sid is invalid"
        uid != null && sid != null -> "process must not carry both uid and sid"
        uid != null && windowsTokenInfo != null -> "POSIX process must not carry Windows token context"
        windowsTokenInfo?.validationError() != null -> windowsTokenInfo.validationError()
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
private val DESKTOP_SID = Regex("S-1-[0-9]+(?:-[0-9]+){1,15}")

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

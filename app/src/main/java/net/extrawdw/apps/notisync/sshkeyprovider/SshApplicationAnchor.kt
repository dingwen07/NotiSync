package net.extrawdw.apps.notisync.sshkeyprovider

import java.util.Locale
import net.extrawdw.notisync.protocol.DesktopProcessContext
import net.extrawdw.notisync.protocol.DesktopProcessIdentity

/** Internal selection policy. The numeric priorities are tunable heuristics, not a compatibility contract. */
internal enum class SshApplicationProcessRole(
    val selectionPriority: Int?,
    val traversal: SshLineageTraversal,
) {
    AI_AGENT(700, SshLineageTraversal.CANDIDATE),
    USER_APPLICATION(600, SshLineageTraversal.CANDIDATE),
    OPERATION_CLIENT(500, SshLineageTraversal.CANDIDATE),
    TERMINAL_HOST(400, SshLineageTraversal.CANDIDATE),
    UNKNOWN(300, SshLineageTraversal.CANDIDATE),
    INTERACTIVE_SHELL(200, SshLineageTraversal.CANDIDATE),
    ENVIRONMENT_HOST(100, SshLineageTraversal.CANDIDATE),
    TRANSPARENT_HELPER(null, SshLineageTraversal.SKIP),
    SESSION_BOUNDARY(null, SshLineageTraversal.STOP),
}

internal enum class SshLineageTraversal { CANDIDATE, SKIP, STOP }

/** Stable, requester-reported application identity used only as a narrowing policy predicate. */
internal class SshApplicationIdentity(val executablePath: String) {
    private val comparisonPath = executablePath.applicationPathComparisonKey()

    init {
        require(executablePath.isAbsoluteDesktopPath()) { "application executable path must be absolute" }
    }

    fun matches(other: SshApplicationIdentity): Boolean = comparisonPath == other.comparisonPath
}

/** One recognized process application. Names are mandatory; a non-empty path set narrows a name match. */
internal class KnownApplication(
    val id: String,
    val displayName: String,
    vararg acceptedNames: String,
    val role: SshApplicationProcessRole,
    val acceptedPaths: Set<String>? = null,
) {
    val acceptedNames: Set<String> = acceptedNames.toSet()
    private val normalizedNames = acceptedNames.mapTo(hashSetOf()) { it.normalizedProcessName() }
    private val acceptedPathIdentities = acceptedPaths?.map(::SshApplicationIdentity)

    init {
        require(id.isNotBlank()) { "known application id must not be blank" }
        require(displayName.isNotBlank()) { "known application display name must not be blank" }
        require(acceptedNames.isNotEmpty() && acceptedNames.none(String::isBlank)) {
            "known application accepted names must be non-empty"
        }
    }

    fun matches(process: DesktopProcessIdentity): Boolean {
        val reportedName = process.executableFileName()
            ?: process.displayName?.trim()?.takeIf(String::isNotEmpty)
            ?: return false
        if (reportedName.normalizedProcessName() !in normalizedNames) return false
        val acceptedPaths = acceptedPathIdentities?.takeIf(List<SshApplicationIdentity>::isNotEmpty) ?: return true
        val reportedPath = process.executablePath ?: return false
        val reportedIdentity = runCatching { SshApplicationIdentity(reportedPath) }.getOrNull() ?: return false
        return acceptedPaths.any(reportedIdentity::matches)
    }
}

internal class KnownApplicationRegistry(
    applications: List<KnownApplication>,
) {
    val applications: List<KnownApplication> = applications.toList()

    init {
        require(this.applications.isNotEmpty()) { "known application registry must not be empty" }
        require(this.applications.map(KnownApplication::id).distinct().size == this.applications.size) {
            "known application ids must be unique"
        }
    }

    fun find(process: DesktopProcessIdentity): KnownApplication? = applications.firstOrNull { it.matches(process) }
}

internal data class SshApplicationAnchor(
    val process: DesktopProcessIdentity,
    val identity: SshApplicationIdentity,
    val knownApplication: KnownApplication?,
    val lineageIndex: Int,
) {
    val applicationId: String get() = knownApplication?.id ?: UNKNOWN_APPLICATION_ID
    val displayName: String get() = knownApplication?.displayName ?: process.shortProcessName()
    val role: SshApplicationProcessRole get() = knownApplication?.role ?: SshApplicationProcessRole.UNKNOWN
}

internal data class SshApplicationAnchorSelection(
    val recommended: SshApplicationAnchor?,
    val candidates: List<SshApplicationAnchor>,
) {
    fun contains(identity: SshApplicationIdentity): Boolean = candidates.any { it.identity.matches(identity) }
}

/**
 * Selects the effective application from a leaf-first requester-reported lineage.
 *
 * Registry names are always enforced. A non-empty registry path list adds another recognition constraint; a null
 * or empty path list accepts any or unavailable path. Only a candidate carrying a full absolute path can become an authorization
 * anchor. The selected path is still reported context, not an independently verified application principal.
 */
internal object SshApplicationAnchorSelector {
    fun select(context: DesktopProcessContext): SshApplicationAnchorSelection = select(context.processLineage)

    fun select(lineage: List<DesktopProcessIdentity>): SshApplicationAnchorSelection {
        val candidates = buildList {
            for ((lineageIndex, process) in lineage.withIndex()) {
                val knownApplication = SSH_KNOWN_APPLICATIONS.find(process)
                val role = knownApplication?.role ?: SshApplicationProcessRole.UNKNOWN
                when (role.traversal) {
                    SshLineageTraversal.STOP -> break
                    SshLineageTraversal.SKIP -> continue
                    SshLineageTraversal.CANDIDATE -> {
                        val path = process.executablePath ?: continue
                        val identity = runCatching { SshApplicationIdentity(path) }.getOrNull() ?: continue
                        add(SshApplicationAnchor(process, identity, knownApplication, lineageIndex))
                    }
                }
            }
        }.sortedWith(
            compareByDescending<SshApplicationAnchor> { requireNotNull(it.role.selectionPriority) }
                .thenBy(SshApplicationAnchor::lineageIndex),
        )
        return SshApplicationAnchorSelection(candidates.firstOrNull(), candidates)
    }
}

internal fun DesktopProcessIdentity.executableFileName(): String? =
    executablePath
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.ifBlank { executablePath }

private fun String.normalizedProcessName(): String = trim().lowercase(Locale.ROOT)

private fun String.applicationPathComparisonKey(): String = if (isWindowsDesktopPath()) {
    replace('/', '\\').lowercase(Locale.ROOT)
} else {
    this
}

private fun String.isAbsoluteDesktopPath(): Boolean =
    startsWith('/') || startsWith("\\\\") ||
        (length >= 3 && this[0].isLetter() && this[1] == ':' && (this[2] == '\\' || this[2] == '/'))

private fun String.isWindowsDesktopPath(): Boolean =
    startsWith("\\\\") ||
        (length >= 3 && this[0].isLetter() && this[1] == ':' && (this[2] == '\\' || this[2] == '/'))

private const val UNKNOWN_APPLICATION_ID = "unknown"

private fun knownApplication(
    id: String,
    displayName: String,
    role: SshApplicationProcessRole,
    vararg acceptedNames: String,
): KnownApplication = KnownApplication(
    id,
    displayName,
    *acceptedNames,
    role = role,
    acceptedPaths = null,
)

private val SSH_KNOWN_APPLICATIONS = KnownApplicationRegistry(
    listOf(
        knownApplication("codex", "Codex", SshApplicationProcessRole.AI_AGENT, "codex", "codex.exe"),
        knownApplication("opencode", "OpenCode", SshApplicationProcessRole.AI_AGENT, "opencode", "opencode.exe"),
        knownApplication("claude-code", "Claude Code", SshApplicationProcessRole.AI_AGENT, "claude", "claude.exe"),
        knownApplication("aider", "Aider", SshApplicationProcessRole.AI_AGENT, "aider", "aider.exe"),
        knownApplication("gemini-cli", "Gemini CLI", SshApplicationProcessRole.AI_AGENT, "gemini", "gemini.exe"),

        knownApplication("com.microsoft.VSCode", "Visual Studio Code", SshApplicationProcessRole.USER_APPLICATION, "code", "code.exe"),
        knownApplication("cursor", "Cursor", SshApplicationProcessRole.USER_APPLICATION, "cursor", "cursor.exe"),
        knownApplication("visual-studio", "Visual Studio", SshApplicationProcessRole.USER_APPLICATION, "devenv.exe"),
        knownApplication("com.jetbrains.intellij", "IntelliJ IDEA", SshApplicationProcessRole.USER_APPLICATION, "idea", "idea.exe", "idea64.exe"),
        knownApplication("com.google.android.studio", "Android Studio", SshApplicationProcessRole.USER_APPLICATION, "studio", "studio.exe", "studio64.exe"),
        knownApplication("com.apple.dt.Xcode", "Xcode", SshApplicationProcessRole.USER_APPLICATION, "xcode"),

        knownApplication("git", "Git", SshApplicationProcessRole.OPERATION_CLIENT, "git", "git.exe"),

        knownApplication("org.alacritty", "Alacritty", SshApplicationProcessRole.TERMINAL_HOST, "alacritty", "alacritty.exe"),
        knownApplication("org.gnome.Terminal", "GNOME Terminal", SshApplicationProcessRole.TERMINAL_HOST, "gnome-terminal", "gnome-terminal-server"),
        knownApplication("com.googlecode.iterm2", "iTerm2", SshApplicationProcessRole.TERMINAL_HOST, "iterm2"),
        knownApplication("net.kovidgoyal.kitty", "kitty", SshApplicationProcessRole.TERMINAL_HOST, "kitty", "kitty.exe"),
        knownApplication("org.kde.konsole", "Konsole", SshApplicationProcessRole.TERMINAL_HOST, "konsole"),
        knownApplication("com.apple.Terminal", "Terminal", SshApplicationProcessRole.TERMINAL_HOST, "terminal"),
        knownApplication("org.wezfurlong.wezterm", "WezTerm", SshApplicationProcessRole.TERMINAL_HOST, "wezterm", "wezterm.exe", "wezterm-gui", "wezterm-gui.exe"),
        knownApplication("Microsoft.WindowsTerminal", "Windows Terminal", SshApplicationProcessRole.TERMINAL_HOST, "windowsterminal.exe", "windows terminal", "wt.exe"),

        knownApplication("bash", "Bash", SshApplicationProcessRole.INTERACTIVE_SHELL, "bash", "bash.exe"),
        knownApplication("cmd", "Command Prompt", SshApplicationProcessRole.INTERACTIVE_SHELL, "cmd.exe"),
        knownApplication("dash", "Dash", SshApplicationProcessRole.INTERACTIVE_SHELL, "dash"),
        knownApplication("fish", "fish", SshApplicationProcessRole.INTERACTIVE_SHELL, "fish"),
        knownApplication("ksh", "KornShell", SshApplicationProcessRole.INTERACTIVE_SHELL, "ksh"),
        knownApplication("powershell", "Windows PowerShell", SshApplicationProcessRole.INTERACTIVE_SHELL, "powershell", "powershell.exe"),
        knownApplication("pwsh", "PowerShell", SshApplicationProcessRole.INTERACTIVE_SHELL, "pwsh", "pwsh.exe"),
        knownApplication("sh", "Shell", SshApplicationProcessRole.INTERACTIVE_SHELL, "sh"),
        knownApplication("zsh", "Zsh", SshApplicationProcessRole.INTERACTIVE_SHELL, "zsh"),

        knownApplication("wslhost", "WSL Host", SshApplicationProcessRole.ENVIRONMENT_HOST, "wslhost", "wslhost.exe"),

        knownApplication("conhost", "Console Host", SshApplicationProcessRole.TRANSPARENT_HELPER, "conhost", "conhost.exe"),
        knownApplication("login", "Login", SshApplicationProcessRole.TRANSPARENT_HELPER, "login"),
        knownApplication("npiperelay", "Named Pipe Relay", SshApplicationProcessRole.TRANSPARENT_HELPER, "npiperelay", "npiperelay.exe"),
        knownApplication("notisync-relay", "NotiSync Relay", SshApplicationProcessRole.TRANSPARENT_HELPER, "relay", "relay.exe"),
        knownApplication("socat", "socat", SshApplicationProcessRole.TRANSPARENT_HELPER, "socat"),
        knownApplication("ssh", "OpenSSH", SshApplicationProcessRole.TRANSPARENT_HELPER, "ssh", "ssh.exe"),

        knownApplication("explorer", "Windows Explorer", SshApplicationProcessRole.SESSION_BOUNDARY, "explorer", "explorer.exe"),
        knownApplication("init", "init", SshApplicationProcessRole.SESSION_BOUNDARY, "init"),
        knownApplication("com.apple.launchd", "launchd", SshApplicationProcessRole.SESSION_BOUNDARY, "launchd"),
        knownApplication("services", "Windows Services", SshApplicationProcessRole.SESSION_BOUNDARY, "services", "services.exe"),
        knownApplication("notisync-session-leader", "NotiSync Session Leader", SshApplicationProcessRole.SESSION_BOUNDARY, "sessionleader", "sessionleader.exe"),
        knownApplication("svchost", "Windows Service Host", SshApplicationProcessRole.SESSION_BOUNDARY, "svchost", "svchost.exe"),
        knownApplication("systemd", "systemd", SshApplicationProcessRole.SESSION_BOUNDARY, "systemd", "init-systemd"),
        knownApplication("wininit", "Windows Start-Up", SshApplicationProcessRole.SESSION_BOUNDARY, "wininit", "wininit.exe"),
    ),
)

package net.extrawdw.apps.notisync.sshkeyprovider

import java.util.Locale
import net.extrawdw.notisync.protocol.DesktopProcessIdentity

/** Semantic defaults for built-ins only. Registry entries and selection use numeric priorities. */
internal enum class DesktopApplicationProcessRole(val defaultPriority: Int) {
    AI_AGENT(700),
    USER_APPLICATION(600),
    OPERATION_CLIENT(500),
    TERMINAL_HOST(400),
    UNKNOWN(300),
    INTERACTIVE_SHELL(200),
    ENVIRONMENT_HOST(100),
}

internal enum class DesktopApplicationLineageTraversal { CANDIDATE, SKIP, STOP }

/**
 * One registry entry, independent of Android resources. Traversal is an explicit policy separate from priority.
 * Names are mandatory; a non-empty path set additionally requires an executable-path match.
 * Accepted names without an '.exe' or '.app' suffix also recognize the corresponding '.exe' name.
 * macOS app executables can match either their executable basename or their enclosing '<name>.app' name.
 * An accepted path ending in '/' matches that directory prefix; all other accepted paths match exactly.
 * Any integer priority is valid, and higher values win. Priority never means skip or stop traversal.
 */
internal class KnownDesktopApplication(
    val id: String,
    val displayName: String,
    val priority: Int,
    val traversal: DesktopApplicationLineageTraversal = DesktopApplicationLineageTraversal.CANDIDATE,
    acceptedNames: Set<String>,
    acceptedPaths: Set<String>? = null,
) {
    val acceptedNames: Set<String> = acceptedNames.toSet()
    val acceptedPaths: Set<String> = acceptedPaths.orEmpty().toSet()
    private val normalizedNames = buildSet {
        for (acceptedName in this@KnownDesktopApplication.acceptedNames) {
            val name = acceptedName.normalizedProcessName()
            add(name)
            if (!name.endsWith(".exe") && !name.endsWith(".app")) add("$name.exe")
        }
    }
    private val acceptedExactPaths = this.acceptedPaths.filterNot { it.endsWith('/') }
        .mapTo(hashSetOf()) { it.desktopApplicationPathComparisonKey() }
    private val acceptedPathPrefixes = this.acceptedPaths.filter { it.endsWith('/') }
        .map { it.desktopApplicationPathComparisonKey() }

    init {
        require(id.isNotBlank()) { "known application id must not be blank" }
        require(displayName.isNotBlank()) { "known application display name must not be blank" }
        require(this.acceptedNames.isNotEmpty() && this.acceptedNames.none(String::isBlank)) {
            "known application accepted names must be non-empty"
        }
        require(this.acceptedPaths.all { it.isAbsoluteDesktopPath() }) {
            "known application accepted paths must be absolute"
        }
    }

    fun matches(process: DesktopProcessIdentity): Boolean {
        if (process.desktopApplicationProcessNames().none { it in normalizedNames }) return false
        if (acceptedPaths.isEmpty()) return true
        val reportedPath = process.executablePath ?: return false
        if (!reportedPath.isAbsoluteDesktopPath()) return false
        val comparisonPath = reportedPath.desktopApplicationPathComparisonKey()
        return comparisonPath in acceptedExactPaths || acceptedPathPrefixes.any(comparisonPath::startsWith)
    }
}

/** A registry snapshot. Overlapping matches use priority, then ID for a stable tie break. */
internal class KnownDesktopApplicationRegistry(applications: List<KnownDesktopApplication>) {
    val applications: List<KnownDesktopApplication> = applications.toList()
    private val recognitionOrder = this.applications.sortedWith(
        compareByDescending<KnownDesktopApplication> { it.priority }.thenBy { it.id },
    )

    init {
        require(this.applications.map(KnownDesktopApplication::id).distinct().size == this.applications.size) {
            "known application ids must be unique"
        }
    }

    fun find(process: DesktopProcessIdentity): KnownDesktopApplication? = recognitionOrder.firstOrNull { it.matches(process) }

    /** User entries extend the catalog; a matching ID replaces the entire built-in entry. */
    fun withUserApplications(applications: List<KnownDesktopApplication>): KnownDesktopApplicationRegistry {
        val userRegistry = KnownDesktopApplicationRegistry(applications)
        val merged = this.applications.associateByTo(linkedMapOf(), KnownDesktopApplication::id)
        userRegistry.applications.forEach { merged[it.id] = it }
        return KnownDesktopApplicationRegistry(merged.values.toList())
    }
}

private fun DesktopProcessIdentity.desktopApplicationProcessNames(): Set<String> = buildSet {
    (executableFileName() ?: displayName)?.normalizedProcessName()?.takeIf(String::isNotEmpty)?.let(::add)
    executablePath?.macOsApplicationBundleName()?.normalizedProcessName()?.let(::add)
}

/** The nearest app bundle containing the executable under Contents contributes an alternate name. */
private fun String.macOsApplicationBundleName(): String? {
    if (!startsWith('/') || endsWith('/')) return null
    val segments = split('/')
    for (index in segments.lastIndex - 1 downTo 1) {
        if (segments[index] != "Contents") continue
        val bundleName = segments[index - 1]
        if (bundleName.length > 4 && bundleName.endsWith(".app", ignoreCase = true)) return bundleName
    }
    return null
}

private fun String.normalizedProcessName(): String = trim().lowercase(Locale.ROOT)

private fun knownDesktopApplication(
    id: String,
    displayName: String,
    role: DesktopApplicationProcessRole,
    vararg acceptedNames: String,
    acceptedPaths: Set<String> = emptySet(),
): KnownDesktopApplication = KnownDesktopApplication(
    id = id,
    displayName = displayName,
    priority = role.defaultPriority,
    acceptedNames = acceptedNames.toSet(),
    acceptedPaths = acceptedPaths,
)

private fun lineageProcess(
    id: String,
    displayName: String,
    traversal: DesktopApplicationLineageTraversal,
    vararg acceptedNames: String,
    acceptedPaths: Set<String> = emptySet(),
): KnownDesktopApplication = KnownDesktopApplication(
    id = id,
    displayName = displayName,
    priority = 0,
    traversal = traversal,
    acceptedNames = acceptedNames.toSet(),
    acceptedPaths = acceptedPaths,
)

internal val BUILT_IN_DESKTOP_APPLICATIONS = KnownDesktopApplicationRegistry(
    listOf(
        knownDesktopApplication("codex", "Codex", DesktopApplicationProcessRole.AI_AGENT, "codex", "ChatGPT.app"),
        knownDesktopApplication("opencode", "OpenCode", DesktopApplicationProcessRole.AI_AGENT, "opencode"),
        knownDesktopApplication("claude-code", "Claude Code", DesktopApplicationProcessRole.AI_AGENT, "claude"),
        knownDesktopApplication("aider", "Aider", DesktopApplicationProcessRole.AI_AGENT, "aider"),
        knownDesktopApplication("gemini-cli", "Gemini CLI", DesktopApplicationProcessRole.AI_AGENT, "gemini"),

        knownDesktopApplication("com.microsoft.VSCode", "Visual Studio Code", DesktopApplicationProcessRole.USER_APPLICATION, "code", "/Applications/Visual Studio Code.app"),
        knownDesktopApplication("cursor", "Cursor", DesktopApplicationProcessRole.USER_APPLICATION, "cursor"),
        knownDesktopApplication("visual-studio", "Visual Studio", DesktopApplicationProcessRole.USER_APPLICATION, "devenv.exe"),
        knownDesktopApplication("com.jetbrains.intellij", "IntelliJ IDEA", DesktopApplicationProcessRole.USER_APPLICATION, "idea", "idea64.exe"),
        knownDesktopApplication("com.google.android.studio", "Android Studio", DesktopApplicationProcessRole.USER_APPLICATION, "studio", "studio64.exe"),
        knownDesktopApplication("com.apple.dt.Xcode", "Xcode", DesktopApplicationProcessRole.USER_APPLICATION, "Xcode", "Xcode.app"),

        knownDesktopApplication("git", "Git", DesktopApplicationProcessRole.OPERATION_CLIENT, "git"),

        knownDesktopApplication("org.alacritty", "Alacritty", DesktopApplicationProcessRole.TERMINAL_HOST, "alacritty"),
        knownDesktopApplication("org.gnome.Terminal", "GNOME Terminal", DesktopApplicationProcessRole.TERMINAL_HOST, "gnome-terminal", "gnome-terminal-server"),
        knownDesktopApplication("com.googlecode.iterm2", "iTerm2", DesktopApplicationProcessRole.TERMINAL_HOST, "iterm2"),
        knownDesktopApplication("net.kovidgoyal.kitty", "kitty", DesktopApplicationProcessRole.TERMINAL_HOST, "kitty"),
        knownDesktopApplication("org.kde.konsole", "Konsole", DesktopApplicationProcessRole.TERMINAL_HOST, "konsole"),
        knownDesktopApplication("com.apple.Terminal", "Apple Terminal", DesktopApplicationProcessRole.TERMINAL_HOST, "Terminal.app"),
        knownDesktopApplication("org.wezfurlong.wezterm", "WezTerm", DesktopApplicationProcessRole.TERMINAL_HOST, "wezterm", "wezterm-gui"),
        knownDesktopApplication("Microsoft.WindowsTerminal", "Windows Terminal", DesktopApplicationProcessRole.TERMINAL_HOST, "windowsterminal.exe", "windows terminal", "wt.exe"),

        knownDesktopApplication("bash", "Bash", DesktopApplicationProcessRole.INTERACTIVE_SHELL, "bash"),
        knownDesktopApplication("cmd", "Command Prompt", DesktopApplicationProcessRole.INTERACTIVE_SHELL, "cmd.exe"),
        knownDesktopApplication("dash", "Dash", DesktopApplicationProcessRole.INTERACTIVE_SHELL, "dash"),
        knownDesktopApplication("fish", "fish", DesktopApplicationProcessRole.INTERACTIVE_SHELL, "fish"),
        knownDesktopApplication("ksh", "KornShell", DesktopApplicationProcessRole.INTERACTIVE_SHELL, "ksh"),
        knownDesktopApplication("powershell", "Windows PowerShell", DesktopApplicationProcessRole.INTERACTIVE_SHELL, "powershell"),
        knownDesktopApplication("pwsh", "PowerShell", DesktopApplicationProcessRole.INTERACTIVE_SHELL, "pwsh"),
        knownDesktopApplication("sh", "Shell", DesktopApplicationProcessRole.INTERACTIVE_SHELL, "sh"),
        knownDesktopApplication("zsh", "Zsh", DesktopApplicationProcessRole.INTERACTIVE_SHELL, "zsh"),

        knownDesktopApplication("wslhost", "WSL Host", DesktopApplicationProcessRole.ENVIRONMENT_HOST, "wslhost"),

        lineageProcess("conhost", "Console Host", DesktopApplicationLineageTraversal.SKIP, "conhost"),
        lineageProcess("login", "Login", DesktopApplicationLineageTraversal.SKIP, "login"),
        lineageProcess("socat", "socat", DesktopApplicationLineageTraversal.SKIP, "socat"),
        lineageProcess("ssh", "OpenSSH", DesktopApplicationLineageTraversal.SKIP, "ssh"),

        lineageProcess("explorer", "Windows Explorer", DesktopApplicationLineageTraversal.STOP, "explorer"),
        lineageProcess("init", "init", DesktopApplicationLineageTraversal.STOP, "init"),
        lineageProcess("com.apple.launchd", "launchd", DesktopApplicationLineageTraversal.STOP, "launchd"),
        lineageProcess("services", "Windows Services", DesktopApplicationLineageTraversal.STOP, "services"),
        lineageProcess("notisync-session-leader", "NotiSync Session Leader", DesktopApplicationLineageTraversal.STOP, "sessionleader"),
        lineageProcess("svchost", "Windows Service Host", DesktopApplicationLineageTraversal.STOP, "svchost"),
        lineageProcess("systemd", "systemd", DesktopApplicationLineageTraversal.STOP, "systemd", "init-systemd"),
        lineageProcess("wininit", "Windows Start-Up", DesktopApplicationLineageTraversal.STOP, "wininit"),
        lineageProcess("npiperelay", "Named Pipe Relay", DesktopApplicationLineageTraversal.STOP, "npiperelay"),
    ),
)

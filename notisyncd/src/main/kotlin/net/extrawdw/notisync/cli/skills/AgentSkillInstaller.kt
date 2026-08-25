package net.extrawdw.notisync.cli.skills

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Comparator

internal data class AgentSkillTarget(
    val id: String,
    val globalRoot: (Path, Map<String, String>) -> Path,
    val projectRoot: (Path) -> Path,
    val detectionRoot: (Path, Map<String, String>) -> Path,
)

internal class AgentSkillInstaller(
    private val userHome: Path = Path.of(System.getProperty("user.home")),
    private val environment: Map<String, String> = System.getenv(),
) {
    fun agents(requested: String?, project: Path?): List<AgentSkillTarget> {
        if (requested != null) {
            val ids = requested.split(',').map(String::trim).filter(String::isNotEmpty).distinct()
            require(ids.isNotEmpty()) { "--agent requires at least one agent id" }
            return ids.map { id ->
                TARGETS.firstOrNull { it.id == id }
                    ?: throw IllegalArgumentException(
                        "unknown agent '$id'; expected ${TARGETS.joinToString(", ") { it.id }}",
                    )
            }
        }
        return TARGETS.filter { target ->
            target.id == "common" ||
                Files.isDirectory(target.detectionRoot(userHome, environment)) ||
                (project != null && Files.isDirectory(target.projectRoot(project)))
        }
    }

    fun destination(agent: AgentSkillTarget, project: Path?): Path =
        (project?.let(agent.projectRoot) ?: agent.globalRoot(userHome, environment))
            .toAbsolutePath().normalize()

    fun install(skill: BundledSkill, agents: List<AgentSkillTarget>, project: Path?): List<Path> =
        uniqueDestinations(agents, project).map { root ->
            Files.createDirectories(root)
            val target = root.resolve(skill.id)
            val staging = Files.createTempDirectory(root, ".${skill.id}.notisync-")
            try {
                skill.files.forEach { relativePath ->
                    val output = staging.resolve(relativePath).normalize()
                    require(output.startsWith(staging)) { "invalid bundled skill path: $relativePath" }
                    Files.createDirectories(requireNotNull(output.parent))
                    NotisyncSkillCatalog.open(skill, relativePath).use { input ->
                        Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
                deleteTree(target)
                try {
                    Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(staging, target)
                }
                target
            } finally {
                deleteTree(staging)
            }
        }

    fun remove(skill: BundledSkill, agents: List<AgentSkillTarget>, project: Path?): List<Path> =
        uniqueDestinations(agents, project).mapNotNull { root ->
            val target = root.resolve(skill.id)
            if (!Files.exists(target)) null else target.also(::deleteTree)
        }

    fun installedAt(skill: BundledSkill, agents: List<AgentSkillTarget>, project: Path?): List<Path> =
        uniqueDestinations(agents, project).map { it.resolve(skill.id) }.filter(Files::isDirectory)

    private fun uniqueDestinations(agents: List<AgentSkillTarget>, project: Path?): List<Path> =
        agents.map { destination(it, project) }.distinct()

    private fun deleteTree(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { entries ->
            entries.sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
    }

    private companion object {
        val TARGETS = listOf(
            AgentSkillTarget(
                "common",
                { home, _ -> home.resolve(".agents/skills") },
                { project -> project.resolve(".agents/skills") },
                { home, _ -> home.resolve(".agents") },
            ),
            AgentSkillTarget(
                "claude-code",
                { home, _ -> home.resolve(".claude/skills") },
                { project -> project.resolve(".claude/skills") },
                { home, _ -> home.resolve(".claude") },
            ),
            AgentSkillTarget(
                "codex",
                { home, env -> env.absolutePath("CODEX_HOME")?.resolve("skills") ?: home.resolve(".codex/skills") },
                { project -> project.resolve(".codex/skills") },
                { home, env -> env.absolutePath("CODEX_HOME") ?: home.resolve(".codex") },
            ),
            AgentSkillTarget(
                "cursor",
                { home, _ -> home.resolve(".cursor/skills") },
                { project -> project.resolve(".cursor/skills") },
                { home, _ -> home.resolve(".cursor") },
            ),
            AgentSkillTarget(
                "gemini",
                { home, _ -> home.resolve(".gemini/skills") },
                { project -> project.resolve(".gemini/skills") },
                { home, _ -> home.resolve(".gemini") },
            ),
            AgentSkillTarget(
                "github-copilot",
                { home, _ -> home.resolve(".copilot/skills") },
                { project -> project.resolve(".github/skills") },
                { home, _ -> home.resolve(".copilot") },
            ),
            AgentSkillTarget(
                "junie",
                { home, _ -> home.resolve(".junie/skills") },
                { project -> project.resolve(".junie/skills") },
                { home, _ -> home.resolve(".junie") },
            ),
            AgentSkillTarget(
                "opencode",
                { home, env ->
                    (env.absolutePath("XDG_CONFIG_HOME") ?: home.resolve(".config")).resolve("opencode/skills")
                },
                { project -> project.resolve(".opencode/skills") },
                { home, env ->
                    (env.absolutePath("XDG_CONFIG_HOME") ?: home.resolve(".config")).resolve("opencode")
                },
            ),
        )

        fun Map<String, String>.absolutePath(name: String): Path? = get(name)
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?.takeIf(Path::isAbsolute)
            ?.normalize()
    }
}

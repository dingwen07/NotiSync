package net.extrawdw.notisync.cli.skills

import java.nio.file.Files
import java.nio.file.Path

internal class NotisyncSkillsCommand(
    private val output: Appendable = System.out,
    private val installer: AgentSkillInstaller = AgentSkillInstaller(),
) {
    fun run(arguments: List<String>): Int = when (arguments.firstOrNull()) {
        null, "help", "--help", "-h" -> showUsage()
        "add" -> add(arguments.drop(1))
        "remove" -> remove(arguments.drop(1))
        "list" -> list(arguments.drop(1))
        else -> throw IllegalArgumentException("skills requires add, remove, or list")
    }

    private fun add(arguments: List<String>): Int {
        if (arguments.any { it == "--help" || it == "-h" }) return showAddUsage()
        val parsed = parse(arguments, allowAll = true, allowLong = false)
        val selected = if (parsed.all) {
            require(parsed.positional.isEmpty()) { "skills add --all does not accept a skill id" }
            NotisyncSkillCatalog.skills
        } else {
            val id = parsed.positional.singleOrNull()
                ?: throw IllegalArgumentException("skills add requires a skill id or --all")
            listOf(requireSkill(id))
        }
        val project = validateProject(parsed.project)
        val agents = installer.agents(parsed.agent, project)
        selected.forEach { skill ->
            installer.install(skill, agents, project).forEach { destination ->
                output.appendLine("Skill '${skill.id}' installed to $destination")
            }
        }
        return 0
    }

    private fun remove(arguments: List<String>): Int {
        if (arguments.any { it == "--help" || it == "-h" }) return showRemoveUsage()
        val parsed = parse(arguments, allowAll = false, allowLong = false)
        val skill = requireSkill(
            parsed.positional.singleOrNull()
                ?: throw IllegalArgumentException("skills remove requires one skill id"),
        )
        val project = validateProject(parsed.project)
        val agents = installer.agents(parsed.agent, project)
        val removed = installer.remove(skill, agents, project)
        if (removed.isEmpty()) {
            output.appendLine("Skill '${skill.id}' is not installed for the selected agents.")
        } else {
            removed.forEach { output.appendLine("Skill '${skill.id}' removed from $it") }
        }
        return 0
    }

    private fun list(arguments: List<String>): Int {
        if (arguments.any { it == "--help" || it == "-h" }) return showListUsage()
        val parsed = parse(arguments, allowAll = false, allowLong = true)
        require(parsed.positional.isEmpty()) { "skills list does not accept a skill id" }
        require(parsed.agent == null) { "skills list does not accept --agent" }
        val project = validateProject(parsed.project)
        val agents = installer.agents(null, project)
        NotisyncSkillCatalog.skills.forEach { skill ->
            output.appendLine(skill.id)
            if (parsed.long) {
                output.appendLine("  ${skill.description}")
                val installed = installer.installedAt(skill, agents, project)
                output.appendLine(
                    if (installed.isEmpty()) "  installed: no"
                    else "  installed: ${installed.joinToString()}",
                )
            }
        }
        return 0
    }

    private fun parse(arguments: List<String>, allowAll: Boolean, allowLong: Boolean): ParsedOptions {
        var all = false
        var long = false
        var agent: String? = null
        var project: String? = null
        val positional = mutableListOf<String>()
        var index = 0
        while (index < arguments.size) {
            val argument = arguments[index]
            when {
                argument == "--all" && allowAll -> all = true
                argument == "--long" && allowLong -> long = true
                argument == "--agent" -> {
                    require(agent == null) { "--agent may only be specified once" }
                    agent = arguments.getOrNull(++index)
                        ?: throw IllegalArgumentException("--agent requires a value")
                }
                argument.startsWith("--agent=") -> {
                    require(agent == null) { "--agent may only be specified once" }
                    agent = argument.substringAfter('=')
                }
                argument == "--project" -> {
                    require(project == null) { "--project may only be specified once" }
                    project = arguments.getOrNull(++index)
                        ?: throw IllegalArgumentException("--project requires a path")
                }
                argument.startsWith("--project=") -> {
                    require(project == null) { "--project may only be specified once" }
                    project = argument.substringAfter('=')
                }
                argument.startsWith('-') -> throw IllegalArgumentException("unknown skills option: $argument")
                else -> positional += argument
            }
            index += 1
        }
        return ParsedOptions(all, long, agent, project, positional)
    }

    private fun validateProject(value: String?): Path? = value?.let { text ->
        val path = Path.of(text).toAbsolutePath().normalize()
        require(Files.isDirectory(path)) { "project root is not a directory: $path" }
        path
    }

    private fun requireSkill(id: String): BundledSkill = NotisyncSkillCatalog.find(id)
        ?: throw IllegalArgumentException(
            "unknown skill '$id'; expected ${NotisyncSkillCatalog.skills.joinToString(", ") { it.id }}",
        )

    private fun showUsage(): Int {
        output.appendLine(
            """
            Usage: notisync skills [-h] [COMMAND]
            Manage NotiSync Desktop agent skills.

            Commands:
              add     [id]  Install one skill, or use --all
              remove  [id]  Remove one installed skill
              list          List available skills
            """.trimIndent(),
        )
        return 0
    }

    private fun showAddUsage(): Int {
        output.appendLine(
            "Usage: notisync skills add [--all] [--agent=IDS] [--project=PATH] [SKILL]",
        )
        return 0
    }

    private fun showRemoveUsage(): Int {
        output.appendLine("Usage: notisync skills remove [--agent=IDS] [--project=PATH] SKILL")
        return 0
    }

    private fun showListUsage(): Int {
        output.appendLine("Usage: notisync skills list [--long] [--project=PATH]")
        return 0
    }

    private data class ParsedOptions(
        val all: Boolean,
        val long: Boolean,
        val agent: String?,
        val project: String?,
        val positional: List<String>,
    )
}

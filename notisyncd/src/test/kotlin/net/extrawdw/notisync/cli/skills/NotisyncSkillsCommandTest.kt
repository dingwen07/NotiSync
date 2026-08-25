package net.extrawdw.notisync.cli.skills

import java.nio.file.Files
import java.nio.file.Path
import net.extrawdw.notisync.protocol.OpenPgpRejectReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NotisyncSkillsCommandTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `add installs one complete skill only through the requested project target`() {
        val project = temporary.newFolder("project").toPath()
        val home = temporary.newFolder("home").toPath()
        val output = StringBuilder()
        val command = command(home, output)

        assertEquals(
            0,
            command.run(
                listOf(
                    "add",
                    "--agent=common",
                    "--project=${project.toAbsolutePath()}",
                    "notisync-seal",
                ),
            ),
        )

        val installed = project.resolve(".agents/skills/notisync-seal")
        assertTrue(Files.isRegularFile(installed.resolve("SKILL.md")))
        assertTrue(Files.isRegularFile(installed.resolve("references/setup.md")))
        assertTrue(Files.isRegularFile(installed.resolve("references/errors.md")))
        assertFalse(Files.exists(project.resolve(".agents/skills/notisync-run")))
        assertFalse(Files.exists(home.resolve(".agents/skills/notisync-seal")))
        assertTrue(output.toString().contains("Skill 'notisync-seal' installed to"))
    }

    @Test
    fun `add all installs the complete portable catalog and remove deletes one skill`() {
        val project = temporary.newFolder("all-project").toPath()
        val home = temporary.newFolder("all-home").toPath()
        val output = StringBuilder()
        val command = command(home, output)

        assertEquals(
            0,
            command.run(listOf("add", "--all", "--agent", "common", "--project", project.toString())),
        )
        NotisyncSkillCatalog.skills.forEach { skill ->
            assertTrue(Files.isRegularFile(project.resolve(".agents/skills/${skill.id}/SKILL.md")))
        }

        assertEquals(
            0,
            command.run(listOf("remove", "--agent=common", "--project=$project", "notisync-run")),
        )
        assertFalse(Files.exists(project.resolve(".agents/skills/notisync-run")))
        assertTrue(Files.exists(project.resolve(".agents/skills/notisync-seal")))
    }

    @Test
    fun `default target detection always includes common and includes existing agents`() {
        val project = temporary.newFolder("detected-project").toPath()
        val home = temporary.newFolder("detected-home").toPath()
        Files.createDirectories(home.resolve(".codex"))
        val output = StringBuilder()

        assertEquals(
            0,
            command(home, output).run(listOf("add", "--project=$project", "notisyncd")),
        )

        assertTrue(Files.isRegularFile(project.resolve(".agents/skills/notisyncd/SKILL.md")))
        assertTrue(Files.isRegularFile(project.resolve(".codex/skills/notisyncd/SKILL.md")))
        assertFalse(Files.exists(project.resolve(".claude/skills/notisyncd")))
    }

    @Test
    fun `list is read only and long output reports descriptions`() {
        val project = temporary.newFolder("list-project").toPath()
        val home = temporary.newFolder("list-home").toPath()
        val output = StringBuilder()

        assertEquals(0, command(home, output).run(listOf("list", "--long", "--project=$project")))

        NotisyncSkillCatalog.skills.forEach { skill ->
            assertTrue(output.toString().contains(skill.id))
            assertTrue(output.toString().contains(skill.description))
        }
        assertFalse(Files.exists(project.resolve(".agents")))
    }

    @Test
    fun `every catalog resource is present and has matching skill frontmatter`() {
        NotisyncSkillCatalog.skills.forEach { skill ->
            skill.files.forEach { relative ->
                NotisyncSkillCatalog.open(skill, relative).use { input ->
                    assertTrue(input.readBytes().isNotEmpty())
                }
            }
            val entrypoint = NotisyncSkillCatalog.open(skill, "SKILL.md").use { it.readBytes().decodeToString() }
            assertTrue(entrypoint.startsWith("---\nname: ${skill.id}\n"))
        }
    }

    @Test
    fun `Seal error reference covers every stable rejection reason without real request values`() {
        val errors = NotisyncSkillCatalog.open(NotisyncSkillCatalog.find("notisync-seal")!!, "references/errors.md")
            .use { it.readBytes().decodeToString() }

        OpenPgpRejectReason.entries.forEach { reason -> assertTrue(errors.contains("`${reason.name}`")) }
        assertFalse(Regex("compare hash [0-9a-fA-F]{7}").containsMatchIn(errors))
        assertFalse(Regex("request [0-9a-fA-F]{8}").containsMatchIn(errors))
    }

    private fun command(home: Path, output: Appendable): NotisyncSkillsCommand = NotisyncSkillsCommand(
        output,
        AgentSkillInstaller(home, emptyMap()),
    )
}

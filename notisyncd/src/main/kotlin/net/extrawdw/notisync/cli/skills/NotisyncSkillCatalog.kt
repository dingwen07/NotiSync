package net.extrawdw.notisync.cli.skills

import java.io.InputStream

internal data class BundledSkill(
    val id: String,
    val description: String,
    val files: List<String>,
)

internal object NotisyncSkillCatalog {
    val skills = listOf(
        BundledSkill(
            "notisync",
            "Use NotiSync Desktop's administrative CLI for status, pairing, trust, applications, and skills.",
            listOf("SKILL.md", "references/setup-and-platforms.md"),
        ),
        BundledSkill(
            "notisyncd",
            "Operate and troubleshoot the NotiSync Desktop daemon without confusing it with GPG or SSH agents.",
            listOf("SKILL.md", "agents/openai.yaml", "references/platforms-and-troubleshooting.md"),
        ),
        BundledSkill(
            "notisync-run",
            "Run POSIX commands under NotiSync Run supervision and interpret its local and phone-side behavior.",
            listOf("SKILL.md", "references/configuration-and-privacy.md"),
        ),
        BundledSkill(
            "notisync-seal",
            "Set up and troubleshoot NotiSync Seal remote OpenPGP Git commit and tag signing.",
            listOf("SKILL.md", "references/setup.md", "references/errors.md"),
        ),
        BundledSkill(
            "notisync-ssh-agent",
            "Set up and troubleshoot the separate NotiSync SSH Agent and remote Android-backed SSH signing.",
            listOf("SKILL.md", "references/setup.md", "references/troubleshooting.md"),
        ),
    )

    fun find(id: String): BundledSkill? = skills.firstOrNull { it.id == id }

    fun open(skill: BundledSkill, relativePath: String): InputStream {
        require(relativePath in skill.files) { "unknown bundled skill file: $relativePath" }
        val resource = "$RESOURCE_ROOT/${skill.id}/$relativePath"
        return requireNotNull(javaClass.classLoader.getResourceAsStream(resource)) {
            "bundled skill resource is missing: $resource"
        }
    }

    private const val RESOURCE_ROOT = "net/extrawdw/notisync/skills"
}

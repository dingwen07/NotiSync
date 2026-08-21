package net.extrawdw.apps.notisync.sshagent

import java.util.UUID
import net.extrawdw.notisync.protocol.SshAgentLimits

internal object SshInventoryGeneration {
    private val canonicalPattern = Regex("[0-9a-f]{${SshAgentLimits.REQUEST_ID_HEX_LENGTH}}")

    fun create(): String = UUID.randomUUID().toString().replace("-", "")

    /** Preserves UUID bits while repairing legacy UUID text; replaces any other invalid value. */
    fun canonicalize(value: String): String {
        val normalized = value.replace("-", "").lowercase()
        return normalized.takeIf(canonicalPattern::matches) ?: create()
    }

    fun isCanonical(value: String): Boolean = canonicalPattern.matches(value)
}

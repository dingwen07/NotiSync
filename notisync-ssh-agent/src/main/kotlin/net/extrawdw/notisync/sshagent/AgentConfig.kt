package net.extrawdw.notisync.sshagent

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.extrawdw.notisync.desktop.PrivateFiles

@Serializable
enum class AgentEndpointMode { AUTO, CUSTOM, OPENSSH_COMPATIBLE }

@Serializable
data class AgentConfig(
    val defaultProviderClientId: String? = null,
    val endpointMode: AgentEndpointMode = AgentEndpointMode.AUTO,
    val refreshIntervalMinutes: Long = 15,
    val signTimeoutSeconds: Long = 120,
    val maximumConnections: Int = 64,
    val maximumInFlightRequests: Int = 256,
    val allowLegacyRsaSha1: Boolean = false,
) {
    fun validated(): AgentConfig = apply {
        require(defaultProviderClientId == null || defaultProviderClientId.isNotBlank()) {
            "default provider client id must not be blank"
        }
        require(refreshIntervalMinutes in 1..24 * 60) { "refresh interval must be 1..1440 minutes" }
        require(signTimeoutSeconds in 1..300) { "sign timeout must be 1..300 seconds" }
        require(maximumConnections in 1..1024) { "maximum connections must be 1..1024" }
        require(maximumInFlightRequests in 1..4096) { "maximum in-flight requests must be 1..4096" }
    }
}

class AgentConfigStore(private val path: Path) {
    fun load(): AgentConfig {
        if (!path.exists()) return AgentConfig()
        PrivateFiles.validatePrivateFile(path)
        val bytes = Files.readAllBytes(path)
        require(bytes.size <= MAX_CONFIG_BYTES) { "SSH Agent configuration is too large" }
        return JSON.decodeFromString<AgentConfig>(bytes.decodeToString()).validated()
    }

    fun save(config: AgentConfig) {
        val encoded = encode(config).encodeToByteArray()
        require(encoded.size <= MAX_CONFIG_BYTES)
        PrivateFiles.ensureDirectory(requireNotNull(path.toAbsolutePath().parent))
        PrivateFiles.atomicWrite(path, encoded)
    }

    fun encode(config: AgentConfig): String = JSON.encodeToString(config.validated()) + "\n"

    private companion object {
        const val MAX_CONFIG_BYTES = 64 * 1024
        val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    }
}

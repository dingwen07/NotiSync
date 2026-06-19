package net.extrawdw.notisync.server

/**
 * Broker configuration, read from the environment so the same image runs anywhere. All persistent
 * state is recoverable cache; the broker never holds plaintext content or user secrets.
 */
data class ServerConfig(
    val dbPath: String,
    val fcmEnabled: Boolean,
    val fcmProjectId: String,
    /** Max ciphertext bytes delivered inline in an FCM data message; larger ones send a wake pointer. */
    val inlineBudgetBytes: Int,
    /** How long the broker retains an undelivered encrypted relay item. */
    val relayTtlMillis: Long,
    /** How long an opaque private-asset blob lives before GC. */
    val privateAssetTtlMillis: Long,
    /** Max ciphertext bytes accepted for a single private-asset upload. */
    val maxPrivateAssetBytes: Int,
    val version: String,
) {
    companion object {
        const val VERSION = "0.1.0"

        fun fromEnv(): ServerConfig {
            // System property overrides environment (handy for tests and ops).
            fun env(k: String) = (System.getProperty(k) ?: System.getenv(k))?.takeIf { it.isNotBlank() }
            return ServerConfig(
                dbPath = env("NOTISYNC_DB_PATH") ?: "data/notisync.db",
                fcmEnabled = env("NOTISYNC_FCM_ENABLED")?.toBooleanStrictOrNull() ?: true,
                fcmProjectId = env("NOTISYNC_FCM_PROJECT_ID") ?: "extrawdw-notifly",
                inlineBudgetBytes = env("NOTISYNC_INLINE_BUDGET")?.toIntOrNull() ?: 3072,
                relayTtlMillis = env("NOTISYNC_RELAY_TTL_MS")?.toLongOrNull() ?: (48L * 60 * 60 * 1000),
                privateAssetTtlMillis = env("NOTISYNC_ASSET_TTL_MS")?.toLongOrNull() ?: (7L * 24 * 60 * 60 * 1000),
                maxPrivateAssetBytes = env("NOTISYNC_MAX_ASSET_BYTES")?.toIntOrNull() ?: (1024 * 1024),
                version = VERSION,
            )
        }
    }
}

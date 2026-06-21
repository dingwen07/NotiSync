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
    val securityEnabled: Boolean,
    val playIntegrityEnabled: Boolean,
    val playIntegrityPackageName: String,
    val playIntegrityMaxTokenAgeMillis: Long,
    val debugKey: String,
    val jwtIssuer: String,
    val jwtTtlMillis: Long,
    val jwtPrivateKeyPath: String,
    val requiredAppLicensingVerdicts: Set<String>,
    val requiredAppRecognitionVerdicts: Set<String>,
    val requiredDeviceRecognitionVerdicts: Set<String>,
    val allowedDeviceActivityLevels: Set<String>,
    val requiredPlayProtectVerdicts: Set<String>,
    val signedRequestMaxSkewMillis: Long,
    val version: String,
) {
    companion object {
        const val VERSION = "0.1.0"

        fun fromEnv(): ServerConfig {
            // System property overrides environment (handy for tests and ops).
            val localProperties by lazy {
                java.util.Properties().apply {
                    val file = java.io.File(System.getProperty("NOTISYNC_LOCAL_PROPERTIES") ?: "local.properties")
                    if (file.isFile) file.inputStream().use(::load)
                }
            }
            fun env(k: String) = (System.getProperty(k) ?: System.getenv(k) ?: localProperties.getProperty(k))
                ?.takeIf { it.isNotBlank() }
            fun csv(k: String, default: String): Set<String> =
                (env(k) ?: default).split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet()
            val dbPath = env("NOTISYNC_DB_PATH") ?: "data/notisync.db"
            val jwtDefaultPath = java.io.File(dbPath).absoluteFile.parentFile
                ?.resolve("jwt-es256-private.pem")
                ?.path
                ?: "data/jwt-es256-private.pem"
            return ServerConfig(
                dbPath = dbPath,
                fcmEnabled = env("NOTISYNC_FCM_ENABLED")?.toBooleanStrictOrNull() ?: true,
                fcmProjectId = env("NOTISYNC_FCM_PROJECT_ID") ?: "extrawdw-notifly",
                inlineBudgetBytes = env("NOTISYNC_INLINE_BUDGET")?.toIntOrNull() ?: 3072,
                relayTtlMillis = env("NOTISYNC_RELAY_TTL_MS")?.toLongOrNull() ?: (48L * 60 * 60 * 1000),
                privateAssetTtlMillis = env("NOTISYNC_ASSET_TTL_MS")?.toLongOrNull() ?: (7L * 24 * 60 * 60 * 1000),
                maxPrivateAssetBytes = env("NOTISYNC_MAX_ASSET_BYTES")?.toIntOrNull() ?: (1024 * 1024),
                securityEnabled = env("NOTISYNC_SECURITY_ENABLED")?.toBooleanStrictOrNull() ?: true,
                playIntegrityEnabled = env("NOTISYNC_PLAY_INTEGRITY_ENABLED")?.toBooleanStrictOrNull() ?: true,
                playIntegrityPackageName = env("NOTISYNC_PLAY_INTEGRITY_PACKAGE") ?: "net.extrawdw.apps.notisync",
                playIntegrityMaxTokenAgeMillis = env("NOTISYNC_PLAY_INTEGRITY_MAX_AGE_MS")?.toLongOrNull() ?: (5L * 60 * 1000),
                debugKey = env("NOTISYNC_DEBUG_KEY") ?: env("DEBUG_KEY").orEmpty(),
                jwtIssuer = env("NOTISYNC_JWT_ISSUER") ?: "notisync-broker",
                jwtTtlMillis = env("NOTISYNC_JWT_TTL_MS")?.toLongOrNull() ?: (6L * 60 * 60 * 1000),
                jwtPrivateKeyPath = env("NOTISYNC_JWT_PRIVATE_KEY_PATH") ?: jwtDefaultPath,
                requiredAppLicensingVerdicts = csv("NOTISYNC_REQUIRE_APP_LICENSING", "LICENSED"),
                requiredAppRecognitionVerdicts = csv("NOTISYNC_REQUIRE_APP_RECOGNITION", "PLAY_RECOGNIZED"),
                requiredDeviceRecognitionVerdicts = csv("NOTISYNC_REQUIRE_DEVICE_RECOGNITION", "MEETS_DEVICE_INTEGRITY"),
                allowedDeviceActivityLevels = csv("NOTISYNC_ALLOW_DEVICE_ACTIVITY", "LEVEL_1,LEVEL_2"),
                requiredPlayProtectVerdicts = csv("NOTISYNC_REQUIRE_PLAY_PROTECT", "NO_ISSUES"),
                signedRequestMaxSkewMillis = env("NOTISYNC_SIGNED_REQUEST_MAX_SKEW_MS")?.toLongOrNull()
                    ?: (5L * 60 * 1000),
                version = VERSION,
            )
        }
    }
}

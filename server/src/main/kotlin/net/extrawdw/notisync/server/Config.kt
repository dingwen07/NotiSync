package net.extrawdw.notisync.server

/**
 * Broker configuration, read from the environment so the same image runs anywhere. All persistent
 * state is recoverable cache; the broker never holds plaintext content or user secrets.
 */
data class ServerConfig(
    val dbPath: String,
    val fcmEnabled: Boolean,
    val fcmProjectId: String,
    val apnsEnabled: Boolean,
    val apnsTeamId: String,
    val apnsKeyId: String,
    val apnsPrivateKeyPath: String,
    val apnsTopic: String,
    /** Max ciphertext bytes delivered inline in an FCM data message; larger ones send a wake pointer. */
    val inlineBudgetBytes: Int,
    /** How long the broker retains an undelivered encrypted relay item. */
    val relayTtlMillis: Long,
    /** How long an opaque private-asset blob lives before GC. */
    val privateAssetTtlMillis: Long,
    /** Max ciphertext bytes accepted for a single private-asset upload. */
    val maxPrivateAssetBytes: Int,
    /**
     * Master switch: when on, the broker enforces signed + JWT auth AND real Play Integrity
     * attestation. When off, all of that is bypassed (local protocol testing only).
     */
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
    /**
     * Allow-list over the 5 known device-activity levels (LEVEL_1..LEVEL_4, UNEVALUATED). The default
     * permits everything except LEVEL_4 (>50 token requests/hour — a strong abuse signal); null and any
     * future/unknown level fail closed.
     */
    val allowedDeviceActivityLevels: Set<String>,
    val requiredPlayProtectVerdicts: Set<String>,
    // --- Firebase App Check (verified locally as an RS256 JWT against the App Check JWKS) ---
    /** When on, the broker also accepts the firebaseAppCheck attestation method (alongside Play Integrity). */
    val appCheckEnabled: Boolean,
    /** Numeric Firebase project number; an App Check token's iss/aud must match it. */
    val appCheckProjectNumber: String,
    /** Allow-list of Firebase app ids (the token `sub`) — pins attestation to NotiSync's own app(s). */
    val appCheckAppIds: Set<String>,
    val appCheckJwksUrl: String,
    /** Optional extra freshness cap on an App Check token (0 = rely on the token's own exp). */
    val appCheckMaxTokenAgeMillis: Long,
    // --- /v2/metrics diagnostics endpoint (HTTP Basic Auth; disabled when the password is blank) ---
    val metricsUser: String,
    val metricsPassword: String,
    val signedRequestMaxSkewMillis: Long,
    /** Leading-hex-zero difficulty required of the /v1/integrity/verify proof of work (0 disables). */
    val powDifficulty: Int,
    val version: String,
) {
    companion object {
        // 0.2.0 = clean NS2 server (operational-key delegation + epoch rotation, /v2 API). The legacy
        // NS1 JAR stays at 0.1.x on /v1; the version distinguishes the two instances via /healthz + /status.
        const val VERSION = "0.2.0"

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
            // Security-critical switches must NOT be sourceable from a stray local.properties (the Android
            // build keeps one, with DEBUG_KEY, at the repo root). They come from env / system property only.
            fun secureEnv(k: String) = (System.getProperty(k) ?: System.getenv(k))?.takeIf { it.isNotBlank() }
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
            // Single master switch: on => enforce signed + JWT auth AND real Play Integrity attestation;
            // off => all of that is bypassed (local protocol testing only). Read from env/sysprop only.
            val playIntegrityEnabled = secureEnv("NOTISYNC_PLAY_INTEGRITY_ENABLED")?.toBooleanStrictOrNull() ?: true
            return ServerConfig(
                dbPath = dbPath,
                fcmEnabled = env("NOTISYNC_FCM_ENABLED")?.toBooleanStrictOrNull() ?: true,
                fcmProjectId = env("NOTISYNC_FCM_PROJECT_ID") ?: "extrawdw-notifly",
                apnsEnabled = env("NOTISYNC_APNS_ENABLED")?.toBooleanStrictOrNull() ?: false,
                apnsTeamId = env("NOTISYNC_APNS_TEAM_ID").orEmpty(),
                apnsKeyId = env("NOTISYNC_APNS_KEY_ID").orEmpty(),
                apnsPrivateKeyPath = env("NOTISYNC_APNS_PRIVATE_KEY_PATH").orEmpty(),
                apnsTopic = env("NOTISYNC_APNS_TOPIC") ?: "net.extrawdw.apps.NotiSync",
                inlineBudgetBytes = env("NOTISYNC_INLINE_BUDGET")?.toIntOrNull() ?: 3072,
                relayTtlMillis = env("NOTISYNC_RELAY_TTL_MS")?.toLongOrNull() ?: (48L * 60 * 60 * 1000),
                privateAssetTtlMillis = env("NOTISYNC_ASSET_TTL_MS")?.toLongOrNull() ?: (7L * 24 * 60 * 60 * 1000),
                maxPrivateAssetBytes = env("NOTISYNC_MAX_ASSET_BYTES")?.toIntOrNull() ?: (1024 * 1024),
                playIntegrityEnabled = playIntegrityEnabled,
                playIntegrityPackageName = env("NOTISYNC_PLAY_INTEGRITY_PACKAGE") ?: "net.extrawdw.apps.notisync",
                playIntegrityMaxTokenAgeMillis = env("NOTISYNC_PLAY_INTEGRITY_MAX_AGE_MS")?.toLongOrNull() ?: (5L * 60 * 1000),
                debugKey = secureEnv("NOTISYNC_DEBUG_KEY").orEmpty(),
                jwtIssuer = env("NOTISYNC_JWT_ISSUER") ?: "notisync-broker",
                jwtTtlMillis = env("NOTISYNC_JWT_TTL_MS")?.toLongOrNull() ?: (7L * 24 * 60 * 60 * 1000),
                jwtPrivateKeyPath = env("NOTISYNC_JWT_PRIVATE_KEY_PATH") ?: jwtDefaultPath,
                requiredAppLicensingVerdicts = csv("NOTISYNC_REQUIRE_APP_LICENSING", "LICENSED"),
                requiredAppRecognitionVerdicts = csv("NOTISYNC_REQUIRE_APP_RECOGNITION", "PLAY_RECOGNIZED"),
                requiredDeviceRecognitionVerdicts = csv("NOTISYNC_REQUIRE_DEVICE_RECOGNITION", "MEETS_DEVICE_INTEGRITY"),
                allowedDeviceActivityLevels = csv("NOTISYNC_ALLOW_DEVICE_ACTIVITY", "LEVEL_1,LEVEL_2,LEVEL_3,UNEVALUATED"),
                requiredPlayProtectVerdicts = csv("NOTISYNC_REQUIRE_PLAY_PROTECT", "NO_ISSUES"),
                // App Check enable is security-critical (it widens accepted attestation) → env/sysprop only.
                appCheckEnabled = secureEnv("NOTISYNC_APPCHECK_ENABLED")?.toBooleanStrictOrNull() ?: false,
                appCheckProjectNumber = env("NOTISYNC_APPCHECK_PROJECT_NUMBER").orEmpty(),
                appCheckAppIds = csv("NOTISYNC_APPCHECK_APP_IDS", ""),
                appCheckJwksUrl = env("NOTISYNC_APPCHECK_JWKS_URL") ?: "https://firebaseappcheck.googleapis.com/v1/jwks",
                appCheckMaxTokenAgeMillis = env("NOTISYNC_APPCHECK_MAX_AGE_MS")?.toLongOrNull() ?: 0L,
                // Metrics endpoint creds (not sensitive). env()/local.properties OK; a blank password disables it.
                metricsUser = env("NOTISYNC_METRICS_USER") ?: "metrics",
                metricsPassword = env("NOTISYNC_METRICS_PASSWORD").orEmpty(),
                signedRequestMaxSkewMillis = env("NOTISYNC_SIGNED_REQUEST_MAX_SKEW_MS")?.toLongOrNull()
                    ?: (5L * 60 * 1000),
                powDifficulty = env("NOTISYNC_POW_DIFFICULTY")?.toIntOrNull() ?: 4,
                version = VERSION,
            )
        }
    }
}

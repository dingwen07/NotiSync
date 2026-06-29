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
    /**
     * APNs auth keys are split per environment because Apple now issues **environment-scoped** `.p8` keys:
     * a key can be created as Sandbox-only, Production-only (Production also covers Sandbox), and is
     * additionally **restricted to specific Topics (app bundle ids)** at creation. A key minted for one
     * environment/topic is rejected on the other endpoint (`InvalidProviderToken` 403), so the broker holds
     * one key per environment and routes by the recipient's [net.extrawdw.notisync.protocol.RouteEnvironment]:
     * PRODUCTION → api.push.apple.com with [apnsKeyId]; DEVELOPMENT → api.sandbox.push.apple.com with
     * [apnsKeyIdSandbox]. Both keys must be scoped to [apnsTopic]. A legacy unscoped key can serve both:
     * leave the sandbox fields blank and they fall back to [apnsKeyId].
     */
    val apnsKeyId: String,
    val apnsPrivateKeyPath: String,
    /** Sandbox (api.sandbox.push.apple.com) APNs auth key; falls back to [apnsKeyId] for an unscoped key. */
    val apnsKeyIdSandbox: String,
    val apnsPrivateKeyPathSandbox: String,
    /** APNs topic (the iOS app bundle id). Both [apnsKeyId] and [apnsKeyIdSandbox] must be authorized for it. */
    val apnsTopic: String,
    /**
     * Operator ceiling on the base64 ciphertext delivered inline in a push data message; larger ones send
     * a wake pointer. A soft policy knob (push-bandwidth control): the broker independently floors the
     * inline size per-transport under the wire hard limit (see `Broker.hardInlineCtBudget`), so raising
     * this only widens how much the broker is *willing* to inline — it can never produce an oversize push.
     */
    val inlineBudgetBytes: Int,
    /** How long the broker retains an undelivered encrypted relay item. */
    val relayTtlMillis: Long,
    /** How long an opaque private-asset blob lives before GC. */
    val privateAssetTtlMillis: Long,
    /** Max ciphertext bytes accepted for a single private-asset upload. */
    val maxPrivateAssetBytes: Int,
    /**
     * Master security switch (`NOTISYNC_SECURITY_ENABLED`): when on, the broker enforces signed + JWT auth.
     * When off, all of that is bypassed (local protocol testing only). Whether a *passing* client-integrity
     * attestation is additionally required to mint a bearer is the separate [integrityRequired] axis.
     */
    val securityEnabled: Boolean,
    /**
     * When on (`NOTISYNC_INTEGRITY_REQUIRED`), the broker requires a passing client-integrity attestation
     * (a configured "way" — Firebase App Check today) to issue a bearer. When off (default), the broker
     * still verifies and records any attestation presented but issues a bearer to any validly-signed client
     * — the safe posture while a method (e.g. App Check) is being rolled out. Only meaningful when
     * [securityEnabled] is on; with security off, everything is bypassed regardless.
     */
    val integrityRequired: Boolean,
    val jwtIssuer: String,
    val jwtTtlMillis: Long,
    val jwtPrivateKeyPath: String,
    // --- Firebase App Check (verified locally as an RS256 JWT against the App Check JWKS) ---
    /** When on, the broker accepts the firebaseAppCheck attestation method (the live client-integrity "way"). */
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
    /** Leading-hex-zero difficulty required of the /v2/integrity/verify proof of work (0 disables). */
    val powDifficulty: Int,
    /** Optional JSON scenario file for root /demo Experience Mode. Blank uses the bundled default. */
    val demoConfigPath: String,
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
            // Master security switch: on => enforce signed + JWT auth; off => all of that is bypassed
            // (local protocol testing only). Read from env/sysprop only (never a stray local.properties).
            val securityEnabled = secureEnv("NOTISYNC_SECURITY_ENABLED")?.toBooleanStrictOrNull() ?: true
            // Whether a passing client-integrity attestation is *required* to mint a bearer. Defaults off so
            // removing/rolling a method (e.g. App Check) can't strand validly-signed clients; flip on once a
            // "way" is proven. Security-critical (it tightens acceptance) → env/sysprop only.
            val integrityRequired = secureEnv("NOTISYNC_INTEGRITY_REQUIRED")?.toBooleanStrictOrNull() ?: false
            return ServerConfig(
                dbPath = dbPath,
                fcmEnabled = env("NOTISYNC_FCM_ENABLED")?.toBooleanStrictOrNull() ?: true,
                fcmProjectId = env("NOTISYNC_FCM_PROJECT_ID") ?: "extrawdw-notifly",
                apnsEnabled = env("NOTISYNC_APNS_ENABLED")?.toBooleanStrictOrNull() ?: false,
                apnsTeamId = env("NOTISYNC_APNS_TEAM_ID").orEmpty(),
                apnsKeyId = env("NOTISYNC_APNS_KEY_ID").orEmpty(),
                apnsPrivateKeyPath = env("NOTISYNC_APNS_PRIVATE_KEY_PATH").orEmpty(),
                apnsKeyIdSandbox = env("NOTISYNC_APNS_KEY_ID_SANDBOX").orEmpty(),
                apnsPrivateKeyPathSandbox = env("NOTISYNC_APNS_PRIVATE_KEY_PATH_SANDBOX").orEmpty(),
                apnsTopic = env("NOTISYNC_APNS_TOPIC") ?: "net.extrawdw.apps.NotiSync",
                inlineBudgetBytes = env("NOTISYNC_INLINE_BUDGET")?.toIntOrNull() ?: 4096,
                relayTtlMillis = env("NOTISYNC_RELAY_TTL_MS")?.toLongOrNull() ?: (48L * 60 * 60 * 1000),
                privateAssetTtlMillis = env("NOTISYNC_ASSET_TTL_MS")?.toLongOrNull() ?: (7L * 24 * 60 * 60 * 1000),
                maxPrivateAssetBytes = env("NOTISYNC_MAX_ASSET_BYTES")?.toIntOrNull() ?: (1024 * 1024),
                securityEnabled = securityEnabled,
                integrityRequired = integrityRequired,
                jwtIssuer = env("NOTISYNC_JWT_ISSUER") ?: "notisync-broker",
                jwtTtlMillis = env("NOTISYNC_JWT_TTL_MS")?.toLongOrNull() ?: (7L * 24 * 60 * 60 * 1000),
                jwtPrivateKeyPath = env("NOTISYNC_JWT_PRIVATE_KEY_PATH") ?: jwtDefaultPath,
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
                demoConfigPath = env("NOTISYNC_DEMO_CONFIG_PATH").orEmpty(),
                version = VERSION,
            )
        }
    }
}

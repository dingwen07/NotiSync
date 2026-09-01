package net.extrawdw.apps.notisync.sshkeyprovider

import java.security.MessageDigest
import net.extrawdw.notisync.protocol.ClientId

/** One process-memory-only application authorization with an optional verified-host constraint. */
internal data class SshVolatileApplicationAuthorization(
    val authorizationId: String,
    val providerKeyId: String,
    val requesterClientId: ClientId,
    val authorizationGeneration: String,
    val authorizationEpoch: Long,
    val application: SshApplicationIdentity,
    val applicationId: String,
    val applicationDisplayName: String,
    val hostKeySha256: ByteArray?,
    val createdAt: Long,
)

internal fun SshVolatileApplicationAuthorization.toRememberedAuthorizationSnapshot(
    hostname: String?,
): SshRememberedAuthorization = SshRememberedAuthorization(
    authorizationId = authorizationId,
    providerKeyId = providerKeyId,
    requesterClientId = requesterClientId,
    authorizationGeneration = authorizationGeneration,
    authorizationEpoch = authorizationEpoch,
    scope = net.extrawdw.notisync.protocol.SshRememberScope.APPLICATION_PROCESS,
    hostKeySha256 = hostKeySha256?.copyOf(),
    hostname = hostname,
    createdAt = createdAt,
    applicationExecutablePath = application.executablePath,
    applicationId = applicationId,
    applicationDisplayName = applicationDisplayName,
    processMemoryOnly = true,
)

internal sealed interface PreparedVolatileApplicationAuthorization {
    val authorization: SshVolatileApplicationAuthorization

    data class Existing(
        override val authorization: SshVolatileApplicationAuthorization,
    ) : PreparedVolatileApplicationAuthorization

    data class New(
        override val authorization: SshVolatileApplicationAuthorization,
    ) : PreparedVolatileApplicationAuthorization
}

/** Volatile grants intentionally disappear with the Android provider process and never enter SQLite or snapshots. */
internal class SshVolatileApplicationAuthorizationStore(
    private val newAuthorizationId: () -> String,
) {
    private val authorizations = linkedMapOf<String, SshVolatileApplicationAuthorization>()

    fun prepare(
        providerKeyId: String,
        requesterClientId: ClientId,
        authorizationGeneration: String,
        authorizationEpoch: Long,
        application: SshApplicationAnchor,
        hostKeySha256: ByteArray?,
        createdAt: Long,
    ): PreparedVolatileApplicationAuthorization? {
        matchingExact(
            providerKeyId,
            requesterClientId,
            authorizationGeneration,
            authorizationEpoch,
            application.identity,
            hostKeySha256,
        )?.let { return PreparedVolatileApplicationAuthorization.Existing(it) }
        if (authorizations.size >= MAX_AUTHORIZATIONS_GLOBAL ||
            authorizations.values.count { it.providerKeyId == providerKeyId } >= MAX_AUTHORIZATIONS_PER_KEY
        ) return null
        return PreparedVolatileApplicationAuthorization.New(
            SshVolatileApplicationAuthorization(
                authorizationId = newAuthorizationId(),
                providerKeyId = providerKeyId,
                requesterClientId = requesterClientId,
                authorizationGeneration = authorizationGeneration,
                authorizationEpoch = authorizationEpoch,
                application = application.identity,
                applicationId = application.applicationId,
                applicationDisplayName = application.displayName,
                hostKeySha256 = hostKeySha256?.copyOf(),
                createdAt = createdAt,
            ),
        )
    }

    fun commit(prepared: PreparedVolatileApplicationAuthorization) {
        if (prepared is PreparedVolatileApplicationAuthorization.Existing) return
        val authorization = prepared.authorization
        check(authorization.authorizationId !in authorizations) { "duplicate volatile SSH authorization id" }
        check(authorizations.size < MAX_AUTHORIZATIONS_GLOBAL) { "volatile SSH authorization limit changed" }
        check(authorizations.values.count { it.providerKeyId == authorization.providerKeyId } < MAX_AUTHORIZATIONS_PER_KEY) {
            "volatile per-key SSH authorization limit changed"
        }
        authorizations[authorization.authorizationId] = authorization
    }

    fun matching(
        providerKeyId: String,
        requesterClientId: ClientId,
        authorizationGeneration: String,
        authorizationEpoch: Long,
        applicationSelection: SshApplicationAnchorSelection,
        hostKeySha256: ByteArray?,
    ): SshVolatileApplicationAuthorization? = authorizations.values.asSequence()
        .filter { authorization ->
            authorization.providerKeyId == providerKeyId &&
                authorization.requesterClientId == requesterClientId &&
                authorization.authorizationGeneration == authorizationGeneration &&
                authorization.authorizationEpoch == authorizationEpoch &&
                applicationSelection.contains(authorization.application) &&
                (authorization.hostKeySha256 == null ||
                    hostKeySha256 != null && MessageDigest.isEqual(authorization.hostKeySha256, hostKeySha256))
        }
        // When both grants authorize the request, retain the narrower host-bound grant in the audit trail.
        .maxByOrNull { if (it.hostKeySha256 == null) 0 else 1 }

    fun forget(requesterClientId: ClientId, authorizationGeneration: String, invalidatedThroughEpoch: Long): Boolean {
        val removed = authorizations.values.filter { authorization ->
            authorization.requesterClientId == requesterClientId &&
                authorization.authorizationGeneration == authorizationGeneration &&
                authorization.authorizationEpoch <= invalidatedThroughEpoch
        }.map(SshVolatileApplicationAuthorization::authorizationId)
        removed.forEach { authorizationId -> authorizations.remove(authorizationId)?.hostKeySha256?.fill(0) }
        return removed.isNotEmpty()
    }

    fun forgetKey(providerKeyId: String): Boolean {
        val removed = authorizations.values.filter { it.providerKeyId == providerKeyId }
            .map(SshVolatileApplicationAuthorization::authorizationId)
        removed.forEach { authorizationId -> authorizations.remove(authorizationId)?.hostKeySha256?.fill(0) }
        return removed.isNotEmpty()
    }

    fun snapshot(): List<SshVolatileApplicationAuthorization> = authorizations.values.toList()

    fun delete(authorizationId: String): Boolean {
        val removed = authorizations.remove(authorizationId) ?: return false
        removed.hostKeySha256?.fill(0)
        return true
    }

    fun clear() {
        authorizations.values.forEach { it.hostKeySha256?.fill(0) }
        authorizations.clear()
    }

    internal fun size(): Int = authorizations.size

    private fun matchingExact(
        providerKeyId: String,
        requesterClientId: ClientId,
        authorizationGeneration: String,
        authorizationEpoch: Long,
        application: SshApplicationIdentity,
        hostKeySha256: ByteArray?,
    ): SshVolatileApplicationAuthorization? = authorizations.values.firstOrNull { authorization ->
        authorization.providerKeyId == providerKeyId &&
            authorization.requesterClientId == requesterClientId &&
            authorization.authorizationGeneration == authorizationGeneration &&
            authorization.authorizationEpoch == authorizationEpoch &&
            authorization.application.matches(application) &&
            nullableDigestEquals(authorization.hostKeySha256, hostKeySha256)
    }

    private companion object {
        const val MAX_AUTHORIZATIONS_GLOBAL = 1_024
        const val MAX_AUTHORIZATIONS_PER_KEY = 128
    }
}

private fun nullableDigestEquals(first: ByteArray?, second: ByteArray?): Boolean = when {
    first == null || second == null -> first == null && second == null
    else -> MessageDigest.isEqual(first, second)
}

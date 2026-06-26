package net.extrawdw.apps.notisync.transport

import net.extrawdw.notisync.protocol.IntegrityVerificationResponse

/**
 * Persistence port for the broker auth token — the cached proof of a successful attestation.
 * Implementations encrypt at rest. [BrokerClient] loads once on construction and writes
 * through on every change, so a still-valid token survives process death (a force-stop/relaunch reuses
 * it instead of forcing a fresh attestation).
 */
interface AuthTokenStore {
    fun load(): IntegrityVerificationResponse?
    fun save(token: IntegrityVerificationResponse?)
}

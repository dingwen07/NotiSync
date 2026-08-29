package net.extrawdw.notisync.peer.channel

import java.security.MessageDigest
import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.DataSync
import net.extrawdw.notisync.protocol.DataSyncKind
import net.extrawdw.notisync.protocol.OpenPgpSignAction
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.ScreenMirrorAction
import net.extrawdw.notisync.protocol.SshAgentLimits
import net.extrawdw.notisync.protocol.SshAgentSyncKind

/** Shared validation for the exceptional DATA_SYNC messages allowed to request a high-priority wake. */
object HighDataSyncPolicy {
    /**
     * Validate one HIGH DATA_SYNC body and its audience. Existing Run lifecycle traffic retains its
     * capability-routed fan-out policy; screen mirroring is an exact, body-bound unicast.
     */
    fun validate(body: ByteArray, scope: Recipients, requesterId: ClientId? = null) {
        val sync = runCatching { ProtocolCodec.decodeFromCbor<DataSync>(body) }.getOrNull()
        if (scope is Recipients.OnlyCapableSet && sync?.kind == DataSyncKind.SSH_AGENT) {
            validateSshRequest(sync, scope, requesterId)
        } else if (scope is Recipients.OnlyCapableSet) {
            validateExactPush(scope)
        } else if (sync?.kind == DataSyncKind.OPENPGP_SIGN) {
            validateOpenPgpRequest(sync, scope, requesterId)
        } else if (sync?.kind == DataSyncKind.SCREEN_MIRRORING || scope is Recipients.OnlyCapable) {
            validateScreenRequest(sync, scope, requesterId)
        } else {
            validateLegacyRouted(scope)
        }
    }

    /**
     * Exact-set HIGH routing is intentionally application agnostic. The submitting application is
     * responsible for binding its signed body to this audience; the daemon only requires the
     * capabilities needed for exact routing and filtered push delivery.
     */
    private fun validateExactPush(scope: Recipients.OnlyCapableSet) {
        require(
            scope.requiredCapabilities.contains(Capability.CAPABILITY_ROUTING_V1) &&
                scope.requiredCapabilities.contains(Capability.PUSH_FILTERING),
        ) {
            "HIGH exact-set DATA_SYNC requires capability routing and push filtering"
        }
    }

    private fun validateSshRequest(sync: DataSync, scope: Recipients.OnlyCapableSet, requesterId: ClientId?) {
        val ssh = requireNotNull(sync.sshAgent) { "HIGH SSH_AGENT requires an sshAgent body" }
        val validationError = ssh.validationError(::sha256)
        require(validationError == null) { validationError ?: "invalid SSH request" }
        when (ssh.kind) {
            SshAgentSyncKind.KEYS_REQUEST -> {
                val request = requireNotNull(ssh.keysRequest)
                requesterId?.let {
                    require(request.requesterClientId == it) {
                        "SSH inventory requesterClientId must be the envelope signer"
                    }
                }
                require(request.startup && scope.ids == request.targetProviderClientIds.toSet()) {
                    "HIGH SSH inventory requires its exact signed startup provider set"
                }
                require(scope.requiredCapabilities == SshAgentLimits.HIGH_FILTERING_PROVIDER_CAPABILITIES) {
                    "HIGH SSH inventory requires capability routing, a key provider, and push filtering"
                }
            }
            SshAgentSyncKind.SIGN_REQUEST -> {
                val request = requireNotNull(ssh.signRequest)
                requesterId?.let {
                    require(request.requesterClientId == it) {
                        "SSH sign requesterClientId must be the envelope signer"
                    }
                }
                require(scope.ids == request.eligibleProviderClientIds.toSet()) {
                    "HIGH SSH sign audience must equal the signed eligible provider set"
                }
                require(scope.requiredCapabilities == SshAgentLimits.HIGH_SIGN_PROVIDER_CAPABILITIES) {
                    "HIGH SSH sign requires capability routing and a key provider"
                }
            }
            SshAgentSyncKind.IMPORT_REQUEST -> {
                val request = requireNotNull(ssh.importRequest)
                requesterId?.let {
                    require(request.requesterClientId == it) {
                        "SSH import requesterClientId must be the envelope signer"
                    }
                }
                require(scope.ids.size == 1 && request.requesterClientId !in scope.ids) {
                    "HIGH SSH import must target exactly one provider and exclude its requester"
                }
                require(scope.requiredCapabilities == SshAgentLimits.HIGH_SIGN_PROVIDER_CAPABILITIES) {
                    "HIGH SSH import requires capability routing and a key provider"
                }
            }
            else -> throw IllegalArgumentException("only SSH keys/sign/import requests may use HIGH urgency")
        }
    }

    private fun validateOpenPgpRequest(sync: DataSync, scope: Recipients, requesterId: ClientId?) {
        val request = requireNotNull(sync.openPgpSign) {
            "HIGH OPENPGP_SIGN requires an openPgpSign body"
        }
        require(request.action == OpenPgpSignAction.REQUEST) {
            "only an OPENPGP_SIGN REQUEST may use HIGH urgency"
        }
        val validationError = request.validationError(::sha256)
        require(validationError == null) { validationError ?: "invalid OpenPGP request" }
        requesterId?.let {
            require(request.requesterClientId == it) {
                "OpenPGP requesterClientId must be the envelope signer"
            }
        }
        val filtered = scope as? Recipients.OwnMeshFiltered
        require(
            filtered != null &&
                filtered.excluded.isEmpty() &&
                filtered.excludedPlatforms.isEmpty() &&
                filtered.legacyExcludedPlatforms.isEmpty() &&
                filtered.forbiddenCapabilities.isEmpty() &&
                filtered.requireCapabilityRoutingV1 &&
                filtered.requiredCapabilities == request.requiredSignerCapabilities()
        ) {
            "HIGH OpenPGP request requires the exact capability-routed own-mesh audience"
        }
    }

    /** Preserve validation of an empty strict batch without manufacturing an unauthenticated body. */
    fun validateEmpty(scope: Recipients) {
        require(scope !is Recipients.OnlyCapable && scope !is Recipients.OnlyCapableSet) {
            "HIGH exact-capability DATA_SYNC requires a matching request body"
        }
        validateLegacyRouted(scope)
    }

    private fun validateScreenRequest(sync: DataSync?, scope: Recipients, requesterId: ClientId?) {
        require(sync?.kind == DataSyncKind.SCREEN_MIRRORING) {
            "HIGH capability-gated unicast requires a SCREEN_MIRRORING body"
        }
        val request = requireNotNull(sync.screenMirror) {
            "HIGH SCREEN_MIRRORING requires a screenMirror body"
        }
        require(request.action == ScreenMirrorAction.REQUEST) {
            "only a SCREEN_MIRRORING REQUEST may use HIGH urgency"
        }
        require(request.protocolVersion == 1) { "unsupported screen mirror protocol version" }
        require(request.sessionId.isNotBlank() && request.sessionId.length <= 128) {
            "screen mirror sessionId must contain 1..128 characters"
        }
        require(request.requesterPeerId != request.sourcePeerId) {
            "screen mirror requester and source must be different peers"
        }
        requesterId?.let {
            require(request.requesterPeerId == it) {
                "screen mirror requesterPeerId must be the envelope signer"
            }
        }
        val expiresAt = request.expiresAt
        require(request.issuedAt > 0 && expiresAt != null && expiresAt > request.issuedAt) {
            "screen mirror request requires a valid issuedAt/expiresAt interval"
        }
        require(request.routingToken?.size == 16) { "screen mirror routingToken must be 16 bytes" }
        require(request.masterPsk?.size == 32) { "screen mirror masterPsk must be 32 bytes" }
        require(request.codec != null) { "screen mirror request requires a codec" }
        require(request.candidates.isNotEmpty()) { "screen mirror request requires a connection candidate" }
        require(request.maxDimension?.let { it > 0 } != false) {
            "screen mirror maxDimension must be positive"
        }
        require(request.maxFps?.let { it > 0 } != false) { "screen mirror maxFps must be positive" }
        require(request.videoBitrateBps?.let { it > 0 } != false) {
            "screen mirror videoBitrateBps must be positive"
        }

        val exact = scope as? Recipients.OnlyCapable
        require(exact?.id == request.sourcePeerId) {
            "HIGH screen request must target its sourcePeerId with OnlyCapable"
        }
        val required = request.requiredSourceCapabilities()
        require(required.isNotEmpty() && exact.requiredCapabilities == required) {
            "screen request features and codec must exactly match its required source capabilities"
        }
    }

    private fun validateLegacyRouted(scope: Recipients) {
        val filtered = scope as? Recipients.OwnMeshFiltered
        require(
            filtered?.requireCapabilityRoutingV1 == true &&
                filtered.requiredCapabilities.containsAll(LEGACY_HIGH_DATA_SYNC_CAPABILITIES),
        ) {
            "HIGH DATA_SYNC requires either an exact capable screen source or a capability-routed " +
                "OwnMeshFiltered audience with DISPLAY, BACKGROUND_WAKE, and PUSH_FILTERING"
        }
    }

    private val LEGACY_HIGH_DATA_SYNC_CAPABILITIES = setOf(
        Capability.DISPLAY,
        Capability.BACKGROUND_WAKE,
        Capability.PUSH_FILTERING,
    )

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

}

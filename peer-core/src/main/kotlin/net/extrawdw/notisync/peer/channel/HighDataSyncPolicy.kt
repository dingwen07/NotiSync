package net.extrawdw.notisync.peer.channel

import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.DataSync
import net.extrawdw.notisync.protocol.DataSyncKind
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.ScreenMirrorAction

/** Shared validation for the exceptional DATA_SYNC messages allowed to request a high-priority wake. */
object HighDataSyncPolicy {
    /**
     * Validate one HIGH DATA_SYNC body and its audience. Existing Run lifecycle traffic retains its
     * capability-routed fan-out policy; screen mirroring is an exact, body-bound unicast.
     */
    fun validate(body: ByteArray, scope: Recipients, requesterId: ClientId? = null) {
        val sync = runCatching { ProtocolCodec.decodeFromCbor<DataSync>(body) }.getOrNull()
        if (sync?.kind == DataSyncKind.SCREEN_MIRRORING || scope is Recipients.OnlyCapable) {
            validateScreenRequest(sync, scope, requesterId)
        } else {
            validateLegacyRouted(scope)
        }
    }

    /** Preserve validation of an empty strict batch without manufacturing an unauthenticated body. */
    fun validateEmpty(scope: Recipients) {
        require(scope !is Recipients.OnlyCapable) {
            "HIGH screen DATA_SYNC requires a SCREEN_MIRRORING REQUEST body"
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
}

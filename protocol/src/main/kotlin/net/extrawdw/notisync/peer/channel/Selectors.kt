package net.extrawdw.notisync.peer.channel

import kotlinx.serialization.Serializable
import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.ClientId

/** Audience selector resolved by a peer directory into a concrete recipient set. */
@Serializable
sealed interface Recipients {
    /** This user's own devices only (notifications, dismissals, trust, cards, asset repair). */
    @Serializable
    data object OwnMesh : Recipients

    /** Every trusted device — own AND "other" (used only by profile updates). */
    @Serializable
    data object AllTrusted : Recipients

    /** This user's own devices except specific peers and (optionally) platform families. [excludedPlatforms]
     *  is an unconditional policy/user gate. [legacyExcludedPlatforms] is only the compatibility fallback for
     *  peers without CAPABILITY_ROUTING_V1; a routed peer uses [requiredCapabilities] instead. */
    @Serializable
    data class OwnMeshFiltered(
        val excluded: Set<ClientId> = emptySet(),
        val excludedPlatforms: Set<String> = emptySet(),
        val legacyExcludedPlatforms: Set<String> = emptySet(),
        /** Existing requirements (for example DISPLAY) apply to every peer. New requirements apply once a
         *  peer advertises CAPABILITY_ROUTING_V1; legacy peers use [legacyExcludedPlatforms] as fallback. */
        val requiredCapabilities: Set<Capability> = emptySet(),
        /** Capabilities which disqualify a peer even when every required capability is present. This is an
         *  unconditional negative requirement, so callers can describe audiences such as the compatibility
         *  display path (`DISPLAY` + `BACKGROUND_WAKE`, but not `PUSH_FILTERING`) without platform checks. */
        val forbiddenCapabilities: Set<Capability> = emptySet(),
        /** Reject legacy peers that do not advertise capability-routing support. Default false keeps
         * existing Android routing source-compatible; callers such as nsrun periodic updates set true. */
        val requireCapabilityRoutingV1: Boolean = false,
    ) : Recipients

    /** A single own device by id (unicast: card / asset repair). */
    @Serializable
    data class Only(val id: ClientId) : Recipients

    /**
     * A single own device, only if its complete capability declaration satisfies [requiredCapabilities].
     * Unlike [OwnMeshFiltered], this selector has no legacy platform fallback: it is for security-sensitive
     * protocols whose request must never be routed to a peer that did not explicitly advertise support.
     */
    @Serializable
    data class OnlyCapable(
        val id: ClientId,
        val requiredCapabilities: Set<Capability>,
    ) : Recipients

    /**
     * An exact allow-list of own devices whose complete capability declarations satisfy every requirement.
     * This avoids the time-of-check/time-of-use expansion possible when callers approximate an allow-list by
     * excluding the current complement from [OwnMeshFiltered]. Security-sensitive bodies must also repeat
     * their authorized peer ids inside the signed encrypted payload.
     */
    @Serializable
    data class OnlyCapableSet(
        val ids: Set<ClientId>,
        val requiredCapabilities: Set<Capability>,
    ) : Recipients {
        init {
            require(ids.isNotEmpty() && ids.size <= MAX_IDS) {
                "OnlyCapableSet ids must contain 1..$MAX_IDS entries"
            }
            require(requiredCapabilities.isNotEmpty()) {
                "OnlyCapableSet requires at least one capability"
            }
        }

        private companion object {
            const val MAX_IDS = 64
        }
    }
}

/**
 * Which key signs an outbound envelope. The caller chooses explicitly; a body-agnostic channel does
 * not infer this from the message type.
 *
 * [OPERATIONAL] is the rotatable hot-path key (`signerEpoch` >= 1). [IDENTITY] is the cold identity
 * root (`signerEpoch` = 0), reserved for identity-anchored control bodies such as trust rosters.
 */
@Serializable
enum class SignerSelection { OPERATIONAL, IDENTITY }

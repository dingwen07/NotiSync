package net.extrawdw.apps.notisync.foundation

import net.extrawdw.apps.notisync.data.PendingRotation
import net.extrawdw.apps.notisync.data.TrustState
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ClientKeyEpoch
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.Purpose
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.SignedType
import net.extrawdw.notisync.protocol.crypto.Hpke
import net.extrawdw.notisync.protocol.crypto.OperationalSigner

/**
 * NS2 epoch-rotation state machine (§7) — the mint → pre-warm → activate → retire dance. Gated entirely by
 * `BuildConfig.ENABLE_ROTATION`: with it OFF this class is never constructed, so the device runs at epoch 1
 * forever and the foundation never mints a second epoch. It is deliberately decoupled from the Android
 * Keystore (key generation, the live-signer swap, and key destruction are injected) so the whole machine is
 * unit-testable on the JVM with software signers and a fake [TrustState].
 *
 * Rotation is *key hygiene + forward secrecy + planned recovery* — NOT instant revocation: an offline peer
 * keeps accepting the retired epoch until its `notAfter`. The two hard correctness rules it enforces (§7):
 *  - **Overlap ≥ relay TTL + offline gap.** The relay holds envelopes up to [RELAY_TTL_MS]; retaining the
 *    old HPKE private keyset for less than that would silently drop in-flight notifications. Enforced as
 *    [overlapMillis] ≥ [MIN_OVERLAP_MS] at construction.
 *  - **Pre-warm lead ≥ own-mesh convergence latency.** N+1 is published + pushed at `now`, but not signed
 *    with until [leadMillis] later — by which point it is committed on the broker and gossiped, removing
 *    the read-your-writes race.
 *
 * State is persisted in the identity-signed TrustStore section #4 ([PendingRotation]) so staged activation
 * and retirement survive a process restart and cannot be tampered. Drive it with [beginRotation] (start) +
 * periodic [tick] (advance through activation and retirement as wall-clock crosses each boundary).
 */
class RotationManager(
    private val clientId: ClientId,
    private val identitySpki: ByteArray,
    /** The identity root signature over a key-epoch payload (the cold StrongBox key). */
    private val identitySign: (ByteArray) -> ByteArray,
    private val trust: TrustState,
    /** Generate or load the operational signer for an epoch (Android TEE keystore; software in tests). */
    private val mintOperational: (epoch: Int) -> OperationalSigner,
    /** Generate or load the HPKE public keyset for an epoch (epoch-indexed ring; fake in tests). */
    private val mintHpke: (epoch: Int) -> ByteArray,
    /** Swap the live operational signer to [signer] for [epoch] and advance the persisted epoch counter. */
    private val onActivate: (signer: OperationalSigner, epoch: Int) -> Unit,
    /** Destroy the retired epoch's operational + HPKE private keys, retaining only the epochs in [keep]. */
    private val onRetire: (retiredEpoch: Int, keep: Set<Int>) -> Unit,
    /** Publish a key-epoch to the broker (`POST /v2/keyepoch`). */
    private val publish: suspend (SignedBlob) -> Unit,
    /** Push a key-epoch E2E to own-mesh for pre-warm (so peers cache N+1 before activation, no poll). */
    private val pushE2E: suspend (SignedBlob) -> Unit,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val leadMillis: Long = DEFAULT_LEAD_MS,
    private val overlapMillis: Long = DEFAULT_OVERLAP_MS,
    private val graceMillis: Long = RELAY_TTL_MS,
    private val lifetimeMillis: Long = DEFAULT_LIFETIME_MS,
) {
    init {
        require(overlapMillis >= MIN_OVERLAP_MS) {
            "rotation overlap ($overlapMillis ms) < relay TTL + offline gap ($MIN_OVERLAP_MS ms) would drop in-flight notifications"
        }
    }

    /**
     * Begin a rotation if none is in flight: mint epoch N+1, re-publish N with a finite `notAfter` so it
     * retires after the overlap, publish N+1 pre-warmed (future `notBefore`, floor still N so it does not yet
     * supersede), and push it E2E. Returns the target epoch, or null if a rotation is already pending.
     *
     * [leadMillisOverride] replaces the default pre-warm lead for this one rotation — diagnostics passes 0 so
     * the immediately-following [tick] activates N+1 at once. It does NOT shorten the overlap or grace, so the
     * retired epoch's private keys are still retained for the full window (forward-secrecy invariant intact).
     */
    suspend fun beginRotation(leadMillisOverride: Long? = null): Int? {
        if (trust.pendingRotation() != null) return null
        val n = trust.selfEpoch()
        val target = n + 1
        val opCur = mintOperational(n)
        val hpkeCur = mintHpke(n)
        val opNext = mintOperational(target)
        val hpkeNext = mintHpke(target)

        val nb = now() + (leadMillisOverride ?: leadMillis)
        val retireNotAfter = nb + overlapMillis            // N stops being valid one overlap after N+1 activates
        val targetNotAfter = retireNotAfter + lifetimeMillis

        // Re-publish N with a finite notAfter (floor stays N: minEpoch = n) so the broker GC + peers retire it.
        publish(buildKeyEpoch(n, opCur.operationalPublicKeySpki, hpkeCur, notBefore = 0L, notAfter = retireNotAfter, minEpoch = n))
        // Publish + pre-warm N+1 (future notBefore; minEpoch still n so it does not yet raise the floor).
        val nextBlob = buildKeyEpoch(target, opNext.operationalPublicKeySpki, hpkeNext, notBefore = nb, notAfter = targetNotAfter, minEpoch = n)
        publish(nextBlob)
        pushE2E(nextBlob)

        trust.setPendingRotation(
            PendingRotation(
                targetEpoch = target,
                notBefore = nb,
                notAfter = targetNotAfter,
                retiredEpoch = n,
                retireRetiredAt = retireNotAfter + graceMillis,
            ),
        )
        return target
    }

    /**
     * Advance an in-flight rotation as wall-clock crosses each boundary (idempotent — safe to call on any
     * schedule). ACTIVATE at `notBefore`: swap the live signer to N+1 and start signing with it (N is still
     * accepted). RETIRE at the retired epoch's `notAfter` + grace: raise the floor to N+1 (re-publish with
     * `minEpoch = target`) and destroy N's operational + HPKE keys.
     */
    suspend fun tick() {
        val p = trust.pendingRotation() ?: return
        val nowMs = now()

        if (nowMs >= p.notBefore && trust.selfEpoch() < p.targetEpoch) {
            val opNext = mintOperational(p.targetEpoch)
            onActivate(opNext, p.targetEpoch) // sets the live operational signer + advanceSelfEpoch(target)
            // Re-publish the now-active target (floor still N during the overlap so peers may still send to N).
            publish(buildKeyEpoch(p.targetEpoch, opNext.operationalPublicKeySpki, mintHpke(p.targetEpoch), notBefore = p.notBefore, notAfter = p.notAfter, minEpoch = p.retiredEpoch))
        }

        if (nowMs >= p.retireRetiredAt) {
            val opNext = mintOperational(p.targetEpoch)
            // Raise the floor to the target (minEpoch = target): the broker floor advances and peers reject N.
            publish(buildKeyEpoch(p.targetEpoch, opNext.operationalPublicKeySpki, mintHpke(p.targetEpoch), notBefore = p.notBefore, notAfter = p.notAfter, minEpoch = p.targetEpoch))
            onRetire(p.retiredEpoch, setOf(p.targetEpoch))
            trust.setPendingRotation(null)
        }
    }

    private fun buildKeyEpoch(epoch: Int, opSpki: ByteArray, hpkePublic: ByteArray, notBefore: Long, notAfter: Long, minEpoch: Int): SignedBlob {
        val keyEpoch = ClientKeyEpoch(
            clientId = clientId,
            identityPublicKey = identitySpki,
            epoch = epoch,
            operationalSigningKey = opSpki,
            // Publish the raw 32-byte X25519 key (not the Tink keyset); peers seal via Hpke.seal's length
            // dispatch (iOS CryptoKit directly). Mirrors AppGraph.buildClientKeyEpochBlob.
            hpkePublicKeyset = Hpke.rawPublicKey(hpkePublic),
            purposes = listOf(Purpose.ENVELOPE_SIGN, Purpose.REQUEST_AUTH, Purpose.HPKE_SEAL),
            notBefore = notBefore,
            notAfter = notAfter,
            minEpoch = minEpoch,
        )
        val payload = ProtocolCodec.encodeToCbor(keyEpoch)
        return SignedBlob(SignedType.KEY_EPOCH, signerId = clientId, payload = payload, sig = identitySign(payload))
    }

    companion object {
        /** Relay store-and-forward TTL (matches the broker's `NOTISYNC_RELAY_TTL_MS` default, 48h). */
        const val RELAY_TTL_MS = 48L * 60 * 60 * 1000

        /** Hard floor on the overlap window: HPKE retention shorter than the relay TTL silently drops
         *  in-flight notifications (§7, hardening #4). The overlap must be at least this. */
        const val MIN_OVERLAP_MS = RELAY_TTL_MS

        /** Pre-warm lead: how long after publishing N+1 before we start signing with it (≥ own-mesh
         *  convergence latency, absorbing offline peers + FCM wake). */
        const val DEFAULT_LEAD_MS = 6L * 60 * 60 * 1000

        /** Default overlap (notAfter(N) − notBefore(N+1)): ~7 days, comfortably past the relay TTL + offline gap. */
        const val DEFAULT_OVERLAP_MS = 7L * 24 * 60 * 60 * 1000

        /** Fallback expiry for the *current* epoch if rotation stalls AND the app is never reopened — NOT the
         *  rotation cadence ([DEFAULT_ROTATION_INTERVAL_MS]) and NOT a forward-secrecy bound (retired keys are
         *  destroyed at overlap+grace, ~9 days). Normal operation re-publishes this long before it bites; it
         *  only governs an abandoned device's lockout. The current epoch's notAfter ≈ activation + overlap +
         *  this, so ~60 days here ≈ two rotation cycles of resilience. Must stay comfortably above the interval. */
        const val DEFAULT_LIFETIME_MS = 60L * 24 * 60 * 60 * 1000

        /** Default cadence at which the maintenance worker initiates a fresh rotation (live-epoch age ≥ this).
         *  30 days. This is the *initiation* interval only — the worker must still `tick()` far more often so
         *  activation/retirement land inside the multi-day overlap; see EpochMaintenanceWorker. */
        const val DEFAULT_ROTATION_INTERVAL_MS = 30L * 24 * 60 * 60 * 1000

        init {
            // Lockout guard. A headless device (never foregrounded → never re-published to MAX notAfter) only
            // refreshes its current epoch at the NEXT rotation. That epoch's validity window (overlap + lifetime)
            // must therefore cover at least one rotation interval, or the broker would reject the device in the
            // gap before the next rotation re-publishes it. Validated against the shipped defaults at class load.
            require(DEFAULT_OVERLAP_MS + DEFAULT_LIFETIME_MS >= DEFAULT_ROTATION_INTERVAL_MS) {
                "epoch validity (overlap ${DEFAULT_OVERLAP_MS} + lifetime ${DEFAULT_LIFETIME_MS} ms) must cover " +
                    "the rotation interval (${DEFAULT_ROTATION_INTERVAL_MS} ms) or a headless device locks out"
            }
        }
    }
}

package net.extrawdw.apps.notisync.foundation

import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.notisync.testsupport.FakeTrustState
import net.extrawdw.apps.notisync.testsupport.newSigner
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.crypto.Hpke
import net.extrawdw.notisync.protocol.crypto.OperationalSigner
import net.extrawdw.notisync.protocol.crypto.SoftwareOperationalSigner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The mint → pre-warm → activate → retire state machine (§7), driven with software keys + a fake clock. */
class RotationManagerTest {

    private class Rig(start: Long) {
        val identity = newSigner()
        val trust = FakeTrustState().apply { selfEpochValue = 1 }
        var now = start
        val ops = mutableMapOf<Int, SoftwareOperationalSigner>()
        val hpkes = mutableMapOf<Int, ByteArray>()
        val activated = mutableListOf<Pair<OperationalSigner, Int>>()
        val retired = mutableListOf<Pair<Int, Set<Int>>>()
        val published = mutableListOf<SignedBlob>()
        val pushed = mutableListOf<SignedBlob>()

        val rm = RotationManager(
            clientId = identity.clientId,
            identitySpki = identity.publicKeySpki,
            identitySign = identity::sign,
            trust = trust,
            mintOperational = { e -> ops.getOrPut(e) { SoftwareOperationalSigner.generate(identity.clientId, e) } },
            mintHpke = { e -> hpkes.getOrPut(e) { Hpke.generateKeyPair().publicKeyset } },
            // Mirror production: the activation callback swaps the live signer AND advances the epoch counter.
            onActivate = { s, e -> activated.add(s to e); trust.advanceSelfEpoch(e) },
            onRetire = { r, keep -> retired.add(r to keep) },
            publish = { b -> published.add(b) },
            pushE2E = { b -> pushed.add(b) },
            now = { now },
            leadMillis = 100,
            overlapMillis = RotationManager.MIN_OVERLAP_MS,
            graceMillis = 50,
            lifetimeMillis = 1000,
        )
    }

    @Test
    fun fullRotation_prewarmActivateRetire() = runBlocking {
        val r = Rig(start = 1_000L)

        // PRE-WARM: mint N+1, re-publish N (finite notAfter) + publish N+1, push N+1 E2E, persist the schedule.
        val target = r.rm.beginRotation()
        assertEquals(2, target)
        assertNotNull(r.trust.pendingRotation())
        assertEquals("not yet activated — still signing with epoch 1", 1, r.trust.selfEpoch())
        assertEquals("N+1 pre-warmed E2E", 1, r.pushed.size)
        assertEquals("re-published N + published N+1", 2, r.published.size)
        assertNull("a second beginRotation while one is pending is a no-op", r.rm.beginRotation())

        // Before notBefore: nothing happens.
        r.now = 1_000L + 50
        r.rm.tick()
        assertEquals(1, r.trust.selfEpoch())
        assertTrue(r.activated.isEmpty())

        // ACTIVATE at notBefore: swap to epoch 2; epoch 1 is still accepted (no retire yet).
        r.now = 1_000L + 100
        r.rm.tick()
        assertEquals(2, r.trust.selfEpoch())
        assertEquals(1, r.activated.size)
        assertEquals(2, r.activated.single().second)
        assertTrue("not retired during the overlap", r.retired.isEmpty())

        // Just before the retire boundary: still no retirement.
        r.now = r.trust.pendingRotation()!!.retireRetiredAt - 1
        r.rm.tick()
        assertTrue(r.retired.isEmpty())
        assertNotNull(r.trust.pendingRotation())

        // RETIRE at notAfter(N) + grace: raise the floor to 2, destroy epoch-1 keys (keep {2}), clear pending.
        r.now = r.trust.pendingRotation()!!.retireRetiredAt
        r.rm.tick()
        assertEquals(listOf(1 to setOf(2)), r.retired)
        assertNull(r.trust.pendingRotation())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOverlapShorterThanRelayTtl() {
        val id = newSigner()
        RotationManager(
            clientId = id.clientId,
            identitySpki = id.publicKeySpki,
            identitySign = id::sign,
            trust = FakeTrustState(),
            mintOperational = { SoftwareOperationalSigner.generate(id.clientId, it) },
            mintHpke = { Hpke.generateKeyPair().publicKeyset },
            onActivate = { _, _ -> },
            onRetire = { _, _ -> },
            publish = { },
            pushE2E = { },
            overlapMillis = RotationManager.MIN_OVERLAP_MS - 1, // too short → would drop in-flight notifications
        )
    }
}

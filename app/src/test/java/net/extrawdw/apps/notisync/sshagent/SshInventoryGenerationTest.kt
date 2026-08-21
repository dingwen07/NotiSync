package net.extrawdw.apps.notisync.sshagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SshInventoryGenerationTest {
    @Test
    fun canonicalizesLegacyUuidTextWithoutChangingItsBits() {
        assertEquals(
            "b59ebab45fc040d0b1de27e20f21cf80",
            SshInventoryGeneration.canonicalize("b59ebab4-5fc0-40d0-b1de-27e20f21cf80"),
        )
    }

    @Test
    fun preservesCanonicalGeneration() {
        val generation = "b59ebab45fc040d0b1de27e20f21cf80"
        assertEquals(generation, SshInventoryGeneration.canonicalize(generation))
    }

    @Test
    fun replacesUnrecoverableInvalidGeneration() {
        val repaired = SshInventoryGeneration.canonicalize("not-an-inventory-generation")
        assertNotEquals("not-an-inventory-generation", repaired)
        assertTrue(SshInventoryGeneration.isCanonical(repaired))
    }
}

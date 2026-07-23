package net.extrawdw.apps.notisync.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenControllerLabelTest {
    @Test
    fun labelAlwaysIncludesPeerIdentity() {
        assertEquals("Workstation (a3b2…f8c1)", ScreenControllerLabel.create("Workstation", "a3b2…f8c1"))
        assertEquals("a3b2…f8c1", ScreenControllerLabel.create(null, "a3b2…f8c1"))
    }

    @Test
    fun intentLabelIsSingleLineAndBounded() {
        val label = ScreenControllerLabel.fromIntent("Desk\n\u202e" + "x".repeat(200))
        assertTrue(requireNotNull(label).length <= 96)
        assertTrue('\n' !in label)
        assertTrue('\u202e' !in label)

        val formatted = ScreenControllerLabel.create("x".repeat(200), "a3b2…f8c1")
        assertTrue(formatted.length <= 96)
        assertTrue(formatted.endsWith("(a3b2…f8c1)"))
    }
}

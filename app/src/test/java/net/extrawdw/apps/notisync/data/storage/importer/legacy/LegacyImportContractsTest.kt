package net.extrawdw.apps.notisync.data.storage.importer.legacy

import java.util.Arrays
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LegacyImportContractsTest {
    @Test
    fun digestFramingIsDeterministicAndLengthDelimited() {
        fun digest(first: String, second: String): ByteArray = LegacyDigestAccumulator().run {
            text("fixture")
            text(first)
            text(second)
            digest()
        }

        assertArrayEquals(digest("ab", "c"), digest("ab", "c"))
        // A length-delimited digest must not collapse these two field boundaries.
        assertFalse(Arrays.equals(digest("ab", "c"), digest("a", "bc")))
    }
}

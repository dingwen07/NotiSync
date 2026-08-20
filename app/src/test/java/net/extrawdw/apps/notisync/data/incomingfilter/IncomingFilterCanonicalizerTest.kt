package net.extrawdw.apps.notisync.data.incomingfilter

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class IncomingFilterCanonicalizerTest {
    @Test
    fun versionOneGoldenDigestsAndUnsignedOrderAreStable() {
        val canonical = IncomingFilterCanonicalizer.canonicalize(
            listOf(
                IncomingFilterRuleValue(
                    CanonicalIncomingFilterOrigin.ANDROID_LOCAL,
                    appId = "com.example",
                    channelId = "updates",
                ),
                IncomingFilterRuleValue(
                    CanonicalIncomingFilterOrigin.IOS_ANCS,
                    appId = "com.apple.mail",
                    channelId = null,
                ),
            ),
        )

        assertEquals(IncomingFilterCanonicalizer.VERSION, 1)
        assertEquals(listOf("com.apple.mail", "com.example"), canonical.rules.map { it.value.appId })
        assertEquals(listOf(0, 1), canonical.rules.map { it.position })
        assertArrayEquals(
            hex("684a5043f1c906dffd7b58cac783d9e902bbc05ea9ca8a746c0b3cc1783c250b"),
            canonical.rules[0].digestCopy(),
        )
        assertArrayEquals(
            hex("9f119ae6307022ea9367691df65e25c86c8e431d8cb6598925d4f03394210122"),
            canonical.rules[1].digestCopy(),
        )
        assertArrayEquals(
            hex("d8e45935a9229f08f526ca933f2fea6696f4424e8a2267ef09b73fd0798b2882"),
            canonical.digestCopy(),
        )
    }

    @Test
    fun permutationAndByteIdenticalDuplicatesHaveOneSemanticIdentity() {
        val android = IncomingFilterRuleValue(
            CanonicalIncomingFilterOrigin.ANDROID_LOCAL,
            appId = "com.example",
            channelId = "updates",
        )
        val ios = IncomingFilterRuleValue(
            CanonicalIncomingFilterOrigin.IOS_ANCS,
            appId = "com.apple.mail",
            channelId = null,
        )

        val first = IncomingFilterCanonicalizer.canonicalize(listOf(android, ios, android))
        val second = IncomingFilterCanonicalizer.canonicalize(listOf(ios, android))

        assertEquals(2, first.rules.size)
        assertArrayEquals(first.digestCopy(), second.digestCopy())
        first.rules.zip(second.rules).forEach { (left, right) ->
            assertEquals(left.value, right.value)
            assertArrayEquals(left.digestCopy(), right.digestCopy())
        }
    }

    @Test
    fun exactStringsAreNotTrimmedCaseFoldedOrUnicodeNormalized() {
        fun digest(appId: String) = IncomingFilterCanonicalizer.canonicalize(
            listOf(IncomingFilterRuleValue(CanonicalIncomingFilterOrigin.ANDROID_LOCAL, appId, null)),
        ).digestCopy()

        assertFalse(digest("Example").contentEquals(digest("example")))
        assertFalse(digest("e\u0301").contentEquals(digest("\u00e9")))
        assertFalse(digest("example").contentEquals(digest(" example")))
    }

    @Test
    fun invalidTupleControlsAndUnpairedSurrogatesFailClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            IncomingFilterCanonicalizer.canonicalize(
                listOf(IncomingFilterRuleValue(CanonicalIncomingFilterOrigin.ANDROID_LOCAL, null, "channel")),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            IncomingFilterCanonicalizer.canonicalize(
                listOf(IncomingFilterRuleValue(CanonicalIncomingFilterOrigin.IOS_ANCS, "app", "channel")),
            )
        }
        listOf("bad\nvalue", "bad\uD800value", "bad\uDC00value").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                IncomingFilterCanonicalizer.canonicalize(
                    listOf(IncomingFilterRuleValue(CanonicalIncomingFilterOrigin.ANDROID_LOCAL, value, null)),
                )
            }
        }
    }

    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

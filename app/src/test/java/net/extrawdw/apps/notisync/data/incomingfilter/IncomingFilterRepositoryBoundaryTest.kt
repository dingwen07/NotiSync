package net.extrawdw.apps.notisync.data.incomingfilter

import java.lang.reflect.Type
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingFilterRepositoryBoundaryTest {
    @Test
    fun digestAndRuleListsAreDefensivelyCopied() {
        val sourceDigest = ByteArray(IncomingFilterLimits.DIGEST_BYTES) { it.toByte() }
        val digest = IncomingFilterDigest.of(sourceDigest)
        sourceDigest.fill(99)
        assertArrayEquals(ByteArray(IncomingFilterLimits.DIGEST_BYTES) { it.toByte() }, digest.copyBytes())

        val rule = IncomingFilterRuleSpec(IncomingFilterOrigin.ANDROID_LOCAL, "com.example", "updates")
        val sourceRules = mutableListOf(rule)
        val update = IncomingFilterUpdate("peer", 10, 20, sourceRules)
        sourceRules.clear()
        assertEquals(listOf(rule), update.rules)
        assertNotSame(sourceRules, update.rules)
    }

    @Test
    fun projectionFencesOlderAndSameVersionConflictingEmissions() {
        val projection = IncomingFilterProjection()
        val first = snapshot("peer", updatedAt = 10, appId = "com.first")
        val newer = snapshot("peer", updatedAt = 20, appId = "com.newer")
        val sameVersionConflict = snapshot("peer", updatedAt = 20, appId = "com.other")
        val older = snapshot("peer", updatedAt = 10, appId = "com.old")

        assertEquals(IncomingFilterProjectionResult.APPLIED, projection.accept(first))
        assertEquals(IncomingFilterProjectionResult.APPLIED, projection.accept(newer))
        assertEquals(IncomingFilterProjectionResult.CONFLICT, projection.accept(sameVersionConflict))
        assertEquals(IncomingFilterProjectionResult.STALE, projection.accept(older))
        assertEquals(newer, projection.filterFor("peer"))
    }

    @Test
    fun removalLeavesFenceAgainstDelayedFlowAndAllowsAStrictlyNewerReplacement() {
        val projection = IncomingFilterProjection()
        val current = snapshot("peer", updatedAt = 10, appId = "com.current")
        val delayed = snapshot("peer", updatedAt = 9, appId = "com.delayed")
        val replacement = snapshot("peer", updatedAt = 11, appId = "com.replacement")

        projection.accept(current)
        assertEquals(IncomingFilterProjectionResult.APPLIED, projection.remove("peer", current))
        assertEquals(IncomingFilterProjectionResult.STALE, projection.accept(delayed))
        assertEquals(IncomingFilterProjectionResult.APPLIED, projection.accept(replacement))
        assertEquals(replacement, projection.filterFor("peer"))
    }

    @Test
    fun ownerWriteCanRestoreExactSameSnapshotAfterDeleteButFlowCannot() {
        val projection = IncomingFilterProjection()
        val current = snapshot("peer", updatedAt = 10, appId = "com.current")

        assertEquals(IncomingFilterProjectionResult.APPLIED, projection.accept(current))
        assertEquals(IncomingFilterProjectionResult.APPLIED, projection.remove("peer", current))
        assertEquals(IncomingFilterProjectionResult.UNCHANGED, projection.accept(current))
        assertEquals(IncomingFilterProjectionResult.APPLIED, projection.acceptOwnerWrite(current))
        assertEquals(current, projection.filterFor("peer"))
    }

    @Test
    fun recipientMatchingPreservesOriginDeviceAppAndChannelSemantics() {
        val projection = IncomingFilterProjection()
        projection.accept(snapshot("device", 1, appId = null))
        projection.accept(snapshot("app", 1, appId = "com.example"))
        projection.accept(snapshot("channel", 1, appId = "com.example", channelId = "updates"))
        projection.accept(snapshot("ios", 1, origin = IncomingFilterOrigin.IOS_ANCS, appId = "com.apple.mail"))

        assertEquals(
            setOf("device", "app", "channel"),
            projection.recipientsToExclude(IncomingFilterOrigin.ANDROID_LOCAL, "com.example", "updates"),
        )
        assertEquals(
            setOf("device", "app"),
            projection.recipientsToExclude(IncomingFilterOrigin.ANDROID_LOCAL, "com.example", "other"),
        )
        assertEquals(
            setOf("device"),
            projection.recipientsToExclude(IncomingFilterOrigin.ANDROID_LOCAL, "com.other", "updates"),
        )
        assertEquals(
            setOf("ios"),
            projection.recipientsToExclude(
                IncomingFilterOrigin.IOS_ANCS,
                packageName = "com.fallback",
                iosBundleId = " com.apple.mail ",
                channelId = null,
            ),
        )
        assertTrue(
            projection.recipientsToExclude(IncomingFilterOrigin.ANDROID_LOCAL, "  ", "  ").contains("device"),
        )
    }

    @Test
    fun protocolAndRoomTypesDoNotLeakThroughRepositoryInterface() {
        val forbidden = listOf(
            "androidx.room",
            "androidx.sqlite",
            "net.extrawdw.apps.notisync.data.storage",
            "net.extrawdw.notisync.protocol",
            "androidx.datastore",
        )
        val types = IncomingFilterRepository::class.java.declaredMethods.flatMap { method ->
            listOf<Type>(method.genericReturnType) + method.genericParameterTypes.toList()
        }
        assertTrue(types.isNotEmpty())
        types.forEach { type ->
            assertFalse("forbidden type leaked: $type", forbidden.any { type.typeName.contains(it) })
        }
        assertSame(IncomingFilterProjection::class.java, IncomingFilterRepository::class.java
            .getMethod("getProjection").returnType)
    }

    private fun snapshot(
        requester: String,
        updatedAt: Long,
        origin: IncomingFilterOrigin = IncomingFilterOrigin.ANDROID_LOCAL,
        appId: String?,
        channelId: String? = null,
    ): IncomingFilterSnapshot {
        val canonical = IncomingFilterCanonicalizer.canonicalize(
            listOf(
                IncomingFilterRuleValue(
                    origin = when (origin) {
                        IncomingFilterOrigin.ANDROID_LOCAL -> CanonicalIncomingFilterOrigin.ANDROID_LOCAL
                        IncomingFilterOrigin.IOS_ANCS -> CanonicalIncomingFilterOrigin.IOS_ANCS
                    },
                    appId = appId,
                    channelId = channelId,
                ),
            ),
        )
        return IncomingFilterSnapshot(
            requesterClientId = requester,
            canonicalizationVersion = IncomingFilterCanonicalizer.VERSION,
            updatedAt = updatedAt,
            receivedAt = updatedAt + 1,
            ruleSetDigest = IncomingFilterDigest.of(canonical.digestCopy()),
            rules = canonical.rules.map { rule ->
                IncomingFilterRule(
                    position = rule.position,
                    origin = when (rule.value.origin) {
                        CanonicalIncomingFilterOrigin.ANDROID_LOCAL -> IncomingFilterOrigin.ANDROID_LOCAL
                        CanonicalIncomingFilterOrigin.IOS_ANCS -> IncomingFilterOrigin.IOS_ANCS
                    },
                    appId = rule.value.appId,
                    channelId = rule.value.channelId,
                    digest = IncomingFilterDigest.of(rule.digestCopy()),
                )
            },
        )
    }
}

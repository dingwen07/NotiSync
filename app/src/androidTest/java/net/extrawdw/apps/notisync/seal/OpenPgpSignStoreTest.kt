package net.extrawdw.apps.notisync.seal

import android.content.Context
import android.content.ContentValues
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.extrawdw.apps.notisync.data.HistoryDirection
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDatabase
import net.extrawdw.apps.notisync.testsupport.RoomStorageTestContext
import net.extrawdw.apps.notisync.testsupport.initializeOperationalTestDatabase
import java.security.MessageDigest
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.OpenPgpObjectKind
import net.extrawdw.notisync.protocol.OpenPgpRejectReason
import net.extrawdw.notisync.protocol.OpenPgpSignAction
import net.extrawdw.notisync.protocol.OpenPgpSignLimits
import net.extrawdw.notisync.protocol.OpenPgpSignSync
import net.extrawdw.notisync.protocol.ProtocolCodec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OpenPgpSignStoreTest {
    private val context: Context = RoomStorageTestContext(
        ApplicationProvider.getApplicationContext(),
        "openpgp-sign-store",
    )

    @Before
    fun clearBefore() {
        context.deleteDatabase(DB_NAME)
        initializeOperationalTestDatabase(context)
    }

    @After
    fun clearAfter() {
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun decisionAndResponseSurviveReopenWithoutResigning() {
        val request = request()
        val sender = request.requesterClientId
        val store = OpenPgpSignStore(context)
        assertEquals(OpenPgpAcceptResult.STORED, store.accept(request, sender, 1_100))
        assertTrue(store.approve(request.requestId, 1_200))
        assertTrue(store.storeResult(request.requestId, ARMOR, 1_300))
        assertEquals(OpenPgpRequestState.SIGNED_PENDING_SEND, store.find(request.requestId)?.state)
        store.close()

        val reopened = OpenPgpSignStore(context)
        assertEquals(1, reopened.pendingResponses().size)
        assertEquals(OpenPgpAcceptResult.DUPLICATE, reopened.accept(request, sender, 1_400))
        assertTrue(reopened.markSent(request.requestId, 1_500))
        val sent = reopened.find(request.requestId)
        assertArrayEquals(request.payload, sent?.request?.payload)
        assertEquals(ARMOR, sent?.response?.signatureArmor)
        assertTrue(reopened.pendingResponses().isEmpty())
        assertEquals(OpenPgpRequestResult.APPROVED, sent?.result)
        assertEquals("Store test\n", sent?.commit?.message)
        assertEquals(request.payload?.size, sent?.commit?.payloadBytes)
        assertEquals("C:\\work\\NotiSync", sent?.request?.workingDirectory)
        reopened.close()
    }

    @Test
    fun conflictingReplayIsRejectedAndCancellationWinsLateProviderResult() {
        val request = request()
        val store = OpenPgpSignStore(context)
        assertEquals(OpenPgpAcceptResult.STORED, store.accept(request, request.requesterClientId, 1_100))
        assertEquals(
            OpenPgpAcceptResult.CONFLICT,
            store.accept(request.copy(primaryKeyId = "1111111111111111"), request.requesterClientId, 1_200),
        )
        assertTrue(store.approve(request.requestId, 1_300))
        assertTrue(store.markProviderInteraction(request.requestId, 1_400))
        assertTrue(store.cancel(request.requestId, request.requesterClientId, 1_500))
        assertFalse(store.storeResult(request.requestId, ARMOR, 1_600))
        val canceled = store.find(request.requestId)
        assertEquals(OpenPgpRequestState.CANCELLED, canceled?.state)
        assertEquals(OpenPgpRequestResult.CANCELED, canceled?.result)
        assertNotNull(canceled?.commit)
        store.close()
    }

    @Test
    fun rejectionIsPersistedForOutboxAndExpiryPreservesFullRecord() {
        val rejected = request("11111111111111111111111111111111")
        val expired = request("22222222222222222222222222222222")
        val store = OpenPgpSignStore(context)
        store.accept(rejected, rejected.requesterClientId, 1_100)
        store.accept(expired, expired.requesterClientId, 1_100)
        assertTrue(store.storeReject(rejected.requestId, OpenPgpRejectReason.USER_REJECTED, 1_200))
        val rejectedStored = store.find(rejected.requestId)
        assertEquals(OpenPgpRequestState.REJECTED_PENDING_SEND, rejectedStored?.state)
        assertEquals(OpenPgpRequestResult.REJECTED, rejectedStored?.result)
        assertNotNull(rejectedStored?.commit)

        assertTrue(store.markExpired(expired.requestId, expired.expiresAt + 1))
        val expiredStored = store.find(expired.requestId)
        assertEquals(OpenPgpRequestState.EXPIRED, expiredStored?.state)
        assertEquals(OpenPgpRequestResult.EXPIRED, expiredStored?.result)
        assertArrayEquals(expired.payload, expiredStored?.request?.payload)
        assertNotNull(expiredStored?.commit)
        store.close()
    }

    @Test
    fun cleanupWithoutNewTrafficExpiresReviewsAndPreservesResponsesUntilTheirGraceDeadline() {
        val pending = request("1".repeat(32))
        val rejected = request("2".repeat(32))
        val cancelled = request("3".repeat(32))
        OpenPgpSignStore(context).use { store ->
            for (request in listOf(pending, rejected, cancelled)) {
                store.accept(request, request.requesterClientId, 1_100)
            }
            assertTrue(store.storeReject(rejected.requestId, OpenPgpRejectReason.USER_REJECTED, 1_200))
            assertTrue(store.cancel(cancelled.requestId, cancelled.requesterClientId, 1_200))

            assertTrue(store.expireDue(pending.expiresAt).isEmpty())
            assertEquals(listOf(pending.requestId), store.expireDue(pending.expiresAt + 1))
            assertEquals(OpenPgpRequestState.EXPIRED, store.requests.value.first { it.request.requestId == pending.requestId }.state)
            assertArrayEquals(pending.payload, store.find(pending.requestId)?.request?.payload)
            assertEquals(OpenPgpRequestState.REJECTED_PENDING_SEND, store.find(rejected.requestId)?.state)
            assertNotNull(store.find(rejected.requestId)?.response)

            val graceDeadline = rejected.expiresAt + OpenPgpSignLimits.CLOCK_SKEW_MILLIS
            assertTrue(store.expireDue(graceDeadline).isEmpty())
            assertEquals(listOf(rejected.requestId), store.expireDue(graceDeadline + 1))
            assertEquals(OpenPgpRejectReason.USER_REJECTED, store.find(rejected.requestId)?.response?.rejectReason)
            assertTrue(store.pendingResponses().isEmpty())
            assertEquals(OpenPgpRequestState.CANCELLED, store.find(cancelled.requestId)?.state)
            assertTrue(store.expireDue(graceDeadline + 2).isEmpty())
        }
    }

    @Test
    fun annotatedTagAndPayloadSurviveReopenAfterDelivery() {
        val request = tagRequest()
        val store = OpenPgpSignStore(context)
        assertEquals(OpenPgpAcceptResult.STORED, store.accept(request, request.requesterClientId, 1_100))
        assertTrue(store.storeReject(request.requestId, OpenPgpRejectReason.USER_REJECTED, 1_200))
        assertTrue(store.markSent(request.requestId, 1_300))
        store.close()

        val reopened = OpenPgpSignStore(context)
        val stored = reopened.find(request.requestId)
        assertArrayEquals(request.payload, stored?.request?.payload)
        assertNull(stored?.commit)
        assertEquals("v1.0.0", stored?.tag?.tagName)
        assertEquals("commit", stored?.tag?.objectType)
        assertEquals("Release v1.0.0\n", stored?.tag?.message)
        assertEquals(request.payload?.size, stored?.tag?.payloadBytes)
        reopened.close()
    }

    @Test
    fun largeCommitRetainsEveryParentHeaderAndMessageAfterCancellationAndReopen() {
        val parents = List(70) { (it + 1).toString(16).padStart(40, '0') }
        val headerValue = "v".repeat(3_000)
        val message = "Full history\n\n" + "m".repeat(24_000) + "\n"
        val payload = buildString {
            append("tree ${"f".repeat(40)}\n")
            parents.forEach { append("parent $it\n") }
            append("author Example <example@example.com> 1700000000 +0000\n")
            append("committer Example <example@example.com> 1700000000 +0000\n")
            repeat(70) { append("x-$it $headerValue\n") }
            append('\n')
            append(message)
        }.encodeToByteArray()
        val request = request().copy(payload = payload, payloadSha256 = MessageDigest.getInstance("SHA-256").digest(payload))
        OpenPgpSignStore(context).use { store ->
            assertEquals(OpenPgpAcceptResult.STORED, store.accept(request, request.requesterClientId, 1_100))
            assertTrue(store.cancel(request.requestId, request.requesterClientId, 1_200))
            val summary = store.requests.value.single()
            assertNull(summary.request.payload)
            assertNull(summary.commit)
            assertNull(summary.response)
            assertEquals("Full history", summary.summary?.title)
            assertEquals(parents.first(), summary.summary?.reference)
            store.readableDatabase.rawQuery(
                "SELECT legacy_details_json FROM seal_requests WHERE request_id = ?", arrayOf(request.requestId),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
            }
        }
        OpenPgpSignStore(context).use { store ->
            val stored = requireNotNull(store.find(request.requestId))
            assertArrayEquals(payload, stored.request.payload)
            val commit = requireNotNull(stored.commit)
            assertEquals(parents, commit.parentIds)
            assertEquals(message, commit.message)
            assertEquals(70, commit.extraHeaders.size)
            assertTrue(commit.extraHeaders.all { it.value == headerValue })
            assertFalse(commit.legacyTruncated)
        }
    }

    @Test
    fun migratedLegacyCommitAndTagDetailsRemainReadableWithoutSynthesizingSigningPayloads() {
        val commitRequest = request()
        val tagRequest = tagRequest()
        val commit = GitCommitDetails(
            treeId = "f".repeat(40), parentIds = listOf("1".repeat(40), "2".repeat(40)),
            author = "Previously truncated author", committer = "Previously truncated committer",
            message = "Retained legacy commit\nBody", extraHeaders = listOf(GitCommitHeader("x-extra", "value")),
            payloadBytes = 90_000, legacyTruncated = true,
        )
        val tag = GitTagDetails(
            objectId = "a".repeat(40), objectType = "commit", tagName = "legacy-v1",
            tagger = "Previously truncated tagger", message = "Retained legacy tag",
            payloadBytes = 70_000, legacyTruncated = true,
        )
        OpenPgpSignStore(context).use { store ->
            listOf(commitRequest, tagRequest).forEach {
                store.accept(it, it.requesterClientId, 1_100)
                store.cancel(it.requestId, it.requesterClientId, 1_200)
            }
            fun replaceWithLegacy(requestId: String, json: String, summary: OpenPgpRequestSummary) {
                store.writableDatabase.update("seal_requests", ContentValues().apply {
                    putNull("payload")
                    put("legacy_details_json", json)
                    put("summary_title", summary.title)
                    put("summary_reference", summary.reference)
                    put("summary_identity", summary.identity)
                }, "request_id = ?", arrayOf(requestId))
            }
            replaceWithLegacy(commitRequest.requestId, ProtocolCodec.encodeToJson(commit), commit.toSummary())
            replaceWithLegacy(tagRequest.requestId, ProtocolCodec.encodeToJson(tag), tag.toSummary())
        }
        OpenPgpSignStore(context).use { store ->
            val storedCommit = requireNotNull(store.find(commitRequest.requestId))
            val storedTag = requireNotNull(store.find(tagRequest.requestId))
            assertNull(storedCommit.request.payload)
            assertNull(storedTag.request.payload)
            assertEquals(commit, storedCommit.commit)
            assertEquals(tag, storedTag.tag)
            assertFalse(store.approve(commitRequest.requestId, 1_300))
            assertFalse(store.approve(tagRequest.requestId, 1_300))
            assertEquals("Retained legacy commit", store.requests.value.first {
                it.request.requestId == commitRequest.requestId
            }.summary?.title)
            assertTrue(store.requests.value.all { it.commit == null && it.tag == null && it.request.payload == null })
        }
    }

    @Test
    fun malformedCommitCannotLeaveAHalfWrittenPendingRecord() {
        val payload = "not a git object".encodeToByteArray()
        val malformedRequest = request().copy(payload = payload, payloadSha256 = MessageDigest.getInstance("SHA-256").digest(payload))
        OpenPgpSignStore(context).use { store ->
            var failed = false
            try {
                store.accept(malformedRequest, malformedRequest.requesterClientId, 1_100)
            } catch (_: IllegalArgumentException) {
                failed = true
            }
            assertTrue(failed)
            assertNull(store.find(malformedRequest.requestId))
            assertTrue(store.requests.value.isEmpty())
            assertEquals(OpenPgpAcceptResult.STORED, store.accept(request(), malformedRequest.requesterClientId, 1_200))
        }
    }

    @Test
    fun terminalLedgerIsNotPrunedByAgeOrCountAndOldActiveRecordsRemainVisible() {
        val pending = request()
        OpenPgpSignStore(context).use { store ->
            store.accept(pending, pending.requesterClientId, 1_100)
            store.writableDatabase.execSQL(
                """
                WITH RECURSIVE records(n) AS (VALUES(1) UNION ALL SELECT n + 1 FROM records WHERE n < 10001)
                INSERT INTO seal_requests(
                    request_id,protocol_version,requester_client_id,sender_client_id,primary_key_id,
                    issued_at,expires_at,payload_sha256,object_kind,payload,state,updated_at,result,working_directory
                )
                SELECT printf('%032x', n),protocol_version,requester_client_id,sender_client_id,primary_key_id,
                    issued_at,expires_at,payload_sha256,object_kind,payload,'SENT',2000+n,'APPROVED',working_directory
                FROM records CROSS JOIN seal_requests WHERE request_id = '${pending.requestId}'
                """.trimIndent(),
            )
        }
        OpenPgpSignStore(context).use { store ->
            assertNotNull(store.find("1".padStart(32, '0')))
            assertTrue(store.requests.value.any { it.request.requestId == pending.requestId })
            assertEquals(51, store.requests.value.size)
            assertEquals(1, store.historyPage(cursor = OpenPgpHistoryCursor(2_002, "2".padStart(32, '0'))).items.size)
            store.readableDatabase.rawQuery("SELECT COUNT(*) FROM seal_requests", emptyArray()).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(10_002, cursor.getInt(0))
            }
        }
    }

    @Test
    fun historyUsesStableKeysetPagesAndLoadsFullDetailsOnlyForSelectedRecord() {
        val original = request()
        val payload = requireNotNull(original.payload) + ("body ".repeat(10_000) + "\n").encodeToByteArray()
        val source = original.copy(payload = payload, payloadSha256 = MessageDigest.getInstance("SHA-256").digest(payload))
        OpenPgpSignStore(context).use { store ->
            store.accept(source, source.requesterClientId, 1_100)
            store.approve(source.requestId, 1_200)
            store.storeResult(source.requestId, ARMOR, 1_300)
            store.writableDatabase.execSQL(
                """
                WITH RECURSIVE records(n) AS (VALUES(1) UNION ALL SELECT n + 1 FROM records WHERE n < 123)
                INSERT INTO seal_requests(
                    request_id,protocol_version,requester_client_id,sender_client_id,primary_key_id,
                    issued_at,expires_at,payload_sha256,object_kind,payload,state,updated_at,result,working_directory,
                    response_action,response_signature_armor,response_action_at,summary_title,summary_reference,summary_identity
                )
                SELECT printf('%032x', n),protocol_version,requester_client_id,sender_client_id,primary_key_id,
                    issued_at,expires_at,payload_sha256,object_kind,payload,'SENT',2000,'APPROVED',working_directory,
                    response_action,response_signature_armor,response_action_at,summary_title,summary_reference,summary_identity
                FROM records CROSS JOIN seal_requests WHERE request_id = '${source.requestId}'
                """.trimIndent(),
            )
        }
        OpenPgpSignStore(context).use { store ->
            val first = store.historyPage()
            assertEquals(50, first.items.size)
            assertNotNull(first.nextCursor)
            for ((position, arguments) in listOf(
                "" to arrayOf("51"),
                " AND (updated_at,request_id) < (?,?)" to arrayOf("2000", first.nextCursor!!.requestId, "51"),
            )) {
                store.readableDatabase.rawQuery(
                    "EXPLAIN QUERY PLAN SELECT request_id,state,updated_at,summary_title FROM seal_requests " +
                        "WHERE state NOT IN ('PENDING_REVIEW','USER_APPROVED','PROVIDER_INTERACTION'," +
                        "'SIGNED_PENDING_SEND','REJECTED_PENDING_SEND')$position " +
                        "ORDER BY updated_at DESC,request_id DESC LIMIT ?",
                    arguments,
                ).use { cursor ->
                    val plan = buildList {
                        while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("detail")))
                    }.joinToString("\n")
                    assertTrue(plan, plan.contains("seal_requests_history_idx"))
                    assertFalse(plan, plan.contains("TEMP B-TREE"))
                    if (position.isNotEmpty()) assertTrue(plan, plan.contains("SEARCH seal_requests"))
                }
            }
            assertEquals(51, store.requests.value.size)
            assertTrue(store.requests.value.any { it.request.requestId == source.requestId })
            assertArrayEquals(source.payloadSha256, store.requests.value.first {
                it.request.requestId == source.requestId
            }.request.payloadSha256)
            val initialVersion = store.changeVersion.value

            // A newer insertion and a deletion before the cursor cannot shift older page boundaries.
            val newer = request("f".repeat(32))
            assertEquals(OpenPgpAcceptResult.STORED, store.accept(newer, newer.requesterClientId, 3_000))
            assertTrue(store.cancel(newer.requestId, newer.requesterClientId, 3_100))
            assertTrue(store.changeVersion.value > initialVersion)
            store.writableDatabase.delete("seal_requests", "request_id = ?", arrayOf(first.items.first().request.requestId))

            val second = store.historyPage(cursor = first.nextCursor)
            val third = store.historyPage(cursor = second.nextCursor)
            assertEquals(50, second.items.size)
            assertEquals(23, third.items.size)
            assertNotNull(second.nextCursor)
            assertNull(third.nextCursor)
            // Refresh around an off-page visible row; both directions preserve ties and its identity.
            val pivot = second.items[25]
            val anchor = OpenPgpHistoryCursor(pivot.updatedAt, pivot.request.requestId)
            val before = store.historyPage(25, anchor, HistoryDirection.NEWER)
            val fromAnchor = store.historyPage(25, anchor, HistoryDirection.OLDER, includeCursor = true)
            assertEquals(second.items.map { it.request.requestId }, (before.items + fromAnchor.items).map {
                it.request.requestId
            })
            assertNotNull(before.nextCursor)
            assertEquals(pivot.request.requestId, store.historyPage(
                1, anchor, HistoryDirection.NEWER, includeCursor = true,
            ).items.single().request.requestId)
            assertTrue(store.historyPage(
                50, OpenPgpHistoryCursor(3_100, newer.requestId), HistoryDirection.NEWER,
            ).items.isEmpty())
            val all = first.items + second.items + third.items
            assertEquals((123 downTo 1).map { it.toString(16).padStart(32, '0') }, all.map { it.request.requestId })
            assertTrue(all.all {
                it.request.payload == null && it.request.payloadSha256.isEmpty() && it.response == null &&
                    it.commit == null && it.tag == null && it.summary?.title == "Store test"
            })

            val oldestId = "1".padStart(32, '0')
            assertFalse(store.requests.value.any { it.request.requestId == oldestId })
            val selected = requireNotNull(store.find(oldestId))
            assertArrayEquals(payload, selected.request.payload)
            assertEquals(ARMOR, selected.response?.signatureArmor)
            assertEquals(payload.size, selected.commit?.payloadBytes)
            assertTrue(requireNotNull(selected.commit).message.endsWith("body \n"))
        }
    }

    private fun request(id: String = "0123456789abcdef0123456789abcdef"): OpenPgpSignSync {
        val payload = (
            "tree 0123456789abcdef0123456789abcdef01234567\n" +
                "author Example <example@example.com> 1700000000 +0000\n" +
                "committer Example <example@example.com> 1700000000 +0000\n\n" +
                "Store test\n"
            ).encodeToByteArray()
        return OpenPgpSignSync(
            action = OpenPgpSignAction.REQUEST,
            requestId = id,
            requesterClientId = ClientId("desktop-client"),
            issuedAt = 1_000,
            expiresAt = 100_000,
            primaryKeyId = "89ABCDEF01234567",
            payloadSha256 = MessageDigest.getInstance("SHA-256").digest(payload),
            objectKind = OpenPgpObjectKind.GIT_COMMIT,
            payload = payload,
            workingDirectory = "C:\\work\\NotiSync",
        )
    }

    private fun tagRequest(): OpenPgpSignSync {
        val payload = (
            "object 0123456789abcdef0123456789abcdef01234567\n" +
                "type commit\n" +
                "tag v1.0.0\n" +
                "tagger Example <example@example.com> 1700000000 +0000\n\n" +
                "Release v1.0.0\n"
            ).encodeToByteArray()
        return OpenPgpSignSync(
            action = OpenPgpSignAction.REQUEST,
            requestId = "33333333333333333333333333333333",
            requesterClientId = ClientId("desktop-client"),
            issuedAt = 1_000,
            expiresAt = 100_000,
            primaryKeyId = "89ABCDEF01234567",
            payloadSha256 = MessageDigest.getInstance("SHA-256").digest(payload),
            objectKind = OpenPgpObjectKind.GIT_TAG,
            payload = payload,
            workingDirectory = "C:\\work\\NotiSync",
        )
    }

    private companion object {
        const val DB_NAME = OperationalDatabase.DATABASE_NAME
        const val ARMOR = "-----BEGIN PGP SIGNATURE-----\nfixture\n-----END PGP SIGNATURE-----\n"
    }
}


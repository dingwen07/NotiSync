package net.extrawdw.apps.notisync.sign

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.MessageDigest
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.OpenPgpObjectKind
import net.extrawdw.notisync.protocol.OpenPgpRejectReason
import net.extrawdw.notisync.protocol.OpenPgpSignAction
import net.extrawdw.notisync.protocol.OpenPgpSignSync
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OpenPgpSignStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clearBefore() {
        context.deleteDatabase(DB_NAME)
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
        assertNull(reopened.find(request.requestId)?.request?.payload)
        assertNull(reopened.find(request.requestId)?.encodedResponse)
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
        assertEquals(OpenPgpRequestState.CANCELLED, store.find(request.requestId)?.state)
        store.close()
    }

    @Test
    fun rejectionIsPersistedForOutboxAndExpiryClearsSensitivePayload() {
        val rejected = request("11111111111111111111111111111111")
        val expired = request("22222222222222222222222222222222")
        val store = OpenPgpSignStore(context)
        store.accept(rejected, rejected.requesterClientId, 1_100)
        store.accept(expired, expired.requesterClientId, 1_100)
        assertTrue(store.storeReject(rejected.requestId, OpenPgpRejectReason.USER_REJECTED, 1_200))
        assertEquals(OpenPgpRequestState.REJECTED_PENDING_SEND, store.find(rejected.requestId)?.state)

        assertTrue(store.markExpired(expired.requestId, expired.expiresAt + 1))
        assertEquals(OpenPgpRequestState.EXPIRED, store.find(expired.requestId)?.state)
        assertNull(store.find(expired.requestId)?.request?.payload)
        store.close()
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
        )
    }

    private companion object {
        const val DB_NAME = "openpgp_signing.db"
        const val ARMOR = "-----BEGIN PGP SIGNATURE-----\nfixture\n-----END PGP SIGNATURE-----\n"
    }
}

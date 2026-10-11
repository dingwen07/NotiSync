package net.extrawdw.apps.notisync.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import net.extrawdw.apps.notisync.seal.OpenPgpRequestState
import net.extrawdw.apps.notisync.seal.ReviewContent
import net.extrawdw.apps.notisync.seal.ReviewScreenState
import net.extrawdw.apps.notisync.seal.StoredOpenPgpRequest
import net.extrawdw.apps.notisync.seal.toCommitDetails
import net.extrawdw.apps.notisync.sshkeyprovider.SshProviderRequestKind
import net.extrawdw.apps.notisync.sshkeyprovider.SshProviderRequestState
import net.extrawdw.apps.notisync.sshkeyprovider.SshRequestHistorySnapshot
import net.extrawdw.apps.notisync.sshkeyprovider.SshReviewContent
import net.extrawdw.apps.notisync.sshkeyprovider.SshReviewScreenState
import net.extrawdw.apps.notisync.sshkeyprovider.StoredSshProviderRequest
import net.extrawdw.apps.notisync.ui.theme.NotiSyncTheme
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.OpenPgpObjectKind
import net.extrawdw.notisync.protocol.OpenPgpSignAction
import net.extrawdw.notisync.protocol.OpenPgpSignSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run on the tablet emulator; fixtures never store or approve a real signing request. */
@RunWith(AndroidJUnit4::class)
class RequestDetailLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val width = mutableStateOf(840.dp)

    @Test
    fun columnsRetainDetailStateAcrossResizeAndScrollIndependently() {
        compose.setContent {
            NotiSyncTheme {
                Box(Modifier.requiredWidth(width.value).height(500.dp)) {
                    RequestDetailLayout(
                        contentPadding = PaddingValues(20.dp),
                        useWideLayout = true,
                        summary = { Text("Hero", Modifier.testTag("hero")) },
                    ) {
                        var value by remember { mutableStateOf("") }
                        OutlinedTextField(value, { value = it }, Modifier.testTag("draft"))
                        repeat(20) { Text("Detail $it", Modifier.height(48.dp)) }
                    }
                }
            }
        }
        assertSideBySide("hero", "draft")
        compose.onNodeWithTag("draft").performTextInput("retained value")
        compose.onNodeWithText("Detail 19").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("hero").assertIsDisplayed()

        compose.runOnIdle { width.value = 839.dp }
        compose.onNodeWithTag("draft").performScrollTo().assertTextContains("retained value")
        val hero = compose.onNodeWithTag("hero").fetchSemanticsNode().boundsInRoot
        val detail = compose.onNodeWithTag("draft").fetchSemanticsNode().boundsInRoot
        assertEquals(hero.left, detail.left, 1f)
        assertTrue(detail.top > hero.top)

        compose.runOnIdle { width.value = 840.dp }
        compose.onNodeWithTag("draft").performScrollTo().assertTextContains("retained value")
        assertSideBySide("hero", "draft")
    }

    @Test
    fun sealApprovalRendersWideAndCompactWithReachableActions() {
        val now = System.currentTimeMillis()
        val payload = ("tree 0123456789abcdef0123456789abcdef01234567\n" +
            "parent 6648f0d82d47bafb997f07ea8720e22d89471068\n" +
            "author Example <example@example.com> 1700000000 +0000\n" +
            "committer Example <example@example.com> 1700000000 +0000\n\n" +
            "Improve approval layouts\n\nMake request details easier to review on wider displays.\n").encodeToByteArray()
        val request = StoredOpenPgpRequest(
            request = OpenPgpSignSync(
                action = OpenPgpSignAction.REQUEST,
                requestId = "0123456789abcdef0123456789abcdef",
                requesterClientId = ClientId("desktop"),
                issuedAt = now,
                expiresAt = now + 300_000,
                primaryKeyId = "0123456789ABCDEF",
                payloadSha256 = ByteArray(32),
                objectKind = OpenPgpObjectKind.GIT_COMMIT,
                payload = payload,
                workingDirectory = "/work/NotiSync",
            ),
            senderClientId = ClientId("desktop"),
            state = OpenPgpRequestState.PENDING_REVIEW,
            updatedAt = now,
            commit = payload.toCommitDetails(),
        )
        var approved = 0
        compose.setContent {
            NotiSyncTheme {
                Surface(Modifier.requiredWidth(width.value).height(550.dp)) {
                    ReviewContent(
                        ReviewScreenState.Details(request, "Desktop", null, "Example signing key"),
                        onApprove = { approved++ },
                        onReject = {},
                        onClose = {},
                    )
                }
            }
        }
        assertRequestColumns("Commit")
        compose.onNodeWithText("Approve").assertIsDisplayed()
        capture("seal-approval-wide.png")
        assertInfoInLeftColumn("Approval", "Commit")
        assertInfoInLeftColumn("Request", "Commit")
        capture("seal-approval-wide-info.png")
        compose.runOnIdle { width.value = 412.dp }
        compose.onNodeWithText("Approve").assertIsDisplayed().performClick()
        assertEquals(1, approved)
        capture("seal-approval-compact.png")
    }

    @Test
    fun sshApprovalRendersWideAndCompactWithReachableActions() {
        val now = System.currentTimeMillis()
        val request = StoredSshProviderRequest(
            requestId = "1".repeat(32),
            kind = SshProviderRequestKind.SIGN,
            requesterClientId = ClientId("desktop"),
            requestFingerprint = ByteArray(32),
            history = SshRequestHistorySnapshot(
                requestedAt = now,
                expiresAt = now + 300_000,
                destinationUsername = "developer",
                destinationHost = "example.com",
                payloadSize = 256,
            ),
            state = SshProviderRequestState.PENDING_REVIEW,
            updatedAt = now,
        )
        var approved = 0
        compose.setContent {
            NotiSyncTheme {
                Surface(Modifier.requiredWidth(width.value).height(550.dp)) {
                    SshReviewContent(
                        state = SshReviewScreenState.Details(
                            request = request,
                            rememberChoices = emptySet(),
                            encryptedImport = false,
                            keyPreview = null,
                            keyName = "Development key",
                            requesterName = "Desktop",
                            requesterIdentityKeyFingerprint = null,
                            destinationHostname = "example.com",
                        ),
                        busy = false,
                        onApprove = { approved++ },
                        onReject = {},
                        onRemember = {},
                        onClose = {},
                    )
                }
            }
        }
        assertRequestColumns("SSH Signature")
        compose.onNodeWithText("Approve").assertIsDisplayed()
        capture("ssh-approval-wide.png")
        assertInfoInLeftColumn("Development key", "SSH Signature")
        assertInfoInLeftColumn("Request", "SSH Signature")
        capture("ssh-approval-wide-info.png")
        compose.runOnIdle { width.value = 412.dp }
        compose.onNodeWithText("Approve").assertIsDisplayed().performClick()
        assertEquals(1, approved)
        capture("ssh-approval-compact.png")
    }

    private fun assertSideBySide(first: String, second: String) {
        val left = compose.onNodeWithTag(first).fetchSemanticsNode().boundsInRoot
        val right = compose.onNodeWithTag(second).fetchSemanticsNode().boundsInRoot
        assertTrue(right.left > left.right)
        assertEquals(left.top, right.top, 1f)
    }

    private fun assertRequestColumns(section: String) {
        val hero = compose.onNodeWithText("Awaiting approval").fetchSemanticsNode().boundsInRoot
        val detail = compose.onNodeWithText(section).fetchSemanticsNode().boundsInRoot
        assertTrue(detail.left > hero.right)
        assertTrue(kotlin.math.abs(detail.top - hero.top) < 32f)
    }

    private fun assertInfoInLeftColumn(label: String, rightSection: String) {
        val info = compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val details = compose.onNodeWithText(rightSection).assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        assertTrue(info.right < details.left)
    }

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), name).outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}

package net.extrawdw.apps.notisync.ui

import android.graphics.Bitmap
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import net.extrawdw.apps.notisync.NotiSyncApp
import net.extrawdw.apps.notisync.seal.OpenPgpRequestState
import net.extrawdw.apps.notisync.seal.OpenPgpRequestResult
import net.extrawdw.apps.notisync.seal.SigningRequestDetail
import net.extrawdw.apps.notisync.seal.ReviewContent
import net.extrawdw.apps.notisync.seal.ReviewScreenState
import net.extrawdw.apps.notisync.seal.StoredOpenPgpRequest
import net.extrawdw.apps.notisync.seal.toCommitDetails
import net.extrawdw.apps.notisync.sshkeyprovider.SshProviderRequestKind
import net.extrawdw.apps.notisync.sshkeyprovider.SshProviderRequestOutcome
import net.extrawdw.apps.notisync.sshkeyprovider.SshHistoryRequestDetail
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run on a tablet or landscape phone; fixtures never store or approve a real signing request. */
@RunWith(AndroidJUnit4::class)
class RequestDetailLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val width = mutableStateOf(704.dp)

    @Before
    fun useLandscapeTestActivity() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NotiSyncApp
        checkNotNull(app.awaitGraphReadyBlocking()) { "Application initialization failed" }
        compose.runOnUiThread {
            compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        compose.waitUntil(5_000) {
            compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
    }

    @Test
    fun requestAndSettingsColumnsShareBreakpointAfterContentPadding() {
        width.value = 663.dp
        val padding = mutableStateOf(PaddingValues(0.dp))
        compose.setContent {
            NotiSyncTheme {
                Column(Modifier.requiredWidth(width.value)) {
                    RequestDetailLayout(
                        modifier = Modifier.height(200.dp),
                        contentPadding = padding.value,
                        useWideLayout = true,
                        summary = { Box(Modifier.fillMaxWidth().height(48.dp).testTag("request-summary")) },
                    ) {
                        Box(Modifier.fillMaxWidth().height(48.dp).testTag("request-details"))
                    }
                    AdaptiveDetailGrid(
                        modifier = Modifier.height(200.dp),
                        contentPadding = padding.value,
                        verticalSpacing = 16.dp,
                    ) {
                        detailSection("settings-summary") { Box(Modifier.height(48.dp)) }
                        detailSection("settings-details") { Box(Modifier.height(48.dp)) }
                    }
                }
            }
        }
        for (horizontalPadding in listOf(0.dp, 20.dp)) {
            compose.runOnIdle {
                padding.value = PaddingValues(horizontal = horizontalPadding)
                width.value = 663.dp + horizontalPadding * 2
            }
            for (prefix in listOf("request", "settings")) {
                val first = compose.onNodeWithTag("$prefix-summary").getUnclippedBoundsInRoot()
                val second = compose.onNodeWithTag("$prefix-details").getUnclippedBoundsInRoot()
                assertEquals(first.left, second.left)
                assertTrue(second.top > first.top)
            }
            compose.runOnIdle { width.value = 664.dp + horizontalPadding * 2 }
            assertSideBySide("request-summary", "request-details")
            assertSideBySide("settings-summary", "settings-details")
        }
    }

    @Test
    fun expandingHistoryKeepsCardWidthUntilTwoColumnsFit() {
        width.value = 492.dp
        compose.setContent {
            NotiSyncTheme {
                val geometry = with(LocalDensity.current) {
                    DetailPaneGeometry(1_000.dp.roundToPx(), DetailPaneSpacer.roundToPx())
                }
                CompositionLocalProvider(LocalDetailPaneGeometry provides geometry) {
                    Box(Modifier.requiredWidth(width.value).height(400.dp)) {
                        RequestDetailLayout(
                            contentPadding = PaddingValues(20.dp),
                            useWideLayout = true,
                            summary = { Box(Modifier.fillMaxWidth().height(80.dp).testTag("hero-card")) },
                        ) {
                            Box(Modifier.fillMaxWidth().height(80.dp).testTag("detail-card"))
                        }
                    }
                }
            }
        }
        val hero = compose.onNodeWithTag("hero-card")
        fun heroWidth() = hero.getUnclippedBoundsInRoot().let { it.right - it.left }
        val splitWidth = heroWidth()
        for (paneWidth in listOf(700.dp, 703.dp)) {
            compose.runOnIdle { width.value = paneWidth }
            assertEquals(splitWidth, heroWidth())
        }
        compose.runOnIdle { width.value = 1_000.dp }
        assertSideBySide("hero-card", "detail-card")
        assertEquals(468.dp, heroWidth())
        compose.runOnIdle { width.value = 700.dp }
        assertEquals(splitWidth, heroWidth())
    }

    @Test
    fun cardsAnimateBetweenSingleAndTwoColumns() {
        width.value = 703.dp
        compose.setContent {
            NotiSyncTheme {
                Box(Modifier.requiredWidth(width.value).height(500.dp)) {
                    RequestDetailLayout(
                        contentPadding = PaddingValues(20.dp),
                        useWideLayout = true,
                        summary = { Text("Hero", Modifier.height(120.dp)) },
                        requestInfo = { Text("Request", Modifier.testTag("info")) },
                    ) {
                        Text("Details", Modifier.height(120.dp).testTag("detail"))
                    }
                }
            }
        }
        val detail = compose.onNodeWithTag("detail")
        val narrow = detail.getUnclippedBoundsInRoot()
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { width.value = 704.dp }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(80)
        val expanding = detail.getUnclippedBoundsInRoot()
        capture("request-reflow-expanding.png")
        compose.mainClock.autoAdvance = true
        val wide = detail.getUnclippedBoundsInRoot()
        assertTrue("Card should move through an intermediate position: $narrow -> $expanding -> $wide", expanding.left > narrow.left)
        assertTrue("Card should not jump directly to the right column", expanding.left < wide.left)
        assertTrue(expanding.top < narrow.top && expanding.top > wide.top)

        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { width.value = 703.dp }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(80)
        val contracting = detail.getUnclippedBoundsInRoot()
        compose.mainClock.autoAdvance = true
        val restored = detail.getUnclippedBoundsInRoot()
        assertTrue(contracting.left < wide.left && contracting.left > restored.left)
        assertTrue(contracting.top > wide.top && contracting.top < restored.top)
        assertEquals(narrow, restored)
    }

    @Test
    fun scrolledOffscreenCardsSurviveRepeatedColumnChanges() {
        compose.setContent {
            NotiSyncTheme {
                LookaheadScope {
                    Box(Modifier.requiredWidth(width.value).height(400.dp)) {
                        RequestDetailLayout(
                            contentPadding = PaddingValues(20.dp),
                            useWideLayout = true,
                            summary = { Text("Hero", Modifier.height(300.dp)) },
                            requestInfo = {
                                var value by remember { mutableStateOf("") }
                                OutlinedTextField(value, { value = it }, Modifier.testTag("request-note"))
                            },
                        ) {
                            repeat(20) { Text("Detail $it", Modifier.height(48.dp)) }
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("request-note").performScrollTo().performTextInput("retained")
        repeat(5) {
            compose.onNodeWithText("Detail 19").performScrollTo()
            compose.runOnIdle { width.value = 703.dp }
            compose.onNodeWithText("Detail 19").performScrollTo()
            compose.onNodeWithTag("request-note").performScrollTo().assertTextContains("retained")
            compose.runOnIdle { width.value = 704.dp }
            compose.onNodeWithText("Hero").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("request-note").performScrollTo().assertTextContains("retained")
        }
    }

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

        compose.runOnIdle { width.value = 703.dp }
        compose.onNodeWithTag("draft").performScrollTo().assertTextContains("retained value")
        val hero = compose.onNodeWithTag("hero").fetchSemanticsNode().boundsInRoot
        val detail = compose.onNodeWithTag("draft").fetchSemanticsNode().boundsInRoot
        assertEquals(hero.left, detail.left, 1f)
        assertTrue(detail.top > hero.top)

        compose.runOnIdle { width.value = 704.dp }
        compose.onNodeWithTag("draft").performScrollTo().assertTextContains("retained value")
        assertSideBySide("hero", "draft")
    }

    @Test
    fun sealApprovalRendersWideAndCompactWithReachableActions() {
        val request = sealRequest()
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
        val request = sshRequest()
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

    @Test
    fun sealHistoryPaneUsesTwoColumnsWhenExpandedAndOneWhenRestored() {
        verifyExpandableHistoryPane(seal = true)
    }

    @Test
    fun sshHistoryPaneUsesTwoColumnsWhenExpandedAndOneWhenRestored() {
        verifyExpandableHistoryPane(seal = false)
    }

    private fun verifyExpandableHistoryPane(seal: Boolean) {
        val sealHistory = sealRequest().copy(
            state = OpenPgpRequestState.SENT,
            result = OpenPgpRequestResult.APPROVED,
        )
        val sshHistory = sshRequest().copy(
            state = SshProviderRequestState.SENT,
            outcome = SshProviderRequestOutcome.SIGNED,
        )
        compose.setContent {
            NotiSyncTheme {
                var selected by remember { mutableStateOf<String?>(null) }
                AdaptiveDetailLayout(
                    detailState = Unit,
                    modifier = Modifier.fillMaxSize(),
                    selectedKey = selected,
                    onDismiss = { selected = null },
                    paneTitle = "Request history",
                    detail = {
                        EdgeToEdgeHistoryModalBottomSheet(onDismissRequest = { selected = null }) {
                            if (seal) {
                                SigningRequestDetail(
                                    stored = sealHistory,
                                    requesterName = "Desktop",
                                    requesterIdentityKeyFingerprint = null,
                                    signingIdentity = "Example signing key",
                                    contentPadding = historySheetContentPadding(),
                                    showSheetHeader = true,
                                )
                            } else {
                                SshHistoryRequestDetail(
                                    request = sshHistory,
                                    requesterName = "Desktop",
                                    requesterIdentityKeyFingerprint = null,
                                    knownHostname = "example.com",
                                    contentPadding = historySheetContentPadding(),
                                    onBack = { selected = null },
                                )
                            }
                        }
                    },
                ) {
                    Button(onClick = { selected = "history" }) { Text("Open history") }
                }
            }
        }
        val section = if (seal) "Commit" else "SSH Signature"
        compose.onNodeWithText("Open history").performClick()
        assertHistorySingleColumn(section)
        val splitDividerX = compose.onNodeWithTag("detail-pane-divider").fetchSemanticsNode().boundsInRoot.center.x
        repeat(4) { iteration ->
            compose.onNodeWithTag("detail-pane-divider").performTouchInput {
                swipe(center, Offset(center.x - 1500f, center.y), if (iteration == 0) 700 else 240)
            }
            compose.onNodeWithText("Open history").assertIsNotDisplayed()
            val hero = compose.onNodeWithText("Signed").getUnclippedBoundsInRoot()
            val details = compose.onNodeWithText(section).getUnclippedBoundsInRoot()
            assertTrue(details.left > hero.right)
            assertInfoInLeftColumn("Request", section)
            if (iteration == 0) capture(if (seal) "seal-history-expanded.png" else "ssh-history-expanded.png")
            val expandedDividerX = compose.onNodeWithTag("detail-pane-divider").fetchSemanticsNode().boundsInRoot.center.x
            compose.onNodeWithTag("detail-pane-divider").performTouchInput {
                // Pause at the midpoint so this tests restoring the split, not a fling that
                // can select the next anchor after passing the middle by a few pixels.
                val start = center
                val distance = splitDividerX - expandedDividerX
                down(start)
                repeat(20) { moveTo(Offset(start.x + distance * (it + 1) / 20, start.y), 50) }
                advanceEventTime(200)
                moveTo(Offset(start.x + distance, start.y))
                up()
            }
            compose.onNodeWithText("Open history").assertIsDisplayed()
            assertHistorySingleColumn(section)
        }
    }

    private fun assertHistorySingleColumn(section: String) {
        compose.onNodeWithText("Signed").performScrollTo()
        val hero = compose.onNodeWithText("Signed").getUnclippedBoundsInRoot()
        val details = compose.onNodeWithText(section).getUnclippedBoundsInRoot()
        assertTrue(details.left < hero.right)
        assertTrue(details.top > hero.top)
    }

    private fun sealRequest(): StoredOpenPgpRequest {
        val now = System.currentTimeMillis()
        val payload = ("tree 0123456789abcdef0123456789abcdef01234567\n" +
            "parent 6648f0d82d47bafb997f07ea8720e22d89471068\n" +
            "author Example <example@example.com> 1700000000 +0000\n" +
            "committer Example <example@example.com> 1700000000 +0000\n\n" +
            "Improve approval layouts\n\nMake request details easier to review on wider displays.\n").encodeToByteArray()
        return StoredOpenPgpRequest(
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
    }

    private fun sshRequest(): StoredSshProviderRequest {
        val now = System.currentTimeMillis()
        return StoredSshProviderRequest(
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

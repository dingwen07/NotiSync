package net.extrawdw.apps.notisync.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import net.extrawdw.apps.notisync.data.AppConfigRepository
import net.extrawdw.apps.notisync.data.RosterDevice
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDatabase
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDatabaseFactory
import net.extrawdw.apps.notisync.data.storage.operational.RoomOperationalApplicationState
import net.extrawdw.apps.notisync.navigation.AppNavigationShell
import net.extrawdw.apps.notisync.navigation.MenuConfiguration
import net.extrawdw.apps.notisync.screen.AndroidScreenDecoderSupport
import net.extrawdw.apps.notisync.testsupport.RoomStorageTestContext
import net.extrawdw.apps.notisync.ui.theme.NotiSyncTheme
import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.TrustStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import android.graphics.Bitmap
import java.io.File

/** Native 1280 dp tablet profile; all controls use fixtures or an isolated test database. */
@RunWith(AndroidJUnit4::class)
class DeviceAppDetailLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val context = RoomStorageTestContext(InstrumentationRegistry.getInstrumentation().targetContext, "adaptive-app-details")

    @After
    fun cleanUp() {
        testScope.cancel()
        OperationalDatabaseFactory.close(context)
        context.deleteDatabase(OperationalDatabase.DATABASE_NAME)
    }

    @Test
    fun sectionsAnimateBetweenColumnsAndKeepLocalState() {
        val width = mutableStateOf(650.dp)
        compose.setContent {
            NotiSyncTheme {
                Box(Modifier.requiredWidth(width.value).height(600.dp)) {
                    AdaptiveDetailGrid(contentPadding = PaddingValues(20.dp), verticalSpacing = 18.dp) {
                        detailSection("controls") {
                            var draft by remember { mutableStateOf("") }
                            OutlinedTextField(draft, onValueChange = { draft = it }, modifier = Modifier.testTag("draft"))
                        }
                        detailSection("channels") { Text("Channels", Modifier.height(100.dp)) }
                    }
                }
            }
        }
        compose.onNodeWithTag("draft").performTextInput("retained")
        val before = bounds("channels")
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { width.value = 1000.dp }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(80)
        compose.waitForIdle()
        val moving = bounds("channels")
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        val after = bounds("channels")
        assertTrue(moving.left > before.left && moving.left < after.left)
        assertTrue(after.top < before.top)
        compose.onNodeWithTag("draft").assertTextContains("retained")
        repeat(3) {
            compose.runOnIdle { width.value = 650.dp }
            compose.onNodeWithTag("draft").assertTextContains("retained")
            compose.runOnIdle { width.value = 1000.dp }
            compose.onNodeWithTag("draft").assertTextContains("retained")
        }
    }

    @Test
    fun deviceSectionsUseRequestedPositionsAndPreserveExpandedMetadata() {
        var forwarded = false
        val hotspotVisible = mutableStateOf(true)
        val sharingEnabled = mutableStateOf(true)
        val remoteScreen = mutableStateOf(false)
        val screenCapabilities = listOf(
            Capability.CAPABILITY_ROUTING_V1, Capability.SCREEN_MIRROR_SOURCE_V1,
            Capability.SCREEN_MIRROR_CONTROL_V1, Capability.SCREEN_MIRROR_CLIPBOARD_TEXT_V1,
            Capability.SCREEN_MIRROR_ENCODER_H264_HW,
        )
        val device = RosterDevice(
            clientId = ClientId("fixture-device"), status = TrustStatus.TRUSTED,
            displayName = "Example phone", keyAvailable = true, introducedByName = null,
            revokedAt = null, ownDevice = true, currentEpoch = 1, platform = "android",
            capabilities = listOf(Capability.HOTSPOT_PROVIDER_V1), verified = true,
        )
        compose.setContent {
            Fixture {
                DeviceDetailsSheet(
                    device = device.copy(capabilities =
                        (if (hotspotVisible.value) device.capabilities else emptyList()) +
                            (if (remoteScreen.value) screenCapabilities else emptyList())),
                    nowMillis = 0, screenMirroringEnabled = sharingEnabled.value,
                    screenControlAuthorized = false, forwardLocalNotifications = false,
                    forwardIphoneNotifications = false,
                    onForwardLocalNotificationsChange = { forwarded = it },
                    onForwardIphoneNotificationsChange = {}, screenMirrorCodecOverride = null,
                    screenMirrorDecoderSupport = AndroidScreenDecoderSupport(emptySet(), emptySet()),
                    onScreenControlAuthorizedChange = {}, onScreenMirrorCodecOverrideChange = {},
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("Key Epoch").performClick()
        compose.onNodeWithText("Signing Key").assertIsDisplayed()
        expand()
        val identity = bounds("device-details-identity")
        val notifications = bounds("device-details-notifications")
        val hotspot = bounds("device-details-hotspot")
        val screen = bounds("device-details-screen")
        assertEquals(identity.top, hotspot.top)
        assertTrue(hotspot.left > identity.right)
        // The next section uses the shorter column immediately, without reserving row height.
        assertEquals(hotspot.left, screen.left)
        assertEquals(hotspot.bottom + 18.dp, screen.top)
        assertEquals(identity.left, notifications.left)
        assertEquals(identity.bottom + 18.dp, notifications.top)
        compose.runOnIdle { hotspotVisible.value = false }
        compose.onNodeWithTag("device-details-hotspot").assertDoesNotExist()
        // Omitting hotspot fills the gap, without promoting notifications or spanning the row.
        assertEquals(bounds("device-details-identity").top, bounds("device-details-screen").top)
        assertTrue(bounds("device-details-screen").left > bounds("device-details-identity").right)
        val shorter = listOf(bounds("device-details-identity"), bounds("device-details-screen")).minBy { it.bottom }
        assertEquals(shorter.left, bounds("device-details-notifications").left)
        assertEquals(shorter.bottom + 18.dp, bounds("device-details-notifications").top)
        compose.runOnIdle { sharingEnabled.value = false }
        compose.onNodeWithTag("device-details-screen").assertDoesNotExist()
        compose.runOnIdle { remoteScreen.value = true }
        compose.onNodeWithTag("device-details-screen").assertIsDisplayed()
        compose.onNodeWithText("Video codec").assertIsDisplayed()
        compose.onNodeWithText("Allow screen control").assertDoesNotExist()
        compose.onNodeWithText("Allow virtual display access").assertDoesNotExist()
        compose.runOnIdle {
            remoteScreen.value = false
            sharingEnabled.value = true
            hotspotVisible.value = true
        }
        capture("device-details-wide.png")
        compose.onNodeWithText("Local notifications").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(forwarded) }
        restore()
        compose.onNodeWithText("Signing Key").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun appSwitchesStayLeftOfChannelsAndContinueUpdatingConfiguration() {
        context.deleteDatabase(OperationalDatabase.DATABASE_NAME)
        val repository = AppConfigRepository(testScope, RoomOperationalApplicationState(context))
        repository.recordSeenChannel("fixture.app", "messages", "Messages", "social", "Social")
        compose.setContent {
            Fixture { AppConfigSheet(InstalledApp("fixture.app", "Example app", null), repository, {}) }
        }
        expand()
        val controls = bounds("app-details-controls")
        val channels = bounds("app-details-channels")
        assertTrue(channels.left > controls.right)
        assertEquals(controls.top, channels.top)
        compose.onNodeWithText("Messages").assertIsDisplayed()
        capture("app-details-wide.png")
        compose.onNodeWithText("Mirror ongoing notifications").performClick()
        compose.runOnIdle { assertTrue(repository.configFor("fixture.app").mirrorOngoing) }
        restore()
        compose.onNodeWithTag("adaptive-detail-grid").performScrollToIndex(0)
        compose.onNodeWithText("Mirror ongoing notifications").assertIsDisplayed()
        compose.runOnIdle { assertTrue(repository.configFor("fixture.app").mirrorOngoing) }
    }

    @Test
    fun expandingPaneKeepsSettingsWidthUntilTwoColumnsFit() {
        val width = mutableStateOf(492.dp)
        compose.setContent {
            NotiSyncTheme {
                val geometry = with(LocalDensity.current) { DetailPaneGeometry(1000.dp.roundToPx(), 16.dp.roundToPx()) }
                CompositionLocalProvider(LocalDetailPaneGeometry provides geometry) {
                    Box(Modifier.requiredWidth(width.value).height(600.dp)) {
                        AdaptiveDetailGrid(contentPadding = PaddingValues(20.dp), verticalSpacing = 18.dp) {
                            detailSection("controls") { Text("Controls") }
                            detailSection("channels") { Text("Channels") }
                        }
                    }
                }
            }
        }
        val splitWidth = bounds("controls").let { it.right - it.left }
        compose.runOnIdle { width.value = 680.dp }
        assertEquals(splitWidth, bounds("controls").let { it.right - it.left })
        assertEquals(bounds("controls").left, bounds("channels").left)
        compose.runOnIdle { width.value = 900.dp }
        assertTrue(bounds("channels").left > bounds("controls").right)
        compose.runOnIdle { width.value = 680.dp }
        assertEquals(splitWidth, bounds("controls").let { it.right - it.left })
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()

    private fun capture(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun expand() {
        compose.onNodeWithTag("detail-pane-divider").performTouchInput {
            swipe(center, Offset(center.x - 1500f, center.y), 700)
        }
    }

    private fun restore() {
        compose.onNodeWithTag("detail-region").performTouchInput {
            down(Offset(width * .15f, height * .3f))
            repeat(20) { moveTo(Offset(width * (.15f + .5f * (it + 1) / 20), height * .3f), 30) }
            advanceEventTime(200)
            moveTo(Offset(width * .65f, height * .3f))
            up()
        }
    }

    @Composable
    private fun Fixture(detail: @Composable () -> Unit) {
        NotiSyncTheme {
            AppNavigationShell(
                menuConfiguration = MenuConfiguration(), currentDestination = net.extrawdw.apps.notisync.Route.Devices,
                openMenuDestination = null, onNavigationRequestStarted = {},
                onOpenMenuDestinationConsumed = {}, onNavigate = {}, openMenu = {}, openAbout = {},
            ) { _, _ ->
                AdaptiveDetailLayout(
                    detailState = Unit,
                    modifier = Modifier.fillMaxSize().testTag("detail-region"),
                    selectedKey = "fixture", onDismiss = {}, detail = { detail() },
                ) { Text("Device or app list") }
            }
        }
    }
}

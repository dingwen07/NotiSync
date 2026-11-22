package net.extrawdw.apps.notisync.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.extrawdw.apps.notisync.ios.IosCompanion.Peer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IncomingIphonePickerTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun duplicateNamesSelectOnlyTheTappedBluetoothAddress() {
        val first = Peer("AA:00:00:00:00:01", "iPhone", newServerLinkAt = 1L)
        val second = Peer("AA:00:00:00:00:02", "iPhone", newServerLinkAt = 2L)
        val selections = mutableListOf<Peer>()
        compose.setContent {
            IncomingIphonePicker(listOf(first, second), onDismiss = {}, onSelect = selections::add)
        }
        compose.onNodeWithText(second.address, substring = true).performClick()
        compose.runOnIdle { assertEquals(listOf(second), selections) }
    }
}

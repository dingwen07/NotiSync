package net.extrawdw.apps.notisync.ui

import net.extrawdw.apps.notisync.ios.IosBridgeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IosBridgeUiStateTest {
    private fun state(
        bridgeEnabled: Boolean = false,
        bluetoothAvailable: Boolean = true,
        bluetoothOn: Boolean = true,
        status: IosBridgeStatus = IosBridgeStatus.OFF,
        associated: Boolean = false,
        needsEnrollment: Boolean = !associated,
        appSetup: Boolean = false,
        hasSetupPeer: Boolean = false,
    ): IosBridgeUiState = resolveIosBridgeUiState(
        bridgeEnabled = bridgeEnabled,
        bluetoothAvailable = bluetoothAvailable,
        bluetoothOn = bluetoothOn,
        status = status,
        associated = associated,
        needsEnrollment = needsEnrollment,
        appSetup = appSetup,
        hasSetupPeer = hasSetupPeer,
    )

    @Test
    fun unpairedAndBridgeOffShowsCompactStartSetupCard() {
        val ui = state(bridgeEnabled = false, status = IosBridgeStatus.OFF, associated = false)
        assertEquals(IosConnectionBannerAction.NONE, ui.connectionBannerAction)
        assertFalse(ui.showBluetoothOffStatus)
        assertFalse(ui.showConnectedDeviceName)
        assertEquals(
            IosPairingPresentation.SetupCard(
                body = SetupCardBody.BRIDGE_OFF,
                showBusyIndicator = false,
                showModeSelector = true,
            ),
            ui.pairing,
        )
    }

    @Test
    fun unpairedAdvertisingOffersBothMethodsInEitherMode() {
        val standard = state(
            bridgeEnabled = true,
            status = IosBridgeStatus.ADVERTISING,
            associated = false,
        )
        assertEquals(
            IosPairingPresentation.SetupCard(
                body = SetupCardBody.METHOD_STEPS,
                showBusyIndicator = false,
                showModeSelector = true,
            ),
            standard.pairing,
        )

        val appMode = state(
            bridgeEnabled = true,
            status = IosBridgeStatus.ADVERTISING,
            associated = false,
            appSetup = true,
        )
        assertEquals(
            IosPairingPresentation.SetupCard(
                body = SetupCardBody.METHOD_STEPS,
                showBusyIndicator = false,
                showModeSelector = true,
            ),
            appMode.pairing,
        )
    }

    @Test
    fun incomingAppSetupPeerSwitchesToConfirmationCardWithoutModeSelector() {
        val ui = state(
            bridgeEnabled = true,
            status = IosBridgeStatus.ADVERTISING,
            associated = false,
            appSetup = true,
            hasSetupPeer = true,
        )
        assertEquals(
            IosPairingPresentation.SetupCard(
                body = SetupCardBody.CONFIRM_APP_PEER,
                showBusyIndicator = false,
                showModeSelector = false,
            ),
            ui.pairing,
        )
    }

    @Test
    fun activeBondingShowsPairingInProgressEvenIfAssociationExists() {
        val unpairedBonding = state(
            bridgeEnabled = true,
            status = IosBridgeStatus.NEEDS_PAIRING,
            associated = false,
        )
        assertEquals(
            IosPairingPresentation.SetupCard(
                body = SetupCardBody.PAIRING_IN_PROGRESS,
                showBusyIndicator = true,
                showModeSelector = false,
            ),
            unpairedBonding.pairing,
        )

        val associatedBonding = state(
            bridgeEnabled = true,
            status = IosBridgeStatus.NEEDS_PAIRING,
            associated = true,
            needsEnrollment = false,
        )
        assertEquals(unpairedBonding.pairing, associatedBonding.pairing)
    }

    @Test
    fun connectingDistinguishesUnpairedFromSettledPaired() {
        val unpaired = state(
            bridgeEnabled = true,
            status = IosBridgeStatus.CONNECTING,
            associated = false,
        )
        assertEquals(
            IosPairingPresentation.SetupCard(
                body = SetupCardBody.CONNECTING,
                showBusyIndicator = true,
                showModeSelector = false,
            ),
            unpaired.pairing,
        )
        assertTrue(unpaired.showConnectedDeviceName)

        val paired = state(
            bridgeEnabled = true,
            status = IosBridgeStatus.CONNECTING,
            associated = true,
            needsEnrollment = false,
        )
        assertEquals(IosPairingPresentation.SettledRow, paired.pairing)
        assertTrue(paired.showConnectedDeviceName)
    }

    @Test
    fun settledPairedStaysSettledRowAcrossActiveAndDisconnectedStates() {
        val stableStatuses = listOf(
            IosBridgeStatus.SHARING,
            IosBridgeStatus.ADVERTISING,
            IosBridgeStatus.OFF,
            IosBridgeStatus.ERROR,
            IosBridgeStatus.NO_ANCS,
        )
        for (status in stableStatuses) {
            val ui = state(
                bridgeEnabled = status != IosBridgeStatus.OFF,
                status = status,
                associated = true,
                needsEnrollment = false,
            )
            assertEquals("Expected SettledRow for $status", IosPairingPresentation.SettledRow, ui.pairing)
        }

        val noAncs = state(
            bridgeEnabled = true,
            status = IosBridgeStatus.NO_ANCS,
            associated = true,
            needsEnrollment = false,
        )
        assertEquals(IosConnectionBannerAction.RETRY_SHARING, noAncs.connectionBannerAction)
        assertTrue(noAncs.showConnectedDeviceName)
    }

    @Test
    fun pairedDeviceNeedingLeEnrollmentKeepsSetupCardVisible() {
        val bridgeOff = state(
            bridgeEnabled = false,
            status = IosBridgeStatus.OFF,
            associated = true,
            needsEnrollment = true,
        )
        assertEquals(
            IosPairingPresentation.SetupCard(
                body = SetupCardBody.BRIDGE_OFF,
                showBusyIndicator = false,
                showModeSelector = true,
            ),
            bridgeOff.pairing,
        )

        val advertising = state(
            bridgeEnabled = true,
            status = IosBridgeStatus.ADVERTISING,
            associated = true,
            needsEnrollment = true,
        )
        assertEquals(
            IosPairingPresentation.SetupCard(
                body = SetupCardBody.METHOD_STEPS,
                showBusyIndicator = false,
                showModeSelector = true,
            ),
            advertising.pairing,
        )
    }

    @Test
    fun bluetoothOffTriggersSettingsActionAndWaitingCardWhenUnpaired() {
        val unpairedBtOff = state(
            bridgeEnabled = true,
            bluetoothAvailable = true,
            bluetoothOn = false,
            status = IosBridgeStatus.ERROR,
            associated = false,
        )
        assertEquals(IosConnectionBannerAction.OPEN_BLUETOOTH_SETTINGS, unpairedBtOff.connectionBannerAction)
        assertTrue(unpairedBtOff.showBluetoothOffStatus)
        assertEquals(
            IosPairingPresentation.SetupCard(
                body = SetupCardBody.WAITING_BLUETOOTH,
                showBusyIndicator = false,
                showModeSelector = true,
            ),
            unpairedBtOff.pairing,
        )

        val pairedBtOff = state(
            bridgeEnabled = true,
            bluetoothAvailable = true,
            bluetoothOn = false,
            status = IosBridgeStatus.ERROR,
            associated = true,
            needsEnrollment = false,
        )
        assertEquals(IosConnectionBannerAction.OPEN_BLUETOOTH_SETTINGS, pairedBtOff.connectionBannerAction)
        assertTrue(pairedBtOff.showBluetoothOffStatus)
        assertEquals(IosPairingPresentation.SettledRow, pairedBtOff.pairing)

        val permissionError = state(
            bridgeEnabled = true,
            bluetoothAvailable = true,
            bluetoothOn = true,
            status = IosBridgeStatus.ERROR,
            associated = false,
        )
        assertEquals(IosConnectionBannerAction.NONE, permissionError.connectionBannerAction)
        assertFalse(permissionError.showBluetoothOffStatus)
        assertEquals(
            IosPairingPresentation.SetupCard(
                body = SetupCardBody.BRIDGE_ERROR,
                showBusyIndicator = false,
                showModeSelector = true,
            ),
            permissionError.pairing,
        )
    }

    @Test
    fun unsupportedHardwareShowsUnsupportedOrSettledRowWhenAssociated() {
        val unpaired = state(
            bridgeEnabled = true,
            status = IosBridgeStatus.UNSUPPORTED,
            associated = false,
        )
        assertEquals(IosPairingPresentation.UnsupportedRow, unpaired.pairing)

        val paired = state(
            bridgeEnabled = true,
            status = IosBridgeStatus.UNSUPPORTED,
            associated = true,
            needsEnrollment = false,
        )
        assertEquals(IosPairingPresentation.SettledRow, paired.pairing)
    }
}

package net.extrawdw.apps.notisync.ios

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** User-facing iOS bridge status for the iOS tab. */
enum class IosBridgeStatus {
    /** The bridge is switched off. */
    OFF,

    /** This phone can't act as a BLE peripheral (no advertiser / multi-advertisement unsupported). */
    UNSUPPORTED,

    /** Advertising the ANCS solicitation; waiting for an iPhone to connect from its Bluetooth settings. */
    ADVERTISING,

    /** An iPhone connected; discovering services / negotiating MTU. */
    CONNECTING,

    /** Waiting for the user to accept pairing on the iPhone (and enable "Share System Notifications"). */
    NEEDS_PAIRING,

    /** Bonded and subscribed — notifications are flowing. */
    SHARING,

    /** Connected, but the peer isn't exposing ANCS (e.g. not an iPhone, or sharing disabled). */
    NO_ANCS,

    /** Missing permission / Bluetooth off / other error. */
    ERROR,
}

/** Live iOS bridge state for the UI: connection status + the connected iPhone's name. In-memory. */
class IosDeviceRepository {
    private val _status = MutableStateFlow(IosBridgeStatus.OFF)
    val status: StateFlow<IosBridgeStatus> = _status

    private val _deviceName = MutableStateFlow<String?>(null)
    val deviceName: StateFlow<String?> = _deviceName

    fun setStatus(status: IosBridgeStatus) {
        _status.value = status
    }

    /** Set the connected iPhone's name. Ignores null/blank so a transient empty read can't clobber a known
     *  name; call [clearDeviceName] to explicitly drop it when the device disconnects. */
    fun setDeviceName(name: String?) {
        if (!name.isNullOrBlank()) _deviceName.value = name
    }

    /** Drop the device name (no device connected), so a later session never shows a previous iPhone's name. */
    fun clearDeviceName() {
        _deviceName.value = null
    }
}

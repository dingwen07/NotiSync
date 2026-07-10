package net.extrawdw.apps.notisync.ios

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanResult
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.regex.Pattern

/**
 * CompanionDeviceManager (CDM) glue for **background reliability**. After the user creates a one-time
 * association (a system device picker) and we observe device presence, the OS wakes [IosCompanionService]
 * whenever the iPhone comes into BLE range — which (re)starts [IosBridgeService] so notifications keep
 * flowing even when NotiSync isn't open. The foreground-service path works without this; CDM is additive.
 *
 * All calls are guarded — a CDM quirk must never crash the app or break the core bridge.
 */
object IosCompanion {
    private const val TAG = "IosCompanion"

    fun manager(context: Context): CompanionDeviceManager? =
        runCatching { context.getSystemService(CompanionDeviceManager::class.java) }.getOrNull()

    /** Whether this app already holds a CDM association (drives the iOS tab's pairing state). */
    @SuppressLint("MissingPermission")
    fun isAssociated(context: Context): Boolean =
        runCatching { manager(context)?.myAssociations?.isNotEmpty() == true }.getOrDefault(false)

    /**
     * Name of the (first) associated iPhone for the pairing card — the name the user picked in the CDM picker
     * ([AssociationInfo.getDisplayName]), e.g. "John's iPhone". Falls back to the bonded device's name when CDM
     * didn't persist a display name. Null when there's no association or no resolvable name.
     */
    @SuppressLint("MissingPermission")
    fun associatedDeviceName(context: Context): String? = runCatching {
        val info = manager(context)?.myAssociations?.firstOrNull()
        info?.displayName?.toString()?.takeIf { it.isNotBlank() }
            ?: info?.deviceMacAddress?.toString()?.uppercase()?.let { mac ->
                context.getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(mac)?.name
            }?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /**
     * The association request. Uses the **classic** [BluetoothDeviceFilter] (BR/EDR inquiry), NOT the BLE one:
     * iOS does not put a local name in its BLE advertisements, so a BluetoothLeDeviceFilter name pattern matches
     * the (empty) advertised name and the iPhone never appears. Classic inquiry actively fetches the device name,
     * so the iPhone surfaces as "<name>'s iPhone".
     *
     * That is also why there is no manufacturer-data / ScanFilter option here: a BLE ScanFilter reads
     * advertisement fields that classic inquiry doesn't have. And ANCS itself is a BLE GATT service, absent from
     * the classic SDP record, so an addServiceUuid filter can't match it either — leaving the name as the one
     * usable discriminator.
     *
     * The pattern requires a **non-empty name** (`.+`): that drops the bare-MAC noise (unnamed devices can't be
     * recognised or picked) while still matching an iPhone renamed away from "iPhone".
     */
    fun request(): AssociationRequest {
        val filter = BluetoothDeviceFilter.Builder().setNamePattern(Pattern.compile(".+")).build()
        return AssociationRequest.Builder()
            .addDeviceFilter(filter)
            .setSingleDevice(false)
            .build()
    }

    /**
     * The BluetoothDevice the user picked, from the CDM picker result — handles both the API-33+
     * AssociationInfo extra and the legacy device / scan-result extra.
     */
    @Suppress("DEPRECATION") // EXTRA_DEVICE deprecated in 33; kept as a fallback when EXTRA_ASSOCIATION's associatedDevice is absent
    fun deviceFromPickerResult(intent: Intent?): BluetoothDevice? {
        if (intent == null) return null
        runCatching {
            intent.getParcelableExtra(
                CompanionDeviceManager.EXTRA_ASSOCIATION,
                AssociationInfo::class.java
            )
                ?.associatedDevice?.bluetoothDevice
        }.getOrNull()?.let { return it }
        runCatching {
            intent.getParcelableExtra(
                CompanionDeviceManager.EXTRA_DEVICE,
                BluetoothDevice::class.java
            )
        }
            .getOrNull()?.let { return it }
        runCatching {
            intent.getParcelableExtra(
                CompanionDeviceManager.EXTRA_DEVICE,
                ScanResult::class.java
            )?.device
        }
            .getOrNull()?.let { return it }
        return null
    }

    /**
     * Bond (pair) with [device] **from the Android side**, so the user doesn't have to pair via iOS Settings →
     * Bluetooth. The iPhone shows a pairing prompt; accepting it (with our ANCS solicitation already on air)
     * yields the encrypted link + "Share System Notifications". Association alone does NOT pair — this does.
     */
    @SuppressLint("MissingPermission")
    fun bondDevice(device: BluetoothDevice?) {
        device ?: run { Log.w(TAG, "no device to bond from CDM picker"); return }
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            Log.i(TAG, "CDM: initiating bond with ${device.address}")
            runCatching { device.createBond() }.onFailure { Log.w(TAG, "createBond failed", it) }
        }
    }

    /** (Re)arm presence observation for every association so [IosCompanionService.onDeviceAppeared] fires.
     *  Safe to call repeatedly (e.g. on every bridge start, to survive reboots). No-op if not associated. */
    @SuppressLint("MissingPermission")
    fun observePresence(context: Context) {
        val cdm = manager(context) ?: return
        runCatching { cdm.myAssociations }.getOrNull()?.forEach { info ->
            runCatching { observe(cdm, info) }.onFailure {
                Log.w(
                    TAG,
                    "startObservingDevicePresence failed: ${it.message}"
                )
            }
        }
    }

    @Suppress("DEPRECATION") // the address-based form is broadly supported; the request-based one tightened in 34+
    private fun observe(cdm: CompanionDeviceManager, info: AssociationInfo) {
        val mac = info.deviceMacAddress?.toString() ?: return
        cdm.startObservingDevicePresence(mac)
        Log.i(TAG, "observing presence for $mac")
    }

    /** Stop waking the app on the iPhone's presence, but KEEP the association (unlike [disassociateAll]) so the
     *  user can re-enable the bridge later without re-pairing. Called when the user turns the bridge off. */
    @SuppressLint("MissingPermission")
    fun stopObservingPresence(context: Context) {
        val cdm = manager(context) ?: return
        runCatching { cdm.myAssociations }.getOrNull()?.forEach { info ->
            runCatching { info.deviceMacAddress?.toString()?.let { stopObserving(cdm, it) } }
        }
    }

    /** Remove every association (and stop observing it) so the user can re-run "Set up". */
    @SuppressLint("MissingPermission")
    fun disassociateAll(context: Context) {
        val cdm = manager(context) ?: return
        runCatching { cdm.myAssociations }.getOrNull()?.forEach { info ->
            runCatching { info.deviceMacAddress?.toString()?.let { stopObserving(cdm, it) } }
            runCatching { cdm.disassociate(info.id) }
            Log.i(TAG, "disassociated ${info.id}")
        }
    }

    @Suppress("DEPRECATION")
    private fun stopObserving(cdm: CompanionDeviceManager, mac: String) =
        cdm.stopObservingDevicePresence(mac)
}

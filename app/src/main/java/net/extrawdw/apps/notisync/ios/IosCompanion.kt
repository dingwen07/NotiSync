package net.extrawdw.apps.notisync.ios

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import java.util.Locale

/** A dual-mode device can have a Classic link key and still need to pair for ANCS. */
@SuppressLint("MissingPermission")
internal fun BluetoothDevice.hasBridgeBond(): Boolean = if (Build.VERSION.SDK_INT >= 37) {
    getBondStatus(BluetoothDevice.TRANSPORT_LE) != null
} else bondState == BluetoothDevice.BOND_BONDED

/**
 * CompanionDeviceManager (CDM) glue for **background reliability**. After the user creates a one-time
 * association (a system access prompt) and we observe device presence, the OS wakes [IosCompanionService]
 * whenever the iPhone comes into BLE range — which (re)starts [IosBridgeService] so notifications keep
 * flowing even when NotiSync isn't open. The foreground-service path works without this; CDM is additive.
 *
 * All calls are guarded — a CDM quirk must never crash the app or break the core bridge.
 */
object IosCompanion {
    private const val TAG = "IosCompanion"

    fun manager(context: Context): CompanionDeviceManager? =
        runCatching { context.getSystemService(CompanionDeviceManager::class.java) }.getOrNull()

    private const val PEER_PREFS = "notisync_ios_bridge"
    private const val ADDRESS = "selected_address"
    private const val ASSOCIATION = "selected_association"

    private fun prefs(context: Context) = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(PEER_PREFS, Context.MODE_PRIVATE)

    internal fun setupPreferences(context: Context) = IphoneSetupPreferences(
        prefs(context), IphoneAppSetup.prefersAppSetup(Build.VERSION.SDK_INT, Build.MANUFACTURER, Build.SOC_MANUFACTURER),
    )

    private fun AssociationInfo.peer(): IosPeerSelection? =
        deviceMacAddress?.toString()?.uppercase(Locale.ROOT)?.let { IosPeerSelection(it, id) }

    internal fun selectedPeer(context: Context): IosPeerSelection? {
        val prefs = prefs(context)
        val saved = prefs.getString(ADDRESS, null)?.takeIf { it.isNotEmpty() }?.let {
            IosPeerSelection(it, prefs.getInt(ASSOCIATION, -1).takeIf { id -> id != -1 })
        }
        val explicit = prefs.contains(ADDRESS)
        if (explicit && saved?.associationId == null) return saved
        val associations = runCatching { manager(context)?.myAssociations }.getOrNull().orEmpty()
        val resolved = IosPeerSelection.resolve(saved, explicit, associations.mapNotNull { it.peer() })
        // Helper enrollment stays closed across CDM revocation or temporary unavailability.
        // The saved iPhone can request presence access again; Forget clears the address itself.
        return if (resolved == null && saved != null && setupPreferences(context).usesApp) {
            saved.copy(associationId = null)
        } else resolved
    }

    private fun savePeer(context: Context, peer: IosPeerSelection?) {
        // An empty address records an explicit Forget, even if the system still lists the old association.
        prefs(context).edit {
            if (prefs(context).getString("name_address", null) != peer?.address) {
                remove("name_address")
                remove("gap_name")
            }
            putString(ADDRESS, peer?.address.orEmpty())
            putInt(ASSOCIATION, peer?.associationId ?: -1)
        }
    }

    fun selectAssociation(context: Context, info: AssociationInfo) {
        val peer = info.peer() ?: return
        // Both CDM callbacks can deliver the same selection. Preserve any confirmed BLE address for it.
        if (prefs(context).getInt(ASSOCIATION, -1) != info.id) savePeer(context, peer)
    }

    data class Peer(
        val address: String,
        val name: String?,
        val newServerLinkAt: Long? = null,
    )

    @SuppressLint("MissingPermission")
    internal fun needsLeEnrollment(context: Context, peer: IosPeerSelection?): Boolean {
        if (peer == null) return true
        return runCatching {
            context.getSystemService(BluetoothManager::class.java)?.adapter
                ?.getRemoteDevice(peer.address)?.hasBridgeBond() == false
        }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    internal fun connectedGattDevices(manager: BluetoothManager): List<BluetoothDevice> =
        listOf(BluetoothProfile.GATT_SERVER, BluetoothProfile.GATT).flatMap { profile ->
            runCatching { manager.getConnectedDevices(profile) }.getOrDefault(emptyList())
        }.distinctBy { it.address }

    internal fun hasAssociation(context: Context, address: String, selected: IosPeerSelection?): Boolean {
        val id = selected?.associationId ?: return false
        return runCatching { manager(context)?.myAssociations?.any {
            it.id == id && address.equals(it.deviceMacAddress?.toString(), ignoreCase = true)
        } == true }.getOrDefault(false)
    }

    internal fun saveConfirmedAddress(context: Context, address: String) {
        savePeer(context, IosPeerSelection(address, selectedPeer(context)?.associationId))
    }

    internal fun saveGapName(context: Context, address: String, name: String) {
        prefs(context).edit {
            putString("name_address", address)
            putString("gap_name", name)
        }
    }

    @SuppressLint("MissingPermission")
    internal fun selectedDeviceName(context: Context, peer: IosPeerSelection?): String? {
        peer ?: return null
        val prefs = prefs(context)
        val device = runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(peer.address) }.getOrNull()
        val alias = runCatching { device?.alias }.getOrNull()
        val name = runCatching { device?.name }.getOrNull()
        val gap = prefs.getString("gap_name", null).takeIf { prefs.getString("name_address", null) == peer.address }
        val association = peer.associationId?.let { id -> runCatching {
            manager(context)?.myAssociations?.find { it.id == id }?.displayName?.toString()
        }.getOrNull() }
        // getAlias() returns the cached Bluetooth name too when the user hasn't set a local alias.
        return listOf(alias.takeIf { it != name }, gap, name, association).firstOrNull {
            !it.isNullOrBlank() && !it.equals(peer.address, ignoreCase = true)
        }
    }

    /** Pixel CDM also checks already-connected GATT peers for this exact-address request. */
    fun request(address: String): AssociationRequest = AssociationRequest.Builder()
        .addDeviceFilter(BluetoothDeviceFilter.Builder().setAddress(address).build())
        .setSingleDevice(true)
        .build()

    /** Re-arm presence for the selected phone after setup or a bridge restart. */
    @SuppressLint("MissingPermission")
    fun observePresence(context: Context) {
        val selectedId = selectedPeer(context)?.associationId ?: return
        val cdm = manager(context) ?: return
        runCatching { cdm.myAssociations.find { it.id == selectedId } }.getOrNull()?.let { info ->
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

    /** Remove every association (and stop observing it) and the selected Bluetooth address, so the user can re-run "Set up". */
    @SuppressLint("MissingPermission")
    fun disassociateAll(context: Context) {
        savePeer(context, null)
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

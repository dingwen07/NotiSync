package net.extrawdw.apps.notisync.ios

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.extrawdw.apps.notisync.appicon.BundleIdMap
import net.extrawdw.apps.notisync.appicon.IconResolver
import net.extrawdw.notisync.protocol.ActionEvent
import net.extrawdw.notisync.protocol.ActionKind
import net.extrawdw.notisync.protocol.AssetRole
import net.extrawdw.notisync.protocol.CallType
import net.extrawdw.notisync.protocol.CapturedNotification
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.MediaCommand
import net.extrawdw.notisync.protocol.PrivateAssetRef
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Orchestrates the iOS BLE bridge: advertises this phone as a BLE peripheral soliciting ANCS (so it
 * appears in the iPhone's Bluetooth settings) and attaches an [IosGattClient] to the selected bonded
 * endpoint. Each ANCS notification is fetched, resolved to an app name and icon, filtered by the
 * user's per-app opt-in, and dispatched to local display and/or the mesh.
 *
 * The same link also mirrors the iPhone's now-playing media over AMS (the media sibling of ANCS): the GATT
 * client forwards Entity Updates into [amsBridge], which maintains one media card ([AmsNotificationMapper]),
 * and transport presses flow back through [performOnIphone] as AMS Remote Commands.
 *
 * Dependencies are injected as plain function references (manual-DI house style) so this stays decoupled from
 * [net.extrawdw.apps.notisync.AppGraph] and unit-reasonable.
 */
@SuppressLint("MissingPermission")
class IosBridgeManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val clientId: ClientId,
    private val iconResolver: IconResolver,
    /** WEBP bytes of an installed app's launcher icon, or null if not installed here ([GraphicsExtractor.appIcon]). */
    private val appIconBytes: (packageName: String) -> ByteArray?,
    /** Upload an asset and return its body-only ref ([AssetManager.ensureUploaded]). */
    private val uploadAsset: suspend (ByteArray, AssetRole, String, ClientId) -> PrivateAssetRef?,
    private val registry: IosAppRegistry,
    private val deviceRepo: IosDeviceRepository,
    private val onRepeatedAdvertisingLoss: (appModeFailed: Boolean) -> Unit,
    private val localDisplayEnabled: () -> Boolean,
    private val meshMirrorEnabled: () -> Boolean,
    /** iPhone media (AMS) mirroring master toggle; gates the now-playing card, not the subscription. */
    private val mediaMirrorEnabled: () -> Boolean = { false },
    /** Seal the capture to the own mesh ([MirrorEngine.captureLocal]). */
    private val captureToMesh: suspend (CapturedNotification) -> Int,
    /** Quiet in-place update to the own mesh ([MirrorEngine.sendNotificationQuiet]) — media updates. */
    private val sendQuietToMesh: suspend (CapturedNotification) -> Int = { 0 },
    /** Post the capture locally on this phone ([RemoteNotificationPoster.render]); [silent] mutes the backlog. */
    private val renderLocal: (CapturedNotification, Boolean) -> Unit,
    /** Clear a locally-posted mirror ([RemoteNotificationPoster.clear]). */
    private val clearLocal: (ClientId, String) -> Unit,
    /** Broadcast a dismissal to the mesh ([MirrorEngine.dismissLocal]). */
    private val dismissMesh: suspend (ClientId, String) -> Unit,
) {
    private val adapter =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private var gattServer: BluetoothGattServer? = null
    private var serverCallback: BluetoothGattServerCallback? = null
    private var advertiser: BluetoothLeAdvertiser? = null

    @Volatile
    private var client: IosGattClient? = null

    @Volatile
    private var connectedDevice: BluetoothDevice? = null

    private val lePeers = mutableMapOf<String, BluetoothDevice>()
    private val pairingCandidates = deviceRepo.pairingCandidates
    private var enrolling: BluetoothDevice? = null
    private var setupDevice: BluetoothDevice? = null
        set(value) {
            field = value
            deviceRepo.setSetupPeer(value?.let { IosCompanion.Peer(it.address, it.name) })
        }

    private var appAdvertising = false
    private var advertisingReady = false
    private var advertisingMode: IphoneAdvertisement? = null
    private val setupPreferences = IosCompanion.setupPreferences(context)
    private var selectedPeer: IosPeerSelection? = null

    @Volatile
    private var receiverRegistered = false

    @Volatile
    private var adapterReceiverRegistered = false

    @Volatile
    private var running = false

    @Volatile
    private var advertisingSet: AdvertisingSet? = null

    private val handler = Handler(Looper.getMainLooper())
    private val attachRunnable = Runnable { tryAttachConnected() }
    private val peripheralRestartRunnable = Runnable { reopenPeripheral() }
    private var reconnectAttempts = 0
    private val advertisingLosses = AdvertisingLossTracker()

    // True only during a full peripheral reset ([restartPeripheral] → settle → [reopenPeripheral]): the GATT
    // server is briefly closed, so any stray client-gone callback must NOT schedule a re-attach in the gap.
    @Volatile
    private var resettingPeripheral = false

    /** The iPhone's now-playing (AMS) card: accumulates media entity updates and dispatches the mirror.
     *  One manager-lifetime instance; its session state resets whenever the link drops ([AmsMediaBridge.onGone]). */
    private val amsBridge = AmsMediaBridge(
        scope = scope,
        clientId = clientId,
        enabled = { mediaMirrorEnabled() },
        localDisplayEnabled = { localDisplayEnabled() },
        meshMirrorEnabled = { meshMirrorEnabled() },
        iphoneId = { iphoneId() },
        iphoneName = { deviceRepo.deviceName.value },
        readAttribute = { entity, attr -> client?.readEntityAttribute(entity, attr) },
        renderLocal = { notif, silent -> renderLocal(notif, silent) },
        clearLocal = { cid, key -> clearLocal(cid, key) },
        captureToMesh = { notif -> captureToMesh(notif) },
        sendQuietToMesh = { notif -> sendQuietToMesh(notif) },
        dismissMesh = { cid, key -> dismissMesh(cid, key) },
    )

    private val appNameCache =
        ConcurrentHashMap<String, String>() // bundleId -> resolved display name
    private val uidToKey =
        ConcurrentHashMap<Int, String>()        // ANCS notification UID -> mirror sourceKey

    // sourceKey -> reconnect-stable content key. Unlike [uidToKey] (session-scoped, cleared on disconnect) this
    // SURVIVES a reconnect — the manager outlives the BLE link — so a dismissal can still be matched to the same
    // notification when it replays under a fresh UID. Bounded LRU so a long session can't grow it without end.
    private val keyToContent = java.util.Collections.synchronizedMap(
        object : java.util.LinkedHashMap<String, String>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, String>): Boolean = size > 256
        }
    )

    // Content keys of mirrors dismissed (here or by a mesh peer) while we couldn't reach the iPhone — the link
    // was down, or the UID had already rotated. Drained in [onSourceEvent]: when such a notification replays we
    // clear it on the iPhone (with its fresh UID) instead of re-displaying it. Survives reconnect.
    private val pendingClear = java.util.Collections.synchronizedSet(HashSet<String>())

    // UIDs we asked the iPhone to clear ourselves (dismiss-through), awaiting their EVENT_REMOVED. That removal
    // must NOT re-broadcast to the mesh — the dismissal it answers already propagated there (it's what triggered
    // the clear), so re-sending it would echo back to the peer that dismissed. Session-scoped, like [uidToKey].
    private val selfClearedUids = java.util.Collections.synchronizedSet(HashSet<Int>())

    // Source keys of LIVE calls this session — a ringing incoming call OR an answered/active one. This is the
    // SINGLE guard that stops a dismissal from ending a call: [dismissOnIphone] (reached by every dismissal path —
    // a local swipe, the ring-window timeout, a mesh peer relaying a dismissal) skips the ANCS negative for a key
    // in here, since for a live call that negative is DECLINE (ringing) or HANG UP (active) and would drop the real
    // call. Ending it needs the explicit Decline / Hang up button (the PERFORM action path). Kept in lockstep with
    // [uidToKey] — added together in [onSourceEvent], and [onRemoved] drops uidToKey BEFORE this — so the guard is
    // race-free (see [dismissOnIphone]). Session-scoped; a missed call / voicemail isn't tracked (its negative is a
    // harmless "Clear", so a dismissal SHOULD clear it on the iPhone).
    private val liveCallKeys = java.util.Collections.synchronizedSet(HashSet<String>())

    fun start() {
        if (advertisingLosses.tripped) return // The switch is being persisted and the service stopped.
        if (running) {
            refreshSelection()
            return
        }
        if (!hasPermissions()) {
            Log.w(
                TAG,
                "missing BLUETOOTH_CONNECT/ADVERTISE"
            ); deviceRepo.setStatus(IosBridgeStatus.ERROR); return
        }
        val a = adapter
        if (a == null) {
            Log.w(TAG, "no Bluetooth adapter on this device")
            deviceRepo.setStatus(IosBridgeStatus.ERROR)
            return
        }
        // Arm this BEFORE the isEnabled bail so we hear STATE_ON and re-advertise when the user turns
        // Bluetooth back on — instead of parking in ERROR until they reopen the iOS tab.
        registerAdapterReceiver()
        if (!a.isEnabled) {
            Log.w(TAG, "Bluetooth is off — will re-advertise when it's turned back on")
            deviceRepo.setStatus(IosBridgeStatus.ERROR)
            return
        }
        // Gate on the ADVERTISER being available — NOT isMultipleAdvertisementSupported, which asks for
        // *concurrent* advertising sets (far stricter) and is false on many phones that advertise one set
        // fine. bluetoothLeAdvertiser is null only when the device truly can't be a BLE peripheral.
        val adv = a.bluetoothLeAdvertiser
        if (adv == null) {
            Log.w(TAG, "no BLE advertiser — this device can't act as a BLE peripheral")
            deviceRepo.setStatus(IosBridgeStatus.UNSUPPORTED)
            return
        }
        Log.i(
            TAG,
            "iOS bridge start: multiAdv=${a.isMultipleAdvertisementSupported} " +
                    "extAdv=${runCatching { a.isLeExtendedAdvertisingSupported }.getOrNull()} " +
                    "maxAdvData=${runCatching { a.leMaximumAdvertisingDataLength }.getOrNull()}",
        )
        advertiser = adv
        deviceRepo.setStatus(IosBridgeStatus.ADVERTISING)
        running = true
        reconnectAttempts = 0
        resettingPeripheral = false
        registerBondReceiver()
        openPeripheral()
        // Re-arm CompanionDeviceManager presence (if the user set up background auto-connect) so the OS keeps
        // waking us when the iPhone is in range — survives reboots / process death.
        IosCompanion.observePresence(context)
        tryAttachConnected()
    }

    fun stop() {
        advertisingLosses.reset()
        running = false
        clearEnrollment()
        setupDevice = null
        resettingPeripheral = false
        handler.removeCallbacks(attachRunnable)
        handler.removeCallbacks(peripheralRestartRunnable)
        stopAdvertising()
        if (receiverRegistered) runCatching { context.unregisterReceiver(bondReceiver) }.also {
            receiverRegistered = false
        }
        if (adapterReceiverRegistered) runCatching { context.unregisterReceiver(adapterReceiver) }.also {
            adapterReceiverRegistered = false
        }
        client?.disconnectAndClose(); client = null
        amsBridge.onGone()
        // Actively drop the iPhone's link to our GATT server too (not just release our handle, which leaves the
        // ACL up and the iPhone showing "Connected"/Sharing), then close the server.
        runCatching { connectedDevice?.let { gattServer?.cancelConnection(it) } }
        connectedDevice = null
        runCatching { gattServer?.close() }; gattServer = null; serverCallback = null
        deviceRepo.clearDeviceName()
        deviceRepo.setStatus(IosBridgeStatus.OFF)
    }

    // ---- Advertising + GATT server (peripheral role) ----

    private fun desiredAdvertisingMode() = IphoneAppSetup.advertisingMode(appAdvertising, selectedPeer, lePeers.keys)

    private fun syncAdvertising() {
        if (!running || !advertisingReady || resettingPeripheral) return
        val adv = advertiser ?: return
        val desired = desiredAdvertisingMode()
        if (desired == advertisingMode) return
        val callback = advertisingCallback
        if (callback == null) {
            if (desired != null) startAdvertising(adv, desired)
        } else if (advertisingSet != null && advertisingMode != null) {
            // Reuse the controller's single advertising slot only after it confirms the old set stopped.
            val previous = advertisingMode
            advertisingMode = null
            runCatching { adv.stopAdvertisingSet(callback) }.onFailure {
                advertisingMode = previous
                Log.w(TAG, "Couldn't stop iPhone advertising", it)
            }
        }
        // A pending start or stop callback rechecks the latest selection and link state.
    }

    private fun startAdvertising(adv: BluetoothLeAdvertiser, mode: IphoneAdvertisement) {
        // The legacy startAdvertising() path silently drops service-solicitation UUIDs on many OEM stacks
        // (confirmed here: nRF Connect saw no solicitation). startAdvertisingSet() is the modern path and
        // reports a distinct status if the controller rejects the data. Keep setLegacyMode(true): iOS only
        // scans LEGACY advertisements for the Settings → Bluetooth accessory / ANCS flow.
        val params = AdvertisingSetParameters.Builder()
            .setLegacyMode(true)
            .setConnectable(true)
            .setScannable(true)
            .setDiscoverable(mode != IphoneAdvertisement.RECONNECT)
            .setInterval(AdvertisingSetParameters.INTERVAL_MEDIUM)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MEDIUM)
            .build()
        // The public setup scan response may include the name; reconnect packets carry only the marker.
        val data = IphoneAppSetup.advertisement(mode)
        val scanResponse = if (mode == IphoneAdvertisement.RECONNECT) null else AdvertiseData.Builder()
            .setIncludeDeviceName((adapter?.name?.toByteArray()?.size ?: 0) <= 29)
            .build()
        val callback = object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
                if (advertisingCallback !== this) return
                if (status == ADVERTISE_SUCCESS) {
                    advertisingSet = set
                    Log.i(TAG, "iPhone advertising started ($mode)")
                    syncAdvertising()
                } else {
                    Log.w(TAG, "iPhone advertising failed: status=$status")
                    advertisingStartFailed(mode)
                }
            }

            override fun onAdvertisingSetStopped(set: AdvertisingSet?) {
                if (advertisingCallback !== this) return
                advertisingCallback = null
                advertisingSet = null
                advertisingMode = null
                advertisingLosses.advertisingStopped()
                Log.i(TAG, "iPhone advertising stopped ($mode)")
                syncAdvertising()
            }
        }
        advertisingMode = mode
        advertisingCallback = callback
        advertisingLosses.advertisingRequested(SystemClock.elapsedRealtime())
        runCatching {
            adv.startAdvertisingSet(
                params,
                data,
                scanResponse,
                null,
                null,
                callback
            )
        }
            .onFailure {
                Log.w(TAG, "startAdvertisingSet threw", it)
                advertisingStartFailed(mode)
            }
    }

    private fun advertisingStartFailed(mode: IphoneAdvertisement) {
        advertisingMode = null
        advertisingCallback = null
        if (desiredAdvertisingMode() == mode) deviceRepo.setStatus(IosBridgeStatus.ERROR) else syncAdvertising()
    }

    private var advertisingCallback: AdvertisingSetCallback? = null

    private fun stopAdvertising() {
        advertisingReady = false
        advertisingMode = null
        advertisingLosses.advertisingStopped()
        val callback = advertisingCallback
        advertisingCallback = null
        advertisingSet = null
        if (callback != null) runCatching { advertiser?.stopAdvertisingSet(callback) }
    }

    private fun openPeripheral() {
        val useApp = setupPreferences.usesApp
        if (appAdvertising != useApp) advertisingLosses.reset()
        appAdvertising = useApp
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        // In app setup, wait for service registration before allowing the iPhone to discover us.
        val callback = object : BluetoothGattServerCallback() {
            override fun onServiceAdded(status: Int, service: BluetoothGattService) {
                handler.post {
                    if (!running || serverCallback !== this || service.uuid != IphoneAppSetup.serviceId) return@post
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        advertisingReady = true
                        syncAdvertising()
                    } else deviceRepo.setStatus(IosBridgeStatus.ERROR)
                }
            }

            override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int,
                                                     characteristic: BluetoothGattCharacteristic) {
                handler.post {
                    if (!running || serverCallback !== this) return@post
                    updateSelection()
                    val selected = selectedPeer
                    val status = when {
                        characteristic.uuid != IphoneAppSetup.versionId -> BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                        selected != null && !device.address.equals(selected.address, ignoreCase = true) ->
                            BluetoothGatt.GATT_INSUFFICIENT_AUTHORIZATION
                        offset !in 0..1 -> BluetoothGatt.GATT_INVALID_OFFSET
                        else -> BluetoothGatt.GATT_SUCCESS
                    }
                    val value = if (status == BluetoothGatt.GATT_SUCCESS && offset == 0) byteArrayOf(1) else byteArrayOf()
                    if (gattServer?.sendResponse(device, requestId, status, offset, value) != true ||
                        status != BluetoothGatt.GATT_SUCCESS) return@post
                    if (selected == null) setupDevice = device else attach(listOf(device))
                }
            }

            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                handler.post {
                    if (!running || resettingPeripheral || serverCallback !== this) return@post
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> if (status == BluetoothGatt.GATT_SUCCESS) {
                            pairingCandidates.serverConnectionChanged(device.address, connected = true)
                            if (leLinkState(device) == true || device.address in lePeers) {
                                lePeers[device.address] = device
                                publishLePeers()
                            }
                            updateSelection()
                            attach(listOf(device))
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            pairingCandidates.serverConnectionChanged(device.address, connected = false)
                            if (leLinkState(device) != true) lePeers.remove(device.address)
                            publishLePeers()
                            if (device == setupDevice && leLinkState(device) != true) setupDevice = null
                            if (device == connectedDevice && leLinkState(device) != true) {
                                onCentralDisconnected()
                                scheduleAttach()
                            }
                        }
                    }
                    syncAdvertising()
                }
            }
        }
        serverCallback = callback
        val server = mgr.openGattServer(context, callback)
        gattServer = server
        if (server == null || (appAdvertising && !server.addService(IphoneAppSetup.service()))) {
            deviceRepo.setStatus(IosBridgeStatus.ERROR)
        } else if (!appAdvertising) {
            advertisingReady = true
            syncAdvertising()
        }
    }

    /** Called on the main thread when setup or Forget changes the selected phone. */
    fun refreshSelection() {
        if (running && appAdvertising != setupPreferences.usesApp) {
            stop()
            start()
        } else tryAttachConnected()
    }

    private fun clearEnrollment() {
        enrolling = null
        pairingCandidates.clearLinks()
        lePeers.clear()
        deviceRepo.setConnectedLePeers(emptyList())
    }

    private fun publishLePeers() {
        val peers = lePeers.values.map { device ->
            IosCompanion.Peer(device.address, device.name, pairingCandidates.offer(device.address) { device.hasBridgeBond() })
        }
        deviceRepo.setConnectedLePeers(peers.sortedWith(compareBy({ it.name.orEmpty() }, { it.address })))
    }

    internal fun currentPairingOffer(address: String): IphonePairingCandidates.Offer? {
        val device = lePeers[address] ?: return null
        if (leLinkState(device) == false) return null
        return pairingCandidates.offer(address) { device.hasBridgeBond() }
    }

    /** A Classic CDM selection cannot authenticate a separate, unbonded LE address. Pair only the
     * live endpoint the Android user chose and persist it only after Bluetooth confirms the bond. */
    fun chooseConnectedPeer(address: String): Boolean {
        val device = lePeers[address] ?: return false
        if (leLinkState(device) == false) return false
        if (device.hasBridgeBond()) {
            acceptBondedEndpoint(device)
            return true
        }
        val requested = runCatching {
            device.bondState == BluetoothDevice.BOND_BONDING || createLeBond(device)
        }.getOrDefault(false)
        if (requested) {
            enrolling = device
            deviceRepo.setStatus(IosBridgeStatus.NEEDS_PAIRING)
        }
        return requested
    }

    private fun acceptBondedEndpoint(device: BluetoothDevice) {
        enrolling = null
        IosCompanion.saveConfirmedAddress(context, device.address)
        updateSelection()
        attach(listOf(device))
    }

    /** Bind the encrypted read to the user's on-screen confirmation, including separate LE identities. */
    fun confirmSetupPeer(address: String): Boolean {
        val device = setupDevice ?: return false
        if (device.address != address || leLinkState(device) == false || !device.hasBridgeBond()) return false
        acceptBondedEndpoint(device)
        return true
    }

    fun retrySharing() {
        if (deviceRepo.status.value == IosBridgeStatus.NO_ANCS) client?.rediscoverServices()
    }

    private fun updateSelection() {
        val next = IosCompanion.selectedPeer(context)
        val previous = selectedPeer
        selectedPeer = next
        if (previous?.address == next?.address) return
        enrolling = null
        setupDevice = null
        reconnectAttempts = 0
        handler.removeCallbacks(attachRunnable)
        if (client != null) onCentralDisconnected()
        // Deferred dismissals and content keys belong to the previous phone.
        keyToContent.clear()
        pendingClear.clear()
        syncAdvertising()
    }

    private fun attach(candidates: List<BluetoothDevice>) {
        if (client != null) return
        val device = selectedPeer?.findBonded(candidates, BluetoothDevice::getAddress) {
            runCatching { it.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false)
        } ?: return

        if (leLinkState(device) == false) return
        handler.removeCallbacks(attachRunnable)
        connectedDevice = device
        refreshDeviceName(device)
        lateinit var c: IosGattClient
        c = IosGattClient(
            context = context,
            device = device,
            onStatus = { status ->
                if (client === c) {
                    deviceRepo.setStatus(status)
                    if (status == IosBridgeStatus.SHARING) {
                        reconnectAttempts = 0
                        handler.removeCallbacks(attachRunnable)
                        refreshDeviceName(device)
                    }
                }
            },
            onSourceEvent = { if (client === c) onSourceEvent(it) },
            onBondNeeded = { if (client === c) ensureBond(device) },
            onDeviceName = { name ->
                if (client === c) {
                    IosCompanion.saveGapName(context, device.address, name)
                    refreshDeviceName(device)
                    Log.i(TAG, "Read the selected iPhone’s GAP device name")
                }
            },
            onLeConnected = {
                if (client === c && appAdvertising) {
                    lePeers[device.address] = device
                    syncAdvertising()
                }
            },
            onClientGone = {
                if (client === c) {
                    onCentralDisconnected()
                    scheduleAttach()
                }
            },
            scope = scope,
            onAmsEntityUpdate = { if (client === c) amsBridge.onEntityUpdate(it) },
            onAmsSupportedCommands = { if (client === c) amsBridge.onSupportedCommands(it) },
        )
        client = c
        try {
            c.connect()
        } catch (e: SecurityException) {
            Log.w(TAG, "Bluetooth permission revoked while connecting", e)
            onCentralDisconnected()
            deviceRepo.setStatus(IosBridgeStatus.ERROR)
        }
    }

    // GATT server callbacks and the system connection list can both refer to a Classic-only ACL.
    private fun leLinkState(device: BluetoothDevice): Boolean? =
        if (Build.VERSION.SDK_INT >= 36 && Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1) {
            runCatching { device.isConnected(BluetoothDevice.TRANSPORT_LE) }.getOrDefault(false)
        } else null

    /** Android reports system-wide GATT connections, including accessories using other profiles. */
    private fun tryAttachConnected() {
        if (!running || resettingPeripheral) return
        updateSelection()
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return
        val connected = IosCompanion.connectedGattDevices(manager)
        for (device in connected) if (leLinkState(device) == true) lePeers[device.address] = device
        publishLePeers()
        if (client == null && selectedPeer != null) attach(connected)
        syncAdvertising()
    }

    /**
     * Schedule [tryAttachConnected] with capped exponential backoff (reset on a successful SHARING). But a
     * re-attach only rebuilds the GATT *client* over the EXISTING link — when that link is wedged (a stale ACL
     * our GATT *server* connection keeps alive, surfacing as "Connected, but notifications aren't shared"), it
     * lands on the same dead link every cycle and never reaches SHARING. So after [MAX_LIGHT_RECOVERY_ATTEMPTS]
     * such cycles, stop re-attaching and do a full peripheral reset instead — the automatic equivalent of the
     * user toggling the iOS bridge switch off/on, which drops both GATT roles so the iPhone reconnects fresh.
     */
    private fun scheduleAttach() {
        if (!running || resettingPeripheral) return
        handler.removeCallbacks(attachRunnable)
        if (reconnectAttempts >= MAX_LIGHT_RECOVERY_ATTEMPTS) {
            restartPeripheral()
            return
        }
        val delay = minOf(2_000L * (reconnectAttempts + 1), 20_000L)
        reconnectAttempts++
        handler.postDelayed(attachRunnable, delay)
    }

    /**
     * Full peripheral reset — the automatic equivalent of the user toggling the iOS bridge switch off/on,
     * reached only once lightweight re-attaches have repeatedly failed to reach SHARING ([scheduleAttach]).
     * Drop the GATT client AND actively cancel the iPhone's link to our GATT *server* — the latter is what a
     * client-only watchdog disconnect leaves up, pinning the wedged ACL so a re-attach just rebuilds on the
     * same stale link — then stop advertising and close the server so the iPhone sees a genuine drop. After a
     * settle, [reopenPeripheral] re-advertises and the iPhone reconnects on a fresh link.
     */
    private fun restartPeripheral() {
        Log.w(
            TAG,
            "notifications still not sharing after $reconnectAttempts recovery attempts — full peripheral reset"
        )
        resettingPeripheral = true
        clearEnrollment()
        setupDevice = null
        reconnectAttempts = 0
        handler.removeCallbacks(attachRunnable)
        handler.removeCallbacks(peripheralRestartRunnable)
        client?.disconnectAndClose(); client = null
        amsBridge.onGone()
        runCatching { connectedDevice?.let { gattServer?.cancelConnection(it) } }
        connectedDevice = null
        stopAdvertising()
        runCatching { gattServer?.close() }; gattServer = null; serverCallback = null
        deviceRepo.clearDeviceName()
        deviceRepo.setStatus(IosBridgeStatus.CONNECTING) // transient "recovering"; reopen flips to ADVERTISING
        // Let the stack release the server + advertiser before reopening — a synchronous reopen races the close
        // on some OEM stacks (same settle rationale as the GATT client's refresh/discovery delays).
        handler.postDelayed(peripheralRestartRunnable, PERIPHERAL_RESTART_SETTLE_MS)
    }

    /** Second half of [restartPeripheral]: re-open the GATT server and re-advertise after the settle. */
    private fun reopenPeripheral() {
        if (!running) return
        resettingPeripheral = false
        val a = adapter
        val adv = a?.bluetoothLeAdvertiser
        if (a == null || !a.isEnabled || adv == null) {
            // Bluetooth went away during the settle. Reset [running] so the adapter-on receiver's start() (or
            // the iOS tab's auto-resume) can bring the bridge back cleanly — leaving it true would make start()
            // no-op on its `running` guard and strand us with no advertising.
            Log.w(TAG, "peripheral reopen: adapter unavailable — will resume when Bluetooth returns")
            running = false
            deviceRepo.setStatus(IosBridgeStatus.ERROR)
            return
        }
        Log.i(TAG, "peripheral reopen: re-opening GATT server + advertising")
        advertiser = adv
        deviceRepo.setStatus(IosBridgeStatus.ADVERTISING)
        openPeripheral()
        tryAttachConnected()
    }

    private fun onCentralDisconnected() {
        Log.i(TAG, "central disconnected — back to advertising")
        connectedDevice?.let { if (leLinkState(it) != true) lePeers.remove(it.address) }
        client?.close(); client = null
        connectedDevice = null
        amsBridge.onGone() // media state is session-scoped like the UID map below
        uidToKey.clear() // UIDs are session-scoped; drop the removal map on disconnect
        liveCallKeys.clear() // ditto — live-call protection is scoped to the connection
        selfClearedUids.clear() // ditto — pending self-clear confirmations don't carry across sessions
        deviceRepo.clearDeviceName() // no device connected — don't leave a stale name in the FGS / tab / group labels
        deviceRepo.setStatus(IosBridgeStatus.ADVERTISING)
        syncAdvertising()
    }

    private fun refreshDeviceName(device: BluetoothDevice) {
        if (device == connectedDevice) deviceRepo.setDeviceName(
            IosCompanion.selectedDeviceName(context, selectedPeer) ?: context.getString(net.extrawdw.apps.notisync.R.string.mirror_ios_device_fallback)
        )
    }

    // ---- Bonding ----

    private fun createLeBond(device: BluetoothDevice) = if (Build.VERSION.SDK_INT >= 37) {
        device.createBond(BluetoothDevice.TRANSPORT_LE)
    } else device.createBond()

    private fun ensureBond(device: BluetoothDevice) {
        Log.i(TAG, "ensureBond: bondState=${device.bondState}")
        if (!device.hasBridgeBond()) {
            val started = runCatching { createLeBond(device) }.getOrDefault(false)
            Log.i(TAG, "createBond() -> $started")
        } else {
            client?.onBonded()
        }
    }

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
            if (!running || resettingPeripheral) return
            val transport = intent.getIntExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_AUTO)
            if (intent.action == BluetoothDevice.ACTION_PAIRING_REQUEST) {
                val passkeyConfirmation = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1) ==
                    BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION
                val repair = Build.VERSION.SDK_INT >= 37 && intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_CONTEXT, -1) ==
                    BluetoothDevice.PAIRING_CONTEXT_REPAIRING
                if (transport != BluetoothDevice.TRANSPORT_BREDR && (passkeyConfirmation || repair)) {
                    pairingCandidates.repairRequested(device.address)
                }
                return
            }
            if (Build.VERSION.SDK_INT >= 36 && Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1 &&
                intent.action == BluetoothDevice.ACTION_KEY_MISSING) {
                if (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_LOSS_REASON, -1) == BluetoothDevice.BOND_LOSS_REASON_LE_INCOMING_PAIRING) {
                    pairingCandidates.repairRequested(device.address)
                }
                return
            }
            if (Build.VERSION.SDK_INT >= 36 && intent.action == BluetoothDevice.ACTION_ENCRYPTION_CHANGE) {
                if (transport == BluetoothDevice.TRANSPORT_LE) {
                    val encrypted = intent.getBooleanExtra(BluetoothDevice.EXTRA_ENCRYPTION_ENABLED, false) &&
                        intent.getIntExtra(BluetoothDevice.EXTRA_ENCRYPTION_STATUS, -1) == 0
                    if (pairingCandidates.leEncryptionChanged(device.address, encrypted, SystemClock.elapsedRealtime())) {
                        Log.i(TAG, "iPhone setup observed an encrypted LE re-pair")
                        publishLePeers()
                    }
                }
                return
            }
            if (intent.action == BluetoothDevice.ACTION_ACL_CONNECTED || intent.action == BluetoothDevice.ACTION_ACL_DISCONNECTED) {
                if (transport != BluetoothDevice.TRANSPORT_LE) return
                if (intent.action == BluetoothDevice.ACTION_ACL_CONNECTED) {
                    pairingCandidates.leConnected(device.address, device.hasBridgeBond(), SystemClock.elapsedRealtime())
                    lePeers[device.address] = device
                    updateSelection()
                    attach(listOf(device))
                } else {
                    pairingCandidates.leDisconnected(device.address)
                    lePeers.remove(device.address)
                    if (device == enrolling) {
                        enrolling = null
                        if (client == null) deviceRepo.setStatus(IosBridgeStatus.ADVERTISING)
                    }
                }
                publishLePeers()
                syncAdvertising()
                return
            }
            val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
            if (device.address in lePeers && (state == BluetoothDevice.BOND_NONE || state == BluetoothDevice.BOND_BONDED)) {
                publishLePeers()
            }
            if (device == enrolling) {
                if (state == BluetoothDevice.BOND_BONDED && device.hasBridgeBond()) acceptBondedEndpoint(device)
                else if (state == BluetoothDevice.BOND_NONE) {
                    enrolling = null
                    if (client == null) deviceRepo.setStatus(IosBridgeStatus.ADVERTISING)
                }
                return
            }
            Log.i(TAG, "bond state -> $state for ${device.address}")
            if (!device.address.equals(selectedPeer?.address, ignoreCase = true)) return
            when (state) {
                BluetoothDevice.BOND_BONDED -> if (client == null) tryAttachConnected() else if (device.hasBridgeBond()) client?.onBonded()
                BluetoothDevice.BOND_NONE -> if (device == connectedDevice) onCentralDisconnected()
            }
        }
    }

    private fun registerBondReceiver() {
        if (receiverRegistered) return
        // Bluetooth runs under its own UID and sends this protected system broadcast.
        ContextCompat.registerReceiver(
            context, bondReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED).apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                if (Build.VERSION.SDK_INT >= 36) {
                    addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
                    addAction(BluetoothDevice.ACTION_ENCRYPTION_CHANGE)
                    if (Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1) addAction(BluetoothDevice.ACTION_KEY_MISSING)
                }
            },
            ContextCompat.RECEIVER_EXPORTED,
        )
        receiverRegistered = true
    }

    // ---- Bluetooth adapter on/off ----

    /** Recover from the user toggling Bluetooth: re-advertise on STATE_ON, tear the dead link down on OFF.
     *  Registered even while BT is off (see [start]) so STATE_ON always reaches us. */
    private val adapterReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> {
                    Log.i(TAG, "Bluetooth on — restarting ANCS advertising"); start()
                }

                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    val repeatedLoss = advertisingLosses.adapterWentDown(SystemClock.elapsedRealtime())
                    onBluetoothOff()
                    if (repeatedLoss) {
                        Log.w(TAG, "Repeated Bluetooth adapter losses after advertising (appSetup=$appAdvertising)")
                        setupPreferences.recordRepeatedLoss()
                        if (!appAdvertising) advertisingLosses.reset() // Resume in app mode on STATE_ON.
                        onRepeatedAdvertisingLoss(appAdvertising)
                    }
                }
            }
        }
    }

    private fun registerAdapterReceiver() {
        if (adapterReceiverRegistered) return
        // The Bluetooth process sends this protected system broadcast under its own UID.
        ContextCompat.registerReceiver(
            context, adapterReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        adapterReceiverRegistered = true
    }

    /** BT was turned off: the advertiser/GATT/client are now dead — drop them and reset [running] so STATE_ON
     *  re-runs [start] cleanly. Keep both receivers registered; leave the FGS up (the tab's switch owns teardown). */
    private fun onBluetoothOff() {
        if (!running && advertisingSet == null && client == null) return // already torn down
        Log.i(TAG, "Bluetooth off — tearing down BLE; will re-advertise when it returns")
        running = false
        clearEnrollment()
        setupDevice = null
        resettingPeripheral = false
        handler.removeCallbacks(attachRunnable)
        handler.removeCallbacks(peripheralRestartRunnable)
        stopAdvertising()
        client?.close(); client = null
        connectedDevice = null
        amsBridge.onGone()
        runCatching { gattServer?.close() }; gattServer = null; serverCallback = null
        deviceRepo.clearDeviceName() // link torn down — drop the stale name
        deviceRepo.setStatus(IosBridgeStatus.ERROR)
    }

    // ---- Notification handling ----

    private fun onSourceEvent(packet: Ancs.SourcePacket) {
        val c = client ?: return
        // Diagnostic trace of the raw ANCS stream: we ADD / REMOVE a mirror only in response to these events —
        // there is NO independent local timer — so a call mirror that vanishes in under a second means iOS itself
        // sent us EVENT_REMOVED (evt=2) for it. evt: 0=added 1=modified 2=removed; cat 1=incoming-call.
        Log.i(
            TAG,
            "ANCS src evt=${packet.eventId} cat=${packet.categoryId} uid=${packet.notificationUid}" +
                " flags=0x${packet.eventFlags.toString(16)} pos=${packet.hasPositiveAction}" +
                " neg=${packet.hasNegativeAction} preExisting=${packet.isPreExisting}"
        )
        val sourcePhone = iphoneId()
        val sourceName = deviceRepo.deviceName.value
        scope.launch {
            if (client !== c) return@launch
            if (packet.isRemoved) {
                onRemoved(packet); return@launch
            }
            // The Notification Source packet has no app id — we must fetch attributes first, then filter.
            // Action labels ride along only when a positive action is advertised: a lone negative action
            // ("Clear") isn't mirrored anyway (see AncsNotificationMapper.mapActions), so the common
            // notification skips the two extra attribute reads.
            val attrs = c.fetchNotificationAttributes(
                packet.notificationUid,
                // Also fetch labels for a call (incoming, or the active-call category) advertising only a negative
                // "Hang up"/"End Call" with no positive, so the ongoing-call Hang up button has an action to fire;
                // a lone negative on a non-call notification is just "Clear" and stays skipped.
                includeActionLabels = packet.hasPositiveAction ||
                    (Ancs.isCallCategory(packet.categoryId) && packet.hasNegativeAction),
            ) ?: return@launch
            val bundleId = attrs.appId ?: return@launch
            val displayName = appNameCache.getOrPut(bundleId) {
                BundleIdMap.displayName(bundleId) ?: c.fetchAppDisplayName(bundleId) ?: prettyName(
                    bundleId
                )
            }
            registry.recordSeen(
                bundleId,
                displayName,
                System.currentTimeMillis()
            ) // surface for opt-in even if not enabled
            if (!registry.isEnabled(bundleId)) return@launch

            val record = AncsRecord(
                packet,
                bundleId,
                displayName,
                attrs.title,
                attrs.subtitle,
                attrs.message,
                attrs.date,
                positiveActionLabel = attrs.positiveActionLabel,
                negativeActionLabel = attrs.negativeActionLabel,
            )
            val androidPkg = iconResolver.androidPackageForIos(bundleId, displayName)
            // A connect-time backlog (PreExisting) shouldn't blast every mesh device with old alerts: show it
            // locally but quietly, and don't mirror it. Fresh notifications flow to both sinks normally.
            val toMesh = !packet.isPreExisting && meshMirrorEnabled()
            var notif = AncsNotificationMapper.map(
                clientId,
                record,
                sourcePhone,
                sourceName,
                androidPkg,
                System.currentTimeMillis(),
            )
            // Only ship the app icon as an asset when mirroring — a local render resolves the icon directly.
            if (toMesh) notif = attachAppIcon(notif, androidPkg)
            if (client !== c) return@launch
            uidToKey[packet.notificationUid] = notif.sourceKey
            // Track a live call (ringing or active) so its dismissal can't decline / hang up the real call on the
            // iPhone (see [liveCallKeys]); only the explicit Decline / Hang up button may.
            if (notif.callType == CallType.INCOMING || notif.callType == CallType.ONGOING) {
                liveCallKeys.add(notif.sourceKey)
            }
            val contentKey = contentKeyOf(record)
            keyToContent[notif.sourceKey] = contentKey
            // Dismissed earlier while the iPhone was unreachable? This replay (fresh UID this session) is our
            // chance to clear it on the source instead of re-showing a notification the user already swiped.
            if (pendingClear.remove(contentKey)) {
                // A parked dismissal drained on replay → we PerformNotificationAction NEGATIVE and never render.
                // For a call NEGATIVE = Decline/Hang-up, so if this fires for cat=1 it would end the call on
                // arrival (and it never shows) — logged so it can be told apart from an iOS-side removal.
                Log.i(TAG, "ANCS auto-clear on replay uid=${packet.notificationUid} cat=${packet.categoryId} → NEGATIVE (parked dismissal)")
                selfClearedUids.add(packet.notificationUid) // our own clear → don't re-broadcast its removal
                runCatching { c.performAction(packet.notificationUid, Ancs.ACTION_NEGATIVE) }
                return@launch
            }

            if (localDisplayEnabled()) {
                // Backlog (PreExisting) replays quietly: post it silently rather than lowering its importance —
                // a lowered importance would pin the channel to Silent for good (see MirrorChannels.ensure).
                renderLocal(notif, packet.isPreExisting)
            }
            if (toMesh) runCatching { captureToMesh(notif) }
        }
    }

    /** Attach the app icon as an asset when the mapped app is installed on THIS phone (else leave it to the
     *  consumer's resolution chain). Deduped by the asset layer, so it uploads at most once per app. */
    private suspend fun attachAppIcon(
        notif: CapturedNotification,
        androidPkg: String?
    ): CapturedNotification {
        val pkg = androidPkg ?: return notif
        val bytes = appIconBytes(pkg) ?: return notif
        val ref = uploadAsset(bytes, AssetRole.APP_ICON, MIME_WEBP, clientId) ?: return notif
        return notif.copy(appIcon = ref)
    }

    private fun onRemoved(packet: Ancs.SourcePacket) {
        val key = uidToKey.remove(packet.notificationUid)
        // Diagnostic: this is the ONLY reason an ANCS mirror is cleared from the iPhone side. If a ringing-call
        // mirror disappears, expect this line right before it — the iPhone removed the notification (whether the
        // caller hung up, it went to voicemail, iOS handled the call in its own UI, or the UID rotated).
        Log.i(
            TAG,
            "ANCS removed uid=${packet.notificationUid} key=$key liveCall=${key != null && key in liveCallKeys}"
        )
        if (key == null) return
        liveCallKeys.remove(key) // call ended on the iPhone — no longer a live call to protect
        // Drop the content mapping first: the dismissMesh below routes back through [dismissOnIphone], and with
        // the notification already gone from the iPhone we don't want it to (re-)park a clear for a dead key.
        keyToContent.remove(key)
        clearLocal(clientId, key)
        // If this removal is the iPhone confirming a clear WE performed (dismiss-through), the dismissal that
        // triggered it already reached the mesh — re-broadcasting would echo it back to the sender. Only a
        // removal the user made on the iPhone itself propagates outward.
        if (selfClearedUids.remove(packet.notificationUid)) return
        scope.launch { runCatching { dismissMesh(clientId, key) } }
    }

    /**
     * Propagate a dismissal back to the iPhone: clear the source notification on iOS via a best-effort ANCS
     * negative ("Clear") action. Invoked for both a local swipe of the mirror and a dismissal relayed from
     * another NotiSync device — the iOS analogue of cancelling the original Android notification, which a local
     * swipe alone can't do (it removes our mirror, not the iPhone's notification). If the iPhone is connected
     * and this UID is still live this session we clear immediately; otherwise we park the dismissal by content
     * key and clear it when the notification next replays (under a fresh UID) — so one dismissed while the link
     * was down doesn't reappear on reconnect. No-op for non-ANCS keys.
     */
    fun dismissOnIphone(sourceKey: String) {
        val parts = sourceKey.split('|')
        if (parts.size < 4 || parts[0] != "ancs") return // not an ANCS source key
        // A live call (ringing OR active): a plain dismissal (mirror swipe, ring-window timeout, or a mesh-relayed
        // dismissal — all funnel here) must NOT perform the ANCS negative action — for a call that's DECLINE
        // (ringing) or HANG UP (active), which would drop the real call. Declining / hanging up requires the
        // explicit Decline / Hang up button (performAncsAction). Race-free: the NEGATIVE below only fires while
        // uidToKey still maps this uid, and [onRemoved] drops uidToKey before liveCallKeys — so a still-live call
        // is always still tracked here. The mirror is already gone locally; the call continues on the iPhone.
        if (sourceKey in liveCallKeys) return
        val uid = parts[3].toIntOrNull() ?: return
        // Live this session (same UID still mapped to this exact key) on our connected iPhone → clear now.
        val c = client
        if (c != null && parts[1] == iphoneId() && uidToKey[uid] == sourceKey) {
            selfClearedUids.add(uid) // our own clear → its EVENT_REMOVED must not re-broadcast to the mesh
            scope.launch { runCatching { c.performAction(uid, Ancs.ACTION_NEGATIVE) } }
            return
        }
        // Otherwise defer: clear it when it next replays. Keyed by content (not the rotating UID) so it matches
        // across the reconnect. Unknown only if this process never captured it — then it's a best-effort no-op.
        keyToContent[sourceKey]?.let { pendingClear.add(it) }
    }

    /**
     * Perform a mesh peer's (or the local card's) [ActionEvent] on the bridged iPhone. PERFORM is the ANCS
     * positive/negative action a mirrored button represents
     * ([net.extrawdw.notisync.protocol.NotificationAction.index] IS the ANCS ActionID); MEDIA is a transport
     * press on the mirrored now-playing card, replayed over AMS Remote Command. TAP never arrives: neither
     * service has an "open" command, and bridged captures keep `hasContentIntent` false.
     */
    fun performOnIphone(event: ActionEvent) {
        when (event.kind) {
            ActionKind.PERFORM -> performAncsAction(event)
            ActionKind.MEDIA -> performAmsCommand(event)
            else -> return
        }
    }

    /**
     * The ANCS action path. Unlike [dismissOnIphone] nothing is parked for a replay: firing "Answer"
     * minutes later against a re-advertised notification would be wrong, not helpful — a stale event
     * (link down, UID rotated) is simply dropped. A performed NEGATIVE action removes the notification
     * on the iPhone; that EVENT_REMOVED is a genuine state change the mesh should learn about, so it is
     * deliberately NOT self-clear-suppressed (unlike a dismissal echo, which already propagated).
     */
    private fun performAncsAction(event: ActionEvent) {
        if (event.actionIndex != Ancs.ACTION_POSITIVE && event.actionIndex != Ancs.ACTION_NEGATIVE) return
        val parts = event.sourceKey.split('|')
        if (parts.size < 4 || parts[0] != "ancs") return // not an ANCS source key
        val uid = parts[3].toIntOrNull() ?: return
        val c = client ?: return
        if (parts[1] != iphoneId() || uidToKey[uid] != event.sourceKey) return // stale UID / other iPhone
        scope.launch { runCatching { c.performAction(uid, event.actionIndex) } }
    }

    /**
     * The AMS media path: translate the mirrored session's [MediaCommand] to an AMS RemoteCommandID and
     * write it — iOS routes it to whatever app is the active player. Best-effort like the ANCS action: a
     * stale event (link down, another iPhone's card) is dropped, and iOS rejects a command the current
     * player doesn't support at ATT level. SEEK never arrives (AMS has no absolute seek, so the card
     * doesn't advertise ACTION_SEEK_TO); STOP maps to pause, the nearest AMS has.
     */
    private fun performAmsCommand(event: ActionEvent) {
        val parts = event.sourceKey.split('|')
        if (parts.size < 3 || parts[0] != AmsNotificationMapper.KEY_PREFIX) return // not an AMS source key
        val c = client ?: return
        if (parts[1] != iphoneId()) return // another (or no longer connected) iPhone's card
        val command = when (event.mediaCommand) {
            MediaCommand.PLAY -> Ams.CMD_PLAY
            MediaCommand.PAUSE -> Ams.CMD_PAUSE
            MediaCommand.PLAY_PAUSE -> Ams.CMD_TOGGLE_PLAY_PAUSE
            MediaCommand.NEXT -> Ams.CMD_NEXT_TRACK
            MediaCommand.PREVIOUS -> Ams.CMD_PREVIOUS_TRACK
            MediaCommand.STOP -> Ams.CMD_PAUSE
            MediaCommand.CUSTOM ->
                event.mediaCustomAction?.let(AmsNotificationMapper::commandOfCustomAction) ?: return

            // AMS volume is relative-only, so a ±1 adjust maps to VolumeUp/Down; an absolute SET can't be
            // expressed by AMS and is intentionally dropped below.
            MediaCommand.ADJUST_VOLUME -> when {
                (event.mediaVolume ?: 0) > 0 -> Ams.CMD_VOLUME_UP
                (event.mediaVolume ?: 0) < 0 -> Ams.CMD_VOLUME_DOWN
                else -> return
            }

            MediaCommand.SET_VOLUME, MediaCommand.SEEK, null -> return
        }
        scope.launch { runCatching { c.sendMediaCommand(command) } }
    }

    /** The media master toggle turned off — tear the now-playing card down now (mesh + local). */
    fun onMediaMirrorDisabled() {
        amsBridge.onDisabled()
    }

    // ---- Helpers ----

    /** A reconnect-stable identity for an iPhone notification: the ANCS UID rotates per session, but the app +
     *  original date + text do not — so this is what matches a parked dismissal to the notification's replay. */
    private fun contentKeyOf(record: AncsRecord): String =
        "${record.bundleId}\u001F${record.date}\u001F${record.title}\u001F${record.message}"

    /** A stable, non-identifying id for the bonded iPhone (hashed MAC) for the mirror source key. */
    private fun iphoneId(): String = connectedDevice?.address?.let { addr ->
        MessageDigest.getInstance("SHA-256").digest(addr.toByteArray())
            .take(6).joinToString("") { "%02x".format(it) }
    } ?: "iphone"

    private fun prettyName(bundleId: String): String =
        bundleId.substringAfterLast('.').ifBlank { bundleId }.replaceFirstChar { it.uppercase() }

    private fun hasPermissions(): Boolean = IosBluetoothPermissions.hasRequired(context)

    private companion object {
        const val TAG = "IosBridgeManager"
        const val MIME_WEBP = "image/webp"

        /** Lightweight client-only re-attaches to try before escalating to a full peripheral reset. Each cycle
         *  is one failed session (~30s GATT-client watchdog + backoff), so this is ~1.5 min of self-healing on
         *  the existing link before the heavier reset that drops the server link and re-advertises. */
        const val MAX_LIGHT_RECOVERY_ATTEMPTS = 3

        /** Settle between tearing the peripheral down and re-advertising, so the stack releases the server +
         *  advertiser before we reopen (a synchronous reopen races the close on some OEM stacks). */
        const val PERIPHERAL_RESTART_SETTLE_MS = 800L
    }
}

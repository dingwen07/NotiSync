package net.extrawdw.apps.notisync.ancs

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattServer
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
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.extrawdw.apps.notisync.appicon.BundleIdMap
import net.extrawdw.apps.notisync.appicon.IconResolver
import net.extrawdw.notisync.protocol.AssetRole
import net.extrawdw.notisync.protocol.CapturedNotification
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.PrivateAssetRef
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Orchestrates the ANCS bridge: advertises this phone as a BLE peripheral soliciting the ANCS service (so it
 * appears in the iPhone's Bluetooth settings), accepts the iPhone's connection via a GATT server, then runs
 * an [AncsGattClient] over that link to consume notifications. Each ANCS notification is fetched, resolved to
 * an app name + icon, filtered by the user's per-app opt-in, and dispatched to local display and/or the mesh.
 *
 * Dependencies are injected as plain function references (manual-DI house style) so this stays decoupled from
 * [net.extrawdw.apps.notisync.AppGraph] and unit-reasonable.
 */
@SuppressLint("MissingPermission")
class AncsBleManager(
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
    private val localDisplayEnabled: () -> Boolean,
    private val meshMirrorEnabled: () -> Boolean,
    /** Seal the capture to the own mesh ([MirrorEngine.captureLocal]). */
    private val captureToMesh: suspend (CapturedNotification) -> Int,
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
    private var advertiser: BluetoothLeAdvertiser? = null

    @Volatile
    private var client: AncsGattClient? = null

    @Volatile
    private var connectedDevice: BluetoothDevice? = null

    @Volatile
    private var lastDevice: BluetoothDevice? =
        null // the iPhone we were last talking to, for targeted re-attach

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
    private var reconnectAttempts = 0

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

    fun start() {
        if (running) return // idempotent: the foreground service may re-deliver onStartCommand
        if (!hasPermissions()) {
            Log.w(
                TAG,
                "missing BLUETOOTH_CONNECT/ADVERTISE"
            ); deviceRepo.setStatus(AncsStatus.ERROR); return
        }
        val a = adapter
        if (a == null) {
            Log.w(TAG, "no Bluetooth adapter on this device")
            deviceRepo.setStatus(AncsStatus.ERROR)
            return
        }
        // Arm this BEFORE the isEnabled bail so we hear STATE_ON and re-advertise when the user turns
        // Bluetooth back on — instead of parking in ERROR until they reopen the iOS tab.
        registerAdapterReceiver()
        if (!a.isEnabled) {
            Log.w(TAG, "Bluetooth is off — will re-advertise when it's turned back on")
            deviceRepo.setStatus(AncsStatus.ERROR)
            return
        }
        // Gate on the ADVERTISER being available — NOT isMultipleAdvertisementSupported, which asks for
        // *concurrent* advertising sets (far stricter) and is false on many phones that advertise one set
        // fine. bluetoothLeAdvertiser is null only when the device truly can't be a BLE peripheral.
        val adv = a.bluetoothLeAdvertiser
        if (adv == null) {
            Log.w(TAG, "no BLE advertiser — this device can't act as a BLE peripheral")
            deviceRepo.setStatus(AncsStatus.UNSUPPORTED)
            return
        }
        Log.i(
            TAG,
            "ANCS bridge start: multiAdv=${a.isMultipleAdvertisementSupported} " +
                    "extAdv=${runCatching { a.isLeExtendedAdvertisingSupported }.getOrNull()} " +
                    "maxAdvData=${runCatching { a.leMaximumAdvertisingDataLength }.getOrNull()}",
        )
        advertiser = adv
        openGattServer()
        startAdvertising(adv)
        registerBondReceiver()
        running = true
        reconnectAttempts = 0
        // Re-arm CompanionDeviceManager presence (if the user set up background auto-connect) so the OS keeps
        // waking us when the iPhone is in range — survives reboots / process death.
        AncsCompanion.observePresence(context)
        deviceRepo.setStatus(AncsStatus.ADVERTISING)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(attachRunnable)
        runCatching { advertiser?.stopAdvertisingSet(advertisingSetCallback) }
        advertisingSet = null
        if (receiverRegistered) runCatching { context.unregisterReceiver(bondReceiver) }.also {
            receiverRegistered = false
        }
        if (adapterReceiverRegistered) runCatching { context.unregisterReceiver(adapterReceiver) }.also {
            adapterReceiverRegistered = false
        }
        client?.disconnectAndClose(); client = null
        // Actively drop the iPhone's link to our GATT server too (not just release our handle, which leaves the
        // ACL up and the iPhone showing "Connected"/Sharing), then close the server.
        runCatching { connectedDevice?.let { gattServer?.cancelConnection(it) } }
        connectedDevice = null
        runCatching { gattServer?.close() }; gattServer = null
        deviceRepo.clearDeviceName()
        deviceRepo.setStatus(AncsStatus.OFF)
    }

    // ---- Advertising + GATT server (peripheral role) ----

    private fun startAdvertising(adv: BluetoothLeAdvertiser) {
        // The legacy startAdvertising() path silently drops service-solicitation UUIDs on many OEM stacks
        // (confirmed here: nRF Connect saw no solicitation). startAdvertisingSet() is the modern path and
        // reports a distinct status if the controller rejects the data. Keep setLegacyMode(true): iOS only
        // scans LEGACY advertisements for the Settings → Bluetooth accessory / ANCS flow.
        val params = AdvertisingSetParameters.Builder()
            .setLegacyMode(true)
            .setConnectable(true)
            .setScannable(true)
            .setInterval(AdvertisingSetParameters.INTERVAL_MEDIUM)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MEDIUM)
            .build()
        // A 128-bit solicitation UUID nearly fills the 31-byte legacy advert, so the name goes in the scan response.
        val data = AdvertiseData.Builder()
            .addServiceSolicitationUuid(ParcelUuid(Ancs.SERVICE_UUID))
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()
        runCatching {
            adv.startAdvertisingSet(
                params,
                data,
                scanResponse,
                null,
                null,
                advertisingSetCallback
            )
        }
            .onFailure {
                Log.w(TAG, "startAdvertisingSet threw", it); deviceRepo.setStatus(
                AncsStatus.ERROR
            )
            }
    }

    private val advertisingSetCallback = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
            if (status == AdvertisingSetCallback.ADVERTISE_SUCCESS) {
                advertisingSet = set
                Log.i(TAG, "ANCS advertising set started (txPower=$txPower)")
            } else {
                Log.w(TAG, "ANCS advertising set start failed: status=$status")
                deviceRepo.setStatus(AncsStatus.ERROR)
            }
        }

        // Decisive diagnostic: if the controller rejected the advertising data (the solicitation UUID), status
        // here is non-zero. SUCCESS but still nothing on air (per nRF Connect) ⇒ a deeper stack bug on this device.
        override fun onAdvertisingDataSet(set: AdvertisingSet?, status: Int) {
            Log.i(TAG, "ANCS advertising data set: status=$status (0=success)")
        }

        override fun onScanResponseDataSet(set: AdvertisingSet?, status: Int) {
            Log.i(TAG, "ANCS scan response set: status=$status (0=success)")
        }

        override fun onAdvertisingSetStopped(set: AdvertisingSet?) {
            advertisingSet = null
        }
    }

    private fun openGattServer() {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        // An empty GATT server: it exists only to accept the iPhone's connection. ANCS data flows the other
        // way — over our GATT *client* to the iPhone's server — once we're connected (independent GATT roles).
        gattServer = mgr.openGattServer(context, gattServerCallback)
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            Log.i(
                TAG,
                "GATT server conn: ${device.address} status=$status newState=$newState bond=${device.bondState}"
            )
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> onCentralConnected(device)
                BluetoothProfile.STATE_DISCONNECTED ->
                    if (device.address == connectedDevice?.address) onCentralDisconnected()
            }
        }
    }

    private fun onCentralConnected(device: BluetoothDevice) {
        if (client != null) return // already bridging one iPhone
        // Only bridge a BONDED central. Our advert is connectable, so any nearby BLE central — the user's other
        // phones, earbuds, watches — connects to our GATT server too. ANCS only ever lives behind an encrypted/
        // bonded link, so an unbonded central is never the paired iPhone. Bridging one anyway grabbed this single
        // client slot, spammed it with createBond() pairing requests, and locked out the real (bonded) iPhone —
        // which then shows "Connected" on iOS but never reaches SHARING until the user toggles iPhone Bluetooth.
        // Pairing a NEW iPhone is the CDM "Set up" flow (AncsCompanion.bondDevice) or iOS Settings; both bond at
        // the OS level, after which the iPhone reconnects bonded and lands here.
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            Log.i(
                TAG,
                "ignoring unbonded central ${device.address} (bond=${device.bondState}) — not the paired iPhone"
            )
            return
        }
        handler.removeCallbacks(attachRunnable) // we're handling a connection; cancel any pending re-attach
        Log.i(
            TAG,
            "iPhone connected as central: ${device.address} — opening ANCS GATT client back to it"
        )
        if (device.address != lastDevice?.address) deviceRepo.clearDeviceName() // a different iPhone: don't inherit the old name
        connectedDevice = device
        lastDevice = device
        deviceRepo.setDeviceName(runCatching { device.name }.getOrNull())
        val c = AncsGattClient(
            context = context,
            device = device,
            onStatus = { status ->
                deviceRepo.setStatus(status)
                if (status == AncsStatus.SHARING) {
                    reconnectAttempts = 0
                    handler.removeCallbacks(attachRunnable)
                    refreshDeviceName(device)
                }
            },
            onSourceEvent = ::onSourceEvent,
            onBondNeeded = { ensureBond(device) },
            onClientGone = ::onClientGone,
        )
        client = c
        c.connect()
    }

    /**
     * The GATT client to the iPhone terminally disconnected or failed to connect. Drop it, then schedule a
     * re-attach: if the iPhone is still connected at the stack level (e.g. a transient client failure right
     * after a process restart, where no fresh GATT-server connect event will arrive), we re-attach to it
     * with backoff instead of getting stuck waiting. Also handles stacks that surface the iPhone under
     * rotating random addresses. No-op if the iPhone has genuinely disconnected (a reconnect will retrigger us).
     */
    private fun onClientGone() {
        connectedDevice?.let {
            lastDevice = it
        } // remember the iPhone so we re-attach to IT, not another bonded device
        client = null
        connectedDevice = null
        scheduleAttach()
    }

    /** Re-attach to the iPhone we were last talking to, but only if it's still connected at the stack level. */
    private fun tryAttachConnected() {
        if (client != null || !running) return
        val want = lastDevice?.address ?: return
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return
        val connected = (
                runCatching { mgr.getConnectedDevices(BluetoothProfile.GATT_SERVER) }.getOrDefault(
                    emptyList()
                ) +
                        runCatching { mgr.getConnectedDevices(BluetoothProfile.GATT) }.getOrDefault(
                            emptyList()
                        )
                ).distinctBy { it.address }
        val target = connected.firstOrNull { it.address == want }
            ?: return // gone → a fresh connect event will retrigger us
        Log.i(
            TAG,
            "re-attaching to still-connected iPhone ${target.address} (attempt $reconnectAttempts)"
        )
        onCentralConnected(target)
    }

    /** Schedule [tryAttachConnected] with capped exponential backoff (reset on a successful SHARING). */
    private fun scheduleAttach() {
        if (!running) return
        handler.removeCallbacks(attachRunnable)
        val delay = minOf(2_000L * (reconnectAttempts + 1), 20_000L)
        reconnectAttempts++
        handler.postDelayed(attachRunnable, delay)
    }

    private fun onCentralDisconnected() {
        Log.i(TAG, "central disconnected — back to advertising")
        client?.close(); client = null
        connectedDevice = null
        uidToKey.clear() // UIDs are session-scoped; drop the removal map on disconnect
        selfClearedUids.clear() // ditto — pending self-clear confirmations don't carry across sessions
        deviceRepo.clearDeviceName() // no device connected — don't leave a stale name in the FGS / tab / group labels
        deviceRepo.setStatus(AncsStatus.ADVERTISING)
    }

    private fun refreshDeviceName(device: BluetoothDevice) {
        deviceRepo.setDeviceName(runCatching { device.name }.getOrNull())
    }

    // ---- Bonding ----

    private fun ensureBond(device: BluetoothDevice) {
        Log.i(TAG, "ensureBond: bondState=${device.bondState}")
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            val started = runCatching { device.createBond() }.getOrDefault(false)
            Log.i(TAG, "createBond() -> $started")
        } else {
            client?.onBonded()
        }
    }

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
            val device =
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            Log.i(TAG, "bond state -> $state for ${device?.address}")
            if (state == BluetoothDevice.BOND_BONDED) client?.onBonded()
        }
    }

    private fun registerBondReceiver() {
        if (receiverRegistered) return
        // ACTION_BOND_STATE_CHANGED is a protected system broadcast; NOT_EXPORTED still receives it.
        ContextCompat.registerReceiver(
            context, bondReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
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

                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> onBluetoothOff()
            }
        }
    }

    private fun registerAdapterReceiver() {
        if (adapterReceiverRegistered) return
        // ACTION_STATE_CHANGED is a protected system broadcast; NOT_EXPORTED still receives it.
        ContextCompat.registerReceiver(
            context, adapterReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        adapterReceiverRegistered = true
    }

    /** BT was turned off: the advertiser/GATT/client are now dead — drop them and reset [running] so STATE_ON
     *  re-runs [start] cleanly. Keep both receivers registered; leave the FGS up (the tab's switch owns teardown). */
    private fun onBluetoothOff() {
        if (!running && advertisingSet == null && client == null) return // already torn down
        Log.i(TAG, "Bluetooth off — tearing down BLE; will re-advertise when it returns")
        running = false
        handler.removeCallbacks(attachRunnable)
        runCatching { advertiser?.stopAdvertisingSet(advertisingSetCallback) }
        advertisingSet = null
        client?.close(); client = null
        connectedDevice = null
        runCatching { gattServer?.close() }; gattServer = null
        deviceRepo.clearDeviceName() // link torn down — drop the stale name
        deviceRepo.setStatus(AncsStatus.ERROR)
    }

    // ---- Notification handling ----

    private fun onSourceEvent(packet: Ancs.SourcePacket) {
        val c = client ?: return
        scope.launch {
            if (packet.isRemoved) {
                onRemoved(packet); return@launch
            }
            // The Notification Source packet has no app id — we must fetch attributes first, then filter.
            val attrs = c.fetchNotificationAttributes(packet.notificationUid) ?: return@launch
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
                attrs.date
            )
            val androidPkg = iconResolver.androidPackageForIos(bundleId, displayName)
            // A connect-time backlog (PreExisting) shouldn't blast every mesh device with old alerts: show it
            // locally but quietly, and don't mirror it. Fresh notifications flow to both sinks normally.
            val toMesh = !packet.isPreExisting && meshMirrorEnabled()
            var notif = AncsNotificationMapper.map(
                clientId,
                record,
                iphoneId(),
                deviceRepo.deviceName.value,
                androidPkg,
                System.currentTimeMillis(),
            )
            // Only ship the app icon as an asset when mirroring — a local render resolves the icon directly.
            if (toMesh) notif = attachAppIcon(notif, androidPkg)
            uidToKey[packet.notificationUid] = notif.sourceKey
            val contentKey = contentKeyOf(record)
            keyToContent[notif.sourceKey] = contentKey
            // Dismissed earlier while the iPhone was unreachable? This replay (fresh UID this session) is our
            // chance to clear it on the source instead of re-showing a notification the user already swiped.
            if (pendingClear.remove(contentKey)) {
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
        val key = uidToKey.remove(packet.notificationUid) ?: return
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
        val uid = parts[3].toIntOrNull() ?: return
        // Live this session (same UID still mapped to this exact key) on our connected iPhone → clear now.
        if (client != null && parts[1] == iphoneId() && uidToKey[uid] == sourceKey) {
            selfClearedUids.add(uid) // our own clear → its EVENT_REMOVED must not re-broadcast to the mesh
            scope.launch { runCatching { client?.performAction(uid, Ancs.ACTION_NEGATIVE) } }
            return
        }
        // Otherwise defer: clear it when it next replays. Keyed by content (not the rotating UID) so it matches
        // across the reconnect. Unknown only if this process never captured it — then it's a best-effort no-op.
        keyToContent[sourceKey]?.let { pendingClear.add(it) }
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

    private fun hasPermissions(): Boolean =
        granted(Manifest.permission.BLUETOOTH_CONNECT) && granted(Manifest.permission.BLUETOOTH_ADVERTISE)

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "AncsBleManager"
        const val MIME_WEBP = "image/webp"
    }
}

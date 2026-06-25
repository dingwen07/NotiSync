package net.extrawdw.apps.notisync.ancs

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream

/**
 * GATT **client** to one iPhone's ANCS server. Opens the link, requests a large MTU, discovers ANCS, enables
 * Data Source + Notification Source notifications (driving bonding when the link isn't yet encrypted),
 * forwards Notification Source events, and answers attribute / app-name queries over Control Point —
 * reassembling fragmented Data Source responses.
 *
 * GATT operations are serialized by the Android stack; correspondingly we keep at most one Control Point
 * request in flight ([cpMutex]) and one CCCD-enable in flight (chained through [onDescriptorWrite]).
 *
 * Bluetooth permissions are guaranteed by the caller ([AncsBleManager] checks before constructing this).
 */
@SuppressLint("MissingPermission")
class AncsGattClient(
    private val context: Context,
    private val device: BluetoothDevice,
    private val onStatus: (AncsStatus) -> Unit,
    private val onSourceEvent: (Ancs.SourcePacket) -> Unit,
    private val onBondNeeded: () -> Unit,
    /** Invoked once this client terminally disconnects or fails to connect, so the manager can drop it and
     *  let the next inbound connection (possibly under a rotated random address) spawn a fresh client. */
    private val onClientGone: () -> Unit = {},
) {
    private var gatt: BluetoothGatt? = null
    private var controlPoint: BluetoothGattCharacteristic? = null
    private var dataSource: BluetoothGattCharacteristic? = null
    private var notificationSource: BluetoothGattCharacteristic? = null

    private val cpMutex = Mutex()
    @Volatile private var pending: PendingRequest? = null

    private val handler = Handler(Looper.getMainLooper())
    // CCCD-enable retry state: the characteristic whose subscribe is in flight, and how many times we've
    // retried it while bonded (the link's encryption settling after cross-transport key derivation).
    private var inFlightChar: BluetoothGattCharacteristic? = null
    private var cccdAttempts = 0
    private var discoveryAttempts = 0

    @Volatile private var reachedSharing = false
    @Volatile private var torndown = false
    // Recovery watchdog: if a session doesn't reach SHARING within the current phase's budget, force the link
    // down so the iPhone reconnects on a FRESH one — automating the "toggle iPhone Bluetooth" manual recovery.
    // The budget is phase-aware (see [armWatchdog]): the machine-driven phases (connect → discovery → CCCD) get
    // a tight deadline, while NEEDS_PAIRING gets a longer one because it waits on the user tapping "Pair".
    //
    // It must DISCONNECT, not just close(): close() releases our handle locally but the iPhone keeps the ACL
    // alive, so re-attaching reuses the same wedged link and requestMtu/discovery hang again (confirmed on the
    // S25 — gattServer.cancelConnection() is ignored too). We opened this GATT client ourselves, so disconnect()
    // genuinely terminates the link; the iPhone then sees the drop and reconnects clean. STATE_DISCONNECTED runs
    // the teardown; [disconnectFallback] covers a stack that swallows the disconnect callback.
    private val watchdog = Runnable {
        if (reachedSharing || torndown) return@Runnable
        val g = gatt
        if (g == null) { teardown(); return@Runnable }
        Log.w(TAG, "no SHARING within the phase deadline — disconnecting link to force a fresh reconnect")
        runCatching { g.disconnect() }
        handler.postDelayed(disconnectFallback, DISCONNECT_FALLBACK_MS)
    }

    private val disconnectFallback = Runnable {
        if (!reachedSharing) { Log.w(TAG, "watchdog disconnect didn't land — forcing teardown"); teardown() }
    }

    /** Close this client and notify the manager, at most once per session (the watchdog disconnect and the
     *  STATE_DISCONNECTED callback can both land). */
    private fun teardown() {
        if (torndown) return
        torndown = true
        close()
        onClientGone()
    }

    /** (Re)arm the recovery watchdog with [deadlineMs], replacing any pending fire. No-op once SHARING. */
    private fun armWatchdog(deadlineMs: Long) {
        if (reachedSharing) return
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, deadlineMs)
    }

    private class PendingRequest(val command: Int, val attrCount: Int, val uid: Int = -1) {
        val buffer = ByteArrayOutputStream()
        val deferred = CompletableDeferred<ByteArray>()
    }

    @Suppress("DEPRECATION") // the transport overload is the one we want (force BLE); the 3-arg picks TRANSPORT_AUTO
    fun connect() {
        onStatus(AncsStatus.CONNECTING)
        Log.i(TAG, "connecting GATT client to ${device.address} (bond=${device.bondState})")
        armWatchdog(CONNECT_DEADLINE_MS)
        // autoConnect=FALSE: a direct connection. The iPhone has just connected to our peripheral, so the link
        // already exists and a direct connect attaches immediately. autoConnect=true is the slow background-
        // reconnect path and fails with status 135 on some stacks (e.g. Samsung). Reconnection is iPhone-driven
        // (it re-connects to our advertisement), so we don't need autoConnect here. Matches working ANCS clients.
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun close() {
        handler.removeCallbacksAndMessages(null)
        runCatching { gatt?.close() }
        gatt = null
        pending = null
        inFlightChar = null
    }

    /** Actively terminate the link, then release — for an intentional shutdown (the user disabled the bridge),
     *  so the iPhone sees the drop and stops sharing ANCS. Plain [close] only releases our handle and lets the
     *  iPhone keep the ACL link alive (see the watchdog note above). */
    fun disconnectAndClose() {
        runCatching { gatt?.disconnect() }
        close()
    }

    /** After bonding, ANCS (hidden until the link is encrypted) should now be discoverable — re-run discovery
     *  rather than just re-enabling, so a service that wasn't visible pre-bond shows up. */
    fun onBonded() {
        if (gatt == null) return
        Log.i(TAG, "bonded — re-discovering services")
        armWatchdog(CONNECT_DEADLINE_MS) // pairing done; back to the tight machine-driven budget
        refreshThenDiscover()
    }

    /**
     * Clear Android's cached GATT service database for this device (hidden `BluetoothGatt.refresh()` via
     * reflection) so the next discovery reads fresh from the iPhone. Without this, a reconnect can reuse a
     * stale cache that's missing ANCS — surfacing as "Connected, not shared" until the user toggles iPhone
     * Bluetooth. Best-effort: if the hidden API is unavailable it logs false and we fall back to retrying.
     */
    private fun refreshGattCache(g: BluetoothGatt) {
        val ok = runCatching { g.javaClass.getMethod("refresh").invoke(g) as? Boolean }.getOrNull()
        Log.i(TAG, "gatt cache refresh -> $ok")
    }

    /**
     * Refresh Android's GATT cache, then re-discover AFTER a settle. The settle is load-bearing on this Samsung:
     * [BluetoothGatt.refresh] immediately followed by [BluetoothGatt.discoverServices] races — discovery comes
     * back with an EMPTY service list (so the iPhone's ANCS never shows up), and on a re-attached link it can
     * hang outright until the recovery watchdog fires. Letting the refresh land first makes the next discovery
     * read the real database — automating what manually toggling iPhone Bluetooth was doing.
     */
    private fun refreshThenDiscover() {
        val g = gatt ?: return
        refreshGattCache(g)
        handler.postDelayed({ gatt?.let { if (!it.discoverServices()) onStatus(AncsStatus.ERROR) } }, REFRESH_SETTLE_MS)
    }

    suspend fun fetchNotificationAttributes(uid: Int): Ancs.NotificationAttributes? =
        request(Ancs.CMD_GET_NOTIFICATION_ATTRIBUTES, Ancs.NOTIFICATION_ATTRS.size, Ancs.buildGetNotificationAttributes(uid), correlationId = uid)
            ?.let { Ancs.parseNotificationAttributes(it, Ancs.NOTIFICATION_ATTRS.size) }

    suspend fun fetchAppDisplayName(appId: String): String? =
        request(Ancs.CMD_GET_APP_ATTRIBUTES, 1, Ancs.buildGetAppAttributes(appId))
            ?.let { Ancs.parseAppAttributes(it)?.displayName }

    /**
     * Best-effort PerformNotificationAction — e.g. a negative ("Clear") action that dismisses the notification
     * on the iPhone. Unlike the attribute fetches there's no Data Source reply to await, so this is a
     * fire-and-forget Control Point write, still serialized through [cpMutex] so it can't interleave with an
     * in-flight request's write. Returns whether the local stack accepted the write (not whether iOS, which
     * silently ignores an action a notification doesn't expose, actually honored it).
     */
    suspend fun performAction(uid: Int, actionId: Int): Boolean = cpMutex.withLock {
        val cp = controlPoint ?: return false
        val g = gatt ?: return false
        g.writeCharacteristic(cp, Ancs.buildPerformAction(uid, actionId), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
            BluetoothStatusCodes.SUCCESS
    }

    private suspend fun request(command: Int, attrCount: Int, payload: ByteArray, correlationId: Int = -1): ByteArray? = cpMutex.withLock {
        val cp = controlPoint ?: return null
        val g = gatt ?: return null
        val req = PendingRequest(command, attrCount, correlationId)
        pending = req
        val status = g.writeCharacteristic(cp, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        if (status != BluetoothStatusCodes.SUCCESS) { pending = null; return null }
        val result = withTimeoutOrNull(REQUEST_TIMEOUT_MS) { req.deferred.await() }
        pending = null
        result
    }

    // CCCDs still to enable for the current attempt (Data Source first, so the backlog can be answered).
    private val toEnable = ArrayDeque<BluetoothGattCharacteristic>()

    private fun beginEnable(g: BluetoothGatt) {
        cccdAttempts = 0
        toEnable.clear()
        dataSource?.let { toEnable.add(it) }
        notificationSource?.let { toEnable.add(it) }
        enableNext(g)
    }

    private fun enableNext(g: BluetoothGatt) {
        val ch = toEnable.removeFirstOrNull() ?: run {
            inFlightChar = null
            reachedSharing = true
            handler.removeCallbacks(watchdog)
            Log.i(TAG, "ANCS subscribed — SHARING")
            onStatus(AncsStatus.SHARING)
            return
        }
        inFlightChar = ch
        g.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(Ancs.CCCD) ?: return enableNext(g)
        g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
    }

    /** Re-queue the in-flight characteristic and retry its CCCD write after [delayMs], without re-discovering. */
    private fun retryEnableAfter(delayMs: Long) {
        inFlightChar?.let { toEnable.addFirst(it) }
        handler.postDelayed({ gatt?.let { enableNext(it) } }, delayMs)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG, "client conn: status=$status newState=$newState bond=${device.bondState}")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> { onStatus(AncsStatus.CONNECTING); g.requestMtu(MTU) }
                // Covers both a clean disconnect and a failed connect (newState=DISCONNECTED with an error
                // status like 133/135). Tear down and tell the manager so it can retry the next connection.
                BluetoothProfile.STATE_DISCONNECTED -> { onStatus(AncsStatus.ADVERTISING); teardown() }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "mtu=$mtu status=$status — discovering services")
            // Plain discover — do NOT refresh() here. On this Samsung, refresh() right before discovery makes the
            // first discovery come back EMPTY, so we'd loop several times before ANCS shows up. A fresh link's OTA
            // discovery returns the real DB (ANCS included) directly; we only refresh if a populated DB turns out
            // to be missing ANCS (a stale cache — handled in onServicesDiscovered).
            if (!g.discoverServices()) onStatus(AncsStatus.ERROR)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            Log.i(TAG, "services discovered status=$status: [${g.services.joinToString { it.uuid.toString() }}]")
            val svc = g.getService(Ancs.SERVICE_UUID)
            if (svc == null) {
                // ANCS is hidden until the link is encrypted. If we're not bonded, bond and re-discover; only
                // give up (NO_ANCS) once it's still absent after bonding (the peer isn't sharing notifications).
                if (device.bondState != BluetoothDevice.BOND_BONDED) {
                    Log.i(TAG, "ANCS not visible yet & not bonded — requesting bond")
                    onStatus(AncsStatus.NEEDS_PAIRING)
                    armWatchdog(PAIRING_DEADLINE_MS) // user-paced: give them time to accept pairing
                    onBondNeeded()
                } else if (++discoveryAttempts <= MAX_DISCOVERY_ATTEMPTS) {
                    if (g.services.isEmpty()) {
                        // Empty result — the OTA discovery didn't land yet (common on this Samsung). Re-discover
                        // WITHOUT refreshing; another refresh just re-clears the cache and we loop on empties.
                        Log.i(TAG, "discovery came back empty — re-discovering #$discoveryAttempts (no refresh)")
                        handler.postDelayed({ gatt?.let { if (!it.discoverServices()) onStatus(AncsStatus.ERROR) } }, DISCOVERY_RETRY_MS)
                    } else {
                        // A populated DB that's genuinely missing ANCS — a stale cache. Clear it once, re-discover.
                        Log.i(TAG, "ANCS absent from a populated DB — refresh + re-discover #$discoveryAttempts")
                        refreshThenDiscover()
                    }
                } else {
                    Log.w(TAG, "ANCS still absent after $MAX_DISCOVERY_ATTEMPTS refreshes — peer not sharing")
                    onStatus(AncsStatus.NO_ANCS)
                }
                return
            }
            Log.i(TAG, "ANCS found — letting the link settle, then subscribing")
            discoveryAttempts = 0
            controlPoint = svc.getCharacteristic(Ancs.CONTROL_POINT)
            dataSource = svc.getCharacteristic(Ancs.DATA_SOURCE)
            notificationSource = svc.getCharacteristic(Ancs.NOTIFICATION_SOURCE)
            // Let the LE link's encryption establish (especially after cross-transport key derivation from a
            // classic bond) before the first CCCD write, so the iPhone doesn't reject the subscribe.
            handler.postDelayed({ gatt?.let { beginEnable(it) } }, ENABLE_SETTLE_MS)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.i(TAG, "CCCD write status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                cccdAttempts = 0
                enableNext(g)
                return
            }
            if (device.bondState != BluetoothDevice.BOND_BONDED) {
                // Not bonded yet — pair, then onBonded() re-discovers + re-enables once it completes.
                Log.i(TAG, "CCCD rejected & not bonded — requesting bond")
                onStatus(AncsStatus.NEEDS_PAIRING)
                armWatchdog(PAIRING_DEADLINE_MS) // user-paced: give them time to accept pairing
                onBondNeeded()
                return
            }
            // Bonded but rejected (status 14 = encryption not yet settled on this LE link — common right after
            // cross-transport key derivation from a classic bond). Retry the SAME write after a growing delay;
            // do NOT re-discover (that thrashes the link and the iPhone terminates it). Bounded.
            if (++cccdAttempts <= MAX_CCCD_ATTEMPTS) {
                val delay = CCCD_RETRY_BASE_MS * cccdAttempts
                Log.i(TAG, "CCCD rejected while bonded — retry #$cccdAttempts in ${delay}ms (waiting for encryption)")
                retryEnableAfter(delay)
            } else {
                Log.w(TAG, "CCCD still failing after $MAX_CCCD_ATTEMPTS attempts — link won't encrypt; giving up")
                onStatus(AncsStatus.NO_ANCS)
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            when (ch.uuid) {
                Ancs.NOTIFICATION_SOURCE -> Ancs.parseSource(value)?.let(onSourceEvent)
                Ancs.DATA_SOURCE -> appendDataSource(value)
            }
        }
    }

    /** Accumulate a Data Source fragment; complete the pending request once the full response has arrived. */
    private fun appendDataSource(value: ByteArray) {
        val req = pending ?: return
        // Correlate the opening fragment with the awaited request so a late fragment from a previously
        // timed-out request can't be glued onto this one's buffer (which would corrupt the parse — e.g. show
        // the wrong notification's title). Only the first fragment carries the header we can match;
        // continuations (buffer already non-empty) are appended as-is.
        if (req.buffer.size() == 0 && !firstFragmentMatches(req, value)) return
        req.buffer.write(value)
        val buf = req.buffer.toByteArray()
        val complete = when (req.command) {
            Ancs.CMD_GET_NOTIFICATION_ATTRIBUTES -> Ancs.parseNotificationAttributes(buf, req.attrCount) != null
            Ancs.CMD_GET_APP_ATTRIBUTES -> Ancs.parseAppAttributes(buf, req.attrCount) != null
            else -> true
        }
        if (complete) req.deferred.complete(buf)
    }

    /** Does this opening fragment belong to [req]? Matches the response command and, for
     *  GetNotificationAttributes, the echoed UID — so a stray fragment from a timed-out earlier request is
     *  dropped rather than mis-parsed into this response. */
    private fun firstFragmentMatches(req: PendingRequest, frag: ByteArray): Boolean {
        if (Ancs.dataSourceCommand(frag) != req.command) return false // also rejects an empty fragment (-1)
        if (req.command == Ancs.CMD_GET_NOTIFICATION_ATTRIBUTES && req.uid >= 0) {
            // If the fragment is too short to carry the UID yet, accept it; the parse still validates the rest.
            Ancs.notificationResponseUid(frag)?.let { return it == req.uid }
        }
        return true
    }

    private companion object {
        const val TAG = "AncsGattClient"
        const val MTU = 517
        const val REQUEST_TIMEOUT_MS = 5_000L
        // Small settle so discovery fully finishes; the first CCCD write then prompts the stack to request
        // encryption from the iPhone, and the retry lands once that encryption has completed.
        const val ENABLE_SETTLE_MS = 250L
        const val CCCD_RETRY_BASE_MS = 400L
        const val MAX_CCCD_ATTEMPTS = 5
        // Watchdog budgets (phase-aware). Machine-driven phases are quick (discovery alone can take ~8s), so a
        // tight deadline recovers a genuine wedge fast; pairing waits on the user, so it gets a longer leash.
        const val CONNECT_DEADLINE_MS = 30_000L
        const val PAIRING_DEADLINE_MS = 60_000L
        const val MAX_DISCOVERY_ATTEMPTS = 4
        const val DISCOVERY_RETRY_MS = 700L
        // Let BluetoothGatt.refresh() land before the next discoverServices() — see [refreshThenDiscover].
        const val REFRESH_SETTLE_MS = 600L
        // After a watchdog disconnect(), force teardown if STATE_DISCONNECTED never arrives (wedged stack).
        const val DISCONNECT_FALLBACK_MS = 2_500L
    }
}

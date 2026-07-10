package net.extrawdw.apps.notisync.ios

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * GATT **client** to one iPhone's ANCS server. Opens the link, requests a large MTU, discovers ANCS, enables
 * Data Source + Notification Source notifications (driving bonding when the link isn't yet encrypted),
 * forwards Notification Source events, and answers attribute / app-name queries over Control Point —
 * reassembling fragmented Data Source responses.
 *
 * The same link also consumes the iPhone's **AMS** (Apple Media Service, [Ams]) when present: once ANCS
 * reaches SHARING the client subscribes Entity Update + Remote Command, registers the Player/Track
 * attributes, and forwards media updates / supported-command changes — best-effort and non-fatal (an
 * iPhone not exposing AMS still bridges notifications normally).
 *
 * GATT operations are serialized by the Android stack; correspondingly we keep at most one Control Point
 * request in flight ([cpMutex]) and one CCCD-enable in flight (chained through [onDescriptorWrite]). AMS
 * operations share [cpMutex], each awaiting its own completion callback before releasing the lock, so they
 * can never interleave with an ANCS request's write or each other.
 *
 * Bluetooth permissions are guaranteed by the caller ([IosBridgeManager] checks before constructing this).
 */
@SuppressLint("MissingPermission")
class IosGattClient(
    private val context: Context,
    private val device: BluetoothDevice,
    private val onStatus: (IosBridgeStatus) -> Unit,
    private val onSourceEvent: (Ancs.SourcePacket) -> Unit,
    private val onBondNeeded: () -> Unit,
    /** Invoked once this client terminally disconnects or fails to connect, so the manager can drop it and
     *  let the next inbound connection (possibly under a rotated random address) spawn a fresh client. */
    private val onClientGone: () -> Unit = {},
    /** Host scope for the AMS setup coroutine; AMS work is cancelled with this client (see [close]). */
    private val scope: CoroutineScope? = null,
    /** An AMS Entity Update landed (a Player/Track attribute changed). */
    private val onAmsEntityUpdate: (Ams.EntityUpdate) -> Unit = {},
    /** iOS notified the currently-supported AMS remote commands (changes with the active player). */
    private val onAmsSupportedCommands: (List<Int>) -> Unit = {},
) {
    private var gatt: BluetoothGatt? = null
    private var controlPoint: BluetoothGattCharacteristic? = null
    private var dataSource: BluetoothGattCharacteristic? = null
    private var notificationSource: BluetoothGattCharacteristic? = null

    private val cpMutex = Mutex()

    // ---- AMS state (all best-effort; null / false when the iPhone doesn't expose AMS) ----
    private var amsRemoteCommand: BluetoothGattCharacteristic? = null
    private var amsEntityUpdate: BluetoothGattCharacteristic? = null
    private var amsEntityAttribute: BluetoothGattCharacteristic? = null

    @Volatile
    private var amsSetupStarted = false

    /** Child job so [close] cancels any in-flight AMS setup/fetch without touching the host scope. */
    private val amsJob = SupervisorJob()

    /** The one in-flight AMS descriptor write (mutex-guarded; completed by [onDescriptorWrite]). */
    @Volatile
    private var amsDescriptorWrite: CompletableDeferred<Boolean>? = null

    /** The one in-flight AMS characteristic write, keyed by uuid so an unrelated (ANCS) write completion
     *  can't complete it (mutex-guarded; completed by `onCharacteristicWrite`). */
    @Volatile
    private var amsPendingWrite: AmsPendingWrite? = null

    /** The one in-flight Entity Attribute read (mutex-guarded; completed by `onCharacteristicRead`). */
    @Volatile
    private var amsCharacteristicRead: CompletableDeferred<ByteArray?>? = null

    private class AmsPendingWrite(val uuid: UUID) {
        val deferred = CompletableDeferred<Boolean>()
    }

    @Volatile
    private var pending: PendingRequest? = null

    private val handler = Handler(Looper.getMainLooper())

    // CCCD-enable retry state: the characteristic whose subscribe is in flight, and how many times we've
    // retried it while bonded (the link's encryption settling after cross-transport key derivation).
    private var inFlightChar: BluetoothGattCharacteristic? = null
    private var cccdAttempts = 0
    private var discoveryAttempts = 0

    @Volatile
    private var reachedSharing = false

    @Volatile
    private var torndown = false

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
        if (g == null) {
            teardown(); return@Runnable
        }
        Log.w(
            TAG,
            "no SHARING within the phase deadline — disconnecting link to force a fresh reconnect"
        )
        runCatching { g.disconnect() }
        handler.postDelayed(disconnectFallback, DISCONNECT_FALLBACK_MS)
    }

    private val disconnectFallback = Runnable {
        if (!reachedSharing) {
            Log.w(TAG, "watchdog disconnect didn't land — forcing teardown"); teardown()
        }
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
        onStatus(IosBridgeStatus.CONNECTING)
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
        amsJob.cancel()
        amsReset()
        runCatching { gatt?.close() }
        gatt = null
        pending = null
        inFlightChar = null
    }

    /** Drop AMS characteristic refs + fail any in-flight AMS op (fresh discovery / teardown). */
    private fun amsReset() {
        amsRemoteCommand = null
        amsEntityUpdate = null
        amsEntityAttribute = null
        amsSetupStarted = false
        amsDescriptorWrite?.complete(false)
        amsPendingWrite?.deferred?.complete(false)
        amsCharacteristicRead?.complete(null)
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
        handler.postDelayed(
            { gatt?.let { if (!it.discoverServices()) onStatus(IosBridgeStatus.ERROR) } },
            REFRESH_SETTLE_MS
        )
    }

    suspend fun fetchNotificationAttributes(
        uid: Int,
        includeActionLabels: Boolean = false,
    ): Ancs.NotificationAttributes? {
        val attrCount = Ancs.notificationAttrCount(includeActionLabels)
        return request(
            Ancs.CMD_GET_NOTIFICATION_ATTRIBUTES,
            attrCount,
            Ancs.buildGetNotificationAttributes(uid, includeActionLabels = includeActionLabels),
            correlationId = uid
        )
            ?.let { Ancs.parseNotificationAttributes(it, attrCount) }
    }

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
        g.writeCharacteristic(
            cp,
            Ancs.buildPerformAction(uid, actionId),
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        ) ==
                BluetoothStatusCodes.SUCCESS
    }

    // ---- AMS (Apple Media Service) ----

    /**
     * Write one AMS RemoteCommandID to the Remote Command characteristic — a transport press (play,
     * pause, next…) replayed on whatever the iPhone's active media app is. Returns whether iOS accepted
     * the write; an unsupported command is rejected at ATT level (spec error 0xA1) and comes back false.
     */
    suspend fun sendMediaCommand(commandId: Int): Boolean {
        val rc = amsRemoteCommand ?: return false
        return amsWrite(rc, Ams.buildRemoteCommand(commandId))
    }

    /**
     * Fetch the full value of a truncated Entity Update attribute: write the (entity, attribute) pair to
     * Entity Attribute, then read the characteristic back — two ATT operations under one [cpMutex] hold so
     * nothing interleaves between the naming write and its read. Null on any failure (absent AMS, timeout,
     * or spec error 0xA2 for an attribute that emptied since).
     */
    suspend fun readEntityAttribute(entityId: Int, attributeId: Int): String? {
        val ea = amsEntityAttribute ?: return null
        cpMutex.withLock {
            val g = gatt ?: return null
            val pendingWrite = AmsPendingWrite(ea.uuid)
            amsPendingWrite = pendingWrite
            val issued = g.writeCharacteristic(
                ea,
                Ams.buildEntityAttributeRequest(entityId, attributeId),
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            ) == BluetoothStatusCodes.SUCCESS
            val named = issued &&
                (withTimeoutOrNull(AMS_OP_TIMEOUT_MS) { pendingWrite.deferred.await() } ?: false)
            amsPendingWrite = null
            if (!named) return null
            val pendingRead = CompletableDeferred<ByteArray?>()
            amsCharacteristicRead = pendingRead
            if (!g.readCharacteristic(ea)) {
                amsCharacteristicRead = null; return null
            }
            val bytes = withTimeoutOrNull(AMS_OP_TIMEOUT_MS) { pendingRead.await() }
            amsCharacteristicRead = null
            return bytes?.let { String(it, Charsets.UTF_8) }
        }
    }

    /**
     * Kick the AMS setup once ANCS reaches SHARING (so its CCCD writes never interleave with the ANCS
     * enable chain, and the link is already encrypted — AMS, like ANCS, only lives behind encryption).
     * Ordering per the spec: subscribe Entity Update FIRST (registration writes are rejected with 0xA0
     * before it), then Remote Command (whose subscribe makes iOS notify the supported-command list),
     * then register the Player + Track attribute sets. Best-effort: an iPhone without AMS (or a failed
     * step) logs and leaves the notification bridge untouched.
     */
    private fun startAmsSetup() {
        val eu = amsEntityUpdate
        if (eu == null) {
            Log.i(TAG, "AMS not exposed by this iPhone — media mirroring unavailable this session")
            return
        }
        if (amsSetupStarted) return
        amsSetupStarted = true
        val host = scope ?: return
        CoroutineScope(host.coroutineContext + amsJob).launch {
            val euOk = amsSubscribe(eu)
            if (!euOk) {
                Log.w(TAG, "AMS Entity Update subscribe failed — skipping media mirroring")
                return@launch
            }
            val rcOk = amsRemoteCommand?.let { amsSubscribe(it) } ?: false
            val playerOk =
                amsWrite(eu, Ams.buildEntityUpdateRegistration(Ams.ENTITY_PLAYER, Ams.PLAYER_ATTRS))
            val trackOk =
                amsWrite(eu, Ams.buildEntityUpdateRegistration(Ams.ENTITY_TRACK, Ams.TRACK_ATTRS))
            Log.i(TAG, "AMS setup: remoteCommand=$rcOk player=$playerOk track=$trackOk")
        }
    }

    /** Enable notifications on an AMS characteristic (CCCD write), awaiting its [onDescriptorWrite]. */
    private suspend fun amsSubscribe(ch: BluetoothGattCharacteristic): Boolean = cpMutex.withLock {
        val g = gatt ?: return false
        g.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(Ancs.CCCD) ?: return false
        val d = CompletableDeferred<Boolean>()
        amsDescriptorWrite = d
        val issued =
            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                BluetoothStatusCodes.SUCCESS
        val ok = issued && (withTimeoutOrNull(AMS_OP_TIMEOUT_MS) { d.await() } ?: false)
        amsDescriptorWrite = null
        ok
    }

    /**
     * Write [payload] to an AMS characteristic and await its write completion under [cpMutex]. One brief
     * retry covers the ATT-busy window an ANCS fire-and-forget Control Point write can leave behind
     * ([performAction] releases the lock before its own completion callback lands).
     */
    private suspend fun amsWrite(ch: BluetoothGattCharacteristic, payload: ByteArray): Boolean =
        cpMutex.withLock {
            val g = gatt ?: return false
            val pending = AmsPendingWrite(ch.uuid)
            amsPendingWrite = pending
            var issued = g.writeCharacteristic(
                ch, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) == BluetoothStatusCodes.SUCCESS
            if (!issued) {
                delay(AMS_WRITE_RETRY_MS)
                issued = g.writeCharacteristic(
                    ch, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ) == BluetoothStatusCodes.SUCCESS
            }
            val ok = issued && (withTimeoutOrNull(AMS_OP_TIMEOUT_MS) { pending.deferred.await() } ?: false)
            amsPendingWrite = null
            ok
        }

    private suspend fun request(
        command: Int,
        attrCount: Int,
        payload: ByteArray,
        correlationId: Int = -1
    ): ByteArray? = cpMutex.withLock {
        val cp = controlPoint ?: return null
        val g = gatt ?: return null
        val req = PendingRequest(command, attrCount, correlationId)
        pending = req
        val status =
            g.writeCharacteristic(cp, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        if (status != BluetoothStatusCodes.SUCCESS) {
            pending = null; return null
        }
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
            onStatus(IosBridgeStatus.SHARING)
            // ANCS is fully enabled (its CCCD chain is done) — now bring up AMS on the same link.
            startAmsSetup()
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
                BluetoothProfile.STATE_CONNECTED -> {
                    onStatus(IosBridgeStatus.CONNECTING); g.requestMtu(MTU)
                }
                // Covers both a clean disconnect and a failed connect (newState=DISCONNECTED with an error
                // status like 133/135). Tear down and tell the manager so it can retry the next connection.
                BluetoothProfile.STATE_DISCONNECTED -> {
                    onStatus(IosBridgeStatus.ADVERTISING); teardown()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "mtu=$mtu status=$status — discovering services")
            // Plain discover — do NOT refresh() here. On this Samsung, refresh() right before discovery makes the
            // first discovery come back EMPTY, so we'd loop several times before ANCS shows up. A fresh link's OTA
            // discovery returns the real DB (ANCS included) directly; we only refresh if a populated DB turns out
            // to be missing ANCS (a stale cache — handled in onServicesDiscovered).
            if (!g.discoverServices()) onStatus(IosBridgeStatus.ERROR)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            Log.i(
                TAG,
                "services discovered status=$status: [${g.services.joinToString { it.uuid.toString() }}]"
            )
            // Re-capture AMS from this (possibly fresh) database. Failing any in-flight AMS op first keeps a
            // service-changed re-discovery from stranding a waiter on a stale characteristic. AMS setup itself
            // runs once ANCS reaches SHARING (see enableNext) so it never interleaves with the enable chain.
            amsReset()
            g.getService(Ams.SERVICE_UUID)?.let { ams ->
                amsRemoteCommand = ams.getCharacteristic(Ams.REMOTE_COMMAND)
                amsEntityUpdate = ams.getCharacteristic(Ams.ENTITY_UPDATE)
                amsEntityAttribute = ams.getCharacteristic(Ams.ENTITY_ATTRIBUTE)
            }
            val svc = g.getService(Ancs.SERVICE_UUID)
            if (svc == null) {
                // ANCS is hidden until the link is encrypted. If we're not bonded, bond and re-discover; only
                // give up (NO_ANCS) once it's still absent after bonding (the peer isn't sharing notifications).
                if (device.bondState != BluetoothDevice.BOND_BONDED) {
                    Log.i(TAG, "ANCS not visible yet & not bonded — requesting bond")
                    onStatus(IosBridgeStatus.NEEDS_PAIRING)
                    armWatchdog(PAIRING_DEADLINE_MS) // user-paced: give them time to accept pairing
                    onBondNeeded()
                } else if (++discoveryAttempts <= MAX_DISCOVERY_ATTEMPTS) {
                    if (g.services.isEmpty()) {
                        // Empty result — the OTA discovery didn't land yet (common on this Samsung). Re-discover
                        // WITHOUT refreshing; another refresh just re-clears the cache and we loop on empties.
                        Log.i(
                            TAG,
                            "discovery came back empty — re-discovering #$discoveryAttempts (no refresh)"
                        )
                        handler.postDelayed({
                            gatt?.let {
                                if (!it.discoverServices()) onStatus(
                                    IosBridgeStatus.ERROR
                                )
                            }
                        }, DISCOVERY_RETRY_MS)
                    } else {
                        // A populated DB that's genuinely missing ANCS — a stale cache. Clear it once, re-discover.
                        Log.i(
                            TAG,
                            "ANCS absent from a populated DB — refresh + re-discover #$discoveryAttempts"
                        )
                        refreshThenDiscover()
                    }
                } else {
                    Log.w(
                        TAG,
                        "ANCS still absent after $MAX_DISCOVERY_ATTEMPTS refreshes — peer not sharing"
                    )
                    onStatus(IosBridgeStatus.NO_ANCS)
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

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            // AMS CCCD writes are awaited suspend-style (amsSubscribe), not chained like the ANCS enables —
            // route them out before the ANCS retry logic (which would otherwise re-drive ANCS state on them).
            val chUuid = descriptor.characteristic?.uuid
            if (chUuid == Ams.ENTITY_UPDATE || chUuid == Ams.REMOTE_COMMAND) {
                Log.i(TAG, "AMS CCCD write status=$status ($chUuid)")
                amsDescriptorWrite?.complete(status == BluetoothGatt.GATT_SUCCESS)
                return
            }
            Log.i(TAG, "CCCD write status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                cccdAttempts = 0
                enableNext(g)
                return
            }
            if (device.bondState != BluetoothDevice.BOND_BONDED) {
                // Not bonded yet — pair, then onBonded() re-discovers + re-enables once it completes.
                Log.i(TAG, "CCCD rejected & not bonded — requesting bond")
                onStatus(IosBridgeStatus.NEEDS_PAIRING)
                armWatchdog(PAIRING_DEADLINE_MS) // user-paced: give them time to accept pairing
                onBondNeeded()
                return
            }
            // Bonded but rejected (status 14 = encryption not yet settled on this LE link — common right after
            // cross-transport key derivation from a classic bond). Retry the SAME write after a growing delay;
            // do NOT re-discover (that thrashes the link and the iPhone terminates it). Bounded.
            if (++cccdAttempts <= MAX_CCCD_ATTEMPTS) {
                val delay = CCCD_RETRY_BASE_MS * cccdAttempts
                Log.i(
                    TAG,
                    "CCCD rejected while bonded — retry #$cccdAttempts in ${delay}ms (waiting for encryption)"
                )
                retryEnableAfter(delay)
            } else {
                Log.w(
                    TAG,
                    "CCCD still failing after $MAX_CCCD_ATTEMPTS attempts — link won't encrypt; giving up"
                )
                onStatus(IosBridgeStatus.NO_ANCS)
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            when (ch.uuid) {
                Ancs.NOTIFICATION_SOURCE -> Ancs.parseSource(value)?.let(onSourceEvent)
                Ancs.DATA_SOURCE -> appendDataSource(value)
                Ams.ENTITY_UPDATE -> Ams.parseEntityUpdate(value)?.let(onAmsEntityUpdate)
                Ams.REMOTE_COMMAND -> onAmsSupportedCommands(Ams.parseSupportedCommands(value))
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            status: Int
        ) {
            // Only AMS writes are awaited (ANCS Control Point writes await their Data Source response
            // instead); key by uuid so a late ANCS write completion can't complete an AMS waiter.
            amsPendingWrite?.takeIf { it.uuid == ch.uuid }
                ?.deferred?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            // Only the AMS Entity Attribute is ever read (the truncated-value fetch).
            if (ch.uuid == Ams.ENTITY_ATTRIBUTE) {
                amsCharacteristicRead?.complete(if (status == BluetoothGatt.GATT_SUCCESS) value else null)
            }
        }

        override fun onServiceChanged(g: BluetoothGatt) {
            // The iPhone's GATT database changed (services can come and go — AMS in particular is not
            // guaranteed to be present at connect time). Re-discover so a late-appearing service is picked
            // up; onServicesDiscovered re-runs the ANCS enable chain (idempotent) and re-arms AMS.
            Log.i(TAG, "GATT services changed — re-discovering")
            if (!g.discoverServices()) Log.w(TAG, "re-discovery after service change failed to start")
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
            Ancs.CMD_GET_NOTIFICATION_ATTRIBUTES -> Ancs.parseNotificationAttributes(
                buf,
                req.attrCount
            ) != null

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
        const val TAG = "IosGattClient"
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

        // AMS ops are simple single-op ATT round-trips (no fragmented responses like ANCS Data Source).
        const val AMS_OP_TIMEOUT_MS = 3_000L
        const val AMS_WRITE_RETRY_MS = 150L
    }
}

package net.extrawdw.apps.notisync.pairing

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.extrawdw.apps.notisync.MainActivity
import net.extrawdw.apps.notisync.R

/**
 * Device-protected, no-backup cache of the last locally generated public pairing card.
 *
 * HCE can start the process without an Activity and must answer its first APDU immediately. [preload] is
 * therefore called from Application.onCreate; HostApduService reads only the volatile memory snapshot.
 */
internal object PairingCardStore {
    @Volatile
    private var cachedPayload: String? = null

    @Volatile
    private var loaded = false

    @Synchronized
    fun preload(context: Context) {
        if (loaded) return
        cachedPayload = preferences(context).getString(KEY_OUTGOING_PAYLOAD, null)
            ?.takeIf { runCatching { PairingNfcProtocol.payloadBytes(it) }.isSuccess }
        loaded = true
    }

    /** Memory-only HCE hot path. Application.onCreate always calls [preload] before services are created. */
    fun current(): String? = cachedPayload

    /** Called off-main when a new signed card is generated. */
    fun persist(context: Context, payload: String) {
        PairingNfcProtocol.payloadBytes(payload)
        check(preferences(context).edit().putString(KEY_OUTGOING_PAYLOAD, payload).commit()) {
            "could not persist the NFC pairing card"
        }
        cachedPayload = payload
        loaded = true
    }
}

/** One durable, untrusted pairing card received by the HCE side of a tap. */
internal object PairingNfcInbox {
    private val _pendingPayload = MutableStateFlow<String?>(null)
    val pendingPayload: StateFlow<String?> = _pendingPayload.asStateFlow()

    @Volatile
    private var loaded = false

    @Synchronized
    fun preload(context: Context) {
        if (loaded) return
        _pendingPayload.value = preferences(context).getString(KEY_INCOMING_PAYLOAD, null)
            ?.takeIf { runCatching { PairingNfcProtocol.payloadBytes(it) }.isSuccess }
        loaded = true
    }

    /** Non-blocking commit path used by HostApduService.processCommandApdu on the main thread. */
    fun offer(context: Context, payload: String) {
        PairingNfcProtocol.payloadBytes(payload)
        preferences(context).edit().putString(KEY_INCOMING_PAYLOAD, payload).apply()
        _pendingPayload.value = payload
        // Android's NFC service binds HostApduService with BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS. Queue the
        // launch behind processCommandApdu's response, then fall back to a heads-up notification if the
        // Activity did not become visible (for example because the device is locked or an OEM blocks it).
        Handler(Looper.getMainLooper()).post {
            surfacePairingReview(context.applicationContext, payload)
        }
    }

    fun consume(context: Context, payload: String) {
        if (_pendingPayload.value != payload) return
        preferences(context).edit().remove(KEY_INCOMING_PAYLOAD).apply()
        _pendingPayload.value = null
        dismissNotification(context)
    }

    fun dismissNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(PAIRING_NOTIFICATION_ID)
    }

    private fun surfacePairingReview(context: Context, payload: String) {
        if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            return
        }
        val interactive = context.getSystemService(PowerManager::class.java).isInteractive
        val unlocked = !context.getSystemService(KeyguardManager::class.java).isDeviceLocked
        if (!interactive || !unlocked) {
            showPairingNotification(context)
            return
        }

        val launchSucceeded = runCatching {
            context.startActivity(pairingReviewIntent(context))
        }.isSuccess
        if (!launchSucceeded) {
            showPairingNotification(context)
            return
        }
        Handler(Looper.getMainLooper()).postDelayed(
            {
                if (_pendingPayload.value == payload &&
                    !ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                ) {
                    showPairingNotification(context)
                }
            },
            PAIRING_ACTIVITY_LAUNCH_GRACE_MILLIS,
        )
    }

    private fun showPairingNotification(context: Context) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                PAIRING_NOTIFICATION_CHANNEL,
                context.getString(R.string.pair_nfc_notification_channel),
                NotificationManager.IMPORTANCE_HIGH,
            )
        )
        val open = PendingIntent.getActivity(
            context,
            PAIRING_NOTIFICATION_ID,
            pairingReviewIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        NotificationManagerCompat.from(context).notify(
            PAIRING_NOTIFICATION_ID,
            NotificationCompat.Builder(context, PAIRING_NOTIFICATION_CHANNEL)
                .setSmallIcon(R.drawable.ic_notisync_mirror)
                .setContentTitle(context.getString(R.string.pair_nfc_notification_title))
                .setContentText(context.getString(R.string.pair_nfc_notification_text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .build(),
        )
    }

    private fun pairingReviewIntent(context: Context) = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_SINGLE_TOP
        putExtra(MainActivity.EXTRA_OPEN_DEVICES, true)
    }
}

/** Foreground reader-mode lifetime plus one reciprocal ISO-DEP exchange. */
internal class PairingNfcReaderSession private constructor(
    private val activity: Activity,
    private val adapter: NfcAdapter,
    ownPayload: String,
    private val onPayload: (String) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) : Closeable, NfcAdapter.ReaderCallback {
    private val ownPayloadBytes = PairingNfcProtocol.payloadBytes(ownPayload)
    private val active = AtomicBoolean(true)
    private val handling = AtomicBoolean(false)

    @Volatile
    private var activeIsoDep: IsoDep? = null

    override fun onTagDiscovered(tag: Tag) {
        if (!active.get() || !handling.compareAndSet(false, true)) return
        try {
            val payload = exchange(tag)
            activity.runOnUiThread {
                if (active.get()) onPayload(payload)
            }
        } catch (failure: Throwable) {
            activity.runOnUiThread {
                if (active.get()) onFailure(failure)
            }
        } finally {
            activeIsoDep = null
            handling.set(false)
        }
    }

    override fun close() {
        if (!active.compareAndSet(true, false)) return
        runCatching { activeIsoDep?.close() }
        activeIsoDep = null
        runCatching { adapter.disableReaderMode(activity) }
    }

    private fun exchange(tag: Tag): String {
        val isoDep = requireNotNull(IsoDep.get(tag)) { "the NFC peer does not support ISO-DEP" }
        activeIsoDep = isoDep
        isoDep.use { connection ->
            connection.connect()
            val selection = PairingNfcProtocol.parseSelectResponse(
                connection.transceive(PairingNfcProtocol.selectApplicationCommand)
            )
            val chunkSize = PairingNfcProtocol.readerChunkSize(
                maxTransceiveLength = connection.maxTransceiveLength,
                peerMaxChunkSize = selection.maxChunkSize,
            )

            val remotePayload = ByteArray(selection.payloadSize)
            selection.initialPayload.copyInto(remotePayload)
            var readerOffset = 0
            var peerOffset = selection.initialPayload.size
            while (readerOffset < ownPayloadBytes.size || peerOffset < remotePayload.size) {
                val readerLength = minOf(chunkSize, ownPayloadBytes.size - readerOffset)
                val peerLength = minOf(chunkSize, remotePayload.size - peerOffset)
                val peerChunk = PairingNfcProtocol.requireSuccessfulResponse(
                    connection.transceive(
                        PairingNfcProtocol.exchangePayloadCommand(
                            readerPayload = ownPayloadBytes,
                            readerOffset = readerOffset,
                            readerLength = readerLength,
                            requestedPeerOffset = peerOffset,
                            requestedPeerLength = peerLength,
                        )
                    )
                )
                require(peerChunk.size == peerLength) {
                    "NFC peer returned an incomplete pairing payload"
                }
                peerChunk.copyInto(remotePayload, destinationOffset = peerOffset)
                readerOffset += readerLength
                peerOffset += peerLength
            }
            PairingNfcProtocol.requireSuccessfulResponse(
                connection.transceive(PairingNfcProtocol.commitExchangeCommand)
            )
            return PairingNfcProtocol.payloadString(remotePayload)
        }
    }

    companion object {
        fun start(
            context: Context,
            ownPayload: String,
            onPayload: (String) -> Unit,
            onFailure: (Throwable) -> Unit,
        ): PairingNfcReaderSession? {
            val activity = context.findActivity() ?: return null
            val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return null
            val session = PairingNfcReaderSession(
                activity = activity,
                adapter = adapter,
                ownPayload = ownPayload,
                onPayload = onPayload,
                onFailure = onFailure,
            )
            return runCatching {
                adapter.enableReaderMode(
                    activity,
                    session,
                    NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                    adapter.pairingReaderModeExtras(),
                )
                session
            }.getOrElse {
                session.close()
                onFailure(it)
                null
            }
        }
    }
}

private fun NfcAdapter.pairingReaderModeExtras(): Bundle? {
    if (Build.VERSION.SDK_INT < 37) return null
    val annotationsSupported = runCatching { isReaderModeAnnotationSupported }.getOrDefault(false)
    if (!annotationsSupported) return null
    return Bundle().apply {
        putByteArray(
            NfcAdapter.EXTRA_READER_TECH_A_POLLING_LOOP_ANNOTATION,
            PairingNfcPolling.annotationBytes(),
        )
    }
}

private fun preferences(context: Context) =
    context.createDeviceProtectedStorageContext()
        .getSharedPreferences(PAIRING_PREFERENCES, Context.MODE_PRIVATE)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private const val PAIRING_PREFERENCES = "notisync_pairing_nfc"
private const val KEY_OUTGOING_PAYLOAD = "outgoing_payload"
private const val KEY_INCOMING_PAYLOAD = "incoming_payload"
private const val PAIRING_NOTIFICATION_CHANNEL = "notisync.pairing"
private const val PAIRING_NOTIFICATION_ID = 0x4E534643
private const val PAIRING_ACTIVITY_LAUNCH_GRACE_MILLIS = 1_000L

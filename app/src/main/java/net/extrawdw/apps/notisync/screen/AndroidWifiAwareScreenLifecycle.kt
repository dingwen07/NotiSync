package net.extrawdw.apps.notisync.screen

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.Characteristics
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareDataPathSecurityConfig
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareSession
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred

/** Owns every callback/session/socket associated with one one-shot Aware rendezvous. */
internal class AndroidWifiAwareLifetime : AutoCloseable {
    private val lock = Any()
    private val resources = LinkedHashSet<AutoCloseable>()
    private val failureObservers = LinkedHashSet<(IOException) -> Unit>()
    private var terminalFailure: IOException? = null

    fun <T : AutoCloseable> track(resource: T): T {
        val failure = synchronized(lock) {
            terminalFailure ?: run {
                resources += resource
                null
            }
        }
        if (failure != null) {
            runCatching { resource.close() }
            throw failure
        }
        return resource
    }

    fun tryTrack(resource: AutoCloseable): Boolean {
        val accepted = synchronized(lock) {
            if (terminalFailure != null) false else {
                resources += resource
                true
            }
        }
        if (!accepted) runCatching { resource.close() }
        return accepted
    }

    fun untrack(resource: AutoCloseable) {
        synchronized(lock) { resources -= resource }
    }

    fun observeFailure(observer: (IOException) -> Unit) {
        val failure = synchronized(lock) {
            terminalFailure ?: run {
                failureObservers += observer
                null
            }
        }
        if (failure != null) observer(failure)
    }

    fun requireActive() {
        synchronized(lock) { terminalFailure?.let { throw it } }
    }

    fun fail(error: IOException) {
        val snapshot: Pair<List<(IOException) -> Unit>, List<AutoCloseable>> = synchronized(lock) {
            if (terminalFailure != null) return
            terminalFailure = error
            val observers = failureObservers.toList()
            failureObservers.clear()
            val closing = resources.toList().asReversed()
            resources.clear()
            observers to closing
        }
        snapshot.first.forEach { observer -> runCatching { observer(error) } }
        snapshot.second.forEach { resource -> runCatching { resource.close() } }
    }

    override fun close() = fail(IOException("Wi-Fi Aware screen rendezvous was closed"))
}

internal object AndroidWifiAwarePlatform {
    fun hasRequiredPermissions(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) ==
            PackageManager.PERMISSION_GRANTED &&
            (Build.VERSION.SDK_INT < 37 ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_LOCAL_NETWORK,
                ) == PackageManager.PERMISSION_GRANTED)

    fun requirePermissions(context: Context) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("nearby Wi-Fi permission is not granted")
        }
        if (Build.VERSION.SDK_INT >= 37 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("local network permission is not granted for Wi-Fi Aware")
        }
    }

    fun requireManager(context: Context): WifiAwareManager {
        check(context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)) {
            "device does not support Wi-Fi Aware"
        }
        val manager = requireNotNull(context.getSystemService(WifiAwareManager::class.java)) {
            "Wi-Fi Aware service is unavailable"
        }
        check(manager.isAvailable) { "Wi-Fi Aware is not currently available" }
        return manager
    }

    /** Android only guarantees characteristics after this process has attached to Wi-Fi Aware. */
    fun requireCharacteristics(manager: WifiAwareManager, serviceName: String) {
        val characteristics = requireNotNull(manager.characteristics) {
            "Wi-Fi Aware characteristics are unavailable"
        }
        check(
            characteristics.supportedCipherSuites and
                Characteristics.WIFI_AWARE_CIPHER_SUITE_NCS_SK_128 != 0,
        ) {
            "Wi-Fi Aware NCS_SK_128 is unavailable"
        }
        check(serviceName.encodeToByteArray().size <= characteristics.maxServiceNameLength) {
            "Wi-Fi Aware service name exceeds this device's limit"
        }
    }

    fun watchAvailability(
        context: Context,
        manager: WifiAwareManager,
        lifetime: AndroidWifiAwareLifetime,
    ) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED &&
                    !manager.isAvailable
                ) {
                    lifetime.fail(IOException("Wi-Fi Aware became unavailable"))
                }
            }
        }
        context.registerReceiver(
            receiver,
            IntentFilter(WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED),
            Context.RECEIVER_NOT_EXPORTED,
        )
        lifetime.track(
            AndroidWifiAwareNetworkCallbackRegistration {
                context.unregisterReceiver(receiver)
            },
        )
        if (!manager.isAvailable) lifetime.fail(IOException("Wi-Fi Aware became unavailable"))
    }

    fun securityConfig(pmk: ByteArray): WifiAwareDataPathSecurityConfig {
        require(pmk.size == AWARE_PMK_BYTES) { "Wi-Fi Aware PMK must be $AWARE_PMK_BYTES bytes" }
        // Android's builder retains this exact array rather than cloning it. Callers keep their
        // derived PMK alive through publish/requestNetwork, then erase it after Binder has copied it.
        return WifiAwareDataPathSecurityConfig.Builder(
            Characteristics.WIFI_AWARE_CIPHER_SUITE_NCS_SK_128,
        ).setPmk(pmk).build()
    }

    suspend fun attach(
        manager: WifiAwareManager,
        lifetime: AndroidWifiAwareLifetime,
    ): WifiAwareSession {
        val attached = CompletableDeferred<WifiAwareSession>()
        lifetime.observeFailure(attached::completeExceptionally)
        manager.attach(object : AttachCallback() {
            override fun onAttached(session: WifiAwareSession) {
                if (lifetime.tryTrack(session)) attached.complete(session)
            }

            override fun onAttachFailed() {
                lifetime.fail(IOException("failed to attach a Wi-Fi Aware session"))
            }

            override fun onAwareSessionTerminated() {
                lifetime.fail(IOException("Wi-Fi Aware session was terminated"))
            }
        }, null)
        return attached.await()
    }

    // Entry points call requirePermissions() immediately before this internal operation.
    @SuppressLint("MissingPermission")
    suspend fun publish(
        awareSession: WifiAwareSession,
        serviceName: String,
        securityConfig: WifiAwareDataPathSecurityConfig,
        lifetime: AndroidWifiAwareLifetime,
    ): PublishDiscoverySession {
        val published = CompletableDeferred<PublishDiscoverySession>()
        lifetime.observeFailure(published::completeExceptionally)
        val config = PublishConfig.Builder()
            .setServiceName(serviceName)
            .setPublishType(PublishConfig.PUBLISH_TYPE_UNSOLICITED)
            // Advertising the same explicit suite produces the SCID used to select the secure NDP.
            .setDataPathSecurityConfig(securityConfig)
            .setTerminateNotificationEnabled(true)
            .build()
        awareSession.publish(config, object : DiscoverySessionCallback() {
            override fun onPublishStarted(session: PublishDiscoverySession) {
                if (lifetime.tryTrack(session)) published.complete(session)
            }

            override fun onSessionConfigFailed() {
                lifetime.fail(IOException("failed to publish the Wi-Fi Aware screen service"))
            }

            override fun onSessionTerminated() {
                lifetime.fail(IOException("Wi-Fi Aware publication was terminated"))
            }
        }, null)
        return published.await()
    }

    // Entry points call requirePermissions() immediately before this internal operation.
    @SuppressLint("MissingPermission")
    suspend fun discover(
        awareSession: WifiAwareSession,
        serviceName: String,
        lifetime: AndroidWifiAwareLifetime,
    ): AndroidWifiAwareDiscovery {
        val discovered = CompletableDeferred<AndroidWifiAwareDiscovery>()
        lifetime.observeFailure(discovered::completeExceptionally)
        val subscribeSession = AtomicReference<SubscribeDiscoverySession?>()
        val selectedPeer = AtomicReference<PeerHandle?>()
        val config = SubscribeConfig.Builder()
            .setServiceName(serviceName)
            // UNSOLICITED publishers are discovered by PASSIVE subscribers. ACTIVE subscribe is
            // the matching side for SOLICITED publish and may never discover this service.
            .setSubscribeType(SubscribeConfig.SUBSCRIBE_TYPE_PASSIVE)
            .setTerminateNotificationEnabled(true)
            .build()
        awareSession.subscribe(config, object : DiscoverySessionCallback() {
            override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                if (!lifetime.tryTrack(session)) return
                subscribeSession.set(session)
            }

            override fun onServiceDiscovered(
                peerHandle: PeerHandle,
                serviceSpecificInfo: ByteArray,
                matchFilter: List<ByteArray>,
            ) {
                val session = subscribeSession.get() ?: return
                if (!selectedPeer.compareAndSet(null, peerHandle)) return
                discovered.complete(AndroidWifiAwareDiscovery(session, peerHandle))
            }

            override fun onServiceLost(peerHandle: PeerHandle, reason: Int) {
                if (selectedPeer.get() == peerHandle) {
                    lifetime.fail(IOException("selected Wi-Fi Aware screen service was lost"))
                }
            }

            override fun onSessionConfigFailed() {
                lifetime.fail(IOException("failed to subscribe to the Wi-Fi Aware screen service"))
            }

            override fun onSessionTerminated() {
                lifetime.fail(IOException("Wi-Fi Aware subscription was terminated"))
            }
        }, null)
        return discovered.await()
    }

    const val AWARE_PMK_BYTES = 32
}

internal data class AndroidWifiAwareDiscovery(
    val session: SubscribeDiscoverySession,
    val peer: PeerHandle,
)

internal class AndroidWifiAwareNetworkCallbackRegistration(
    private val closeAction: () -> Unit,
) : AutoCloseable {
    @Volatile
    private var closed = false

    override fun close() {
        if (closed) return
        synchronized(this) {
            if (closed) return
            closed = true
        }
        runCatching(closeAction)
    }
}

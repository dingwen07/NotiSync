package net.extrawdw.apps.notisync.run

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import net.extrawdw.apps.notisync.MainActivity
import net.extrawdw.apps.notisync.NotiSyncApp
import net.extrawdw.apps.notisync.R
import net.extrawdw.apps.notisync.analytics.crashGuard
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.RunBlockedReason
import net.extrawdw.notisync.protocol.RunPhase
import net.extrawdw.notisync.protocol.RunProgress
import net.extrawdw.notisync.protocol.RunState
import net.extrawdw.notisync.protocol.RunUpdateReason

/** Dedicated renderer for Run state. No CapturedNotification or MirrorEngine types cross this boundary. */
class RunNotificationPresenter(
    private val context: Context,
    private val deviceNameOf: (ClientId) -> String? = { null },
) : RunStatePresenter {
    override fun render(state: RunState): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return false

        val peerName = deviceNameOf(state.hostClientId)
        val channelId = RunNotificationChannels.ensure(context, state.hostClientId, peerName)
        val key = RunKey(state.hostClientId.value, state.runId)
        val active = RunPresentationPolicy.active(state)
        val terminal = !active
        val silent = RunPresentationPolicy.silent(state)
        val title = state.llmSummary?.title?.takeIf { it.isNotBlank() } ?: commandLabel(state)
        val summaryBody = RunPresentationPolicy.summaryBody(state)
        val deterministicStatus = statusText(state)
        val contentText = summaryBody?.text?.takeIf { it.isNotBlank() } ?: deterministicStatus
        val expanded = summaryBody?.expandedText?.takeIf { it.isNotBlank() }
            ?: state.failureMessage?.takeIf { it.isNotBlank() }
            ?: state.terminal.text.takeLast(NOTIFICATION_TERMINAL_CHARS).takeIf { it.isNotBlank() }
        val progress = state.progress?.takeUnless { terminal }?.toNativeProgress()
        val tag = tagOf(key)
        val id = tag.hashCode()

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_terminal_notification)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSubText(
                peerName?.let {
                    context.getString(R.string.run_notification_via, it)
                } ?: context.getString(R.string.run_notification_subtext)
            )
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setWhen(state.startedAt)
            .setShowWhen(true)
            .setOngoing(active)
            .setAutoCancel(terminal)
            .setOnlyAlertOnce(silent)
            .setSilent(silent)
            .setContentIntent(openIntent(key, id))
            .setShortCriticalText(shortCriticalText(state))

        if (active) builder.setRequestPromotedOngoing(true)
        if (progress != null) {
            val style = NotificationCompat.ProgressStyle()
                .setProgressIndeterminate(progress.indeterminate)
            if (!progress.indeterminate) {
                style.addProgressSegment(NotificationCompat.ProgressStyle.Segment(progress.total))
                    .setProgress(progress.current)
                    .setStyledByProgress(true)
            }
            builder.setStyle(style)
        } else if (expanded != null) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(expanded))
        }

        notificationActions(state).forEach { action ->
            val pendingIntent = actionPendingIntent(key, state.interactionGeneration, action, id)
            val compatAction = NotificationCompat.Action.Builder(0, action.label, pendingIntent)
            if (action == RunShadeAction.INPUT) {
                compatAction.addRemoteInput(
                    RemoteInput.Builder(RunActionReceiver.REMOTE_INPUT_KEY)
                        .setLabel(context.getString(R.string.run_input_hint))
                        .build()
                )
            }
            builder.addAction(compatAction.build())
        }

        NotificationManagerCompat.from(context).notify(tag, id, builder.build())
        return true
    }

    override fun dismiss(key: RunKey) {
        val tag = tagOf(key)
        NotificationManagerCompat.from(context).cancel(tag, tag.hashCode())
    }

    private fun openIntent(key: RunKey, id: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_RUN
            data = "notisync://run/${Uri.encode(key.hostClientId)}/${Uri.encode(key.runId)}".toUri()
            putExtra(MainActivity.EXTRA_RUN_HOST_CLIENT_ID, key.hostClientId)
            putExtra(MainActivity.EXTRA_RUN_ID, key.runId)
        }
        return PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun actionPendingIntent(
        key: RunKey,
        generation: Long,
        action: RunShadeAction,
        notificationId: Int,
    ): PendingIntent {
        val intent = Intent(context, RunActionReceiver::class.java).apply {
            this.action = RunActionReceiver.ACTION_CONTROL
            // INPUT must use a mutable PendingIntent for RemoteInput. Bind every privileged routing field into
            // the already-populated base data URI; fill-in extras can then carry text but cannot retarget/control.
            data = runActionRouteData(key, generation, action.name).toUri()
        }
        val mutable = if (action == RunShadeAction.INPUT) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(
            context,
            notificationId * 31 + action.ordinal,
            intent,
            mutable or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun notificationActions(state: RunState): List<RunShadeAction> =
        RunPresentationPolicy.shadeActions(state)

    private fun commandLabel(state: RunState): String =
        state.argv.firstOrNull()?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.run_command_fallback)

    private fun statusText(state: RunState): String = when (state.phase) {
        RunPhase.RUNNING -> if (state.updateReason == RunUpdateReason.RESUMED) {
            context.getString(R.string.run_status_resumed)
        } else state.progress?.percentOrNull()?.let { percent ->
            context.getString(R.string.run_status_progress, percent)
        } ?: context.getString(R.string.run_status_running)
        RunPhase.BLOCKED -> when (state.blockedReason) {
            RunBlockedReason.TERMINAL_INPUT -> context.getString(R.string.run_status_waiting_input)
            RunBlockedReason.OUTPUT_AND_CPU_IDLE -> context.getString(R.string.run_status_attention)
            null -> context.getString(R.string.run_status_blocked)
        }
        RunPhase.COMPLETED -> if (state.exitCode == null || state.exitCode == 0) {
            context.getString(R.string.run_status_completed)
        } else context.getString(R.string.run_status_exit_code, state.exitCode)
        RunPhase.FAILED_TO_START -> context.getString(R.string.run_status_failed_start)
    }

    private fun shortCriticalText(state: RunState): String {
        val progressPercent = state.progress?.percentOrNull()
        return when {
            RunPresentationPolicy.blockedNeedsInput(state) -> context.getString(R.string.run_short_input)
            state.phase == RunPhase.BLOCKED -> context.getString(R.string.run_short_attention)
            state.phase == RunPhase.COMPLETED -> context.getString(R.string.run_short_done)
            state.phase == RunPhase.FAILED_TO_START -> context.getString(R.string.run_short_failed)
            progressPercent != null -> "$progressPercent%".take(6)
            else -> context.getString(R.string.run_short_running)
        }
    }

    private val RunShadeAction.label: String get() = context.getString(
        when (this) {
            RunShadeAction.YES -> R.string.run_action_yes
            RunShadeAction.NO -> R.string.run_action_no
            RunShadeAction.INPUT -> R.string.run_action_input
            RunShadeAction.INTERRUPT -> R.string.run_action_interrupt
            RunShadeAction.TERMINATE -> R.string.run_action_terminate
        }
    )

    private data class NativeProgress(val current: Int, val total: Int, val indeterminate: Boolean)

    private fun RunProgress.percentOrNull(): Int? {
        val value = current ?: return null
        val maximum = total ?: return null
        if (maximum <= 0) return null
        return (value.toDouble() / maximum.toDouble() * 100).roundToInt().coerceIn(0, 100)
    }

    private fun RunProgress.toNativeProgress(): NativeProgress {
        val value = current
        val maximum = total
        if (indeterminate || value == null || maximum == null || maximum <= 0) {
            return NativeProgress(0, 100, true)
        }
        val clamped = value.coerceIn(0, maximum)
        if (maximum <= Int.MAX_VALUE) return NativeProgress(clamped.toInt(), maximum.toInt(), false)
        val scaledTotal = 10_000
        val scaledCurrent = (clamped.toDouble() / maximum.toDouble() * scaledTotal)
            .roundToInt().coerceIn(0, scaledTotal)
        return NativeProgress(scaledCurrent, scaledTotal, false)
    }

    private fun tagOf(key: RunKey): String = "notisync-run:${key.hostClientId}:${key.runId}"

    companion object {
        private const val NOTIFICATION_TERMINAL_CHARS = 2_048
    }
}

/** One fixed Run group and one deterministic, user-configurable HIGH channel per authenticated host peer. */
internal object RunNotificationChannels {
    const val GROUP_ID = "notisync_run"
    const val GROUP_NAME = "NotiSync Run"
    const val CHANNEL_ID_PREFIX = "notisync_run_peer_"
    fun vibrationPattern(): LongArray = longArrayOf(0, 200, 120, 200)

    fun channelId(hostClientId: ClientId): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(hostClientId.value.encodeToByteArray())
        val hex = CharArray(digest.size * 2)
        digest.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            hex[index * 2] = HEX[value ushr 4]
            hex[index * 2 + 1] = HEX[value and 0x0f]
        }
        return CHANNEL_ID_PREFIX + hex.concatToString()
    }

    fun channelName(displayName: String?, hostClientId: ClientId): String =
        displayName?.trim()?.takeIf { it.isNotEmpty() } ?: hostClientId.shortForm()

    fun ensure(
        context: Context,
        hostClientId: ClientId,
        displayName: String?,
    ): String {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannelGroup(NotificationChannelGroup(GROUP_ID, GROUP_NAME))
        val peerName = channelName(displayName, hostClientId)
        val id = channelId(hostClientId)
        manager.createNotificationChannel(
            NotificationChannel(id, peerName, NotificationManager.IMPORTANCE_HIGH).apply {
                group = GROUP_ID
                description = context.getString(R.string.run_notification_channel_description, peerName)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                setShowBadge(true)
                enableVibration(true)
                vibrationPattern = vibrationPattern()
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .build(),
                )
            }
        )
        return id
    }

    private const val HEX = "0123456789abcdef"
}

internal data class RunActionRoute(val key: RunKey, val control: String, val interactionGeneration: Long)

internal fun runActionRouteData(key: RunKey, interactionGeneration: Long, control: String): String =
    "notisync://run-control/${control.lowercase(Locale.ROOT)}/" +
        "${key.hostClientId.encodePathSegment()}/${key.runId.encodePathSegment()}/$interactionGeneration"

/** Parse only immutable base Intent data. Mutable RemoteInput fill-in extras are deliberately ignored here. */
internal fun parseRunActionRoute(dataString: String?): RunActionRoute? = runCatching {
    val uri = URI(dataString ?: return null)
    if (
        uri.scheme != "notisync" || uri.host != "run-control" || uri.port != -1 || uri.userInfo != null ||
        uri.rawQuery != null || uri.rawFragment != null
    ) return null
    val segments = uri.rawPath.orEmpty().removePrefix("/").split('/')
    if (segments.size != 4) return null
    val control = segments[0].decodePathSegment().uppercase(Locale.ROOT)
    if (control !in RUN_NOTIFICATION_CONTROLS) return null
    val host = segments[1].decodePathSegment()
    val runId = segments[2].decodePathSegment()
    val generation = segments[3].toLongOrNull() ?: return null
    if (host.isBlank() || runId.isBlank() || generation < 0) return null
    RunActionRoute(RunKey(host, runId), control, generation)
}.getOrNull()

internal fun buildRunNotificationControl(
    route: RunActionRoute,
    remoteInput: String?,
    requestId: String,
    requestedAt: Long,
): net.extrawdw.notisync.protocol.RunControl? = runCatching {
    val base = net.extrawdw.notisync.protocol.RunControl(
        requestId = requestId,
        hostClientId = ClientId(route.key.hostClientId),
        runId = route.key.runId,
        kind = when (route.control) {
            "YES", "NO", "INPUT" -> net.extrawdw.notisync.protocol.RunControlKind.WRITE_INPUT
            else -> net.extrawdw.notisync.protocol.RunControlKind.SIGNAL
        },
        requestedAt = requestedAt,
        interactionGeneration = when (route.control) {
            "YES", "NO", "INPUT" -> route.interactionGeneration
            else -> null
        },
        inputText = when (route.control) {
            "YES" -> "y\n"
            "NO" -> "n\n"
            "INPUT" -> remoteInput?.asRunTerminalLine() ?: return null
            else -> null
        },
        signal = when (route.control) {
            "INTERRUPT" -> "INT"
            "TERMINATE" -> "TERM"
            else -> null
        },
    )
    base
}.getOrNull()

private fun String.encodePathSegment(): String =
    URLEncoder.encode(this, Charsets.UTF_8).replace("+", "%20")

private fun String.decodePathSegment(): String = URLDecoder.decode(this, Charsets.UTF_8)

private val RUN_CONTEXTUAL_CONTROLS = setOf("YES", "NO", "INPUT")
private val RUN_NOTIFICATION_CONTROLS = RUN_CONTEXTUAL_CONTROLS + setOf("INTERRUPT", "TERMINATE")

/** Notification shade action receiver. All actions become durable DATA_SYNC/RUN controls. */

class RunActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CONTROL) return
        val route = parseRunActionRoute(intent.dataString) ?: return
        val remoteInput = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(REMOTE_INPUT_KEY)?.toString()
        val control = buildRunNotificationControl(
            route = route,
            remoteInput = remoteInput,
            requestId = UUID.randomUUID().toString(),
            requestedAt = System.currentTimeMillis(),
        ) ?: return
        val app = context.applicationContext as? NotiSyncApp ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO + crashGuard("RunActionReceiver")).launch {
            var outbox: RunControlOutbox? = null
            try {
                val queue = RunControlOutbox(context)
                outbox = queue
                queue.enqueue(control)
                runCatching { RunControlDrainWorker.enqueue(context) }
                val graph = app.awaitGraphReady(RECEIVER_GRAPH_WAIT_MS)
                val engine = graph?.runEngine
                if (engine != null) {
                    // Use the remaining broadcast budget for a fast send. Timeout/cancellation leaves the exact
                    // request in SQLite for WorkManager; no new request id is minted on retry.
                    withTimeoutOrNull(RECEIVER_SEND_JOIN_MS) {
                        RunControlOutboxDrainer.drain(
                            queue = queue,
                            send = engine::sendPersistedControl,
                        )
                    }
                }
            } catch (error: Exception) {
                Log.w("RunActionReceiver", "could not queue Run action", error)
            } finally {
                outbox?.close()
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_CONTROL = "net.extrawdw.apps.notisync.RUN_CONTROL"
        const val REMOTE_INPUT_KEY = "run_input"
        private const val RECEIVER_GRAPH_WAIT_MS = 7_000L
        private const val RECEIVER_SEND_JOIN_MS = 2_000L
    }
}

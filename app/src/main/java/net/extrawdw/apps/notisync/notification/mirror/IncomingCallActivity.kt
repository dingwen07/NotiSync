package net.extrawdw.apps.notisync.notification.mirror

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.extrawdw.apps.notisync.NotiSyncApp
import net.extrawdw.apps.notisync.R
import net.extrawdw.apps.notisync.ui.theme.NotiSyncTheme
import net.extrawdw.notisync.protocol.CapturedNotification
import net.extrawdw.notisync.protocol.NotificationAction

/** The stable, non-executable information rendered by the incoming-call activity. */
internal data class IncomingCallPresentation(
    val sourceClientId: String,
    val sourceKey: String,
    val packageName: String,
    val iosBundleId: String?,
    val appIconHash: String?,
    val appLabel: String,
    val callerName: String,
    val callText: String?,
    val verificationText: String?,
    val deviceName: String?,
) {
    val key: String get() = "$sourceClientId|$sourceKey"
}

/** The two actions a ringing call screen understands; nulls deliberately stay non-destructive. */
internal data class IncomingCallActionSelection(
    val answer: NotificationAction?,
    val decline: NotificationAction?,
)

/** Build the call-screen copy while suppressing duplicate notification lines. */
internal fun CapturedNotification.incomingCallPresentation(
    deviceName: String?,
): IncomingCallPresentation {
    fun String?.nonBlank(): String? = this?.trim()?.takeIf(String::isNotEmpty)
    fun String?.differentFrom(vararg values: String?): String? {
        val candidate = nonBlank() ?: return null
        return candidate.takeUnless { text ->
            values.any { value -> value?.trim()?.equals(text, ignoreCase = true) == true }
        }
    }

    val caller = callerName.nonBlank()
        ?: title.nonBlank()
        ?: text.nonBlank()
        ?: appLabel
    val verification = callVerificationText.differentFrom(caller, appLabel)
    val detail = text.differentFrom(caller, appLabel, verification)
    return IncomingCallPresentation(
        sourceClientId = sourceClientId.value,
        sourceKey = sourceKey,
        packageName = packageName,
        iosBundleId = iosBundleId,
        appIconHash = appIcon?.assetHash,
        appLabel = appLabel,
        callerName = caller,
        callText = detail,
        verificationText = verification,
        deviceName = deviceName.nonBlank(),
    )
}

/** Explicit CallStyle indices win; SEMANTIC_ACTION_CALL is a safe answer fallback for category-only calls. */
internal fun CapturedNotification.incomingCallActions(): IncomingCallActionSelection {
    fun indexed(index: Int?): NotificationAction? =
        index?.let { wanted -> actions.firstOrNull { it.index == wanted } }

    val answer = indexed(callAnswerIndex)
        ?: actions.firstOrNull { it.semanticAction == Notification.Action.SEMANTIC_ACTION_CALL }
    val decline = (indexed(callDeclineIndex) ?: indexed(callHangUpIndex))
        ?.takeUnless { it == answer }
    return IncomingCallActionSelection(answer, decline)
}

/**
 * The lock-screen surface for a mirrored incoming call. The real call still lives on the source device:
 * Answer/Decline send the same PendingIntents used by NotificationCompat.CallStyle back through
 * [MirrorActionReceiver]. No keyguard dismissal is requested, so acting on a remote call never unlocks this phone.
 */
class IncomingCallActivity : ComponentActivity() {
    private data class CallAction(val label: String?, val pendingIntent: PendingIntent)

    private data class ScreenState(
        val presentation: IncomingCallPresentation,
        val answer: CallAction?,
        val decline: CallAction?,
    )

    private val screenState = MutableStateFlow<ScreenState?>(null)
    private val appIcon = MutableStateFlow<Bitmap?>(null)
    private var iconLoadJob: Job? = null
    private var activeCheckJob: Job? = null
    private var actionSent = false
    private var receiverRegistered = false

    private val finishReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val current = screenState.value?.presentation ?: return
            if (intent.getStringExtra(EXTRA_SOURCE_CLIENT) == current.sourceClientId &&
                intent.getStringExtra(EXTRA_SOURCE_KEY) == current.sourceKey
            ) {
                finishAndRemoveTask()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        ContextCompat.registerReceiver(
            this,
            finishReceiver,
            IntentFilter(ACTION_FINISH),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true

        if (!acceptIntent(intent)) {
            finishAndRemoveTask()
            return
        }
        setContent {
            val state by screenState.collectAsStateWithLifecycle()
            val icon by appIcon.collectAsStateWithLifecycle()
            NotiSyncTheme(darkTheme = true) {
                state?.let { current ->
                    IncomingCallScreen(
                        presentation = current.presentation,
                        appIcon = icon,
                        answerLabel = current.answer?.label,
                        declineLabel = current.decline?.label,
                        onAnswer = current.answer?.let { action -> { perform(action) } },
                        onDecline = current.decline?.let { action -> { perform(action) } },
                        onDismiss = ::finishAndRemoveTask,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        actionSent = false
        if (!acceptIntent(intent)) finishAndRemoveTask()
    }

    override fun onDestroy() {
        iconLoadJob?.cancel()
        activeCheckJob?.cancel()
        if (receiverRegistered) unregisterReceiver(finishReceiver)
        super.onDestroy()
    }

    private fun acceptIntent(intent: Intent): Boolean {
        val sourceClient = intent.getStringExtra(EXTRA_SOURCE_CLIENT) ?: return false
        val sourceKey = intent.getStringExtra(EXTRA_SOURCE_KEY) ?: return false
        val packageName = intent.getStringExtra(EXTRA_PACKAGE) ?: return false
        val appLabel = intent.getStringExtra(EXTRA_APP_LABEL) ?: return false
        val callerName = intent.getStringExtra(EXTRA_CALLER_NAME) ?: return false
        val presentation = IncomingCallPresentation(
            sourceClientId = sourceClient,
            sourceKey = sourceKey,
            packageName = packageName,
            iosBundleId = intent.getStringExtra(EXTRA_IOS_BUNDLE_ID),
            appIconHash = intent.getStringExtra(EXTRA_APP_ICON_HASH),
            appLabel = appLabel,
            callerName = callerName,
            callText = intent.getStringExtra(EXTRA_CALL_TEXT),
            verificationText = intent.getStringExtra(EXTRA_VERIFICATION_TEXT),
            deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME),
        )
        val answerIntent = intent.getParcelableExtra(EXTRA_ANSWER_INTENT, PendingIntent::class.java)
        val declineIntent = intent.getParcelableExtra(EXTRA_DECLINE_INTENT, PendingIntent::class.java)
        screenState.value = ScreenState(
            presentation = presentation,
            answer = answerIntent?.let { CallAction(intent.getStringExtra(EXTRA_ANSWER_LABEL), it) },
            decline = declineIntent?.let { CallAction(intent.getStringExtra(EXTRA_DECLINE_LABEL), it) },
        )
        loadAppIcon(presentation)
        verifyNotificationIsStillActive(presentation)
        return true
    }

    private fun loadAppIcon(presentation: IncomingCallPresentation) {
        appIcon.value = null
        iconLoadJob?.cancel()
        iconLoadJob = lifecycleScope.launch {
            val graph = (application as? NotiSyncApp)?.awaitGraphReady() ?: return@launch
            val resolver = graph.iconResolver ?: return@launch
            val bitmap = withContext(Dispatchers.IO) {
                resolver.colorIcon(
                    presentation.packageName,
                    presentation.iosBundleId,
                    presentation.appIconHash,
                    includeIosGenericFallback = false,
                ) ?: presentation.iosBundleId?.let { bundleId ->
                    // The notification poster may still be fetching a long-tail iOS icon. Resolve it here too so
                    // the first call screen upgrades from its monogram instead of waiting for a future call.
                    resolver.colorIconEnsuringRemote(presentation.packageName, bundleId)
                } ?: resolver.colorIcon(presentation.packageName)
            }
            if (screenState.value?.presentation?.key == presentation.key) appIcon.value = bitmap
        }
    }

    /** Close if the source ended in the narrow gap before this activity registered its finish receiver. */
    private fun verifyNotificationIsStillActive(presentation: IncomingCallPresentation) {
        activeCheckJob?.cancel()
        activeCheckJob = lifecycleScope.launch {
            delay(ACTIVE_NOTIFICATION_CHECK_DELAY_MS)
            val manager = getSystemService(NotificationManager::class.java)
            val active = runCatching {
                manager.activeNotifications.any {
                    it.tag == presentation.key && it.id == presentation.key.hashCode()
                }
            }.getOrDefault(true)
            if (!active && screenState.value?.presentation?.key == presentation.key) finishAndRemoveTask()
        }
    }

    private fun perform(action: CallAction) {
        if (actionSent) return
        actionSent = true
        try {
            action.pendingIntent.send()
            finishAndRemoveTask()
        } catch (_: PendingIntent.CanceledException) {
            actionSent = false
            Toast.makeText(this, R.string.incoming_call_action_failed, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val ACTION_FINISH = "net.extrawdw.apps.notisync.FINISH_INCOMING_CALL"
        private const val ACTIVE_NOTIFICATION_CHECK_DELAY_MS = 250L
        private const val EXTRA_SOURCE_CLIENT = "source_client"
        private const val EXTRA_SOURCE_KEY = "source_key"
        private const val EXTRA_PACKAGE = "package_name"
        private const val EXTRA_IOS_BUNDLE_ID = "ios_bundle_id"
        private const val EXTRA_APP_ICON_HASH = "app_icon_hash"
        private const val EXTRA_APP_LABEL = "app_label"
        private const val EXTRA_CALLER_NAME = "caller_name"
        private const val EXTRA_CALL_TEXT = "call_text"
        private const val EXTRA_VERIFICATION_TEXT = "verification_text"
        private const val EXTRA_DEVICE_NAME = "device_name"
        private const val EXTRA_ANSWER_INTENT = "answer_intent"
        private const val EXTRA_ANSWER_LABEL = "answer_label"
        private const val EXTRA_DECLINE_INTENT = "decline_intent"
        private const val EXTRA_DECLINE_LABEL = "decline_label"

        fun pendingIntent(
            context: Context,
            requestCode: Int,
            notif: CapturedNotification,
            deviceName: String,
            answer: NotificationAction?,
            answerIntent: PendingIntent?,
            decline: NotificationAction?,
            declineIntent: PendingIntent?,
            showPrivateDetails: Boolean,
        ): PendingIntent {
            val original = notif.incomingCallPresentation(deviceName)
            val presentation = if (showPrivateDetails) original else original.copy(
                packageName = context.packageName,
                iosBundleId = null,
                appIconHash = null,
                appLabel = context.getString(R.string.app_name),
                callerName = context.getString(R.string.incoming_call_unknown_caller),
                callText = null,
                verificationText = null,
                deviceName = null,
            )
            val launch = Intent(context, IncomingCallActivity::class.java).apply {
                action = ACTION_SHOW
                data = "notisync://incoming-call/${Uri.encode(original.key)}".toUri()
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
                putExtra(EXTRA_SOURCE_CLIENT, presentation.sourceClientId)
                putExtra(EXTRA_SOURCE_KEY, presentation.sourceKey)
                putExtra(EXTRA_PACKAGE, presentation.packageName)
                putExtra(EXTRA_IOS_BUNDLE_ID, presentation.iosBundleId)
                putExtra(EXTRA_APP_ICON_HASH, presentation.appIconHash)
                putExtra(EXTRA_APP_LABEL, presentation.appLabel)
                putExtra(EXTRA_CALLER_NAME, presentation.callerName)
                putExtra(EXTRA_CALL_TEXT, presentation.callText)
                putExtra(EXTRA_VERIFICATION_TEXT, presentation.verificationText)
                putExtra(EXTRA_DEVICE_NAME, presentation.deviceName)
                if (answerIntent != null) putExtra(EXTRA_ANSWER_INTENT, answerIntent)
                if (declineIntent != null) putExtra(EXTRA_DECLINE_INTENT, declineIntent)
                putExtra(EXTRA_ANSWER_LABEL, answer?.title)
                putExtra(EXTRA_DECLINE_LABEL, decline?.title)
            }
            return PendingIntent.getActivity(
                context,
                requestCode,
                launch,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        fun requestFinish(context: Context, sourceClientId: String, sourceKey: String) {
            context.sendBroadcast(
                Intent(ACTION_FINISH).setPackage(context.packageName)
                    .putExtra(EXTRA_SOURCE_CLIENT, sourceClientId)
                    .putExtra(EXTRA_SOURCE_KEY, sourceKey)
            )
        }

        private const val ACTION_SHOW = "net.extrawdw.apps.notisync.SHOW_INCOMING_CALL"
    }
}

@Composable
internal fun IncomingCallScreen(
    presentation: IncomingCallPresentation,
    appIcon: Bitmap?,
    answerLabel: String?,
    declineLabel: String?,
    onAnswer: (() -> Unit)?,
    onDecline: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val background = Brush.verticalGradient(
        listOf(
            MaterialTheme.colorScheme.surface,
            MaterialTheme.colorScheme.surfaceContainerLowest,
        )
    )
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        BoxWithConstraints(
            Modifier.fillMaxSize().background(background).safeDrawingPadding()
        ) {
            val contentMinHeight = maxHeight
            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .widthIn(max = 560.dp)
                        .heightIn(min = contentMinHeight)
                        .padding(horizontal = 28.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceEvenly,
                ) {
                    AppIdentity(presentation.appLabel, appIcon)
                    CallerIdentity(presentation)
                    presentation.deviceName?.let { DeviceIdentity(it) }
                    CallControls(
                        answerLabel = answerLabel,
                        declineLabel = declineLabel,
                        onAnswer = onAnswer,
                        onDecline = onDecline,
                        onDismiss = onDismiss,
                    )
                }
            }
        }
    }
}

@Composable
private fun AppIdentity(appLabel: String, appIcon: Bitmap?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val shape = RoundedCornerShape(22.dp)
        if (appIcon != null) {
            Image(
                bitmap = appIcon.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(88.dp).clip(shape),
            )
        } else {
            Box(
                Modifier.size(88.dp).clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = appLabel.trim().firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = appLabel,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CallerIdentity(presentation: IncomingCallPresentation) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = androidx.compose.ui.res.stringResource(R.string.incoming_call_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = presentation.callerName,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        presentation.callText?.let {
            Spacer(Modifier.height(10.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        presentation.verificationText?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.tertiary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DeviceIdentity(deviceName: String) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Smartphone, contentDescription = null)
            Text(
                text = androidx.compose.ui.res.stringResource(
                    R.string.incoming_call_on_device,
                    deviceName,
                ),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CallControls(
    answerLabel: String?,
    declineLabel: String?,
    onAnswer: (() -> Unit)?,
    onDecline: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Top,
    ) {
        if (onDecline != null) {
            CallActionButton(
                label = declineLabel?.takeIf(String::isNotBlank)
                    ?: androidx.compose.ui.res.stringResource(R.string.incoming_call_decline),
                icon = Icons.Filled.CallEnd,
                containerColor = Color(0xFFB3261E),
                onClick = onDecline,
            )
        } else {
            CallActionButton(
                label = androidx.compose.ui.res.stringResource(R.string.incoming_call_dismiss),
                icon = Icons.Outlined.Close,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                onClick = onDismiss,
            )
        }
        if (onAnswer != null) {
            CallActionButton(
                label = answerLabel?.takeIf(String::isNotBlank)
                    ?: androidx.compose.ui.res.stringResource(R.string.incoming_call_answer),
                icon = Icons.Filled.Call,
                containerColor = Color(0xFF287D3C),
                onClick = onAnswer,
            )
        }
    }
}

@Composable
private fun CallActionButton(
    label: String,
    icon: ImageVector,
    containerColor: Color,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier.widthIn(min = 96.dp, max = 136.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(72.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = containerColor,
                contentColor = Color.White,
            ),
            shape = CircleShape,
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

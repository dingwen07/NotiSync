package net.extrawdw.apps.notisync.notification

import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.PowerManager

/**
 * Supplies the BAL opt-in needed by request-page PendingIntents on newer Android versions.
 * The request setting is an explicit user choice, so the more permissive mode is intentional here:
 * this is a trusted companion-device request, not an arbitrary background launch.
 */
internal fun requestPagePendingIntentOptions(): Bundle =
    ActivityOptions.makeBasic()
        .setPendingIntentCreatorBackgroundActivityStartMode(backgroundActivityStartMode())
        .toBundle()

/**
 * Attempts to open a newly received request while the user is already using the phone.
 * Returns false for the lock screen/screen-off state so the notification's full-screen intent
 * remains the platform-native launch path there. A failed attempt is deliberately non-fatal: the
 * request notification has already been posted and remains available for a user tap.
 */
internal fun tryOpenRequestPageWhileUnlocked(
    context: Context,
    pendingIntent: PendingIntent,
): Boolean {
    val powerManager = context.getSystemService(PowerManager::class.java) ?: return false
    if (!powerManager.isInteractive) return false
    if (context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true) return false

    return runCatching {
        val options = ActivityOptions.makeBasic()
            .setPendingIntentBackgroundActivityStartMode(backgroundActivityStartMode())
            .toBundle()
        pendingIntent.send(context, 0, null, null, null, null, options)
        true
    }.getOrDefault(false)
}

@Suppress("DEPRECATION")
private fun backgroundActivityStartMode(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
    } else {
        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
    }

package net.extrawdw.apps.notisync.navigation

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutManager
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import net.extrawdw.apps.notisync.MainActivity

internal object AppMenuShortcuts {
    private const val PREFIX = "menu:"
    private const val ACTION = "net.extrawdw.apps.notisync.OPEN_MENU_DESTINATION"
    private const val EXTRA_DESTINATION = "net.extrawdw.apps.notisync.MENU_DESTINATION"
    private var homePackage: String? = null
    private var homePackageResolved = false

    // Leave room for publish -> notify -> unpublish of a conversation without evicting a menu action.
    fun limit(context: Context): Int =
        (context.getSystemService(ShortcutManager::class.java).maxShortcutCountPerActivity - 1).coerceIn(0, 4)

    fun consumeDestination(intent: Intent?): MenuDestination? {
        if (intent?.action != ACTION || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
        val destination = MenuDestination.fromId(intent.getStringExtra(EXTRA_DESTINATION))
        intent.removeExtra(EXTRA_DESTINATION)
        return destination
    }

    /** Caller runs off-main. Touch only menu IDs; notification conversation shortcuts have their own lifecycle. */
    @Synchronized
    fun update(context: Context, configuration: MenuConfiguration) {
        // The first startup update resolves Home once for this process, including a null result.
        if (!homePackageResolved) {
            homePackage = context.packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY,
            )?.activityInfo?.packageName
            homePackageResolved = true
        }
        val destinations = configuration.destinations()
            .filter { it.id in configuration.shortcuts }
            .take(limit(context))
        val ids = destinations.map { PREFIX + it.id }.toSet()
        val removed = ShortcutManagerCompat.getDynamicShortcuts(context)
            .map { it.id }.filter { it.startsWith(PREFIX) && it !in ids }
        if (removed.isNotEmpty()) ShortcutManagerCompat.removeDynamicShortcuts(context, removed)
        val shortcuts = destinations.mapIndexed { rank, destination ->
            ShortcutInfoCompat.Builder(context, PREFIX + destination.id)
                .setShortLabel(context.getString(destination.label))
                .setIcon(IconCompat.createWithResource(context, destination.shortcutIcon(homePackage)))
                .setRank(rank)
                .setIntent(Intent(context, MainActivity::class.java)
                    .setAction(ACTION)
                    .putExtra(EXTRA_DESTINATION, destination.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                .build()
        }
        if (shortcuts.isNotEmpty()) check(ShortcutManagerCompat.addDynamicShortcuts(context, shortcuts)) {
            "Launcher shortcut update was rate-limited"
        }
    }
}

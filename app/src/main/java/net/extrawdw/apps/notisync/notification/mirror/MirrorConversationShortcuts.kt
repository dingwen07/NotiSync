package net.extrawdw.apps.notisync.notification.mirror

import android.content.Context
import androidx.core.content.pm.ShortcutManagerCompat

/** Retire old IDs entirely; hide current shortcuts left by interrupted posts without deleting their cache. */
internal fun cleanMirroredConversationShortcuts(context: Context) {
    val shortcuts = ShortcutManagerCompat.getShortcuts(context,
        ShortcutManagerCompat.FLAG_MATCH_DYNAMIC or ShortcutManagerCompat.FLAG_MATCH_CACHED or
            ShortcutManagerCompat.FLAG_MATCH_PINNED)
    val legacyIds = shortcuts.map { it.id }.filter { it.startsWith("noticonv:") }
    if (legacyIds.isNotEmpty()) ShortcutManagerCompat.removeLongLivedShortcuts(context, legacyIds)

    val dynamicIds = shortcuts.filter { it.isDynamic && it.id.startsWith("conversation:") }.map { it.id }
    if (dynamicIds.isNotEmpty()) ShortcutManagerCompat.removeDynamicShortcuts(context, dynamicIds)
}

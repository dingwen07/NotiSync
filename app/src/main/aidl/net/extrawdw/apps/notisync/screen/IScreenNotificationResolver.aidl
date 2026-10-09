package net.extrawdw.apps.notisync.screen;

import android.app.PendingIntent;

/** App-local callback bound to one owner and notification key. Never received from the network. */
interface IScreenNotificationResolver {
    PendingIntent resolve(String ownerToken);
    /** Called only after sending the resolved activity PendingIntent succeeds. */
    void onSent(String ownerToken);
}

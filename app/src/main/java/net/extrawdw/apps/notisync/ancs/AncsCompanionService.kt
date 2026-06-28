package net.extrawdw.apps.notisync.ancs

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.util.Log
import net.extrawdw.apps.notisync.NotiSyncApp

/**
 * Optional background-reliability layer. Once the user creates a CompanionDeviceManager association for their
 * iPhone (from the iOS tab) and presence observation is started, the OS calls [onDeviceAppeared] when the
 * iPhone comes into BLE range — we (re)start the bridge service then, which helps it recover after the
 * process is killed. The foreground-service path works without this; it's purely additive.
 *
 * Starting a foreground service from this background callback requires the
 * `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND` permission (declared in the manifest);
 * the start is still guarded so any residual denial logs rather than crashing this system-bound service.
 */
@Suppress("OVERRIDE_DEPRECATION") // the AssociationInfo overloads are the correct ones to implement on minSdk 34
class AncsCompanionService : CompanionDeviceService() {
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        // Honor the master switch: don't resurrect the bridge on presence if the user turned it off (but kept
        // the association). Reliable while warm; on a cold start spawned by this callback, AppGraph.init() also
        // runs resumeAncsBridgeIfEnabled() (a persisted read) to cover the not-yet-loaded window. The start is
        // synchronous so it stays inside the companion-presence foreground-service exemption.
        if (!AncsBridgeService.hasPermissions(applicationContext)) return
        val graph = (application as? NotiSyncApp)?.graphIfReady ?: return
        if (graph.settings.ancsBridgeEnabled.value != true) return
        runCatching { AncsBridgeService.start(applicationContext) }
            .onFailure {
                Log.w(
                    "AncsCompanionService",
                    "onDeviceAppeared: bridge start denied",
                    it
                )
            }
    }

    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        // Keep advertising while the master switch is on (the FGS self-idles in ADVERTISING); the tab's
        // toggle owns teardown, so we deliberately don't stop the bridge just because the iPhone roamed away.
    }
}

package net.extrawdw.apps.notisync.pairing

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import android.os.Build
import android.os.UserManager
import android.util.Log
import net.extrawdw.apps.notisync.nfc.ForegroundNdefSession
import net.extrawdw.apps.notisync.nfc.NotiSyncHostApduService
import net.extrawdw.apps.notisync.nfc.NotiSyncNfcProtocol

/** Foreground pairing routing plus the always-on NotiSync HCE rendezvous configuration. */
internal object PairingNfcController {
    private const val NDEF_TAG_APPLICATION_AID = "D2760000850101"

    fun enableForegroundNdef(context: Context, pairingUrl: String) {
        val cardEmulation = cardEmulation(context) ?: return ForegroundNdefSession.clear()
        val component = serviceComponent(context)
        if (!ForegroundNdefSession.setUrl(pairingUrl, context.applicationContext.packageName)) {
            return disableForegroundNdef(context)
        }
        // Dynamic registration replaces the static category group. Include the NotiSync AID so custom
        // operations remain routable while NDEF pairing compatibility is temporarily added.
        val registered = runCatching {
            cardEmulation.registerAidsForService(
                component,
                CardEmulation.CATEGORY_OTHER,
                listOf(NotiSyncNfcProtocol.APPLICATION_AID_HEX, NDEF_TAG_APPLICATION_AID),
            )
        }.getOrDefault(false)
        if (!registered) return disableForegroundNdef(context)
        context.findActivity()?.let { activity ->
            runCatching { cardEmulation.setPreferredService(activity, component) }
        }
    }

    /** Keep the NotiSync HCE service preferred while withholding the foreground-only NDEF application. */
    fun enableForegroundCustomAidOnly(context: Context) {
        ForegroundNdefSession.clear()
        val cardEmulation = cardEmulation(context) ?: return
        val component = serviceComponent(context)
        val registered = runCatching {
            cardEmulation.registerAidsForService(
                component,
                CardEmulation.CATEGORY_OTHER,
                listOf(NotiSyncNfcProtocol.APPLICATION_AID_HEX),
            )
        }.getOrDefault(false)
        if (!registered) return disableForegroundNdef(context)
        context.findActivity()?.let { activity ->
            runCatching { cardEmulation.setPreferredService(activity, component) }
        }
    }

    /** Remove the dynamic group so Android restores the manifest's always-on NotiSync AID. */
    fun disableForegroundNdef(context: Context) {
        ForegroundNdefSession.clear()
        val cardEmulation = cardEmulation(context) ?: return
        context.findActivity()?.let { activity ->
            runCatching { cardEmulation.unsetPreferredService(activity) }
        }
        runCatching {
            cardEmulation.removeAidsForService(serviceComponent(context), CardEmulation.CATEGORY_OTHER)
        }
    }

    /** Repairs foreground-only routing and installs the pairing Observe Mode rendezvous filter. */
    fun restoreBackgroundRouting(context: Context) {
        val appContext = context.applicationContext
        disableForegroundNdef(appContext)
        registerPollingLoopFilter(appContext)
    }

    private fun registerPollingLoopFilter(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return

        // Android only auto-transacts when this service is preferred or no other service owns the same
        // filter. Installing NotiSync in both the primary user and a work/private profile would otherwise
        // make the shared marker ambiguous. The NotiSync AID remains registered in every profile; only the
        // primary profile owns the automatic background pairing rendezvous.
        val isProfile = runCatching {
            context.getSystemService(UserManager::class.java).isProfile
        }.getOrDefault(false)
        if (isProfile) return

        val cardEmulation = cardEmulation(context) ?: return
        val registered = runCatching {
            cardEmulation.registerPollingLoopFilterForService(
                serviceComponent(context),
                PairingNfcPolling.FILTER_HEX,
                true,
            )
        }.getOrDefault(false)
        if (!registered) Log.w(TAG, "Could not register the NFC pairing polling-loop filter")
    }

    private fun serviceComponent(context: Context) =
        ComponentName(context.applicationContext, NotiSyncHostApduService::class.java)

    private fun cardEmulation(context: Context): CardEmulation? {
        val appContext = context.applicationContext
        if (!appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)) {
            return null
        }
        val adapter = NfcAdapter.getDefaultAdapter(appContext) ?: return null
        return runCatching { CardEmulation.getInstance(adapter) }.getOrNull()
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

    private const val TAG = "PairingNfcController"
}

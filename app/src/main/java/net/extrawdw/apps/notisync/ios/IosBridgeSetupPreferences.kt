package net.extrawdw.apps.notisync.ios

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

internal class IosBridgeSetupPreferences(private val prefs: SharedPreferences, private val listed: Boolean) {
    val usesApp: Boolean get() = prefs.getBoolean(APP_MODE, false) ||
        (listed && !prefs.getBoolean(LISTED_NATIVE_CONFIRMED, false))
    val warnBeforeNative: Boolean get() = listed || prefs.getBoolean(REPEATED_LOSS, false)

    val appModeChanges = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == APP_MODE || key == LISTED_NATIVE_CONFIRMED) trySend(usesApp)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(usesApp)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    fun selectMode(useApp: Boolean) {
        prefs.edit {
            putBoolean(APP_MODE, useApp)
            if (!useApp) remove(REPEATED_LOSS)
            if (!useApp && listed) putBoolean(LISTED_NATIVE_CONFIRMED, true) else remove(LISTED_NATIVE_CONFIRMED)
        }
    }

    fun recordRepeatedLoss() {
        prefs.edit {
            putBoolean(REPEATED_LOSS, true)
            putBoolean(APP_MODE, true)
            remove(LISTED_NATIVE_CONFIRMED)
        }
    }

    private companion object {
        const val APP_MODE = "app_advertising"
        const val LISTED_NATIVE_CONFIRMED = "listed_native_confirmed"
        const val REPEATED_LOSS = "repeated_advertising_loss"
    }
}

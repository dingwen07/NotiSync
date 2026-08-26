package net.extrawdw.apps.notisync.ios

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/** Runtime permissions required by the iOS bridge itself.
 *
 * CompanionDeviceManager performs device discovery on the app's behalf, so the bridge does not request
 * [Manifest.permission.BLUETOOTH_SCAN]. It does need to advertise as a BLE peripheral and communicate with
 * the selected iPhone.
 */
internal object IosBluetoothPermissions {
    private val required = listOf(
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_ADVERTISE,
    )

    /** A fresh array for Activity Result permission launchers, so callers cannot mutate shared state. */
    fun requestPermissions(): Array<String> = required.toTypedArray()

    fun hasRequired(context: Context): Boolean = required.all { has(context, it) }

    fun canConnect(context: Context): Boolean = has(context, Manifest.permission.BLUETOOTH_CONNECT)

    private fun has(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

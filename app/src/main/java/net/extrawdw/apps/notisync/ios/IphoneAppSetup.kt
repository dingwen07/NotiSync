package net.extrawdw.apps.notisync.ios

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.le.AdvertiseData
import android.os.ParcelUuid
import java.util.UUID

/** Discovery service for an iPhone that explicitly connects using Core Bluetooth's requiresANCS option. */
internal object IphoneAppSetup {
    // Shared with AndroidBridgeSetup.swift. The read requires link encryption and contains no user data.
    val serviceId: UUID = UUID.fromString("d87b3910-72fa-48df-8ab7-6ae96fc3fb51")
    val versionId: UUID = UUID.fromString("d87b3911-72fa-48df-8ab7-6ae96fc3fb51")

    private val affectedManufacturers = setOf("xiaomi", "redmi", "poco", "vivo", "iqoo")

    fun prefersAppSetup(sdk: Int, manufacturer: String, socManufacturer: String): Boolean {
        if (sdk != 37 || manufacturer.lowercase() !in affectedManufacturers) return false
        // These OEMs ship the affected Qualcomm Bluetooth stack on some builds.
        val vendor = socManufacturer.trim().lowercase()
        return vendor == "qti" || vendor == "qcom" || vendor.startsWith("qualcomm")
    }

    fun advertisement(appSetup: Boolean): AdvertiseData = AdvertiseData.Builder().apply {
        if (appSetup) addServiceUuid(ParcelUuid(serviceId))
        else addServiceSolicitationUuid(ParcelUuid(Ancs.SERVICE_UUID))
    }.build()

    fun service() = BluetoothGattService(serviceId, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
        addCharacteristic(BluetoothGattCharacteristic(versionId, BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED))
    }
}

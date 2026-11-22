package net.extrawdw.apps.notisync.ios

/** The one Bluetooth endpoint selected for the bridge. Older CDM associations used Classic inquiry, so the
 * user can select its separately bonded BLE endpoint while retaining the association for presence. */
internal data class IosPeerSelection(val address: String, val associationId: Int? = null) {
    fun <T> findBonded(
        devices: Iterable<T>,
        addressOf: (T) -> String,
        isBonded: (T) -> Boolean,
    ): T? = devices.firstOrNull { address.equals(addressOf(it), ignoreCase = true) && isBonded(it) }

    companion object {
        fun resolve(
            saved: IosPeerSelection?,
            hasExplicitSelection: Boolean,
            associations: List<IosPeerSelection>,
        ): IosPeerSelection? {
            // Existing installations selected an iPhone through CDM before the app stored its address.
            if (!hasExplicitSelection) return associations.singleOrNull()
            if (saved?.associationId == null) return saved
            return saved.takeIf { associations.any { it.associationId == saved.associationId } }
        }
    }
}

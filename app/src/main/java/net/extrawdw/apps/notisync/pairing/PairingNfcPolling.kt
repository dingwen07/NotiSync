package net.extrawdw.apps.notisync.pairing

/**
 * Short polling-loop marker emitted before ISO-DEP activation on supported readers.
 *
 * This is only a rendezvous signal for Android Observe Mode. Pairing cards still travel through the
 * bidirectional, chunked APDU protocol after the custom application AID is selected.
 */
internal object PairingNfcPolling {
    const val FILTER_HEX = "4E5350616972" // ASCII "NSPair"

    private val annotation = byteArrayOf(
        0x4E,
        0x53,
        0x50,
        0x61,
        0x69,
        0x72,
    )

    fun annotationBytes(): ByteArray = annotation.copyOf()
}

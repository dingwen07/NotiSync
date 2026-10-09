package net.extrawdw.notisync.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.CborLabel

/** Session-scoped, non-secure Android display. Resolution is independent of the encoder limit. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ScreenVirtualDisplay(
    @CborLabel(0) val width: Int = 1080,
    @CborLabel(1) val height: Int = 1920,
    @CborLabel(2) val densityDpi: Int = 320,
    /** Open registry, so unknown launch kinds fail validation instead of changing destinations. */
    // Keep the nested map nonempty even for default Home settings; empty nullable CBOR maps collapse to null.
    @CborLabel(3) @EncodeDefault(EncodeDefault.Mode.ALWAYS) val launchKind: String = HOME,
    @CborLabel(4) val packageName: String? = null,
    @CborLabel(5) val notificationKey: String? = null,
) {
    fun isValid(): Boolean = width in 240..4096 && height in 240..4096 &&
        width.toLong() * height <= 8_388_608L && densityDpi in 120..640 && when (launchKind) {
            HOME -> packageName == null && notificationKey == null
            APP -> packageName != null && packageName.length <= 255 &&
                PACKAGE.matches(packageName) && notificationKey == null
            NOTIFICATION -> packageName == null && !notificationKey.isNullOrBlank() &&
                notificationKey.length <= 1024 && notificationKey.none(Char::isISOControl)
            else -> false
        }

    companion object {
        const val PROTOCOL_VERSION = 2
        const val HOME = "HOME"
        const val APP = "APP"
        const val NOTIFICATION = "NOTIFICATION"
        private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    }
}

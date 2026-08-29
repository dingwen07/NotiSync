package net.extrawdw.apps.notisync.sshkeyprovider

/** Owns one mutable secret buffer and wipes it when ownership ends. */
internal class SensitiveBytes private constructor(
    private var value: ByteArray?,
) : AutoCloseable {
    val bytes: ByteArray
        get() = checkNotNull(value) { "sensitive bytes are closed" }

    fun copy(): SensitiveBytes = takeCopyOf(bytes)

    /** Transfers ownership to the caller. The caller must wipe the returned array. */
    fun take(): ByteArray {
        val owned = bytes
        value = null
        return owned
    }

    override fun close() {
        value?.fill(0)
        value = null
    }

    companion object {
        fun takeOwnership(value: ByteArray): SensitiveBytes = SensitiveBytes(value)
        fun takeCopyOf(value: ByteArray): SensitiveBytes = SensitiveBytes(value.copyOf())
    }
}

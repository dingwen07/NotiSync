package net.extrawdw.notisync.desktop

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer

/** Best-effort native process naming; failure must never prevent the peer from starting. */
object DesktopProcessTitle {
    fun set(name: String) {
        require(name.isNotBlank() && name.encodeToByteArray().size < LINUX_COMM_BYTES)
        if (Platform.isLinux()) runCatching { setLinuxName(name) }
    }

    private fun setLinuxName(name: String) {
        val bytes = name.encodeToByteArray()
        Memory(LINUX_COMM_BYTES.toLong()).use { memory ->
            memory.clear()
            memory.write(0, bytes, 0, bytes.size)
            check(linux.prctl(PR_SET_NAME, memory, Pointer.NULL, Pointer.NULL, Pointer.NULL) == 0)
        }
    }

    private interface LinuxLibC : Library {
        fun prctl(option: Int, argument: Pointer, third: Pointer?, fourth: Pointer?, fifth: Pointer?): Int
    }

    private val linux: LinuxLibC by lazy { Native.load(Platform.C_LIBRARY_NAME, LinuxLibC::class.java) }

    private const val PR_SET_NAME = 15
    private const val LINUX_COMM_BYTES = 16
}

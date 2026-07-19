package net.extrawdw.notisync.run.process

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Platform

internal object TerminalFileDescriptors {
    private interface PosixLibC : Library {
        fun isatty(fileDescriptor: Int): Int
    }

    private val libc: PosixLibC? by lazy {
        if (Platform.isWindows()) null
        else runCatching { Native.load(Platform.C_LIBRARY_NAME, PosixLibC::class.java) }.getOrNull()
    }

    fun isTerminal(fileDescriptor: Int): Boolean =
        libc?.let { runCatching { it.isatty(fileDescriptor) == 1 }.getOrDefault(false) }
            ?: (System.console() != null)
}

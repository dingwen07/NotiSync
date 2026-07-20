package net.extrawdw.notisync.daemon

import com.sun.jna.LastErrorException
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Platform

/** POSIX session handling for a daemon launched through a JVM or generated shell script. */
internal object DesktopProcessSession {
    const val DETACH_ENVIRONMENT = "NOTISYNC_INTERNAL_DETACH_SESSION"
    const val DETACH_VALUE = "1"
    const val DETACH_PROPERTY = "notisync.internal.detachSession"

    fun requestNativeDetach(processBuilder: ProcessBuilder) {
        processBuilder.environment()[DETACH_ENVIRONMENT] = DETACH_VALUE
    }

    fun detachIfRequested() {
        val request = System.getProperty(DETACH_PROPERTY) ?: return
        require(request == DETACH_VALUE) { "invalid internal detach-session request" }
        System.clearProperty(DETACH_PROPERTY)
        check(Platform.isLinux() || Platform.isMac()) {
            "detached daemon sessions are supported only on Linux and macOS"
        }
        try {
            check(posix.setsid() > 0) { "setsid returned an invalid session ID" }
        } catch (failure: LastErrorException) {
            throw IllegalStateException(
                "could not create a detached daemon session (errno ${failure.errorCode})",
                failure,
            )
        }
    }

    private interface PosixLibC : Library {
        @Throws(LastErrorException::class)
        fun setsid(): Int
    }

    private val posix: PosixLibC by lazy {
        Native.load(Platform.C_LIBRARY_NAME, PosixLibC::class.java)
    }
}

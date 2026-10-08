package net.extrawdw.notisync.desktop

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.mac.SystemB
import com.sun.jna.platform.unix.LibCAPI.size_t
import com.sun.jna.platform.win32.Advapi32
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.nio.file.Files
import java.nio.file.Path
import net.extrawdw.notisync.protocol.DesktopWindowsElevationType
import net.extrawdw.notisync.protocol.DesktopWindowsTokenInfo

data class DesktopProcessOwner(
    val username: String? = null,
    val uid: Long? = null,
    val sid: String? = null,
    val windowsTokenInfo: DesktopWindowsTokenInfo? = null,
)

/** Optional effective-user metadata. Permission failures never prevent collecting a process node. */
class DesktopProcessOwnerResolver(
    osName: String = System.getProperty("os.name"),
    private val procRoot: Path = Path.of("/proc"),
    private val posixUsername: (Long) -> String? = PosixAccounts::username,
    private val macUid: (Long) -> Long? = MacOwner::uid,
    private val windowsOwner: (Long) -> DesktopProcessOwner? = WindowsOwner::resolve,
) {
    private val osName = osName.lowercase()

    fun resolve(pid: Long): DesktopProcessOwner? {
        if (pid <= 0) return null
        return runCatching {
            when {
                osName.contains("windows") -> windowsOwner(pid)
                osName.contains("mac") -> macUid(pid)?.let(::posixOwner)
                osName.contains("linux") -> Files.readString(procRoot.resolve("$pid/status"))
                    .lineSequence().firstOrNull { it.startsWith("Uid:") }
                    ?.substringAfter(':')?.trim()?.split(Regex("\\s+"))?.getOrNull(1)
                    ?.toLongOrNull()?.takeIf { it in 0..0xffff_ffffL }?.let(::posixOwner)
                else -> null
            }
        }.getOrNull()
    }

    private fun posixOwner(uid: Long) = DesktopProcessOwner(
        // A missing account name must not discard the numeric identity.
        username = runCatching { posixUsername(uid) }.getOrNull(), uid = uid,
    )

    private object MacOwner {
        // proc_info.h: unlike PROC_PIDTBSDINFO, this flavor permits cross-user inspection.
        private const val PROC_PIDT_SHORTBSDINFO = 13

        @Structure.FieldOrder(
            "pid", "ppid", "pgid", "status", "comm", "flags", "uid", "gid", "ruid", "rgid", "svuid", "svgid", "reserved",
        )
        class ProcBsdShortInfo : Structure() {
            @JvmField var pid: Int = 0
            @JvmField var ppid: Int = 0
            @JvmField var pgid: Int = 0
            @JvmField var status: Int = 0
            @JvmField var comm = ByteArray(16)
            @JvmField var flags: Int = 0
            @JvmField var uid: Int = 0
            @JvmField var gid: Int = 0
            @JvmField var ruid: Int = 0
            @JvmField var rgid: Int = 0
            @JvmField var svuid: Int = 0
            @JvmField var svgid: Int = 0
            @JvmField var reserved: Int = 0
        }

        fun uid(pid: Long): Long? {
            if (pid > Int.MAX_VALUE) return null
            val info = ProcBsdShortInfo()
            val size = SystemB.INSTANCE.proc_pidinfo(pid.toInt(), PROC_PIDT_SHORTBSDINFO, 0, info, info.size())
            return if (size == info.size() && info.pid.toLong() == pid) {
                Integer.toUnsignedLong(info.uid)
            } else null
        }
    }

    private object PosixAccounts {
        private interface Accounts : Library {
            fun getpwuid_r(uid: Int, passwd: Pointer, buffer: Pointer, size: size_t, result: PointerByReference): Int
        }

        // Linux struct passwd; pointer fields stay native and only pw_name is read below.
        @Structure.FieldOrder("name", "password", "uid", "gid", "gecos", "directory", "shell")
        class LinuxPasswd : Structure() {
            @JvmField var name: Pointer? = null
            @JvmField var password: Pointer? = null
            @JvmField var uid: Int = 0
            @JvmField var gid: Int = 0
            @JvmField var gecos: Pointer? = null
            @JvmField var directory: Pointer? = null
            @JvmField var shell: Pointer? = null
        }

        private val accounts: Accounts by lazy { Native.load(Platform.C_LIBRARY_NAME, Accounts::class.java) }

        fun username(uid: Long): String? {
            val passwd = if (Platform.isMac()) SystemB.Passwd() else LinuxPasswd()
            Memory(16_384).use { buffer ->
                val result = PointerByReference()
                if (accounts.getpwuid_r(uid.toInt(), passwd.pointer, buffer, size_t(buffer.size()), result) != 0 ||
                    result.value == null
                ) return null
                return passwd.pointer.getPointer(0)?.getString(0)?.takeIf(String::isNotBlank)
            }
        }
    }

    private object WindowsOwner {
        fun resolve(pid: Long): DesktopProcessOwner? {
            if (pid > Int.MAX_VALUE) return null
            val process = Kernel32.INSTANCE.OpenProcess(WinNT.PROCESS_QUERY_LIMITED_INFORMATION, false, pid.toInt())
                ?: return null
            try {
                val token = WinNT.HANDLEByReference()
                if (!Advapi32.INSTANCE.OpenProcessToken(process, WinNT.TOKEN_QUERY, token)) return null
                try {
                    val owner = runCatching {
                        readSid(token.value, WinNT.TOKEN_INFORMATION_CLASS.TokenUser) { sid ->
                            DesktopProcessOwner(
                                username = runCatching { Advapi32Util.getAccountBySid(sid).fqn }.getOrNull(),
                                sid = Advapi32Util.convertSidToStringSid(sid),
                            )
                        }
                    }.getOrNull() ?: DesktopProcessOwner()
                    val details = DesktopWindowsTokenInfo(
                        elevated = readDword(token.value, WinNT.TOKEN_INFORMATION_CLASS.TokenElevation)?.let { it != 0 },
                        elevationType = when (readDword(token.value, WinNT.TOKEN_INFORMATION_CLASS.TokenElevationType)) {
                            1 -> DesktopWindowsElevationType.DEFAULT
                            2 -> DesktopWindowsElevationType.FULL
                            3 -> DesktopWindowsElevationType.LIMITED
                            else -> null
                        },
                        integrityLevel = runCatching {
                            readSid(token.value, WinNT.TOKEN_INFORMATION_CLASS.TokenIntegrityLevel) { sid ->
                                Advapi32Util.convertSidToStringSid(sid).takeIf { it.startsWith("S-1-16-") }
                                    ?.substringAfterLast('-')?.toLongOrNull()
                            }
                        }.getOrNull(),
                        appContainer = readDword(token.value, TOKEN_IS_APP_CONTAINER)?.let { it != 0 },
                    )
                    return owner.copy(windowsTokenInfo = details.takeIf { it.validationError() == null })
                } finally {
                    Kernel32.INSTANCE.CloseHandle(token.value)
                }
            } finally {
                Kernel32.INSTANCE.CloseHandle(process)
            }
        }

        private fun readDword(token: WinNT.HANDLE, informationClass: Int): Int? = runCatching {
            val info = TokenDword()
            if (Advapi32.INSTANCE.GetTokenInformation(token, informationClass, info, info.size(), IntByReference())) {
                info.value
            } else null
        }.getOrNull()

        private fun <T> readSid(token: WinNT.HANDLE, informationClass: Int, block: (WinNT.PSID) -> T): T? {
            val length = IntByReference()
            Advapi32.INSTANCE.GetTokenInformation(token, informationClass, null, 0, length)
            if (length.value !in 1..65_536) return null
            // TOKEN_USER and TOKEN_MANDATORY_LABEL both contain one SID_AND_ATTRIBUTES plus the SID bytes.
            Memory(length.value.toLong()).use { buffer ->
                val info = TokenSid(buffer)
                if (!Advapi32.INSTANCE.GetTokenInformation(token, informationClass, info, length.value, length)) return null
                return block(info.label.Sid)
            }
        }

        @Structure.FieldOrder("value")
        class TokenDword : Structure() { @JvmField var value: Int = 0 }

        @Structure.FieldOrder("label")
        class TokenSid(pointer: Pointer) : Structure(pointer) {
            @JvmField var label = WinNT.SID_AND_ATTRIBUTES()
        }

        // TOKEN_INFORMATION_CLASS value from winnt.h; not declared by JNA's current WinNT mapping.
        private const val TOKEN_IS_APP_CONTAINER = 29
    }
}

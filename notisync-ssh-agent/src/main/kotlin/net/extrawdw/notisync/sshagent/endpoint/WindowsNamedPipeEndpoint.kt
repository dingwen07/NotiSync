package net.extrawdw.notisync.sshagent.endpoint

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class NamedPipeConflictException(
    val pipeName: String,
    val win32Error: Int,
    message: String,
) : IOException(message)

/** Owner-only, local byte-mode Win32 named-pipe SSH agent endpoint. */
class WindowsNamedPipeEndpoint(
    private val pipeName: String,
    private val handler: (InputStream, OutputStream, Long) -> Unit,
    private val maximumConnections: Int,
) : AgentEndpoint {
    private val closed = AtomicBoolean(false)
    private val pending = AtomicReference<Pointer?>()
    private val active = ConcurrentHashMap.newKeySet<Pointer>()
    private val clients = Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name("notisync-ssh-agent-pipe-", 0).factory(),
    )

    init {
        require(isWindowsNamedPipeAddress(pipeName)) { "Windows SSH Agent endpoint must be a local named pipe" }
        require(maximumConnections > 0) { "maximumConnections must be positive" }
    }

    override fun run(onReady: () -> Unit) {
        check(isWindows())
        check(!closed.get())
        PipeSecurity().use { security ->
            var firstInstance = true
            var ready = false
            while (!closed.get()) {
                val pipe = KERNEL.CreateNamedPipeW(
                    WString(pipeName),
                    PIPE_ACCESS_DUPLEX or if (firstInstance) FILE_FLAG_FIRST_PIPE_INSTANCE else 0,
                    PIPE_TYPE_BYTE or PIPE_READMODE_BYTE or PIPE_WAIT or PIPE_REJECT_REMOTE_CLIENTS,
                    MAX_INSTANCES,
                    PIPE_BUFFER_BYTES,
                    PIPE_BUFFER_BYTES,
                    0,
                    security.attributes,
                )
                if (isInvalid(pipe)) {
                    throw pipeCreationFailure(Native.getLastError())
                }
                firstInstance = false
                if (!ready) {
                    ready = true
                    onReady()
                }
                pending.set(pipe)
                val connected = KERNEL.ConnectNamedPipe(pipe, null) != 0 ||
                    Native.getLastError() == ERROR_PIPE_CONNECTED
                pending.compareAndSet(pipe, null)
                if (!connected || closed.get()) {
                    closeHandle(pipe)
                    if (closed.get()) break
                    throw IOException("cannot accept SSH agent pipe client (Win32 ${Native.getLastError()})")
                }
                if (active.size >= maximumConnections) {
                    KERNEL.DisconnectNamedPipe(pipe)
                    closeHandle(pipe)
                    continue
                }
                val pid = IntByReference()
                if (KERNEL.GetNamedPipeClientProcessId(pipe, pid) == 0 || pid.value <= 0) {
                    KERNEL.DisconnectNamedPipe(pipe)
                    closeHandle(pipe)
                    continue
                }
                active += pipe
                clients.submit {
                    try {
                        handler(PipeInputStream(pipe), PipeOutputStream(pipe), pid.value.toLong())
                    } finally {
                        KERNEL.FlushFileBuffers(pipe)
                        KERNEL.DisconnectNamedPipe(pipe)
                        closeHandle(pipe)
                        active -= pipe
                    }
                }
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        pending.getAndSet(null)?.let(::closeHandle)
        active.forEach(::closeHandle)
        clients.shutdownNow()
        clients.awaitTermination(5, TimeUnit.SECONDS)
    }

    private class PipeInputStream(private val handle: Pointer) : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            val buffer = if (offset == 0 && length == target.size) target else ByteArray(length)
            val read = IntByReference()
            if (KERNEL.ReadFile(handle, buffer, length, read, null) == 0) {
                val error = Native.getLastError()
                if (error == ERROR_BROKEN_PIPE || error == ERROR_NO_DATA) return -1
                throw IOException("SSH agent pipe read failed (Win32 $error)")
            }
            if (read.value <= 0) return -1
            if (buffer !== target) buffer.copyInto(target, offset, 0, read.value)
            return read.value
        }
    }

    private class PipeOutputStream(private val handle: Pointer) : OutputStream() {
        override fun write(value: Int) = write(byteArrayOf(value.toByte()))

        override fun write(source: ByteArray, offset: Int, length: Int) {
            var position = 0
            val buffer = if (offset == 0 && length == source.size) source else source.copyOfRange(offset, offset + length)
            while (position < buffer.size) {
                val chunk = if (position == 0) buffer else buffer.copyOfRange(position, buffer.size)
                val written = IntByReference()
                if (KERNEL.WriteFile(handle, chunk, chunk.size, written, null) == 0) {
                    throw IOException("SSH agent pipe write failed (Win32 ${Native.getLastError()})")
                }
                if (written.value <= 0) throw IOException("SSH agent pipe write made no progress")
                position += written.value
            }
        }
    }

    private class PipeSecurity : AutoCloseable {
        private val descriptor = PointerByReference()
        val attributes: SecurityAttributes

        init {
            check(ADVAPI.ConvertStringSecurityDescriptorToSecurityDescriptorW(
                WString("D:P(A;;GA;;;OW)(A;;GA;;;SY)"),
                SDDL_REVISION_1,
                descriptor,
                null,
            ) != 0) { "cannot create named-pipe security descriptor (Win32 ${Native.getLastError()})" }
            attributes = SecurityAttributes().apply {
                dwLength = size()
                lpSecurityDescriptor = descriptor.value
                bInheritHandle = 0
                write()
            }
        }

        override fun close() {
            descriptor.value?.let(KERNEL::LocalFree)
            descriptor.value = null
        }
    }

    class SecurityAttributes : Structure() {
        @JvmField var dwLength: Int = 0
        @JvmField var lpSecurityDescriptor: Pointer? = null
        @JvmField var bInheritHandle: Int = 0
        override fun getFieldOrder() = listOf("dwLength", "lpSecurityDescriptor", "bInheritHandle")
    }

    private interface Kernel32 : StdCallLibrary {
        fun CreateNamedPipeW(
            name: WString,
            openMode: Int,
            pipeMode: Int,
            maximumInstances: Int,
            outputBufferSize: Int,
            inputBufferSize: Int,
            defaultTimeout: Int,
            security: SecurityAttributes,
        ): Pointer
        fun ConnectNamedPipe(pipe: Pointer, overlapped: Pointer?): Int
        fun DisconnectNamedPipe(pipe: Pointer): Int
        fun GetNamedPipeClientProcessId(pipe: Pointer, processId: IntByReference): Int
        fun ReadFile(handle: Pointer, buffer: ByteArray, bytesToRead: Int, bytesRead: IntByReference, overlapped: Pointer?): Int
        fun WriteFile(handle: Pointer, buffer: ByteArray, bytesToWrite: Int, bytesWritten: IntByReference, overlapped: Pointer?): Int
        fun FlushFileBuffers(handle: Pointer): Int
        fun CloseHandle(handle: Pointer): Int
        fun LocalFree(memory: Pointer): Pointer?
    }

    private interface Advapi32 : StdCallLibrary {
        fun ConvertStringSecurityDescriptorToSecurityDescriptorW(
            descriptor: WString,
            revision: Int,
            securityDescriptor: PointerByReference,
            size: IntByReference?,
        ): Int
    }

    private fun closeHandle(handle: Pointer) = runCatching { KERNEL.CloseHandle(handle) }.let { Unit }
    private fun isInvalid(handle: Pointer?): Boolean = handle == null || Pointer.nativeValue(handle) == -1L

    private fun pipeCreationFailure(error: Int): IOException {
        val conflict = error == ERROR_ACCESS_DENIED || error == ERROR_PIPE_BUSY
        val detail = when {
            conflict && pipeName.equals(WINDOWS_OPENSSH_PIPE, ignoreCase = true) ->
                "the Windows OpenSSH Authentication Agent service or another agent already owns it; " +
                    "stop that service explicitly or choose a custom pipe with -a"
            conflict -> "another process already owns this pipe; choose another address with -a"
            else -> "the operating system rejected the endpoint"
        }
        val message = "cannot create SSH Agent pipe $pipeName: $detail (Win32 $error)"
        return if (conflict) NamedPipeConflictException(pipeName, error, message) else IOException(message)
    }

    private companion object {
        val KERNEL: Kernel32 = Native.load("kernel32", Kernel32::class.java)
        val ADVAPI: Advapi32 = Native.load("advapi32", Advapi32::class.java)
        const val PIPE_ACCESS_DUPLEX = 0x00000003
        const val FILE_FLAG_FIRST_PIPE_INSTANCE = 0x00080000
        const val PIPE_TYPE_BYTE = 0x00000000
        const val PIPE_READMODE_BYTE = 0x00000000
        const val PIPE_WAIT = 0x00000000
        const val PIPE_REJECT_REMOTE_CLIENTS = 0x00000008
        const val MAX_INSTANCES = 255
        const val PIPE_BUFFER_BYTES = 256 * 1024
        const val ERROR_BROKEN_PIPE = 109
        const val ERROR_ACCESS_DENIED = 5
        const val ERROR_PIPE_BUSY = 231
        const val ERROR_NO_DATA = 232
        const val ERROR_PIPE_CONNECTED = 535
        const val SDDL_REVISION_1 = 1
    }
}

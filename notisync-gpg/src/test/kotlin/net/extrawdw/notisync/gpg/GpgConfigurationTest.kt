package net.extrawdw.notisync.gpg

import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GpgConfigurationTest {
    private val store = NotisyncGpgConfigStore(Path.of("ignored.conf"))

    @Test
    fun configurationCodecPreservesQuotedWindowsPath() {
        val expected = NotisyncGpgConfig(
            realGpgPath = Path.of("C:\\Program Files\\GnuPG\\bin\\gpg.exe"),
            timeoutSeconds = 90,
            maximumPayloadBytes = 100_000,
        )

        assertEquals(expected, store.decode(store.encode(expected)))
    }

    @Test
    fun rejectsUnknownAndDuplicateOptions() {
        assertThrows(IllegalArgumentException::class.java) {
            store.decode("real-gpg-path /gpg\nunknown value\n")
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.decode("real-gpg-path /gpg\nreal-gpg-path /other\n")
        }
    }
}

package net.extrawdw.notisync.run.output

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RemoteInputRedactorTest {
    @Test
    fun `redacts UTF-8 remote input split across arbitrary output chunks`() {
        val redactor = RemoteInputRedactor()
        redactor.register("sëcret-value\n")
        val captured = ByteArrayOutputStream()
        captured.write(redactor.accept("before së".encodeToByteArray()))
        captured.write(redactor.accept("cret-".encodeToByteArray()))
        captured.write(redactor.accept("value after".encodeToByteArray()))
        captured.write(redactor.finish())

        val text = captured.toString(Charsets.UTF_8)
        assertEquals("before [remote input redacted] after", text)
        assertFalse(text.contains("sëcret-value"))
    }
}

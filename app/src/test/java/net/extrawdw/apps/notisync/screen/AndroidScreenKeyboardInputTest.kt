package net.extrawdw.apps.notisync.screen

import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import com.genymobile.scrcpy.control.ControlChannel
import com.genymobile.scrcpy.control.ControlMessage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidScreenKeyboardInputTest {
    @Test
    fun `physical keys are accepted but software keyboard and other input sources stay local`() {
        assertTrue(isScreenHardwareKeyboardEvent(InputDevice.SOURCE_KEYBOARD, 0))
        assertFalse(isScreenHardwareKeyboardEvent(InputDevice.SOURCE_KEYBOARD, KeyEvent.FLAG_SOFT_KEYBOARD))
        assertFalse(isScreenHardwareKeyboardEvent(InputDevice.SOURCE_GAMEPAD, 0))
        assertFalse(isScreenHardwareKeyboardEvent(InputDevice.SOURCE_DPAD, 0))
    }

    @Test
    fun `typing works without an IME and preserves layout case punctuation space and numpad text`() =
        withKeyboard { input, channel ->
            listOf(
                KeyEvent.KEYCODE_A to 'A', KeyEvent.KEYCODE_Z to 'y',
                KeyEvent.KEYCODE_1 to '!', KeyEvent.KEYCODE_SPACE to ' ',
                KeyEvent.KEYCODE_NUMPAD_2 to '2', KeyEvent.KEYCODE_E to 'é',
            ).forEach { (key, character) ->
                assertTrue(input.key(KeyEvent.ACTION_DOWN, key, unicode = character.code))
                assertTrue(input.key(KeyEvent.ACTION_UP, key))
            }
            val messages = channel.messages()
            assertTrue(messages.all { it.type == ControlMessage.TYPE_INJECT_TEXT })
            assertEquals("Ay! 2é", messages.joinToString("") { it.text })
        }

    @Test
    fun `navigation and escape produce real down and up events accepted by the source`() =
        withKeyboard { input, channel ->
            val keys = listOf(
                KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DEL,
                KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_PAGE_UP,
                KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END,
                KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_NUMPAD_ENTER,
            )
            keys.forEach { key ->
                assertTrue(input.key(KeyEvent.ACTION_DOWN, key))
                assertTrue(input.key(KeyEvent.ACTION_UP, key))
            }
            val messages = channel.messages()
            keys.forEachIndexed { index, key ->
                val expected = key
                assertEquals(expected, messages[index * 2].keycode)
                assertEquals(KeyEvent.ACTION_DOWN, messages[index * 2].action)
                assertEquals(expected, messages[index * 2 + 1].keycode)
                assertEquals(KeyEvent.ACTION_UP, messages[index * 2 + 1].action)
            }
        }

    @Test
    fun `ctrl shortcut carries modifiers and releases even when ctrl is released first`() =
        withKeyboard { input, channel ->
            val ctrl = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
            assertTrue(input.key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, meta = ctrl))
            assertTrue(input.key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_C, meta = ctrl))
            assertTrue(input.key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT))
            assertTrue(input.key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_C, unicode = 'c'.code))
            val messages = channel.messages()
            assertEquals(4, messages.size)
            assertEquals(KeyEvent.KEYCODE_CTRL_LEFT, messages[0].keycode)
            assertEquals(KeyEvent.KEYCODE_C, messages[1].keycode)
            assertEquals(ctrl, messages[1].metaState)
            assertEquals(KeyEvent.KEYCODE_CTRL_LEFT, messages[2].keycode)
            assertEquals(KeyEvent.ACTION_UP, messages[3].action)
            assertEquals(0, messages[3].metaState)
        }

    @Test
    fun `shift selection and long key repeats stay within the source policy`() =
        withKeyboard { input, channel ->
            val meta = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON or KeyEvent.META_CAPS_LOCK_ON
            input.key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, meta = meta)
            input.key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, repeat = 5000, meta = meta)
            input.key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT, meta = meta)
            val messages = channel.messages()
            assertEquals(3, messages.size)
            assertEquals(1000, messages[1].repeat)
            assertEquals(meta, messages[1].metaState)
        }

    @Test
    fun `shift tab and ctrl shift letter preserve their complete supported modifier state`() =
        withKeyboard { input, channel ->
            val shift = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_RIGHT_ON
            val ctrlShift = shift or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
            input.key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB, meta = shift)
            input.key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TAB, meta = shift)
            input.key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Z, meta = ctrlShift)
            input.key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_Z, meta = ctrlShift)
            val messages = channel.messages()
            assertEquals(listOf(shift, shift, ctrlShift, ctrlShift), messages.map { it.metaState })
            assertEquals(listOf(KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_Z),
                messages.map { it.keycode })
        }

    @Test
    fun `typing repeats produce text once per down without duplicate key up text`() =
        withKeyboard { input, channel ->
            input.key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, unicode = 'a'.code)
            input.key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, repeat = 1, unicode = 'a'.code)
            input.key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A, unicode = 'a'.code)
            assertEquals(listOf("a", "a"), channel.messages().map { it.text })
        }

    @Test
    fun `focus loss releases held remote keys and clears partial dead key composition`() =
        withKeyboard { input, channel ->
            input.key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)
            input.key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_APOSTROPHE,
                unicode = KeyCharacterMap.COMBINING_ACCENT or '´'.code)
            input.releasePressedKeys()
            input.releasePressedKeys()
            assertFalse(input.key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT))
            assertFalse(input.key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, repeat = 2))
            input.key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_E, unicode = 'e'.code)
            val messages = channel.messages()
            assertEquals(3, messages.size)
            assertEquals(KeyEvent.ACTION_UP, messages[1].action)
            assertEquals(0, messages[1].metaState)
            assertEquals("e", messages[2].text)
        }

    @Test
    fun `focus loss releases a shortcut key before its modifier`() =
        withKeyboard { input, channel ->
            val ctrl = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
            input.key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, meta = ctrl)
            input.key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE, meta = ctrl)
            input.releasePressedKeys()
            val releases = channel.messages().filter { it.action == KeyEvent.ACTION_UP }
            assertEquals(listOf(KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_CTRL_LEFT), releases.map { it.keycode })
            assertTrue(releases.all { it.metaState == 0 })
        }

    @Test
    fun `session change releases only the old session and does not forward an orphan up`() =
        withKeyboard { input, oldChannel ->
            Channel().use { newChannel ->
                input.key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB)
                input.control = newChannel.dispatcher
                assertFalse(input.key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TAB))
                assertEquals(listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP), oldChannel.messages().map { it.action })
                assertTrue(newChannel.messages().isEmpty())
            }
        }

    @Test
    fun `dead keys compose once and unsupported combinations preserve both characters`() =
        withKeyboard { input, channel ->
            input.key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_APOSTROPHE,
                unicode = KeyCharacterMap.COMBINING_ACCENT or '´'.code)
            input.key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_APOSTROPHE)
            input.key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_E, unicode = 'e'.code)
            input.key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_E)
            input.key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_APOSTROPHE,
                unicode = KeyCharacterMap.COMBINING_ACCENT or '´'.code)
            input.key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_X, unicode = 'x'.code)
            assertEquals("é´x", channel.messages().joinToString("") { it.text })
        }

    @Test
    fun `inactive input and unsupported shortcuts neither type nor send invalid protocol keys`() =
        withKeyboard { input, channel ->
            assertFalse(input.key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_POWER))
            assertFalse(input.key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SLEEP, meta = KeyEvent.META_CTRL_ON))
            input.control = null
            assertFalse(input.key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, unicode = 'a'.code))
            assertTrue(channel.messages().isEmpty())
        }

    @Test
    fun `ctrl space and punctuation shortcuts are key events rather than inserted text`() =
        withKeyboard { input, channel ->
            val keys = listOf(
                KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_EQUALS,
                KeyEvent.KEYCODE_LEFT_BRACKET, KeyEvent.KEYCODE_RIGHT_BRACKET, KeyEvent.KEYCODE_BACKSLASH,
                KeyEvent.KEYCODE_SEMICOLON, KeyEvent.KEYCODE_APOSTROPHE, KeyEvent.KEYCODE_COMMA,
                KeyEvent.KEYCODE_PERIOD, KeyEvent.KEYCODE_SLASH, KeyEvent.KEYCODE_GRAVE,
                KeyEvent.KEYCODE_AT, KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_NUMPAD_DIVIDE,
            )
            val ctrlShift = KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON
            keys.forEach { key ->
                assertTrue(input.key(KeyEvent.ACTION_DOWN, key, meta = ctrlShift, unicode = '?'.code))
                assertTrue(input.key(KeyEvent.ACTION_UP, key, meta = ctrlShift))
            }
            val messages = channel.messages()
            assertEquals(keys.flatMap { listOf(it, it) }, messages.map { it.keycode })
            assertTrue(messages.all { it.type == ControlMessage.TYPE_INJECT_KEYCODE && it.metaState == ctrlShift })
        }

    @Test
    fun `all standard shifted punctuation is sent as the character produced by the local layout`() =
        withKeyboard { input, channel ->
            val symbols = "!@#\$%^&*()-_=+[{]}\\|;:'\",<.>/?"
            symbols.forEach { char ->
                input.key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_UNKNOWN, meta = KeyEvent.META_SHIFT_ON, unicode = char.code)
                input.key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_UNKNOWN)
            }
            assertEquals(symbols, channel.messages().joinToString("") { it.text })
        }

    @Test
    fun `function keys numpad navigation and standalone modifiers keep their key identities`() =
        withKeyboard { input, channel ->
            val keys = (KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12).toList() + listOf(
                KeyEvent.KEYCODE_NUMPAD_4, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_INSERT,
                KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT,
                KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT,
                KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT,
                KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT,
                KeyEvent.KEYCODE_CAPS_LOCK, KeyEvent.KEYCODE_NUM_LOCK, KeyEvent.KEYCODE_SCROLL_LOCK,
                KeyEvent.KEYCODE_FUNCTION,
            )
            keys.forEach { key ->
                assertTrue(input.key(KeyEvent.ACTION_DOWN, key))
                assertTrue(input.key(KeyEvent.ACTION_UP, key))
                assertEquals(listOf(key, key), channel.messages().map { it.keycode })
            }
        }

    @Test
    fun `alt and meta shortcuts retain left right and lock state`() =
        withKeyboard { input, channel ->
            val modifiers = listOf(
                KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON,
                KeyEvent.META_ALT_ON or KeyEvent.META_ALT_RIGHT_ON,
                KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON,
                KeyEvent.META_META_ON or KeyEvent.META_META_RIGHT_ON,
                KeyEvent.META_CTRL_ON or KeyEvent.META_CAPS_LOCK_ON or KeyEvent.META_NUM_LOCK_ON or KeyEvent.META_SCROLL_LOCK_ON,
            )
            modifiers.forEach { meta ->
                input.key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SEMICOLON, meta = meta, unicode = ';'.code)
                input.key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SEMICOLON, meta = meta)
                assertEquals(listOf(meta, meta), channel.messages().map { it.metaState })
            }
        }

    @Test
    @Suppress("DEPRECATION") // ACTION_MULTIPLE must be rejected, never encoded as a wire action.
    fun `dispatcher accepts exactly source-compatible keys and rejects malformed input`() {
        Channel().use { channel ->
            for (keyCode in 0..400) {
                assertEquals(isAndroidScreenControlKey(keyCode),
                    channel.dispatcher.sendKeyEvent(KeyEvent.ACTION_DOWN, keyCode, 0, -1))
                // Drain each accepted event to exercise the real source validator without filling the queue.
                channel.messages().forEach { assertEquals(keyCode, it.keycode) }
            }
            assertFalse(channel.dispatcher.sendKeyEvent(KeyEvent.ACTION_MULTIPLE, KeyEvent.KEYCODE_A, 0, 0))
            assertFalse(channel.dispatcher.sendKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, -1, 0))
            assertTrue(channel.messages().isEmpty())
        }
    }

    private fun withKeyboard(block: (AndroidScreenKeyboardInput, Channel) -> Unit) {
        Channel().use { channel ->
            val input = AndroidScreenKeyboardInput { accent, base ->
                if (accent == '´'.code && base == 'e'.code) 'é'.code else 0
            }.apply { control = channel.dispatcher }
            try { block(input, channel) } finally { input.control = null }
        }
    }

    private fun AndroidScreenKeyboardInput.key(
        action: Int, key: Int, repeat: Int = 0, meta: Int = 0, unicode: Int = 0,
    ): Boolean = dispatch(action, key, repeat, meta, unicode, deviceId = 1)

    private class Channel : AutoCloseable {
        private val writes = LinkedBlockingQueue<ByteArray>()
        private val failures = LinkedBlockingQueue<Throwable>()
        val dispatcher = AndroidScreenControlDispatcher(AndroidScreenControlWriter(object : OutputStream() {
            override fun write(value: Int) = error("expected whole frames")
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                writes.add(buffer.copyOfRange(offset, offset + length))
            }
        })) { failures.add(it) }

        fun messages(): List<ControlMessage> {
            assertTrue(failures.toString(), failures.isEmpty())
            assertTrue(dispatcher.setVideoVisible(true)) // Ordered barrier behind all queued input.
            val messages = mutableListOf<ControlMessage>()
            while (true) {
                val bytes = checkNotNull(writes.poll(5, TimeUnit.SECONDS)) { "control writer did not drain" }
                val message = ControlChannel(ByteArrayInputStream(bytes), ByteArrayOutputStream(), true, false).recv()
                if (message.type == ControlMessage.TYPE_SET_VIDEO_VISIBILITY) return messages
                messages += message
            }
        }

        override fun close() = dispatcher.close()
    }
}

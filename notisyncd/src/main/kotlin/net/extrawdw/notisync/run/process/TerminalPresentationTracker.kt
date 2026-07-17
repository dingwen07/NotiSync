package net.extrawdw.notisync.run.process

import java.io.ByteArrayOutputStream

/** Tracks terminal presentation modes whose ordinary shell baseline is known. */
internal class TerminalPresentationTracker {
    private enum class ParseState { TEXT, ESCAPE, G0_DESIGNATION, CSI, CONTROL_STRING, CONTROL_STRING_ESCAPE }

    private enum class Mode(val recovery: String) {
        SYNCHRONIZED_OUTPUT("\u001B[?2026l"),
        ALT_SCREEN_1049("\u001B[?1049l"),
        ALT_SCREEN_1047("\u001B[?1047l"),
        ALT_SCREEN_47("\u001B[?47l"),
        CURSOR_HIDDEN("\u001B[?25h"),
        APPLICATION_CURSOR("\u001B[?1l"),
        ORIGIN_MODE("\u001B[?6l"),
        AUTOWRAP_DISABLED("\u001B[?7h"),
        MOUSE_X10("\u001B[?1000l"),
        MOUSE_BUTTON("\u001B[?1002l"),
        MOUSE_ANY("\u001B[?1003l"),
        FOCUS_EVENTS("\u001B[?1004l"),
        MOUSE_UTF8("\u001B[?1005l"),
        MOUSE_SGR("\u001B[?1006l"),
        MOUSE_URXVT("\u001B[?1015l"),
        BRACKETED_PASTE("\u001B[?2004l"),
        APPLICATION_KEYPAD("\u001B>"),
        SCROLL_REGION("\u001B[r"),
        SGR_ATTRIBUTES("\u001B[0m"),
        G0_CHARACTER_SET("\u001B(B"),
    }

    private enum class SgrCategory {
        INTENSITY, ITALIC, UNDERLINE, BLINK, INVERSE, CONCEAL, STRIKE,
        FONT, FOREGROUND, BACKGROUND, FRAME, OVERLINE, UNDERLINE_COLOR, SCRIPT,
    }

    private var state = ParseState.TEXT
    private var controlStringAllowsBel = false
    private val csi = StringBuilder()
    private val outstanding = linkedSetOf<Mode>()
    private val sgr = linkedSetOf<SgrCategory>()

    fun accept(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
        for (index in offset until offset + length) accept(bytes[index].toInt() and 0xff)
    }

    fun recovery(): ByteArray {
        val output = ByteArrayOutputStream()
        when (state) {
            ParseState.TEXT -> Unit
            ParseState.CONTROL_STRING -> output.write(byteArrayOf(ESC.toByte(), '\\'.code.toByte()))
            ParseState.CONTROL_STRING_ESCAPE -> output.write('\\'.code)
            ParseState.ESCAPE, ParseState.G0_DESIGNATION, ParseState.CSI -> output.write(CAN)
        }
        state = ParseState.TEXT
        csi.setLength(0)
        // Disabling synchronized output first ensures the remaining recovery is rendered now.
        Mode.entries.forEach { mode ->
            if (mode in outstanding) output.write(mode.recovery.encodeToByteArray())
        }
        outstanding.clear()
        sgr.clear()
        return output.toByteArray()
    }

    private fun accept(byte: Int) {
        when (state) {
            ParseState.TEXT -> if (byte == ESC) state = ParseState.ESCAPE
            ParseState.ESCAPE -> acceptEscape(byte)
            ParseState.G0_DESIGNATION -> {
                if (byte == CAN || byte == SUB) Unit
                else if (byte == 'B'.code) outstanding -= Mode.G0_CHARACTER_SET
                else outstanding += Mode.G0_CHARACTER_SET
                state = ParseState.TEXT
            }
            ParseState.CSI -> acceptCsi(byte)
            ParseState.CONTROL_STRING -> when {
                byte == CAN || byte == SUB -> state = ParseState.TEXT
                controlStringAllowsBel && byte == BEL -> state = ParseState.TEXT
                byte == ESC -> state = ParseState.CONTROL_STRING_ESCAPE
            }
            ParseState.CONTROL_STRING_ESCAPE -> state = when (byte) {
                CAN, SUB -> ParseState.TEXT
                '\\'.code -> ParseState.TEXT
                ESC -> ParseState.CONTROL_STRING_ESCAPE
                else -> ParseState.CONTROL_STRING
            }
        }
    }

    private fun acceptEscape(byte: Int) {
        when (byte) {
            '['.code -> {
                csi.setLength(0)
                state = ParseState.CSI
            }
            ']'.code -> startControlString(allowsBel = true) // OSC
            'P'.code, '_'.code, '^'.code, 'X'.code -> startControlString(allowsBel = false) // DCS/APC/PM/SOS
            '('.code -> state = ParseState.G0_DESIGNATION
            '='.code -> {
                outstanding += Mode.APPLICATION_KEYPAD
                state = ParseState.TEXT
            }
            '>'.code -> {
                outstanding -= Mode.APPLICATION_KEYPAD
                state = ParseState.TEXT
            }
            'c'.code -> {
                // RIS resets these modes to their ordinary terminal defaults.
                outstanding.clear()
                sgr.clear()
                state = ParseState.TEXT
            }
            ESC -> state = ParseState.ESCAPE
            else -> state = ParseState.TEXT
        }
    }

    private fun startControlString(allowsBel: Boolean) {
        controlStringAllowsBel = allowsBel
        state = ParseState.CONTROL_STRING
    }

    private fun acceptCsi(byte: Int) {
        if (byte == CAN || byte == SUB) {
            csi.setLength(0)
            state = ParseState.TEXT
            return
        }
        if (byte == ESC) {
            csi.setLength(0)
            state = ParseState.ESCAPE
            return
        }
        if (byte in CSI_FINAL_FIRST..CSI_FINAL_LAST) {
            processCsi(csi.toString(), byte.toChar())
            csi.setLength(0)
            state = ParseState.TEXT
            return
        }
        if (csi.length >= MAX_CSI_LENGTH) {
            csi.setLength(0)
            state = ParseState.TEXT
        } else {
            csi.append(byte.toChar())
        }
    }

    private fun processCsi(body: String, final: Char) {
        if (final == 'm' && body.all { it.isDigit() || it == ';' || it == ':' }) {
            processSgr(body)
            return
        }
        if (final == 'r' && body.all { it.isDigit() || it == ';' }) {
            val reset = body.isBlank() || body.split(';').all { it.isBlank() }
            if (reset) outstanding -= Mode.SCROLL_REGION else outstanding += Mode.SCROLL_REGION
            return
        }
        if (!body.startsWith('?') || final !in setOf('h', 'l')) return
        val set = final == 'h'
        body.drop(1).split(';').mapNotNull { it.substringBefore(':').toIntOrNull() }.forEach { parameter ->
            val mode = SET_MODES[parameter]
            if (mode != null) {
                if (set) outstanding += mode else outstanding -= mode
            } else {
                val resetMode = RESET_MODES[parameter]
                if (resetMode != null) {
                    // These modes have a safe shell baseline of SET; their dangerous state is RESET.
                    if (set) outstanding -= resetMode else outstanding += resetMode
                }
            }
        }
    }

    private fun processSgr(body: String) {
        val parameters = if (body.isEmpty()) listOf("0") else body.split(';')
        var index = 0
        while (index < parameters.size) {
            val raw = parameters[index]
            val parameter = raw.substringBefore(':').toIntOrNull() ?: 0
            when (parameter) {
                0 -> sgr.clear()
                1, 2 -> sgr += SgrCategory.INTENSITY
                22 -> sgr -= SgrCategory.INTENSITY
                3 -> sgr += SgrCategory.ITALIC
                23 -> sgr -= SgrCategory.ITALIC
                4, 21 -> sgr += SgrCategory.UNDERLINE
                24 -> sgr -= SgrCategory.UNDERLINE
                5, 6 -> sgr += SgrCategory.BLINK
                25 -> sgr -= SgrCategory.BLINK
                7 -> sgr += SgrCategory.INVERSE
                27 -> sgr -= SgrCategory.INVERSE
                8 -> sgr += SgrCategory.CONCEAL
                28 -> sgr -= SgrCategory.CONCEAL
                9 -> sgr += SgrCategory.STRIKE
                29 -> sgr -= SgrCategory.STRIKE
                in 11..19 -> sgr += SgrCategory.FONT
                10 -> sgr -= SgrCategory.FONT
                in 30..37, in 90..97 -> sgr += SgrCategory.FOREGROUND
                39 -> sgr -= SgrCategory.FOREGROUND
                in 40..47, in 100..107 -> sgr += SgrCategory.BACKGROUND
                49 -> sgr -= SgrCategory.BACKGROUND
                38 -> {
                    sgr += SgrCategory.FOREGROUND
                    index += extendedColorParameterCount(parameters, index, raw)
                }
                48 -> {
                    sgr += SgrCategory.BACKGROUND
                    index += extendedColorParameterCount(parameters, index, raw)
                }
                51, 52 -> sgr += SgrCategory.FRAME
                54 -> sgr -= SgrCategory.FRAME
                53 -> sgr += SgrCategory.OVERLINE
                55 -> sgr -= SgrCategory.OVERLINE
                58 -> {
                    sgr += SgrCategory.UNDERLINE_COLOR
                    index += extendedColorParameterCount(parameters, index, raw)
                }
                59 -> sgr -= SgrCategory.UNDERLINE_COLOR
                73, 74 -> sgr += SgrCategory.SCRIPT
                75 -> sgr -= SgrCategory.SCRIPT
            }
            index++
        }
        if (sgr.isEmpty()) outstanding -= Mode.SGR_ATTRIBUTES else outstanding += Mode.SGR_ATTRIBUTES
    }

    private fun extendedColorParameterCount(parameters: List<String>, index: Int, raw: String): Int {
        if (':' in raw || index + 1 >= parameters.size) return 0
        return when (parameters[index + 1].toIntOrNull()) {
            5 -> minOf(2, parameters.lastIndex - index)
            2 -> minOf(4, parameters.lastIndex - index)
            else -> 0
        }
    }

    private companion object {
        const val ESC = 0x1b
        const val BEL = 0x07
        const val CAN = 0x18
        const val SUB = 0x1a
        const val CSI_FINAL_FIRST = 0x40
        const val CSI_FINAL_LAST = 0x7e
        const val MAX_CSI_LENGTH = 256

        val SET_MODES = mapOf(
            1 to Mode.APPLICATION_CURSOR,
            6 to Mode.ORIGIN_MODE,
            47 to Mode.ALT_SCREEN_47,
            1000 to Mode.MOUSE_X10,
            1002 to Mode.MOUSE_BUTTON,
            1003 to Mode.MOUSE_ANY,
            1004 to Mode.FOCUS_EVENTS,
            1005 to Mode.MOUSE_UTF8,
            1006 to Mode.MOUSE_SGR,
            1015 to Mode.MOUSE_URXVT,
            1047 to Mode.ALT_SCREEN_1047,
            1049 to Mode.ALT_SCREEN_1049,
            2004 to Mode.BRACKETED_PASTE,
            2026 to Mode.SYNCHRONIZED_OUTPUT,
        )
        val RESET_MODES = mapOf(
            7 to Mode.AUTOWRAP_DISABLED,
            25 to Mode.CURSOR_HIDDEN,
        )
    }
}

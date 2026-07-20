package com.genymobile.scrcpy.control;

import com.genymobile.scrcpy.model.Position;
import com.genymobile.scrcpy.util.Binary;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.function.IntPredicate;

public class ControlMessageReader {

    private static final int MESSAGE_MAX_SIZE = 1 << 18; // 256k

    public static final int CLIPBOARD_TEXT_MAX_LENGTH = MESSAGE_MAX_SIZE - 14; // type: 1 byte; sequence: 8 bytes; paste flag: 1 byte; length: 4 bytes
    public static final int INJECT_TEXT_MAX_LENGTH = 300;

    private final DataInputStream dis;

    public ControlMessageReader(InputStream rawInputStream) {
        dis = new DataInputStream(new BufferedInputStream(rawInputStream));
    }

    /** Reject a session-disabled message immediately after its one-byte type. */
    public ControlMessage read(IntPredicate allowedType) throws IOException {
        int type = dis.readUnsignedByte();
        if (!allowedType.test(type)) {
            throw new ControlProtocolException("Control message type is not enabled: " + type);
        }
        switch (type) {
            case ControlMessage.TYPE_INJECT_KEYCODE:
                return parseInjectKeycode();
            case ControlMessage.TYPE_INJECT_TEXT:
                return parseInjectText();
            case ControlMessage.TYPE_INJECT_TOUCH_EVENT:
                return parseInjectTouchEvent();
            case ControlMessage.TYPE_INJECT_SCROLL_EVENT:
                return parseInjectScrollEvent();
            case ControlMessage.TYPE_BACK_OR_SCREEN_ON:
                return parseBackOrScreenOnEvent();
            case ControlMessage.TYPE_GET_CLIPBOARD:
                return parseGetClipboard();
            case ControlMessage.TYPE_SET_CLIPBOARD:
                return parseSetClipboard();
            case ControlMessage.TYPE_TOGGLE_POWER:
                return ControlMessage.createTogglePower();
            case ControlMessage.TYPE_SET_VIDEO_VISIBILITY:
                return parseSetVideoVisibility();
            default:
                throw new ControlProtocolException("Unsupported NotiSync control message type: " + type);
        }
    }

    private ControlMessage parseInjectKeycode() throws IOException {
        int action = dis.readUnsignedByte();
        int keycode = dis.readInt();
        int repeat = dis.readInt();
        int metaState = dis.readInt();
        return ControlMessage.createInjectKeycode(action, keycode, repeat, metaState);
    }

    private int parseBufferLength(int sizeBytes, int maximum) throws IOException {
        assert sizeBytes > 0 && sizeBytes <= 4;
        long value = 0;
        for (int i = 0; i < sizeBytes; ++i) {
            value = (value << 8) | dis.readUnsignedByte();
        }
        if (value > maximum) {
            throw new ControlProtocolException("Control payload exceeds " + maximum + " bytes");
        }
        return (int) value;
    }

    private String parseString(int sizeBytes, int maximum) throws IOException {
        assert sizeBytes > 0 && sizeBytes <= 4;
        byte[] data = parseByteArray(sizeBytes, maximum);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data))
                    .toString();
        } catch (CharacterCodingException error) {
            throw new ControlProtocolException("Control text is not valid UTF-8");
        }
    }

    private String parseString(int maximum) throws IOException {
        return parseString(4, maximum);
    }

    private byte[] parseByteArray(int sizeBytes, int maximum) throws IOException {
        int len = parseBufferLength(sizeBytes, maximum);
        byte[] data = new byte[len];
        dis.readFully(data);
        return data;
    }

    private ControlMessage parseInjectText() throws IOException {
        String text = parseString(INJECT_TEXT_MAX_LENGTH);
        return ControlMessage.createInjectText(text);
    }

    private ControlMessage parseInjectTouchEvent() throws IOException {
        int action = dis.readUnsignedByte();
        long pointerId = dis.readLong();
        Position position = parsePosition();
        float pressure = Binary.u16FixedPointToFloat(dis.readShort());
        int actionButton = dis.readInt();
        int buttons = dis.readInt();
        return ControlMessage.createInjectTouchEvent(action, pointerId, position, pressure, actionButton, buttons);
    }

    private ControlMessage parseInjectScrollEvent() throws IOException {
        Position position = parsePosition();
        // Binary.i16FixedPointToFloat() decodes values assuming the full range is [-1, 1], but the actual range is [-16, 16].
        float hScroll = Binary.i16FixedPointToFloat(dis.readShort()) * 16;
        float vScroll = Binary.i16FixedPointToFloat(dis.readShort()) * 16;
        int buttons = dis.readInt();
        return ControlMessage.createInjectScrollEvent(position, hScroll, vScroll, buttons);
    }

    private ControlMessage parseBackOrScreenOnEvent() throws IOException {
        int action = dis.readUnsignedByte();
        return ControlMessage.createBackOrScreenOn(action);
    }

    private ControlMessage parseGetClipboard() throws IOException {
        int copyKey = dis.readUnsignedByte();
        return ControlMessage.createGetClipboard(copyKey);
    }

    private ControlMessage parseSetClipboard() throws IOException {
        long sequence = dis.readLong();
        boolean paste = dis.readByte() != 0;
        String text = parseString(CLIPBOARD_TEXT_MAX_LENGTH);
        return ControlMessage.createSetClipboard(sequence, text, paste);
    }

    private ControlMessage parseSetVideoVisibility() throws IOException {
        int visible = dis.readUnsignedByte();
        if (visible > 1) {
            throw new ControlProtocolException("Invalid video visibility value: " + visible);
        }
        return ControlMessage.createSetVideoVisibility(visible != 0);
    }

    private Position parsePosition() throws IOException {
        int x = dis.readInt();
        int y = dis.readInt();
        int screenWidth = dis.readUnsignedShort();
        int screenHeight = dis.readUnsignedShort();
        return new Position(x, y, screenWidth, screenHeight);
    }
}

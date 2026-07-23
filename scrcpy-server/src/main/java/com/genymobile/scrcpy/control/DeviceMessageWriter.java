package com.genymobile.scrcpy.control;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;

public class DeviceMessageWriter {

    private static final int MESSAGE_MAX_SIZE = 1 << 18; // 256k
    public static final int CLIPBOARD_TEXT_MAX_LENGTH = MESSAGE_MAX_SIZE - 5; // type: 1 byte; length: 4 bytes

    private final DataOutputStream dos;

    static final class EncodedClipboard {
        final byte[] data;
        final int length;

        private EncodedClipboard(byte[] data, int length) {
            this.data = data;
            this.length = length;
        }
    }

    public DeviceMessageWriter(OutputStream rawOutputStream) {
        dos = new DataOutputStream(new BufferedOutputStream(rawOutputStream));
    }

    public void write(DeviceMessage msg) throws IOException {
        int type = msg.getType();
        switch (type) {
            case DeviceMessage.TYPE_CLIPBOARD:
                byte[] raw = msg.getClipboardUtf8();
                int len = msg.getClipboardUtf8Length();
                dos.writeByte(type);
                dos.writeInt(len);
                dos.write(raw, 0, len);
                break;
            case DeviceMessage.TYPE_ACK_CLIPBOARD:
                dos.writeByte(type);
                dos.writeLong(msg.getSequence());
                break;
            default:
                throw new ControlProtocolException("Unknown event type: " + type);
        }
        dos.flush();
    }

    /**
     * Encodes at most the wire limit directly into a bounded buffer. The encoder never emits a
     * partial UTF-8 sequence, so overflow safely truncates before the next code point without
     * allocating an array proportional to a hostile local clipboard.
     */
    static EncodedClipboard encodeClipboardText(String text) throws ControlProtocolException {
        if (text == null) {
            throw new ControlProtocolException("Device clipboard is null");
        }
        try {
            long maximumEncodedSize = (long) text.length() * 3L;
            int capacity = (int) Math.min(CLIPBOARD_TEXT_MAX_LENGTH, maximumEncodedSize);
            ByteBuffer encoded = ByteBuffer.allocate(capacity);
            java.nio.charset.CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            CoderResult result = encoder.encode(CharBuffer.wrap(text), encoded, true);
            if (result.isError()) {
                result.throwException();
            }
            if (result.isUnderflow()) {
                result = encoder.flush(encoded);
                if (result.isError()) {
                    result.throwException();
                }
            }
            return new EncodedClipboard(encoded.array(), encoded.position());
        } catch (CharacterCodingException error) {
            throw new ControlProtocolException("Device clipboard is not valid Unicode text");
        }
    }
}

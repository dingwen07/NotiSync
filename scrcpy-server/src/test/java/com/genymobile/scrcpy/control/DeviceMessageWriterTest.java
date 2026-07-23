package com.genymobile.scrcpy.control;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

public final class DeviceMessageWriterTest {

    @Test
    public void hugeClipboardUsesBoundedEncodingStorage() throws Exception {
        String text = "x".repeat(DeviceMessageWriter.CLIPBOARD_TEXT_MAX_LENGTH * 8);

        DeviceMessageWriter.EncodedClipboard encoded = DeviceMessageWriter.encodeClipboardText(text);

        assertTrue(encoded.data.length <= DeviceMessageWriter.CLIPBOARD_TEXT_MAX_LENGTH);
        assertEquals(DeviceMessageWriter.CLIPBOARD_TEXT_MAX_LENGTH, encoded.length);
    }

    @Test
    public void truncationNeverSplitsUtf8CodePoint() throws Exception {
        String text = "a".repeat(DeviceMessageWriter.CLIPBOARD_TEXT_MAX_LENGTH - 2)
                + "\ud83d\ude00" + "tail";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new DeviceMessageWriter(bytes).write(DeviceMessage.createClipboard(text));
        byte[] wire = bytes.toByteArray();
        int length = ByteBuffer.wrap(wire, 1, 4).getInt();

        assertEquals(DeviceMessageWriter.CLIPBOARD_TEXT_MAX_LENGTH - 2, length);
        StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(wire, 5, length));
    }

    @Test
    public void malformedUnicodeBeforeLimitIsRejected() {
        assertThrows(ControlProtocolException.class,
                () -> DeviceMessageWriter.encodeClipboardText("bad\ud800text"));
    }
}

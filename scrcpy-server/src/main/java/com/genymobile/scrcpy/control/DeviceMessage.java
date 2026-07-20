package com.genymobile.scrcpy.control;

public final class DeviceMessage {

    public static final int TYPE_CLIPBOARD = 0;
    public static final int TYPE_ACK_CLIPBOARD = 1;

    private int type;
    private byte[] clipboardUtf8;
    private int clipboardUtf8Length;
    private long sequence;

    private DeviceMessage() {
    }

    public static DeviceMessage createClipboard(String text) throws ControlProtocolException {
        return createClipboard(DeviceMessageWriter.encodeClipboardText(text));
    }

    static DeviceMessage createClipboard(DeviceMessageWriter.EncodedClipboard encoded) {
        DeviceMessage event = new DeviceMessage();
        event.type = TYPE_CLIPBOARD;
        event.clipboardUtf8 = encoded.data;
        event.clipboardUtf8Length = encoded.length;
        return event;
    }

    public static DeviceMessage createAckClipboard(long sequence) {
        DeviceMessage event = new DeviceMessage();
        event.type = TYPE_ACK_CLIPBOARD;
        event.sequence = sequence;
        return event;
    }

    public int getType() {
        return type;
    }

    byte[] getClipboardUtf8() {
        return clipboardUtf8;
    }

    int getClipboardUtf8Length() {
        return clipboardUtf8Length;
    }

    public long getSequence() {
        return sequence;
    }

}

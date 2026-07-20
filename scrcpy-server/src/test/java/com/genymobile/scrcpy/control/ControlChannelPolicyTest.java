package com.genymobile.scrcpy.control;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;

import org.junit.Test;

public final class ControlChannelPolicyTest {

    @Test
    public void rejectsEveryNonMvpScrcpyCommandBeforeItsPayload() {
        int[] removedTypes = {5, 6, 7, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22};
        for (int type : removedTypes) {
            ControlChannel channel = channel(new byte[]{(byte) type}, true, true);
            assertThrows(ControlProtocolException.class, channel::recv);
        }
    }

    @Test
    public void acceptsBackOrScreenOnWhenControlEnabled() throws Exception {
        ControlChannel channel = channel(new byte[]{ControlMessage.TYPE_BACK_OR_SCREEN_ON, 1}, true, false);

        assertEquals(ControlMessage.TYPE_BACK_OR_SCREEN_ON, channel.recv().getType());
    }

    @Test
    public void acceptsPowerToggleWhenControlEnabled() throws Exception {
        ControlChannel channel = channel(new byte[]{ControlMessage.TYPE_TOGGLE_POWER}, true, false);

        assertEquals(ControlMessage.TYPE_TOGGLE_POWER, channel.recv().getType());
    }

    @Test
    public void rejectsPowerToggleWhenControlDisabled() {
        ControlChannel channel = channel(new byte[]{ControlMessage.TYPE_TOGGLE_POWER}, false, true);

        assertThrows(ControlProtocolException.class, channel::recv);
    }

    @Test
    public void acceptsVideoVisibilityForViewOnlySession() throws Exception {
        ControlChannel channel = channel(
                new byte[]{ControlMessage.TYPE_SET_VIDEO_VISIBILITY, 0,
                        ControlMessage.TYPE_SET_VIDEO_VISIBILITY, 1},
                false,
                false
        );

        ControlMessage hidden = channel.recv();
        ControlMessage visible = channel.recv();
        assertEquals(ControlMessage.TYPE_SET_VIDEO_VISIBILITY, hidden.getType());
        assertFalse(hidden.isVideoVisible());
        assertEquals(ControlMessage.TYPE_SET_VIDEO_VISIBILITY, visible.getType());
        assertTrue(visible.isVideoVisible());
    }

    @Test
    public void rejectsNonBooleanVideoVisibility() {
        ControlChannel channel = channel(
                new byte[]{ControlMessage.TYPE_SET_VIDEO_VISIBILITY, 2},
                false,
                false
        );

        assertThrows(ControlProtocolException.class, channel::recv);
    }

    @Test
    public void rejectsClipboardWhenClipboardDisabled() {
        ControlChannel channel = channel(new byte[]{ControlMessage.TYPE_GET_CLIPBOARD, 0}, true, false);

        assertThrows(ControlProtocolException.class, channel::recv);
    }

    @Test
    public void acceptsClipboardWhenClipboardEnabled() throws Exception {
        ControlChannel channel = channel(new byte[]{ControlMessage.TYPE_GET_CLIPBOARD, 0}, false, true);

        assertEquals(ControlMessage.TYPE_GET_CLIPBOARD, channel.recv().getType());
    }

    @Test
    public void rejectsOutgoingClipboardWhenClipboardDisabled() {
        ControlChannel channel = channel(new byte[0], true, false);

        assertThrows(
                ControlProtocolException.class,
                () -> channel.send(DeviceMessage.createClipboard("secret"))
        );
    }

    @Test
    public void rejectsMalformedDeviceClipboardText() {
        ControlChannel channel = channel(new byte[0], true, true);

        assertThrows(
                ControlProtocolException.class,
                () -> channel.send(DeviceMessage.createClipboard("\ud800"))
        );
    }

    @Test
    public void rejectsOversizedInjectTextBeforeReadingPayload() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeByte(ControlMessage.TYPE_INJECT_TEXT);
        output.writeInt(ControlMessageReader.INJECT_TEXT_MAX_LENGTH + 1);

        ControlChannel channel = channel(bytes.toByteArray(), true, false);

        assertThrows(ControlProtocolException.class, channel::recv);
    }

    @Test
    public void rejectsMalformedUtf8() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeByte(ControlMessage.TYPE_INJECT_TEXT);
        output.writeInt(2);
        output.writeByte(0xc3);
        output.writeByte(0x28);

        ControlChannel channel = channel(bytes.toByteArray(), true, false);

        assertThrows(ControlProtocolException.class, channel::recv);
    }

    @Test
    public void clipboardOnlySessionCannotInjectCopyKey() {
        ControlChannel channel = channel(
                new byte[]{ControlMessage.TYPE_GET_CLIPBOARD, ControlMessage.COPY_KEY_COPY},
                false,
                true
        );

        assertThrows(ControlProtocolException.class, channel::recv);
    }

    @Test
    public void clipboardSynchronizationCannotRequestPaste() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeByte(ControlMessage.TYPE_SET_CLIPBOARD);
        output.writeLong(1);
        output.writeBoolean(true);
        output.writeInt(0);

        ControlChannel channel = channel(bytes.toByteArray(), false, true);

        assertThrows(ControlProtocolException.class, channel::recv);
    }

    @Test
    public void rejectsKeycodesOutsideMvpAllowlist() throws Exception {
        // Power state is available only through bounded dedicated operations. Generic key
        // injection must expose neither the toggle-prone POWER key nor one-way WAKEUP.
        assertThrows(ControlProtocolException.class, channel(keyMessage(26), true, false)::recv);
        assertThrows(ControlProtocolException.class, channel(keyMessage(224), true, false)::recv);
    }

    @Test
    public void acceptsSystemHomeAndRecentsNavigationKeys() throws Exception {
        assertEquals(ControlMessage.TYPE_INJECT_KEYCODE, channel(keyMessage(3), true, false).recv().getType());
        assertEquals(ControlMessage.TYPE_INJECT_KEYCODE, channel(keyMessage(187), true, false).recv().getType());
    }

    @Test
    public void rejectsTouchCoordinatesOutsideDeclaredDisplay() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeByte(ControlMessage.TYPE_INJECT_TOUCH_EVENT);
        output.writeByte(0);
        output.writeLong(0);
        output.writeInt(1_920);
        output.writeInt(100);
        output.writeShort(1_920);
        output.writeShort(1_080);
        output.writeShort(0xffff);
        output.writeInt(0);
        output.writeInt(0);

        ControlChannel channel = channel(bytes.toByteArray(), true, false);

        assertThrows(ControlProtocolException.class, channel::recv);
    }

    private static ControlChannel channel(byte[] bytes, boolean allowControl, boolean allowClipboard) {
        return new ControlChannel(
                new ByteArrayInputStream(bytes),
                new ByteArrayOutputStream(),
                allowControl,
                allowClipboard
        );
    }

    private static byte[] keyMessage(int keycode) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeByte(ControlMessage.TYPE_INJECT_KEYCODE);
        output.writeByte(0);
        output.writeInt(keycode);
        output.writeInt(0);
        output.writeInt(0);
        return bytes.toByteArray();
    }
}

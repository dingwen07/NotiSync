package com.genymobile.scrcpy.control;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class ControlChannel implements java.io.Closeable {

    private final InputStream input;
    private final OutputStream output;
    private final ControlMessageReader reader;
    private final DeviceMessageWriter writer;
    private final boolean allowControl;
    private final boolean allowClipboard;

    /**
     * NotiSync transport adapter: the app owns a private full-duplex socket pair and passes the
     * privileged endpoint into the Shizuku process. Keeping this constructor stream-based avoids
     * giving the shell process any LAN socket or session key.
     */
    public ControlChannel(InputStream input, OutputStream output) {
        this(input, output, true, true);
    }

    public ControlChannel(InputStream input, OutputStream output, boolean allowControl, boolean allowClipboard) {
        this.input = input;
        this.output = output;
        reader = new ControlMessageReader(input);
        writer = new DeviceMessageWriter(output);
        this.allowControl = allowControl;
        this.allowClipboard = allowClipboard;
    }

    public ControlMessage recv() throws IOException {
        ControlMessage message = reader.read(this::isAllowed);
        if (!isSemanticallyValid(message)) {
            throw new ControlProtocolException("Malformed or unsupported NotiSync control message");
        }
        return message;
    }

    public void send(DeviceMessage msg) throws IOException {
        if (!allowClipboard || (msg.getType() != DeviceMessage.TYPE_CLIPBOARD
                && msg.getType() != DeviceMessage.TYPE_ACK_CLIPBOARD)) {
            throw new ControlProtocolException(
                    "Device message type is not enabled for this NotiSync session: " + msg.getType());
        }
        writer.write(msg);
    }

    @Override
    public void close() {
        try {
            input.close();
        } catch (IOException ignored) {
            // Best effort; closing both duplicated socket descriptors is what unblocks the loop.
        }
        try {
            output.close();
        } catch (IOException ignored) {
            // Best effort during session teardown.
        }
    }

    private boolean isAllowed(int type) {
        switch (type) {
            case ControlMessage.TYPE_INJECT_KEYCODE:
            case ControlMessage.TYPE_INJECT_TEXT:
            case ControlMessage.TYPE_INJECT_TOUCH_EVENT:
            case ControlMessage.TYPE_INJECT_SCROLL_EVENT:
            case ControlMessage.TYPE_BACK_OR_SCREEN_ON:
            case ControlMessage.TYPE_TOGGLE_POWER:
            case ControlMessage.TYPE_EXPAND_NOTIFICATION_PANEL:
                return allowControl;
            case ControlMessage.TYPE_SET_VIDEO_VISIBILITY:
                // Flow control changes only this session's encoder resource use. It is available
                // to an authenticated view-only client and grants no Android input authority.
                return true;
            case ControlMessage.TYPE_GET_CLIPBOARD:
            case ControlMessage.TYPE_SET_CLIPBOARD:
                return allowClipboard;
            default:
                // All other scrcpy protocol numbers are intentionally absent from this server.
                return false;
        }
    }

    private boolean isSemanticallyValid(ControlMessage message) {
        switch (message.getType()) {
            case ControlMessage.TYPE_INJECT_KEYCODE:
                return isAction(message.getAction())
                        && message.getRepeat() >= 0 && message.getRepeat() <= MAX_KEY_REPEAT
                        && (message.getMetaState() & ~ALLOWED_META_STATE_MASK) == 0
                        && isAllowedKeycode(message.getKeycode());
            case ControlMessage.TYPE_INJECT_TEXT:
                return utf8Length(message.getText()) <= ControlMessageReader.INJECT_TEXT_MAX_LENGTH;
            case ControlMessage.TYPE_INJECT_TOUCH_EVENT:
                return message.getAction() >= 0 && message.getAction() <= 3
                        && validPosition(message)
                        && (message.getActionButton() & ~ALLOWED_BUTTON_MASK) == 0
                        && (message.getButtons() & ~ALLOWED_BUTTON_MASK) == 0;
            case ControlMessage.TYPE_INJECT_SCROLL_EVENT:
                return validPosition(message)
                        && (message.getButtons() & ~ALLOWED_BUTTON_MASK) == 0;
            case ControlMessage.TYPE_BACK_OR_SCREEN_ON:
                return isAction(message.getAction());
            case ControlMessage.TYPE_TOGGLE_POWER:
            case ControlMessage.TYPE_EXPAND_NOTIFICATION_PANEL:
            case ControlMessage.TYPE_SET_VIDEO_VISIBILITY:
                return true;
            case ControlMessage.TYPE_GET_CLIPBOARD:
                // COPY/CUT inject keys and must not bypass a view/clipboard-only session.
                return message.getCopyKey() == ControlMessage.COPY_KEY_NONE;
            case ControlMessage.TYPE_SET_CLIPBOARD:
                // PASTE injects a key; text synchronization never requests it.
                return !message.getPaste()
                        && utf8Length(message.getText()) <= ControlMessageReader.CLIPBOARD_TEXT_MAX_LENGTH;
            default:
                return false;
        }
    }

    private static boolean isAction(int action) {
        return action == 0 || action == 1;
    }

    private static boolean validPosition(ControlMessage message) {
        int width = message.getPosition().getScreenSize().getWidth();
        int height = message.getPosition().getScreenSize().getHeight();
        int x = message.getPosition().getPoint().getX();
        int y = message.getPosition().getPoint().getY();
        return width > 0 && width <= MAX_SCREEN_DIMENSION
                && height > 0 && height <= MAX_SCREEN_DIMENSION
                && x >= 0 && x < width && y >= 0 && y < height;
    }

    private static int utf8Length(String text) {
        return text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    private static boolean isAllowedKeycode(int keycode) {
        if (keycode >= 7 && keycode <= 16) return true; // 0..9
        if (keycode >= 29 && keycode <= 54) return true; // A..Z
        switch (keycode) {
            case 3:   // HOME (system navigation)
            case 4:   // BACK
            case 19:  // DPAD_UP
            case 20:  // DPAD_DOWN
            case 21:  // DPAD_LEFT
            case 22:  // DPAD_RIGHT
            case 24:  // VOLUME_UP
            case 25:  // VOLUME_DOWN
            case 61:  // TAB
            case 66:  // ENTER
            case 67:  // DEL/backspace
            case 92:  // PAGE_UP
            case 93:  // PAGE_DOWN
            case 112: // FORWARD_DEL
            case 122: // MOVE_HOME
            case 123: // MOVE_END
            case 187: // APP_SWITCH/Recents (system navigation)
                return true;
            default:
                return false;
        }
    }

    private static final int MAX_KEY_REPEAT = 1_000;
    private static final int MAX_SCREEN_DIMENSION = 8_192;
    private static final int ALLOWED_BUTTON_MASK = 0x1f;
    private static final int ALLOWED_META_STATE_MASK = 0x000770f3;
}

package com.genymobile.scrcpy.control;

import com.genymobile.scrcpy.AndroidVersions;
import com.genymobile.scrcpy.AsyncProcessor;
import com.genymobile.scrcpy.Options;
import com.genymobile.scrcpy.device.Device;
import com.genymobile.scrcpy.model.Point;
import com.genymobile.scrcpy.model.Position;
import com.genymobile.scrcpy.model.Size;
import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.video.CaptureDisplayListener;
import com.genymobile.scrcpy.wrappers.ClipboardManager;
import com.genymobile.scrcpy.wrappers.InputManager;
import com.genymobile.scrcpy.wrappers.ServiceManager;

import android.os.Build;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * NotiSync's deliberately small scrcpy control loop.
 *
 * <p>Only direct input, primary-display power control, and text clipboard synchronization are compiled into
 * the privileged process. Generic scrcpy actions (UHID, app launch, panels, display mutation,
 * camera, file scan, and arbitrary commands) have no handler here.</p>
 */
public final class Controller implements AsyncProcessor, CaptureDisplayListener {

    private static final int DEFAULT_DEVICE_ID = 0;
    private static final int POINTER_ID_MOUSE = -1;

    private static final class DisplayData {
        private final int inputDisplayId;
        private final PositionMapper positionMapper;

        private DisplayData(int inputDisplayId, PositionMapper positionMapper) {
            this.inputDisplayId = inputDisplayId;
            this.positionMapper = positionMapper;
        }
    }

    private final int displayId;
    private final boolean supportsInputEvents;
    private final ControlChannel controlChannel;
    private final DeviceMessageSender sender;
    private final ClipboardManager clipboardManager;
    private final android.content.ClipboardManager.OnPrimaryClipChangedListener clipboardListener;
    private final KeyCharacterMap charMap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
    private final AtomicReference<DisplayData> displayData = new AtomicReference<>();
    private final Object inputStateLock = new Object();
    private final ClipboardEchoSuppressor clipboardEchoSuppressor = new ClipboardEchoSuppressor();
    private final AtomicBoolean stopped = new AtomicBoolean();

    private final PointersState pointersState = new PointersState();
    private final MotionEvent.PointerProperties[] pointerProperties = new MotionEvent.PointerProperties[PointersState.MAX_POINTERS];
    private final MotionEvent.PointerCoords[] pointerCoords = new MotionEvent.PointerCoords[PointersState.MAX_POINTERS];

    private long lastTouchDown;
    private Thread thread;

    public Controller(ControlChannel controlChannel, Options options) {
        this.controlChannel = controlChannel;
        this.displayId = options.getDisplayId();
        this.supportsInputEvents = Device.supportsInputEvents(displayId);
        initPointers();

        if (options.getClipboardAutosync()) {
            sender = new DeviceMessageSender(controlChannel);
            clipboardManager = ServiceManager.getClipboardManager();
            if (clipboardManager != null) {
                clipboardListener = this::onPrimaryClipChanged;
                clipboardManager.addPrimaryClipChangedListener(clipboardListener);
            } else {
                clipboardListener = null;
                Ln.w("No clipboard manager; clipboard synchronization is unavailable");
            }
        } else {
            sender = null;
            clipboardManager = null;
            clipboardListener = null;
        }
    }

    private void onPrimaryClipChanged() {
        if (stopped.get() || sender == null) {
            return;
        }
        String text = Device.getClipboardText();
        if (text == null) {
            return;
        }
        if (clipboardEchoSuppressor.shouldSuppress(text)) {
            return;
        }
        sender.sendClipboard(text);
    }

    @Override
    public void onCaptureDisplay(int inputDisplayId, PositionMapper positionMapper) {
        synchronized (inputStateLock) {
            DisplayData previous = displayData.get();
            if (previous != null) {
                cancelActivePointers(previous.inputDisplayId);
            }
            displayData.set(new DisplayData(inputDisplayId, positionMapper));
        }
    }

    private void cancelActivePointers(int targetDisplayId) {
        int pointerCount = pointersState.size();
        if (pointerCount == 0) {
            return;
        }
        MotionEvent cancel = null;
        try {
            pointerCount = pointersState.update(pointerProperties, pointerCoords);
            int source = pointerProperties[0].toolType == MotionEvent.TOOL_TYPE_MOUSE
                    ? InputDevice.SOURCE_MOUSE : InputDevice.SOURCE_TOUCHSCREEN;
            cancel = MotionEvent.obtain(lastTouchDown, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, pointerCount,
                    pointerProperties, pointerCoords, 0, 0, 1f, 1f, DEFAULT_DEVICE_ID, 0, source, 0);
            Device.injectEvent(cancel, targetDisplayId, Device.INJECT_MODE_ASYNC);
        } catch (RuntimeException error) {
            Ln.w("Could not inject pointer cancellation: " + error.getMessage());
        } finally {
            if (cancel != null) {
                cancel.recycle();
            }
            // Android ACTION_CANCEL terminates the complete gesture, not only the pointer id
            // carried by the scrcpy control frame.
            pointersState.clear();
        }
    }

    private void initPointers() {
        for (int i = 0; i < PointersState.MAX_POINTERS; ++i) {
            MotionEvent.PointerProperties props = new MotionEvent.PointerProperties();
            props.toolType = MotionEvent.TOOL_TYPE_FINGER;
            pointerProperties[i] = props;

            MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
            coords.orientation = 0;
            coords.size = 0;
            pointerCoords[i] = coords;
        }
    }

    @Override
    public void start(TerminationListener listener) {
        thread = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted() && handleEvent()) {
                    // Process one bounded, allowlisted message at a time.
                }
            } catch (IOException error) {
                if (!stopped.get()) {
                    Ln.e("Controller error", error);
                }
            } finally {
                listener.onTerminated(true);
            }
        }, "control-recv");
        thread.start();
        if (sender != null) {
            sender.start();
        }
    }

    @Override
    public void stop() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        synchronized (inputStateLock) {
            DisplayData current = displayData.get();
            if (current != null) {
                cancelActivePointers(current.inputDisplayId);
            } else {
                pointersState.clear();
            }
        }
        if (clipboardManager != null && clipboardListener != null) {
            try {
                clipboardManager.removePrimaryClipChangedListener(clipboardListener);
            } catch (RuntimeException error) {
                Ln.w("Could not remove clipboard listener: " + error.getMessage());
            }
        }
        controlChannel.close();
        if (thread != null) {
            thread.interrupt();
        }
        if (sender != null) {
            sender.stop();
        }
    }

    @Override
    public void join() throws InterruptedException {
        if (thread != null) {
            thread.join();
        }
        if (sender != null) {
            sender.join();
        }
    }

    private boolean handleEvent() throws IOException {
        final ControlMessage msg;
        try {
            msg = controlChannel.recv();
        } catch (ControlProtocolException error) {
            Ln.e("Control protocol error", error);
            return false;
        }

        switch (msg.getType()) {
            case ControlMessage.TYPE_INJECT_KEYCODE:
                if (supportsInputEvents) {
                    injectKeyEvent(msg.getAction(), msg.getKeycode(), msg.getRepeat(), msg.getMetaState(), Device.INJECT_MODE_ASYNC);
                }
                return true;
            case ControlMessage.TYPE_INJECT_TEXT:
                if (supportsInputEvents) {
                    injectText(msg.getText());
                }
                return true;
            case ControlMessage.TYPE_INJECT_TOUCH_EVENT:
                if (supportsInputEvents) {
                    injectTouch(msg.getAction(), msg.getPointerId(), msg.getPosition(), msg.getPressure(), msg.getActionButton(), msg.getButtons());
                }
                return true;
            case ControlMessage.TYPE_INJECT_SCROLL_EVENT:
                if (supportsInputEvents) {
                    injectScroll(msg.getPosition(), msg.getHScroll(), msg.getVScroll(), msg.getButtons());
                }
                return true;
            case ControlMessage.TYPE_BACK_OR_SCREEN_ON:
                if (supportsInputEvents) {
                    pressBackOrTurnScreenOn(msg.getAction());
                }
                return true;
            case ControlMessage.TYPE_TOGGLE_POWER:
                if (!Device.togglePrimaryDisplayPower()) {
                    Ln.w("Could not toggle primary display power");
                }
                return true;
            case ControlMessage.TYPE_GET_CLIPBOARD:
                sendClipboard();
                return true;
            case ControlMessage.TYPE_SET_CLIPBOARD:
                setClipboard(msg.getText(), msg.getSequence());
                return true;
            default:
                throw new ControlProtocolException("Unsupported NotiSync control message");
        }
    }

    private void injectText(String text) {
        for (char c : text.toCharArray()) {
            String decomposed = KeyComposition.decompose(c);
            char[] chars = decomposed != null ? decomposed.toCharArray() : new char[]{c};
            KeyEvent[] events = charMap.getEvents(chars);
            if (events == null) {
                Ln.w("Could not inject char u+" + String.format("%04x", (int) c));
                continue;
            }
            int actionDisplayId = getActionDisplayId();
            if (actionDisplayId == Device.DISPLAY_ID_NONE) {
                return;
            }
            for (KeyEvent event : events) {
                if (!Device.injectEvent(event, actionDisplayId, Device.INJECT_MODE_ASYNC)) {
                    break;
                }
            }
        }
    }

    private android.util.Pair<Point, Integer> getEventPointAndDisplayId(Position position) {
        DisplayData currentDisplay = displayData.get();
        if (currentDisplay == null) {
            return null;
        }
        Point point = currentDisplay.positionMapper.map(position);
        if (point == null) {
            if (Ln.isEnabled(Ln.Level.VERBOSE)) {
                Size eventSize = position.getScreenSize();
                Size currentSize = currentDisplay.positionMapper.getVideoSize();
                Ln.v("Ignore positional event generated for size " + eventSize + " (current size is " + currentSize + ")");
            }
            return null;
        }
        return android.util.Pair.create(point, currentDisplay.inputDisplayId);
    }

    private boolean injectTouch(int action, long pointerId, Position position, float pressure, int actionButton, int buttons) {
        synchronized (inputStateLock) {
            return injectTouchLocked(action, pointerId, position, pressure, actionButton, buttons);
        }
    }

    private boolean injectTouchLocked(int action, long pointerId, Position position, float pressure, int actionButton, int buttons) {
        android.util.Pair<Point, Integer> pair = getEventPointAndDisplayId(position);
        if (pair == null) {
            return false;
        }

        if (action == MotionEvent.ACTION_CANCEL) {
            cancelActivePointers(pair.second);
            return true;
        }

        long now = SystemClock.uptimeMillis();
        int pointerIndex;
        if (action == MotionEvent.ACTION_DOWN) {
            if (pointersState.getActivePointerIndex(pointerId) != -1) {
                // A pointer lifecycle must begin exactly once.
                return false;
            }
            pointerIndex = pointersState.getOrCreatePointerIndex(pointerId);
        } else {
            // Never synthesize a pointer from a delayed MOVE/UP received after a global cancel.
            pointerIndex = pointersState.getActivePointerIndex(pointerId);
        }
        if (pointerIndex == -1) {
            Ln.w(action == MotionEvent.ACTION_DOWN
                    ? "Too many pointers for touch event"
                    : "Ignoring orphan touch event without a fresh down");
            return false;
        }
        Pointer pointer = pointersState.get(pointerIndex);
        pointer.setPoint(pair.first);
        pointer.setPressure(pressure);

        int source;
        boolean activeSecondaryButtons = ((actionButton | buttons) & ~MotionEvent.BUTTON_PRIMARY) != 0;
        if (pointerId == POINTER_ID_MOUSE && activeSecondaryButtons) {
            pointerProperties[pointerIndex].toolType = MotionEvent.TOOL_TYPE_MOUSE;
            source = InputDevice.SOURCE_MOUSE;
            pointer.setUp(buttons == 0);
        } else {
            pointerProperties[pointerIndex].toolType = MotionEvent.TOOL_TYPE_FINGER;
            source = InputDevice.SOURCE_TOUCHSCREEN;
            buttons = 0;
            pointer.setUp(action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL);
        }

        int pointerCount = pointersState.update(pointerProperties, pointerCoords);
        if (pointerCount == 1 && action == MotionEvent.ACTION_DOWN) {
            lastTouchDown = now;
        } else if (pointerCount > 1) {
            if (action == MotionEvent.ACTION_UP) {
                action = MotionEvent.ACTION_POINTER_UP | (pointerIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
            } else if (action == MotionEvent.ACTION_DOWN) {
                action = MotionEvent.ACTION_POINTER_DOWN | (pointerIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
            }
        }

        if (Build.VERSION.SDK_INT >= AndroidVersions.API_23_ANDROID_6_0 && source == InputDevice.SOURCE_MOUSE) {
            if (action == MotionEvent.ACTION_DOWN) {
                if (actionButton == buttons && !injectMouseButtonEvent(MotionEvent.ACTION_DOWN, pair.second, now, pointerCount, buttons, source, 0)) {
                    return false;
                }
                return injectMouseButtonEvent(MotionEvent.ACTION_BUTTON_PRESS, pair.second, now, pointerCount, buttons, source, actionButton);
            }
            if (action == MotionEvent.ACTION_UP) {
                if (!injectMouseButtonEvent(MotionEvent.ACTION_BUTTON_RELEASE, pair.second, now, pointerCount, buttons, source, actionButton)) {
                    return false;
                }
                return buttons != 0 || injectMouseButtonEvent(MotionEvent.ACTION_UP, pair.second, now, pointerCount, buttons, source, 0);
            }
        }

        MotionEvent event = MotionEvent.obtain(lastTouchDown, now, action, pointerCount, pointerProperties, pointerCoords, 0, buttons, 1f, 1f,
                DEFAULT_DEVICE_ID, 0, source, 0);
        try {
            return Device.injectEvent(event, pair.second, Device.INJECT_MODE_ASYNC);
        } finally {
            event.recycle();
        }
    }

    private boolean injectMouseButtonEvent(int action, int targetDisplayId, long now, int pointerCount, int buttons, int source,
            int actionButton) {
        MotionEvent event = MotionEvent.obtain(lastTouchDown, now, action, pointerCount, pointerProperties, pointerCoords, 0, buttons, 1f, 1f,
                DEFAULT_DEVICE_ID, 0, source, 0);
        try {
            if (actionButton != 0 && !InputManager.setActionButton(event, actionButton)) {
                return false;
            }
            return Device.injectEvent(event, targetDisplayId, Device.INJECT_MODE_ASYNC);
        } finally {
            event.recycle();
        }
    }

    private boolean injectScroll(Position position, float hScroll, float vScroll, int buttons) {
        synchronized (inputStateLock) {
            return injectScrollLocked(position, hScroll, vScroll, buttons);
        }
    }

    private boolean injectScrollLocked(Position position, float hScroll, float vScroll, int buttons) {
        android.util.Pair<Point, Integer> pair = getEventPointAndDisplayId(position);
        if (pair == null) {
            return false;
        }
        long now = SystemClock.uptimeMillis();
        MotionEvent.PointerProperties props = pointerProperties[0];
        props.id = 0;
        MotionEvent.PointerCoords coords = pointerCoords[0];
        coords.x = pair.first.getX();
        coords.y = pair.first.getY();
        coords.setAxisValue(MotionEvent.AXIS_HSCROLL, hScroll);
        coords.setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll);

        MotionEvent event = MotionEvent.obtain(lastTouchDown, now, MotionEvent.ACTION_SCROLL, 1, pointerProperties, pointerCoords, 0, buttons, 1f,
                1f, DEFAULT_DEVICE_ID, 0, InputDevice.SOURCE_MOUSE, 0);
        try {
            return Device.injectEvent(event, pair.second, Device.INJECT_MODE_ASYNC);
        } finally {
            event.recycle();
        }
    }

    private boolean pressBackOrTurnScreenOn(int action) {
        int actionDisplayId = getActionDisplayId();
        boolean screenOn = actionDisplayId == Device.DISPLAY_ID_NONE || Device.isScreenOn(actionDisplayId);
        if (screenOn) {
            return injectKeyEvent(action, KeyEvent.KEYCODE_BACK, 0, 0, Device.INJECT_MODE_ASYNC);
        }
        return action != KeyEvent.ACTION_DOWN || Device.wakeUp(displayId);
    }

    private void sendClipboard() {
        if (sender == null) {
            return;
        }
        String text = Device.getClipboardText();
        if (text != null) {
            sender.sendClipboard(text);
        }
    }

    private void setClipboard(String text, long sequence) {
        long generation = clipboardEchoSuppressor.markRemoteWrite(text);
        boolean changed = Device.setClipboardText(text);
        if (!changed) {
            clipboardEchoSuppressor.cancel(generation);
        }
        if (sequence != ControlMessage.SEQUENCE_INVALID && sender != null) {
            sender.send(DeviceMessage.createAckClipboard(sequence));
        }
    }

    private boolean injectKeyEvent(int action, int keyCode, int repeat, int metaState, int injectMode) {
        int actionDisplayId = getActionDisplayId();
        return actionDisplayId != Device.DISPLAY_ID_NONE
                && Device.injectKeyEvent(action, keyCode, repeat, metaState, actionDisplayId, injectMode);
    }

    private int getActionDisplayId() {
        DisplayData data = displayData.get();
        return data == null ? Device.DISPLAY_ID_NONE : data.inputDisplayId;
    }
}

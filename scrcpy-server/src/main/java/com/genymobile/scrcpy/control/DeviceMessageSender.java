package com.genymobile.scrcpy.control;

import com.genymobile.scrcpy.util.Ln;

import java.io.IOException;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;

public final class DeviceMessageSender {

    private final ControlChannel controlChannel;

    private Thread thread;
    private final BlockingDeque<DeviceMessage> queue = new LinkedBlockingDeque<>(16);
    private final Object enqueueLock = new Object();

    public DeviceMessageSender(ControlChannel controlChannel) {
        this.controlChannel = controlChannel;
    }

    public void send(DeviceMessage msg) {
        boolean offered;
        synchronized (enqueueLock) {
            if (msg.getType() == DeviceMessage.TYPE_CLIPBOARD) {
                // Clipboard state is replaceable. Keep only the newest bounded snapshot.
                queue.removeIf(queued -> queued.getType() == DeviceMessage.TYPE_CLIPBOARD);
                offered = queue.offerLast(msg);
            } else {
                // ACKs make clipboard loop suppression prompt. If the bounded queue is full,
                // evict replaceable clipboard state first, otherwise the oldest ACK. The newest
                // sequence remains last, so reordering cannot overwrite it with an older ACK.
                if (queue.remainingCapacity() == 0) {
                    DeviceMessage replaceable = null;
                    for (DeviceMessage queued : queue) {
                        if (queued.getType() == DeviceMessage.TYPE_CLIPBOARD) {
                            replaceable = queued;
                            break;
                        }
                    }
                    if (replaceable != null) {
                        queue.remove(replaceable);
                    } else {
                        queue.pollFirst();
                    }
                }
                offered = queue.offerLast(msg);
            }
        }
        if (!offered) {
            Ln.w("Device message dropped: " + msg.getType());
        }
    }

    public void sendClipboard(String text) {
        try {
            send(DeviceMessage.createClipboard(text));
        } catch (ControlProtocolException error) {
            Ln.w("Ignoring invalid local clipboard text: " + error.getMessage());
        }
    }

    private void loop() throws IOException, InterruptedException {
        while (!Thread.currentThread().isInterrupted()) {
            DeviceMessage msg = queue.takeFirst();
            controlChannel.send(msg);
        }
    }

    public void start() {
        thread = new Thread(() -> {
            try {
                loop();
            } catch (IOException | InterruptedException e) {
                // this is expected on close
            } finally {
                Ln.d("Device message sender stopped");
            }
        }, "control-send");
        thread.start();
    }

    public void stop() {
        if (thread != null) {
            thread.interrupt();
        }
    }

    public void join() throws InterruptedException {
        if (thread != null) {
            thread.join();
        }
    }
}

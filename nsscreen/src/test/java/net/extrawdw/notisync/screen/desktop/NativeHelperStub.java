package net.extrawdw.notisync.screen.desktop;

import java.io.InputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;

/** A tiny out-of-process peer used to verify the private Unix-socket lifecycle. */
public final class NativeHelperStub {
    private NativeHelperStub() {}

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        if (mode.equals("exit")) {
            System.exit(23);
        }
        Path videoPath = Path.of(args[1]);
        Path controlPath = Path.of(args[2]);
        Path markerPath = Path.of(args[3]);
        byte[] challenge = System.in.readNBytes(32);
        if (challenge.length != 32) {
            System.exit(24);
        }
        if (mode.equals("race")) {
            try (SocketChannel wrongVideo = connect(videoPath)) {
                sendAuthentication(wrongVideo, (byte) 1, new byte[32]);
            }
            try (SocketChannel wrongControl = connect(controlPath)) {
                sendAuthentication(wrongControl, (byte) 2, new byte[32]);
            }
        }
        try (
                SocketChannel video = authenticated(videoPath, (byte) 1, challenge);
                SocketChannel control = authenticated(controlPath, (byte) 2, challenge);
                InputStream input = Channels.newInputStream(video)
        ) {
            while (input.read() >= 0) {
                // Drain until the Kotlin bridge half-closes its socket output.
            }
            Files.writeString(markerPath, "eof");
        }
    }

    private static SocketChannel connect(Path path) throws Exception {
        SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
        channel.connect(UnixDomainSocketAddress.of(path));
        return channel;
    }

    private static SocketChannel authenticated(Path path, byte channelId, byte[] challenge) throws Exception {
        SocketChannel channel = connect(path);
        sendAuthentication(channel, channelId, challenge);
        return channel;
    }

    private static void sendAuthentication(SocketChannel channel, byte channelId, byte[] challenge) throws Exception {
        ByteBuffer frame = ByteBuffer.allocate(45);
        frame.put(new byte[] { 'N', 'S', 'I', 'P' });
        frame.put(channelId);
        frame.putLong(ProcessHandle.current().pid());
        frame.put(challenge);
        frame.flip();
        while (frame.hasRemaining()) channel.write(frame);
    }
}

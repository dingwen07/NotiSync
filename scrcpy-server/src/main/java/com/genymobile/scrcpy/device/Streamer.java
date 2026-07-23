package com.genymobile.scrcpy.device;

import com.genymobile.scrcpy.model.Codec;
import com.genymobile.scrcpy.util.IO;

import android.media.MediaCodec;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;

/** Writes the pinned scrcpy v4.1 video framing to an app-owned descriptor. */
public final class Streamer {

    private static final long PACKET_FLAG_SESSION = 1L << 63;
    private static final long PACKET_FLAG_CONFIG = 1L << 62;
    private static final long PACKET_FLAG_KEY_FRAME = 1L << 61;

    private final FileDescriptor fd;
    private final Codec codec;
    private final ByteBuffer headerBuffer = ByteBuffer.allocate(12);

    public Streamer(FileDescriptor fd, Codec codec) {
        this.fd = fd;
        this.codec = codec;
    }

    public Codec getCodec() {
        return codec;
    }

    public void writeVideoHeader() throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(4);
        buffer.putInt(codec.getId());
        buffer.flip();
        IO.writeFully(fd, buffer);
    }

    public void writePacket(ByteBuffer codecBuffer, MediaCodec.BufferInfo bufferInfo) throws IOException {
        boolean config = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
        boolean keyFrame = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
        writeFrameMeta(codecBuffer.remaining(), bufferInfo.presentationTimeUs, config, keyFrame);
        IO.writeFully(fd, codecBuffer);
    }

    public void writeSessionMeta(int width, int height, boolean isClientResize) throws IOException {
        headerBuffer.clear();
        int flags = (int) (PACKET_FLAG_SESSION >> 32);
        if (isClientResize) {
            flags |= 1;
        }
        headerBuffer.putInt(flags);
        headerBuffer.putInt(width);
        headerBuffer.putInt(height);
        headerBuffer.flip();
        IO.writeFully(fd, headerBuffer);
    }

    private void writeFrameMeta(int packetSize, long pts, boolean config, boolean keyFrame) throws IOException {
        headerBuffer.clear();
        long ptsAndFlags;
        if (config) {
            ptsAndFlags = PACKET_FLAG_CONFIG;
        } else {
            ptsAndFlags = pts;
            if (keyFrame) {
                ptsAndFlags |= PACKET_FLAG_KEY_FRAME;
            }
        }
        headerBuffer.putLong(ptsAndFlags);
        headerBuffer.putInt(packetSize);
        headerBuffer.flip();
        IO.writeFully(fd, headerBuffer);
    }
}

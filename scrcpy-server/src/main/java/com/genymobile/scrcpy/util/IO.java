package com.genymobile.scrcpy.util;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;

/** Interrupt-safe writes to the app-owned video descriptor. */
public final class IO {

    private IO() {
    }

    private static int write(FileDescriptor fd, ByteBuffer from) throws IOException {
        while (true) {
            try {
                return Os.write(fd, from);
            } catch (ErrnoException error) {
                if (error.errno != OsConstants.EINTR) {
                    throw new IOException(error);
                }
            }
        }
    }

    public static void writeFully(FileDescriptor fd, ByteBuffer from) throws IOException {
        while (from.hasRemaining()) {
            int written = write(fd, from);
            if (written <= 0) {
                throw new IOException("Descriptor write made no progress");
            }
        }
    }

    public static boolean isBrokenPipe(IOException error) {
        Throwable cause = error.getCause();
        return cause instanceof ErrnoException && ((ErrnoException) cause).errno == OsConstants.EPIPE;
    }
}

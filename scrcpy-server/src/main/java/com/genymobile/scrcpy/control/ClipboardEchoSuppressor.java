package com.genymobile.scrcpy.control;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Bounded generation queue for suppressing delayed clipboard reflections.
 *
 * <p>Android may dispatch clipboard callbacks asynchronously and out of order. Each remote write
 * therefore owns a distinct marker. A callback consumes only one matching marker; unrelated
 * callbacks leave all live markers intact, and missing callbacks cannot suppress future local
 * clipboard changes forever.</p>
 */
final class ClipboardEchoSuppressor {

    private static final int MAX_PENDING = 16;
    private static final long DEFAULT_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5);

    private static final class Pending {
        private final long generation;
        private final String text;
        private final long expiresAt;

        private Pending(long generation, String text, long expiresAt) {
            this.generation = generation;
            this.text = text;
            this.expiresAt = expiresAt;
        }
    }

    private final LongSupplier clock;
    private final long timeoutNanos;
    private final AtomicLong nextGeneration = new AtomicLong();
    private final ArrayDeque<Pending> pending = new ArrayDeque<>();

    ClipboardEchoSuppressor() {
        this(System::nanoTime, DEFAULT_TIMEOUT_NANOS);
    }

    ClipboardEchoSuppressor(LongSupplier clock, long timeoutNanos) {
        this.clock = clock;
        this.timeoutNanos = timeoutNanos;
    }

    synchronized long markRemoteWrite(String text) {
        long now = clock.getAsLong();
        removeExpired(now);
        while (pending.size() >= MAX_PENDING) {
            pending.removeFirst();
        }
        long generation = nextGeneration.incrementAndGet();
        pending.addLast(new Pending(generation, text, saturatingAdd(now, timeoutNanos)));
        return generation;
    }

    synchronized void cancel(long generation) {
        for (Iterator<Pending> iterator = pending.iterator(); iterator.hasNext();) {
            if (iterator.next().generation == generation) {
                iterator.remove();
                return;
            }
        }
    }

    synchronized boolean shouldSuppress(String text) {
        long now = clock.getAsLong();
        removeExpired(now);
        for (Iterator<Pending> iterator = pending.iterator(); iterator.hasNext();) {
            Pending item = iterator.next();
            if (Objects.equals(item.text, text)) {
                iterator.remove();
                return true;
            }
        }
        return false;
    }

    private void removeExpired(long now) {
        pending.removeIf(item -> item.expiresAt - now <= 0);
    }

    private static long saturatingAdd(long value, long increment) {
        long result = value + increment;
        if (((value ^ result) & (increment ^ result)) < 0) {
            return Long.MAX_VALUE;
        }
        return result;
    }
}

package com.genymobile.scrcpy.control;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

public final class ClipboardEchoSuppressorTest {

    @Test
    public void delayedOutOfOrderCallbacksConsumeTheirOwnGenerations() {
        AtomicLong now = new AtomicLong();
        ClipboardEchoSuppressor suppressor = new ClipboardEchoSuppressor(now::get, TimeUnit.SECONDS.toNanos(5));

        suppressor.markRemoteWrite("first");
        suppressor.markRemoteWrite("second");

        assertTrue(suppressor.shouldSuppress("first"));
        assertTrue(suppressor.shouldSuppress("second"));
        assertFalse(suppressor.shouldSuppress("first"));
    }

    @Test
    public void unrelatedCallbackDoesNotClearPendingReflections() {
        AtomicLong now = new AtomicLong();
        ClipboardEchoSuppressor suppressor = new ClipboardEchoSuppressor(now::get, TimeUnit.SECONDS.toNanos(5));

        suppressor.markRemoteWrite("remote");

        assertFalse(suppressor.shouldSuppress("local"));
        assertTrue(suppressor.shouldSuppress("remote"));
    }

    @Test
    public void missingCallbackExpiresInsteadOfSuppressingForever() {
        AtomicLong now = new AtomicLong();
        ClipboardEchoSuppressor suppressor = new ClipboardEchoSuppressor(now::get, 10);
        suppressor.markRemoteWrite("same-content-later");

        now.set(11);

        assertFalse(suppressor.shouldSuppress("same-content-later"));
    }

    @Test
    public void cancellingOlderGenerationDoesNotRemoveNewerSameContent() {
        AtomicLong now = new AtomicLong();
        ClipboardEchoSuppressor suppressor = new ClipboardEchoSuppressor(now::get, 100);
        long first = suppressor.markRemoteWrite("same");
        suppressor.markRemoteWrite("same");

        suppressor.cancel(first);

        assertTrue(suppressor.shouldSuppress("same"));
    }
}

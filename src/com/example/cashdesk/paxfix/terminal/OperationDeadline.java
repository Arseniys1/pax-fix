package com.example.cashdesk.paxfix.terminal;

import java.util.concurrent.TimeUnit;

/** A monotonic deadline shared by every stage of one INPAS operation. */
final class OperationDeadline {

    private final long deadlineNanos;

    private OperationDeadline(long timeoutMillis) {
        long now = System.nanoTime();
        long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        this.deadlineNanos = now > Long.MAX_VALUE - timeoutNanos
                ? Long.MAX_VALUE
                : now + timeoutNanos;
    }

    static OperationDeadline afterMillis(long timeoutMillis) {
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("timeoutMillis must be positive");
        }
        return new OperationDeadline(timeoutMillis);
    }

    long remainingMillis(String stage) throws InpasTimeoutException {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            throw new InpasTimeoutException(stage);
        }
        return Math.max(1L, (remainingNanos + 999_999L) / 1_000_000L);
    }
}


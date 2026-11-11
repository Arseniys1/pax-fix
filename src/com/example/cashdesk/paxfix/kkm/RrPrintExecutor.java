package com.example.cashdesk.paxfix.kkm;

import com.bifit.cashdesk.desktop.common.documents.entity.PrintDocument;

import java.util.ArrayList;
import java.util.List;

/** Executes the bounded, single-pass command sequence for a registered slip. */
final class RrPrintExecutor {

    static final int WIDTH = 48;
    static final int FEED_LINES = 15;
    static final long WAIT_TIMEOUT_MILLIS = 30_000L;

    private RrPrintExecutor() {
    }

    static void execute(PrintDocument document, boolean cut, Port port) {
        execute(document, cut, port, System::nanoTime, WIDTH, FEED_LINES);
    }

    static void execute(PrintDocument document, boolean cut, Port port, NanoClock clock) {
        execute(document, cut, port, clock, WIDTH, FEED_LINES);
    }

    static void execute(
            PrintDocument document,
            boolean cut,
            Port port,
            NanoClock clock,
            int width,
            int feedLines) {
        long previousTimeout = port.getWaitTimeout();
        long deadlineNanos = clock.nanoTime() + WAIT_TIMEOUT_MILLIS * 1_000_000L;

        try {
            setRemainingWaitTimeout(port, previousTimeout, deadlineNanos, clock);
            ensureOk(port, "WaitForPrinting", port.waitForPrinting());

            for (String line : renderLines(document, width)) {
                ensureTimeRemaining(deadlineNanos, clock);
                ensureOk(port, "PrintString", port.printLine(line.isBlank() ? " " : line));
            }

            ensureTimeRemaining(deadlineNanos, clock);
            port.setFeedLines(feedLines);
            ensureOk(port, "FeedDocument", port.feedDocument());

            if (cut) {
                ensureTimeRemaining(deadlineNanos, clock);
                ensureOk(port, "CutCheck", port.cutCheck());
            }

            setRemainingWaitTimeout(port, previousTimeout, deadlineNanos, clock);
            ensureOk(port, "WaitForPrinting", port.waitForPrinting());
        } finally {
            port.setWaitTimeout(previousTimeout);
        }
    }

    private static void setRemainingWaitTimeout(
            Port port,
            long previousTimeout,
            long deadlineNanos,
            NanoClock clock) {
        long remainingNanos = deadlineNanos - clock.nanoTime();
        if (remainingNanos <= 0) {
            throw new IllegalStateException("RR Electro print deadline exceeded");
        }
        long remainingMillis = Math.max(1L, (remainingNanos + 999_999L) / 1_000_000L);
        long boundedTimeout = previousTimeout > 0
                ? Math.min(previousTimeout, remainingMillis)
                : remainingMillis;
        port.setWaitTimeout(boundedTimeout);
    }

    private static void ensureTimeRemaining(long deadlineNanos, NanoClock clock) {
        if (clock.nanoTime() >= deadlineNanos) {
            throw new IllegalStateException("RR Electro print deadline exceeded");
        }
    }

    static List<String> renderLines(PrintDocument document, int width) {
        String rendered = document.toString(width).replace("\r", "");
        String[] split = rendered.split("\n", -1);
        int size = split.length;
        while (size > 0 && split[size - 1].isEmpty()) {
            size--;
        }

        List<String> lines = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            lines.add(split[i]);
        }
        return lines;
    }

    private static void ensureOk(Port port, String operation, int result) {
        if (result != 0) {
            throw new IllegalStateException(
                    operation + " failed with RR Electro code " + result + ": "
                            + port.errorDescription());
        }
    }

    interface Port {
        long getWaitTimeout();

        void setWaitTimeout(long timeoutMillis);

        int waitForPrinting();

        int printLine(String line);

        void setFeedLines(int lines);

        int feedDocument();

        int cutCheck();

        String errorDescription();
    }

    interface NanoClock {
        long nanoTime();
    }
}

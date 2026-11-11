package com.example.cashdesk.paxfix.kkm;

import com.bifit.cashdesk.desktop.common.documents.entity.PrintDocument;
import com.bifit.cashdesk.desktop.core.utils.PrintDocumentUtils;
import com.example.cashdesk.paxfix.shared.PaxSlipRegistry;

import java.util.ArrayList;
import java.util.List;

/** Exercises the real BIFIT string-to-PrintDocument conversion path. */
public final class SlipPipelineIntegrationTest {

    public static void main(String[] args) {
        String slip = "              ОПЛАТА             \r\n"
                + "ТЕРМИНАЛ: 123456  ЧЕК: 000001\r\n"
                + "СУММА:                    100.00\r\n"
                + "             ОДОБРЕНО            ";

        PaxSlipRegistry.registerSlip(slip);
        PrintDocument document = PrintDocumentUtils.convertFromString(slip);

        require(document.getLines() != null && !document.getLines().isEmpty(),
                "BIFIT must create a structured PrintDocument");
        require(PaxSlipRegistry.consume(document), "registered slip must be recognized");

        FakePort port = new FakePort();
        FakeClock clock = new FakeClock();
        port.clock = clock;
        port.firstWaitAdvanceNanos = 10_000_000_000L;
        RrPrintExecutor.execute(document, true, port, clock);

        require(port.calls.get(0).equals("timeout:30000"), "bounded timeout must be set first");
        require(port.calls.get(1).equals("wait"), "initial wait");
        require(port.calls.contains("feed:15"), "15-line feed");
        require(port.calls.contains("cut"), "cut command");
        require(port.calls.get(port.calls.size() - 3).equals("timeout:20000"),
                "final wait must receive only the remaining total deadline");
        require(port.calls.get(port.calls.size() - 2).equals("wait"), "final wait");
        require(port.calls.get(port.calls.size() - 1).equals("timeout:300000"),
                "driver timeout must be restored");

        int feedIndex = port.calls.indexOf("feed:15");
        int cutIndex = port.calls.indexOf("cut");
        require(feedIndex > 1 && cutIndex == feedIndex + 1, "single Feed(15) then Cut sequence");
        require(port.calls.stream().noneMatch(call -> call.equals("feed:4")), "no Feed(4)");

        String amountLine = port.printed.stream()
                .filter(line -> line.contains("СУММА:"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("amount line missing"));
        require(amountLine.length() == 48, "amount line must be rendered at 48 columns");
        require(amountLine.endsWith("100.00"), "right column must reach the 48-column edge");

        PrintDocument ordinary = new PrintDocument().addLeftText("ОБЫЧНЫЙ ЧЕК");
        require(!PaxSlipRegistry.consume(ordinary), "ordinary document must not be intercepted");

        FakePort failingPort = new FakePort();
        failingPort.printResult = 77;
        FakeClock failingClock = new FakeClock();
        failingPort.clock = failingClock;
        boolean failed = false;
        try {
            RrPrintExecutor.execute(document, true, failingPort, failingClock);
        } catch (IllegalStateException expected) {
            failed = true;
        }
        require(failed, "driver error must be surfaced");
        require(failingPort.timeout == 300_000L, "timeout must be restored after an error");
        require(failingPort.calls.get(failingPort.calls.size() - 1).equals("timeout:300000"),
                "timeout restoration must be the final failure-path command");

        System.out.println("SlipPipelineIntegrationTest: OK");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class FakePort implements RrPrintExecutor.Port {
        private final List<String> calls = new ArrayList<>();
        private final List<String> printed = new ArrayList<>();
        private long timeout = 300_000L;
        private int printResult;
        private FakeClock clock;
        private long firstWaitAdvanceNanos;
        private int waitCount;

        @Override
        public long getWaitTimeout() {
            return timeout;
        }

        @Override
        public void setWaitTimeout(long timeoutMillis) {
            timeout = timeoutMillis;
            calls.add("timeout:" + timeoutMillis);
        }

        @Override
        public int waitForPrinting() {
            calls.add("wait");
            if (waitCount++ == 0 && clock != null) {
                clock.nowNanos += firstWaitAdvanceNanos;
            }
            return 0;
        }

        @Override
        public int printLine(String line) {
            printed.add(line);
            calls.add("print");
            return printResult;
        }

        @Override
        public void setFeedLines(int lines) {
            calls.add("feed:" + lines);
        }

        @Override
        public int feedDocument() {
            return 0;
        }

        @Override
        public int cutCheck() {
            calls.add("cut");
            return 0;
        }

        @Override
        public String errorDescription() {
            return "test error";
        }
    }

    private static final class FakeClock implements RrPrintExecutor.NanoClock {
        private long nowNanos;

        @Override
        public long nanoTime() {
            return nowNanos;
        }
    }
}

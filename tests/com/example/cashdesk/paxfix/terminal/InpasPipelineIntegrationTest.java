package com.example.cashdesk.paxfix.terminal;

import com.bifit.cashdesk.desktop.common.DeviceSetting;
import com.bifit.cashdesk.desktop.common.OperationStatus;
import com.bifit.cashdesk.desktop.common.terminal.OnInteraction;
import com.bifit.cashdesk.desktop.common.terminal.PaymentParameters;
import com.bifit.cashdesk.desktop.common.terminal.TransactionInfo;
import com.bifit.cashdesk.desktop.core.utils.PrintDocumentUtils;
import com.example.cashdesk.paxfix.shared.PaxSlipRegistry;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;

/** Exercises connect, command, result, receipt and slip registration together. */
public final class InpasPipelineIntegrationTest {

    private static final Charset INPAS_CHARSET = Charset.forName("windows-1251");

    public static void main(String[] args) throws Exception {
        if (!System.getProperty("os.name").startsWith("Windows")) {
            System.out.println("InpasPipelineIntegrationTest: SKIPPED (Windows-only plugin)");
            return;
        }

        Path directory = Files.createTempDirectory("pax-fix inpas ");
        try {
            createLauncher(directory);
            DeviceSetting setting = DeviceSetting.builder()
                    .settings(Map.of(
                            "path", directory.toString(),
                            "tid", "TEST-TID",
                            "operationTimeoutSeconds", "30"))
                    .build();

            InpasPaxFix terminal = new InpasPaxFix();
            terminal.connect(setting);
            require(terminal.checkConnection(), "fake INPAS connection check");

            PaymentParameters parameters = new PaymentParameters(
                    null,
                    "test-payment",
                    null,
                    new BigDecimal("100.00"));
            TransactionInfo result = terminal.payment(parameters, new SilentInteraction());

            require(result.getStatus() == OperationStatus.SUCCESS, "payment status");
            require("TEST-TRANSACTION".equals(result.getTransactionId()), "transaction id");
            require(result.getSlips() != null && result.getSlips().size() == 1, "one slip");
            require(result.getSlips().get(0).contains("ОПЛАТА"), "receipt text");
            require(PaxSlipRegistry.consume(
                            PrintDocumentUtils.convertFromString(result.getSlips().get(0))),
                    "returned INPAS slip must be registered for RR Electro");
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }

        System.out.println("InpasPipelineIntegrationTest: OK");
    }

    private static void createLauncher(Path directory) throws Exception {
        String launcher = "@echo off\r\n"
                + quote(javaExecutable())
                + " -cp "
                + quote(System.getProperty("java.class.path"))
                + " "
                + FakeInpasNative.class.getName()
                + " "
                + quote(directory.toString())
                + " %*\r\n"
                + "exit /b %ERRORLEVEL%\r\n";
        Files.writeString(
                directory.resolve("DCConsole.bat"),
                launcher,
                StandardCharsets.US_ASCII);
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
    }

    private static String quote(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class SilentInteraction extends OnInteraction {
        @Override
        public void showMessage(String message) {
            // The integration test has no UI.
        }
    }

    public static final class FakeInpasNative {

        private FakeInpasNative() {
        }

        public static void main(String[] args) throws Exception {
            Path directory = Path.of(args[0]);
            String operationCode = "26";
            for (String argument : args) {
                if (argument.startsWith("-o")) {
                    operationCode = argument.substring(2);
                }
            }

            String result = "[25] = '" + operationCode + "'\r\n"
                    + "[39] = '1'\r\n"
                    + "[14] = 'TEST-TRANSACTION'\r\n";
            Files.writeString(directory.resolve("result.txt"), result, INPAS_CHARSET);
            Files.writeString(
                    directory.resolve("receipt.txt"),
                    "ОПЛАТА\r\nСУММА:                    100.00\r\n",
                    INPAS_CHARSET);
        }
    }
}

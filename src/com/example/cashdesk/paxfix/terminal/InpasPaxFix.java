package com.example.cashdesk.paxfix.terminal;

import com.bifit.cashdesk.desktop.common.DeviceSetting;
import com.bifit.cashdesk.desktop.common.MathUtils;
import com.bifit.cashdesk.desktop.common.OS;
import com.bifit.cashdesk.desktop.common.OperationStatus;
import com.bifit.cashdesk.desktop.common.ParamInfo;
import com.bifit.cashdesk.desktop.common.http.receipt.CardTerminalProvider;
import com.bifit.cashdesk.desktop.common.terminal.OnInteraction;
import com.bifit.cashdesk.desktop.common.terminal.PaymentParameters;
import com.bifit.cashdesk.desktop.common.terminal.TransactionInfo;
import com.bifit.cashdesk.desktop.plugin.terminal.inpas.InpasTerminalProtocol;
import com.bifit.cashdesk.desktop.plugin.terminal.inpas.enums.InpasNativeCommands;
import com.bifit.cashdesk.desktop.plugin.terminal.inpas.enums.Report;
import com.bifit.cashdesk.desktop.utils.FileUtils;
import com.example.cashdesk.paxfix.shared.PaxSlipRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * INPAS adapter with a bounded external process and bounded result-file waits.
 * Stock INPAS settings, command templates and result format remain in use.
 */
public final class InpasPaxFix extends InpasTerminalProtocol {

    private static final Logger LOG = LoggerFactory.getLogger(InpasPaxFix.class);
    private static final Charset INPAS_CHARSET = Charset.forName("windows-1251");
    private static final String TIMEOUT_KEY = "operationTimeoutSeconds";
    private static final int DEFAULT_TIMEOUT_SECONDS = 180;
    private static final int MIN_TIMEOUT_SECONDS = 30;
    private static final int MAX_TIMEOUT_SECONDS = 600;
    private static final long CONNECTION_TIMEOUT_MILLIS = 30_000L;

    private static final String OPERATION_ERROR = "Ошибка проведения операции";
    private static final String UNKNOWN_RESULT =
            "Результат операции INPAS неизвестен: превышено время ожидания. "
                    + "Не повторяйте оплату или возврат; проверьте результат на терминале "
                    + "и выполните сверку итогов.";

    private String terminalIdentifier;
    private String scriptFileName;
    private Path nativeDirectory;
    private Path resultFile;
    private Path receiptFile;
    private long operationTimeoutMillis = DEFAULT_TIMEOUT_SECONDS * 1_000L;

    @Override
    public void connect(DeviceSetting deviceSetting) {
        super.connect(deviceSetting);

        Map<String, String> settings = deviceSetting.getSettings();
        terminalIdentifier = settings.get("tid");
        String path = OS.isNix() ? FileUtils.appHomePath() : settings.get("path");
        nativeDirectory = Path.of(path);
        resultFile = nativeDirectory.resolve("result.txt");
        receiptFile = nativeDirectory.resolve("receipt.txt");
        scriptFileName = resolveScriptFileName();
        operationTimeoutMillis = parseTimeoutMillis(settings.get(TIMEOUT_KEY));
    }

    @Override
    public List<ParamInfo> connectionParameters() {
        List<ParamInfo> parameters = new ArrayList<>(super.connectionParameters());
        parameters.add(ParamInfo.simpleInteger(
                "Общий таймаут операции INPAS, секунд (30–600)",
                TIMEOUT_KEY,
                DEFAULT_TIMEOUT_SECONDS,
                false));
        return parameters;
    }

    @Override
    public synchronized boolean checkConnection() {
        try {
            OperationDeadline deadline = OperationDeadline.afterMillis(CONNECTION_TIMEOUT_MILLIS);
            Map<Integer, String> result = execute(
                    InpasNativeCommands.CHECK_CONNECTION,
                    deadline);
            return findError(result, deadline) == null;
        } catch (Exception exception) {
            LOG.error("INPAS connection check failed", exception);
            return false;
        }
    }

    @Override
    public synchronized TransactionInfo payment(
            PaymentParameters parameters,
            OnInteraction interaction) {
        interaction.showMessage("Идёт оплата, следуйте инструкциям на терминале");
        try {
            OperationDeadline deadline = operationDeadline();
            Map<Integer, String> result = execute(
                    InpasNativeCommands.PAYMENT,
                    deadline,
                    MathUtils.toCents(parameters.getAmount()));
            return transactionResult(result, deadline, true);
        } catch (InpasTimeoutException exception) {
            return timeoutResult(exception);
        } catch (Exception exception) {
            return failureResult(exception);
        }
    }

    @Override
    public synchronized TransactionInfo reverse(
            PaymentParameters parameters,
            TransactionInfo transaction,
            OnInteraction interaction) {
        interaction.showMessage("Возврат платежа, следуйте инструкциям на терминале");
        try {
            OperationDeadline deadline = operationDeadline();
            Map<Integer, String> result = execute(
                    InpasNativeCommands.REFUND,
                    deadline,
                    MathUtils.toCents(parameters.getAmount()),
                    transaction.getTransactionId());
            return transactionResult(result, deadline, true);
        } catch (InpasTimeoutException exception) {
            return timeoutResult(exception);
        } catch (Exception exception) {
            return failureResult(exception);
        }
    }

    @Override
    public synchronized TransactionInfo abort(
            PaymentParameters parameters,
            TransactionInfo transaction,
            OnInteraction interaction) {
        interaction.interact(ParamInfo.simpleConfirm(
                "Убедитесь, что карта извлечена из пинпада",
                "confirm"));
        interaction.showMessage("Аварийная отмена оплаты, пожалуйста, подождите");
        try {
            OperationDeadline deadline = operationDeadline();
            Map<Integer, String> result = execute(
                    InpasNativeCommands.EMERGENCY_CANCEL,
                    deadline,
                    MathUtils.toCents(parameters.getAmount()));
            return transactionResult(result, deadline, false);
        } catch (InpasTimeoutException exception) {
            return timeoutResult(exception);
        } catch (Exception exception) {
            return failureResult(exception);
        }
    }

    @Override
    public TransactionInfo commit(
            PaymentParameters parameters,
            TransactionInfo transaction,
            OnInteraction interaction) {
        return new TransactionInfo(OperationStatus.SUCCESS);
    }

    @Override
    public synchronized TransactionInfo closeDay() {
        try {
            OperationDeadline deadline = operationDeadline();
            Map<Integer, String> result = execute(
                    InpasNativeCommands.RECONCILIATION,
                    deadline);
            String error = findError(result, deadline);
            if (OPERATION_ERROR.equalsIgnoreCase(error)) {
                error = "Нет операций для сверки";
            }
            return error == null
                    ? successResult(result, deadline, false)
                    : failureResult(error);
        } catch (InpasTimeoutException exception) {
            return timeoutResult(exception);
        } catch (Exception exception) {
            return failureResult(exception);
        }
    }

    @Override
    public synchronized TransactionInfo report(
            String reportId,
            Map<String, String> parameters) {
        try {
            InpasNativeCommands command = switch (Report.valueOf(reportId)) {
                case SHORT_REPORT -> InpasNativeCommands.SHORT_REPORT;
                case FULL_REPORT -> InpasNativeCommands.FULL_REPORT;
            };
            OperationDeadline deadline = operationDeadline();
            Map<Integer, String> result = execute(command, deadline);
            String error = findError(result, deadline);
            if (OPERATION_ERROR.equalsIgnoreCase(error)) {
                error = "Нет операций для сверки";
            }
            return error == null
                    ? successResult(result, deadline, false)
                    : failureResult(error);
        } catch (InpasTimeoutException exception) {
            return timeoutResult(exception);
        } catch (Exception exception) {
            return failureResult(exception);
        }
    }

    private TransactionInfo transactionResult(
            Map<Integer, String> result,
            OperationDeadline deadline,
            boolean includeTransactionId)
            throws IOException, InterruptedException, InpasTimeoutException {
        String error = findError(result, deadline);
        return error == null
                ? successResult(result, deadline, includeTransactionId)
                : failureResult(error);
    }

    private TransactionInfo successResult(
            Map<Integer, String> result,
            OperationDeadline deadline,
            boolean includeTransactionId)
            throws IOException, InterruptedException, InpasTimeoutException {
        List<String> slips = readSlips(deadline);
        TransactionInfo transaction = new TransactionInfo();
        if (includeTransactionId) {
            transaction.setTransactionId(result.getOrDefault(14, "UNKNOWN"));
        }
        transaction.setStatus(OperationStatus.SUCCESS);
        transaction.setSlips(slips);
        transaction.setCardTerminalProvider(CardTerminalProvider.INPAS);
        registerSlips(slips);
        return transaction;
    }

    private Map<Integer, String> execute(
            InpasNativeCommands command,
            OperationDeadline deadline,
            Object... arguments)
            throws IOException, InterruptedException, InpasTimeoutException {
        Files.deleteIfExists(receiptFile);
        Files.deleteIfExists(resultFile);

        List<String> commandLine = command.generateCommandTemplate(
                scriptFileName,
                terminalIdentifier,
                arguments);
        LOG.info("Вызов команды INPAS: {}", String.join(" ", commandLine));

        BoundedProcessRunner.Result process = BoundedProcessRunner.run(
                commandLine,
                nativeDirectory,
                deadline);
        if (!process.stdout().isBlank()) {
            LOG.info(process.stdout());
        }
        if (!process.stderr().isBlank()) {
            LOG.error(process.stderr());
        }
        if (process.exitCode() != 0) {
            LOG.warn("INPAS process exited with code {}", process.exitCode());
        }

        return readResult(deadline);
    }

    private Map<Integer, String> readResult(OperationDeadline deadline)
            throws IOException, InterruptedException, InpasTimeoutException {
        awaitFile(resultFile, deadline, "файл result.txt");
        Map<Integer, String> values = new HashMap<>();
        for (String line : Files.readAllLines(resultFile, INPAS_CHARSET)) {
            String[] parts = line.split(" = ", 2);
            if (parts.length != 2) {
                continue;
            }
            int key = Integer.parseInt(parts[0].replace("[", "").replace("]", ""));
            values.put(key, parts[1].replace("'", ""));
        }
        return values;
    }

    private String findError(
            Map<Integer, String> result,
            OperationDeadline deadline)
            throws InterruptedException, InpasTimeoutException {
        try {
            InpasNativeCommands actualCommand = InpasNativeCommands.findByCode(
                    Integer.parseInt(required(result, 25)));
            int status = Integer.parseInt(required(result, 39));
            return switch (actualCommand) {
                case PAYMENT, REFUND, CANCEL -> status == 1
                        ? null
                        : result.getOrDefault(19, OPERATION_ERROR);
                case EMERGENCY_CANCEL -> null;
                case RECONCILIATION, SHORT_REPORT, FULL_REPORT, CHECK_CONNECTION -> {
                    if (status == 1) {
                        awaitFile(receiptFile, deadline, "файл receipt.txt");
                        yield null;
                    }
                    yield OPERATION_ERROR;
                }
                default -> "Неизвестная ошибка INPAS";
            };
        } catch (InpasTimeoutException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        } catch (Exception exception) {
            LOG.error("Unable to parse INPAS result", exception);
            return "Нет связи с пинпадом, проверьте подключение";
        }
    }

    private List<String> readSlips(OperationDeadline deadline)
            throws IOException, InterruptedException, InpasTimeoutException {
        awaitFile(receiptFile, deadline, "файл receipt.txt");
        String receipt = Files.readString(receiptFile, INPAS_CHARSET);
        LOG.info("Содержимое receipt.txt:\n{}", receipt);
        return Arrays.stream(receipt.split("0xDA|0xDF|~|\\^"))
                .filter(InpasPaxFix::containsCyrillic)
                .map(value -> OS.isWin() ? value : value.replace("\r", "\n"))
                .toList();
    }

    private static void awaitFile(
            Path file,
            OperationDeadline deadline,
            String stage)
            throws InterruptedException, InpasTimeoutException {
        while (!Files.exists(file)) {
            long remaining = deadline.remainingMillis(stage);
            Thread.sleep(Math.min(100L, remaining));
        }
    }

    private OperationDeadline operationDeadline() {
        return OperationDeadline.afterMillis(operationTimeoutMillis);
    }

    private String resolveScriptFileName() {
        if (OS.isNix()) {
            return nativeDirectory.resolve("inpas_script").toString();
        }
        if (Files.exists(nativeDirectory.resolve("DC Console.exe"))) {
            return nativeDirectory.resolve("DC Console.exe").toString();
        }
        if (Files.exists(nativeDirectory.resolve("DCConsole.bat"))) {
            return nativeDirectory.resolve("DCConsole.bat").toString();
        }
        throw new IllegalStateException(
                "В директории с ПО INPAS не обнаружен исполняемый файл");
    }

    private static long parseTimeoutMillis(String configured) {
        if (configured == null || configured.isBlank()) {
            return DEFAULT_TIMEOUT_SECONDS * 1_000L;
        }
        try {
            int seconds = Integer.parseInt(configured.trim());
            if (seconds < MIN_TIMEOUT_SECONDS || seconds > MAX_TIMEOUT_SECONDS) {
                LOG.warn("Таймаут INPAS {} вне диапазона {}-{}, используется {}",
                        seconds, MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS, DEFAULT_TIMEOUT_SECONDS);
                return DEFAULT_TIMEOUT_SECONDS * 1_000L;
            }
            return seconds * 1_000L;
        } catch (NumberFormatException error) {
            LOG.warn("Таймаут INPAS '{}' не число, используется {}", configured, DEFAULT_TIMEOUT_SECONDS);
            return DEFAULT_TIMEOUT_SECONDS * 1_000L;
        }
    }

    private static String required(Map<Integer, String> result, int key) {
        String value = result.get(key);
        if (value == null) {
            throw new IllegalArgumentException("INPAS result field is missing: " + key);
        }
        return value;
    }

    private static boolean containsCyrillic(String value) {
        return value.chars()
                .mapToObj(Character.UnicodeBlock::of)
                .anyMatch(Character.UnicodeBlock.CYRILLIC::equals);
    }

    private static void registerSlips(List<String> slips) {
        for (String slip : slips) {
            PaxSlipRegistry.registerSlip(slip);
        }
    }

    private static TransactionInfo timeoutResult(InpasTimeoutException exception) {
        LOG.error(UNKNOWN_RESULT, exception);
        return failureResult(UNKNOWN_RESULT + " " + exception.getMessage());
    }

    private static TransactionInfo failureResult(Exception exception) {
        LOG.error("INPAS operation failed", exception);
        if (exception instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return failureResult(
                    UNKNOWN_RESULT + " Ожидание операции было прервано.");
        }
        String message = exception.getMessage();
        return failureResult(message == null || message.isBlank()
                ? "Ошибка INPAS без описания"
                : message);
    }

    private static TransactionInfo failureResult(String error) {
        TransactionInfo result = new TransactionInfo();
        result.setError(error);
        result.setStatus(OperationStatus.FAIL);
        return result;
    }
}


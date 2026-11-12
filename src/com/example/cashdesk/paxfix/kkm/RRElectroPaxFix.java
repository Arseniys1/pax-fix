package com.example.cashdesk.paxfix.kkm;

import com.bifit.cashdesk.desktop.common.DeviceSetting;
import com.bifit.cashdesk.desktop.common.ParamInfo;
import com.bifit.cashdesk.desktop.common.documents.entity.PrintDocument;
import com.bifit.cashdesk.desktop.plugin.kkm.rr_electro.RRElectro;
import com.example.cashdesk.paxfix.shared.PaxSlipRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.shtrih_m.fr_drv_ng.classic_interface.classic_interface;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 80 mm printing profile for RR Electro.
 *
 * <p>Only a document registered by {@code InpasPaxFix} uses the custom print
 * path. Every other document is delegated byte-for-byte to the stock driver.</p>
 */
public final class RRElectroPaxFix extends RRElectro {

    static final String WIDTH_KEY = "printWidth";
    static final String FEED_KEY = "feedLines";

    private static final Logger log = LoggerFactory.getLogger(RRElectroPaxFix.class);

    private static final int DEFAULT_WIDTH = 48;
    private static final int DEFAULT_FEED_LINES = 15;
    private static final int MIN_WIDTH = 20;
    private static final int MAX_WIDTH = 80;
    private static final int MIN_FEED = 0;
    private static final int MAX_FEED = 40;

    private static volatile Field driverField;
    private static volatile boolean driverFieldMissing;

    private int printWidth = DEFAULT_WIDTH;
    private int feedLines = DEFAULT_FEED_LINES;

    @Override
    public void connect(DeviceSetting deviceSetting) {
        super.connect(deviceSetting);
        Map<String, String> settings = deviceSetting == null ? Map.of() : deviceSetting.getSettings();
        printWidth = intSetting(settings, WIDTH_KEY, DEFAULT_WIDTH, MIN_WIDTH, MAX_WIDTH,
                "Ширина печати слипа");
        feedLines = intSetting(settings, FEED_KEY, DEFAULT_FEED_LINES, MIN_FEED, MAX_FEED,
                "Протяжка после слипа");
    }

    @Override
    public List<ParamInfo> connectionParameters() {
        List<ParamInfo> parameters = new ArrayList<>(super.connectionParameters());
        parameters.add(ParamInfo.simpleInteger(
                "Ширина печати слипа, символов (20–80)", WIDTH_KEY, DEFAULT_WIDTH, false));
        parameters.add(ParamInfo.simpleInteger(
                "Протяжка после слипа, строк (0–40)", FEED_KEY, DEFAULT_FEED_LINES, false));
        return parameters;
    }

    @Override
    public boolean print(PrintDocument document, boolean cut, Integer width) {
        if (document == null) {
            return false;
        }

        if (!PaxSlipRegistry.consume(document)) {
            return super.print(document, cut, width);
        }

        Field field = driverField();
        if (field == null) {
            // Внутреннее поле драйвера недоступно (например, после обновления
            // rr-electro). Печатаем штатно, без падения и без обрезки.
            return super.print(document, cut, width);
        }

        try {
            RrPrintExecutor.execute(document, cut, new ClassicPort(driver(field)),
                    System::nanoTime, printWidth, feedLines);
            return true;
        } catch (Exception error) {
            // Не ставим слип обратно в очередь: часть строк уже могла уйти в
            // ККТ, и автоматический повтор создал бы дубликат чека.
            log.error("PAX slip printing failed", error);
            return false;
        }
    }

    private classic_interface driver(Field field) throws IllegalAccessException {
        Object value = field.get(this);
        if (!(value instanceof classic_interface)) {
            throw new IllegalStateException("RR Electro driver is not connected");
        }
        return (classic_interface) value;
    }

    private static Field driverField() {
        Field field = driverField;
        if (field != null || driverFieldMissing) {
            return field;
        }
        synchronized (RRElectroPaxFix.class) {
            if (driverField != null || driverFieldMissing) {
                return driverField;
            }
            try {
                Field resolved = RRElectro.class.getDeclaredField("driver");
                resolved.setAccessible(true);
                driverField = resolved;
                return resolved;
            } catch (Throwable error) {
                driverFieldMissing = true;
                log.error("RR Electro internal field is unavailable; PAX slip fix is disabled", error);
                return null;
            }
        }
    }

    private static int intSetting(
            Map<String, String> settings,
            String key,
            int defaultValue,
            int min,
            int max,
            String title) {
        String raw = settings.get(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < min || value > max) {
                log.warn("{}: {} вне диапазона {}-{}, используется {}",
                        title, value, min, max, defaultValue);
                return defaultValue;
            }
            return value;
        } catch (NumberFormatException error) {
            log.warn("{}: '{}' не число, используется {}", title, raw, defaultValue);
            return defaultValue;
        }
    }

    private static final class ClassicPort implements RrPrintExecutor.Port {
        private final classic_interface driver;

        private ClassicPort(classic_interface driver) {
            this.driver = driver;
        }

        @Override
        public long getWaitTimeout() {
            return driver.Get_WaitForPrintingTimeout();
        }

        @Override
        public void setWaitTimeout(long timeoutMillis) {
            driver.Set_WaitForPrintingTimeout(timeoutMillis);
        }

        @Override
        public boolean getUseCommandTimeout() {
            return driver.Get_UseCommandTimeout();
        }

        @Override
        public void setUseCommandTimeout(boolean enabled) {
            driver.Set_UseCommandTimeout(enabled);
        }

        @Override
        public int getCommandTimeout() {
            return driver.Get_CommandTimeout();
        }

        @Override
        public void setCommandTimeout(int timeoutMillis) {
            driver.Set_CommandTimeout(timeoutMillis);
        }

        @Override
        public int waitForPrinting() {
            return driver.WaitForPrinting();
        }

        @Override
        public int printLine(String line) {
            driver.Set_StringForPrinting(line);
            return driver.PrintString();
        }

        @Override
        public void setFeedLines(int lines) {
            driver.Set_StringQuantity(lines);
        }

        @Override
        public int feedDocument() {
            return driver.FeedDocument();
        }

        @Override
        public int cutCheck() {
            return driver.CutCheck();
        }

        @Override
        public String errorDescription() {
            return driver.Get_ErrorDescription();
        }
    }
}

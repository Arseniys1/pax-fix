import com.bifit.cashdesk.desktop.common.ParamInfo;
import com.bifit.cashdesk.desktop.common.PluginInfo;
import com.bifit.cashdesk.desktop.common.kkm.KkmDriver;
import com.bifit.cashdesk.desktop.common.terminal.PaymentTerminal;
import com.bifit.cashdesk.desktop.retail.view.dialog.receipt.TerminalOperationFragment;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.ServiceLoader;

public final class PluginSmokeTest {

    public static void main(String[] args) throws IOException {
        KkmDriver kkm = ServiceLoader.load(KkmDriver.class).stream()
                .map(ServiceLoader.Provider::get)
                .filter(value -> value.getClass().getName().contains("RRElectroPaxFix"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("RRElectroPaxFix provider not found"));

        PaymentTerminal terminal = ServiceLoader.load(PaymentTerminal.class).stream()
                .map(ServiceLoader.Provider::get)
                .filter(value -> value.getClass().getName().contains("InpasPaxFix"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("InpasPaxFix provider not found"));

        require(
                "com.example.cashdesk.paxfix.kkm".equals(kkm.driverInfo().getArtifactId()),
                "KKM artifactId");
        PluginInfo terminalInfo = terminal.driverInfo();
        require(
                "com.example.cashdesk.paxfix.terminal".equals(terminalInfo.getArtifactId()),
                "terminal artifactId");

        require(hasParam(kkm.connectionParameters(), "printWidth"), "KKM printWidth parameter");
        require(hasParam(kkm.connectionParameters(), "feedLines"), "KKM feedLines parameter");
        require(
                !hasParam(terminal.connectionParameters(), "operationTimeoutSeconds"),
                "INPAS bridge must not add terminal timeout settings");
        requireResource(terminalInfo.getImage(), "terminal logo");
        requireResource(terminalInfo.getReceiptImage(), "terminal receipt image");

        System.out.println("PluginSmokeTest: OK");
    }

    private static boolean hasParam(List<ParamInfo> parameters, String key) {
        return parameters.stream().anyMatch(parameter -> key.equals(parameter.getKey()));
    }

    private static void requireResource(String path, String title) throws IOException {
        require(path != null && !path.isBlank(), title + " path");
        try (InputStream stream = TerminalOperationFragment.class.getResourceAsStream(path)) {
            require(stream != null, title + " resource");
            require(stream.read() >= 0, title + " must not be empty");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}

import com.bifit.cashdesk.desktop.common.ParamInfo;
import com.bifit.cashdesk.desktop.common.kkm.KkmDriver;
import com.bifit.cashdesk.desktop.common.terminal.PaymentTerminal;

import java.util.List;
import java.util.ServiceLoader;

public final class PluginSmokeTest {

    public static void main(String[] args) {
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
        require(
                "com.example.cashdesk.paxfix.terminal".equals(terminal.driverInfo().getArtifactId()),
                "terminal artifactId");

        require(hasParam(kkm.connectionParameters(), "printWidth"), "KKM printWidth parameter");
        require(hasParam(kkm.connectionParameters(), "feedLines"), "KKM feedLines parameter");
        require(
                hasParam(terminal.connectionParameters(), "operationTimeoutSeconds"),
                "INPAS timeout parameter");

        System.out.println("PluginSmokeTest: OK");
    }

    private static boolean hasParam(List<ParamInfo> parameters, String key) {
        return parameters.stream().anyMatch(parameter -> key.equals(parameter.getKey()));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}

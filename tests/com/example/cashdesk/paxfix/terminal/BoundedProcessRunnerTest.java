package com.example.cashdesk.paxfix.terminal;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Verifies that timeout returns promptly and stops a spawned child process. */
public final class BoundedProcessRunnerTest {

    public static void main(String[] args) throws Exception {
        Path pidFile = Files.createTempFile("pax-fix-child-", ".pid");
        Files.deleteIfExists(pidFile);

        long started = System.nanoTime();
        boolean timedOut = false;
        long childPid = -1L;
        try {
            List<String> command = List.of(
                    javaExecutable(),
                    "-cp",
                    System.getProperty("java.class.path"),
                    SleepProcess.class.getName(),
                    "parent",
                    pidFile.toString());
            try {
                BoundedProcessRunner.run(
                        command,
                        Path.of(System.getProperty("java.io.tmpdir")),
                        OperationDeadline.afterMillis(750L));
            } catch (InpasTimeoutException expected) {
                timedOut = true;
            }

            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            require(timedOut, "sleeping process must time out");
            require(elapsedMillis < 5_000L, "timeout path must return promptly");
            require(Files.exists(pidFile), "helper must publish the child PID");

            childPid = Long.parseLong(Files.readString(pidFile, StandardCharsets.UTF_8));
            waitUntilStopped(childPid, 3_000L);
            require(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false) == false,
                    "descendant process must be stopped");
        } finally {
            if (childPid > 0) {
                ProcessHandle.of(childPid).ifPresent(ProcessHandle::destroyForcibly);
            }
            Files.deleteIfExists(pidFile);
        }

        System.out.println("BoundedProcessRunnerTest: OK");
    }

    private static void waitUntilStopped(long pid, long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)
                && System.nanoTime() < deadline) {
            Thread.sleep(25L);
        }
    }

    private static String javaExecutable() {
        String name = System.getProperty("os.name").startsWith("Windows")
                ? "java.exe"
                : "java";
        return Path.of(System.getProperty("java.home"), "bin", name).toString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static final class SleepProcess {

        private SleepProcess() {
        }

        public static void main(String[] args) throws Exception {
            if (args.length > 0 && "child".equals(args[0])) {
                Thread.sleep(60_000L);
                return;
            }

            Process child = new ProcessBuilder(
                    javaExecutable(),
                    "-cp",
                    System.getProperty("java.class.path"),
                    SleepProcess.class.getName(),
                    "child")
                    .start();
            Files.writeString(
                    Path.of(args[1]),
                    Long.toString(child.pid()),
                    StandardCharsets.UTF_8);
            Thread.sleep(60_000L);
        }
    }
}

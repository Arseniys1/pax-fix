package com.example.cashdesk.paxfix.terminal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Starts an external command without any unbounded process or stream wait. */
final class BoundedProcessRunner {

    private static final int OUTPUT_LIMIT_BYTES = 64 * 1024;
    private static final long TERMINATION_GRACE_MILLIS = 1_000L;
    private static final long COLLECTOR_JOIN_MILLIS = 500L;

    private BoundedProcessRunner() {
    }

    static Result run(
            List<String> command,
            Path workingDirectory,
            OperationDeadline deadline)
            throws IOException, InterruptedException, InpasTimeoutException {
        Process process = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .start();

        StreamCollector stdout = new StreamCollector(process.getInputStream(), "inpas-stdout");
        StreamCollector stderr = new StreamCollector(process.getErrorStream(), "inpas-stderr");
        stdout.start();
        stderr.start();

        boolean terminationAttempted = false;
        try {
            long remaining = deadline.remainingMillis("внешний процесс");
            if (!process.waitFor(remaining, TimeUnit.MILLISECONDS)) {
                terminateTree(process);
                terminationAttempted = true;
                throw new InpasTimeoutException("внешний процесс");
            }

            stdout.joinBounded(deadline, "вывод внешнего процесса");
            stderr.joinBounded(deadline, "ошибки внешнего процесса");
            return new Result(process.exitValue(), stdout.text(), stderr.text());
        } catch (InterruptedException exception) {
            terminateTree(process);
            terminationAttempted = true;
            Thread.currentThread().interrupt();
            throw exception;
        } finally {
            if (process.isAlive() && !terminationAttempted) {
                terminateTree(process);
            }
            closeQuietly(process.getInputStream());
            closeQuietly(process.getErrorStream());
            closeQuietly(process.getOutputStream());
        }
    }

    private static void terminateTree(Process process) {
        List<ProcessHandle> handles = new ArrayList<>(process.toHandle().descendants().toList());
        handles.add(process.toHandle());

        for (ProcessHandle handle : handles) {
            if (handle.isAlive()) {
                handle.destroy();
            }
        }
        waitUntilStopped(handles, TERMINATION_GRACE_MILLIS);

        List<ProcessHandle> remaining = new ArrayList<>();
        for (ProcessHandle handle : handles) {
            if (handle.isAlive()) {
                handle.destroyForcibly();
                remaining.add(handle);
            }
        }
        waitUntilStopped(remaining, TERMINATION_GRACE_MILLIS);
    }

    private static void waitUntilStopped(List<ProcessHandle> handles, long timeoutMillis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (handles.stream().anyMatch(ProcessHandle::isAlive)
                && System.nanoTime() < deadline) {
            try {
                Thread.sleep(25L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void closeQuietly(AutoCloseable stream) {
        try {
            stream.close();
        } catch (Exception ignored) {
            // The process may already have closed the stream.
        }
    }

    record Result(int exitCode, String stdout, String stderr) {
    }

    private static final class StreamCollector {
        private final InputStream input;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final Thread thread;

        private StreamCollector(InputStream input, String threadName) {
            this.input = input;
            this.thread = new Thread(this::collect, threadName);
            this.thread.setDaemon(true);
        }

        private void start() {
            thread.start();
        }

        private void joinBounded(OperationDeadline deadline, String stage)
                throws InterruptedException, InpasTimeoutException {
            thread.join(Math.min(COLLECTOR_JOIN_MILLIS, deadline.remainingMillis(stage)));
        }

        private void collect() {
            byte[] buffer = new byte[2_048];
            try {
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    int accepted = Math.min(count, OUTPUT_LIMIT_BYTES - bytes.size());
                    if (accepted > 0) {
                        synchronized (bytes) {
                            bytes.write(buffer, 0, accepted);
                        }
                    }
                }
            } catch (IOException ignored) {
                // Closing a timed-out process also closes its streams.
            }
        }

        private String text() {
            synchronized (bytes) {
                return bytes.toString(Charset.defaultCharset());
            }
        }
    }
}


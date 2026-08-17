package io.github.eolivelli.nb5visualizer.it;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Small helper to drive docker from integration tests without extra dependencies. */
final class Docker {

    static final class Result {
        final int exitCode;
        final String output;

        Result(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    static Result run(long timeoutSeconds, String... command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream in = p.getInputStream()) {
                in.transferTo(buf);
            } catch (IOException ignored) {
            }
        });
        reader.start();
        if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            reader.join(5000);
            throw new IOException("Timed out after " + timeoutSeconds + "s: "
                    + String.join(" ", command) + "\n" + buf.toString(StandardCharsets.UTF_8));
        }
        reader.join(5000);
        return new Result(p.exitValue(), buf.toString(StandardCharsets.UTF_8));
    }

    static Result docker(long timeoutSeconds, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.addAll(Arrays.asList(args));
        return run(timeoutSeconds, cmd.toArray(new String[0]));
    }

    static void requireSuccess(Result r, String what) {
        if (r.exitCode != 0) {
            throw new AssertionError(what + " failed with exit code " + r.exitCode + ":\n" + r.output);
        }
    }

    static void silentCleanup(String... args) {
        try {
            docker(60, args);
        } catch (Exception ignored) {
        }
    }

    private Docker() {
    }
}

package io.github.eolivelli.nb5visualizer.it;

import io.github.eolivelli.nb5visualizer.Main;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static io.github.eolivelli.nb5visualizer.it.Docker.docker;
import static io.github.eolivelli.nb5visualizer.it.Docker.requireSuccess;
import static io.github.eolivelli.nb5visualizer.it.Docker.silentCleanup;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Full pipeline test: starts Cassandra 5 in Docker, runs a real NoSQLBench 5
 * CQL workload (with per-statement instrumentation and a deliberately failing
 * statement) against it with --report-csv-to, then feeds the metrics to the
 * visualizer and checks the generated HTML.
 *
 * <p>Enable with: {@code mvn verify -Pdocker-it}. Requires Docker and pulls
 * cassandra:5 and nosqlbench/nosqlbench:latest on first use (~2 GB).
 */
@EnabledIfSystemProperty(named = "nb5.docker.it", matches = "true")
class Nb5EndToEndIT {

    static final String NETWORK = "nb5viz-it-net";
    static final String CASSANDRA = "nb5viz-it-cassandra";
    static Path workDir;

    @BeforeAll
    static void startCassandra() throws Exception {
        workDir = Files.createTempDirectory("nb5viz-it");
        silentCleanup("rm", "-f", CASSANDRA);
        silentCleanup("network", "rm", NETWORK);
        requireSuccess(docker(60, "network", "create", NETWORK), "network create");
        requireSuccess(docker(120, "run", "-d", "--name", CASSANDRA, "--network", NETWORK,
                "-e", "HEAP_NEWSIZE=128M", "-e", "MAX_HEAP_SIZE=1G",
                "cassandra:5"), "cassandra start");
        waitForCassandra();
    }

    static void waitForCassandra() throws Exception {
        long deadline = System.currentTimeMillis() + 300_000;
        while (System.currentTimeMillis() < deadline) {
            Docker.Result r = docker(30, "exec", CASSANDRA, "cqlsh", "-e", "describe cluster");
            if (r.exitCode == 0) {
                return;
            }
            Thread.sleep(5000);
        }
        throw new AssertionError("Cassandra did not become ready within 5 minutes");
    }

    @AfterAll
    static void cleanup() {
        silentCleanup("rm", "-f", CASSANDRA);
        silentCleanup("network", "rm", NETWORK);
    }

    @Test
    void runWorkloadAndVisualize() throws Exception {
        Path workload = Paths.get("examples/nb5viz_demo.yaml").toAbsolutePath();
        assertTrue(Files.exists(workload), "demo workload missing: " + workload);

        // nb5's docker image runs as root with the jar at /; override the
        // entrypoint so the working directory (and its logs/) land in /out.
        Docker.Result run = docker(600, "run", "--rm", "--network", NETWORK,
                "-v", workDir + ":/out",
                "-v", workload.getParent() + ":/workloads",
                "-w", "/out",
                "--entrypoint", "java",
                "nosqlbench/nosqlbench:latest",
                "--enable-preview", "-XX:+UseZGC", "-jar", "/nb5.jar",
                "/workloads/nb5viz_demo.yaml", "witherrors",
                "hosts=" + CASSANDRA, "localdc=datacenter1",
                "rampup-cycles=2000", "main-cycles=4000", "cyclerate=150",
                "instrument=true", "errors=counter,warn",
                "--report-csv-to", "/out/csv:.*:5s",
                "--report-summary-to", "/out/summary.txt");
        requireSuccess(run, "nb5 run");

        // files were written by root inside the container; make them readable
        requireSuccess(docker(120, "run", "--rm", "-v", workDir + ":/out",
                "alpine", "chmod", "-R", "a+rwX", "/out"), "chmod");

        Path report = workDir.resolve("report.html");
        int exit = Main.run(new String[]{
                workDir.resolve("csv").toString(),
                "-o", report.toString(),
                "--title", "integration test run"});
        assertEquals(0, exit);

        String html = Files.readString(report, StandardCharsets.UTF_8);
        assertTrue(html.contains("\"activities\""), "report has activities");
        assertTrue(html.contains("\"name\":\"main\""), "main activity present");
        assertTrue(html.contains("\"main_select\""), "per-statement metrics present (instrument=true)");
        assertTrue(html.contains("\"main_insert\""), "per-statement metrics present (instrument=true)");
        assertTrue(html.contains("\"bad_select\""), "failing statement present");
        assertTrue(html.contains("\"errorTypes\""), "exception-type breakdown present");
        // the bad_select statement fails on every execution, so errors must be nonzero
        assertTrue(html.matches("(?s).*\"totalErrors\":[1-9].*"), "errors were recorded");
    }
}

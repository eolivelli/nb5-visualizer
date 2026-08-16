package io.github.eolivelli.nb5visualizer;

import io.github.eolivelli.nb5visualizer.model.MetricSeries;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompareAndZipTest {

    static final Path FIXTURE = Paths.get("src/test/resources/fixtures/run-witherrors");
    static final Path FIXTURE_B = Paths.get("src/test/resources/fixtures/run-short");

    // ------------------------------------------------------------------- zip

    /** Zips a fixture directory, optionally under a top-level folder like real archives. */
    static Path zipFixture(Path fixture, Path zipFile, String topLevelDir) throws IOException {
        try (OutputStream out = Files.newOutputStream(zipFile);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            try (Stream<Path> walk = Files.walk(fixture)) {
                for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                    String rel = fixture.relativize(p).toString().replace('\\', '/');
                    zip.putNextEntry(new ZipEntry(topLevelDir == null ? rel : topLevelDir + "/" + rel));
                    zip.write(Files.readAllBytes(p));
                    zip.closeEntry();
                }
            }
        }
        return zipFile;
    }

    @Test
    void parsesZipArchive(@TempDir Path tmp) throws IOException {
        Path zip = zipFixture(FIXTURE, tmp.resolve("myrun.zip"), null);
        List<MetricSeries> series = new CsvMetricsParser().parseInput(zip);
        assertTrue(series.size() > 50);
        assertTrue(series.stream().anyMatch(s -> s.metric().equals("result_success")));
    }

    @Test
    void parsesZipArchiveWithTopLevelFolder(@TempDir Path tmp) throws IOException {
        // zipping a directory usually nests everything under its name
        Path zip = zipFixture(FIXTURE, tmp.resolve("archived.zip"), "some-run-2026-08-14");
        List<MetricSeries> series = new CsvMetricsParser().parseInput(zip);
        assertTrue(series.size() > 50);
    }

    @Test
    void rejectsZipWithoutMetrics(@TempDir Path tmp) throws IOException {
        Path zip = tmp.resolve("empty.zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip))) {
            z.putNextEntry(new ZipEntry("readme.txt"));
            z.write("nothing here".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        try {
            new CsvMetricsParser().parseInput(zip);
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("empty.zip"), e.getMessage());
        }
    }

    @Test
    void zipInputWorksThroughTheCli(@TempDir Path tmp) throws IOException {
        Path zip = zipFixture(FIXTURE, tmp.resolve("run.zip"), null);
        Path out = tmp.resolve("report.html");
        int exit = Main.run(new String[]{zip.toString(), "-o", out.toString()});
        assertEquals(0, exit);
        assertTrue(Files.readString(out).contains("\"totalErrors\":2207"));
    }

    // --------------------------------------------------------------- compare

    @Test
    void comparesTwoRuns(@TempDir Path tmp) throws IOException {
        Path out = tmp.resolve("compare.html");
        int exit = Main.run(new String[]{
                FIXTURE.toString(), FIXTURE_B.toString(),
                "--labels", "baseline,candidate",
                "-o", out.toString()});
        assertEquals(0, exit);
        String html = Files.readString(out, StandardCharsets.UTF_8);
        assertTrue(html.contains("\"mode\":\"compare\""));
        assertTrue(html.contains("\"labels\":[\"baseline\",\"candidate\"]"));
        // both runs' data present
        assertTrue(html.contains("\"totalErrors\":2207"));
        assertTrue(html.contains("UNNAMEDACTIVITY"));
        // default title mentions both labels
        assertTrue(html.contains("baseline vs candidate"));
    }

    @Test
    void compareAcceptsMixedZipAndDirectory(@TempDir Path tmp) throws IOException {
        Path zip = zipFixture(FIXTURE_B, tmp.resolve("previous-run.zip"), "nested");
        Path out = tmp.resolve("compare.html");
        int exit = Main.run(new String[]{
                FIXTURE.toString(), zip.toString(), "-o", out.toString()});
        assertEquals(0, exit);
        String html = Files.readString(out, StandardCharsets.UTF_8);
        // default label for the zip input drops the extension
        assertTrue(html.contains("\"labels\":[\"run-witherrors\",\"previous-run\"]"));
    }

    @Test
    void rejectsBadLabelCounts(@TempDir Path tmp) throws IOException {
        assertEquals(1, Main.run(new String[]{
                FIXTURE.toString(), FIXTURE_B.toString(),
                "--labels", "only-one",
                "-o", tmp.resolve("x.html").toString()}));
        assertEquals(2, Main.run(new String[]{
                FIXTURE.toString(), FIXTURE_B.toString(), FIXTURE.toString()}));
    }
}

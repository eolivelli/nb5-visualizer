package io.github.eolivelli.nb5visualizer;

import io.github.eolivelli.nb5visualizer.model.MetricSeries;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlReportGeneratorTest {

    @Test
    void generatesSelfContainedHtml() throws IOException {
        List<MetricSeries> series = new CsvMetricsParser()
                .parseDirectory(Paths.get("src/test/resources/fixtures/run-witherrors"));
        Map<String, Object> report = new RunAnalyzer().analyze(series, "My <Test> & \"Run\"");
        String html = new HtmlReportGenerator().generate(report);

        assertFalse(html.contains("__DATA_JSON__"));
        assertFalse(html.contains("__TITLE__"));
        // the title is HTML-escaped in the <title> tag
        assertTrue(html.contains("My &lt;Test&gt; &amp; &quot;Run&quot;"));
        // real data made it into the embedded JSON
        assertTrue(html.contains("\"totalErrors\":2207"));
        assertTrue(html.contains("\"bad_select\""));
        // no external resources: single self-contained file
        assertFalse(html.contains("<script src="), "no external scripts");
        assertFalse(html.contains("<link "), "no external stylesheets or fonts");
        assertFalse(html.contains("@import"), "no imported stylesheets");
        assertFalse(html.contains("fetch("), "no runtime network calls");
    }

    @Test
    void mainCliWritesReport(@TempDir Path tmp) throws IOException {
        Path out = tmp.resolve("report.html");
        int exit = Main.run(new String[]{
                "src/test/resources/fixtures/run-witherrors",
                "-o", out.toString(),
                "--title", "cli test"});
        assertEquals(0, exit);
        String html = Files.readString(out, StandardCharsets.UTF_8);
        assertTrue(html.contains("cli test"));
        assertTrue(html.contains("\"activities\""));
    }

    @Test
    void mainCliFailsCleanlyOnBadInput(@TempDir Path tmp) throws IOException {
        assertEquals(1, Main.run(new String[]{tmp.toString(), "-o", tmp.resolve("x.html").toString()}));
        assertEquals(2, Main.run(new String[]{}));
        assertEquals(2, Main.run(new String[]{"--bogus"}));
    }
}

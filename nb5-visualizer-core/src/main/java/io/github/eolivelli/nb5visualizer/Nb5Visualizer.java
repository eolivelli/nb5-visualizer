package io.github.eolivelli.nb5visualizer;

import io.github.eolivelli.nb5visualizer.model.MetricSeries;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Programmatic entry point: renders one run, or a comparison of two runs, to a
 * self-contained HTML file. Used by the CLI ({@link Main}) and the TUI module;
 * never writes to stdout/stderr — progress and warnings come back in the
 * {@link Result}.
 */
public final class Nb5Visualizer {

    /** Outcome of a successful generation: where the report went, plus log lines. */
    public static final class Result {
        private final Path output;
        private final List<String> notes;
        private final List<String> warnings;

        Result(Path output, List<String> notes, List<String> warnings) {
            this.output = output;
            this.notes = Collections.unmodifiableList(notes);
            this.warnings = Collections.unmodifiableList(warnings);
        }

        public Path output() {
            return output;
        }

        /** Informational lines, e.g. per-run series/ops counts. */
        public List<String> notes() {
            return notes;
        }

        /** Non-fatal problems, e.g. a run with no per-activity metrics. */
        public List<String> warnings() {
            return warnings;
        }
    }

    /**
     * Generates the report for one or two inputs (metrics directories or .zip
     * archives of them).
     *
     * @param inputs one or two inputs; two means a side-by-side comparison
     * @param output the HTML file to write
     * @param title  report title, or null for a default derived from the inputs
     * @param labels names for the compared runs, or null for directory names;
     *               ignored for a single input
     * @throws IllegalArgumentException on unusable input (unreadable, wrong count)
     */
    public Result generate(List<Path> inputs, Path output, String title, List<String> labels)
            throws IOException {
        if (inputs.isEmpty() || inputs.size() > 2) {
            throw new IllegalArgumentException("Expected one or two inputs, got " + inputs.size());
        }
        List<String> notes = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, Object> report = inputs.size() == 1
                ? singleRunReport(inputs.get(0), title, notes, warnings)
                : compareReport(inputs, title, labels != null ? labels : defaultLabels(inputs),
                        notes, warnings);
        String html = new HtmlReportGenerator().generate(report);
        Files.write(output, html.getBytes(StandardCharsets.UTF_8));
        return new Result(output.toAbsolutePath(), notes, warnings);
    }

    private Map<String, Object> singleRunReport(Path input, String title,
                                                List<String> notes, List<String> warnings)
            throws IOException {
        if (title == null) {
            title = "NoSQLBench run – " + dirName(input);
        }
        List<MetricSeries> series = new CsvMetricsParser().parseInput(input);
        Map<String, Object> report = new RunAnalyzer().analyze(series, title);
        Object activities = report.get("activities");
        int activityCount = activities instanceof List ? ((List<?>) activities).size() : 0;
        notes.add("Parsed " + series.size() + " metric series, " + activityCount
                + " activities (" + report.get("totalOps") + " ops, "
                + report.get("totalErrors") + " errors)");
        if (activityCount == 0) {
            warnings.add("No per-activity metrics found. Did the run last at least one"
                    + " reporting interval and use --report-csv-to?");
        }
        return report;
    }

    private Map<String, Object> compareReport(List<Path> inputs, String title, List<String> labels,
                                              List<String> notes, List<String> warnings)
            throws IOException {
        if (title == null) {
            title = "NoSQLBench comparison – " + labels.get(0) + " vs " + labels.get(1);
        }
        List<Map<String, Object>> runs = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            List<MetricSeries> series = new CsvMetricsParser().parseInput(inputs.get(i));
            Map<String, Object> run = new RunAnalyzer().analyze(series, labels.get(i));
            notes.add("Run '" + labels.get(i) + "': parsed " + series.size()
                    + " metric series (" + run.get("totalOps") + " ops, "
                    + run.get("totalErrors") + " errors)");
            if (((List<?>) run.get("activities")).isEmpty()) {
                warnings.add("No per-activity metrics found in " + inputs.get(i));
            }
            runs.add(run);
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("mode", "compare");
        report.put("title", title);
        report.put("labels", labels);
        report.put("generatedAt", runs.get(0).get("generatedAt"));
        report.put("runs", runs);
        return report;
    }

    /** Labels for a comparison when none were given: the two directory names. */
    public static List<String> defaultLabels(List<Path> inputs) {
        String a = dirName(inputs.get(0));
        String b = dirName(inputs.get(1));
        if (a.equals(b)) {
            return List.of("run A", "run B");
        }
        return List.of(a, b);
    }

    /** Display name of an input: last path element, minus any .zip extension. */
    public static String dirName(Path p) {
        Path normalized = p.toAbsolutePath().normalize();
        Path name = normalized.getFileName();
        String s = name != null ? name.toString() : normalized.toString();
        return s.toLowerCase(Locale.ROOT).endsWith(".zip")
                ? s.substring(0, s.length() - ".zip".length())
                : s;
    }
}

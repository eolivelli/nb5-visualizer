package io.github.eolivelli.nb5visualizer;

import io.github.eolivelli.nb5visualizer.model.MetricSeries;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * Command line entry point.
 *
 * <pre>
 * java -jar nb5-visualizer.jar &lt;metrics-dir&gt; [-o report.html] [--title "My run"]
 * java -jar nb5-visualizer.jar &lt;run-a-dir&gt; &lt;run-b-dir&gt; [--labels "baseline,tuned"] [-o report.html]
 * </pre>
 *
 * where each input is a directory passed to nb5's {@code --report-csv-to} (or
 * its parent, if the CSVs are in a {@code csv/} subdirectory), or a
 * {@code .zip} archive of either. With two inputs the report compares the runs
 * side by side, matching activities and statements by name.
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        int exit = run(args);
        if (exit != 0) {
            System.exit(exit);
        }
    }

    public static int run(String[] args) throws IOException {
        List<Path> inputs = new java.util.ArrayList<>();
        Path output = null;
        String title = null;
        String labelsArg = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-o":
                case "--output":
                    output = Paths.get(requireValue(args, ++i, "-o"));
                    break;
                case "--title":
                    title = requireValue(args, ++i, "--title");
                    break;
                case "--labels":
                    labelsArg = requireValue(args, ++i, "--labels");
                    break;
                case "-h":
                case "--help":
                    printUsage();
                    return 0;
                default:
                    if (args[i].startsWith("-")) {
                        System.err.println("Unknown option: " + args[i]);
                        printUsage();
                        return 2;
                    }
                    if (inputs.size() == 2) {
                        System.err.println("At most two input directories may be given");
                        printUsage();
                        return 2;
                    }
                    inputs.add(Paths.get(args[i]));
            }
        }
        if (inputs.isEmpty()) {
            printUsage();
            return 2;
        }
        if (output == null) {
            output = Paths.get("nb5-report.html");
        }

        Map<String, Object> report;
        try {
            report = inputs.size() == 1
                    ? singleRunReport(inputs.get(0), title)
                    : compareReport(inputs, title, labels(labelsArg, inputs));
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            return 1;
        }
        String html = new HtmlReportGenerator().generate(report);
        Files.write(output, html.getBytes(StandardCharsets.UTF_8));
        System.out.println("Report written to " + output.toAbsolutePath());
        return 0;
    }

    private static Map<String, Object> singleRunReport(Path input, String title) throws IOException {
        if (title == null) {
            title = "NoSQLBench run – " + dirName(input);
        }
        List<MetricSeries> series = new CsvMetricsParser().parseInput(input);
        Map<String, Object> report = new RunAnalyzer().analyze(series, title);
        Object activities = report.get("activities");
        int activityCount = activities instanceof List ? ((List<?>) activities).size() : 0;
        System.out.println("Parsed " + series.size() + " metric series, " + activityCount
                + " activities (" + report.get("totalOps") + " ops, "
                + report.get("totalErrors") + " errors)");
        if (activityCount == 0) {
            System.err.println("Warning: no per-activity metrics found. Did the run last at least one"
                    + " reporting interval and use --report-csv-to?");
        }
        return report;
    }

    private static Map<String, Object> compareReport(List<Path> inputs, String title,
                                                     List<String> labels) throws IOException {
        if (title == null) {
            title = "NoSQLBench comparison – " + labels.get(0) + " vs " + labels.get(1);
        }
        List<Map<String, Object>> runs = new java.util.ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            List<MetricSeries> series = new CsvMetricsParser().parseInput(inputs.get(i));
            Map<String, Object> run = new RunAnalyzer().analyze(series, labels.get(i));
            System.out.println("Run '" + labels.get(i) + "': parsed " + series.size()
                    + " metric series (" + run.get("totalOps") + " ops, "
                    + run.get("totalErrors") + " errors)");
            if (((List<?>) run.get("activities")).isEmpty()) {
                System.err.println("Warning: no per-activity metrics found in " + inputs.get(i));
            }
            runs.add(run);
        }
        Map<String, Object> report = new java.util.LinkedHashMap<>();
        report.put("mode", "compare");
        report.put("title", title);
        report.put("labels", labels);
        report.put("generatedAt", runs.get(0).get("generatedAt"));
        report.put("runs", runs);
        return report;
    }

    private static List<String> labels(String labelsArg, List<Path> inputs) {
        if (labelsArg != null) {
            String[] parts = labelsArg.split(",", -1);
            if (parts.length != inputs.size() || parts[0].trim().isEmpty() || parts[1].trim().isEmpty()) {
                throw new IllegalArgumentException(
                        "--labels needs exactly " + inputs.size() + " comma-separated non-empty values");
            }
            return List.of(parts[0].trim(), parts[1].trim());
        }
        String a = dirName(inputs.get(0));
        String b = dirName(inputs.get(1));
        if (a.equals(b)) {
            return List.of("run A", "run B");
        }
        return List.of(a, b);
    }

    private static String dirName(Path p) {
        Path normalized = p.toAbsolutePath().normalize();
        Path name = normalized.getFileName();
        String s = name != null ? name.toString() : normalized.toString();
        return s.toLowerCase(java.util.Locale.ROOT).endsWith(".zip")
                ? s.substring(0, s.length() - ".zip".length())
                : s;
    }

    private static String requireValue(String[] args, int i, String option) {
        if (i >= args.length) {
            throw new IllegalArgumentException("Missing value for " + option);
        }
        return args[i];
    }

    private static void printUsage() {
        System.out.println("NoSQLBench 5 Visualizer");
        System.out.println();
        System.out.println("Usage: java -jar nb5-visualizer.jar <metrics-dir> [options]");
        System.out.println("       java -jar nb5-visualizer.jar <run-a-dir> <run-b-dir> [options]");
        System.out.println();
        System.out.println("  <metrics-dir>   directory given to nb5 --report-csv-to (or its parent");
        System.out.println("                  containing a csv/ subdirectory), or a .zip archive of");
        System.out.println("                  either. With two inputs, the report compares the runs");
        System.out.println("                  side by side.");
        System.out.println("  -o, --output    output HTML file (default: nb5-report.html)");
        System.out.println("  --title         report title");
        System.out.println("  --labels        comma-separated names for the two compared runs,");
        System.out.println("                  e.g. --labels \"baseline,tuned\" (default: directory names)");
        System.out.println();
        System.out.println("Example nb5 run producing suitable input:");
        System.out.println("  nb5 cql_keyvalue default hosts=localhost localdc=datacenter1 \\");
        System.out.println("      instrument=true errors=counter,warn --report-csv-to metrics/csv:.*:5s");
    }

    private Main() {
    }
}

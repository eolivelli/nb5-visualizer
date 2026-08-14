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
 * </pre>
 *
 * where {@code metrics-dir} is the directory passed to nb5's
 * {@code --report-csv-to} (or its parent, if the CSVs are in a {@code csv/}
 * subdirectory).
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        int exit = run(args);
        if (exit != 0) {
            System.exit(exit);
        }
    }

    public static int run(String[] args) throws IOException {
        Path input = null;
        Path output = null;
        String title = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-o":
                case "--output":
                    output = Paths.get(requireValue(args, ++i, "-o"));
                    break;
                case "--title":
                    title = requireValue(args, ++i, "--title");
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
                    if (input != null) {
                        System.err.println("Only one input directory may be given");
                        printUsage();
                        return 2;
                    }
                    input = Paths.get(args[i]);
            }
        }
        if (input == null) {
            printUsage();
            return 2;
        }
        if (output == null) {
            output = Paths.get("nb5-report.html");
        }
        if (title == null) {
            title = "NoSQLBench run – " + input.toAbsolutePath().normalize().getFileName();
        }

        List<MetricSeries> series;
        try {
            series = new CsvMetricsParser().parseDirectory(input);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            return 1;
        }
        Map<String, Object> report = new RunAnalyzer().analyze(series, title);
        String html = new HtmlReportGenerator().generate(report);
        Files.write(output, html.getBytes(StandardCharsets.UTF_8));

        Object activities = report.get("activities");
        int activityCount = activities instanceof List ? ((List<?>) activities).size() : 0;
        System.out.println("Parsed " + series.size() + " metric series, " + activityCount
                + " activities (" + report.get("totalOps") + " ops, "
                + report.get("totalErrors") + " errors)");
        System.out.println("Report written to " + output.toAbsolutePath());
        if (activityCount == 0) {
            System.err.println("Warning: no per-activity metrics found. Did the run last at least one"
                    + " reporting interval and use --report-csv-to?");
        }
        return 0;
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
        System.out.println();
        System.out.println("  <metrics-dir>   directory given to nb5 --report-csv-to (or its parent");
        System.out.println("                  containing a csv/ subdirectory)");
        System.out.println("  -o, --output    output HTML file (default: nb5-report.html)");
        System.out.println("  --title         report title");
        System.out.println();
        System.out.println("Example nb5 run producing suitable input:");
        System.out.println("  nb5 cql_keyvalue default hosts=localhost localdc=datacenter1 \\");
        System.out.println("      instrument=true errors=counter,warn --report-csv-to metrics/csv:.*:5s");
    }

    private Main() {
    }
}

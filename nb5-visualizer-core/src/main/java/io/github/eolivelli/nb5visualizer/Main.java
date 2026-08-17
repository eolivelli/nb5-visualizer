package io.github.eolivelli.nb5visualizer;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

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

        Nb5Visualizer.Result result;
        try {
            result = new Nb5Visualizer().generate(inputs, output, title, labels(labelsArg, inputs));
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            return 1;
        }
        for (String note : result.notes()) {
            System.out.println(note);
        }
        for (String warning : result.warnings()) {
            System.err.println("Warning: " + warning);
        }
        System.out.println("Report written to " + result.output());
        return 0;
    }

    private static List<String> labels(String labelsArg, List<Path> inputs) {
        if (labelsArg == null) {
            return null;
        }
        String[] parts = labelsArg.split(",", -1);
        if (parts.length != inputs.size()
                || java.util.Arrays.stream(parts).anyMatch(p -> p.trim().isEmpty())) {
            throw new IllegalArgumentException(
                    "--labels needs exactly " + inputs.size() + " comma-separated non-empty values");
        }
        List<String> labels = new java.util.ArrayList<>();
        for (String part : parts) {
            labels.add(part.trim());
        }
        return labels;
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

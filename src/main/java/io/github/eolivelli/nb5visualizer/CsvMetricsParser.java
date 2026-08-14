package io.github.eolivelli.nb5visualizer;

import io.github.eolivelli.nb5visualizer.model.MetricSeries;
import io.github.eolivelli.nb5visualizer.model.MetricType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Reads the output of nb5's {@code --report-csv-to <dir>[:<regex>[:<interval>]]}:
 * one CSV file per metric instance plus a {@code metrics-files.jsonl} manifest
 * mapping each file to its metric name, type and label set.
 *
 * <p>Filenames are derived by nb5 from a per-snapshot label diff and are not
 * stable, so the manifest is the source of truth. Directories without a
 * manifest are still accepted: metric name falls back to the file name and the
 * type is inferred from the CSV header.
 */
public final class CsvMetricsParser {

    public static final String MANIFEST_FILE = "metrics-files.jsonl";

    /**
     * Accepts either the CSV directory itself, or a parent directory that
     * contains a {@code csv/} subdirectory (e.g. the directory passed to the
     * example commands in the README).
     */
    public static Path resolveCsvDir(Path input) {
        if (Files.isDirectory(input)) {
            if (Files.exists(input.resolve(MANIFEST_FILE)) || containsCsvFiles(input)) {
                return input;
            }
            Path sub = input.resolve("csv");
            if (Files.isDirectory(sub)
                    && (Files.exists(sub.resolve(MANIFEST_FILE)) || containsCsvFiles(sub))) {
                return sub;
            }
        }
        throw new IllegalArgumentException("No NoSQLBench CSV metrics found in " + input
                + " (expected " + MANIFEST_FILE + " or *.csv files, either directly or in a csv/ subdirectory)");
    }

    private static boolean containsCsvFiles(Path dir) {
        try (Stream<Path> list = Files.list(dir)) {
            return list.anyMatch(p -> p.getFileName().toString().endsWith(".csv"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<MetricSeries> parseDirectory(Path input) throws IOException {
        Path dir = resolveCsvDir(input);
        Path manifest = dir.resolve(MANIFEST_FILE);
        if (Files.exists(manifest)) {
            return parseWithManifest(dir, manifest);
        }
        return parseWithoutManifest(dir);
    }

    private List<MetricSeries> parseWithManifest(Path dir, Path manifest) throws IOException {
        // The manifest is append-only; a file can be listed more than once and the
        // last entry wins.
        Map<String, Map<String, Object>> byFile = new LinkedHashMap<>();
        for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            Map<String, Object> entry = Json.parseObject(line);
            byFile.put(String.valueOf(entry.get("file")), entry);
        }
        List<MetricSeries> out = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : byFile.entrySet()) {
            Path csv = dir.resolve(e.getKey());
            if (!Files.exists(csv)) {
                continue;
            }
            Map<String, Object> entry = e.getValue();
            String metric = String.valueOf(entry.get("metric"));
            MetricType type = MetricType.fromString(String.valueOf(entry.get("type")));
            Map<String, String> labels = new TreeMap<>();
            Object rawLabels = entry.get("labels");
            if (rawLabels instanceof Map) {
                for (Map.Entry<?, ?> l : ((Map<?, ?>) rawLabels).entrySet()) {
                    labels.put(String.valueOf(l.getKey()), String.valueOf(l.getValue()));
                }
            }
            MetricSeries series = parseCsvFile(csv, metric, type, labels);
            if (series != null) {
                out.add(series);
            }
        }
        return out;
    }

    private List<MetricSeries> parseWithoutManifest(Path dir) throws IOException {
        List<Path> files;
        try (Stream<Path> list = Files.list(dir)) {
            files = list.filter(p -> p.getFileName().toString().endsWith(".csv"))
                    .sorted()
                    .collect(Collectors.toList());
        }
        List<MetricSeries> out = new ArrayList<>();
        for (Path csv : files) {
            String name = csv.getFileName().toString();
            name = name.substring(0, name.length() - ".csv".length());
            MetricSeries series = parseCsvFile(csv, name, null, new TreeMap<>());
            if (series != null) {
                out.add(series);
            }
        }
        return out;
    }

    /**
     * Parses one metric CSV. Layouts (first column is always {@code t}, epoch seconds):
     * <pre>
     * timer:     t,count,max,mean,min,stddev,p50,p75,p95,p98,p99,p999,mean_rate,m1_rate,m5_rate,m15_rate,rate_unit,duration_unit
     * histogram: t,count,max,mean,min,stddev,p50,p75,p95,p98,p99,p999
     * meter:     t,count,mean_rate,m1_rate,m5_rate,m15_rate,rate_unit
     * gauge:     t,value
     * </pre>
     * Timer durations are nanoseconds; rates are per second. The trailing
     * {@code rate_unit}/{@code duration_unit} columns are non-numeric and stored as NaN.
     */
    static MetricSeries parseCsvFile(Path csv, String metric, MetricType type,
                                     Map<String, String> labels) throws IOException {
        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            return null;
        }
        String[] header = lines.get(0).split(",");
        if (header.length < 2 || !header[0].equals("t")) {
            return null; // not a metrics CSV
        }
        if (type == null) {
            type = MetricType.fromHeader(lines.get(0));
        }
        List<String> columns = Arrays.asList(header).subList(1, header.length);
        List<Long> timestamps = new ArrayList<>();
        List<double[]> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split(",");
            double[] row = new double[columns.size()];
            Arrays.fill(row, Double.NaN);
            for (int c = 0; c < row.length && c + 1 < parts.length; c++) {
                try {
                    row[c] = Double.parseDouble(parts[c + 1]);
                } catch (NumberFormatException ignored) {
                    // rate_unit / duration_unit columns
                }
            }
            timestamps.add(Long.parseLong(parts[0]));
            rows.add(row);
        }
        long[] ts = new long[timestamps.size()];
        for (int i = 0; i < ts.length; i++) {
            ts[i] = timestamps.get(i);
        }
        return new MetricSeries(metric, type, labels, csv.getFileName().toString(),
                columns, ts, rows.toArray(new double[0][]));
    }
}

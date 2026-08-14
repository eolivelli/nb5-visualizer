package io.github.eolivelli.nb5visualizer.model;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * One metric instance: a metric name plus a unique label set, backed by one CSV
 * file with one row per reporting interval.
 */
public final class MetricSeries {

    private final String metric;
    private final MetricType type;
    private final Map<String, String> labels;
    private final String fileName;
    private final List<String> columns;
    private final long[] timestamps; // epoch seconds, one per row
    private final double[][] rows;   // rows[i][c] = value of columns[c] at timestamps[i]

    public MetricSeries(String metric, MetricType type, Map<String, String> labels,
                        String fileName, List<String> columns, long[] timestamps, double[][] rows) {
        this.metric = metric;
        this.type = type;
        this.labels = Collections.unmodifiableMap(labels);
        this.fileName = fileName;
        this.columns = Collections.unmodifiableList(columns);
        this.timestamps = timestamps;
        this.rows = rows;
    }

    public String metric() {
        return metric;
    }

    public MetricType type() {
        return type;
    }

    public Map<String, String> labels() {
        return labels;
    }

    public String label(String key) {
        return labels.get(key);
    }

    public String fileName() {
        return fileName;
    }

    /** Column names, excluding the leading "t" timestamp column. */
    public List<String> columns() {
        return columns;
    }

    public int rowCount() {
        return timestamps.length;
    }

    public long timestampAt(int row) {
        return timestamps[row];
    }

    public long[] timestamps() {
        return timestamps;
    }

    public double value(int row, String column) {
        int idx = columns.indexOf(column);
        if (idx < 0) {
            throw new IllegalArgumentException("No column '" + column + "' in metric " + metric
                    + " (columns: " + columns + ")");
        }
        return rows[row][idx];
    }

    public boolean hasColumn(String column) {
        return columns.contains(column);
    }

    /** All values of one column, in row order. */
    public double[] columnValues(String column) {
        double[] out = new double[rowCount()];
        for (int i = 0; i < out.length; i++) {
            out[i] = value(i, column);
        }
        return out;
    }

    @Override
    public String toString() {
        return metric + labels + " (" + type + ", " + rowCount() + " rows)";
    }
}

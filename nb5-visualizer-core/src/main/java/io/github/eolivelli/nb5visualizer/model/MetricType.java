package io.github.eolivelli.nb5visualizer.model;

import java.util.Locale;

/** Metric families produced by the NoSQLBench 5 CSV reporter. */
public enum MetricType {
    GAUGE,
    METER,
    TIMER,
    HISTOGRAM;

    public static MetricType fromString(String s) {
        return valueOf(s.trim().toUpperCase(Locale.ROOT));
    }

    /** Infers the metric type from a CSV header line, for directories without a manifest. */
    public static MetricType fromHeader(String headerLine) {
        String h = headerLine.trim();
        if (h.equals("t,value")) {
            return GAUGE;
        }
        if (h.contains("duration_unit")) {
            return TIMER;
        }
        if (h.contains("mean_rate")) {
            return METER;
        }
        if (h.contains("p50")) {
            return HISTOGRAM;
        }
        throw new IllegalArgumentException("Unrecognized CSV header: " + headerLine);
    }
}

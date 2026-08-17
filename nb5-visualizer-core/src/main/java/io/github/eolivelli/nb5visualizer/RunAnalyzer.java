package io.github.eolivelli.nb5visualizer;

import io.github.eolivelli.nb5visualizer.model.MetricSeries;
import io.github.eolivelli.nb5visualizer.model.MetricType;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Turns the parsed metric series of one nb5 session into the data structure
 * embedded in the HTML report.
 *
 * <p>Semantics of the nb5 CSV columns this relies on (verified against real
 * nb5 5.25 output and the CsvReporter source):
 * <ul>
 *   <li>timer/histogram/meter {@code count} is the number of events in that
 *       reporting interval (delta reservoir), not a cumulative total;</li>
 *   <li>timer percentiles are per-interval distributions in nanoseconds;</li>
 *   <li>{@code errors_total} is a cumulative gauge equal to
 *       {@code result.count - result_success.count};</li>
 *   <li>per-op timers {@code successfor_<op>}/{@code errorsfor_<op>} exist when
 *       the run used {@code instrument=true} and carry an {@code op} label.</li>
 * </ul>
 */
public final class RunAnalyzer {

    private static final double NANOS_PER_MILLI = 1_000_000.0;
    private static final String[] PERCENTILE_COLUMNS = {"p50", "p75", "p95", "p99", "p999", "max"};

    public Map<String, Object> analyze(List<MetricSeries> allSeries, String title) {
        Map<String, List<MetricSeries>> byActivity = new LinkedHashMap<>();
        String session = null;
        for (MetricSeries s : allSeries) {
            if (session == null && s.label("session") != null) {
                session = s.label("session");
            }
            String activity = s.label("activity");
            if (activity != null) {
                byActivity.computeIfAbsent(activity, k -> new ArrayList<>()).add(s);
            }
        }

        List<Map<String, Object>> activities = new ArrayList<>();
        for (Map.Entry<String, List<MetricSeries>> e : byActivity.entrySet()) {
            Map<String, Object> activity = analyzeActivity(e.getKey(), e.getValue());
            if (activity != null) {
                activities.add(activity);
            }
        }
        activities.sort(Comparator.comparingLong(a -> (Long) a.get("startS")));

        long totalOps = 0;
        long totalErrors = 0;
        long startS = Long.MAX_VALUE;
        long endS = Long.MIN_VALUE;
        for (Map<String, Object> a : activities) {
            Map<?, ?> summary = (Map<?, ?>) a.get("summary");
            totalOps += ((Number) summary.get("totalOps")).longValue();
            totalErrors += ((Number) summary.get("totalErrors")).longValue();
            startS = Math.min(startS, (Long) a.get("startS"));
            endS = Math.max(endS, (Long) a.get("endS"));
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("title", title);
        report.put("session", session);
        report.put("generatedAt", DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)));
        report.put("zoneId", ZoneId.systemDefault().getId());
        if (!activities.isEmpty()) {
            report.put("startS", startS);
            report.put("endS", endS);
        }
        report.put("totalOps", totalOps);
        report.put("totalErrors", totalErrors);
        report.put("activities", activities);
        return report;
    }

    private Map<String, Object> analyzeActivity(String name, List<MetricSeries> series) {
        MetricSeries result = find(series, "result", null);
        MetricSeries resultSuccess = find(series, "result_success", null);
        MetricSeries latencySource = resultSuccess != null && resultSuccess.rowCount() > 0
                ? resultSuccess : result;
        if (latencySource == null || latencySource.rowCount() == 0) {
            return null; // activity too short to be sampled (e.g. schema phase)
        }

        long[] t = latencySource.timestamps();
        MetricSeries countSource = result != null ? result : latencySource;
        long totalOpsEarly = totalCount(countSource);
        // With a single snapshot row the reporting interval is unknown; recover the
        // activity duration from the cumulative mean rate instead.
        double singleRowDuration = 0;
        if (t.length == 1 && countSource.hasColumn("mean_rate")) {
            double meanRate = countSource.value(0, "mean_rate");
            if (meanRate > 0) {
                singleRowDuration = totalOpsEarly / meanRate;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        putIfPresent(out, "scenario", series);
        putIfPresent(out, "workload", series);
        out.put("startS", singleRowDuration > 0
                ? t[0] - Math.round(singleRowDuration)
                : t[0] - inferIntervalSeconds(t));
        out.put("endS", t[t.length - 1]);
        out.put("rateLimited", find(series, "cycles_responsetime", null) != null);

        Map<String, Object> chart = new LinkedHashMap<>();
        chart.put("t", t);
        chart.put("rate", ratePerSecond(result != null ? result : latencySource, t));
        chart.put("successRate", ratePerSecond(resultSuccess, t));
        chart.put("errorRate", errorRate(series, result, resultSuccess, t));
        for (String p : PERCENTILE_COLUMNS) {
            chart.put(p, percentilesMs(latencySource, p));
        }
        chart.put("errorsCum", errorsCumulative(series, result, resultSuccess, t));
        out.put("series", chart);

        long totalOps = totalCount(result != null ? result : latencySource);
        long totalSuccess = totalCount(resultSuccess);
        long totalErrors = result != null && resultSuccess != null
                ? Math.max(0, totalOps - totalSuccess)
                : lastValue(find(series, "errors_total", null));
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalOps", totalOps);
        summary.put("totalErrors", totalErrors);
        double durationS = Math.max(1, (Long) out.get("endS") - (Long) out.get("startS"));
        summary.put("durationS", durationS);
        summary.put("avgRate", totalOps / durationS);
        summary.put("maxRate", max((double[]) chart.get("rate")));
        summary.put("p50AvgMs", weightedAverage(latencySource, "p50"));
        summary.put("p99MaxMs", maxColumnMs(latencySource, "p99"));
        summary.put("maxLatencyMs", maxColumnMs(latencySource, "max"));
        out.put("summary", summary);

        out.put("ops", analyzeOps(series));
        out.put("errorTypes", errorTypes(series));
        return out;
    }

    /** Per-op-template ("statement") views, present when the run used instrument=true. */
    private List<Map<String, Object>> analyzeOps(List<MetricSeries> series) {
        Map<String, MetricSeries> successByOp = new TreeMap<>();
        Map<String, MetricSeries> errorsByOp = new TreeMap<>();
        for (MetricSeries s : series) {
            String op = s.label("op");
            if (op == null || s.type() != MetricType.TIMER) {
                continue;
            }
            if (s.metric().startsWith("successfor_")) {
                successByOp.put(op, s);
            } else if (s.metric().startsWith("errorsfor_")) {
                errorsByOp.put(op, s);
            }
        }
        List<Map<String, Object>> ops = new ArrayList<>();
        for (String op : successByOp.keySet()) {
            MetricSeries success = successByOp.get(op);
            MetricSeries errors = errorsByOp.get(op);
            if (success.rowCount() == 0) {
                continue;
            }
            long[] t = success.timestamps();
            Map<String, Object> chart = new LinkedHashMap<>();
            chart.put("t", t);
            // per-op throughput counts attempts: successes plus errors
            double[] rate = ratePerSecond(success, t);
            double[] errRate = errors != null ? ratePerSecond(errors, t) : null;
            if (rate != null && errRate != null) {
                for (int i = 0; i < rate.length; i++) {
                    if (!Double.isNaN(errRate[i])) {
                        rate[i] = (Double.isNaN(rate[i]) ? 0 : rate[i]) + errRate[i];
                    }
                }
            }
            chart.put("rate", rate);
            chart.put("errorRate", errRate);
            for (String p : PERCENTILE_COLUMNS) {
                chart.put(p, percentilesMs(success, p));
            }

            long totalSuccess = totalCount(success);
            long totalErrors = totalCount(errors);
            long interval = inferIntervalSeconds(t);
            double durationS = Math.max(1, t[t.length - 1] - t[0] + interval);
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("totalOps", totalSuccess + totalErrors);
            summary.put("totalErrors", totalErrors);
            summary.put("avgRate", (totalSuccess + totalErrors) / durationS);
            summary.put("p50AvgMs", totalSuccess > 0 ? weightedAverage(success, "p50") : Double.NaN);
            summary.put("p99MaxMs", totalSuccess > 0 ? maxColumnMs(success, "p99") : Double.NaN);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("name", op);
            out.put("summary", summary);
            out.put("series", chart);
            ops.add(out);
        }
        return ops;
    }

    /** Cumulative per-exception-type counts, present when the run used errors=counter. */
    private List<Map<String, Object>> errorTypes(List<MetricSeries> series) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (MetricSeries s : series) {
            String m = s.metric();
            if (!m.startsWith("errors_") || m.equals("errors_total") || m.equals("errors_ALL")) {
                continue;
            }
            if (s.rowCount() == 0 || !s.hasColumn("value")) {
                continue;
            }
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("name", m.substring("errors_".length()));
            one.put("t", s.timestamps());
            one.put("cum", s.columnValues("value"));
            one.put("total", (long) s.value(s.rowCount() - 1, "value"));
            out.add(one);
        }
        out.sort(Comparator.comparingLong(a -> -((Number) a.get("total")).longValue()));
        return out;
    }

    // ------------------------------------------------------------- helpers

    private static void putIfPresent(Map<String, Object> out, String label, List<MetricSeries> series) {
        for (MetricSeries s : series) {
            String v = s.label(label);
            if (v != null) {
                out.put(label, v);
                return;
            }
        }
    }

    private static MetricSeries find(List<MetricSeries> series, String metric, String op) {
        for (MetricSeries s : series) {
            if (s.metric().equals(metric) && java.util.Objects.equals(s.label("op"), op)) {
                return s;
            }
        }
        return null;
    }

    /**
     * Events per second in each interval: interval count divided by the time
     * since the previous row. The first row uses the inferred interval length.
     * Falls back to mean_rate when there is a single row and no delta to use.
     */
    private static double[] ratePerSecond(MetricSeries s, long[] axis) {
        if (s == null || s.rowCount() == 0 || !s.hasColumn("count")) {
            return null;
        }
        long[] t = s.timestamps();
        double[] out = new double[axis.length];
        Arrays.fill(out, Double.NaN);
        long interval = inferIntervalSeconds(t);
        Map<Long, Integer> axisIndex = indexOf(axis);
        for (int i = 0; i < t.length; i++) {
            Integer target = axisIndex.get(t[i]);
            if (target == null) {
                continue;
            }
            double dt = i > 0 ? Math.max(1, t[i] - t[i - 1]) : interval;
            double rate;
            if (t.length == 1 && s.hasColumn("mean_rate")) {
                rate = s.value(i, "mean_rate");
            } else {
                rate = s.value(i, "count") / dt;
            }
            out[target] = rate;
        }
        return out;
    }

    /**
     * Failed operations per second. The cumulative {@code errors_total} gauge is
     * the authoritative source; differencing it per interval avoids the boundary
     * noise of subtracting the {@code result_success} count from the
     * {@code result} count (an op finishing near an interval boundary can land
     * in different intervals of the two timers).
     */
    private static double[] errorRate(List<MetricSeries> series, MetricSeries result,
                                      MetricSeries success, long[] axis) {
        MetricSeries total = find(series, "errors_total", null);
        if (total != null && total.rowCount() > 0 && total.hasColumn("value")) {
            long[] t = total.timestamps();
            long interval = inferIntervalSeconds(t);
            Map<Long, Integer> axisIndex = indexOf(axis);
            double[] out = new double[axis.length];
            Arrays.fill(out, Double.NaN);
            double prev = 0;
            for (int i = 0; i < t.length; i++) {
                double v = total.value(i, "value");
                double dt = i > 0 ? Math.max(1, t[i] - t[i - 1]) : interval;
                Integer target = axisIndex.get(t[i]);
                if (target != null) {
                    out[target] = Math.max(0, v - prev) / dt;
                }
                prev = v;
            }
            return out;
        }
        double[] all = ratePerSecond(result, axis);
        double[] ok = ratePerSecond(success, axis);
        if (all == null || ok == null) {
            return null;
        }
        double[] out = new double[axis.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = (Double.isNaN(all[i]) || Double.isNaN(ok[i])) ? Double.NaN
                    : Math.max(0, all[i] - ok[i]);
        }
        return out;
    }

    private static double[] errorsCumulative(List<MetricSeries> series, MetricSeries result,
                                             MetricSeries success, long[] axis) {
        MetricSeries total = find(series, "errors_total", null);
        if (total != null && total.rowCount() > 0 && total.hasColumn("value")) {
            Map<Long, Integer> axisIndex = indexOf(axis);
            double[] out = new double[axis.length];
            Arrays.fill(out, Double.NaN);
            for (int i = 0; i < total.rowCount(); i++) {
                Integer target = axisIndex.get(total.timestampAt(i));
                if (target != null) {
                    out[target] = total.value(i, "value");
                }
            }
            return out;
        }
        if (result == null || success == null) {
            return null;
        }
        double[] out = new double[axis.length];
        Map<Long, Integer> axisIndex = indexOf(axis);
        double[] byAxis = new double[axis.length];
        for (int i = 0; i < result.rowCount(); i++) {
            Integer target = axisIndex.get(result.timestampAt(i));
            if (target != null) {
                byAxis[target] += result.value(i, "count");
            }
        }
        for (int i = 0; i < success.rowCount(); i++) {
            Integer target = axisIndex.get(success.timestampAt(i));
            if (target != null) {
                byAxis[target] -= success.value(i, "count");
            }
        }
        double cum = 0;
        for (int i = 0; i < out.length; i++) {
            cum += Math.max(0, byAxis[i]);
            out[i] = cum;
        }
        return out;
    }

    private static Map<Long, Integer> indexOf(long[] axis) {
        Map<Long, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i < axis.length; i++) {
            map.put(axis[i], i);
        }
        return map;
    }

    static long inferIntervalSeconds(long[] t) {
        if (t.length < 2) {
            return 5;
        }
        long[] deltas = new long[t.length - 1];
        for (int i = 1; i < t.length; i++) {
            deltas[i - 1] = t[i] - t[i - 1];
        }
        Arrays.sort(deltas);
        return Math.max(1, deltas[deltas.length / 2]);
    }

    private static double[] percentilesMs(MetricSeries s, String column) {
        if (s == null || !s.hasColumn(column)) {
            return null;
        }
        boolean hasCount = s.hasColumn("count");
        double[] out = s.columnValues(column);
        for (int i = 0; i < out.length; i++) {
            // an interval with no recorded events has no latency distribution
            if (hasCount && s.value(i, "count") == 0) {
                out[i] = Double.NaN;
            } else {
                out[i] = out[i] / NANOS_PER_MILLI;
            }
        }
        return out;
    }

    private static long totalCount(MetricSeries s) {
        if (s == null || !s.hasColumn("count")) {
            return 0;
        }
        double sum = 0;
        for (int i = 0; i < s.rowCount(); i++) {
            sum += s.value(i, "count");
        }
        return (long) sum;
    }

    private static long lastValue(MetricSeries s) {
        if (s == null || s.rowCount() == 0 || !s.hasColumn("value")) {
            return 0;
        }
        return (long) s.value(s.rowCount() - 1, "value");
    }

    /** Average of a percentile column weighted by each interval's op count, in ms. */
    private static double weightedAverage(MetricSeries s, String column) {
        if (s == null || !s.hasColumn(column) || !s.hasColumn("count")) {
            return Double.NaN;
        }
        double weighted = 0;
        double weight = 0;
        for (int i = 0; i < s.rowCount(); i++) {
            double c = s.value(i, "count");
            weighted += s.value(i, column) * c;
            weight += c;
        }
        return weight == 0 ? Double.NaN : weighted / weight / NANOS_PER_MILLI;
    }

    private static double maxColumnMs(MetricSeries s, String column) {
        if (s == null || !s.hasColumn(column)) {
            return Double.NaN;
        }
        return max(s.columnValues(column)) / NANOS_PER_MILLI;
    }

    private static double max(double[] values) {
        if (values == null) {
            return Double.NaN;
        }
        double m = Double.NaN;
        for (double v : values) {
            if (!Double.isNaN(v) && (Double.isNaN(m) || v > m)) {
                m = v;
            }
        }
        return m;
    }

}

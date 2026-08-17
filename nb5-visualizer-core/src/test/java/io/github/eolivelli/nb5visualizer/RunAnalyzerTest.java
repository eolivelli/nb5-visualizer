package io.github.eolivelli.nb5visualizer;

import io.github.eolivelli.nb5visualizer.model.MetricSeries;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunAnalyzerTest {

    static Map<String, Object> report;

    @BeforeAll
    static void analyzeFixture() throws IOException {
        List<MetricSeries> series = new CsvMetricsParser()
                .parseDirectory(Paths.get("src/test/resources/fixtures/run-witherrors"));
        report = new RunAnalyzer().analyze(series, "test run");
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> activities() {
        return (List<Map<String, Object>>) report.get("activities");
    }

    static Map<String, Object> activity(String name) {
        return activities().stream().filter(a -> a.get("name").equals(name)).findFirst().orElseThrow();
    }

    @Test
    void findsBothSampledActivitiesInOrder() {
        // the schema activity finished before the first snapshot, so only two remain
        assertEquals(2, activities().size());
        assertEquals("rampup", activities().get(0).get("name"));
        assertEquals("main", activities().get(1).get("name"));
    }

    @Test
    void computesActivityTotalsFromIntervalCounts() {
        Map<?, ?> summary = (Map<?, ?>) activity("main").get("summary");
        // values observed in the recorded run: 24000 cycles minus the tail that
        // fell after the last snapshot
        assertEquals(23760L, summary.get("totalOps"));
        assertEquals(2207L, summary.get("totalErrors"));
        double avgRate = (Double) summary.get("avgRate");
        assertTrue(avgRate > 150 && avgRate < 250, "avgRate=" + avgRate);
        double p50 = (Double) summary.get("p50AvgMs");
        assertTrue(p50 > 0.05 && p50 < 50, "p50AvgMs=" + p50);
    }

    @Test
    void rampupHasNoErrors() {
        Map<?, ?> summary = (Map<?, ?>) activity("rampup").get("summary");
        assertEquals(0L, summary.get("totalErrors"));
        // the per-interval error rate must agree with the total: all zeros
        Map<?, ?> series = (Map<?, ?>) activity("rampup").get("series");
        double[] errorRate = (double[]) series.get("errorRate");
        assertNotNull(errorRate);
        for (double v : errorRate) {
            assertTrue(Double.isNaN(v) || v == 0.0, "expected no rampup errors, got " + v);
        }
    }

    @Test
    void exposesPerStatementViews() {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ops = (List<Map<String, Object>>) activity("main").get("ops");
        assertEquals(3, ops.size());
        assertEquals("bad_select", ops.get(0).get("name"));
        assertEquals("main_insert", ops.get(1).get("name"));
        assertEquals("main_select", ops.get(2).get("name"));

        Map<?, ?> badSummary = (Map<?, ?>) ops.get(0).get("summary");
        long badOps = (Long) badSummary.get("totalOps");
        long badErrors = (Long) badSummary.get("totalErrors");
        assertEquals(badOps, badErrors, "every bad_select op fails");
        assertTrue(badErrors > 1000);
        // no successful ops -> no latency figures
        assertTrue(Double.isNaN((Double) badSummary.get("p50AvgMs")));
        assertTrue(Double.isNaN((Double) badSummary.get("p99MaxMs")));
        // ...but the attempt rate is not zero
        assertTrue((Double) badSummary.get("avgRate") > 1);

        Map<?, ?> selectSummary = (Map<?, ?>) ops.get(2).get("summary");
        assertEquals(0L, selectSummary.get("totalErrors"));
        assertTrue((Long) selectSummary.get("totalOps") > 5000);
    }

    @Test
    void perStatementErrorsRollUpToActivityTotal() {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ops = (List<Map<String, Object>>) activity("main").get("ops");
        long sum = ops.stream()
                .mapToLong(o -> (Long) ((Map<?, ?>) o.get("summary")).get("totalErrors"))
                .sum();
        Map<?, ?> summary = (Map<?, ?>) activity("main").get("summary");
        long total = (Long) summary.get("totalErrors");
        // both come from real metrics; they may differ by the ops in flight around
        // the last snapshot, but must agree closely
        assertTrue(Math.abs(sum - total) <= 50, "per-op sum " + sum + " vs total " + total);
    }

    @Test
    void capturesExceptionTypeBreakdown() {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errorTypes =
                (List<Map<String, Object>>) activity("main").get("errorTypes");
        assertEquals(1, errorTypes.size());
        assertEquals("RuntimeException", errorTypes.get(0).get("name"));
        assertTrue((Long) errorTypes.get(0).get("total") > 1000);
    }

    @Test
    void reportTotalsAggregateActivities() {
        assertEquals(33760L, report.get("totalOps"));
        assertEquals(2207L, report.get("totalErrors"));
        assertEquals("test run", report.get("title"));
        assertNotNull(report.get("session"));
    }

    @Test
    void latencySeriesAreMillisecondsWithGapsForEmptyIntervals() {
        Map<?, ?> series = (Map<?, ?>) activity("main").get("series");
        double[] p99 = (double[]) series.get("p99");
        boolean sawValue = false;
        for (double v : p99) {
            if (!Double.isNaN(v)) {
                sawValue = true;
                assertTrue(v > 0.01 && v < 10_000, "p99 in ms expected, got " + v);
            }
        }
        assertTrue(sawValue);
    }

    @Test
    void inferIntervalUsesMedianDelta() {
        assertEquals(5, RunAnalyzer.inferIntervalSeconds(new long[]{100, 105, 110, 130, 135}));
        assertEquals(5, RunAnalyzer.inferIntervalSeconds(new long[]{100}));
    }
}

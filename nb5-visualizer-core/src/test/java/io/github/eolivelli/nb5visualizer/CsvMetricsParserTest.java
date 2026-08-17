package io.github.eolivelli.nb5visualizer;

import io.github.eolivelli.nb5visualizer.model.MetricSeries;
import io.github.eolivelli.nb5visualizer.model.MetricType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvMetricsParserTest {

    static final Path FIXTURE = Paths.get("src/test/resources/fixtures/run-witherrors");

    @Test
    void parsesRealRunWithManifest() throws IOException {
        List<MetricSeries> series = new CsvMetricsParser().parseDirectory(FIXTURE);
        assertTrue(series.size() > 50, "expected many series, got " + series.size());

        MetricSeries resultSuccess = series.stream()
                .filter(s -> s.metric().equals("result_success") && "main".equals(s.label("activity")))
                .findFirst().orElseThrow();
        assertEquals(MetricType.TIMER, resultSuccess.type());
        assertEquals("witherrors", resultSuccess.label("scenario"));
        assertEquals("nb5viz_demo", resultSuccess.label("workload"));
        assertEquals(24, resultSuccess.rowCount());
        assertTrue(resultSuccess.hasColumn("count"));
        assertTrue(resultSuccess.hasColumn("p99"));
        // timestamps are epoch seconds, monotonically increasing at ~5s
        long prev = 0;
        for (int i = 0; i < resultSuccess.rowCount(); i++) {
            long t = resultSuccess.timestampAt(i);
            assertTrue(t > 1_700_000_000L && t < 4_000_000_000L, "epoch seconds expected: " + t);
            assertTrue(t > prev);
            prev = t;
        }
        // latency percentiles are nanoseconds: sub-millisecond CQL ops on localhost
        assertTrue(resultSuccess.value(0, "p50") > 10_000, "p50 should be ns-scale");

        MetricSeries perOp = series.stream()
                .filter(s -> s.metric().equals("successfor_main_select"))
                .findFirst().orElseThrow();
        assertEquals("main_select", perOp.label("op"));
    }

    @Test
    void resolvesParentDirectoryWithCsvSubdir() {
        assertEquals(FIXTURE.resolve("csv"), CsvMetricsParser.resolveCsvDir(FIXTURE));
        assertEquals(FIXTURE.resolve("csv"), CsvMetricsParser.resolveCsvDir(FIXTURE.resolve("csv")));
    }

    @Test
    void rejectsDirectoryWithoutMetrics(@TempDir Path tmp) {
        assertThrows(IllegalArgumentException.class, () -> CsvMetricsParser.resolveCsvDir(tmp));
    }

    @Test
    void parsesDirectoryWithoutManifest(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("some_timer.csv"),
                "t,count,max,mean,min,stddev,p50,p75,p95,p98,p99,p999,mean_rate,m1_rate,m5_rate,m15_rate,rate_unit,duration_unit\n"
                        + "1786721935,100,2000000,1000000,500000,10000,900000,950000,1100000,1200000,1300000,1900000,20.0,20.1,20.2,20.3,calls/SECONDS,NANOSECONDS\n");
        Files.writeString(tmp.resolve("some_gauge.csv"), "t,value\n1786721935,7.0\n");
        List<MetricSeries> series = new CsvMetricsParser().parseDirectory(tmp);
        assertEquals(2, series.size());
        MetricSeries gauge = series.stream().filter(s -> s.metric().equals("some_gauge")).findFirst().orElseThrow();
        assertEquals(MetricType.GAUGE, gauge.type());
        assertEquals(7.0, gauge.value(0, "value"));
        MetricSeries timer = series.stream().filter(s -> s.metric().equals("some_timer")).findFirst().orElseThrow();
        assertEquals(MetricType.TIMER, timer.type());
        assertEquals(100.0, timer.value(0, "count"));
        // non-numeric trailing columns parse as NaN without failing
        assertTrue(Double.isNaN(timer.value(0, "rate_unit")));
    }

    @Test
    void manifestLastEntryWins(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("m.csv"), "t,value\n1786721935,1.0\n");
        Files.writeString(tmp.resolve(CsvMetricsParser.MANIFEST_FILE),
                "{\"metric\":\"old\",\"labels\":{},\"file\":\"m.csv\",\"first_seen_ms\":1,\"type\":\"gauge\"}\n"
                        + "{\"metric\":\"new\",\"labels\":{\"activity\":\"a\"},\"file\":\"m.csv\",\"first_seen_ms\":2,\"type\":\"gauge\"}\n");
        List<MetricSeries> series = new CsvMetricsParser().parseDirectory(tmp);
        assertEquals(1, series.size());
        assertEquals("new", series.get(0).metric());
        assertEquals("a", series.get(0).label("activity"));
    }
}

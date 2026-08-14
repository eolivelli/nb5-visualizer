# NoSQLBench 5 Visualizer

Turn the metrics of a [NoSQLBench 5](https://github.com/nosqlbench/nosqlbench) (nb5)
CQL benchmark run into a **single, self-contained HTML report** with summaries and
time series:

- actual operation rate (throughput per reporting interval)
- latency percentiles (p50 / p95 / p99 / p99.9, linear or log scale)
- errors (rate, cumulative total, and per-exception-type breakdown)
- a selector to switch between **activities** (scenario steps such as `rampup` /
  `main`) and between the individual **statements** (op templates such as
  `main_select` / `main_insert`) of the run
- per-statement summary table (operations, errors, rates, latencies)

The report is one HTML file with no external resources — open it from disk, mail it,
or archive it next to the run logs. It follows your light/dark color scheme.

![report screenshot](docs/screenshot-light.png)

## Requirements

- **JDK 11 or newer** to run the visualizer (it is a plain executable jar with zero
  runtime dependencies).
- NoSQLBench **5.25+** (the current `nosqlbench/nosqlbench` Docker image or a recent
  `nb5` binary). This tool reads the labeled CSV metrics introduced with nb5's
  snapshot-based metrics system; the old dotted metric names of nb5 5.17/5.21 are
  not supported.

## Build

```bash
mvn package
# -> target/nb5-visualizer-<version>.jar
```

## 1. Run NoSQLBench so it produces input for the visualizer

The visualizer reads the directory written by nb5's `--report-csv-to` option: one CSV
file per metric plus a `metrics-files.jsonl` manifest that maps each file to its
metric name, type and labels (activity, op, …).

The options that matter:

| Option / parameter | Why |
|---|---|
| `--report-csv-to <dir>:<regex>:<interval>` | Writes the per-metric CSVs. The third, colon-separated field is the reporting cadence as a duration (`5s`, `10s`, `1m`); the default is 30s. Note: `--report-interval` does **not** control this. |
| `instrument=true` (activity parameter) | Emits per-statement timers (`successfor_<op>` / `errorsfor_<op>`), which power the per-statement selector and table. Without it you still get per-activity charts. |
| `errors=counter,warn` (activity parameter) | Keeps the run going on statement errors and counts them per exception type (default is `errors=stop`). Enables the per-exception-type error chart. |

### Example: bundled key-value workload against Cassandra 5, everything in Docker

```bash
docker network create nb5net
docker run -d --name cassandra --network nb5net cassandra:5
# wait until: docker exec cassandra cqlsh -e 'describe cluster' succeeds

mkdir -p out
docker run --rm --network nb5net -v "$PWD/out:/out" -w /out \
  --entrypoint java nosqlbench/nosqlbench:latest \
  --enable-preview -XX:+UseZGC -jar /nb5.jar \
  cql_keyvalue default hosts=cassandra localdc=datacenter1 \
  rampup-cycles=100000 main-cycles=1000000 threads=auto \
  instrument=true errors=counter,warn \
  --report-csv-to '/out/csv:.*:5s' \
  --report-summary-to /out/summary.txt
```

(The entrypoint override pins the working directory so nb5's `logs/` also lands in
`out/`; a plain `docker run --rm --network nb5net -v "$PWD/out:/out" nosqlbench/nosqlbench:latest cql_keyvalue ... --report-csv-to '/out/csv:.*:5s'`
works too. The container writes as root — `sudo chown -R "$USER" out` afterwards if
needed.)

### Example: nb5 binary against an existing cluster

```bash
nb5 cql_keyvalue default hosts=10.0.0.5 localdc=dc1 \
  rampup-cycles=1000000 main-cycles=10000000 threads=auto cyclerate=20000 \
  instrument=true errors=counter,warn \
  --report-csv-to 'metrics/csv:.*:5s'
```

### Example: the demo workload of this repository

[`examples/nb5viz_demo.yaml`](examples/nb5viz_demo.yaml) is a key-value workload
(based on nb5's `cql_keyvalue` baseline) whose `witherrors` scenario adds a
statement that fails on every execution, so you can see the error charts in action:

```bash
docker run --rm --network nb5net -v "$PWD/out:/out" -v "$PWD/examples:/workloads" -w /out \
  --entrypoint java nosqlbench/nosqlbench:latest \
  --enable-preview -XX:+UseZGC -jar /nb5.jar \
  /workloads/nb5viz_demo.yaml witherrors \
  hosts=cassandra localdc=datacenter1 \
  rampup-cycles=10000 main-cycles=24000 cyclerate=200 \
  instrument=true errors=counter,warn \
  --report-csv-to '/out/csv:.*:5s'
```

## 2. Generate the report

```bash
java -jar target/nb5-visualizer-*.jar out -o report.html --title "cql_keyvalue baseline"
```

The input is the CSV directory itself (`out/csv`) or its parent (`out`). Then open
`report.html` in a browser.

```
Usage: java -jar nb5-visualizer.jar <metrics-dir> [options]
  -o, --output    output HTML file (default: nb5-report.html)
  --title         report title
```

## What the report shows

- **Activity selector** — one entry per nb5 activity (each scenario step: `schema`,
  `rampup`, `main`, …). Activities shorter than one reporting interval (typically
  the `schema` step) produce no samples and are omitted.
- **Statement selector** — per op template, from the `instrument=true` metrics.
  "All statements" shows the whole activity plus one throughput line per statement.
- **Throughput** — interval op counts divided by the interval length (attempts,
  i.e. successes + errors).
- **Latency percentiles** — from nb5's `result_success` timer (successful ops), or
  `successfor_<op>` for a single statement. Each point is the distribution *within
  that reporting interval* (delta HDR reservoir), in milliseconds. Intervals with
  no operations show gaps, not zeros.
- **Errors** — per-interval error rate from the cumulative `errors_total` gauge
  (activity) or the `errorsfor_<op>` timer (statement), plus the cumulative curve
  broken down by exception type when the run used `errors=counter`.

## Development

```bash
mvn test                 # unit tests (run against recorded real nb5 output)
mvn verify -Pdocker-it   # + integration tests: full Cassandra 5 + nb5 run in
                         #   Docker, and a JDK-11-only runtime check of the jar
```

The integration tests need Docker and pull `cassandra:5`,
`nosqlbench/nosqlbench:latest`, `eclipse-temurin:11-jre` and `alpine` on first use.

The test fixtures in `src/test/resources/fixtures/` are unmodified output of real
nb5 runs against Cassandra 5 (`run-witherrors` was produced by the demo workload
above; `run-short` is a run shorter than the reporting interval, i.e. a single
snapshot).

## Input format notes (for the curious)

- `metrics-files.jsonl` — append-only JSONL manifest; one object per metric
  instance: `{"metric","labels":{...},"file","first_seen_ms","type"}`. Filenames
  are derived from a per-snapshot label diff and are **not stable**; the manifest
  is the source of truth (the visualizer only falls back to filenames when no
  manifest is present).
- Per-metric CSVs, first column `t` = epoch seconds:
  - timer: `t,count,max,mean,min,stddev,p50,p75,p95,p98,p99,p999,mean_rate,m1_rate,m5_rate,m15_rate,rate_unit,duration_unit` (durations in nanoseconds)
  - histogram: `t,count,max,mean,min,stddev,p50,p75,p95,p98,p99,p999`
  - meter: `t,count,mean_rate,m1_rate,m5_rate,m15_rate,rate_unit`
  - gauge: `t,value`
- `count` is per reporting interval; `errors_total` is cumulative;
  `errors_<ExceptionType>` gauges appear with `errors=counter`.

## License

[Apache License 2.0](LICENSE)

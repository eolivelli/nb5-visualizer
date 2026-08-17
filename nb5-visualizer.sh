#!/usr/bin/env bash
# Launches the headless CLI (nb5-visualizer-cli: core CLI + SSH support, no
# terminal UI) without needing to know the jar path; builds it first if it
# isn't there yet.
# Usage: ./nb5-visualizer.sh <metrics-dir> [options]              (see --help)
#        ./nb5-visualizer.sh --ssh user@host <remote-dir> [options]
set -euo pipefail

dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

find_jar() {
  # the shade plugin leaves an original-*.jar next to the real one; the glob
  # below only matches the shaded artifact
  ls -t "$dir"/nb5-visualizer-cli/target/nb5-visualizer-cli-*.jar 2>/dev/null | head -1 || true
}

jar="$(find_jar)"
if [[ -z "$jar" ]]; then
  echo "Jar not built yet — running 'mvn package' once..." >&2
  (cd "$dir" && mvn -B -q -DskipTests package)
  jar="$(find_jar)"
fi
if [[ -z "$jar" ]]; then
  echo "Build did not produce nb5-visualizer-cli/target/nb5-visualizer-cli-*.jar" >&2
  exit 1
fi

exec java -jar "$jar" "$@"

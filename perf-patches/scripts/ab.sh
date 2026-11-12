#!/bin/bash
# A/B runner: runs the same JMH benchmark jar against the baseline lucene-core jar and against a
# patched lucene-core jar, on the same on-disk indexes, with the same JDK and JVM flags.
#
# usage: ab.sh <variant> <label> <jmh benchmark regex> [extra JMH args...]
#   <variant>  "base" or the name of a patch worktree under $WT (p1, p2, ...)
#   <label>    name of the result files under $BENCH/results
set -euo pipefail
BENCH=${BENCH:-$HOME/lucene-bench}
WT=${WT:-$HOME/lucene-wt}
export JAVA_HOME=${BENCH_JAVA_HOME:-/usr/lib/jvm/java-25-openjdk-amd64}
variant=$1; label=$2; regex=$3; shift 3

mkdir -p "$BENCH/results" "$BENCH/indexes"
mp=$BENCH/mp/$variant
rm -rf "$mp"; mkdir -p "$mp"
# Benchmark jar + dependencies always come from the harness worktree (baseline + benchmarks) ...
cp $WT/base/lucene/benchmark-jmh/build/benchmarks/*.jar "$mp"/
if [ "$variant" != "base" ]; then
  # ... and only the lucene-core jar is swapped for the patched build.
  cp $WT/$variant/lucene/core/build/libs/lucene-core-*.jar "$mp"/
fi
sha256sum "$mp"/lucene-core-*.jar | cut -c1-16 > $BENCH/results/$label.$variant.jarsha

unset JAVA_TOOL_OPTIONS
exec $JAVA_HOME/bin/java --add-modules jdk.incubator.vector \
  --module-path "$mp" --module org.apache.lucene.benchmark.jmh "$regex" \
  -jvmArgsAppend "-Dbench.index.dir=$BENCH/indexes" \
  -rf json -rff $BENCH/results/$label.$variant.json "$@" 2>&1 | tee $BENCH/results/$label.$variant.log

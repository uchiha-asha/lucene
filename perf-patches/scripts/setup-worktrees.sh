#!/bin/bash
# Recreates the layout that the A/B benchmarks expect, from the patches in ../patches:
#   $WT/base  baseline commit + the JMH benchmarks (0000-*.patch)
#   $WT/pN    baseline commit + patch N only
# and builds the benchmark jars (base) and the lucene-core jar (each pN).
#
# usage: setup-worktrees.sh [path to a checkout of lucene that contains the baseline commit]
set -euo pipefail
REPO=${1:-$(git rev-parse --show-toplevel)}
HERE=$(cd "$(dirname "$0")/.." && pwd)
WT=${WT:-$HOME/lucene-wt}
BASELINE=8476457b8752be2fd8094eaf5cd9bf0cea9594ae
export JAVA_HOME=${BENCH_JAVA_HOME:-/usr/lib/jvm/java-25-openjdk-amd64}

mkdir -p "$WT"
add() { # <dir> <patch file>
  if [ ! -d "$WT/$1" ]; then
    git -C "$REPO" worktree add --detach "$WT/$1" $BASELINE
    git -C "$WT/$1" am "$2"
  fi
}
add base "$HERE"/patches/0000-*.patch
(cd "$WT/base" && ./gradlew -p lucene/benchmark-jmh assemble)
for n in 1 2 3 4 5; do
  add p$n "$HERE"/patches/000$n-*.patch
  (cd "$WT/p$n" && ./gradlew :lucene:core:jar)
done
# p34 = patch 4 + patch 3, only used to measure patch 3 without the overhead that patch 4 removes
add p34 "$HERE"/patches/0004-*.patch
git -C "$WT/p34" am "$HERE"/patches/0003-*.patch || true
(cd "$WT/p34" && ./gradlew :lucene:core:jar)

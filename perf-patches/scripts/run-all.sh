#!/bin/bash
# Runs every A/B comparison: for each patch, the baseline first and then the patched build, with
# identical JMH options (3 forks x (3 warmup + 5 measurement iterations of 1s) unless OPTS says otherwise). Results (JSON + logs) go to $BENCH/results.
export BENCH=${BENCH:-$HOME/lucene-bench}
SCRIPTS=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$BENCH/results"
OPTS=${OPTS:-"-f 3 -wi 3 -i 5 -w 1 -r 1"}

ab() { # <patch> <label> <regex> [jmh args...]
  local patch=$1 label=$2 regex=$3; shift 3
  for variant in ${BASE:-base} $patch; do
    echo "=== $(date +%H:%M:%S) $label $variant"
    "$SCRIPTS"/ab.sh $variant $label "$regex" $OPTS "$@" > /dev/null 2>&1 || echo "FAILED: $label $variant"
  done
}

# #1 (worktree p4): ConstantScoreBulkScorer on sparse matches
ab p4 p4-postings 'SparseFilterBulkScorerBenchmark' -p source=postings -p gap=100000,10000,1000,256,100,50
ab p4 p4-points   'SparseFilterBulkScorerBenchmark' -p source=points -p gap=10000,1000,100
ab p4 p4-cluster  'SparseFilterBulkScorerBenchmark' -p source=cluster -p gap=50
ab p4 p4-deletes  'SparseFilterBulkScorerBenchmark' -p source=postings -p gap=10000,256,50 -p deletedPercent=5

# #2 (worktree p2): postings enum buffers; the second run reports allocation rates
ab p2 p2-postings 'PostingsEnumAllocationBenchmark'
OPTS="-f 1 -wi 3 -i 5 -w 1 -r 1" ab p2 p2-gc 'PostingsEnumAllocationBenchmark' -prof gc

# #3 (worktree p1): dense multi-valued doc values
ab p1 p1-collect  'MultiValuedDocValuesIterationBenchmark.sorted(Set|Numeric)Collect'
ab p1 p1-nextdoc  'MultiValuedDocValuesIterationBenchmark.sorted(Set|Numeric)NextDoc' -p matchPercent=100

# #4 (worktree p3): DocIdSetBuilder sort; the last run compares #1 alone (p4) with #1 + #4 (p34)
ab p3 p3-1m       'SparsePointRangeQueryBenchmark' -p maxDoc=1000000 -p numHits=100,3000
ab p3 p3-20m      'SparsePointRangeQueryBenchmark' -p maxDoc=20000000 -p numHits=100,5000,50000
BASE=p4 ab p34 p3-on-p4 'SparsePointRangeQueryBenchmark' -p maxDoc=20000000 -p numHits=100,5000,50000

# #5 (worktree p5): DenseConjunctionBulkScorer cardinality
OPTS="-f 4 -wi 3 -i 8 -w 1 -r 1" ab p5 p5-existing 'DefaultBulkScorerBenchmark.scoreIntoBitSet' -p density=0.1,0.5
OPTS="-f 4 -wi 3 -i 8 -w 1 -r 1" ab p5 p5-cached 'DenseFilterBulkScorerBenchmark' -p query=cached -p deletedPercent=0
ab p5 p5-search   'DenseFilterBulkScorerBenchmark' -p query=single,conjunction
echo "=== $(date +%H:%M:%S) ALL DONE"

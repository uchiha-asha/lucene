# Five performance patches for Apache Lucene

Baseline: `8476457b8752be2fd8094eaf5cd9bf0cea9594ae` ("Lower min supported major version to 9 in
main (#16741)", 2026-10-15), the commit this repository was checked out at. Every patch is one
commit on top of that baseline, touches different files than the other four, and was tested and
benchmarked on its own against the unmodified baseline.

## Summary

Ranked in the order I would submit them upstream. Lower is better except where noted. "±" is
JMH's 99.9% confidence interval, "fork spread" the relative difference between the slowest and
fastest of the fresh JVMs (baseline / patched). Each row is the representative workload; the
per-patch sections have the full parameter sweeps.

| # | Patch: branch (commit) | Code path | Workload | Baseline | Patched | Change | Run-to-run variation | Worst observed tradeoff |
|---|---|---|---|---:|---:|---:|---|---|
| 1 | `perf/constant-score-bulk-scorer-sparse` (`02b58eefe`) | `ConstantScoreBulkScorer#scoreIteratorIntoBitSet`, reached from `IndexSearcher.search` for filters without scores that match < 1/32 of a segment | Term filter matching 1 doc in 1,000 of a 4M-doc segment; collector reads one doc value per hit | 149.2 ± 8.2 µs | 33.7 ± 3.7 µs | **-77.4%** | fork spread 5.2% / 8.7% | +8.2% (219.5 ± 13.9 -> 237.5 ± 18.7 µs, intervals overlap): counting hits of a `FixedBitSet`-backed range filter at 1% density |
| 2 | `perf/postings-enum-lazy-buffers` (`e26422c26`) | `Lucene104PostingsReader.BlockPostingsEnum` constructor, reached from `TermsEnum#postings` | Top 10 of 500K filtered hits sorted by a 100K-cardinality keyword, 1M docs, 1 segment | 1529 ± 71 µs | 741 ± 55 µs | **-51.6%** (allocation -57%) | fork spread 0.9% / 9.8% | +3.7% (1128 ± 62 -> 1169 ± 37 µs, intervals overlap): iterating one dense postings list |
| 3 | `perf/dv-multivalued-address-reuse` (`3767e688c`) | `Lucene90DocValuesProducer`: dense SORTED_SET / SORTED_NUMERIC `advance`, `advanceExact` | Read all ordinals of every doc under a match-all search, 2M docs, 2.5 values per doc | 76.1 ± 5.2 ms | 64.1 ± 4.3 ms | **-15.7%** | fork spread 7.6% / 3.8% | +8.4% (37.2 ± 1.5 -> 40.3 ± 2.5 ms, intervals overlap): sorted-numeric values of a random 50% of docs |
| 4 | `perf/docidsetbuilder-narrow-range-sort` (`b3aa8f3a2`) | `DocIdSetBuilder#build`, reached from `PointRangeQuery` when < 1/128 of a segment matches | Range matching 5,000 docs that are clustered in ~5,100 adjacent doc IDs of a 20M-doc segment | 103.5 ± 7.0 µs | 74.2 ± 4.0 µs | **-28.3%** | fork spread 8.1% / 2.4% | +3.9% (24.3 ± 1.2 -> 25.3 ± 1.9 µs, intervals overlap): 100 scattered hits; +6.7% (intervals overlap) for 5,000 scattered hits when measured on top of #1 |
| 5 | `perf/dense-conjunction-skip-unused-cardinality` (`79e1e1f29`) | `DenseConjunctionBulkScorer#scoreWindowUsingBitSet`, reached from `IndexSearcher.search` for filters without scores that match >= 1/32 of a segment | Count the hits of a cached filter that matches 10% of a 4M-doc segment | 105.4 ± 6.3 µs | 90.5 ± 5.7 µs | **-14.1%** | fork spread 6.0% / 8.9% | +5.0% (1985 ± 82 -> 2084 ± 112 µs, intervals overlap): per-doc collector on a term filter, where the patch removes work and adds none |

What the table does not show:

- #5's gain is only visible when the window is cheap to load and the collector does almost
  nothing: +6.5% to +25% throughput on the pre-existing `DefaultBulkScorerBenchmark`, -9% to -14%
  on a cached filter count, but within noise (-11% to +5%) for term filters read from postings.
- #3 costs a little in one case (random 50% of docs) and #1 probably costs ~8% in one
  (counting a bit-set-backed filter at exactly 1% density). Both are within the overlap of the
  confidence intervals but consistent enough across runs that I would mention them in the PRs.
- Checksums of collected doc IDs, values, counts and top hits are identical between baseline and
  patch for every benchmark and parameter combination (`results/summary.md`).


## Where everything is

| What | Where |
|---|---|
| Standalone patch branches (local only, each = baseline + 1 commit) | `perf/constant-score-bulk-scorer-sparse`, `perf/postings-enum-lazy-buffers`, `perf/dv-multivalued-address-reuse`, `perf/docidsetbuilder-narrow-range-sort`, `perf/dense-conjunction-skip-unused-cardinality` |
| Worktrees of those branches | `/home/user/lucene-wt/p4` (#1), `p2` (#2), `p1` (#3), `p3` (#4), `p5` (#5); `base` = baseline + benchmarks (branch `perf/benchmarks`). Worktree and patch-file numbers follow the order in which the patches were written, not the ranking |
| The same commits stacked, pushed | branch `claude/lucene-perf-optimizations-dvw5t2`: baseline, benchmarks, then the five patches in submission order |
| Patches as files (`git am`-able on the baseline) | `perf-patches/patches/0001..0005-*.patch`, benchmarks in `0000-jmh-benchmarks.patch` |
| Benchmark code | `lucene/benchmark-jmh/src/java/org/apache/lucene/benchmark/jmh/{SparseFilterBulkScorer,DenseFilterBulkScorer,MultiValuedDocValuesIteration,PostingsEnumAllocation,SparsePointRangeQuery}Benchmark.java` |
| A/B scripts | `perf-patches/scripts/{setup-worktrees,ab,run-all}.sh`, `summarize.py` |
| Raw results (JMH JSON + full logs) | `perf-patches/results/`, generated tables in `perf-patches/results/summary.md` |

The patch branches and worktrees only exist in this container; the pushed branch and the
`patches/` directory are what survives it.

## Environment

- Machine: KVM guest, 4 vCPUs of an Intel Xeon @ 2.10GHz (AVX-512; 48 KiB L1d and 2 MiB L2 per
  core), 15 GiB RAM, Ubuntu 24.04.5, kernel 6.18.44. No CPU pinning or isolation is available, the
  host is shared.
- JDK: OpenJDK 25.0.5.0.3+1 (Ubuntu 24.04 package), G1, compressed oops. JMH 1.37.
- JVM flags, identical for baseline and patched runs: `--add-modules jdk.incubator.vector` (Lucene
  logs `Java vector incubator API enabled; uses preferredBitSize=512`), and per fork
  `-Xmx2g -Xms2g -XX:+AlwaysPreTouch` (`-Xmx1g -Xms1g` for the pre-existing
  `DefaultBulkScorerBenchmark`).
- JMH settings: 3 forks (fresh JVMs) x (3 warmup + 5 measurement iterations of 1s), single thread.
  Exceptions, noted where they apply: 4 forks x 8 iterations for the two runs that carry #5's
  claim, 1 fork for the allocation profile of #2.
- Query cache disabled (`searcher.setQueryCache(null)`) so that queries are evaluated rather than
  served from the cache, except in the one benchmark case that measures a cached filter (#5).

## How the comparisons were made

- One benchmark jar, built from baseline + benchmarks (`/home/user/lucene-wt/base`), is used for
  both sides. `ab.sh` copies its module path and, for the patched side, swaps in the
  `lucene-core` jar built from the patch's worktree. Nothing else differs.
- Indexes are built once, with fixed seeds, into `$BENCH/indexes` (`-Dbench.index.dir`) and reused
  by every fork of both sides, so both builds read the very same files. Index building and reader
  opening happen in `@Setup`, outside the timed region.
- Every benchmark goes through `IndexSearcher.search` (plus two `LeafReader` doc-values `nextDoc()`
  loops) and checks in `@Setup` that the implementation it means to measure is the one that runs
  (bulk scorer class, iterator class, dense multi-valued doc values), failing otherwise.
- Every benchmark method returns a checksum of what it collected (doc IDs in collection order, doc
  values, hit counts, top hits) and prints it at teardown. `summarize.py` compares the checksums of
  both sides; they are identical for every benchmark and parameter combination below.
- "±" is the half-width of JMH's 99.9% confidence interval over all measurement iterations (15,
  or 32 for the 4-fork runs). "Fork spread" is (max - min) / mean of the per-fork means, i.e. the
  run-to-run variation between fresh JVMs.

### Commands

```bash
# 1. worktrees + builds (already done in this container)
perf-patches/scripts/setup-worktrees.sh

# 2. everything: ~90 minutes
BENCH=/home/user/lucene-bench WT=/home/user/lucene-wt perf-patches/scripts/run-all.sh
BENCH=/home/user/lucene-bench perf-patches/scripts/summarize.py

# a single comparison, e.g. #1 (worktree p4) on sparse postings
export BENCH=/home/user/lucene-bench WT=/home/user/lucene-wt
S=perf-patches/scripts
$S/ab.sh base my-label 'SparseFilterBulkScorerBenchmark' -f 3 -wi 3 -i 5 -w 1 -r 1 -p source=postings
$S/ab.sh p4   my-label 'SparseFilterBulkScorerBenchmark' -f 3 -wi 3 -i 5 -w 1 -r 1 -p source=postings
$S/summarize.py my-label

# tests, in each patch worktree
./gradlew -p lucene/core test
```

`run-all.sh` lists the exact benchmark regexes and parameters of every group reported here. The
scripts use the JDK at `/usr/lib/jvm/java-25-openjdk-amd64` unless `BENCH_JAVA_HOME` is set; the
Gradle build needs `JAVA_HOME` to point at a JDK 25.

# The patches

## #1 Collect sparse matches one by one in `ConstantScoreBulkScorer`

Worktree `p4`, branch `perf/constant-score-bulk-scorer-sparse`, commit `02b58eefe` (`cea134f8e` on the pushed
branch), `patches/0004-*.patch`. Files: `search/ConstantScoreBulkScorer.java` (+ tests).

**Hypothesis before editing.** `ConstantScoreBulkScorer#scoreIteratorIntoBitSet` handles
every filter that does not need scores and matches less than 1/32th of a segment
(`ConstantScoreScorerSupplier#bulkScorer`: term filters, point ranges, multi-term queries, cached
filters). For each window of 4096 doc IDs that starts on a match, it calls
`iterator.intoBitSet`, wraps the 64-long bit set in a `BitSetDocIdStream` that the collector
iterates, and clears it. That work is proportional to the window, not to the matches in it, so a
filter whose matches are more than 4096 docs apart writes, scans and clears 64 longs per match.
The cheapest experiment that could disprove it: time `bulkScorer().score(collector)` against a
plain `nextDoc()`/`collect(doc)` loop over the same iterator, across densities. It did not
disprove it: on a 4.7M-doc segment the windowed scorer took 127µs vs 10.5µs for a term with 4,011
matches, and was still slower with a collector that only counts through `DocIdStream#count()`.
The routing came with apache/lucene#16141/#16143, whose benchmark only covers a `BitSetIterator`
at densities of 1% and more with a counting collector.

**Change.** For plain (non-two-phase) iterators the scorer starts by collecting matches one at a
time. If 32 matches fall within 4096 doc IDs it switches to bit-set windows (keeping the window
base a multiple of 64 away from the first match, so that runs of adjacent doc IDs still copy whole
words), and it goes back to one-by-one collection when the next match is at least 512 docs past
the end of a window and that window had fewer than 32 matches. Two-phase iterators and the
competitive-iterator path are untouched.

**Why it gets faster.** The per-window cost (measured ~70-110ns here) is replaced by a per-match
cost of a `nextDoc()` plus a `collect(doc)` (2-3ns) wherever windows would hold few matches.

**Who benefits.** Any non-scoring query whose only clause, or whose conjunction, is sparse: a term
or `TermInSetQuery` filter on a selective value, a narrow range, with aggregation-style collectors,
hit counting on segments with deletions, or when the query cache materializes the filter.

**When the benefit disappears.** At 32 or more matches per 4096 docs (density >= 1/128) the old
path is used and nothing changes; at 1/32 and above `DenseConjunctionBulkScorer` takes over as
before. Two-phase filters (e.g. doc-values ranges) never use the new path.

Results (µs per search, lower is better; `gap` = average number of docs between matches; 4M-doc segment, 3 forks):

**Term filter (postings)**

| benchmark | params | baseline | ± | fork spread | patched | ± | fork spread | change |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| collectDocIds | gap=100000 | 4.404 µs | 0.452 | 3.2% | 1.922 | 0.075 | 1.7% | -56.3% |
| collectDocIds | gap=10000 | 26.10 µs | 1.884 | 10.3% | 3.865 | 0.203 | 7.5% | -85.2% |
| collectDocIds | gap=1000 | 140.8 µs | 10.42 | 4.9% | 15.76 | 0.974 | 7.0% | -88.8% |
| collectDocIds | gap=256 | 266.1 µs | 33.52 | 10.4% | 59.49 | 3.216 | 3.7% | -77.6% |
| collectDocIds | gap=100 | 550.5 µs | 31.75 | 3.7% | 533.9 | 23.17 | 5.5% | -3.0% |
| collectDocIds | gap=50 | 828.3 µs | 58.84 | 1.1% | 740.2 | 23.98 | 1.5% | -10.6% |
| collectDocValues | gap=100000 | 4.586 µs | 0.424 | 11.1% | 2.259 | 0.081 | 3.2% | -50.7% |
| collectDocValues | gap=10000 | 27.69 µs | 3.757 | 5.0% | 5.587 | 0.228 | 1.0% | -79.8% |
| collectDocValues | gap=1000 | 149.2 µs | 8.202 | 5.2% | 33.71 | 3.659 | 8.7% | -77.4% |
| collectDocValues | gap=256 | 424.6 µs | 33.75 | 9.2% | 160.7 | 27.39 | 11.4% | -62.2% |
| collectDocValues | gap=100 | 966.0 µs | 44.02 | 5.0% | 993.9 | 120.3 | 4.2% | +2.9% |
| collectDocValues | gap=50 | 1403 µs | 110.3 | 2.7% | 1329 | 63.81 | 2.8% | -5.3% |
| count | gap=100000 | 2.760 µs | 0.123 | 2.5% | 1.876 | 0.091 | 3.4% | -32.0% |
| count | gap=10000 | 11.09 µs | 0.637 | 3.9% | 3.911 | 0.315 | 16.1% | -64.7% |
| count | gap=1000 | 34.37 µs | 3.397 | 13.1% | 14.35 | 1.195 | 9.7% | -58.2% |
| count | gap=256 | 57.12 µs | 4.538 | 11.9% | 51.93 | 4.272 | 7.4% | -9.1% |
| count | gap=100 | 98.06 µs | 8.496 | 1.4% | 96.33 | 3.941 | 2.7% | -1.8% |
| count | gap=50 | 162.0 µs | 14.35 | 5.0% | 165.8 | 12.22 | 3.7% | +2.4% |

**Point range filter (sorted int[] at gap 10000 and 1000, `FixedBitSet` at gap 100)**

| benchmark | params | baseline | ± | fork spread | patched | ± | fork spread | change |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| collectDocIds | gap=10000 | 42.25 µs | 3.148 | 6.2% | 10.51 | 0.515 | 6.9% | -75.1% |
| collectDocIds | gap=1000 | 168.3 µs | 12.99 | 13.8% | 47.49 | 2.422 | 7.8% | -71.8% |
| collectDocIds | gap=100 | 696.6 µs | 47.81 | 9.1% | 706.0 | 37.83 | 3.6% | +1.4% |
| collectDocValues | gap=10000 | 45.50 µs | 3.587 | 8.8% | 12.77 | 0.731 | 1.4% | -71.9% |
| collectDocValues | gap=1000 | 196.9 µs | 10.63 | 4.3% | 60.88 | 3.636 | 1.8% | -69.1% |
| collectDocValues | gap=100 | 1109 µs | 33.90 | 2.1% | 1145 | 79.64 | 6.9% | +3.2% |
| count | gap=10000 | 17.21 µs | 0.705 | 1.2% | 11.59 | 1.601 | 7.6% | -32.6% |
| count | gap=1000 | 69.68 µs | 4.547 | 9.6% | 49.18 | 3.591 | 10.7% | -29.4% |
| count | gap=100 | 219.5 µs | 13.92 | 7.8% | 237.5 | 18.73 | 5.9% | +8.2% |

**Term whose 62,500 matches are one run of adjacent docs (1/64th of the segment)**

| benchmark | params | baseline | ± | fork spread | patched | ± | fork spread | change |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| collectDocIds | source=cluster | 129.8 µs | 8.619 | 6.3% | 126.6 | 7.699 | 9.3% | -2.4% |
| collectDocValues | source=cluster | 449.4 µs | 79.04 | 24.1% | 473.8 | 80.66 | 11.7% | +5.4% |
| count | source=cluster | 8.702 µs | 1.107 | 10.8% | 8.847 | 0.913 | 15.7% | +1.7% |

**Term filter on a segment with 5% deleted docs**

| benchmark | params | baseline | ± | fork spread | patched | ± | fork spread | change |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| collectDocIds | gap=10000 | 42.40 µs | 2.714 | 7.6% | 4.513 | 0.680 | 13.3% | -89.4% |
| collectDocIds | gap=256 | 292.9 µs | 23.74 | 2.6% | 69.94 | 7.866 | 17.5% | -76.1% |
| collectDocIds | gap=50 | 816.7 µs | 88.44 | 11.5% | 843.1 | 63.88 | 4.9% | +3.2% |
| collectDocValues | gap=10000 | 47.79 µs | 7.221 | 16.0% | 6.251 | 0.434 | 5.5% | -86.9% |
| collectDocValues | gap=256 | 673.1 µs | 156.6 | 18.5% | 284.3 | 53.20 | 22.3% | -57.8% |
| collectDocValues | gap=50 | 1476 µs | 125.3 | 9.2% | 1385 | 80.79 | 2.6% | -6.1% |
| count | gap=10000 | 30.23 µs | 4.233 | 12.8% | 3.854 | 0.276 | 8.8% | -87.2% |
| count | gap=256 | 109.6 µs | 8.486 | 3.4% | 70.30 | 4.160 | 10.9% | -35.9% |
| count | gap=50 | 235.0 µs | 39.16 | 22.4% | 210.9 | 18.41 | 8.6% | -10.3% |


Tradeoffs: the nearby cases are the densities right above the switch (gap 100 and 50), a
`FixedBitSet`-backed point range at 1% density, a term whose matches are one run of adjacent docs,
and segments with deleted docs. All are within noise except counting the hits of the
`FixedBitSet`-backed range at 1% density: +8.2% here (219.5 ± 13.9 -> 237.5 ± 18.7 µs, intervals
overlap), and +5% to +11% in two runs of earlier revisions of the patch, so it is probably real.
The extra checks cannot account for it (they run for under 1% of windows); the likely cause is
that the larger method is compiled differently. Two regressions that showed up during development
were removed and are covered by these controls: counting every window (up to +29% for counting
collectors at 1-2% density) and windows that were no longer word-aligned with runs of adjacent
docs (+17% on the contiguous-run case).

Left on the table: postings with 32 to 128 matches per window (gap 100 and 50) still use bit
sets. In a scratch comparison on the baseline (same JVM, best of 1,500 runs, not JMH), a plain loop was
1.4x faster there with a counting collector and 1.5-5x faster with per-doc collectors, but bit
sets win for `FixedBitSet`-backed iterators, so the switch stays below the 1% density that
upstream's benchmark covers.

Suggested CHANGES entry: *ConstantScoreBulkScorer collects matches one by one where they are
sparse instead of loading every window of doc IDs into a bit set, which speeds up selective
filters that don't need scores.*

Upstream overlap: none found (`ConstantScoreBulkScorer` appears in three PRs, all merged before
the baseline: #16141, #16143, #16146).

## #2 Don't eagerly allocate buffers that postings enums may not need

Worktree `p2`, branch `perf/postings-enum-lazy-buffers`, commit `e26422c26` (`ec6adca39` on the pushed branch),
`patches/0002-*.patch`. File: `codecs/lucene104/Lucene104PostingsReader.java`.

**Hypothesis before editing.** Every `BlockPostingsEnum` allocates three 1kB buffers in its constructor:
`docBuffer`, a `FixedBitSet` of 8192 bits that is only used by blocks encoded as bit sets (which
need at least 256 docs), and `freqBuffer`, which it fills with ones when frequencies are not
requested (`PostingsEnum.NONE`, i.e. every filter). Queries that pull many postings enums for
low-frequency terms pay for 2kB of allocation, zeroing and filling per enum that nothing reads.
Evidence before editing: about 40% of the samples of a sort on a high-cardinality keyword were
spent creating postings enums (`BlockPostingsEnum.<init>` 16%, `FixedBitSet.<init>` 5%, the rest
cloning the input); `TermOrdValComparator` pulls up to 1024 postings enums per segment to build
its competitive iterator. Cheapest disproof: make both
lazy and see whether such a query moves; it went from 1.95ms to 1.13ms in the scratch harness.

**Change.** The bit set is allocated when the first bit-set-encoded block is read. Enums that do
not need frequencies share one static, read-only array of ones.

**Why it gets faster.** About 1kB instead of 3kB is allocated per postings enum that does not need
frequencies (2kB instead of 3kB with frequencies), and the `Arrays.fill` is gone.

**Who benefits.** Queries that create many postings enums relative to the postings they read:
disjunctions and `TermInSetQuery` rewrites over many rare terms, multi-term queries, sorting by an
indexed keyword field with pruning, many small segments.

**When the benefit disappears.** When postings enums are reused (`TermsEnum#postings(reuse, ..)`),
when the time is spent iterating long postings rather than creating enums, and for terms with
bit-set-encoded blocks (the bit set is then allocated on the first such block, as before).

Results (µs per search; 1M docs, 100K distinct ids, 3 forks):

**Time**

| benchmark | params | baseline | ± | fork spread | patched | ± | fork spread | change |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| denseTermFilter | numSegments=1 | 1128 µs | 61.64 | 3.6% | 1169 | 36.81 | 1.3% | +3.7% |
| denseTermFilter | numSegments=20 | 1204 µs | 25.04 | 0.9% | 1191 | 75.19 | 3.9% | -1.1% |
| disjunctionOf1024Ids | numSegments=1 | 4969 µs | 189.8 | 6.2% | 4307 | 197.7 | 6.4% | -13.3% |
| disjunctionOf1024Ids | numSegments=20 | 40401 µs | 1959 | 2.1% | 35970 | 1591 | 3.5% | -11.0% |
| disjunctionOf64Ids | numSegments=1 | 225.9 µs | 10.47 | 4.4% | 189.3 | 9.010 | 6.0% | -16.2% |
| disjunctionOf64Ids | numSegments=20 | 2190 µs | 71.70 | 2.2% | 1938 | 79.64 | 1.8% | -11.5% |
| sortByKeyword | numSegments=1 | 1529 µs | 71.05 | 0.9% | 740.7 | 54.94 | 9.8% | -51.6% |
| sortByKeyword | numSegments=20 | 1218 µs | 77.03 | 7.9% | 826.0 | 56.16 | 10.2% | -32.2% |

**Allocation (`-prof gc`, 1 fork; the `gc.alloc.rate.norm` rows are bytes allocated per search)**

| benchmark | params | baseline | ± | fork spread | patched | ± | fork spread | change |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| denseTermFilter | numSegments=1 | 1155 µs | 333.5 | n/a | 1125 | 318.7 | n/a | -2.6% |
| denseTermFilter | numSegments=1, gc.alloc.rate.norm | 11497 B/op | | | 10390 B/op | | | -9.6% |
| denseTermFilter | numSegments=20 | 1296 µs | 293.8 | n/a | 1170 | 248.3 | n/a | -9.7% |
| denseTermFilter | numSegments=20, gc.alloc.rate.norm | 212376 B/op | | | 191537 B/op | | | -9.8% |
| disjunctionOf1024Ids | numSegments=1 | 4997 µs | 1250 | n/a | 4212 | 586.4 | n/a | -15.7% |
| disjunctionOf1024Ids | numSegments=1, gc.alloc.rate.norm | 9703273 B/op | | | 7571790 B/op | | | -22.0% |
| disjunctionOf1024Ids | numSegments=20 | 42937 µs | 12974 | n/a | 34046 | 1264 | n/a | -20.7% |
| disjunctionOf1024Ids | numSegments=20, gc.alloc.rate.norm | 120148566 B/op | | | 102639458 B/op | | | -14.6% |
| disjunctionOf64Ids | numSegments=1 | 236.2 µs | 33.22 | n/a | 190.2 | 64.48 | n/a | -19.5% |
| disjunctionOf64Ids | numSegments=1, gc.alloc.rate.norm | 606997 B/op | | | 472305 B/op | | | -22.2% |
| disjunctionOf64Ids | numSegments=20 | 2485 µs | 610.2 | n/a | 2051 | 481.8 | n/a | -17.4% |
| disjunctionOf64Ids | numSegments=20, gc.alloc.rate.norm | 7595548 B/op | | | 6501499 B/op | | | -14.4% |
| sortByKeyword | numSegments=1 | 1471 µs | 313.6 | n/a | 753.9 | 160.7 | n/a | -48.7% |
| sortByKeyword | numSegments=1, gc.alloc.rate.norm | 3683063 B/op | | | 1594602 B/op | | | -56.7% |
| sortByKeyword | numSegments=20 | 1210 µs | 231.0 | n/a | 864.9 | 192.9 | n/a | -28.5% |
| sortByKeyword | numSegments=20, gc.alloc.rate.norm | 2066282 B/op | | | 1046390 B/op | | | -49.4% |


Behaviour note: `freq()` on an enum pulled with `PostingsEnum.NONE` now returns 1 for terms that
occur in a single document too (it used to return the real frequency in that one case; the javadoc
says the value is undefined without `FREQS`).

Suggested CHANGES entry: *Postings enums lazily allocate the bit set they use to decode dense
blocks and share a buffer of ones when frequencies are not requested, which saves 2kB of
allocations per postings enum.*

Upstream overlap: none open. Related history: #12954 (closed unmerged, 2024) tried to shrink
the frequency buffer when frequencies are not needed, and #12997 (merged) stopped resetting it;
neither shares one buffer or defers the bit set.

## #3 Reuse the previous end address when iterating dense multi-valued doc values

Worktree `p1`, branch `perf/dv-multivalued-address-reuse`, commit `3767e688c` (`f204f4c92` on the pushed
branch), `patches/0001-*.patch`. File: `codecs/lucene90/Lucene90DocValuesProducer.java` (+ test).

**Hypothesis before editing.** The dense `SORTED_NUMERIC` and `SORTED_SET` implementations call
`addresses.get(target)` and `addresses.get(target + 1)` on every `advance`/`advanceExact`.
`addresses` is a `DirectMonotonicReader` (two array loads, a long-to-float-to-long round trip, a
packed read). When the target is the doc after the current one, the start address is the end
address that was just read. Evidence before editing: collecting a multi-valued keyword over all
docs cost 40ns per doc with `DirectMonotonicReader.get` at 13-23% of samples plus the packed reads
under it. Cheapest disproof: reuse `end` and time a match-all collection; it went from 205ms to
175ms (keyword) and 154ms to 125ms (numeric) in the scratch harness.

**Change.** `start = target == doc + 1 ? end : addresses.get(target)`, in the dense multi-valued
`SortedNumericDocValues` and `SortedSetDocValues` of `Lucene90DocValuesProducer`. The same idea
was applied to `rangeIntoBitSet` by apache/lucene#16399.

**Why it gets faster.** One address lookup per doc instead of two when docs are visited in
sequence.

**Who benefits.** Anything that reads all values of a dense multi-valued field for runs of adjacent
docs: aggregations/faceting under match-all or dense filters (especially filters that match
contiguous doc ranges), `nextDoc()` loops (merges, doc-values queries, index sorting).

**When the benefit disappears.** Single-valued fields (they use a different code path), sparse
fields (`IndexedDISI`-based, untouched), and selective filters: at 10% and 1% of docs the result
is within noise.

Tradeoff: when about half of the docs are visited in random order, the adjacent/non-adjacent
branch is unpredictable and offsets the saved lookup: +8.4% for sorted-numeric at 50% (37.2 ± 1.5
-> 40.3 ± 2.5 ms, intervals overlap), -3.6% for sorted-set. Filters that match runs of adjacent
docs do not have this problem.

Results (ms per pass over a 2M-doc segment with 1-4 values per doc, 3 forks; `matchPercent` = share of docs that the filter matches, picked at random):

**Collecting all values of matching docs through `IndexSearcher.search` + `advanceExact`**

| benchmark | params | baseline | ± | fork spread | patched | ± | fork spread | change |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| sortedNumericCollect | matchPercent=100 | 71.35 ms | 6.314 | 13.8% | 64.39 | 2.928 | 5.5% | -9.8% |
| sortedNumericCollect | matchPercent=50 | 37.16 ms | 1.462 | 5.7% | 40.27 | 2.468 | 6.9% | +8.4% |
| sortedNumericCollect | matchPercent=10 | 8.266 ms | 0.512 | 7.6% | 8.268 | 0.649 | 8.2% | +0.0% |
| sortedNumericCollect | matchPercent=1 | 1.660 ms | 0.129 | 2.5% | 1.726 | 0.101 | 4.5% | +4.0% |
| sortedSetCollect | matchPercent=100 | 76.06 ms | 5.155 | 7.6% | 64.09 | 4.307 | 3.8% | -15.7% |
| sortedSetCollect | matchPercent=50 | 39.91 ms | 3.476 | 3.0% | 38.48 | 1.101 | 3.2% | -3.6% |
| sortedSetCollect | matchPercent=10 | 8.392 ms | 0.420 | 8.2% | 7.975 | 0.324 | 0.7% | -5.0% |
| sortedSetCollect | matchPercent=1 | 1.467 ms | 0.256 | 16.3% | 1.350 | 0.069 | 1.1% | -8.0% |

**Iterating all docs with `nextDoc()`**

| benchmark | params | baseline | ± | fork spread | patched | ± | fork spread | change |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| sortedNumericNextDoc |  | 62.45 ms | 2.812 | 2.2% | 54.54 | 2.611 | 5.4% | -12.7% |
| sortedSetNextDoc |  | 61.35 ms | 4.377 | 7.1% | 51.79 | 3.544 | 4.0% | -15.6% |


Suggested CHANGES entry: *Dense multi-valued SORTED_NUMERIC and SORTED_SET doc values read one
address instead of two when moving to the next document.*

Upstream overlap: open PR apache/lucene#16832 ("Reuse address upper when scanning sparse sorted
numeric and dense binary doc values") applies the same idea to two other code paths. This patch
covers the dense sorted-numeric and sorted-set `advance`/`advanceExact`, which that PR's
description does not mention; coordinate with it when submitting.

## #4 Fewer radix sort passes in `DocIdSetBuilder#build` when doc IDs are clustered

Worktree `p3`, branch `perf/docidsetbuilder-narrow-range-sort`, commit `b3aa8f3a2` (`c50b0ea98` on the pushed
branch), `patches/0003-*.patch`. File: `util/DocIdSetBuilder.java` (+ test).

**Hypothesis before editing.** When a point range (or multi-term) query matches fewer than `maxDoc/128` docs,
`DocIdSetBuilder#build` sorts the buffered doc IDs with `LSBRadixSorter`, one pass per 8 bits of
`maxDoc - 1`: 3 passes up to 16.7M docs, 4 beyond. If the doc IDs are clustered in a small range
of the doc ID space, which is the norm for a timestamp range on a segment whose docs were indexed
in roughly chronological order, the high bytes are identical and those passes do nothing useful.
Evidence before editing: `LSBRadixSorter.reorder`/`buildHistogram` were 52% of the samples of a
query that builds such sets. Cheapest disproof: sort `doc - min` with the bits of `max - min`.

**Change.** `build()` computes the min and max doc ID and, if that saves at least one pass, sorts
deltas from the min and adds the min back. The scan for the min and max stops as soon as the range
seen so far rules out saving a pass, so scattered doc IDs only pay for looking at the first 64.

**Why it gets faster.** With 5,000-50,000 matches that span less than 65,536 docs, 2 passes
instead of 3 (1M-doc segment) or 4 (20M-doc segment).

**Who benefits.** Selective point range queries on fields that correlate with doc ID order
(timestamps, sequence numbers, any range on a sorted index), on large segments.

**When the benefit disappears.** Matches scattered over the whole segment (same number of
passes), fewer than 30 matches (insertion sort), matches above `maxDoc/128` (bit set, no sort),
small segments (little to save: 1M docs, 100 hits is within noise).

Tradeoffs: scattered matches are within noise against the baseline (worst +3.9%, 24.3 ± 1.2 ->
25.3 ± 1.9 µs, intervals overlap). The first version of the patch scanned all doc IDs for the min
and max and was 12-15% slower for 5,000 and 50,000 scattered hits on the 20M-doc segment once
#1's overhead was out of the way; stopping the scan early brought that to +6.7% and -1.6%
(intervals overlap) in the same isolated comparison.

Note: the benchmarked jar of this patch was built from the working tree just before the final commit
was amended; its class files are byte-for-byte identical to a build of `b3aa8f3a2` (only the git
revision in the jar manifest differs).

Results (µs per search, 3 forks; `timestamp` = matches clustered in a doc ID range about as wide as the number of hits, `random` = matches scattered over the segment):

**1M-doc segment (20 bits: 3 passes at baseline)**

| benchmark | params | baseline | ± | fork spread | patched | ± | fork spread | change |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| search | field=timestamp, numHits=100 | 13.81 µs | 0.876 | 5.4% | 12.92 | 0.886 | 1.6% | -6.4% |
| search | field=timestamp, numHits=3000 | 65.20 µs | 4.186 | 7.1% | 53.69 | 2.508 | 6.8% | -17.6% |
| search | field=random, numHits=100 | 20.37 µs | 1.800 | 10.6% | 20.17 | 1.246 | 3.7% | -1.0% |
| search | field=random, numHits=3000 | 82.35 µs | 11.59 | 17.3% | 81.27 | 6.797 | 9.3% | -1.3% |

**20M-doc segment (25 bits: 4 passes at baseline)**

| benchmark | params | baseline | ± | fork spread | patched | ± | fork spread | change |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| search | field=timestamp, numHits=100 | 14.78 µs | 0.626 | 2.8% | 13.95 | 0.777 | 3.7% | -5.6% |
| search | field=timestamp, numHits=5000 | 103.5 µs | 7.003 | 8.1% | 74.22 | 4.018 | 2.4% | -28.3% |
| search | field=timestamp, numHits=50000 | 1086 µs | 46.06 | 3.0% | 784.5 | 31.53 | 2.4% | -27.7% |
| search | field=random, numHits=100 | 24.33 µs | 1.178 | 4.2% | 25.28 | 1.937 | 2.0% | +3.9% |
| search | field=random, numHits=5000 | 392.7 µs | 13.55 | 0.9% | 390.3 | 22.87 | 8.9% | -0.6% |
| search | field=random, numHits=50000 | 1647 µs | 83.86 | 4.4% | 1619 | 135.1 | 9.8% | -1.7% |

**Same 20M-doc cases with #1 applied on both sides, which removes the baseline's per-window collection overhead and so makes the cost of `build()` a larger share**

| benchmark | params | #1 alone | ± | fork spread | #1 + #4 | ± | fork spread | change |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| search | field=timestamp, numHits=100 | 15.02 µs | 0.93 | 3.4% | 14.15 | 0.95 | 5.1% | -5.8% |
| search | field=timestamp, numHits=5000 | 104.79 µs | 5.61 | 0.8% | 74.16 | 8.65 | 6.0% | -29.2% |
| search | field=timestamp, numHits=50000 | 1049.51 µs | 75.65 | 7.5% | 768.09 | 41.09 | 4.3% | -26.8% |
| search | field=random, numHits=100 | 14.36 µs | 0.67 | 3.3% | 15.01 | 1.01 | 9.2% | +4.5% |
| search | field=random, numHits=5000 | 82.01 µs | 5.67 | 7.5% | 87.47 | 6.68 | 3.9% | +6.7% |
| search | field=random, numHits=50000 | 644.26 µs | 43.44 | 3.1% | 633.90 | 53.96 | 6.5% | -1.6% |


Suggested CHANGES entry: *DocIdSetBuilder sorts doc IDs relative to the smallest doc ID, which
saves radix sort passes when matches are clustered in a small range of doc IDs.*

Upstream overlap: none found.

## #5 Don't compute the cardinality of the window when no clause is left to apply

Worktree `p5`, branch `perf/dense-conjunction-skip-unused-cardinality`, commit `79e1e1f29` (`ea9cbbbd4` on the
pushed branch), `patches/0005-*.patch`. File: `search/DenseConjunctionBulkScorer.java`.

**Hypothesis before editing.** `DenseConjunctionBulkScorer#scoreWindowUsingBitSet` computes
`windowMatches.cardinality()` in the initializer and in the update of its `for` loop over the
remaining clauses, so it pop-counts the 64-long window once more than it uses the result: once per
window for a single clause (every dense filter, cost >= maxDoc/32), and after the last clause of a
conjunction. Cheapest disproof: move the call into the loop condition behind
`upTo < iterators.size()` and run the existing `DefaultBulkScorerBenchmark`.

**Change.** Three lines: the cardinality is only computed while there is a clause left to apply.

**Why it gets faster.** One pop count of 4096 bits less per window.

**Who benefits.** Collectors whose own per-window work is small: counting through
`DocIdStream#count()`, and filters whose window is cheap to load (a cached `FixedBitSet`).

**When the benefit disappears.** Collectors that do work per doc (the window's pop count is then
a rounding error), and filters whose window is expensive to produce: decoding postings for a term
filter takes ~450ns per window here, so the saving is within noise end to end.

Results:

**Pre-existing `DefaultBulkScorerBenchmark.scoreIntoBitSet` (ops/ms, **higher is better**; 4 forks x 8 iterations; a `BitSetIterator` and a counting collector, no `IndexSearcher`)**

| benchmark | params | baseline | ± | fork spread | patched | ± | fork spread | change |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| scoreIntoBitSet | density=0.1, withDeletions=false | 40.05 ops/ms | 2.827 | 7.6% | 45.11 | 3.167 | 8.7% | +12.6% |
| scoreIntoBitSet | density=0.1, withDeletions=true | 27.41 ops/ms | 1.399 | 3.5% | 29.20 | 1.827 | 10.0% | +6.5% |
| scoreIntoBitSet | density=0.5, withDeletions=false | 43.30 ops/ms | 2.510 | 6.3% | 52.76 | 2.629 | 4.3% | +21.8% |
| scoreIntoBitSet | density=0.5, withDeletions=true | 31.13 ops/ms | 2.669 | 11.7% | 38.99 | 1.905 | 7.2% | +25.2% |

**Filter served from the query cache through `IndexSearcher.search` (µs per search, 4M-doc segment, 4 forks x 8 iterations)**

| benchmark | params | baseline | ± | fork spread | patched | ± | fork spread | change |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| collectDocIds | percent=10 | 1384 µs | 47.81 | 2.6% | 1409 | 38.52 | 6.3% | +1.8% |
| collectDocIds | percent=50 | 4139 µs | 125.4 | 6.1% | 4162 | 184.5 | 2.9% | +0.6% |
| count | percent=10 | 105.4 µs | 6.274 | 6.0% | 90.50 | 5.681 | 8.9% | -14.1% |
| count | percent=50 | 108.6 µs | 6.792 | 13.1% | 98.60 | 7.952 | 13.6% | -9.2% |

**Term filters evaluated from postings through `IndexSearcher.search` (µs per search, 3 forks): the saving is within noise**

| benchmark | params | baseline | ± | fork spread | patched | ± | fork spread | change |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| collectDocIds | deletedPercent=0, percent=10, query=single | 1985 µs | 82.44 | 5.8% | 2084 | 112.3 | 4.9% | +5.0% |
| collectDocIds | deletedPercent=0, percent=10, query=conjunction | 2183 µs | 203.7 | 3.8% | 2089 | 164.6 | 4.8% | -4.3% |
| collectDocIds | deletedPercent=0, percent=50, query=single | 4523 µs | 328.5 | 3.9% | 4440 | 317.8 | 12.7% | -1.8% |
| collectDocIds | deletedPercent=0, percent=50, query=conjunction | 3367 µs | 215.7 | 1.4% | 3371 | 177.3 | 5.2% | +0.1% |
| collectDocIds | deletedPercent=5, percent=10, query=single | 2034 µs | 130.1 | 4.5% | 1969 | 148.8 | 3.4% | -3.2% |
| collectDocIds | deletedPercent=5, percent=10, query=conjunction | 2213 µs | 141.9 | 9.1% | 2112 | 125.1 | 8.1% | -4.5% |
| collectDocIds | deletedPercent=5, percent=50, query=single | 4653 µs | 321.2 | 4.6% | 4514 | 176.7 | 2.4% | -3.0% |
| collectDocIds | deletedPercent=5, percent=50, query=conjunction | 3393 µs | 243.4 | 8.9% | 3312 | 353.6 | 5.9% | -2.4% |
| count | deletedPercent=0, percent=10, query=single | 668.6 µs | 41.17 | 2.3% | 634.5 | 58.71 | 11.4% | -5.1% |
| count | deletedPercent=0, percent=10, query=conjunction | 1172 µs | 101.0 | 9.7% | 1167 | 78.77 | 9.8% | -0.4% |
| count | deletedPercent=0, percent=50, query=single | 442.0 µs | 28.78 | 9.6% | 395.3 | 41.69 | 16.3% | -10.6% |
| count | deletedPercent=0, percent=50, query=conjunction | 985.3 µs | 77.87 | 4.8% | 916.9 | 69.55 | 10.4% | -6.9% |
| count | deletedPercent=5, percent=10, query=single | 727.4 µs | 49.88 | 10.4% | 760.9 | 107.7 | 11.4% | +4.6% |
| count | deletedPercent=5, percent=10, query=conjunction | 1256 µs | 74.84 | 9.3% | 1219 | 121.0 | 10.7% | -2.9% |
| count | deletedPercent=5, percent=50, query=single | 488.1 µs | 29.41 | 5.7% | 467.6 | 24.58 | 3.1% | -4.2% |
| count | deletedPercent=5, percent=50, query=conjunction | 1030 µs | 78.50 | 9.7% | 964.5 | 77.74 | 7.2% | -6.4% |


Suggested CHANGES entry: *DenseConjunctionBulkScorer no longer computes the cardinality of its
window after the last clause has been applied.*

Upstream overlap: none found; the loop is unchanged on upstream's default branch.

## Test outcomes

Per patch, on its own worktree at its final commit, `./gradlew -p lucene/core test`:

| Patch | Commit | Tests | Failures / errors | Skipped | Seed |
|---|---|---:|---:|---:|---|
| #1 `ConstantScoreBulkScorer` | `02b58eefe` | 8,860 | 0 / 0 | 322 | `173767047B189C24` |
| #2 postings enum buffers | `e26422c26` | 8,856 | 0 / 0 | 320 | `B3F7E534F949F374` |
| #3 doc-values addresses | `3767e688c` | 8,858 | 0 / 0 | 320 | `B9882CF026B7D05E` |
| #4 `DocIdSetBuilder` | `b3aa8f3a2` | 8,857 | 0 / 0 | 322 | `3B2374091EED780B` |
| #5 window cardinality | `79e1e1f29` | 8,856 | 0 / 0 | 324 | `2968F4639B53DE6F` |

(Skipped tests are ones the suite itself skips in a default run; I did not look into individual
skips. Counts differ by a few because each run uses a different random seed, and by the tests
that the patches add.)

All five stacked on the baseline together with the benchmarks (the pushed branch),
`./gradlew check --continue`, i.e. lint (ecj, forbidden APIs, formatting, licenses) plus the tests
of every module: `BUILD SUCCESSFUL`, 19,215 tests in 37 modules, 0 failures, 0 errors, 801
skipped, seed `FBF3BDD7BA8A32E3` (log: `results/stacked-gradlew-check.log`). This was run at
`ea9cbbbd4`, the last patch commit. The other modules were not run against each patch separately.

The commit after it adds this `perf-patches/` directory, whose files carry no license header, so
`./gradlew check` on the branch tip fails in `checkLicenses`, for these files only; with
`-x checkLicenses` the lint passes there too. Drop the directory, or exclude it, before running
`check` on the tip.

New and changed tests:

- #1: three new tests in `TestConstantScoreBulkScorer` (sparse matches are collected one by one,
  dense matches through bit sets after the first 32, and a randomized mix of sparse and dense
  ranges with live docs and split scoring ranges that checks nothing is lost, duplicated or out
  of order), one new and two updated tests in `TestBooleanScorer`. The two updated tests asserted
  that a `ConstantScoreBulkScorer` *only* ever calls `collect(DocIdStream)`; that is the behaviour
  this patch changes on purpose. Run 20 times each with different seeds.
- #3: `TestLucene90DocValuesFormat#testDenseMultiValuedRandomAccessPatterns` mixes `nextDoc`,
  `advance` and `advanceExact` to adjacent, distant and identical targets, with values only
  partially consumed. Run 30 times. Changing the reuse condition from `doc + 1` to `doc + 2` in
  either implementation makes it fail, so it does cover the new code.
- #4: `TestDocIdSetBuilder#testSparseClustered` (clustered doc IDs with and without duplicates,
  including ranges that straddle a byte boundary of the doc ID). Run 50 times.
- #2 and #5 add no tests: they do not change results, and the existing postings and bulk scorer
  tests exercise both code paths.

The benchmark harness was changed after the measurements to satisfy the forbidden-API check
(`IOUtils.rm` instead of `File#delete`, `@SuppressForbidden` on the method that prints
checksums). The benchmark bodies are unchanged and a smoke run of the final harness prints the
same checksums.



## Candidates that were investigated and dropped

These came out of the same profiling pass (JFR over a 5M-doc synthetic "reporting" index: term
and range filters, doc-values collectors, sorts, with and without deletions, 2 and 40 segments).
They are not counted towards the five.

| Finding | Evidence | Why it was dropped |
|---|---|---|
| `BitSetIterator#docIDRunEnd` rescans the rest of a run of set bits on every 4096-doc window of `DenseConjunctionBulkScorer` (quadratic in run length) | 45% of the samples of `count(term AND timestamp range)`, 59% of `terms-in-set AND range` | Fixed upstream right after the baseline: apache/lucene#16849, merged 2026-10-19 |
| Doc-values range queries on fields without a skip index confirm matches one doc at a time although `rangeIntoBitSet` exists | `TwoPhaseIterator.applyMask` + per-doc predicate = 30-40% of `term AND dv-range` | Open upstream PR apache/lucene#16897 does exactly this |
| `IndexedDISI` scans SPARSE blocks linearly (`// TODO: binary search`) | 24-33% of a sum over a sparse doc-values field (`advanceExact` and `advanceExactWithinBlock`) | Three open upstream PRs: #16349, #14371, #968 |
| Dense binary / sparse sorted-numeric doc values read each address twice | code reading | Open upstream PR #16832; #3 above covers the neighbouring dense multi-valued `advance`/`advanceExact` path, which that PR's description does not mention |
| `BKDReader` allocates ~4kB of leaf-decoding scratch per `getPointTree()`, including for `estimatePointCount`, which never decodes a leaf | `BKDReaderDocIDSetIterator.<init>` = 3.6% of `term AND range` over 40 segments | Not measured in isolation; partially overlaps the stale open PR #13888. Spare candidate |
| Sorting by a timestamp in descending order on a segment whose docs are in ascending timestamp order makes every hit competitive (~60ns per hit in priority-queue updates), and each competitive hit reads its doc value twice (`compareBottom`, then `copy`) | 49% of samples in `PriorityQueue`/`FieldValueHitQueue`; ~25% in doc-values reads | The queue cost needs a different algorithm, not a small patch; caching the value would save an estimated 6-8% in this worst case only. Spare candidate |
| `DocIdSetBuilder` upgrades to a `FixedBitSet` at ~0.5% density (65% of its threshold) because buffers grow by doubling | 23.8K hits in a 4.7M-doc segment produced a bit set | A policy trade-off that upstream revisited one day before the baseline (#16089), not a clear win |
| `LSBRadixSorter` makes one histogram pass per digit | 20% of a sort-by-int query | Estimated gain of merging the histogram passes was ~5% of such queries; not pursued |
| Vectorized `findNextGEQ` | A two-term conjunction ran 17% faster *without* `--add-modules jdk.incubator.vector` on this machine, while `AdvanceBenchmark` shows the vector path 2.5x faster in isolation | Cause not established (the two runs differ by more than `findNextGEQ`), likely specific to this CPU/JDK. Unverified, see below |
| `PointRangeQuery` sets one bit per matching doc | 30-55% of dense range filters | ~0.8ns per doc, inherent to how BKD leaves store doc IDs; no unnecessary work found |


## Shortfalls and unverified claims

- **Hardware and noise.** Everything ran on one shared 4-vCPU KVM guest (Intel, AVX-512) without
  CPU isolation. Fork-to-fork spread is typically 2-10%, occasionally up to 17%; differences below
  ~10% between baseline and patch should be read as "no measurable change" unless the confidence
  intervals are disjoint. Nothing was measured on AMD or ARM, or on another JDK.
- **Synthetic workloads only.** No luceneutil/wikimedia run was done. The benchmarks use normal
  Lucene entry points but synthetic data: uniformly random matches (the worst case for anything
  that depends on adjacency), a jittered timestamp, one contiguous cluster.
- **Thresholds in #1 were chosen on this machine.** 32 matches per window is deliberately
  conservative so that the cases apache/lucene#16141 measured as wins stay on the bit-set path.
  Postings with 32-128 matches per window still go through bit sets although collecting them one
  by one is faster with per-doc collectors here (see #1); that is left as a follow-up.
- **#2 changes an undefined behaviour.** `PostingsEnum#freq()` on an enum pulled with
  `PostingsEnum.NONE` used to return the real frequency for terms that occur in a single document
  and 1 otherwise; it now always returns 1. The javadoc says the result is undefined in this case
  and the full core test suite passes, but it is a behaviour change.
- **Tests.** Per patch: the full `lucene/core` suite. The other modules were only run against all
  five patches stacked together, not against each patch separately (see "Test outcomes").
- **Upstream overlap** was checked with GitHub's issue/PR and code search on apache/lucene on
  2026-11-12, by keyword. The search is not exhaustive: a PR that describes the same change in
  different words could have been missed. The code each patch changes is still present on
  upstream's default branch according to code search.
- **`./gradlew check` fails on the tip of the pushed branch**, in `checkLicenses`, because of the
  files under `perf-patches/` only (see "Test outcomes"). It passes on the last patch commit.
- **No CHANGES.txt entries** are in the commits (all five would touch the same lines and conflict
  with each other); suggested entries are given per patch.
- **Not done:** no issues or PRs were filed.


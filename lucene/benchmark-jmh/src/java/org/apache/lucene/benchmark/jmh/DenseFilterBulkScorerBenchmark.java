/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.lucene.benchmark.jmh;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.DocIdStream;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.LRUQueryCache;
import org.apache.lucene.search.LeafCollector;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.QueryCachingPolicy;
import org.apache.lucene.search.Scorable;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.util.IOUtils;
import org.apache.lucene.util.SuppressForbidden;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Benchmarks collecting all matches of filters that match more than 1/32th of the docs of a segment
 * and do not need scores, which are evaluated one window of doc IDs at a time by {@code
 * DenseConjunctionBulkScorer}.
 *
 * <ul>
 *   <li>{@code query} is either a single term filter that matches {@code percent}% of docs, or the
 *       conjunction of this filter with another filter that matches 50% of docs, or the single
 *       filter served from the query cache, i.e. a bit set that was computed by a previous search.
 *   <li>The benchmark methods differ by what the collector does: only counting matches in bulk
 *       through {@link DocIdStream#count()}, or a cheap computation per doc.
 * </ul>
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(
    value = 3,
    jvmArgsAppend = {"-Xmx2g", "-Xms2g", "-XX:+AlwaysPreTouch"})
public class DenseFilterBulkScorerBenchmark {

  private static final int NUM_DOCS = 4_000_000;
  private static final int[] PERCENTS = {10, 50};

  @Param({"single", "conjunction", "cached"})
  String query;

  /** Percentage of docs that the filter matches. */
  @Param({"10", "50"})
  int percent;

  /** Percentage of deleted docs. */
  @Param({"0", "5"})
  int deletedPercent;

  private Directory dir;
  private IndexReader reader;
  private IndexSearcher searcher;
  private Path path;
  private boolean deleteIndexOnTearDown;
  private Query filter;
  private long checksum;

  @Setup(Level.Trial)
  public void setup() throws IOException {
    path = indexPath("denseFilter-" + deletedPercent);
    dir = MMapDirectory.open(path);
    if (isComplete(path) == false) {
      Random random = new Random(42);
      try (IndexWriter w = new IndexWriter(dir, new IndexWriterConfig().setRAMBufferSizeMB(256))) {
        for (int i = 0; i < NUM_DOCS; i++) {
          Document doc = new Document();
          for (int percent : PERCENTS) {
            if (random.nextInt(100) < percent) {
              doc.add(new StringField("percent" + percent, "x", Store.NO));
            }
          }
          if (random.nextBoolean()) {
            doc.add(new StringField("other", "x", Store.NO));
          }
          if (random.nextInt(100) < deletedPercent) {
            doc.add(new StringField("deleted", "x", Store.NO));
          }
          w.addDocument(doc);
        }
        w.forceMerge(1);
      }
      if (deletedPercent > 0) {
        try (IndexWriter w =
            new IndexWriter(dir, new IndexWriterConfig().setMergePolicy(NoMergePolicy.INSTANCE))) {
          w.deleteDocuments(new Term("deleted", "x"));
        }
      }
      markComplete(path);
    }
    reader = DirectoryReader.open(dir);
    if (reader.leaves().size() != 1
        || reader.leaves().get(0).reader().hasDeletions() != (deletedPercent > 0)) {
      throw new AssertionError("Expected a single segment with " + deletedPercent + "% deletes");
    }
    searcher = new IndexSearcher(reader);
    if (query.equals("cached")) {
      // Cache every filter the first time that it is used.
      searcher.setQueryCache(new LRUQueryCache(16, 1 << 26, _ -> true, 1f));
      searcher.setQueryCachingPolicy(
          new QueryCachingPolicy() {
            @Override
            public void onUse(Query query) {}

            @Override
            public boolean shouldCache(Query query) {
              return true;
            }
          });
    } else {
      searcher.setQueryCache(null);
    }
    BooleanQuery.Builder builder =
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("percent" + percent, "x")), Occur.FILTER);
    if (query.equals("conjunction")) {
      builder.add(new TermQuery(new Term("other", "x")), Occur.FILTER);
    }
    filter = builder.build();

    if (query.equals("cached")) {
      // Populate the cache by running the search once.
      count();
      LRUQueryCache cache = (LRUQueryCache) searcher.getQueryCache();
      if (cache.getCacheSize() != 1) {
        throw new AssertionError("Expected the filter to be cached");
      }
    }

    // Make sure that we are benchmarking the bulk scorer that we think we are benchmarking.
    LeafReaderContext leaf = reader.leaves().get(0);
    String bulkScorer =
        searcher
            .createWeight(searcher.rewrite(filter), ScoreMode.COMPLETE_NO_SCORES, 1f)
            .bulkScorer(leaf)
            .getClass()
            .getSimpleName();
    if (bulkScorer.equals("DenseConjunctionBulkScorer") == false) {
      throw new AssertionError("Expected DenseConjunctionBulkScorer, got " + bulkScorer);
    }
  }

  @TearDown(Level.Trial)
  @SuppressForbidden(
      reason = "prints the checksum so that runs of different builds can be compared")
  public void tearDown() throws IOException {
    System.out.println(
        "checksum(query="
            + query
            + ",percent="
            + percent
            + ",deletedPercent="
            + deletedPercent
            + ")="
            + checksum);
    reader.close();
    dir.close();
    deleteIndexIfTemporary();
  }

  /**
   * Returns the directory of the index for the given parameters and whether it needs to be built.
   * If the {@code bench.index.dir} system property is set, indexes are kept there and reused by
   * later runs, so that different builds of Lucene can be compared on the very same index.
   */
  private Path indexPath(String name) throws IOException {
    String base = System.getProperty("bench.index.dir");
    if (base == null) {
      deleteIndexOnTearDown = true;
      return Files.createTempDirectory(name);
    }
    deleteIndexOnTearDown = false;
    return Files.createDirectories(Path.of(base, name));
  }

  private static boolean isComplete(Path path) {
    return Files.exists(path.resolve("complete"));
  }

  private static void markComplete(Path path) throws IOException {
    Files.createFile(path.resolve("complete"));
  }

  private void deleteIndexIfTemporary() throws IOException {
    if (deleteIndexOnTearDown) {
      IOUtils.rm(path);
    }
  }

  private abstract static class SimpleCollector implements Collector {
    @Override
    public ScoreMode scoreMode() {
      return ScoreMode.COMPLETE_NO_SCORES;
    }
  }

  /** The collector does a cheap computation on every doc, in doc ID order. */
  @Benchmark
  public long collectDocIds() throws IOException {
    long[] sum = new long[1];
    searcher.search(
        filter,
        new SimpleCollector() {
          @Override
          public LeafCollector getLeafCollector(LeafReaderContext context) {
            return new LeafCollector() {
              @Override
              public void setScorer(Scorable scorer) {}

              @Override
              public void collect(int doc) {
                sum[0] = sum[0] * 31 + doc;
              }
            };
          }
        });
    return checksum = sum[0];
  }

  /** The collector only counts matches, in bulk whenever it is given the opportunity. */
  @Benchmark
  public long count() throws IOException {
    long[] count = new long[1];
    searcher.search(
        filter,
        new SimpleCollector() {
          @Override
          public LeafCollector getLeafCollector(LeafReaderContext context) {
            return new LeafCollector() {
              @Override
              public void setScorer(Scorable scorer) {}

              @Override
              public void collect(int doc) {
                count[0]++;
              }

              @Override
              public void collect(DocIdStream stream) throws IOException {
                count[0] += stream.count();
              }
            };
          }
        });
    return checksum = count[0];
  }
}

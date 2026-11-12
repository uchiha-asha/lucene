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
import java.util.Collection;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.CollectorManager;
import org.apache.lucene.search.DocIdStream;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.LeafCollector;
import org.apache.lucene.search.Query;
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
 * Benchmarks collecting all matches of a single filter that matches less than 1/32th of the docs of
 * a segment and does not need scores, e.g. a term or range filter under an aggregation.
 *
 * <ul>
 *   <li>{@code source} is the data structure that matches are read from: the postings of a term, or
 *       a doc ID set built from points. {@code cluster} is a term whose 1/64th of matching docs are
 *       all adjacent. {@code stride} is a term that matches the first 64 docs, then one doc at the
 *       start of every window of {@code DenseConjunctionBulkScorer.WINDOW_SIZE} docs (4096): a
 *       dense prefix followed by sparse matches at regular intervals. {@code gap} is ignored by
 *       {@code cluster} and {@code stride}.
 *   <li>{@code gap} is the average number of docs between two consecutive matches, i.e. the query
 *       matches one doc out of {@code gap}.
 *   <li>The three benchmark methods differ by what the collector does: a cheap computation per doc,
 *       reading a doc value per doc, or only counting matches in bulk through {@link
 *       DocIdStream#count()}.
 * </ul>
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(
    value = 3,
    jvmArgsPrepend = {"-Xmx2g", "-Xms2g", "-XX:+AlwaysPreTouch"})
public class SparseFilterBulkScorerBenchmark {

  private static final int NUM_DOCS = 4_000_000;
  private static final int[] GAPS = {100_000, 10_000, 1_000, 256, 100, 50};
  private static final int POINT_RANGE = 100_000;

  @Param({"postings", "points", "cluster", "stride"})
  String source;

  @Param({"100000", "10000", "1000", "256", "100", "50"})
  int gap;

  /** Percentage of deleted docs. */
  @Param({"0"})
  int deletedPercent;

  private Directory dir;
  private IndexReader reader;
  private IndexSearcher searcher;
  private Path path;
  private boolean deleteIndexOnTearDown;
  private Query query;
  private long checksum;

  @Setup(Level.Trial)
  public void setup() throws IOException {
    path = indexPath("sparseFilter-v2-" + deletedPercent);
    dir = MMapDirectory.open(path);
    if (isComplete(path) == false) {
      Random random = new Random(42);
      try (IndexWriter w = new IndexWriter(dir, new IndexWriterConfig().setRAMBufferSizeMB(256))) {
        for (int i = 0; i < NUM_DOCS; i++) {
          Document doc = new Document();
          for (int gap : GAPS) {
            if (random.nextInt(gap) == 0) {
              doc.add(new StringField("gap" + gap, "x", Store.NO));
            }
          }
          if (i >= NUM_DOCS / 2 && i < NUM_DOCS / 2 + NUM_DOCS / 64) {
            doc.add(new StringField("cluster", "x", Store.NO));
          }
          if (i < 64 || i % 4096 == 0) {
            doc.add(new StringField("stride", "x", Store.NO));
          }
          if (random.nextInt(100) < deletedPercent) {
            doc.add(new StringField("deleted", "x", Store.NO));
          }
          doc.add(new IntPoint("point", random.nextInt(POINT_RANGE)));
          doc.add(new NumericDocValuesField("value", random.nextInt(1000)));
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
    searcher.setQueryCache(null);
    Query filter =
        switch (source) {
          case "postings" -> new TermQuery(new Term("gap" + gap, "x"));
          case "points" -> IntPoint.newRangeQuery("point", 0, POINT_RANGE / gap - 1);
          case "cluster" -> new TermQuery(new Term("cluster", "x"));
          case "stride" -> new TermQuery(new Term("stride", "x"));
          default -> throw new AssertionError(source);
        };
    query = new BooleanQuery.Builder().add(filter, Occur.FILTER).build();

    // Make sure that we are benchmarking the bulk scorer that we think we are benchmarking.
    LeafReaderContext leaf = reader.leaves().get(0);
    String bulkScorer =
        searcher
            .createWeight(searcher.rewrite(query), ScoreMode.COMPLETE_NO_SCORES, 1f)
            .bulkScorer(leaf)
            .getClass()
            .getSimpleName();
    if (bulkScorer.equals("ConstantScoreBulkScorer") == false) {
      throw new AssertionError("Expected ConstantScoreBulkScorer, got " + bulkScorer);
    }
  }

  @TearDown(Level.Trial)
  @SuppressForbidden(
      reason = "prints the checksum so that runs of different builds can be compared")
  public void tearDown() throws IOException {
    System.out.println(
        "checksum(source="
            + source
            + ",gap="
            + gap
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
    searchWith(
        searcher,
        query,
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

  /** The collector reads a doc value for every doc, like a sum aggregation would do. */
  @Benchmark
  public long collectDocValues() throws IOException {
    long[] sum = new long[1];
    searchWith(
        searcher,
        query,
        new SimpleCollector() {
          @Override
          public LeafCollector getLeafCollector(LeafReaderContext context) throws IOException {
            NumericDocValues values = context.reader().getNumericDocValues("value");
            return new LeafCollector() {
              @Override
              public void setScorer(Scorable scorer) {}

              @Override
              public void collect(int doc) throws IOException {
                if (values.advanceExact(doc)) {
                  sum[0] += values.longValue();
                }
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
    searchWith(
        searcher,
        query,
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

  /** Runs a search with a single collector: the searcher has no executor, so it needs only one. */
  private static void searchWith(IndexSearcher searcher, Query query, Collector collector)
      throws IOException {
    searcher.search(
        query,
        new CollectorManager<Collector, Void>() {
          @Override
          public Collector newCollector() {
            return collector;
          }

          @Override
          public Void reduce(Collection<Collector> collectors) {
            return null;
          }
        });
  }
}

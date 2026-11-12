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
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.CollectorManager;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.LeafCollector;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Scorable;
import org.apache.lucene.search.ScoreMode;
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
 * Benchmarks selective point range queries, i.e. queries that match less than 1/128th of the docs
 * of a segment, so that {@code DocIdSetBuilder} collects matches into an int[] that it needs to
 * sort rather than into a bit set.
 *
 * <p>Two fields are indexed. {@code timestamp} increases with the doc ID, with some jitter, like
 * events that get indexed in the approximate order in which they occurred: docs that match a range
 * are clustered in a small range of doc IDs. {@code random} is not correlated with doc IDs: docs
 * that match a range are scattered across the whole segment.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(
    value = 3,
    jvmArgsPrepend = {"-Xmx2g", "-Xms2g", "-XX:+AlwaysPreTouch"})
public class SparsePointRangeQueryBenchmark {

  /** Each doc's timestamp is within JITTER of its doc ID. */
  private static final int JITTER = 100;

  @Param({"1000000", "20000000"})
  int maxDoc;

  /** Number of docs that match the query. */
  @Param({"100", "3000"})
  int numHits;

  @Param({"timestamp", "random"})
  String field;

  private Directory dir;
  private IndexReader reader;
  private IndexSearcher searcher;
  private Path path;
  private boolean deleteIndexOnTearDown;
  private Query query;
  private long checksum;

  @Setup(Level.Trial)
  public void setup() throws IOException {
    path = indexPath("sparsePointRange-" + maxDoc);
    dir = MMapDirectory.open(path);
    Random random = new Random(42);
    if (isComplete(path) == false) {
      try (IndexWriter w = new IndexWriter(dir, new IndexWriterConfig().setRAMBufferSizeMB(256))) {
        Document doc = new Document();
        LongPoint timestamp = new LongPoint("timestamp", 0L);
        LongPoint rnd = new LongPoint("random", 0L);
        doc.add(timestamp);
        doc.add(rnd);
        for (int i = 0; i < maxDoc; i++) {
          timestamp.setLongValue(i + random.nextInt(JITTER));
          rnd.setLongValue(random.nextInt(maxDoc));
          w.addDocument(doc);
        }
        w.forceMerge(1);
      }
      markComplete(path);
    }
    reader = DirectoryReader.open(dir);
    if (reader.leaves().size() != 1) {
      throw new AssertionError("Expected a single segment");
    }
    if (numHits >= maxDoc >>> 7) {
      throw new AssertionError("The query must be selective enough to not use a bit set");
    }
    searcher = new IndexSearcher(reader);
    searcher.setQueryCache(null);
    long min = maxDoc / 2;
    // both fields have about one doc per value
    query = LongPoint.newRangeQuery(field, min, min + numHits - 1);

    // Make sure that matches are collected into a sorted int[] rather than into a bit set.
    String iterator =
        searcher
            .createWeight(searcher.rewrite(query), ScoreMode.COMPLETE_NO_SCORES, 1f)
            .scorer(reader.leaves().get(0))
            .iterator()
            .getClass()
            .getName();
    if (iterator.contains("IntArrayDocIdSet") == false) {
      throw new AssertionError("Expected an iterator over a sorted int[], got " + iterator);
    }
  }

  @TearDown(Level.Trial)
  @SuppressForbidden(
      reason = "prints the checksum so that runs of different builds can be compared")
  public void tearDown() throws IOException {
    System.out.println(
        "checksum(maxDoc=" + maxDoc + ",numHits=" + numHits + ",field=" + field + ")=" + checksum);
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

  /**
   * Collect all matches of the range query. The collector computes a checksum that depends on the
   * number of hits, their doc IDs and the order in which they are collected.
   */
  @Benchmark
  public long search() throws IOException {
    long[] sum = new long[1];
    searchWith(
        searcher,
        query,
        new Collector() {
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

          @Override
          public ScoreMode scoreMode() {
            return ScoreMode.COMPLETE_NO_SCORES;
          }
        });
    return checksum = sum[0];
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

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
import org.apache.lucene.document.SortedNumericDocValuesField;
import org.apache.lucene.document.SortedSetDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.SortedNumericDocValues;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.CollectorManager;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.LeafCollector;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Scorable;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.util.BytesRef;
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
 * Benchmarks reading every value of a dense multi-valued doc-values field (SORTED_SET or
 * SORTED_NUMERIC), the access pattern of faceting / aggregations: either for the docs that match a
 * filter through {@link IndexSearcher#search} and {@code advanceExact}, or for all docs of the
 * field through {@code nextDoc}.
 *
 * <p>{@code matchPercent} controls how many docs the filter matches, hence how often two
 * consecutively collected docs are adjacent: 100 only visits adjacent docs, 1 almost never does.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(
    value = 3,
    jvmArgsPrepend = {"-Xmx2g", "-Xms2g", "-XX:+AlwaysPreTouch"})
public class MultiValuedDocValuesIterationBenchmark {

  private static final int NUM_DOCS = 2_000_000;
  private static final int NUM_TERMS = 1_000;
  private static final int MAX_VALUES_PER_DOC = 4;

  @Param({"100", "50", "10", "1"})
  int matchPercent;

  private Directory dir;
  private IndexReader reader;
  private IndexSearcher searcher;
  private Path path;
  private boolean deleteIndexOnTearDown;
  private Query query;
  private long checksum;

  @Setup(Level.Trial)
  public void setup() throws IOException {
    path = indexPath("multiValuedDV");
    dir = MMapDirectory.open(path);
    Random random = new Random(42);
    if (isComplete(path) == false) {
      try (IndexWriter w = new IndexWriter(dir, new IndexWriterConfig())) {
        BytesRef[] terms = new BytesRef[NUM_TERMS];
        for (int i = 0; i < NUM_TERMS; i++) {
          terms[i] = new BytesRef(String.format(java.util.Locale.ROOT, "term%04d", i));
        }
        for (int i = 0; i < NUM_DOCS; i++) {
          Document doc = new Document();
          // every doc has between 1 and 4 values so that both fields are dense and multi-valued
          int numValues = 1 + random.nextInt(MAX_VALUES_PER_DOC);
          for (int j = 0; j < numValues; j++) {
            doc.add(new SortedSetDocValuesField("sset", terms[random.nextInt(NUM_TERMS)]));
            doc.add(new SortedNumericDocValuesField("snum", random.nextInt(1_000_000)));
          }
          int r = random.nextInt(100);
          if (r < 50) {
            doc.add(new StringField("filter", "p50", Store.NO));
          }
          if (r < 10) {
            doc.add(new StringField("filter", "p10", Store.NO));
          }
          if (r < 1) {
            doc.add(new StringField("filter", "p1", Store.NO));
          }
          w.addDocument(doc);
        }
        w.forceMerge(1);
      }
      markComplete(path);
    }
    reader = DirectoryReader.open(dir);
    searcher = new IndexSearcher(reader);
    searcher.setQueryCache(null);
    if (matchPercent == 100) {
      query = MatchAllDocsQuery.INSTANCE;
    } else {
      query =
          new BooleanQuery.Builder()
              .add(new TermQuery(new Term("filter", "p" + matchPercent)), Occur.FILTER)
              .build();
    }

    // Make sure that we are benchmarking the dense multi-valued implementation.
    LeafReader leaf = reader.leaves().get(0).reader();
    if (reader.leaves().size() != 1
        || DocValues.unwrapSingleton(leaf.getSortedSetDocValues("sset")) != null
        || DocValues.unwrapSingleton(leaf.getSortedNumericDocValues("snum")) != null
        || leaf.getSortedSetDocValues("sset").cost() != leaf.maxDoc()
        || leaf.getSortedNumericDocValues("snum").cost() != leaf.maxDoc()) {
      throw new AssertionError("Expected a single segment with dense multi-valued fields");
    }
  }

  @TearDown(Level.Trial)
  @SuppressForbidden(
      reason = "prints the checksum so that runs of different builds can be compared")
  public void tearDown() throws IOException {
    System.out.println("checksum(matchPercent=" + matchPercent + ")=" + checksum);
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

  /** Sum ordinals of all values of docs that match the filter, like faceting would do. */
  @Benchmark
  public long sortedSetCollect() throws IOException {
    long[] sum = new long[1];
    searchWith(
        searcher,
        query,
        new Collector() {
          @Override
          public LeafCollector getLeafCollector(LeafReaderContext context) throws IOException {
            SortedSetDocValues values = context.reader().getSortedSetDocValues("sset");
            return new LeafCollector() {
              @Override
              public void setScorer(Scorable scorer) {}

              @Override
              public void collect(int doc) throws IOException {
                if (values.advanceExact(doc)) {
                  for (int i = 0, count = values.docValueCount(); i < count; i++) {
                    sum[0] += values.nextOrd();
                  }
                }
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

  /** Sum all values of docs that match the filter, like a sum aggregation would do. */
  @Benchmark
  public long sortedNumericCollect() throws IOException {
    long[] sum = new long[1];
    searchWith(
        searcher,
        query,
        new Collector() {
          @Override
          public LeafCollector getLeafCollector(LeafReaderContext context) throws IOException {
            SortedNumericDocValues values = context.reader().getSortedNumericDocValues("snum");
            return new LeafCollector() {
              @Override
              public void setScorer(Scorable scorer) {}

              @Override
              public void collect(int doc) throws IOException {
                if (values.advanceExact(doc)) {
                  for (int i = 0, count = values.docValueCount(); i < count; i++) {
                    sum[0] += values.nextValue();
                  }
                }
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

  /** Iterate over all docs and ordinals with nextDoc(), independently of the filter. */
  @Benchmark
  public long sortedSetNextDoc() throws IOException {
    long sum = 0;
    for (LeafReaderContext context : reader.leaves()) {
      SortedSetDocValues values = context.reader().getSortedSetDocValues("sset");
      for (int doc = values.nextDoc();
          doc != DocIdSetIterator.NO_MORE_DOCS;
          doc = values.nextDoc()) {
        for (int i = 0, count = values.docValueCount(); i < count; i++) {
          sum += values.nextOrd();
        }
      }
    }
    return checksum = sum;
  }

  /** Iterate over all docs and values with nextDoc(), independently of the filter. */
  @Benchmark
  public long sortedNumericNextDoc() throws IOException {
    long sum = 0;
    for (LeafReaderContext context : reader.leaves()) {
      SortedNumericDocValues values = context.reader().getSortedNumericDocValues("snum");
      for (int doc = values.nextDoc();
          doc != DocIdSetIterator.NO_MORE_DOCS;
          doc = values.nextDoc()) {
        for (int i = 0, count = values.docValueCount(); i < count; i++) {
          sum += values.nextValue();
        }
      }
    }
    return checksum = sum;
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

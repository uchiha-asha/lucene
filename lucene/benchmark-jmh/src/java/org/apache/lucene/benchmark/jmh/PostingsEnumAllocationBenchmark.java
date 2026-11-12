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
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.SortedDocValuesField;
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
import org.apache.lucene.search.CollectorManager;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.LeafCollector;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Scorable;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
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
 * Benchmarks searches whose cost is dominated by pulling many postings enums rather than by
 * iterating postings: disjunctions of many low-frequency terms and sorting by a high-cardinality
 * keyword field (whose comparator skips non-competitive docs via the postings of competitive
 * terms). {@link #denseTermFilter} is a control which iterates a single dense postings list.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(
    value = 3,
    jvmArgsPrepend = {"-Xmx2g", "-Xms2g", "-XX:+AlwaysPreTouch"})
public class PostingsEnumAllocationBenchmark {

  private static final int NUM_DOCS = 1_000_000;
  private static final int NUM_IDS = 100_000;

  /** Number of segments of the index. */
  @Param({"1", "20"})
  int numSegments;

  private Directory dir;
  private IndexReader reader;
  private IndexSearcher searcher;
  private Path path;
  private boolean deleteIndexOnTearDown;
  private Query idsQuery64, idsQuery1024;
  private Query denseFilter;
  private Sort sort;
  private long checksum;

  @Setup(Level.Trial)
  public void setup() throws IOException {
    path = indexPath("postingsEnumAllocation-" + numSegments);
    dir = MMapDirectory.open(path);
    Random random = new Random(42);
    IndexWriterConfig config = new IndexWriterConfig();
    if (numSegments > 1) {
      config
          .setMergePolicy(NoMergePolicy.INSTANCE)
          .setMaxBufferedDocs(NUM_DOCS / numSegments)
          .setRAMBufferSizeMB(IndexWriterConfig.DISABLE_AUTO_FLUSH);
    }
    if (isComplete(path) == false) {
      try (IndexWriter w = new IndexWriter(dir, config)) {
        for (int i = 0; i < NUM_DOCS; i++) {
          Document doc = new Document();
          // 10 docs per id on average
          BytesRef id = new BytesRef(String.format(Locale.ROOT, "id%06d", random.nextInt(NUM_IDS)));
          doc.add(new StringField("id", id, Store.NO));
          doc.add(new SortedDocValuesField("id", id));
          // 50% of docs
          if (random.nextBoolean()) {
            doc.add(new StringField("flag", "on", Store.NO));
          }
          w.addDocument(doc);
        }
        if (numSegments == 1) {
          w.forceMerge(1);
        }
      }
      markComplete(path);
    }
    reader = DirectoryReader.open(dir);
    if (reader.leaves().size() != numSegments) {
      throw new AssertionError(
          "Expected " + numSegments + " segments, got " + reader.leaves().size());
    }
    searcher = new IndexSearcher(reader);
    searcher.setQueryCache(null);
    // independent of the random sequence that built the index, which is skipped when it is reused
    Random queryRandom = new Random(43);
    idsQuery64 = idsQuery(queryRandom, 64);
    idsQuery1024 = idsQuery(queryRandom, 1024);
    denseFilter =
        new BooleanQuery.Builder().add(new TermQuery(new Term("flag", "on")), Occur.FILTER).build();
    sort = new Sort(new SortField("id", SortField.Type.STRING));
  }

  private static Query idsQuery(Random random, int numIds) {
    BooleanQuery.Builder builder = new BooleanQuery.Builder();
    for (int i = 0; i < numIds; i++) {
      String id = String.format(Locale.ROOT, "id%06d", random.nextInt(NUM_IDS));
      builder.add(new TermQuery(new Term("id", id)), Occur.SHOULD);
    }
    return new BooleanQuery.Builder().add(builder.build(), Occur.FILTER).build();
  }

  @TearDown(Level.Trial)
  @SuppressForbidden(
      reason = "prints the checksum so that runs of different builds can be compared")
  public void tearDown() throws IOException {
    System.out.println("checksum(numSegments=" + numSegments + ")=" + checksum);
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

  private long collect(Query query) throws IOException {
    long[] sum = new long[1];
    searchWith(
        searcher,
        query,
        new Collector() {
          @Override
          public LeafCollector getLeafCollector(LeafReaderContext context) {
            final int docBase = context.docBase;
            return new LeafCollector() {
              @Override
              public void setScorer(Scorable scorer) {}

              @Override
              public void collect(int doc) {
                sum[0] = sum[0] * 31 + docBase + doc;
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

  /** Filter on a disjunction of 64 low-frequency terms. */
  @Benchmark
  public long disjunctionOf64Ids() throws IOException {
    return collect(idsQuery64);
  }

  /** Filter on a disjunction of 1024 low-frequency terms. */
  @Benchmark
  public long disjunctionOf1024Ids() throws IOException {
    return collect(idsQuery1024);
  }

  /** Sort docs that match a dense filter by a high-cardinality keyword field. */
  @Benchmark
  public long sortByKeyword() throws IOException {
    TopDocs topDocs = searcher.search(denseFilter, 10, sort);
    long sum = topDocs.totalHits.value();
    for (int i = 0; i < topDocs.scoreDocs.length; i++) {
      sum = sum * 31 + topDocs.scoreDocs[i].doc;
    }
    return checksum = sum;
  }

  /** Control: iterate over all docs of a single dense postings list. */
  @Benchmark
  public long denseTermFilter() throws IOException {
    return collect(denseFilter);
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

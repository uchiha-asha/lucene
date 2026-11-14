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
import org.apache.lucene.document.Field;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.FieldDoc;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TopFieldDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.util.IOUtils;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
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
 * Top-10 TermQuery sorted on a long field over many segments. "sparse" matches a few documents in 2
 * of 64 segments, "dense" matches about half the documents of every segment.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(
    value = 3,
    jvmArgsAppend = {"-Xmx2g", "-Xms2g", "-XX:+AlwaysPreTouch"})
public class NumericSortLeafComparatorBenchmark {

  static final int NUM_SEGMENTS = 64;
  static final int DOCS_PER_SEGMENT = 2048;

  @Param({"sparse", "dense"})
  public String matches;

  /** "points": LongPoint + doc values (points-based skipping); "dv": doc values only. */
  @Param({"points", "dv"})
  public String index;

  /** Whether 10% of documents have no sort value. */
  @Param({"false", "true"})
  public boolean missing;

  @Param({"false", "true"})
  public boolean searchAfter;

  private Path path;
  private Directory dir;
  private DirectoryReader reader;
  private IndexSearcher searcher;
  private Query query;
  private Sort sort;
  private FieldDoc after;

  @Setup
  public void setup() throws IOException {
    path = Files.createTempDirectory("numericSort");
    dir = new MMapDirectory(path);
    buildIndex(dir, index.equals("points"), missing);
    reader = DirectoryReader.open(dir);
    if (reader.leaves().size() != NUM_SEGMENTS) {
      throw new IllegalStateException("expected " + NUM_SEGMENTS + " segments");
    }
    searcher = new IndexSearcher(reader);
    searcher.setQueryCache(null);
    query = new TermQuery(new Term("body", matches));
    sort = new Sort(new SortField("sort", SortField.Type.LONG));
    if (searchAfter) {
      TopFieldDocs top = searcher.search(query, 5, sort);
      after = (FieldDoc) top.scoreDocs[top.scoreDocs.length - 1];
    }
  }

  static void buildIndex(Directory dir, boolean points, boolean missing) throws IOException {
    Random random = new Random(42);
    try (IndexWriter w =
        new IndexWriter(
            dir,
            new IndexWriterConfig()
                .setMergePolicy(NoMergePolicy.INSTANCE)
                .setMaxBufferedDocs(DOCS_PER_SEGMENT + 1)
                .setRAMBufferSizeMB(IndexWriterConfig.DISABLE_AUTO_FLUSH))) {
      for (int seg = 0; seg < NUM_SEGMENTS; seg++) {
        for (int i = 0; i < DOCS_PER_SEGMENT; i++) {
          Document doc = new Document();
          if (random.nextBoolean()) {
            doc.add(new StringField("body", "dense", Field.Store.NO));
          }
          if ((seg == 7 || seg == 40) && i % 256 == 0) {
            doc.add(new StringField("body", "sparse", Field.Store.NO));
          }
          if (missing == false || random.nextInt(10) != 0) {
            long value = random.nextLong();
            doc.add(new NumericDocValuesField("sort", value));
            if (points) {
              doc.add(new LongPoint("sort", value));
            }
          }
          w.addDocument(doc);
        }
        w.commit();
      }
    }
  }

  @TearDown
  public void tearDown() throws IOException {
    IOUtils.close(reader, dir);
    IOUtils.rm(path);
  }

  @Benchmark
  public TopDocs search() throws IOException {
    if (after != null) {
      return searcher.searchAfter(after, query, 10, sort);
    }
    return searcher.search(query, 10, sort);
  }
}

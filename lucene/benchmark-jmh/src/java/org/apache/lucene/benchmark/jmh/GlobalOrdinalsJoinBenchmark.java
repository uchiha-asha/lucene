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
import java.util.concurrent.TimeUnit;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.join.JoinUtil;
import org.apache.lucene.search.join.ScoreMode;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.BytesRef;
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
 * Global ordinal joins that collect scores or occurrence counts, on a single segment searched as a
 * single slice. Measures the collection of the "from" side, which runs in createJoinQuery.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(
    value = 2,
    jvmArgsAppend = {"-Xmx2g", "-Xms2g", "-XX:+AlwaysPreTouch"})
public class GlobalOrdinalsJoinBenchmark {

  @Param({"2000000"})
  public int numDocs;

  @Param({"1000", "100000"})
  public int numKeys;

  /** None uses min=2 so that the join counts occurrences instead of only collecting matches. */
  @Param({"None", "Max", "Avg"})
  public String scoreMode;

  private Directory dir;
  private DirectoryReader reader;
  private IndexSearcher searcher;
  private ScoreMode mode;
  private int min;

  @Setup(Level.Trial)
  public void setup() throws IOException {
    dir = new ByteBuffersDirectory();
    try (IndexWriter w = new IndexWriter(dir, new IndexWriterConfig().setRAMBufferSizeMB(256))) {
      for (int i = 0; i < numDocs; i++) {
        Document doc = new Document();
        doc.add(new SortedDocValuesField("key", new BytesRef(Integer.toString(i % numKeys))));
        w.addDocument(doc);
      }
      w.forceMerge(1);
    }
    reader = DirectoryReader.open(dir);
    // No executor: one slice, collected by the calling thread.
    searcher = new IndexSearcher(reader);
    mode = ScoreMode.valueOf(scoreMode);
    min = mode == ScoreMode.None ? 2 : 0;
  }

  @TearDown(Level.Trial)
  public void tearDown() throws IOException {
    reader.close();
    dir.close();
  }

  @Benchmark
  public Query join() throws IOException {
    return JoinUtil.createJoinQuery(
        "key",
        MatchAllDocsQuery.INSTANCE,
        MatchAllDocsQuery.INSTANCE,
        searcher,
        mode,
        null,
        min,
        Integer.MAX_VALUE);
  }
}

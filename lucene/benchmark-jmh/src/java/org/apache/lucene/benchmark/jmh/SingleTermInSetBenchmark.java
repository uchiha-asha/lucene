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
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KeywordField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.LRUQueryCache;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermInSetQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.UsageTrackingQueryCachingPolicy;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.util.BytesRef;
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
 * TermInSetQuery with one term. "rare" rotates over 1024 terms that match 4 documents each,
 * "common" repeats one term that matches 25% of documents. "top" runs the query alone (scores),
 * "filter" puts it in a FILTER clause next to a TermQuery that matches 50% of documents. "cache"
 * uses an LRUQueryCache with the default caching policy, "nocache" disables the query cache.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(
    value = 3,
    jvmArgsAppend = {"-Xmx2g", "-Xms2g", "-XX:+AlwaysPreTouch"})
public class SingleTermInSetBenchmark {

  static final int NUM_SEGMENTS = 8;
  static final int DOCS_PER_SEGMENT = 32768; // above LRUQueryCache's 10k minimum segment size
  static final int NUM_RARE_TERMS = 1024;

  @Param({"rare", "common"})
  public String term;

  @Param({"top", "filter"})
  public String context;

  @Param({"nocache", "cache"})
  public String cache;

  private Path path;
  private Directory dir;
  private DirectoryReader reader;
  private IndexSearcher searcher;
  private Query[] queries;
  private int next;

  @Setup
  public void setup() throws IOException {
    path = Files.createTempDirectory("singleTermInSet");
    dir = new MMapDirectory(path);
    Random random = new Random(42);
    int numDocs = NUM_SEGMENTS * DOCS_PER_SEGMENT;
    try (IndexWriter w =
        new IndexWriter(
            dir,
            new IndexWriterConfig()
                .setMergePolicy(NoMergePolicy.INSTANCE)
                .setMaxBufferedDocs(DOCS_PER_SEGMENT + 1)
                .setRAMBufferSizeMB(IndexWriterConfig.DISABLE_AUTO_FLUSH))) {
      for (int i = 0; i < numDocs; i++) {
        Document doc = new Document();
        // each rare term is on 4 docs spread over the index
        doc.add(new KeywordField("id", "r" + (i % (numDocs / 4)), Field.Store.NO));
        doc.add(new KeywordField("tag", "t" + random.nextInt(4), Field.Store.NO));
        if (random.nextBoolean()) {
          doc.add(new StringField("body", "half", Field.Store.NO));
        }
        w.addDocument(doc);
        if ((i + 1) % DOCS_PER_SEGMENT == 0) {
          w.commit();
        }
      }
    }
    reader = DirectoryReader.open(dir);
    searcher = new IndexSearcher(reader);
    if (cache.equals("cache")) {
      searcher.setQueryCache(new LRUQueryCache(1000, 64 << 20));
      searcher.setQueryCachingPolicy(new UsageTrackingQueryCachingPolicy());
    } else {
      searcher.setQueryCache(null);
    }
    int numQueries = term.equals("rare") ? NUM_RARE_TERMS : 1;
    queries = new Query[numQueries];
    for (int i = 0; i < numQueries; i++) {
      String value = term.equals("rare") ? "r" + (i * 97 % (numDocs / 4)) : "t1";
      String field = term.equals("rare") ? "id" : "tag";
      Query q = new TermInSetQuery(field, List.of(new BytesRef(value)));
      if (context.equals("filter")) {
        q =
            new BooleanQuery.Builder()
                .add(new TermQuery(new Term("body", "half")), Occur.MUST)
                .add(q, Occur.FILTER)
                .build();
      }
      queries[i] = q;
    }
  }

  @TearDown
  public void tearDown() throws IOException {
    IOUtils.close(reader, dir);
    IOUtils.rm(path);
  }

  @Benchmark
  public TopDocs search() throws IOException {
    Query q = queries[next];
    next = next + 1 == queries.length ? 0 : next + 1;
    return searcher.search(q, 10);
  }
}

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
package org.apache.lucene.facet;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.CollectorManager;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.Directory;
import org.apache.lucene.tests.index.RandomIndexWriter;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.tests.util.TestUtil;

public class TestFacetsCollectorManager extends LuceneTestCase {

  /**
   * Reducing the collectors of segment partitions, in any order, gives the same hits and scores as
   * collecting whole segments.
   */
  public void testReducePartitions() throws IOException {
    try (Directory dir = newDirectory();
        RandomIndexWriter w = new RandomIndexWriter(random(), dir)) {
      int numDocs = atLeast(1000);
      for (int i = 0; i < numDocs; i++) {
        Document doc = new Document();
        doc.add(
            new StringField("f", random().nextInt(10) == 0 ? "rare" : "common", Field.Store.NO));
        if (random().nextBoolean()) {
          doc.add(new StringField("f", "half", Field.Store.NO));
        }
        w.addDocument(doc);
      }
      // a single segment, so that every slice but the first is a partition of it
      w.forceMerge(1);
      try (IndexReader reader = w.getReader()) {
        IndexSearcher whole = new IndexSearcher(reader);
        int maxDocsPerSlice = TestUtil.nextInt(random(), 1, numDocs / 2);
        IndexSearcher partitioned =
            new IndexSearcher(reader, Runnable::run) {
              @Override
              protected LeafSlice[] slices(List<LeafReaderContext> leaves) {
                return slices(leaves, maxDocsPerSlice, 1, true);
              }
            };
        for (int iter = 0; iter < 10; iter++) {
          Query query =
              switch (random().nextInt(4)) {
                case 0 -> MatchAllDocsQuery.INSTANCE;
                case 1 -> new TermQuery(new Term("f", "rare"));
                case 2 -> new TermQuery(new Term("f", "half"));
                default -> new TermQuery(new Term("f", "missing"));
              };
          boolean keepScores = random().nextBoolean();
          FacetsCollector expected = whole.search(query, new FacetsCollectorManager(keepScores));

          FacetsCollectorManager manager = new FacetsCollectorManager(keepScores);
          List<FacetsCollector> collectors = Collections.synchronizedList(new ArrayList<>());
          partitioned.search(
              query,
              new CollectorManager<FacetsCollector, Void>() {
                @Override
                public FacetsCollector newCollector() throws IOException {
                  FacetsCollector collector = manager.newCollector();
                  collectors.add(collector);
                  return collector;
                }

                @Override
                public Void reduce(Collection<FacetsCollector> collectors) {
                  return null;
                }
              });
          assertTrue(collectors.size() > 1);
          Collections.shuffle(collectors, random());
          assertSameHits(expected, manager.reduce(collectors), keepScores);
        }
      }
    }
  }

  private static void assertSameHits(
      FacetsCollector expected, FacetsCollector actual, boolean keepScores) throws IOException {
    Map<LeafReaderContext, FacetsCollector.MatchingDocs> actualByLeaf = new HashMap<>();
    for (FacetsCollector.MatchingDocs matchingDocs : actual.getMatchingDocs()) {
      assertNull(actualByLeaf.put(matchingDocs.context(), matchingDocs));
    }
    for (FacetsCollector.MatchingDocs expectedDocs : expected.getMatchingDocs()) {
      FacetsCollector.MatchingDocs actualDocs = actualByLeaf.remove(expectedDocs.context());
      if (expectedDocs.totalHits() == 0 && actualDocs == null) {
        continue;
      }
      assertNotNull(actualDocs);
      assertEquals(expectedDocs.totalHits(), actualDocs.totalHits());
      DocIdSetIterator expectedIt = expectedDocs.bits().iterator();
      DocIdSetIterator actualIt = actualDocs.bits().iterator();
      if (expectedIt == null || actualIt == null) {
        assertEquals(0, expectedDocs.totalHits());
        continue;
      }
      for (int doc = expectedIt.nextDoc();
          doc != DocIdSetIterator.NO_MORE_DOCS;
          doc = expectedIt.nextDoc()) {
        assertEquals(doc, actualIt.nextDoc());
        if (keepScores) {
          assertEquals(expectedDocs.scores()[doc], actualDocs.scores()[doc], 0f);
        }
      }
      assertEquals(DocIdSetIterator.NO_MORE_DOCS, actualIt.nextDoc());
    }
    for (FacetsCollector.MatchingDocs leftOver : actualByLeaf.values()) {
      assertEquals(0, leftOver.totalHits());
    }
  }
}

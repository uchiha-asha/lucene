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
package org.apache.lucene.search.comparators;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicIntegerArray;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.DoublePoint;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FloatPoint;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FilterDirectoryReader;
import org.apache.lucene.index.FilterLeafReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.FieldDoc;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Pruning;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TotalHits;
import org.apache.lucene.store.Directory;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.tests.util.TestUtil;

public class TestNumericComparator extends LuceneTestCase {

  public void testEmptyCompetitiveIteratorOptimization() throws Exception {
    final int numDocs = atLeast(1000);
    try (var dir = newDirectory()) {
      try (var writer =
          new IndexWriter(dir, new IndexWriterConfig().setMergePolicy(newLogMergePolicy()))) {
        for (int i = 0; i < numDocs; i++) {
          var doc = new Document();

          doc.add(NumericDocValuesField.indexedField("long_field_1", i));
          doc.add(new LongPoint("long_field_2", i));
          doc.add(new NumericDocValuesField("long_field_2", i));

          doc.add(NumericDocValuesField.indexedField("int_field_1", i));
          doc.add(new IntPoint("int_field_2", i));
          doc.add(new NumericDocValuesField("int_field_2", i));

          doc.add(NumericDocValuesField.indexedField("float_field_1", i));
          doc.add(new FloatPoint("float_field_2", i));
          doc.add(new NumericDocValuesField("float_field_2", i));

          doc.add(NumericDocValuesField.indexedField("double_field_1", i));
          doc.add(new DoublePoint("double_field_2", i));
          doc.add(new NumericDocValuesField("double_field_2", i));

          writer.addDocument(doc);
        }
        writer.forceMerge(1);
        try (var reader = DirectoryReader.open(writer)) {
          assertEquals(1, reader.leaves().size());
          var leafContext = reader.leaves().get(0);

          // long field 1 ascending:
          assertLongField("long_field_1", false, -1, leafContext);
          // long field 1 descending:
          assertLongField("long_field_1", true, numDocs + 1, leafContext);
          // long field 2 ascending:
          assertLongField("long_field_2", false, -1, leafContext);
          // long field 2 descending:
          assertLongField("long_field_2", true, numDocs + 1, leafContext);

          // int field 1 ascending:
          assertIntField("int_field_1", false, -1, leafContext);
          // int field 1 descending:
          assertIntField("int_field_1", true, numDocs + 1, leafContext);
          // int field 2 ascending:
          assertIntField("int_field_2", false, -1, leafContext);
          // int field 2 descending:
          assertIntField("int_field_2", true, numDocs + 1, leafContext);

          // float field 1 ascending:
          assertFloatField("float_field_1", false, -1, leafContext);
          // float field 1 descending:
          assertFloatField("float_field_1", true, numDocs + 1, leafContext);
          // float field 2 ascending:
          assertFloatField("float_field_2", false, -1, leafContext);
          // float field 2 descending:
          assertFloatField("float_field_2", true, numDocs + 1, leafContext);

          // double field 1 ascending:
          assertDoubleField("double_field_1", false, -1, leafContext);
          // double field 1 descending:
          assertDoubleField("double_field_1", true, numDocs + 1, leafContext);
          // double field 2 ascending:
          assertDoubleField("double_field_2", false, -1, leafContext);
          // double field 2 descending:
          assertDoubleField("double_field_2", true, numDocs + 1, leafContext);
        }
      }
    }
  }

  public void testEmptyCompetitiveIteratorOptimizationWithMissingValue() throws Exception {
    final int numDocs = atLeast(1000);
    try (var dir = newDirectory()) {
      try (var writer =
          new IndexWriter(dir, new IndexWriterConfig().setMergePolicy(newLogMergePolicy()))) {
        // index docs without missing values:
        for (int i = 0; i < numDocs; i++) {
          var doc = new Document();

          doc.add(NumericDocValuesField.indexedField("long_field_1", i));
          doc.add(new LongPoint("long_field_2", i));
          doc.add(new NumericDocValuesField("long_field_2", i));

          writer.addDocument(doc);
          if (i % 100 == 0) {
            writer.flush();
          }
        }

        // Index one doc with without long_field_1 and long_field_2 fields
        var doc = new Document();
        doc.add(new NumericDocValuesField("another_field", numDocs));
        writer.addDocument(doc);
        writer.flush();

        try (var reader = DirectoryReader.open(writer)) {
          var indexSearcher = newSearcher(reader);
          indexSearcher.setQueryCache(null);
          {
            var sortField =
                new SortField("long_field_1", SortField.Type.LONG, false, Long.MIN_VALUE);
            var topDocs = indexSearcher.search(MatchAllDocsQuery.INSTANCE, 3, new Sort(sortField));
            assertEquals(numDocs, topDocs.scoreDocs[0].doc);
            assertEquals(Long.MIN_VALUE, ((FieldDoc) topDocs.scoreDocs[0]).fields[0]);
            assertEquals(0, topDocs.scoreDocs[1].doc);
            assertEquals(0L, ((FieldDoc) topDocs.scoreDocs[1]).fields[0]);
            assertEquals(1, topDocs.scoreDocs[2].doc);
            assertEquals(1L, ((FieldDoc) topDocs.scoreDocs[2]).fields[0]);
          }
          {
            var sortField =
                new SortField("long_field_2", SortField.Type.LONG, false, Long.MIN_VALUE);
            var topDocs = indexSearcher.search(MatchAllDocsQuery.INSTANCE, 3, new Sort(sortField));
            assertEquals(numDocs, topDocs.scoreDocs[0].doc);
            assertEquals(Long.MIN_VALUE, ((FieldDoc) topDocs.scoreDocs[0]).fields[0]);
            assertEquals(0, topDocs.scoreDocs[1].doc);
            assertEquals(0L, ((FieldDoc) topDocs.scoreDocs[1]).fields[0]);
            assertEquals(1, topDocs.scoreDocs[2].doc);
            assertEquals(1L, ((FieldDoc) topDocs.scoreDocs[2]).fields[0]);
          }
        }
      }
    }
  }

  public void testEmptyCompetitiveIteratorOptimizationAndHitsThresholdReached() throws Exception {
    final int numDocs =
        TestUtil.nextInt(random(), 128, 512); // Below IndexSearcher.DEFAULT_HITS_THRESHOLD
    try (var dir = newDirectory()) {
      try (var writer =
          new IndexWriter(dir, new IndexWriterConfig().setMergePolicy(newLogMergePolicy()))) {
        for (int i = 0; i < numDocs; i++) {
          var doc = new Document();

          doc.add(NumericDocValuesField.indexedField("field_1", i));
          doc.add(new LongPoint("field_2", i));
          doc.add(new NumericDocValuesField("field_2", i));

          writer.addDocument(doc);
          if (i % 100 == 0) {
            writer.flush();
          }
        }

        try (var reader = DirectoryReader.open(writer)) {
          var indexSearcher = newSearcher(reader);
          indexSearcher.setQueryCache(null);
          for (String field : new String[] {"field_1", "field_2"}) {
            var sortField = new SortField(field, SortField.Type.LONG, false);
            var topDocs = indexSearcher.search(MatchAllDocsQuery.INSTANCE, 3, new Sort(sortField));
            assertEquals(TotalHits.Relation.EQUAL_TO, topDocs.totalHits.relation());
            assertEquals(numDocs, topDocs.totalHits.value());
            assertEquals(0, topDocs.scoreDocs[0].doc);
            assertEquals(0L, ((FieldDoc) topDocs.scoreDocs[0]).fields[0]);
            assertEquals(1, topDocs.scoreDocs[1].doc);
            assertEquals(1L, ((FieldDoc) topDocs.scoreDocs[1]).fields[0]);
            assertEquals(2, topDocs.scoreDocs[2].doc);
            assertEquals(2L, ((FieldDoc) topDocs.scoreDocs[2]).fields[0]);
          }
        }
      }
    }
  }

  private static void assertLongField(
      String fieldName, boolean reverse, int bottom, LeafReaderContext leafContext)
      throws IOException {
    var comparator1 =
        (LongComparator)
            new SortField(fieldName, SortField.Type.LONG, reverse)
                .getComparator(1, Pruning.GREATER_THAN_OR_EQUAL_TO);
    comparator1.queueFull = true;
    comparator1.hitsThresholdReached = true;
    comparator1.bottom = bottom;
    var leafComparator = comparator1.getLeafComparator(leafContext);
    leafComparator.setScorer(null);
    assertEquals(DocIdSetIterator.NO_MORE_DOCS, leafComparator.competitiveIterator().nextDoc());
  }

  private static void assertIntField(
      String fieldName, boolean reverse, int bottom, LeafReaderContext leafContext)
      throws IOException {
    var comparator1 =
        (IntComparator)
            new SortField(fieldName, SortField.Type.INT, reverse)
                .getComparator(1, Pruning.GREATER_THAN_OR_EQUAL_TO);
    comparator1.queueFull = true;
    comparator1.hitsThresholdReached = true;
    comparator1.bottom = bottom;
    var leafComparator = comparator1.getLeafComparator(leafContext);
    leafComparator.setScorer(null);
    assertEquals(DocIdSetIterator.NO_MORE_DOCS, leafComparator.competitiveIterator().nextDoc());
  }

  private static void assertFloatField(
      String fieldName, boolean reverse, int bottom, LeafReaderContext leafContext)
      throws IOException {
    var comparator1 =
        (FloatComparator)
            new SortField(fieldName, SortField.Type.FLOAT, reverse)
                .getComparator(1, Pruning.GREATER_THAN_OR_EQUAL_TO);
    comparator1.queueFull = true;
    comparator1.hitsThresholdReached = true;
    comparator1.bottom = bottom;
    var leafComparator = comparator1.getLeafComparator(leafContext);
    leafComparator.setScorer(null);
    assertEquals(DocIdSetIterator.NO_MORE_DOCS, leafComparator.competitiveIterator().nextDoc());
  }

  private static void assertDoubleField(
      String fieldName, boolean reverse, int bottom, LeafReaderContext leafContext)
      throws IOException {
    var comparator1 =
        (DoubleComparator)
            new SortField(fieldName, SortField.Type.DOUBLE, reverse)
                .getComparator(1, Pruning.GREATER_THAN_OR_EQUAL_TO);
    comparator1.queueFull = true;
    comparator1.hitsThresholdReached = true;
    comparator1.bottom = bottom;
    var leafComparator = comparator1.getLeafComparator(leafContext);
    leafComparator.setScorer(null);
    assertEquals(DocIdSetIterator.NO_MORE_DOCS, leafComparator.competitiveIterator().nextDoc());
  }

  /**
   * Numeric doc values are opened on first use, so segments where the query matches nothing do not
   * open them.
   */
  public void testDocValuesOnlyOpenedOnLeavesWithMatches() throws IOException {
    final int numSegments = TestUtil.nextInt(random(), 3, 6);
    final int matchingSegment = random().nextInt(numSegments);
    final boolean isLong = random().nextBoolean();
    final int indexMode = random().nextInt(3); // 0: points, 1: doc values skipper, 2: doc values
    final boolean reverse = random().nextBoolean();
    final List<long[]> expected = new ArrayList<>(); // {globalDoc, value} of matching docs
    try (Directory dir = newDirectory()) {
      try (IndexWriter writer =
          new IndexWriter(dir, newIndexWriterConfig().setMergePolicy(NoMergePolicy.INSTANCE))) {
        int globalDoc = 0;
        for (int seg = 0; seg < numSegments; seg++) {
          int numDocs = TestUtil.nextInt(random(), 20, 100);
          for (int i = 0; i < numDocs; i++, globalDoc++) {
            Document doc = new Document();
            doc.add(new StringField("seg", Integer.toString(seg), Field.Store.NO));
            boolean missing = random().nextInt(10) == 0;
            long value = isLong ? random().nextLong() : random().nextInt();
            if (missing == false) {
              switch (indexMode) {
                case 0 -> {
                  doc.add(new NumericDocValuesField("f", value));
                  doc.add(isLong ? new LongPoint("f", value) : new IntPoint("f", (int) value));
                }
                case 1 -> doc.add(NumericDocValuesField.indexedField("f", value));
                default -> doc.add(new NumericDocValuesField("f", value));
              }
            }
            writer.addDocument(doc);
            if (seg == matchingSegment) {
              long sortValue = missing ? (isLong ? Long.MAX_VALUE : Integer.MAX_VALUE) : value;
              expected.add(new long[] {globalDoc, sortValue});
            }
          }
          writer.commit();
        }
      }
      try (DirectoryReader in = DirectoryReader.open(dir)) {
        assertEquals(numSegments, in.leaves().size());
        AtomicIntegerArray opens = new AtomicIntegerArray(numSegments);
        DirectoryReader reader = new CountingDirectoryReader(in, opens);
        IndexSearcher searcher = newSearcher(reader, true, true, false);
        Object missingValue = isLong ? (Object) Long.MAX_VALUE : (Object) Integer.MAX_VALUE;
        SortField sortField =
            new SortField(
                "f", isLong ? SortField.Type.LONG : SortField.Type.INT, reverse, missingValue);
        Sort sort = new Sort(sortField);
        Query query = new TermQuery(new Term("seg", Integer.toString(matchingSegment)));
        int n = TestUtil.nextInt(random(), 1, 10);

        expected.sort(
            (a, b) -> {
              int cmp = Long.compare(a[1], b[1]);
              return cmp != 0 ? (reverse ? -cmp : cmp) : Long.compare(a[0], b[0]);
            });
        TopDocs topDocs = searcher.search(query, n, sort);
        assertHits(expected, 0, n, topDocs);

        int afterIndex = random().nextInt(expected.size());
        long[] after = expected.get(afterIndex);
        Object afterValue = isLong ? (Object) after[1] : (Object) (int) after[1];
        topDocs =
            searcher.searchAfter(
                new FieldDoc((int) after[0], Float.NaN, new Object[] {afterValue}), query, n, sort);
        assertHits(expected, afterIndex + 1, n, topDocs);

        for (int seg = 0; seg < numSegments; seg++) {
          if (seg == matchingSegment) {
            assertTrue(opens.get(seg) > 0);
          } else {
            assertEquals("segment " + seg, 0, opens.get(seg));
          }
        }
      }
    }
  }

  private static void assertHits(List<long[]> expected, int from, int n, TopDocs topDocs) {
    int to = Math.min(expected.size(), from + n);
    assertEquals(to - from, topDocs.scoreDocs.length);
    for (int i = from; i < to; i++) {
      FieldDoc hit = (FieldDoc) topDocs.scoreDocs[i - from];
      assertEquals(expected.get(i)[0], hit.doc);
      assertEquals(expected.get(i)[1], ((Number) hit.fields[0]).longValue());
    }
  }

  /** Counts {@link LeafReader#getNumericDocValues} calls per segment. */
  private static class CountingDirectoryReader extends FilterDirectoryReader {
    private final AtomicIntegerArray opens;

    CountingDirectoryReader(DirectoryReader in, AtomicIntegerArray opens) throws IOException {
      super(
          in,
          new SubReaderWrapper() {
            int ord = 0;

            @Override
            public LeafReader wrap(LeafReader reader) {
              final int leafOrd = ord++;
              return new FilterLeafReader(reader) {
                @Override
                public NumericDocValues getNumericDocValues(String field) throws IOException {
                  opens.incrementAndGet(leafOrd);
                  return super.getNumericDocValues(field);
                }

                @Override
                public CacheHelper getCoreCacheHelper() {
                  return null;
                }

                @Override
                public CacheHelper getReaderCacheHelper() {
                  return null;
                }
              };
            }
          });
      this.opens = opens;
    }

    @Override
    protected DirectoryReader doWrapDirectoryReader(DirectoryReader in) throws IOException {
      return new CountingDirectoryReader(in, opens);
    }

    @Override
    public CacheHelper getReaderCacheHelper() {
      return null;
    }
  }
}

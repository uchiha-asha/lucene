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
package org.apache.lucene.search;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.SortedSetDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.Directory;
import org.apache.lucene.tests.analysis.MockAnalyzer;
import org.apache.lucene.tests.analysis.MockTokenizer;
import org.apache.lucene.tests.index.RandomIndexWriter;
import org.apache.lucene.tests.search.CheckHits;
import org.apache.lucene.tests.search.QueryUtils;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.tests.util.TestUtil;
import org.apache.lucene.tests.util.automaton.AutomatonTestUtil;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.UnicodeUtil;
import org.apache.lucene.util.automaton.RegExp;

/** Tests the DocValuesRewriteMethod */
public class TestDocValuesRewriteMethod extends LuceneTestCase {
  protected IndexSearcher searcher;
  private IndexReader reader;
  private Directory dir;
  protected String fieldName;

  @Override
  public void setUp() throws Exception {
    super.setUp();
    dir = newDirectory();
    fieldName =
        random().nextBoolean() ? "field" : ""; // sometimes use an empty string as field name
    RandomIndexWriter writer =
        new RandomIndexWriter(
            random(),
            dir,
            newIndexWriterConfig(new MockAnalyzer(random(), MockTokenizer.KEYWORD, false))
                .setMaxBufferedDocs(TestUtil.nextInt(random(), 50, 1000)));
    List<String> terms = new ArrayList<>();
    int num = atLeast(200);
    for (int i = 0; i < num; i++) {
      Document doc = new Document();
      doc.add(newStringField("id", Integer.toString(i), Field.Store.NO));
      int numTerms = random().nextInt(4);
      for (int j = 0; j < numTerms; j++) {
        String s = TestUtil.randomUnicodeString(random());
        doc.add(newStringField(fieldName, s, Field.Store.NO));
        doc.add(new SortedSetDocValuesField(fieldName, new BytesRef(s)));
        doc.add(SortedSetDocValuesField.indexedField(fieldName + "_with-skip", new BytesRef(s)));
        terms.add(s);
      }
      writer.addDocument(doc);
    }

    if (VERBOSE) {
      // utf16 order
      Collections.sort(terms);
      System.out.println("UTF16 order:");
      for (String s : terms) {
        System.out.println("  " + UnicodeUtil.toHexString(s) + " " + s);
      }
    }

    int numDeletions = random().nextInt(num / 10);
    for (int i = 0; i < numDeletions; i++) {
      writer.deleteDocuments(new Term("id", Integer.toString(random().nextInt(num))));
    }

    reader = writer.getReader();
    searcher = newSearcher(reader);
    writer.close();
  }

  @Override
  public void tearDown() throws Exception {
    reader.close();
    dir.close();
    super.tearDown();
  }

  /** test a bunch of random regular expressions */
  public void testRegexps() throws Exception {
    int num = atLeast(1000);
    for (int i = 0; i < num; i++) {
      String reg = AutomatonTestUtil.randomRegexp(random());
      if (VERBOSE) {
        System.out.println("TEST: regexp=" + reg);
      }
      assertSame(reg);
    }
  }

  /** check that the # of hits is the same as if the query is run against the inverted index */
  protected void assertSame(String regexp) throws IOException {
    RegexpQuery docValues =
        new RegexpQuery(
            new Term(fieldName, regexp), RegExp.NONE, 0, _ -> null, new DocValuesRewriteMethod());
    RegexpQuery docValuesWithSkip =
        new RegexpQuery(
            new Term(fieldName + "_with-skip", regexp),
            RegExp.NONE,
            0,
            _ -> null,
            new DocValuesRewriteMethod());
    RegexpQuery inverted = new RegexpQuery(new Term(fieldName, regexp), RegExp.NONE);

    TopDocs invertedDocs = searcher.search(inverted, 25);
    TopDocs docValuesDocs = searcher.search(docValues, 25);
    TopDocs docValuesWithSkipDocs = searcher.search(docValuesWithSkip, 25);

    CheckHits.checkEqual(inverted, invertedDocs.scoreDocs, docValuesDocs.scoreDocs);
    CheckHits.checkEqual(inverted, invertedDocs.scoreDocs, docValuesWithSkipDocs.scoreDocs);
  }

  /** prefix queries resolve their ordinal range without enumerating terms */
  public void testPrefixes() throws Exception {
    int num = atLeast(200);
    for (int i = 0; i < num; i++) {
      String prefix = randomBound();
      assertSame(
          new PrefixQuery(new Term(fieldName, prefix)),
          new PrefixQuery(new Term(fieldName, prefix), new DocValuesRewriteMethod()),
          new PrefixQuery(
              new Term(fieldName + "_with-skip", prefix), new DocValuesRewriteMethod()));
    }
  }

  /** term range queries resolve their ordinal range without enumerating terms */
  public void testTermRanges() throws Exception {
    int num = atLeast(200);
    for (int i = 0; i < num; i++) {
      String lower = random().nextInt(10) == 0 ? null : randomBound();
      String upper = random().nextInt(10) == 0 ? null : randomBound();
      boolean includeLower = random().nextBoolean();
      boolean includeUpper = random().nextBoolean();
      assertSame(
          TermRangeQuery.newStringRange(fieldName, lower, upper, includeLower, includeUpper),
          new TermRangeQuery(
              fieldName,
              lower == null ? null : new BytesRef(lower),
              upper == null ? null : new BytesRef(upper),
              includeLower,
              includeUpper,
              new DocValuesRewriteMethod()),
          new TermRangeQuery(
              fieldName + "_with-skip",
              lower == null ? null : new BytesRef(lower),
              upper == null ? null : new BytesRef(upper),
              includeLower,
              includeUpper,
              new DocValuesRewriteMethod()));
    }
  }

  /** a random string, often a prefix of an indexed term so that it matches something */
  private String randomBound() throws IOException {
    if (random().nextInt(4) == 0) {
      return TestUtil.randomUnicodeString(random(), 3);
    }
    int doc = random().nextInt(reader.maxDoc());
    for (LeafReaderContext ctx : reader.leaves()) {
      if (doc < ctx.docBase + ctx.reader().maxDoc()) {
        SortedSetDocValues values = DocValues.getSortedSet(ctx.reader(), fieldName);
        if (values.advanceExact(doc - ctx.docBase)) {
          String term = values.lookupOrd(values.nextOrd()).utf8ToString();
          return term.substring(0, random().nextInt(term.length() + 1));
        }
      }
    }
    return "";
  }

  private void assertSame(Query inverted, Query docValues, Query docValuesWithSkip)
      throws IOException {
    TopDocs invertedDocs = searcher.search(inverted, 25);
    assertEquals(searcher.count(inverted), searcher.count(docValues));
    assertEquals(searcher.count(inverted), searcher.count(docValuesWithSkip));
    CheckHits.checkEqual(
        inverted, invertedDocs.scoreDocs, searcher.search(docValues, 25).scoreDocs);
    CheckHits.checkEqual(
        inverted, invertedDocs.scoreDocs, searcher.search(docValuesWithSkip, 25).scoreDocs);
  }

  /**
   * binary terms with 0x00 and 0xFF bytes, in single- and multi-valued fields, so that prefixes
   * that end with 0xFF and the singleton code path are covered
   */
  public void testBinaryPrefixesAndRanges() throws Exception {
    byte[] alphabet = {0x00, 0x01, 0x7F, (byte) 0x80, (byte) 0xFE, (byte) 0xFF};
    try (Directory dir = newDirectory();
        RandomIndexWriter writer =
            new RandomIndexWriter(
                random(),
                dir,
                newIndexWriterConfig().setMaxBufferedDocs(TestUtil.nextInt(random(), 20, 200)))) {
      int numDocs = atLeast(300);
      for (int i = 0; i < numDocs; i++) {
        Document doc = new Document();
        if (random().nextInt(10) != 0) {
          BytesRef term = randomBinary(alphabet, 4);
          doc.add(new StringField("single", term, Field.Store.NO));
          doc.add(new SortedDocValuesField("single", term));
          doc.add(SortedDocValuesField.indexedField("single_with-skip", term));
        }
        for (int j = random().nextInt(3); j > 0; j--) {
          BytesRef term = randomBinary(alphabet, 4);
          doc.add(new StringField("multi", term, Field.Store.NO));
          doc.add(new SortedSetDocValuesField("multi", term));
          doc.add(SortedSetDocValuesField.indexedField("multi_with-skip", term));
        }
        writer.addDocument(doc);
      }
      try (IndexReader reader = writer.getReader()) {
        IndexSearcher searcher = newSearcher(reader);
        for (int i = atLeast(300); i > 0; i--) {
          String field = random().nextBoolean() ? "single" : "multi";
          final Query inverted;
          final Query docValues;
          final Query docValuesWithSkip;
          if (random().nextBoolean()) {
            Term prefix = new Term(field, randomBinary(alphabet, 3));
            inverted = new PrefixQuery(prefix);
            docValues = new PrefixQuery(prefix, new DocValuesRewriteMethod());
            docValuesWithSkip =
                new PrefixQuery(
                    new Term(field + "_with-skip", prefix.bytes()), new DocValuesRewriteMethod());
          } else {
            BytesRef lower = random().nextInt(10) == 0 ? null : randomBinary(alphabet, 3);
            BytesRef upper = random().nextInt(10) == 0 ? null : randomBinary(alphabet, 3);
            boolean includeLower = random().nextBoolean();
            boolean includeUpper = random().nextBoolean();
            inverted = new TermRangeQuery(field, lower, upper, includeLower, includeUpper);
            docValues =
                new TermRangeQuery(
                    field, lower, upper, includeLower, includeUpper, new DocValuesRewriteMethod());
            docValuesWithSkip =
                new TermRangeQuery(
                    field + "_with-skip",
                    lower,
                    upper,
                    includeLower,
                    includeUpper,
                    new DocValuesRewriteMethod());
          }
          int expected = searcher.count(inverted);
          assertEquals(docValues.toString(), expected, searcher.count(docValues));
          assertEquals(docValues.toString(), expected, searcher.count(docValuesWithSkip));
          if (rarely()) {
            QueryUtils.check(random(), docValues, searcher);
          }
        }
      }
    }
  }

  private static BytesRef randomBinary(byte[] alphabet, int maxLength) {
    byte[] bytes = new byte[random().nextInt(maxLength + 1)];
    for (int i = 0; i < bytes.length; i++) {
      bytes[i] = alphabet[random().nextInt(alphabet.length)];
    }
    return new BytesRef(bytes);
  }

  public void testEquals() throws Exception {
    {
      RegexpQuery a1 = new RegexpQuery(new Term(fieldName, "[aA]"), RegExp.NONE);
      RegexpQuery a2 = new RegexpQuery(new Term(fieldName, "[aA]"), RegExp.NONE);
      RegexpQuery b = new RegexpQuery(new Term(fieldName, "[bB]"), RegExp.NONE);
      assertEquals(a1, a2);
      assertFalse(a1.equals(b));
    }

    {
      RegexpQuery a1 =
          new RegexpQuery(
              new Term(fieldName, "[aA]"), RegExp.NONE, 0, _ -> null, new DocValuesRewriteMethod());
      RegexpQuery a2 =
          new RegexpQuery(
              new Term(fieldName, "[aA]"), RegExp.NONE, 0, _ -> null, new DocValuesRewriteMethod());
      RegexpQuery b =
          new RegexpQuery(
              new Term(fieldName, "[bB]"), RegExp.NONE, 0, _ -> null, new DocValuesRewriteMethod());
      assertEquals(a1, a2);
      assertFalse(a1.equals(b));
      QueryUtils.check(a1);
    }
  }
}

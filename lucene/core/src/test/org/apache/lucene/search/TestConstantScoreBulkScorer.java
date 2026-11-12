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
import org.apache.lucene.tests.search.AssertingBulkScorer;
import org.apache.lucene.tests.search.RandomApproximationQuery.RandomTwoPhaseView;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.tests.util.TestUtil;
import org.apache.lucene.util.BitSetIterator;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.FixedBitSet;

public class TestConstantScoreBulkScorer extends LuceneTestCase {

  /**
   * A two-phase iterator that advertises a bulk {@link TwoPhaseIterator#intoBitSet}. Its
   * approximation is a superset of the actual matches, so {@link #matches()} genuinely filters.
   */
  private static class BulkTwoPhase extends TwoPhaseIterator {
    private final FixedBitSet matchSet;

    BulkTwoPhase(FixedBitSet approximation, FixedBitSet matchSet) {
      super(new BitSetIterator(approximation, approximation.approximateCardinality()));
      this.matchSet = matchSet;
    }

    @Override
    public boolean matches() {
      return matchSet.get(approximation.docID());
    }

    @Override
    public float matchCost() {
      return 1f;
    }

    @Override
    public void intoBitSet(int upTo, FixedBitSet bitSet, int offset) throws IOException {
      for (int doc = approximation.docID(); doc < upTo; doc = approximation.nextDoc()) {
        if (matchSet.get(doc)) {
          bitSet.set(doc - offset);
        }
      }
    }
  }

  private FixedBitSet modBitSet(int maxDoc, int step) {
    FixedBitSet bits = new FixedBitSet(maxDoc);
    for (int i = random().nextInt(step); i < maxDoc; i += step) {
      bits.set(i);
    }
    return bits;
  }

  private FixedBitSet supersetOf(FixedBitSet matchSet, int maxDoc) {
    FixedBitSet approx = matchSet.clone();
    approx.or(modBitSet(maxDoc, TestUtil.nextInt(random(), 2, 8)));
    return approx;
  }

  private static FixedBitSet collect(BulkScorer scorer, int maxDoc, Bits acceptDocs)
      throws IOException {
    FixedBitSet result = new FixedBitSet(maxDoc);
    scorer.score(
        new LeafCollector() {
          @Override
          public void setScorer(Scorable scorer) throws IOException {}

          @Override
          public void collect(int doc) throws IOException {
            result.set(doc);
          }
        },
        acceptDocs,
        0,
        DocIdSetIterator.NO_MORE_DOCS);
    return result;
  }

  public void testBulkTwoPhaseCollectsExactMatches() throws IOException {
    int maxDoc = 100_000;
    FixedBitSet match = modBitSet(maxDoc, TestUtil.nextInt(random(), 2, 50));
    BulkScorer scorer =
        new ConstantScoreBulkScorer(
            1f, ScoreMode.COMPLETE_NO_SCORES, new BulkTwoPhase(supersetOf(match, maxDoc), match));
    // AssertingBulkScorer randomly splits the scored range into smaller ranges.
    scorer = AssertingBulkScorer.wrap(random(), scorer, maxDoc);
    assertEquals(match, collect(scorer, maxDoc, null));
  }

  public void testBulkTwoPhaseWithAcceptDocs() throws IOException {
    int maxDoc = 100_000;
    FixedBitSet match = modBitSet(maxDoc, 5);
    FixedBitSet acceptDocs = modBitSet(maxDoc, 2);
    BulkScorer scorer =
        new ConstantScoreBulkScorer(
            1f, ScoreMode.COMPLETE_NO_SCORES, new BulkTwoPhase(supersetOf(match, maxDoc), match));
    scorer = AssertingBulkScorer.wrap(random(), scorer, maxDoc);
    FixedBitSet expected = match.clone();
    expected.and(acceptDocs);
    assertEquals(expected, collect(scorer, maxDoc, acceptDocs));
  }

  /** The competitive-iterator path must confirm matches() before collecting. */
  public void testBulkTwoPhaseWithCompetitiveIterator() throws IOException {
    int maxDoc = 100_000;
    FixedBitSet match = modBitSet(maxDoc, 5);
    FixedBitSet competitive = modBitSet(maxDoc, 7);
    BulkScorer scorer =
        new ConstantScoreBulkScorer(
            1f, ScoreMode.COMPLETE_NO_SCORES, new BulkTwoPhase(supersetOf(match, maxDoc), match));

    FixedBitSet result = new FixedBitSet(maxDoc);
    scorer.score(
        new LeafCollector() {
          private final DocIdSetIterator competitiveIt =
              new BitSetIterator(competitive, competitive.approximateCardinality());

          @Override
          public void setScorer(Scorable scorer) throws IOException {}

          @Override
          public void collect(int doc) throws IOException {
            result.set(doc);
          }

          @Override
          public DocIdSetIterator competitiveIterator() {
            return competitiveIt;
          }
        },
        null,
        0,
        DocIdSetIterator.NO_MORE_DOCS);

    FixedBitSet expected = match.clone();
    expected.and(competitive);
    assertEquals(expected, result);
  }

  public void testSupplierRoutesSparseBulkTwoPhase() throws IOException {
    int maxDoc = 100_000;
    // Sparse approximation (cardinality well below maxDoc/32) so it is not routed to
    // DenseConjunctionBulkScorer.
    FixedBitSet match = modBitSet(maxDoc, 1000);

    ConstantScoreScorerSupplier supplier =
        ConstantScoreScorerSupplier.fromIterator(
            TwoPhaseIterator.asDocIdSetIterator(new BulkTwoPhase(match.clone(), match)),
            1f,
            ScoreMode.COMPLETE_NO_SCORES,
            maxDoc);
    BulkScorer bulkScorer = supplier.bulkScorer();
    assertTrue(
        "expected ConstantScoreBulkScorer but got " + bulkScorer.getClass().getName(),
        bulkScorer instanceof ConstantScoreBulkScorer);
    assertEquals(match, collect(bulkScorer, maxDoc, null));
  }

  public void testSupplierRoutesSparseNonBulkTwoPhase() throws IOException {
    int maxDoc = 100_000;
    FixedBitSet match = modBitSet(maxDoc, 1000);

    // Even a two-phase iterator that doesn't override intoBitSet is routed to
    // ConstantScoreBulkScorer:
    // its default intoBitSet confirms matches one doc at a time, so this is no worse than leap-frog
    // while still batching live-docs masking and collection.
    ConstantScoreScorerSupplier supplier =
        ConstantScoreScorerSupplier.fromIterator(
            TwoPhaseIterator.asDocIdSetIterator(
                new RandomTwoPhaseView(
                    random(), new BitSetIterator(match, match.approximateCardinality()))),
            1f,
            ScoreMode.COMPLETE_NO_SCORES,
            maxDoc);
    BulkScorer bulkScorer = supplier.bulkScorer();
    assertTrue(
        "expected ConstantScoreBulkScorer but got " + bulkScorer.getClass().getName(),
        bulkScorer instanceof ConstantScoreBulkScorer);
    assertEquals(match, collect(bulkScorer, maxDoc, null));
  }

  /**
   * Matches of a plain iterator are collected one by one where they are far apart from each other
   * and via bit sets where they are close to each other. Check that switching back and forth
   * between the two does not lose or duplicate matches.
   */
  public void testPlainIteratorWithSparseAndDenseRanges() throws IOException {
    int maxDoc = 200_000;
    FixedBitSet match = new FixedBitSet(maxDoc);
    for (int doc = 0; doc < maxDoc; ) {
      // alternate ranges of doc IDs that have different densities of matches
      int rangeEnd = Math.min(maxDoc, doc + TestUtil.nextInt(random(), 1, 20_000));
      int step =
          switch (random().nextInt(4)) {
            case 0 -> 1;
            case 1 -> TestUtil.nextInt(random(), 2, 100);
            case 2 -> TestUtil.nextInt(random(), 100, 1_000);
            default -> TestUtil.nextInt(random(), 1_000, 10_000);
          };
      for (; doc < rangeEnd; doc += TestUtil.nextInt(random(), 1, step)) {
        match.set(doc);
      }
    }
    FixedBitSet acceptDocs = random().nextBoolean() ? null : modBitSet(maxDoc, 3);
    FixedBitSet expected = match.clone();
    if (acceptDocs != null) {
      expected.and(acceptDocs);
    }

    BulkScorer scorer =
        new ConstantScoreBulkScorer(
            1f,
            ScoreMode.COMPLETE_NO_SCORES,
            new BitSetIterator(match, match.approximateCardinality()));
    // AssertingBulkScorer randomly splits the scored range into smaller ranges.
    scorer = AssertingBulkScorer.wrap(random(), scorer, maxDoc);

    FixedBitSet result = new FixedBitSet(maxDoc);
    int[] lastDoc = {-1};
    scorer.score(
        new LeafCollector() {
          @Override
          public void setScorer(Scorable scorer) throws IOException {}

          @Override
          public void collect(int doc) throws IOException {
            assertTrue("docs must be collected in order", doc > lastDoc[0]);
            lastDoc[0] = doc;
            result.set(doc);
          }
        },
        acceptDocs,
        0,
        DocIdSetIterator.NO_MORE_DOCS);
    assertEquals(expected, result);
  }

  /** Matches that are far apart from each other must not be loaded into bit sets. */
  public void testSparsePlainIteratorIsCollectedOneByOne() throws IOException {
    int maxDoc = 1_000_000;
    FixedBitSet match = modBitSet(maxDoc, TestUtil.nextInt(random(), 200, 10_000));
    BulkScorer scorer =
        new ConstantScoreBulkScorer(
            1f,
            ScoreMode.COMPLETE_NO_SCORES,
            new BitSetIterator(match, match.approximateCardinality()));
    FixedBitSet result = new FixedBitSet(maxDoc);
    scorer.score(
        new LeafCollector() {
          @Override
          public void setScorer(Scorable scorer) throws IOException {}

          @Override
          public void collect(int doc) throws IOException {
            result.set(doc);
          }

          @Override
          public void collect(DocIdStream stream) throws IOException {
            fail("Sparse matches should be collected one by one");
          }
        },
        null,
        0,
        DocIdSetIterator.NO_MORE_DOCS);
    assertEquals(match, result);
  }

  /** Matches that are close to each other must be loaded into bit sets. */
  public void testDensePlainIteratorIsCollectedViaBitSets() throws IOException {
    int maxDoc = 1_000_000;
    FixedBitSet match = modBitSet(maxDoc, TestUtil.nextInt(random(), 1, 100));
    BulkScorer scorer =
        new ConstantScoreBulkScorer(
            1f,
            ScoreMode.COMPLETE_NO_SCORES,
            new BitSetIterator(match, match.approximateCardinality()));
    FixedBitSet result = new FixedBitSet(maxDoc);
    int[] collectedOneByOne = new int[1];
    scorer.score(
        new LeafCollector() {
          @Override
          public void setScorer(Scorable scorer) throws IOException {}

          @Override
          public void collect(int doc) throws IOException {
            collectedOneByOne[0]++;
            result.set(doc);
          }

          @Override
          public void collect(DocIdStream stream) throws IOException {
            stream.forEach(result::set);
          }
        },
        null,
        0,
        DocIdSetIterator.NO_MORE_DOCS);
    assertEquals(match, result);
    // Only the first matches are collected one by one, until we know that matches are close to
    // each other.
    assertEquals(ConstantScoreBulkScorer.MIN_WINDOW_MATCHES, collectedOneByOne[0]);
  }

  /**
   * The plain-iterator constructor must reject a {@link DocIdSetIterator} that wraps a {@link
   * TwoPhaseIterator}: such an iterator has to go through the two-phase constructor so its
   * (upTo-bounded) {@link TwoPhaseIterator#intoBitSet} confirms matches. If it slipped through as a
   * plain iterator we would scan the bare approximation and lose the over-scan guarantee. {@link
   * ConstantScoreScorerSupplier} already unwraps unconditionally, but this guard makes the
   * invariant explicit and fail-fast for any future caller.
   */
  public void testRejectsWrappedTwoPhaseOnPlainConstructor() {
    int maxDoc = 100_000;
    FixedBitSet match = modBitSet(maxDoc, 5);
    DocIdSetIterator wrapped =
        TwoPhaseIterator.asDocIdSetIterator(new BulkTwoPhase(supersetOf(match, maxDoc), match));
    IllegalArgumentException e =
        expectThrows(
            IllegalArgumentException.class,
            () -> new ConstantScoreBulkScorer(1f, ScoreMode.COMPLETE_NO_SCORES, wrapped));
    assertEquals("Iterator must not wrap a TwoPhaseIterator", e.getMessage());
  }
}

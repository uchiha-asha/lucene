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
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.FixedBitSet;
import org.apache.lucene.util.MathUtil;

/**
 * Bulk scorer for no-score constant-score iterators that batches doc IDs via {@link
 * DocIdSetIterator#intoBitSet}.
 *
 * <p>Loading a window of doc IDs into a bit set, collecting it and clearing it has a cost that is
 * proportional to the size of the window rather than to the number of matches in the window. This
 * only pays off if windows contain enough matches, so iterators are collected one doc at a time for
 * as long as their matches are too far apart from each other.
 */
final class ConstantScoreBulkScorer extends BulkScorer {

  /**
   * Minimum number of matches that a window of {@link DenseConjunctionBulkScorer#WINDOW_SIZE} doc
   * IDs must have for {@link DocIdSetIterator#intoBitSet} to be used.
   */
  static final int MIN_WINDOW_MATCHES = DenseConjunctionBulkScorer.WINDOW_SIZE / 128;

  /**
   * Number of doc IDs between the end of a window and the next match above which we check whether
   * the window had fewer than {@link #MIN_WINDOW_MATCHES} matches. This is several times the
   * average gap between matches of a window that has {@link #MIN_WINDOW_MATCHES} matches, so that
   * windows whose number of matches is slightly above this threshold rarely pay for the check.
   */
  private static final int MIN_GAP_FOR_SPARSE_CHECK = DenseConjunctionBulkScorer.WINDOW_SIZE / 8;

  /**
   * Maximum number of consecutive windows that are collected through bit sets without checking
   * whether they had fewer than {@link #MIN_WINDOW_MATCHES} matches. This bounds the number of
   * windows collected through bit sets when sparse matches keep falling right after the end of each
   * window, so that the gap check never triggers. Must be a power of two.
   */
  static final int MAX_WINDOWS_BETWEEN_SPARSE_CHECKS = 8;

  private final Scorer scorer;
  private final DocIdSetIterator iterator;
  // When non-null, matches of the approximation must be confirmed via twoPhase#matches() /
  // twoPhase#intoBitSet rather than taken as-is.
  private final TwoPhaseIterator twoPhase;
  private final FixedBitSet windowMatches = new FixedBitSet(DenseConjunctionBulkScorer.WINDOW_SIZE);
  // Whether matches were too far apart from each other for bit sets to be used last time we
  // checked. Only used with plain iterators, two-phase iterators always use bit sets.
  private boolean sparse = true;
  // Number of windows collected through bit sets since matches were last counted.
  private int windowsSinceSparseCheck;

  ConstantScoreBulkScorer(float score, ScoreMode scoreMode, DocIdSetIterator iterator) {
    this(score, scoreMode, iterator, null);
  }

  /**
   * Variant that confirms the matches of a {@link TwoPhaseIterator}. It is worthwhile when the
   * two-phase iterator overrides {@link TwoPhaseIterator#intoBitSet} with a bulk implementation, so
   * a single clause can be collected window-by-window instead of confirmed one doc at a time.
   */
  ConstantScoreBulkScorer(float score, ScoreMode scoreMode, TwoPhaseIterator twoPhase) {
    this(score, scoreMode, twoPhase.approximation(), twoPhase);
  }

  private ConstantScoreBulkScorer(
      float score, ScoreMode scoreMode, DocIdSetIterator iterator, TwoPhaseIterator twoPhase) {
    if (scoreMode.needsScores()) {
      throw new IllegalArgumentException("ScoreMode must not need scores: " + scoreMode);
    }
    if (twoPhase == null && TwoPhaseIterator.unwrap(iterator) != null) {
      throw new IllegalArgumentException("Iterator must not wrap a TwoPhaseIterator");
    }
    this.scorer =
        twoPhase == null
            ? new ConstantScoreScorer(score, scoreMode, iterator)
            : new ConstantScoreScorer(score, scoreMode, twoPhase);
    this.iterator = iterator;
    this.twoPhase = twoPhase;
  }

  @Override
  public int score(LeafCollector collector, Bits acceptDocs, int min, int max) throws IOException {
    collector.setScorer(scorer);
    DocIdSetIterator competitiveIterator = collector.competitiveIterator();
    if (competitiveIterator != null) {
      scoreIterator(collector, acceptDocs, iterator, twoPhase, competitiveIterator, min, max);
    } else {
      scoreIteratorIntoBitSet(collector, acceptDocs, iterator, min, max);
    }
    return iterator.docID();
  }

  @Override
  public long cost() {
    return iterator.cost();
  }

  private static void scoreIterator(
      LeafCollector collector,
      Bits acceptDocs,
      DocIdSetIterator iterator,
      TwoPhaseIterator twoPhase,
      DocIdSetIterator competitiveIterator,
      int min,
      int max)
      throws IOException {
    if (competitiveIterator.docID() > min) {
      min = Math.min(competitiveIterator.docID(), max);
    }
    if (iterator.docID() < min) {
      if (iterator.docID() == min - 1) {
        iterator.nextDoc();
      } else {
        iterator.advance(min);
      }
    }
    for (int doc = iterator.docID(); doc < max; ) {
      assert competitiveIterator.docID() <= doc; // invariant
      if (competitiveIterator.docID() < doc) {
        int competitiveNext = competitiveIterator.advance(doc);
        if (competitiveNext != doc) {
          doc = iterator.advance(competitiveNext);
          continue;
        }
      }

      if ((acceptDocs == null || acceptDocs.get(doc)) && (twoPhase == null || twoPhase.matches())) {
        collector.collect(doc);
      }

      doc = iterator.nextDoc();
    }
  }

  private void scoreIteratorIntoBitSet(
      LeafCollector collector, Bits acceptDocs, DocIdSetIterator iterator, int min, int max)
      throws IOException {
    if (iterator.docID() < min) {
      if (iterator.docID() == min - 1) {
        iterator.nextDoc();
      } else {
        iterator.advance(min);
      }
    }
    for (int doc = iterator.docID(); doc < max; ) {
      int windowBase = doc;

      if (twoPhase == null && sparse) {
        // Collect matches one by one, and only start using bit sets if we find enough matches that
        // are close to each other.
        final int sparseMax =
            MathUtil.unsignedMin(max, windowBase + DenseConjunctionBulkScorer.WINDOW_SIZE);
        int numMatches = 0;
        do {
          if (acceptDocs == null || acceptDocs.get(doc)) {
            collector.collect(doc);
          }
          doc = iterator.nextDoc();
        } while (++numMatches < MIN_WINDOW_MATCHES && doc < sparseMax);
        if (doc >= sparseMax) {
          continue;
        }
        sparse = false;
        // Keep the base of the bit set a multiple of 64 away from the first match that we
        // collected. Iterators that have long runs of adjacent doc IDs, e.g. postings of an index
        // that is sorted, can then keep copying entire words into the bit set.
        windowBase = doc - ((doc - windowBase) & 0x3F);
      }

      int windowMax =
          MathUtil.unsignedMin(max, windowBase + DenseConjunctionBulkScorer.WINDOW_SIZE);

      assert windowMatches.scanIsEmpty();
      if (twoPhase == null) {
        iterator.intoBitSet(windowMax, windowMatches, windowBase);
        // Go back to collecting matches one by one if this window had few matches. Counting
        // matches of every window would be wasteful, so only do it if the next match is far
        // enough from the end of this window to suggest that matches are getting sparse, or once
        // every MAX_WINDOWS_BETWEEN_SPARSE_CHECKS windows.
        if (iterator.docID() - windowMax >= MIN_GAP_FOR_SPARSE_CHECK
            || ++windowsSinceSparseCheck == MAX_WINDOWS_BETWEEN_SPARSE_CHECKS) {
          windowsSinceSparseCheck = 0;
          sparse = windowMatches.cardinality() < MIN_WINDOW_MATCHES;
        }
      } else {
        twoPhase.intoBitSet(windowMax, windowMatches, windowBase);
      }

      if (acceptDocs != null) {
        acceptDocs.applyMask(windowMatches, windowBase);
      }

      collector.collect(new BitSetDocIdStream(windowMatches, windowBase));
      windowMatches.clear();

      doc = iterator.docID();
    }
  }
}

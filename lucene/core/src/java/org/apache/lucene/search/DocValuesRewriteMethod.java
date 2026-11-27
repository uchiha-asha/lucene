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
import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.DocValuesSkipper;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.util.BytesRef;

/**
 * Rewrites MultiTermQueries into a filter, using DocValues for term enumeration.
 *
 * <p>This can be used to perform these queries against an unindexed docvalues field.
 *
 * @lucene.experimental
 */
public final class DocValuesRewriteMethod extends MultiTermQuery.RewriteMethod {

  @Override
  public Query rewrite(IndexSearcher indexSearcher, MultiTermQuery query) {
    return new ConstantScoreQuery(new MultiTermQueryDocValuesWrapper(query));
  }

  static class MultiTermQueryDocValuesWrapper extends Query {

    protected final MultiTermQuery query;

    /** Wrap a {@link MultiTermQuery} as a Filter. */
    protected MultiTermQueryDocValuesWrapper(MultiTermQuery query) {
      this.query = query;
    }

    @Override
    public String toString(String field) {
      // query.toString should be ok for the filter, too, if the query boost is 1.0f
      return query.toString(field);
    }

    @Override
    public final boolean equals(final Object other) {
      return sameClassAs(other) && query.equals(((MultiTermQueryDocValuesWrapper) other).query);
    }

    @Override
    public final int hashCode() {
      return 31 * classHash() + query.hashCode();
    }

    /** Returns the field name for this query */
    public final String getField() {
      return query.getField();
    }

    @Override
    public void visit(QueryVisitor visitor) {
      if (visitor.acceptField(query.getField())) {
        visitor.getSubVisitor(BooleanClause.Occur.FILTER, query);
      }
    }

    @Override
    public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost)
        throws IOException {
      return new ConstantScoreWeight(this, boost) {

        @Override
        public Matches matches(LeafReaderContext context, int doc) throws IOException {
          final SortedSetDocValues values = DocValues.getSortedSet(context.reader(), query.field);
          return MatchesUtils.forField(
              query.field,
              () ->
                  DisjunctionMatchesIterator.fromTermsEnum(
                      context, doc, query, query.field, getTermsEnum(values)));
        }

        /**
         * Create a TermsEnum that provides the intersection of the query terms with the terms
         * present in the doc values.
         */
        private TermsEnum getTermsEnum(SortedSetDocValues values) throws IOException {
          return query.getTermsEnum(
              new Terms() {

                @Override
                public TermsEnum iterator() throws IOException {
                  return values.termsEnum();
                }

                @Override
                public long getSumTotalTermFreq() {
                  throw new UnsupportedOperationException();
                }

                @Override
                public long getSumDocFreq() {
                  throw new UnsupportedOperationException();
                }

                @Override
                public int getDocCount() {
                  throw new UnsupportedOperationException();
                }

                @Override
                public long size() {
                  return -1;
                }

                @Override
                public boolean hasFreqs() {
                  return false;
                }

                @Override
                public boolean hasOffsets() {
                  return false;
                }

                @Override
                public boolean hasPositions() {
                  return false;
                }

                @Override
                public boolean hasPayloads() {
                  return false;
                }
              });
        }

        @Override
        public ScorerSupplier scorerSupplier(LeafReaderContext context) throws IOException {
          final SortedSetDocValues values = DocValues.getSortedSet(context.reader(), query.field);
          if (values.getValueCount() == 0) {
            return null; // no values/docs so nothing can match
          }
          final OrdinalRange ordinalRange = ordinalRange(query, values);
          if (ordinalRange != null && ordinalRange.min > ordinalRange.max) {
            return null; // no term in the doc values falls in the range
          }

          return new ScorerSupplier() {
            @Override
            public Scorer get(long leadCost) throws IOException {
              // Leverage a DV skipper if one was indexed for the field:
              DocValuesSkipper skipper = context.reader().getDocValuesSkipper(query.field);
              SortedDocValues singleton = DocValues.unwrapSingleton(values);
              if (ordinalRange != null) {
                return new ConstantScoreScorer(
                    score(),
                    scoreMode,
                    singleton == null
                        ? DocValuesRangeIterator.forOrdinalRange(
                            values, skipper, ordinalRange.min, ordinalRange.max)
                        : DocValuesRangeIterator.forOrdinalRange(
                            singleton, skipper, ordinalRange.min, ordinalRange.max));
              }
              // Create a TermsEnum that will provide the intersection of the terms specified in the
              // query with the values present in the doc values:
              TermsEnum termsEnum = getTermsEnum(values);
              assert termsEnum != null;
              return singleton == null
                  ? new ConstantScoreScorer(
                      score(),
                      scoreMode,
                      DocValuesRangeIterator.forOrdinalSet(values, skipper, termsEnum))
                  : new ConstantScoreScorer(
                      score(),
                      scoreMode,
                      DocValuesRangeIterator.forOrdinalSet(singleton, skipper, termsEnum));
            }

            @Override
            public long cost() {
              // We have no prior knowledge of how many docs might match for any given query term,
              // so we assume that all docs with a value could be a match:
              return values.cost();
            }
          };
        }

        @Override
        public boolean isCacheable(LeafReaderContext ctx) {
          return DocValues.isCacheable(ctx, query.field);
        }
      };
    }
  }

  /** Inclusive range of ordinals; empty if {@code min > max}. */
  private record OrdinalRange(long min, long max) {}

  /**
   * Returns the ordinals of the terms matched by {@code query} if they are known to form a
   * contiguous range in term order, as is the case for prefix and term range queries. This finds
   * the bounds with two term lookups instead of enumerating every matching term. Returns {@code
   * null} for other queries.
   */
  private static OrdinalRange ordinalRange(MultiTermQuery query, SortedSetDocValues values)
      throws IOException {
    final long maxOrd = values.getValueCount() - 1;
    // Exact classes only: a subclass could override getTermsEnum to match other terms.
    if (query.getClass() == PrefixQuery.class) {
      PrefixQuery prefixQuery = (PrefixQuery) query;
      BytesRef prefix = prefixQuery.getPrefix().bytes();
      BytesRef end = prefixEnd(prefix);
      return new OrdinalRange(
          ceilOrd(values, prefix), end == null ? maxOrd : ceilOrd(values, end) - 1);
    } else if (query.getClass() == TermRangeQuery.class) {
      TermRangeQuery rangeQuery = (TermRangeQuery) query;
      BytesRef lower = rangeQuery.getLowerTerm();
      BytesRef upper = rangeQuery.getUpperTerm();
      long min;
      if (lower == null) {
        min = 0;
      } else {
        min = rangeQuery.includesLower() ? ceilOrd(values, lower) : higherOrd(values, lower);
      }
      long max;
      if (upper == null) {
        max = maxOrd;
      } else {
        max = (rangeQuery.includesUpper() ? higherOrd(values, upper) : ceilOrd(values, upper)) - 1;
      }
      return new OrdinalRange(min, max);
    }
    return null;
  }

  /** Ordinal of the smallest term {@code >= key}, or the value count if there is none. */
  private static long ceilOrd(SortedSetDocValues values, BytesRef key) throws IOException {
    long ord = values.lookupTerm(key);
    return ord >= 0 ? ord : -1 - ord;
  }

  /** Ordinal of the smallest term {@code > key}, or the value count if there is none. */
  private static long higherOrd(SortedSetDocValues values, BytesRef key) throws IOException {
    long ord = values.lookupTerm(key);
    return ord >= 0 ? ord + 1 : -1 - ord;
  }

  /**
   * Returns the smallest term that is greater than every term starting with {@code prefix}, or
   * {@code null} if there is no such term because the prefix is empty or only has 0xFF bytes.
   */
  private static BytesRef prefixEnd(BytesRef prefix) {
    for (int i = prefix.length - 1; i >= 0; i--) {
      if (prefix.bytes[prefix.offset + i] != (byte) 0xFF) {
        byte[] end = new byte[i + 1];
        System.arraycopy(prefix.bytes, prefix.offset, end, 0, i + 1);
        end[i]++;
        return new BytesRef(end);
      }
    }
    return null;
  }

  @Override
  public boolean equals(Object other) {
    return other != null && getClass() == other.getClass();
  }

  @Override
  public int hashCode() {
    return 641;
  }
}

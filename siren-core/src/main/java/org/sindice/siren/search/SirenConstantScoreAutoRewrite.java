/**
 * Copyright (c) 2009-2011 Sindice Limited. All Rights Reserved.
 *
 * Project and contact information: http://www.siren.sindice.com/
 *
 * This file is part of the SIREn project.
 *
 * SIREn is a free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * SIREn is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public
 * License along with SIREn. If not, see <http://www.gnu.org/licenses/>.
 */
/**
 * @project siren-core_rdelbru
 * @author Campinas Stephane [ 21 Sep 2011 ]
 * @link stephane.campinas@deri.org
 */
package org.sindice.siren.search;

import java.io.IOException;
import java.util.ArrayList;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.ConstantScoreQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.MultiTermQuery.ConstantScoreAutoRewrite;
import org.sindice.siren.search.SirenBooleanClause.Occur;

/**
 * class copied from {@link ConstantScoreAutoRewrite}
 */
public class SirenConstantScoreAutoRewrite extends SirenTermCollectingRewrite<SirenBooleanQuery> {

  // Defaults derived from rough tests with a 20.0 million
  // doc Wikipedia index.  With more than 350 terms in the
  // query, the filter method is fastest:
  public static int DEFAULT_TERM_COUNT_CUTOFF = 350;

  // If the query will hit more than 1 in 1000 of the docs
  // in the index (0.1%), the filter method is fastest:
  public static double DEFAULT_DOC_COUNT_PERCENT = 0.1;

  private int termCountCutoff = DEFAULT_TERM_COUNT_CUTOFF;
  private double docCountPercent = DEFAULT_DOC_COUNT_PERCENT;

  /** If the number of terms in this query is equal to or
   *  larger than this setting then {@link
   *  #CONSTANT_SCORE_FILTER_REWRITE} is used. */
  public void setTermCountCutoff(int count) {
    termCountCutoff = count;
  }

  /** @see #setTermCountCutoff */
  public int getTermCountCutoff() {
    return termCountCutoff;
  }

  /** If the number of documents to be visited in the
   *  postings exceeds this specified percentage of the
   *  maxDoc() for the index, then {@link
   *  #CONSTANT_SCORE_FILTER_REWRITE} is used.
   *  @param percent 0.0 to 100.0 */
  public void setDocCountPercent(double percent) {
    docCountPercent = percent;
  }

  /** @see #setDocCountPercent */
  public double getDocCountPercent() {
    return docCountPercent;
  }

  @Override
  protected SirenBooleanQuery getTopLevelQuery() {
    return new SirenBooleanQuery(true);
  }
  
  @Override
  protected void addClause(SirenBooleanQuery topLevel, Term term, float boost /*ignored*/) {
    topLevel.add(new SirenTermQuery(term), Occur.SHOULD);
  }

  @Override
  public Query rewrite(final IndexReader reader, final SirenMultiTermQuery query) throws IOException {

    // Get the enum and start visiting terms.  If we
    // exhaust the enum before hitting either of the
    // cutoffs, we use ConstantBooleanQueryRewrite; else,
    // ConstantFilterRewrite:
    final int docCountCutoff = (int) ((docCountPercent / 100.) * reader.maxDoc());
    final int termCountLimit = Math.min(BooleanQuery.getMaxClauseCount(), termCountCutoff);

    final CutOffTermCollector col = new CutOffTermCollector(reader, docCountCutoff, termCountLimit);
    collectTerms(reader, query, col);
    
    if (col.hasCutOff) {
      return SirenMultiTermQuery.CONSTANT_SCORE_FILTER_REWRITE.rewrite(reader, query);
    } else {
      final Query result;
      if (col.pendingTerms.isEmpty()) {
        result = getTopLevelQuery();
      } else {
        SirenBooleanQuery bq = getTopLevelQuery();
        for(Term term : col.pendingTerms) {
          addClause(bq, term, 1.0f);
        }
        // Strip scores
        result = new ConstantScoreQuery(bq);
        result.setBoost(query.getBoost());
      }
      query.incTotalNumberOfTerms(col.pendingTerms.size());
      return result;
    }
  }
  
  private static final class CutOffTermCollector implements TermCollector {
    CutOffTermCollector(IndexReader reader, int docCountCutoff, int termCountLimit) {
      this.reader = reader;
      this.docCountCutoff = docCountCutoff;
      this.termCountLimit = termCountLimit;
    }
  
    public boolean collect(Term t, float boost) throws IOException {
      pendingTerms.add(t);
      // Loading the TermInfo from the terms dict here
      // should not be costly, because 1) the
      // query/filter will load the TermInfo when it
      // runs, and 2) the terms dict has a cache:
      docVisitCount += reader.docFreq(t);
      if (pendingTerms.size() >= termCountLimit || docVisitCount >= docCountCutoff) {
        hasCutOff = true;
        return false;
      }
      return true;
    }
    
    int docVisitCount = 0;
    boolean hasCutOff = false;
    
    final IndexReader reader;
    final int docCountCutoff, termCountLimit;
    final ArrayList<Term> pendingTerms = new ArrayList<Term>();
  }

  @Override
  public int hashCode() {
    final int prime = 1279;
    return (int) (prime * termCountCutoff + Double.doubleToLongBits(docCountPercent));
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj)
      return true;
    if (obj == null)
      return false;
    if (getClass() != obj.getClass())
      return false;

    SirenConstantScoreAutoRewrite other = (SirenConstantScoreAutoRewrite) obj;
    if (other.termCountCutoff != termCountCutoff) {
      return false;
    }

    if (Double.doubleToLongBits(other.docCountPercent) != Double.doubleToLongBits(docCountPercent)) {
      return false;
    }
    
    return true;
  }

}

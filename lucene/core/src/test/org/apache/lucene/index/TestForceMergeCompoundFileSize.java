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
package org.apache.lucene.index;

import java.io.IOException;
import java.util.Map;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.store.Directory;
import org.apache.lucene.tests.index.MockRandomMergePolicy;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.tests.util.TestUtil;

/**
 * forceMerge(1) must leave an already merged segment alone when the compound file size limit falls
 * between the segment's size before and after it was packed into a compound file.
 */
public class TestForceMergeCompoundFileSize extends LuceneTestCase {

  private static final int MAX_FORCED_MERGES = 10;

  /** Only hands out forced merges, counts them, and gives up after MAX_FORCED_MERGES. */
  private static class CountingMergePolicy extends FilterMergePolicy {
    int forcedMerges;

    CountingMergePolicy(MergePolicy in) {
      super(in);
    }

    @Override
    public MergeSpecification findMerges(
        MergeTrigger mergeTrigger, SegmentInfos segmentInfos, MergeContext mergeContext) {
      return null;
    }

    @Override
    public MergeSpecification findFullFlushMerges(
        MergeTrigger mergeTrigger, SegmentInfos segmentInfos, MergeContext mergeContext) {
      return null;
    }

    @Override
    public MergeSpecification findForcedMerges(
        SegmentInfos segmentInfos,
        int maxSegmentCount,
        Map<SegmentCommitInfo, Boolean> segmentsToMerge,
        MergeContext mergeContext)
        throws IOException {
      if (forcedMerges >= MAX_FORCED_MERGES) {
        return null; // let forceMerge return so that the test fails instead of hanging
      }
      MergeSpecification spec =
          super.findForcedMerges(segmentInfos, maxSegmentCount, segmentsToMerge, mergeContext);
      if (spec != null) {
        forcedMerges += spec.merges.size();
      }
      return spec;
    }
  }

  private static void addDocs(IndexWriter w, int count) throws IOException {
    for (int i = 0; i < count; i++) {
      Document doc = new Document();
      doc.add(new StringField("id", Integer.toString(i), Field.Store.YES));
      doc.add(new TextField("body", "the quick brown fox number " + i + " jumps", Field.Store.NO));
      w.addDocument(doc);
    }
  }

  private void doTestNoRewrite(MergePolicy mergePolicy) throws IOException {
    // A private codec instance, so that the compound file settings don't leak into other tests.
    Codec codec = TestUtil.getDefaultCodec();
    CountingMergePolicy mp = new CountingMergePolicy(mergePolicy);
    IndexWriterConfig iwc =
        new IndexWriterConfig()
            .setCodec(codec)
            .setMergePolicy(mp)
            .setMergeScheduler(new SerialMergeScheduler());
    try (Directory dir = newDirectory();
        IndexWriter w = new IndexWriter(dir, iwc)) {
      addDocs(w, 150);
      w.commit();
      addDocs(w, 150);
      w.commit();
      w.forceMerge(1);
      assertEquals(1, mp.forcedMerges);

      SegmentInfos infos = w.cloneSegmentInfos();
      assertEquals(1, infos.size());
      SegmentCommitInfo merged = infos.info(0);
      assertTrue(merged.info.getUseCompoundFile());

      // IndexWriter chose a compound file from the size of the merged files, before it packed them
      // and wrote the .si file. Put the limit between that size and the size of the segment now.
      long packedSize = merged.sizeInBytes();
      long siSize = dir.fileLength(IndexFileNames.segmentFileName(merged.info.name, "", "si"));
      assertTrue("siSize=" + siSize, siSize >= 64);
      long maxCFSSegmentSize = packedSize - siSize / 2;
      codec.compoundFormat().setMaxCFSSegmentSizeMB(maxCFSSegmentSize / 1024. / 1024.);

      for (int i = 0; i < 2; i++) {
        mp.forcedMerges = 0;
        w.forceMerge(1);
        assertEquals("forceMerge(1) call " + i, 0, mp.forcedMerges);
        assertEquals(merged.info.name, w.cloneSegmentInfos().info(0).info.name);
      }
    }
  }

  public void testMockRandomMergePolicy() throws IOException {
    MockRandomMergePolicy mp = new MockRandomMergePolicy(random());
    mp.setDoNonBulkMerges(false);
    doTestNoRewrite(mp);
  }

  public void testTieredMergePolicy() throws IOException {
    doTestNoRewrite(new TieredMergePolicy());
  }

  public void testLogByteSizeMergePolicy() throws IOException {
    doTestNoRewrite(new LogByteSizeMergePolicy());
  }

  public void testLogDocMergePolicy() throws IOException {
    doTestNoRewrite(new LogDocMergePolicy());
  }
}

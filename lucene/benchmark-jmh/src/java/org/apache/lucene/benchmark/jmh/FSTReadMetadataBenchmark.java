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
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.apache.lucene.store.ByteArrayDataInput;
import org.apache.lucene.store.ByteBuffersDataOutput;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.IntsRefBuilder;
import org.apache.lucene.util.fst.ByteSequenceOutputs;
import org.apache.lucene.util.fst.FST;
import org.apache.lucene.util.fst.FSTCompiler;
import org.apache.lucene.util.fst.NoOutputs;
import org.apache.lucene.util.fst.Outputs;
import org.apache.lucene.util.fst.Util;
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
import org.openjdk.jmh.annotations.Warmup;

/** Reads the metadata of an FST that accepts the empty input. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 4, time = 1)
@Measurement(iterations = 6, time = 1)
@Fork(2)
public class FSTReadMetadataBenchmark {

  /** Empty-string output length in bytes; 0 means {@link NoOutputs}. */
  @Param({"0", "16", "1024", "4096"})
  int emptyOutputLength;

  private byte[] metadata;
  private Outputs<?> outputs;

  @Setup(Level.Trial)
  public void setup() throws IOException {
    IntsRefBuilder scratch = new IntsRefBuilder();
    ByteBuffersDataOutput metaOut = new ByteBuffersDataOutput();
    ByteBuffersDataOutput dataOut = new ByteBuffersDataOutput();
    if (emptyOutputLength == 0) {
      NoOutputs noOutputs = NoOutputs.getSingleton();
      FSTCompiler<Object> compiler =
          new FSTCompiler.Builder<>(FST.INPUT_TYPE.BYTE1, noOutputs).build();
      compiler.add(Util.toIntsRef(new BytesRef(), scratch), noOutputs.getNoOutput());
      compiler.add(Util.toIntsRef(new BytesRef("a"), scratch), noOutputs.getNoOutput());
      FST.fromFSTReader(compiler.compile(), compiler.getFSTReader()).save(metaOut, dataOut);
      outputs = noOutputs;
    } else {
      ByteSequenceOutputs byteOutputs = ByteSequenceOutputs.getSingleton();
      byte[] bytes = new byte[emptyOutputLength];
      new Random(42).nextBytes(bytes);
      FSTCompiler<BytesRef> compiler =
          new FSTCompiler.Builder<>(FST.INPUT_TYPE.BYTE1, byteOutputs).build();
      compiler.add(Util.toIntsRef(new BytesRef(), scratch), new BytesRef(bytes));
      compiler.add(Util.toIntsRef(new BytesRef("a"), scratch), new BytesRef("x"));
      FST.fromFSTReader(compiler.compile(), compiler.getFSTReader()).save(metaOut, dataOut);
      outputs = byteOutputs;
    }
    metadata = metaOut.toArrayCopy();
  }

  @Benchmark
  public Object readMetadata() throws IOException {
    return FST.readMetadata(new ByteArrayDataInput(metadata), outputs).getEmptyOutput();
  }
}

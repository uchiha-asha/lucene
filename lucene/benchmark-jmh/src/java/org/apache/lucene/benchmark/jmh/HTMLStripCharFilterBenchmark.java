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
import java.io.StringReader;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.apache.lucene.analysis.charfilter.HTMLStripCharFilter;
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

/** Strips one field value per op with a fresh {@link HTMLStripCharFilter}, as analysis does. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 4, time = 1)
@Measurement(iterations = 6, time = 1)
@Fork(2)
public class HTMLStripCharFilterBenchmark {

  @Param({"short", "medium", "longHtml", "longComment", "longTag"})
  String input;

  private String text;
  private final char[] buffer = new char[1024];

  @Setup(Level.Trial)
  public void setup() {
    Random random = new Random(42);
    text =
        switch (input) {
          case "short" ->
              "<p>A <b>short</b> product description &amp; a <a href=\"/x\">link</a>.</p>";
          case "medium" -> html(random, 4 * 1024);
          case "longHtml" -> html(random, 256 * 1024);
          case "longComment" -> "<p>start</p><!--" + words(random, 64 * 1024) + "--><p>end</p>";
          case "longTag" -> "<p>start</p><a title='" + words(random, 64 * 1024) + "'>end</a>";
          default -> throw new IllegalArgumentException(input);
        };
  }

  private static String html(Random random, int length) {
    StringBuilder sb = new StringBuilder("<html><head><title>t</title></head><body>");
    while (sb.length() < length) {
      sb.append("<div class=\"c").append(random.nextInt(10)).append("\"><p>");
      sb.append(words(random, 200 + random.nextInt(400)));
      sb.append(" <a href=\"https://example.com/").append(random.nextInt(1000)).append("\">");
      sb.append(words(random, 20)).append("</a> &amp; &lt;x&gt; &#169; ");
      sb.append("<b>").append(words(random, 30)).append("</b><br/>");
      sb.append("<!-- c -->").append(words(random, 100)).append("</p></div>\n");
    }
    return sb.append("</body></html>").toString();
  }

  private static String words(Random random, int length) {
    StringBuilder sb = new StringBuilder(length);
    while (sb.length() < length) {
      int wordLength = 2 + random.nextInt(8);
      for (int i = 0; i < wordLength; i++) {
        sb.append((char) ('a' + random.nextInt(26)));
      }
      sb.append(' ');
    }
    sb.setLength(length);
    return sb.toString();
  }

  @Benchmark
  public int strip() throws IOException {
    int total = 0;
    try (HTMLStripCharFilter filter = new HTMLStripCharFilter(new StringReader(text))) {
      int n;
      while ((n = filter.read(buffer, 0, buffer.length)) != -1) {
        total += n;
      }
    }
    return total;
  }
}

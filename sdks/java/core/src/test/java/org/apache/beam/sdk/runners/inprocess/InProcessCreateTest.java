/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.runners.inprocess;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.coders.AtomicCoder;
import org.apache.beam.sdk.coders.BigEndianIntegerCoder;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.CoderException;
import org.apache.beam.sdk.coders.NullableCoder;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.coders.VarIntCoder;
import org.apache.beam.sdk.io.BoundedSource;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.runners.inprocess.InProcessCreate.InMemorySource;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.testing.RunnableOnService;
import org.apache.beam.sdk.testing.SourceTestUtils;
import org.apache.beam.sdk.testing.TestPipeline;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.util.SerializableUtils;
import org.apache.beam.sdk.values.PCollection;

import com.google.common.collect.ImmutableList;

import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Tests for {@link InProcessCreate}.
 */
@RunWith(JUnit4.class)
public class InProcessCreateTest {
  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Test
  @Category(RunnableOnService.class)
  public void testConvertsCreate() {
    TestPipeline p = TestPipeline.create();
    Create.Values<Integer> og = Create.of(1, 2, 3);

    InProcessCreate<Integer> converted = InProcessCreate.from(og);

    PAssert.that(p.apply(converted)).containsInAnyOrder(2, 1, 3);

    p.run();
  }

  @Test
  @Category(RunnableOnService.class)
  public void testConvertsCreateWithNullElements() {
    Create.Values<String> og =
        Create.<String>of("foo", null, "spam", "ham", null, "eggs")
            .withCoder(NullableCoder.of(StringUtf8Coder.of()));

    InProcessCreate<String> converted = InProcessCreate.from(og);
    TestPipeline p = TestPipeline.create();

    PAssert.that(p.apply(converted))
        .containsInAnyOrder(null, "foo", null, "spam", "ham", "eggs");

    p.run();
  }

  static class Record implements Serializable {}

  static class Record2 extends Record {}

  @Test
  public void testThrowsIllegalArgumentWhenCannotInferCoder() {
    Create.Values<Record> og = Create.of(new Record(), new Record2());
    InProcessCreate<Record> converted = InProcessCreate.from(og);

    Pipeline p = TestPipeline.create();

    // Create won't infer a default coder in this case.
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage(Matchers.containsString("Unable to infer a coder"));

    PCollection<Record> c = p.apply(converted);
    p.run();

    fail("Unexpectedly Inferred Coder " + c.getCoder());
  }

  /**
   * An unserializable class to demonstrate encoding of elements.
   */
  private static class UnserializableRecord {
    private final String myString;

    private UnserializableRecord(String myString) {
      this.myString = myString;
    }

    @Override
    public int hashCode() {
      return myString.hashCode();
    }

    @Override
    public boolean equals(Object o) {
      return myString.equals(((UnserializableRecord) o).myString);
    }

    static class UnserializableRecordCoder extends AtomicCoder<UnserializableRecord> {
      private final Coder<String> stringCoder = StringUtf8Coder.of();

      @Override
      public void encode(
          UnserializableRecord value,
          OutputStream outStream,
          org.apache.beam.sdk.coders.Coder.Context context)
          throws CoderException, IOException {
        stringCoder.encode(value.myString, outStream, context.nested());
      }

      @Override
      public UnserializableRecord decode(
          InputStream inStream, org.apache.beam.sdk.coders.Coder.Context context)
          throws CoderException, IOException {
        return new UnserializableRecord(stringCoder.decode(inStream, context.nested()));
      }
    }
  }

  @Test
  @Category(RunnableOnService.class)
  public void testConvertsUnserializableElements() throws Exception {
    List<UnserializableRecord> elements =
        ImmutableList.of(
            new UnserializableRecord("foo"),
            new UnserializableRecord("bar"),
            new UnserializableRecord("baz"));
    InProcessCreate<UnserializableRecord> create =
        InProcessCreate.from(
            Create.of(elements).withCoder(new UnserializableRecord.UnserializableRecordCoder()));

    TestPipeline p = TestPipeline.create();
    PAssert.that(p.apply(create))
        .containsInAnyOrder(
            new UnserializableRecord("foo"),
            new UnserializableRecord("bar"),
            new UnserializableRecord("baz"));
    p.run();
  }

  @Test
  public void testSerializableOnUnserializableElements() throws Exception {
    List<UnserializableRecord> elements =
        ImmutableList.of(
            new UnserializableRecord("foo"),
            new UnserializableRecord("bar"),
            new UnserializableRecord("baz"));
    InMemorySource<UnserializableRecord> source =
        InMemorySource.fromIterable(elements, new UnserializableRecord.UnserializableRecordCoder());
    SerializableUtils.ensureSerializable(source);
  }

  @Test
  public void testSplitIntoBundles() throws Exception {
    InProcessCreate.InMemorySource<Integer> source =
        InMemorySource.fromIterable(
            ImmutableList.of(1, 2, 3, 4, 5, 6, 7, 8), BigEndianIntegerCoder.of());
    PipelineOptions options = PipelineOptionsFactory.create();
    List<? extends BoundedSource<Integer>> splitSources = source.splitIntoBundles(12, options);
    assertThat(splitSources, hasSize(3));
    SourceTestUtils.assertSourcesEqualReferenceSource(source, splitSources, options);
  }

  @Test
  public void testDoesNotProduceSortedKeys() throws Exception {
    InProcessCreate.InMemorySource<String> source =
        InMemorySource.fromIterable(ImmutableList.of("spam", "ham", "eggs"), StringUtf8Coder.of());
    assertThat(source.producesSortedKeys(PipelineOptionsFactory.create()), is(false));
  }

  @Test
  public void testGetDefaultOutputCoderReturnsConstructorCoder() throws Exception {
    Coder<Integer> coder = VarIntCoder.of();
    InProcessCreate.InMemorySource<Integer> source =
        InMemorySource.fromIterable(ImmutableList.of(1, 2, 3, 4, 5, 6, 7, 8), coder);

    Coder<Integer> defaultCoder = source.getDefaultOutputCoder();
    assertThat(defaultCoder, equalTo(coder));
  }

  @Test
  public void testSplitAtFraction() throws Exception {
    List<Integer> elements = new ArrayList<>();
    Random random = new Random();
    for (int i = 0; i < 25; i++) {
      elements.add(random.nextInt());
    }
    InProcessCreate.InMemorySource<Integer> source =
        InMemorySource.fromIterable(elements, VarIntCoder.of());

    SourceTestUtils.assertSplitAtFractionExhaustive(source, PipelineOptionsFactory.create());
  }
}

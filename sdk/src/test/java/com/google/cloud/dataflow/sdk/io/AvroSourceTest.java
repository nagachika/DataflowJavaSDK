/*
 * Copyright (C) 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.dataflow.sdk.io;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.google.cloud.dataflow.sdk.coders.AvroCoder;
import com.google.cloud.dataflow.sdk.coders.DefaultCoder;
import com.google.cloud.dataflow.sdk.io.AvroSource.AvroReader;
import com.google.cloud.dataflow.sdk.io.AvroSource.AvroReader.Seeker;
import com.google.cloud.dataflow.sdk.options.PipelineOptions;
import com.google.cloud.dataflow.sdk.options.PipelineOptionsFactory;

import org.apache.avro.Schema;
import org.apache.avro.file.CodecFactory;
import org.apache.avro.file.DataFileConstants;
import org.apache.avro.file.DataFileWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.DatumWriter;
import org.apache.avro.reflect.ReflectData;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PushbackInputStream;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Tests for AvroSource.
 */
@RunWith(JUnit4.class)
public class AvroSourceTest {
  @Rule
  public TemporaryFolder tmpFolder = new TemporaryFolder();

  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  private enum SyncBehavior {
    SYNC_REGULAR, // Sync at regular, user defined intervals
    SYNC_RANDOM, // Sync at random intervals
    SYNC_DEFAULT; // Sync at default intervals (i.e., no manual syncing).
  }

  private static final int DEFAULT_RECORD_COUNT = 10000;

  /**
   * Generates an input Avro file containing the given records in the temporary directory and
   * returns the full path of the file.
   */
  private <T> String generateTestFile(String filename, List<T> elems, SyncBehavior syncBehavior,
      int syncInterval, AvroCoder<T> coder, String codec) throws IOException {
    Random random = new Random(0);
    File tmpFile = tmpFolder.newFile(filename);
    String path = tmpFile.toString();

    FileOutputStream os = new FileOutputStream(tmpFile);
    DatumWriter<T> datumWriter = coder.createDatumWriter();
    try (DataFileWriter<T> writer = new DataFileWriter<>(datumWriter)) {
      writer.setCodec(CodecFactory.fromString(codec));
      writer.create(coder.getSchema(), os);

      int recordIndex = 0;
      int syncIndex = syncBehavior == SyncBehavior.SYNC_RANDOM ? random.nextInt(syncInterval) : 0;

      for (T elem : elems) {
        writer.append(elem);
        recordIndex++;

        switch (syncBehavior) {
          case SYNC_REGULAR:
            if (recordIndex == syncInterval) {
              recordIndex = 0;
              writer.sync();
            }
            break;
          case SYNC_RANDOM:
            if (recordIndex == syncIndex) {
              recordIndex = 0;
              writer.sync();
              syncIndex = random.nextInt(syncInterval);
            }
            break;
          case SYNC_DEFAULT:
          default:
        }
      }
    }
    return path;
  }

  @Test
  public void testReadWithDifferentCodecs() throws Exception {
    // Test reading files generated using all codecs.
    String codecs[] = {DataFileConstants.NULL_CODEC, DataFileConstants.BZIP2_CODEC,
        DataFileConstants.DEFLATE_CODEC, DataFileConstants.SNAPPY_CODEC,
        DataFileConstants.XZ_CODEC};
    List<Bird> expected = createRandomRecords(DEFAULT_RECORD_COUNT);

    for (String codec : codecs) {
      String filename = generateTestFile(
          codec, expected, SyncBehavior.SYNC_DEFAULT, 0, AvroCoder.of(Bird.class), codec);
      AvroSource<Bird> source = AvroSource.from(filename).withSchema(Bird.class);
      List<Bird> actual = SourceTestUtils.readFromSource(source, null);
      assertThat(expected, containsInAnyOrder(actual.toArray()));
    }
  }

  @Test
  public void testSplitAtFraction() throws Exception {
    List<FixedRecord> expected = createFixedRecords(DEFAULT_RECORD_COUNT);
    // Create an AvroSource where each block is 16k
    String filename = generateTestFile("tmp.avro", expected, SyncBehavior.SYNC_REGULAR, 1000,
        AvroCoder.of(FixedRecord.class), DataFileConstants.NULL_CODEC);
    File file = new File(filename);

    AvroSource<FixedRecord> source = AvroSource.from(filename).withSchema(FixedRecord.class);
    List<? extends BoundedSource<FixedRecord>> splits =
        source.splitIntoBundles(file.length() / 3, null);
    for (BoundedSource<FixedRecord> subSource : splits) {
      int items = SourceTestUtils.readFromSource(subSource, null).size();
      SourceTestUtils.assertSplitAtFractionFails(subSource, 0, 0.0, null);
      SourceTestUtils.assertSplitAtFractionSucceedsAndConsistent(subSource, 0, 0.7, null);
      SourceTestUtils.assertSplitAtFractionSucceedsAndConsistent(subSource, 1, 0.7, null);
      SourceTestUtils.assertSplitAtFractionSucceedsAndConsistent(subSource, 100, 0.7, null);
      SourceTestUtils.assertSplitAtFractionSucceedsAndConsistent(subSource, 1000, 0.1, null);
      SourceTestUtils.assertSplitAtFractionFails(subSource, 1001, 0.1, null);
      SourceTestUtils.assertSplitAtFractionFails(subSource, DEFAULT_RECORD_COUNT / 3, 0.3, null);
      SourceTestUtils.assertSplitAtFractionFails(subSource, items, 1.0, null);
      SourceTestUtils.assertSplitAtFractionFails(subSource, items, 0.9, null);
      SourceTestUtils.assertSplitAtFractionSucceedsAndConsistent(subSource, items, 0.999, null);
    }
  }

  @Test
  public void testSplitAtFractionExhaustive() throws Exception {
    List<FixedRecord> expected = createFixedRecords(100);
    String filename = generateTestFile("tmp.avro", expected, SyncBehavior.SYNC_REGULAR, 5,
        AvroCoder.of(FixedRecord.class), DataFileConstants.NULL_CODEC);

    AvroSource<FixedRecord> source = AvroSource.from(filename).withSchema(FixedRecord.class);
    SourceTestUtils.assertSplitAtFractionExhaustive(source, null);
  }

  @Test
  public void testSplitsWithSmallBlocks() throws Exception {
    PipelineOptions options = PipelineOptionsFactory.create();
    // Test reading from an object file with many small random-sized blocks.
    List<Bird> expected = createRandomRecords(DEFAULT_RECORD_COUNT);
    String filename = generateTestFile("tmp.avro", expected, SyncBehavior.SYNC_RANDOM,
        100/* max records/block */, AvroCoder.of(Bird.class), DataFileConstants.NULL_CODEC);
    File file = new File(filename);

    // Small minimum bundle size
    AvroSource<Bird> source =
        AvroSource.from(filename).withSchema(Bird.class).withMinBundleSize(100L);

    // Assert that the source produces the expected records
    assertEquals(expected, SourceTestUtils.readFromSource(source, options));

    List<? extends BoundedSource<Bird>> splits;
    int nonEmptySplits;

    // Split with the minimum bundle size
    splits = source.splitIntoBundles(100L, options);
    assertTrue(splits.size() > 2);
    SourceTestUtils.assertSourcesEqualReferenceSource(source, splits, options);
    nonEmptySplits = 0;
    for (Source<Bird> subSource : splits) {
      if (SourceTestUtils.readFromSource(subSource, options).size() > 0) {
        nonEmptySplits += 1;
      }
    }
    assertTrue(nonEmptySplits > 2);

    // Split with larger bundle size
    splits = source.splitIntoBundles(file.length() / 4, options);
    assertTrue(splits.size() > 2);
    SourceTestUtils.assertSourcesEqualReferenceSource(source, splits, options);
    nonEmptySplits = 0;
    for (Source<Bird> subSource : splits) {
      if (SourceTestUtils.readFromSource(subSource, options).size() > 0) {
        nonEmptySplits += 1;
      }
    }
    assertTrue(nonEmptySplits > 2);

    // Split with the file length
    splits = source.splitIntoBundles(file.length(), options);
    assertTrue(splits.size() == 1);
    SourceTestUtils.assertSourcesEqualReferenceSource(source, splits, options);
  }

  @Test
  public void testMultipleFiles() throws Exception {
    String baseName = "tmp-";
    List<Bird> expected = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      List<Bird> contents = createRandomRecords(DEFAULT_RECORD_COUNT / 10);
      expected.addAll(contents);
      generateTestFile(baseName + i, contents, SyncBehavior.SYNC_DEFAULT, 0,
          AvroCoder.of(Bird.class), DataFileConstants.NULL_CODEC);
    }

    AvroSource<Bird> source =
        AvroSource.from(Paths.get(tmpFolder.getRoot().toString(), baseName + "*").toString())
            .withSchema(Bird.class);
    List<Bird> actual = SourceTestUtils.readFromSource(source, null);
    assertThat(actual, containsInAnyOrder(expected.toArray()));
  }

  @Test
  public void testCreationWithSchema() throws Exception {
    List<Bird> expected = createRandomRecords(100);
    String filename = generateTestFile("tmp.avro", expected, SyncBehavior.SYNC_DEFAULT, 0,
        AvroCoder.of(Bird.class), DataFileConstants.NULL_CODEC);

    // Create a source with a schema object
    Schema schema = ReflectData.get().getSchema(Bird.class);
    AvroSource<GenericRecord> source = AvroSource.from(filename).withSchema(schema);
    List<GenericRecord> records = SourceTestUtils.readFromSource(source, null);
    assertEqualsWithGeneric(expected, records);

    // Create a source with a JSON schema
    String schemaString = ReflectData.get().getSchema(Bird.class).toString();
    source = AvroSource.from(filename).withSchema(schemaString);
    records = SourceTestUtils.readFromSource(source, null);
    assertEqualsWithGeneric(expected, records);

    // Create a source with no schema
    source = AvroSource.from(filename);
    records = SourceTestUtils.readFromSource(source, null);
    assertEqualsWithGeneric(expected, records);
  }

  private void assertEqualsWithGeneric(List<Bird> expected, List<GenericRecord> actual) {
    assertEquals(expected.size(), actual.size());
    for (int i = 0; i < expected.size(); i++) {
      Bird fixed = expected.get(i);
      GenericRecord generic = actual.get(i);
      assertEquals(fixed.number, generic.get("number"));
      assertEquals(fixed.quality, generic.get("quality").toString()); // From Avro util.Utf8
      assertEquals(fixed.quantity, generic.get("quantity"));
      assertEquals(fixed.species, generic.get("species").toString());
    }
  }

  /**
   * Creates a haystack byte array of the give size with a needle that starts at the given position.
   */
  private byte[] createHaystack(byte[] needle, int position, int size) {
    byte[] haystack = new byte[size];
    for (int i = position, j = 0; i < size && j < needle.length; i++, j++) {
      haystack[i] = needle[j];
    }
    return haystack;
  }

  /**
   * Asserts that advancePastNextSyncMarker advances an input stream past a sync marker and
   * correctly returns the number of bytes consumed from the stream.
   * Creates a haystack of size bytes and places a 16-byte sync marker at the position specified.
   */
  private void testAdvancePastNextSyncMarkerAt(int position, int size) throws IOException {
    byte sentinel = (byte) 0xFF;
    byte[] marker = new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 0, 1, 2, 3, 4, 5, 6};
    byte[] haystack = createHaystack(marker, position, size);
    PushbackInputStream stream =
        new PushbackInputStream(new ByteArrayInputStream(haystack), marker.length);
    if (position + marker.length < size) {
      haystack[position + marker.length] = sentinel;
      assertEquals(position + marker.length, AvroReader.advancePastNextSyncMarker(stream, marker));
      assertEquals(sentinel, (byte) stream.read());
    } else {
      assertEquals(size, AvroReader.advancePastNextSyncMarker(stream, marker));
      assertEquals(-1, stream.read());
    }
  }

  @Test
  public void testAdvancePastNextSyncMarker() throws IOException {
    // Test placing the sync marker at different locations at the start and in the middle of the
    // buffer.
    for (int i = 0; i <= 16; i++) {
      testAdvancePastNextSyncMarkerAt(i, 1000);
      testAdvancePastNextSyncMarkerAt(160 + i, 1000);
    }
    // Test placing the sync marker at the end of the buffer.
    testAdvancePastNextSyncMarkerAt(983, 1000);
    // Test placing the sync marker so that it begins at the end of the buffer.
    testAdvancePastNextSyncMarkerAt(984, 1000);
    testAdvancePastNextSyncMarkerAt(985, 1000);
    testAdvancePastNextSyncMarkerAt(999, 1000);
    // Test with no sync marker.
    testAdvancePastNextSyncMarkerAt(1000, 1000);
  }

  // Tests for Seeker.
  @Test
  public void testSeekerFind() {
    byte[] marker = {0, 1, 2, 3};
    byte[] buffer;
    Seeker s;
    s = new Seeker(marker);

    buffer = new byte[] {0, 1, 2, 3, 4, 5, 6, 7};
    assertEquals(3, s.find(buffer, buffer.length));

    buffer = new byte[] {0, 0, 0, 0, 0, 1, 2, 3};
    assertEquals(7, s.find(buffer, buffer.length));

    buffer = new byte[] {0, 1, 2, 0, 0, 1, 2, 3};
    assertEquals(7, s.find(buffer, buffer.length));

    buffer = new byte[] {0, 1, 2, 3};
    assertEquals(3, s.find(buffer, buffer.length));
  }

  @Test
  public void testSeekerFindResume() {
    byte[] marker = {0, 1, 2, 3};
    byte[] buffer;
    Seeker s;
    s = new Seeker(marker);

    buffer = new byte[] {0, 0, 0, 0, 0, 0, 0, 0};
    assertEquals(-1, s.find(buffer, buffer.length));
    buffer = new byte[] {1, 2, 3, 0, 0, 0, 0, 0};
    assertEquals(2, s.find(buffer, buffer.length));

    buffer = new byte[] {0, 0, 0, 0, 0, 0, 1, 2};
    assertEquals(-1, s.find(buffer, buffer.length));
    buffer = new byte[] {3, 0, 1, 2, 3, 0, 1, 2};
    assertEquals(0, s.find(buffer, buffer.length));

    buffer = new byte[] {0};
    assertEquals(-1, s.find(buffer, buffer.length));
    buffer = new byte[] {1};
    assertEquals(-1, s.find(buffer, buffer.length));
    buffer = new byte[] {2};
    assertEquals(-1, s.find(buffer, buffer.length));
    buffer = new byte[] {3};
    assertEquals(0, s.find(buffer, buffer.length));
  }

  @Test
  public void testSeekerUsesBufferLength() {
    byte[] marker = {0, 0, 1};
    byte[] buffer;
    Seeker s;
    s = new Seeker(marker);

    buffer = new byte[] {0, 0, 0, 1};
    assertEquals(-1, s.find(buffer, 3));

    s = new Seeker(marker);
    buffer = new byte[] {0, 0};
    assertEquals(-1, s.find(buffer, 1));
    buffer = new byte[] {1, 0};
    assertEquals(-1, s.find(buffer, 1));

    s = new Seeker(marker);
    buffer = new byte[] {0, 2};
    assertEquals(-1, s.find(buffer, 1));
    buffer = new byte[] {0, 2};
    assertEquals(-1, s.find(buffer, 1));
    buffer = new byte[] {1, 2};
    assertEquals(0, s.find(buffer, 1));
  }


  @Test
  public void testSeekerFindPartial() {
    byte[] marker = {0, 0, 1};
    byte[] buffer;
    Seeker s;
    s = new Seeker(marker);

    buffer = new byte[] {0, 0, 0, 1};
    assertEquals(3, s.find(buffer, buffer.length));

    marker = new byte[] {1, 1, 1, 2};
    s = new Seeker(marker);

    buffer = new byte[] {1, 1, 1, 1, 1};
    assertEquals(-1, s.find(buffer, buffer.length));
    buffer = new byte[] {1, 1, 2};
    assertEquals(2, s.find(buffer, buffer.length));

    buffer = new byte[] {1, 1, 1, 1, 1};
    assertEquals(-1, s.find(buffer, buffer.length));
    buffer = new byte[] {2, 1, 1, 1, 2};
    assertEquals(0, s.find(buffer, buffer.length));
  }

  @Test
  public void testSeekerFindAllLocations() {
    byte[] marker = {1, 1, 2};
    byte[] allOnes = new byte[] {1, 1, 1, 1};
    byte[] findIn = new byte[] {1, 1, 1, 1};
    Seeker s = new Seeker(marker);

    for (int i = 0; i < findIn.length; i++) {
      assertEquals(-1, s.find(allOnes, allOnes.length));
      findIn[i] = 2;
      assertEquals(i, s.find(findIn, findIn.length));
      findIn[i] = 1;
    }
  }

  /**
   * Class that will encode to a fixed size: 16 bytes.
   *
   * <p>Each object has a 15-byte array. Avro encodes an object of this type as
   * a byte array, so each encoded object will consist of 1 byte that encodes the
   * length of the array, followed by 15 bytes.
   */
  @DefaultCoder(AvroCoder.class)
  public static class FixedRecord {
    private byte[] value = new byte[15];

    public FixedRecord() {
      this(0);
    }

    public FixedRecord(int i) {
      value[0] = (byte) i;
      value[1] = (byte) (i >> 8);
      value[2] = (byte) (i >> 16);
      value[3] = (byte) (i >> 24);
    }

    public int asInt() {
      return value[0] | (value[1] << 8) | (value[2] << 16) | (value[3] << 24);
    }

    @Override
    public boolean equals(Object o) {
      if (o instanceof FixedRecord) {
        FixedRecord other = (FixedRecord) o;
        return this.asInt() == other.asInt();
      }
      return false;
    }

    @Override
    public int hashCode() {
      return toString().hashCode();
    }

    @Override
    public String toString() {
      return Integer.toString(this.asInt());
    }
  }

  /**
   * Create a list of count 16-byte records.
   */
  private static List<FixedRecord> createFixedRecords(int count) {
    List<FixedRecord> records = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      records.add(new FixedRecord(i));
    }
    return records;
  }

  /**
   * Class used as the record type in tests.
   */
  @DefaultCoder(AvroCoder.class)
  public static class Bird {
    private long number;
    private String species;
    private String quality;
    private long quantity;

    public String getQuality() {
      return this.quality;
    }

    public String getSpecies() {
      return this.species;
    }

    public long getQuantity() {
      return quantity;
    }

    public long getNumber() {
      return number;
    }

    @Override
    public String toString() {
      return quantity + " " + quality + " " + species;
    }

    @Override
    public boolean equals(Object obj) {
      if (obj instanceof Bird) {
        Bird other = (Bird) obj;
        return species.equals(other.species) && quality.equals(other.quality)
            && quantity == other.quantity && number == other.number;
      }
      return false;
    }

    @Override
    public int hashCode() {
      return toString().hashCode();
    }
  }

  /**
   * Create a list of n random records.
   */
  private static List<Bird> createRandomRecords(long n) {
    String[] qualities = {
        "miserable", "forelorn", "fidgity", "squirrelly", "fanciful", "chipper", "lazy"};
    String[] species = {"pigeons", "owls", "gulls", "hawks", "robins", "jays"};
    Random random = new Random(0);

    List<Bird> records = new ArrayList<>();
    for (long i = 0; i < n; i++) {
      Bird bird = new Bird();
      bird.quality = qualities[random.nextInt(qualities.length)];
      bird.species = species[random.nextInt(species.length)];
      bird.number = i;
      bird.quantity = random.nextLong();
      records.add(bird);
    }
    return records;
  }
}

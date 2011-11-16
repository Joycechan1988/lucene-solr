package org.apache.lucene.index.codecs;

/**
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

import java.io.IOException;

import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.FieldReaderException;
import org.apache.lucene.index.IndexFileNames;
import org.apache.lucene.index.IndexFormatTooNewException;
import org.apache.lucene.index.IndexFormatTooOldException;
import org.apache.lucene.index.SegmentInfo;
import org.apache.lucene.index.StoredFieldVisitor;
import org.apache.lucene.store.AlreadyClosedException;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.util.IOUtils;

import java.io.Closeable;
import java.nio.charset.Charset;
import java.util.Set;

/**
 * Class responsible for access to stored document fields.
 * <p/>
 * It uses &lt;segment&gt;.fdt and &lt;segment&gt;.fdx; files.
 * 
 * @lucene.internal
 */
public final class DefaultStoredFieldsReader extends StoredFieldsReader implements Cloneable, Closeable {
  private final static int FORMAT_SIZE = 4;

  private final FieldInfos fieldInfos;
  private final IndexInput fieldsStream;
  private final IndexInput indexStream;
  private int numTotalDocs;
  private int size;
  private boolean closed;
  private final int format;

  // The docID offset where our docs begin in the index
  // file.  This will be 0 if we have our own private file.
  private int docStoreOffset;

  /** Returns a cloned FieldsReader that shares open
   *  IndexInputs with the original one.  It is the caller's
   *  job not to close the original FieldsReader until all
   *  clones are called (eg, currently SegmentReader manages
   *  this logic). */
  @Override
  public DefaultStoredFieldsReader clone() {
    ensureOpen();
    return new DefaultStoredFieldsReader(fieldInfos, numTotalDocs, size, format, docStoreOffset, (IndexInput)fieldsStream.clone(), (IndexInput)indexStream.clone());
  }

  /** Verifies that the code version which wrote the segment is supported. */
  public static void checkCodeVersion(Directory dir, String segment) throws IOException {
    final String indexStreamFN = IndexFileNames.segmentFileName(segment, "", DefaultStoredFieldsWriter.FIELDS_INDEX_EXTENSION);
    IndexInput idxStream = dir.openInput(indexStreamFN, IOContext.DEFAULT);
    
    try {
      int format = idxStream.readInt();
      if (format < DefaultStoredFieldsWriter.FORMAT_MINIMUM)
        throw new IndexFormatTooOldException(idxStream, format, DefaultStoredFieldsWriter.FORMAT_MINIMUM, DefaultStoredFieldsWriter.FORMAT_CURRENT);
      if (format > DefaultStoredFieldsWriter.FORMAT_CURRENT)
        throw new IndexFormatTooNewException(idxStream, format, DefaultStoredFieldsWriter.FORMAT_MINIMUM, DefaultStoredFieldsWriter.FORMAT_CURRENT);
    } finally {
      idxStream.close();
    }
  
  }
  
  // Used only by clone
  private DefaultStoredFieldsReader(FieldInfos fieldInfos, int numTotalDocs, int size, int format, int docStoreOffset,
                       IndexInput fieldsStream, IndexInput indexStream) {
    this.fieldInfos = fieldInfos;
    this.numTotalDocs = numTotalDocs;
    this.size = size;
    this.format = format;
    this.docStoreOffset = docStoreOffset;
    this.fieldsStream = fieldsStream;
    this.indexStream = indexStream;
  }

  public DefaultStoredFieldsReader(Directory d, SegmentInfo si, FieldInfos fn, IOContext context) throws IOException {
    final String segment = si.getDocStoreSegment();
    final int docStoreOffset = si.getDocStoreOffset();
    final int size = si.docCount;
    boolean success = false;
    fieldInfos = fn;
    try {
      fieldsStream = d.openInput(IndexFileNames.segmentFileName(segment, "", DefaultStoredFieldsWriter.FIELDS_EXTENSION), context);
      final String indexStreamFN = IndexFileNames.segmentFileName(segment, "", DefaultStoredFieldsWriter.FIELDS_INDEX_EXTENSION);
      indexStream = d.openInput(indexStreamFN, context);
      
      format = indexStream.readInt();

      if (format < DefaultStoredFieldsWriter.FORMAT_MINIMUM)
        throw new IndexFormatTooOldException(indexStream, format, DefaultStoredFieldsWriter.FORMAT_MINIMUM, DefaultStoredFieldsWriter.FORMAT_CURRENT);
      if (format > DefaultStoredFieldsWriter.FORMAT_CURRENT)
        throw new IndexFormatTooNewException(indexStream, format, DefaultStoredFieldsWriter.FORMAT_MINIMUM, DefaultStoredFieldsWriter.FORMAT_CURRENT);

      final long indexSize = indexStream.length() - FORMAT_SIZE;
      
      if (docStoreOffset != -1) {
        // We read only a slice out of this shared fields file
        this.docStoreOffset = docStoreOffset;
        this.size = size;

        // Verify the file is long enough to hold all of our
        // docs
        assert ((int) (indexSize / 8)) >= size + this.docStoreOffset: "indexSize=" + indexSize + " size=" + size + " docStoreOffset=" + docStoreOffset;
      } else {
        this.docStoreOffset = 0;
        this.size = (int) (indexSize >> 3);
        // Verify two sources of "maxDoc" agree:
        if (this.size != si.docCount) {
          throw new CorruptIndexException("doc counts differ for segment " + segment + ": fieldsReader shows " + this.size + " but segmentInfo shows " + si.docCount);
        }
      }
      numTotalDocs = (int) (indexSize >> 3);
      success = true;
    } finally {
      // With lock-less commits, it's entirely possible (and
      // fine) to hit a FileNotFound exception above. In
      // this case, we want to explicitly close any subset
      // of things that were opened so that we don't have to
      // wait for a GC to do so.
      if (!success) {
        close();
      }
    }
  }

  /**
   * @throws AlreadyClosedException if this FieldsReader is closed
   */
  private void ensureOpen() throws AlreadyClosedException {
    if (closed) {
      throw new AlreadyClosedException("this FieldsReader is closed");
    }
  }

  /**
   * Closes the underlying {@link org.apache.lucene.store.IndexInput} streams.
   * This means that the Fields values will not be accessible.
   *
   * @throws IOException
   */
  public final void close() throws IOException {
    if (!closed) {
      IOUtils.close(fieldsStream, indexStream);
      closed = true;
    }
  }

  public final int size() {
    return size;
  }

  private void seekIndex(int docID) throws IOException {
    indexStream.seek(FORMAT_SIZE + (docID + docStoreOffset) * 8L);
  }

  public final void visitDocument(int n, StoredFieldVisitor visitor) throws CorruptIndexException, IOException {
    seekIndex(n);
    fieldsStream.seek(indexStream.readLong());

    final int numFields = fieldsStream.readVInt();
    for (int fieldIDX = 0; fieldIDX < numFields; fieldIDX++) {
      int fieldNumber = fieldsStream.readVInt();
      FieldInfo fieldInfo = fieldInfos.fieldInfo(fieldNumber);
      
      int bits = fieldsStream.readByte() & 0xFF;
      assert bits <= (DefaultStoredFieldsWriter.FIELD_IS_NUMERIC_MASK | DefaultStoredFieldsWriter.FIELD_IS_BINARY): "bits=" + Integer.toHexString(bits);

      switch(visitor.needsField(fieldInfo)) {
        case YES:
          readField(visitor, fieldInfo, bits);
          break;
        case NO: 
          skipField(bits);
          break;
        case STOP: 
          return;
      }
    }
  }
  
  static final Charset UTF8 = Charset.forName("UTF-8");

  private void readField(StoredFieldVisitor visitor, FieldInfo info, int bits) throws IOException {
    final int numeric = bits & DefaultStoredFieldsWriter.FIELD_IS_NUMERIC_MASK;
    if (numeric != 0) {
      switch(numeric) {
        case DefaultStoredFieldsWriter.FIELD_IS_NUMERIC_INT:
          visitor.intField(info, fieldsStream.readInt());
          return;
        case DefaultStoredFieldsWriter.FIELD_IS_NUMERIC_LONG:
          visitor.longField(info, fieldsStream.readLong());
          return;
        case DefaultStoredFieldsWriter.FIELD_IS_NUMERIC_FLOAT:
          visitor.floatField(info, Float.intBitsToFloat(fieldsStream.readInt()));
          return;
        case DefaultStoredFieldsWriter.FIELD_IS_NUMERIC_DOUBLE:
          visitor.doubleField(info, Double.longBitsToDouble(fieldsStream.readLong()));
          return;
        default:
          throw new FieldReaderException("Invalid numeric type: " + Integer.toHexString(numeric));
      }
    } else { 
      final int length = fieldsStream.readVInt();
      byte bytes[] = new byte[length];
      fieldsStream.readBytes(bytes, 0, length);
      if ((bits & DefaultStoredFieldsWriter.FIELD_IS_BINARY) != 0) {
        visitor.binaryField(info, bytes, 0, bytes.length);
      } else {
        visitor.stringField(info, new String(bytes, 0, bytes.length, UTF8));
      }
    }
  }
  
  private void skipField(int bits) throws IOException {
    final int numeric = bits & DefaultStoredFieldsWriter.FIELD_IS_NUMERIC_MASK;
    if (numeric != 0) {
      switch(numeric) {
        case DefaultStoredFieldsWriter.FIELD_IS_NUMERIC_INT:
        case DefaultStoredFieldsWriter.FIELD_IS_NUMERIC_FLOAT:
          fieldsStream.readInt();
          return;
        case DefaultStoredFieldsWriter.FIELD_IS_NUMERIC_LONG:
        case DefaultStoredFieldsWriter.FIELD_IS_NUMERIC_DOUBLE:
          fieldsStream.readLong();
          return;
        default: 
          throw new FieldReaderException("Invalid numeric type: " + Integer.toHexString(numeric));
      }
    } else {
      final int length = fieldsStream.readVInt();
      fieldsStream.seek(fieldsStream.getFilePointer() + length);
    }
  }

  /** Returns the length in bytes of each raw document in a
   *  contiguous range of length numDocs starting with
   *  startDocID.  Returns the IndexInput (the fieldStream),
   *  already seeked to the starting point for startDocID.*/
  public final IndexInput rawDocs(int[] lengths, int startDocID, int numDocs) throws IOException {
    seekIndex(startDocID);
    long startOffset = indexStream.readLong();
    long lastOffset = startOffset;
    int count = 0;
    while (count < numDocs) {
      final long offset;
      final int docID = docStoreOffset + startDocID + count + 1;
      assert docID <= numTotalDocs;
      if (docID < numTotalDocs) 
        offset = indexStream.readLong();
      else
        offset = fieldsStream.length();
      lengths[count++] = (int) (offset-lastOffset);
      lastOffset = offset;
    }

    fieldsStream.seek(startOffset);

    return fieldsStream;
  }
  
  // TODO: split into PreFlexFieldsReader so it can handle this shared docstore crap?
  // only preflex segments refer to these?
  public static void files(Directory dir, SegmentInfo info, Set<String> files) throws IOException {
    if (info.getDocStoreOffset() != -1) {
      assert info.getDocStoreSegment() != null;
      if (!info.getDocStoreIsCompoundFile()) {
        files.add(IndexFileNames.segmentFileName(info.getDocStoreSegment(), "", DefaultStoredFieldsWriter.FIELDS_INDEX_EXTENSION));
        files.add(IndexFileNames.segmentFileName(info.getDocStoreSegment(), "", DefaultStoredFieldsWriter.FIELDS_EXTENSION));
      }
    } else {
      files.add(IndexFileNames.segmentFileName(info.name, "", DefaultStoredFieldsWriter.FIELDS_INDEX_EXTENSION));
      files.add(IndexFileNames.segmentFileName(info.name, "", DefaultStoredFieldsWriter.FIELDS_EXTENSION));
    }
  }
}

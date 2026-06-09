package com.example.batch.common.storage;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * 对象存储写入用的计数输入流：统一保证声明长度精确，并可限制实际读取上限。
 *
 * <p>本类不接管底层流生命周期，close 只透传给父类；调用方仍按 {@link BatchObjectStore#put} 契约负责关闭原始流。
 */
final class ExactSizeInputStream extends FilterInputStream {

  private final String backend;
  private final String bucket;
  private final String key;
  private final long expectedSize;
  private final long maxBytes;
  private long readBytes;
  private boolean endReached;

  private ExactSizeInputStream(
      InputStream delegate,
      String backend,
      String bucket,
      String key,
      long expectedSize,
      long maxBytes) {
    super(delegate);
    this.backend = backend;
    this.bucket = bucket;
    this.key = key;
    this.expectedSize = expectedSize;
    this.maxBytes = maxBytes;
  }

  static ExactSizeInputStream exact(
      InputStream delegate, String backend, String bucket, String key, long expectedSize) {
    return new ExactSizeInputStream(delegate, backend, bucket, key, expectedSize, -1);
  }

  static ExactSizeInputStream exactAndBounded(
      InputStream delegate,
      String backend,
      String bucket,
      String key,
      long expectedSize,
      long maxBytes) {
    return new ExactSizeInputStream(delegate, backend, bucket, key, expectedSize, maxBytes);
  }

  @Override
  public int read() throws IOException {
    int value = super.read();
    if (value < 0) {
      markEnd();
      return value;
    }
    afterRead(1);
    return value;
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    int count = super.read(b, off, len);
    if (count < 0) {
      markEnd();
      return count;
    }
    afterRead(count);
    return count;
  }

  @Override
  public byte[] readNBytes(int len) throws IOException {
    if (len < 0) {
      throw new IllegalArgumentException("len < 0");
    }
    byte[] buffer = new byte[len];
    int total = 0;
    while (total < len) {
      int count = read(buffer, total, len - total);
      if (count < 0) {
        break;
      }
      total += count;
    }
    return total == len ? buffer : Arrays.copyOf(buffer, total);
  }

  @Override
  public long transferTo(OutputStream out) throws IOException {
    byte[] buffer = new byte[8192];
    long total = 0L;
    while (true) {
      int read = read(buffer);
      if (read < 0) {
        return total;
      }
      out.write(buffer, 0, read);
      total += read;
    }
  }

  void verifyFullyRead() {
    if (expectedSize >= 0 && readBytes != expectedSize) {
      throw lengthMismatch(expectedSize, readBytes);
    }
  }

  void verifyEndOfStream() {
    try {
      int value = read();
      if (value >= 0) {
        throw lengthMismatch(expectedSize, readBytes);
      }
    } catch (IOException exception) {
      throw new ObjectStoreException(
          "%s object store put failed while checking exact length: bucket=%s, key=%s"
              .formatted(backend, bucket, key),
          exception);
    }
  }

  long readBytes() {
    return readBytes;
  }

  private void afterRead(int count) {
    if (count <= 0) {
      return;
    }
    readBytes += count;
    if (maxBytes >= 0 && readBytes > maxBytes) {
      throw new ObjectStoreException(
          "%s object store put exceeds read limit: bucket=%s, key=%s, limit=%d, actual>%d"
              .formatted(backend, bucket, key, maxBytes, maxBytes));
    }
    if (expectedSize >= 0 && readBytes > expectedSize) {
      throw lengthMismatch(expectedSize, readBytes);
    }
  }

  private void markEnd() {
    if (endReached) {
      return;
    }
    endReached = true;
    verifyFullyRead();
  }

  private ObjectStoreException lengthMismatch(long expected, long actual) {
    return new ObjectStoreException(
        "%s object store put length mismatch: bucket=%s, key=%s, expected=%d, actual=%d"
            .formatted(backend, bucket, key, expected, actual));
  }
}

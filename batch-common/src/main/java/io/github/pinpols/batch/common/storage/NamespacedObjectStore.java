package io.github.pinpols.batch.common.storage;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.io.InputStream;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/** 添加部署独占的物理前缀，并保持调用方使用的逻辑对象键稳定。 */
public final class NamespacedObjectStore implements BatchObjectStore {

  private static final Pattern VALID_NAMESPACE = Pattern.compile("[a-z0-9][a-z0-9_-]{0,47}");

  private final BatchObjectStore delegate;
  private final String prefix;

  public NamespacedObjectStore(BatchObjectStore delegate, String namespace) {
    if (EmptyChecks.isNull(namespace)
        || !VALID_NAMESPACE.matcher(namespace.trim()).matches()) {
      throw new IllegalArgumentException("object storage namespace is invalid");
    }
    this.delegate = delegate;
    this.prefix = namespace.trim() + "/";
  }

  @Override
  public void put(String bucket, String key, InputStream in, long size, String contentType) {
    delegate.put(bucket, physicalKey(key), in, size, contentType);
  }

  @Override
  public void copy(String bucket, String srcKey, String dstKey) {
    delegate.copy(bucket, physicalKey(srcKey), physicalKey(dstKey));
  }

  @Override
  public void delete(String bucket, String key) {
    delegate.delete(bucket, physicalKey(key));
  }

  @Override
  public void deleteMany(String bucket, Collection<String> keys) {
    if (EmptyChecks.isNull(keys)) {
      return;
    }
    delegate.deleteMany(
        bucket,
        keys.stream()
            .filter(key -> EmptyChecks.isNotNull(key) && !EmptyChecks.isBlank(key))
            .map(this::physicalKey)
            .toList());
  }

  @Override
  public InputStream get(String bucket, String key) {
    return delegate.get(bucket, physicalKey(key));
  }

  @Override
  public InputStream getFrom(String bucket, String key, long offset) {
    return delegate.getFrom(bucket, physicalKey(key), offset);
  }

  @Override
  public boolean supportsRangeRead() {
    return delegate.supportsRangeRead();
  }

  @Override
  public long statSize(String bucket, String key) {
    return delegate.statSize(bucket, physicalKey(key));
  }

  @Override
  public boolean exists(String bucket, String key) {
    return delegate.exists(bucket, physicalKey(key));
  }

  @Override
  public ObjectListing list(String bucket, String keyPrefix, String afterMarker, int maxKeys) {
    ObjectListing listing = delegate.list(
        bucket,
        EmptyChecks.isNull(keyPrefix) ? prefix : physicalKey(keyPrefix),
        physicalMarker(afterMarker),
        maxKeys);
    List<ObjectSummary> objects = listing.objects().stream()
        .filter(summary -> {
          String physicalKey = summary.key();
          return physicalKey.startsWith(prefix);
        })
        .map(summary -> {
          String logicalObjectKey = logicalKey(summary.key());
          return new ObjectSummary(
              logicalObjectKey, summary.size(), summary.lastModified(), summary.etag());
        })
        .toList();
    String physicalNextMarker = listing.nextMarker();
    String logicalNextMarker = logicalMarker(physicalNextMarker);
    return new ObjectListing(objects, logicalNextMarker);
  }

  @Override
  public String presign(String bucket, String key, Duration ttl) {
    return delegate.presign(bucket, physicalKey(key), ttl);
  }

  @Override
  public boolean supportsPresignPut() {
    return delegate.supportsPresignPut();
  }

  @Override
  public String presignPut(String bucket, String key, Duration ttl, String contentType) {
    return delegate.presignPut(bucket, physicalKey(key), ttl, contentType);
  }

  private String physicalKey(String key) {
    if (EmptyChecks.isNull(key)) {
      return key;
    }
    return prefix + key;
  }

  private String physicalMarker(String marker) {
    return EmptyChecks.isNull(marker) || EmptyChecks.isBlank(marker) ? marker : physicalKey(marker);
  }

  private String logicalMarker(String marker) {
    return EmptyChecks.isNull(marker) || EmptyChecks.isBlank(marker) ? marker : logicalKey(marker);
  }

  private String logicalKey(String key) {
    return key.startsWith(prefix) ? key.substring(prefix.length()) : key;
  }
}

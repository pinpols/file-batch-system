package com.example.batch.orchestrator.infrastructure.file;

import com.example.batch.common.config.S3StorageProperties;
import com.example.batch.common.storage.BatchObjectStore;
import com.example.batch.common.storage.ObjectListing;
import com.example.batch.common.storage.ObjectSummary;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class S3GovernanceStorage {

  private final S3StorageProperties properties;
  private final BatchObjectStore objectStore;

  /** 治理任务只做对象清点和清理，不在这里承载业务编排。 */
  public List<StorageObjectView> listObjects(
      String prefix, int limit, boolean includeTemporaryObjects) {
    String bucket = properties.getBucket();
    String effectivePrefix = prefix == null ? "" : prefix;
    List<StorageObjectView> objects = new ArrayList<>();
    try {
      String marker = null;
      // 循环翻页累计，直到取满 limit 个（过滤临时对象后的）结果或末页（nextMarker==null）。
      while (objects.size() < limit) {
        ObjectListing listing = objectStore.list(bucket, effectivePrefix, marker, limit);
        for (ObjectSummary summary : listing.objects()) {
          if (objects.size() >= limit) {
            break;
          }
          String objectName = summary.key();
          if (!includeTemporaryObjects
              && (objectName.endsWith(".part") || objectName.startsWith("tmp/"))) {
            continue;
          }
          objects.add(
              new StorageObjectView(
                  bucket, objectName, summary.size(), summary.etag(), summary.lastModified()));
        }
        marker = listing.nextMarker();
        if (marker == null) {
          break;
        }
      }
      return objects;
    } catch (Exception exception) {
      throw new IllegalStateException("failed to list objectStore objects", exception);
    }
  }

  public void removeObject(String objectName) {
    try {
      objectStore.delete(properties.getBucket(), objectName);
    } catch (Exception exception) {
      throw new IllegalStateException("failed to remove object: " + objectName, exception);
    }
  }

  public String createPresignedDownloadUrl(String bucket, String objectName, int expirySeconds) {
    try {
      String targetBucket = bucket == null || bucket.isBlank() ? properties.getBucket() : bucket;
      return objectStore.presign(
          targetBucket, objectName, Duration.ofSeconds(Math.max(60, expirySeconds)));
    } catch (Exception exception) {
      throw new IllegalStateException(
          "failed to create presigned url for object: " + objectName, exception);
    }
  }

  public record StorageObjectView(
      String bucket, String objectName, long size, String etag, Instant lastModified) {}
}

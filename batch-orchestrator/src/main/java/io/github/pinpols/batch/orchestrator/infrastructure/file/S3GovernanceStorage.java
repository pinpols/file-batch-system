package io.github.pinpols.batch.orchestrator.infrastructure.file;

import io.github.pinpols.batch.common.config.S3StorageProperties;
import io.github.pinpols.batch.common.storage.BatchObjectStore;
import io.github.pinpols.batch.common.storage.ObjectListing;
import io.github.pinpols.batch.common.storage.ObjectNotFoundException;
import io.github.pinpols.batch.common.storage.ObjectSummary;
import io.github.pinpols.batch.common.storage.ingress.ImportIngressOwnershipIdentity;
import io.github.pinpols.batch.common.storage.ingress.ImportIngressOwnershipRepository;
import io.github.pinpols.batch.common.storage.ingress.ImportIngressOwnershipRepository.Scope;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.mapper.FileGovernanceReconcileCursorMapper;
import io.github.pinpols.batch.orchestrator.mapper.FileGovernanceReconcileCursorMapper.Advance;
import io.github.pinpols.batch.orchestrator.mapper.FileGovernanceReconcileCursorMapper.Cursor;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class S3GovernanceStorage {

  private final S3StorageProperties properties;
  private final BatchObjectStore objectStore;
  private final JdbcTemplate platformJdbc;
  private final Environment environment;
  private final FileGovernanceReconcileCursorMapper cursorMapper;

  /** 治理任务只做对象清点和清理，不在这里承载业务编排。 */
  public ReconcilePage listReconcilePage(
      String prefix, int limit, boolean includeTemporaryObjects) {
    String bucket = properties.getBucket();
    String effectivePrefix = prefix == null ? "" : prefix;
    String storageId = ImportIngressOwnershipIdentity.storageId(
        environment, properties, JsonUtils.newDefaultMapper());
    cursorMapper.insertIfAbsent(storageId, effectivePrefix);
    Cursor cursor = cursorMapper.select(storageId, effectivePrefix);
    List<Scope> protectedScopes =
        new ImportIngressOwnershipRepository(platformJdbc).listProtectedScopes(storageId);
    List<StorageObjectView> objects = new ArrayList<>();
    try {
      // 批大小约束原始对象数；跳过受保护对象也消费本页预算，避免一次对账无限翻页。
      boolean revisiting = "REVISIT".equals(cursor.nextLane());
      String marker = revisiting ? cursor.revisitMarker() : cursor.nextMarker();
      ObjectListing listing = objectStore.list(bucket, effectivePrefix, marker, limit);
      for (ObjectSummary summary : listing.objects()) {
        String objectName = summary.key();
        boolean temporaryObjectShouldBeExcluded = !includeTemporaryObjects
            && (objectName.endsWith(".part") || objectName.startsWith("tmp/"));
        boolean ingressObject =
            protectedScopes.stream().anyMatch(scope -> scope.protects(bucket, objectName));
        if (!temporaryObjectShouldBeExcluded && !ingressObject) {
          objects.add(new StorageObjectView(
              bucket, objectName, summary.size(), summary.etag(), summary.lastModified()));
        }
      }
      Advance next = nextProgress(storageId, effectivePrefix, cursor, listing.nextMarker());
      return new ReconcilePage(next, objects);
    } catch (Exception exception) {
      throw new IllegalStateException("failed to list objectStore objects", exception);
    }
  }

  /** 仅在当前页全部处理成功后推进；过期执行者不得覆盖较新的扫描进度。 */
  public void completeReconcilePage(ReconcilePage page) {
    cursorMapper.advance(page.progress());
  }

  private Advance nextProgress(String storageId, String prefix, Cursor cursor, String marker) {
    String nextMarker = cursor.nextMarker();
    String revisitMarker = cursor.revisitMarker();
    long budget = cursor.revisitBudget();
    long remaining = cursor.revisitRemaining();
    String lane;
    if ("REVISIT".equals(cursor.nextLane())) {
      if (marker == null || remaining <= 1) {
        revisitMarker = null;
        budget = budget > Long.MAX_VALUE / 2 ? Long.MAX_VALUE : budget * 2;
        remaining = budget;
      } else {
        revisitMarker = marker;
        remaining--;
      }
      lane = "FORWARD";
    } else {
      nextMarker = marker;
      lane = marker == null ? "FORWARD" : "REVISIT";
      if (marker == null) {
        revisitMarker = null;
        budget = 1;
        remaining = 1;
      }
    }
    return new Advance(
        storageId, prefix, cursor.version(), nextMarker, revisitMarker, budget, remaining, lane);
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
      int minimumExpirySeconds = Math.max(60, expirySeconds);
      Duration expiry = Duration.ofSeconds(minimumExpirySeconds);
      return objectStore.presign(targetBucket, objectName, expiry);
    } catch (Exception exception) {
      throw new IllegalStateException(
          "failed to create presigned url for object: " + objectName, exception);
    }
  }

  public long objectSize(String bucket, String objectName) {
    try {
      String targetBucket = bucket == null || bucket.isBlank() ? properties.getBucket() : bucket;
      return objectStore.statSize(targetBucket, objectName);
    } catch (ObjectNotFoundException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new IllegalStateException("failed to stat object: " + objectName, exception);
    }
  }

  public boolean objectExists(String bucket, String objectName) {
    try {
      String targetBucket = bucket == null || bucket.isBlank() ? properties.getBucket() : bucket;
      return objectStore.exists(targetBucket, objectName);
    } catch (Exception exception) {
      throw new IllegalStateException("failed to check object: " + objectName, exception);
    }
  }

  public String defaultBucket() {
    return properties.getBucket();
  }

  public record StorageObjectView(
      String bucket, String objectName, long size, String etag, Instant lastModified) {}

  public record ReconcilePage(Advance progress, List<StorageObjectView> objects) {}
}

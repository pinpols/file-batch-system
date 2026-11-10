package io.github.pinpols.batch.worker.imports.mapper;

import io.github.pinpols.batch.common.storage.ObjectSummary;
import io.github.pinpols.batch.worker.imports.runtime.ImportIngressScanRepository.Configuration;
import io.github.pinpols.batch.worker.imports.runtime.ImportIngressScanRepository.Lease;
import io.github.pinpols.batch.worker.imports.runtime.ImportIngressScanRepository.Observed;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

/** 入站扫描状态和存储租约的固定平台库操作。 */
public interface ImportIngressScanMapper {
  record ScanStart(String storageId, String scopeId, String bucket, String prefix, String digest) {}

  record LeaseRow(
      String phase,
      long cycle,
      String listMarker,
      long afterObjectId,
      long fence,
      long windowId,
      String revisitMarker,
      long revisitBudget,
      long revisitRemaining,
      String nextLane,
      boolean cleanupRequired,
      long applyAfterId,
      long applyUpperId) {}

  record ObjectWrite(
      String scopeId,
      String digest,
      String key,
      long size,
      String etag,
      Instant lastModified,
      long cycle,
      long windowId,
      boolean manifest) {
    public static ObjectWrite of(
        Lease lease, String digest, ObjectSummary object, boolean manifest) {
      return new ObjectWrite(
          lease.scopeId(),
          digest,
          object.key(),
          object.size(),
          object.etag(),
          object.lastModified(),
          lease.cycle(),
          lease.windowId(),
          manifest);
    }
  }

  record Progress(Lease lease, String phase, String marker, long afterObjectId, long cycle) {}

  int ensureStorage(@Param("storageId") String storageId);

  List<Boolean> lockStorage(@Param("storageId") String storageId);

  int ensureScope(ScanStart start);

  int setInitialDigest(ScanStart start);

  List<Configuration> configurations(@Param("scopeId") String scopeId);

  int queueScope(@Param("scopeId") String scopeId, @Param("waitingMillis") long waitingMillis);

  List<String> eligibleScopes(@Param("storageId") String storageId);

  List<LeaseRow> acquireScope(@Param("start") ScanStart start, @Param("owner") UUID owner);

  List<Long> acquireStorage(@Param("storageId") String storageId, @Param("owner") UUID owner);

  List<Integer> lockOwnedStorage(Lease lease);

  List<Integer> lockActiveStorage(Lease lease);

  List<Integer> lockActiveScope(Lease lease);

  int releaseScope(Lease lease);

  int releaseStorage(Lease lease);

  int extendStorage(Lease lease);

  int extendScope(Lease lease);

  List<Long> observe(ObjectWrite write);

  List<Observed> manifestPage(@Param("lease") Lease lease, @Param("limit") int limit);

  List<Observed> applyPage(@Param("lease") Lease lease, @Param("limit") int limit);

  List<Long> maxObjectId(@Param("scopeId") String scopeId);

  List<Observed> findObject(@Param("lease") Lease lease, @Param("digest") String digest);

  int setManifestWaitReason(
      @Param("lease") Lease lease,
      @Param("objectId") long objectId,
      @Param("reason") String reason);

  int removeObject(@Param("lease") Lease lease, @Param("objectId") long objectId);

  int cleanOldObjects(@Param("lease") Lease lease, @Param("limit") int limit);

  int advance(Progress progress);

  int saveProgress(Lease lease);
}

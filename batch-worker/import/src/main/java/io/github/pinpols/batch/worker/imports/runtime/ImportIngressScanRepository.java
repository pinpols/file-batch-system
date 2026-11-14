package io.github.pinpols.batch.worker.imports.runtime;

import io.github.pinpols.batch.common.storage.ObjectSummary;
import io.github.pinpols.batch.common.storage.ingress.ImportIngressOwnershipRepository;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.worker.imports.mapper.ImportIngressScanMapper;
import io.github.pinpols.batch.worker.imports.mapper.ImportIngressScanMapper.LeaseRow;
import io.github.pinpols.batch.worker.imports.mapper.ImportIngressScanMapper.ObjectWrite;
import io.github.pinpols.batch.worker.imports.mapper.ImportIngressScanMapper.Progress;
import io.github.pinpols.batch.worker.imports.mapper.ImportIngressScanMapper.ScanStart;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 保存跨实例可恢复的入站扫描进度；对象存储读取不在数据库事务内执行。 */
@Repository
public class ImportIngressScanRepository {

  /** 未指定时的扫描租约时长毫秒数。 */
  private static final long DEFAULT_LEASE_MILLIS = 30000L;

  /** 排队等待的宽限毫秒数下限；轮询间隔更长时取轮询间隔。 */
  private static final long MIN_QUEUE_GRACE_MILLIS = 120000L;

  private final ImportIngressScanMapper mapper;
  private final ImportIngressOwnershipRepository ownership;
  private final TransactionTemplate transaction;

  public ImportIngressScanRepository(
      ImportIngressScanMapper mapper,
      ImportIngressOwnershipRepository ownership,
      @Qualifier("transactionManager") PlatformTransactionManager manager) {
    this.mapper = mapper;
    this.ownership = ownership;
    this.transaction = new TransactionTemplate(manager);
  }

  /** 入口归属从平台库读取，与扫描状态处于同一数据源。 */
  public Optional<ImportIngressOwnershipRepository.Scope> findActiveOwnership(String scopeId) {
    return ownership.findActiveScope(scopeId);
  }

  /** 读取同一物理存储仍受保护的范围，用于识别更具体的扫描归属。 */
  public List<ImportIngressOwnershipRepository.Scope> listProtectedOwnership(String storageId) {
    return ownership.listProtectedScopes(storageId);
  }

  public Optional<Lease> claim(
      String storageId, String scopeId, String bucket, String prefix, String configDigest) {
    return claim(storageId, scopeId, bucket, prefix, configDigest, DEFAULT_LEASE_MILLIS);
  }

  public Optional<Lease> claim(
      String storageId,
      String scopeId,
      String bucket,
      String prefix,
      String configDigest,
      long pollIntervalMillis) {
    UUID owner = UUID.randomUUID();
    ScanStart start = new ScanStart(storageId, scopeId, bucket, prefix, configDigest);
    Optional<Lease> claimed = transaction.execute(status -> {
      mapper.ensureStorage(storageId);
      List<Boolean> storageReady = mapper.lockStorage(storageId);
      if (EmptyChecks.isEmpty(storageReady)) {
        throw new IllegalStateException("无法读取存储扫描租约，请检查数据库及 Import 角色授权");
      }
      mapper.ensureScope(start);
      mapper.setInitialDigest(start);
      List<Configuration> configurations = mapper.configurations(scopeId);
      if (EmptyChecks.isEmpty(configurations)) {
        throw new IllegalStateException("无法读取入站扫描状态，请检查数据库及 Import 角色授权");
      }
      Configuration configuration = configurations.get(0);
      if (!storageId.equals(configuration.storageId())) {
        throw new IllegalStateException("扫描范围摘要冲突，无法安全认领存储租约");
      }
      if ("MAINTENANCE".equals(configuration.phase())) {
        return Optional.empty();
      }
      if (!configDigest.equals(configuration.digest())) {
        throw new IllegalStateException("同一入站存储范围存在不一致的扫描规则，须停止扫描后完成配置切换");
      }
      long interval = Math.max(0, pollIntervalMillis);
      long grace = Math.max(interval, MIN_QUEUE_GRACE_MILLIS);
      long waitingMillis = interval > Long.MAX_VALUE - grace ? Long.MAX_VALUE : interval + grace;
      mapper.queueScope(scopeId, waitingMillis);
      List<String> eligible = mapper.eligibleScopes(storageId);
      if (EmptyChecks.isEmpty(eligible)
          || !scopeId.equals(eligible.get(0))
          || !storageReady.get(0)) {
        return Optional.empty();
      }
      List<LeaseRow> rows = mapper.acquireScope(start, owner);
      if (EmptyChecks.isEmpty(rows)) {
        return Optional.empty();
      }
      List<Long> storageFences = mapper.acquireStorage(storageId, owner);
      LeaseRow row = rows.get(0);
      Lease lease = Lease.builder()
          .storageId(storageId)
          .storageFence(storageFences.get(0))
          .scopeId(scopeId)
          .owner(owner)
          .fence(row.fence())
          .phase(row.phase())
          .cycle(row.cycle())
          .listMarker(row.listMarker())
          .afterObjectId(row.afterObjectId())
          .windowId(row.windowId())
          .revisitMarker(row.revisitMarker())
          .revisitBudget(row.revisitBudget())
          .revisitRemaining(row.revisitRemaining())
          .nextLane(row.nextLane())
          .cleanupRequired(row.cleanupRequired())
          .applyAfterId(row.applyAfterId())
          .applyUpperId(row.applyUpperId())
          .build();
      return Optional.of(lease);
    });
    return EmptyChecks.isNull(claimed) ? Optional.empty() : claimed;
  }

  public void release(Lease lease) {
    transaction.executeWithoutResult(status -> {
      if (EmptyChecks.isEmpty(mapper.lockOwnedStorage(lease))) {
        return;
      }
      mapper.releaseScope(lease);
      mapper.releaseStorage(lease);
    });
  }

  /** 用数据库行锁和代际隔离过期执行者；调用方不得在 action 中读取对象存储。 */
  public boolean fenced(Lease lease, Runnable action) {
    Boolean committed = transaction.execute(status -> {
      if (EmptyChecks.isEmpty(mapper.lockActiveStorage(lease))) {
        return false;
      }
      if (EmptyChecks.isEmpty(mapper.lockActiveScope(lease))) {
        return false;
      }
      mapper.extendStorage(lease);
      mapper.extendScope(lease);
      action.run();
      return true;
    });
    return Boolean.TRUE.equals(committed);
  }

  public void observe(Lease lease, String digest, ObjectSummary object, boolean manifest) {
    ObjectWrite write = ObjectWrite.of(lease, digest, object, manifest);
    List<Long> rows = mapper.observe(write);
    if (EmptyChecks.isEmpty(rows)) {
      throw new IllegalStateException("扫描对象摘要冲突，无法安全记录对象");
    }
  }

  public List<Observed> manifestPage(Lease lease, int limit) {
    return mapper.manifestPage(lease, limit);
  }

  public List<Observed> applyPage(Lease lease, int limit) {
    return mapper.applyPage(lease, limit);
  }

  public long maxObjectId(Lease lease) {
    List<Long> rows = mapper.maxObjectId(lease.scopeId());
    return EmptyChecks.isEmpty(rows) ? 0 : rows.get(0);
  }

  public Optional<Observed> findObject(Lease lease, String digest) {
    return mapper.findObject(lease, digest).stream().findFirst();
  }

  public boolean setManifestWaitReason(Lease lease, long objectId, String reason) {
    return mapper.setManifestWaitReason(lease, objectId, reason) > 0;
  }

  public void removeObject(Lease lease, long objectId) {
    mapper.removeObject(lease, objectId);
  }

  public int cleanOldObjects(Lease lease, int limit) {
    return mapper.cleanOldObjects(lease, limit);
  }

  public void advance(Lease lease, String phase, String marker, long afterObjectId, long cycle) {
    Progress progress = new Progress(lease, phase, marker, afterObjectId, cycle);
    mapper.advance(progress);
  }

  public void saveProgress(Lease lease) {
    mapper.saveProgress(lease);
  }

  @Builder(toBuilder = true)
  public record Lease(
      String storageId,
      long storageFence,
      String scopeId,
      UUID owner,
      long fence,
      String phase,
      long cycle,
      String listMarker,
      long afterObjectId,
      long windowId,
      String revisitMarker,
      long revisitBudget,
      long revisitRemaining,
      String nextLane,
      boolean cleanupRequired,
      long applyAfterId,
      long applyUpperId) {}

  public record Observed(
      long id,
      String key,
      long size,
      String etag,
      Instant lastModified,
      Instant firstObservedAt,
      Instant databaseNow) {}

  public record Configuration(String digest, String phase, String storageId) {}
}

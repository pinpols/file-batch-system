package io.github.pinpols.batch.worker.imports.runtime;

import io.github.pinpols.batch.common.storage.ObjectSummary;
import io.github.pinpols.batch.common.storage.ingress.ImportIngressOwnershipRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import lombok.Builder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 保存跨实例可恢复的入站扫描进度；对象存储读取不在数据库事务内执行。 */
@Repository
public class ImportIngressScanRepository {

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transaction;

  @Autowired
  public ImportIngressScanRepository(
      @Qualifier("importPlatformDataSource") DataSource dataSource,
      @Qualifier("transactionManager") PlatformTransactionManager manager) {
    this(new JdbcTemplate(dataSource), manager);
  }

  public ImportIngressScanRepository(JdbcTemplate jdbc, PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.transaction = new TransactionTemplate(manager);
  }

  /** 入口归属从平台库读取，与扫描状态处于同一数据源。 */
  public Optional<ImportIngressOwnershipRepository.Scope> findActiveOwnership(String scopeId) {
    return new ImportIngressOwnershipRepository(jdbc).findActiveScope(scopeId);
  }

  /** 读取同一物理存储仍受保护的范围，用于识别更具体的扫描归属。 */
  public List<ImportIngressOwnershipRepository.Scope> listProtectedOwnership(String storageId) {
    return new ImportIngressOwnershipRepository(jdbc).listProtectedScopes(storageId);
  }

  public Optional<Lease> claim(
      String storageId, String scopeId, String bucket, String prefix, String configDigest) {
    return claim(storageId, scopeId, bucket, prefix, configDigest, 30000L);
  }

  public Optional<Lease> claim(
      String storageId,
      String scopeId,
      String bucket,
      String prefix,
      String configDigest,
      long pollIntervalMillis) {
    UUID owner = UUID.randomUUID();
    Optional<Lease> claimed = transaction.execute(status -> {
      jdbc.update("""
          INSERT INTO batch.import_ingress_scan_storage_lease (storage_id) VALUES (?)
          ON CONFLICT (storage_id) DO NOTHING
          """, storageId);
      List<Boolean> storageReady = jdbc.query("""
          SELECT lease_owner IS NULL OR lease_until <= clock_timestamp()
            FROM batch.import_ingress_scan_storage_lease WHERE storage_id = ? FOR UPDATE
          """, (rs, n) -> rs.getBoolean(1), storageId);
      if (storageReady.isEmpty()) {
        throw new IllegalStateException("无法读取存储扫描租约，请检查数据库及 Import 角色授权");
      }
      jdbc.update("""
          INSERT INTO batch.import_ingress_scan_state
              (scope_id, storage_id, bucket_name, object_prefix, config_digest)
          VALUES (?, ?, ?, ?, ?)
          ON CONFLICT (scope_id) DO NOTHING
          """, scopeId, storageId, bucket, prefix, configDigest);
      jdbc.update("""
          UPDATE batch.import_ingress_scan_state SET config_digest = ?
           WHERE scope_id = ? AND config_digest IS NULL AND phase = 'DISCOVER'
             AND lease_owner IS NULL
          """, configDigest, scopeId);
      List<Configuration> configurations = jdbc.query(
          """
          SELECT config_digest, phase, storage_id FROM batch.import_ingress_scan_state WHERE scope_id = ?
          """,
          (rs, n) -> new Configuration(
              rs.getString("config_digest"), rs.getString("phase"), rs.getString("storage_id")),
          scopeId);
      if (configurations.isEmpty()) {
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
      long grace = Math.max(interval, 120000L);
      long waitingMillis = interval > Long.MAX_VALUE - grace ? Long.MAX_VALUE : interval + grace;
      jdbc.update("""
          UPDATE batch.import_ingress_scan_state
             SET waiting_since = CASE WHEN waiting_until IS NULL OR waiting_until <= clock_timestamp()
                                      THEN clock_timestamp() ELSE waiting_since END,
                 waiting_until = greatest(coalesce(waiting_until, clock_timestamp()),
                                          clock_timestamp() + (? * interval '1 millisecond'))
           WHERE scope_id = ?
          """, waitingMillis, scopeId);
      List<String> eligible = jdbc.query("""
          SELECT scope_id FROM batch.import_ingress_scan_state
           WHERE storage_id = ? AND waiting_since IS NOT NULL
             AND waiting_until > clock_timestamp() AND phase <> 'MAINTENANCE'
           ORDER BY waiting_since, scope_id LIMIT 1
          """, (rs, n) -> rs.getString(1), storageId);
      if (eligible.isEmpty() || !scopeId.equals(eligible.get(0)) || !storageReady.get(0)) {
        return Optional.empty();
      }
      List<Lease> rows = jdbc.query(
          """
          UPDATE batch.import_ingress_scan_state
             SET lease_owner = ?, lease_until = clock_timestamp() + interval '2 minutes',
                 fence = fence + 1, waiting_since = NULL, waiting_until = NULL,
                 updated_at = clock_timestamp()
           WHERE scope_id = ? AND config_digest = ? AND phase <> 'MAINTENANCE'
             AND (lease_owner IS NULL OR lease_until <= clock_timestamp())
          RETURNING phase, cycle, list_marker, after_object_id, fence, window_id,
                    revisit_marker, revisit_budget, revisit_remaining, next_lane,
                    cleanup_required, apply_after_id, apply_upper_id
          """,
          (rs, n) -> Lease.builder()
              .storageId(storageId)
              .scopeId(scopeId)
              .owner(owner)
              .fence(rs.getLong("fence"))
              .phase(rs.getString("phase"))
              .cycle(rs.getLong("cycle"))
              .listMarker(rs.getString("list_marker"))
              .afterObjectId(rs.getLong("after_object_id"))
              .windowId(rs.getLong("window_id"))
              .revisitMarker(rs.getString("revisit_marker"))
              .revisitBudget(rs.getLong("revisit_budget"))
              .revisitRemaining(rs.getLong("revisit_remaining"))
              .nextLane(rs.getString("next_lane"))
              .cleanupRequired(rs.getBoolean("cleanup_required"))
              .applyAfterId(rs.getLong("apply_after_id"))
              .applyUpperId(rs.getLong("apply_upper_id"))
              .build(),
          owner,
          scopeId,
          configDigest);
      if (rows.isEmpty()) {
        return Optional.empty();
      }
      List<Long> storageFences = jdbc.query("""
          UPDATE batch.import_ingress_scan_storage_lease
             SET lease_owner = ?, lease_until = clock_timestamp() + interval '2 minutes',
                 fence = fence + 1, updated_at = clock_timestamp()
           WHERE storage_id = ? RETURNING fence
          """, (rs, n) -> rs.getLong(1), owner, storageId);
      return Optional.of(
          rows.get(0).toBuilder().storageFence(storageFences.get(0)).build());
    });
    return claimed == null ? Optional.empty() : claimed;
  }

  public void release(Lease lease) {
    transaction.executeWithoutResult(status -> {
      List<Integer> owned = jdbc.query(
          """
          SELECT 1 FROM batch.import_ingress_scan_storage_lease
           WHERE storage_id = ? AND lease_owner = ? AND fence = ? FOR UPDATE
          """, (rs, n) -> rs.getInt(1), lease.storageId(), lease.owner(), lease.storageFence());
      if (owned.isEmpty()) {
        return;
      }
      jdbc.update("""
          UPDATE batch.import_ingress_scan_state
             SET lease_owner = NULL, lease_until = NULL, updated_at = clock_timestamp()
           WHERE scope_id = ? AND lease_owner = ? AND fence = ?
          """, lease.scopeId(), lease.owner(), lease.fence());
      jdbc.update("""
          UPDATE batch.import_ingress_scan_storage_lease
             SET lease_owner = NULL, lease_until = NULL, updated_at = clock_timestamp()
           WHERE storage_id = ? AND lease_owner = ? AND fence = ?
          """, lease.storageId(), lease.owner(), lease.storageFence());
    });
  }

  /** 用数据库行锁和代际隔离过期执行者；调用方不得在 action 中读取对象存储。 */
  public boolean fenced(Lease lease, Runnable action) {
    Boolean committed = transaction.execute(status -> {
      List<Integer> storageLocked = jdbc.query(
          """
          SELECT 1 FROM batch.import_ingress_scan_storage_lease
           WHERE storage_id = ? AND lease_owner = ? AND fence = ?
             AND lease_until > clock_timestamp()
           FOR UPDATE
          """, (rs, n) -> rs.getInt(1), lease.storageId(), lease.owner(), lease.storageFence());
      if (storageLocked.isEmpty()) {
        return false;
      }
      List<Integer> locked =
          jdbc.query("""
          SELECT 1 FROM batch.import_ingress_scan_state
           WHERE scope_id = ? AND lease_owner = ? AND fence = ?
             AND lease_until > clock_timestamp()
           FOR UPDATE
          """, (rs, n) -> rs.getInt(1), lease.scopeId(), lease.owner(), lease.fence());
      if (locked.isEmpty()) {
        return false;
      }
      jdbc.update("""
          UPDATE batch.import_ingress_scan_storage_lease
             SET lease_until = clock_timestamp() + interval '2 minutes',
                 updated_at = clock_timestamp()
           WHERE storage_id = ?
          """, lease.storageId());
      jdbc.update("""
          UPDATE batch.import_ingress_scan_state
             SET lease_until = clock_timestamp() + interval '2 minutes',
                 updated_at = clock_timestamp()
           WHERE scope_id = ?
          """, lease.scopeId());
      action.run();
      return true;
    });
    return Boolean.TRUE.equals(committed);
  }

  public void observe(Lease lease, String digest, ObjectSummary object, boolean manifest) {
    List<Long> rows = jdbc.query(
        """
        INSERT INTO batch.import_ingress_scan_object
            (scope_id, object_digest, object_key, size_bytes, etag, last_modified,
             first_observed_at, seen_cycle, seen_window, is_manifest)
        VALUES (?, ?, ?, ?, ?, ?, clock_timestamp(), ?, ?, ?)
        ON CONFLICT (scope_id, object_digest) DO UPDATE
           SET first_observed_at = CASE
                 WHEN batch.import_ingress_scan_object.size_bytes IS DISTINCT FROM EXCLUDED.size_bytes
                   OR batch.import_ingress_scan_object.etag IS DISTINCT FROM EXCLUDED.etag
                 THEN clock_timestamp() ELSE batch.import_ingress_scan_object.first_observed_at END,
               manifest_wait_reason = CASE
                 WHEN batch.import_ingress_scan_object.size_bytes IS DISTINCT FROM EXCLUDED.size_bytes
                   OR batch.import_ingress_scan_object.etag IS DISTINCT FROM EXCLUDED.etag
                 THEN NULL ELSE batch.import_ingress_scan_object.manifest_wait_reason END,
               size_bytes = EXCLUDED.size_bytes, etag = EXCLUDED.etag,
               last_modified = EXCLUDED.last_modified, seen_cycle = EXCLUDED.seen_cycle,
               seen_window = EXCLUDED.seen_window,
               is_manifest = EXCLUDED.is_manifest
         WHERE batch.import_ingress_scan_object.object_key = EXCLUDED.object_key
        RETURNING id
        """,
        (rs, n) -> rs.getLong(1),
        lease.scopeId(),
        digest,
        object.key(),
        object.size(),
        object.etag(),
        timestamp(object.lastModified()),
        lease.cycle(),
        lease.windowId(),
        manifest);
    if (rows.isEmpty()) {
      throw new IllegalStateException("扫描对象摘要冲突，无法安全记录对象");
    }
  }

  public List<Observed> manifestPage(Lease lease, int limit) {
    return jdbc.query(
        """
        SELECT id, object_key, size_bytes, etag, last_modified, first_observed_at,
               clock_timestamp() AS database_now
          FROM batch.import_ingress_scan_object
         WHERE scope_id = ? AND seen_window = ? AND id > ? AND is_manifest
         ORDER BY id LIMIT ?
        """,
        (rs, n) -> new Observed(
            rs.getLong("id"),
            rs.getString("object_key"),
            rs.getLong("size_bytes"),
            rs.getString("etag"),
            instant(rs.getTimestamp("last_modified")),
            instant(rs.getTimestamp("first_observed_at")),
            instant(rs.getTimestamp("database_now"))),
        lease.scopeId(),
        lease.windowId(),
        lease.afterObjectId(),
        limit);
  }

  public List<Observed> applyPage(Lease lease, int limit) {
    return jdbc.query(
        """
        SELECT id, object_key, size_bytes, etag, last_modified, first_observed_at,
               clock_timestamp() AS database_now
          FROM batch.import_ingress_scan_object
         WHERE scope_id = ? AND id > ? AND id <= ? ORDER BY id LIMIT ?
        """,
        (rs, n) -> new Observed(
            rs.getLong("id"),
            rs.getString("object_key"),
            rs.getLong("size_bytes"),
            rs.getString("etag"),
            instant(rs.getTimestamp("last_modified")),
            instant(rs.getTimestamp("first_observed_at")),
            instant(rs.getTimestamp("database_now"))),
        lease.scopeId(),
        lease.applyAfterId(),
        lease.applyUpperId(),
        limit);
  }

  public long maxObjectId(Lease lease) {
    List<Long> rows = jdbc.query("""
        SELECT id FROM batch.import_ingress_scan_object
         WHERE scope_id = ? ORDER BY id DESC LIMIT 1
        """, (rs, n) -> rs.getLong(1), lease.scopeId());
    return rows.isEmpty() ? 0 : rows.get(0);
  }

  public Optional<Observed> findObject(Lease lease, String digest) {
    List<Observed> rows = jdbc.query(
        """
        SELECT id, object_key, size_bytes, etag, last_modified, first_observed_at,
               clock_timestamp() AS database_now
          FROM batch.import_ingress_scan_object
         WHERE scope_id = ? AND object_digest = ?
        """,
        (rs, n) -> new Observed(
            rs.getLong("id"),
            rs.getString("object_key"),
            rs.getLong("size_bytes"),
            rs.getString("etag"),
            instant(rs.getTimestamp("last_modified")),
            instant(rs.getTimestamp("first_observed_at")),
            instant(rs.getTimestamp("database_now"))),
        lease.scopeId(),
        digest);
    return rows.stream().findFirst();
  }

  public boolean setManifestWaitReason(Lease lease, long objectId, String reason) {
    int changed = jdbc.update("""
        UPDATE batch.import_ingress_scan_object SET manifest_wait_reason = ?
         WHERE scope_id = ? AND id = ? AND manifest_wait_reason IS DISTINCT FROM ?
        """, reason, lease.scopeId(), objectId, reason);
    return changed > 0;
  }

  public void removeObject(Lease lease, long objectId) {
    jdbc.update(
        "DELETE FROM batch.import_ingress_scan_object WHERE scope_id = ? AND id = ?",
        lease.scopeId(),
        objectId);
  }

  public int cleanOldObjects(Lease lease, int limit) {
    return jdbc.update("""
        DELETE FROM batch.import_ingress_scan_object WHERE id IN (
          SELECT id FROM batch.import_ingress_scan_object
           WHERE scope_id = ? AND seen_cycle < ? ORDER BY seen_cycle, id LIMIT ?
        )
        """, lease.scopeId(), lease.cycle(), limit);
  }

  public void advance(Lease lease, String phase, String marker, long afterObjectId, long cycle) {
    jdbc.update(
        """
        UPDATE batch.import_ingress_scan_state
           SET phase = ?, list_marker = ?, after_object_id = ?, cycle = ?,
               updated_at = clock_timestamp()
         WHERE scope_id = ? AND lease_owner = ? AND fence = ?
        """, phase, marker, afterObjectId, cycle, lease.scopeId(), lease.owner(), lease.fence());
  }

  public void saveProgress(Lease lease) {
    jdbc.update(
        """
        UPDATE batch.import_ingress_scan_state
           SET phase = ?, cycle = ?, list_marker = ?, after_object_id = ?, window_id = ?,
               revisit_marker = ?, revisit_budget = ?, revisit_remaining = ?, next_lane = ?,
               cleanup_required = ?, apply_after_id = ?, apply_upper_id = ?, updated_at = clock_timestamp()
         WHERE scope_id = ? AND lease_owner = ? AND fence = ?
        """,
        lease.phase(),
        lease.cycle(),
        lease.listMarker(),
        lease.afterObjectId(),
        lease.windowId(),
        lease.revisitMarker(),
        lease.revisitBudget(),
        lease.revisitRemaining(),
        lease.nextLane(),
        lease.cleanupRequired(),
        lease.applyAfterId(),
        lease.applyUpperId(),
        lease.scopeId(),
        lease.owner(),
        lease.fence());
  }

  private static Timestamp timestamp(Instant value) {
    return value == null ? null : Timestamp.from(value);
  }

  private static Instant instant(Timestamp value) {
    return value == null ? null : value.toInstant();
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

  private record Configuration(String digest, String phase, String storageId) {}
}

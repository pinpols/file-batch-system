package io.github.pinpols.batch.orchestrator.infrastructure.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.FileStatus;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.storage.ObjectNotFoundException;
import io.github.pinpols.batch.orchestrator.config.FileGovernanceProperties;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.FileGovernanceMetricsCacheService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * #440 托管上传会话孤儿清理:createUploadSession 占位的 file_record(RECEIVED + APP_MANAGED + WAITING_ARRIVAL)
 * 前端既不上传也不 confirm 时永久滞留。验证:
 *
 * <ol>
 *   <li>S3 对象不存在 → 经 ARCHIVED 中转两步软删置 DELETED 终态 + CLEANUP 审计
 *   <li>S3 对象已存在(上传了但没 confirm)→ 跳过,不动状态
 *   <li>首跳 CAS 失败(并发状态变化)→ 放弃本轮,不写第二跳 / 审计
 *   <li>开关关闭 → 完全不查库
 * </ol>
 */
@DisplayName("托管上传会话孤儿清理:对象不存在时两步置为删除终态并写审计,对象已上传时保留不动,并发状态变更时放弃本轮,清理开关关闭时不查询数据")
class FileGovernanceOrphanUploadSessionCleanupTest {

  private FileGovernanceRepository repository;
  private S3GovernanceStorage storage;
  private FileGovernanceScheduler scheduler;
  private FileGovernanceProperties properties;

  @BeforeEach
  void setUp() {
    repository = mock(FileGovernanceRepository.class);
    storage = mock(S3GovernanceStorage.class);
    properties = mock(FileGovernanceProperties.class, Mockito.RETURNS_DEEP_STUBS);
    when(properties.getArchive().isEnabled()).thenReturn(true);
    when(properties.getArchive().getRetentionDays()).thenReturn(30);
    when(properties.getArchive().getCleanupBatchSize()).thenReturn(100);
    when(properties.getUploadSession().isCleanupEnabled()).thenReturn(true);
    when(properties.getUploadSession().getOrphanTtlSeconds()).thenReturn(86400L);
    when(properties.getUploadSession().getCleanupBatchSize()).thenReturn(100);

    scheduler = new FileGovernanceScheduler(
        repository,
        storage,
        properties,
        mock(FileGovernanceMetricsCacheService.class),
        new SimpleMeterRegistry(),
        mock(BundleArrivalLauncher.class));
  }

  @Test
  @DisplayName("S3 对象不存在的过期会话 → RECEIVED→ARCHIVED→DELETED 两步软删 + CLEANUP 审计")
  void shouldDeleteOrphanSession_whenObjectMissing() {
    // arrange
    when(repository.selectOrphanUploadSessions(anyLong(), anyInt()))
        .thenReturn(List.of(orphanSession(9001L)));
    when(storage.objectSize(eq("batch-files"), anyString()))
        .thenThrow(new ObjectNotFoundException("missing"));
    when(repository.updateFileStatus(
            eq("default-tenant"),
            eq(9001L),
            eq(FileStatus.RECEIVED.code()),
            eq(FileStatus.ARCHIVED.code()),
            any()))
        .thenReturn(1);

    // act
    scheduler.cleanupOrphanUploadSessions();

    // assert
    verify(repository, times(1))
        .updateFileStatus(
            eq("default-tenant"),
            eq(9001L),
            eq(FileStatus.RECEIVED.code()),
            eq(FileStatus.ARCHIVED.code()),
            any());
    verify(repository, times(1))
        .updateFileStatus(
            eq("default-tenant"),
            eq(9001L),
            eq(FileStatus.ARCHIVED.code()),
            eq(FileStatus.DELETED.code()),
            any());
    verify(repository, times(1)).appendAudit(any());
  }

  @Test
  @DisplayName("S3 对象已存在(上传了但没 confirm)→ 跳过不删")
  void shouldSkipSession_whenObjectAlreadyUploaded() {
    // arrange
    when(repository.selectOrphanUploadSessions(anyLong(), anyInt()))
        .thenReturn(List.of(orphanSession(9002L)));
    when(storage.objectSize(eq("batch-files"), anyString())).thenReturn(1024L);

    // act
    scheduler.cleanupOrphanUploadSessions();

    // assert
    verify(repository, never()).updateFileStatus(anyString(), anyLong(), any(), any(), any());
    verify(repository, never()).appendAudit(any());
  }

  @Test
  @DisplayName("首跳 CAS 失败(并发状态变化)→ 放弃本轮,不写第二跳/审计")
  void shouldAbortCleanup_whenStatusChangedConcurrently() {
    // arrange
    when(repository.selectOrphanUploadSessions(anyLong(), anyInt()))
        .thenReturn(List.of(orphanSession(9003L)));
    when(storage.objectSize(eq("batch-files"), anyString()))
        .thenThrow(new ObjectNotFoundException("missing"));
    when(repository.updateFileStatus(
            eq("default-tenant"),
            eq(9003L),
            eq(FileStatus.RECEIVED.code()),
            eq(FileStatus.ARCHIVED.code()),
            any()))
        .thenReturn(0);

    // act
    scheduler.cleanupOrphanUploadSessions();

    // assert
    verify(repository, never())
        .updateFileStatus(
            anyString(),
            anyLong(),
            eq(FileStatus.ARCHIVED.code()),
            eq(FileStatus.DELETED.code()),
            any());
    verify(repository, never()).appendAudit(any());
  }

  @Test
  @DisplayName("cleanup-enabled=false → 完全不查库")
  void shouldDoNothing_whenCleanupDisabled() {
    // arrange
    FileGovernanceProperties disabled =
        mock(FileGovernanceProperties.class, Mockito.RETURNS_DEEP_STUBS);
    when(disabled.getUploadSession().isCleanupEnabled()).thenReturn(false);
    FileGovernanceScheduler disabledScheduler = new FileGovernanceScheduler(
        repository,
        storage,
        disabled,
        mock(FileGovernanceMetricsCacheService.class),
        new SimpleMeterRegistry(),
        mock(BundleArrivalLauncher.class));

    // act
    disabledScheduler.cleanupOrphanUploadSessions();

    // assert
    verify(repository, never()).selectOrphanUploadSessions(anyLong(), anyInt());
  }

  @Test
  @DisplayName("跨租户孤儿会话清理在每条记录自己的 RLS 上下文中执行")
  void shouldCleanupOrphanSessionsWithinTheirTenantContexts() {
    when(repository.selectOrphanUploadSessions(anyLong(), anyInt()))
        .thenReturn(List.of(orphanSession(9101L, "tenant-a"), orphanSession(9102L, "tenant-b")));
    when(storage.objectSize(eq("batch-files"), anyString()))
        .thenThrow(new ObjectNotFoundException("missing"));
    List<String> tenantContexts = new ArrayList<>();
    when(repository.updateFileStatus(anyString(), anyLong(), any(), any(), any()))
        .thenAnswer(invocation -> {
          tenantContexts.add(RlsTenantContextHolder.get());
          return 1;
        });
    doAnswer(invocation -> {
          tenantContexts.add(RlsTenantContextHolder.get());
          return null;
        })
        .when(repository)
        .appendAudit(any());

    scheduler.cleanupOrphanUploadSessions();

    assertThat(tenantContexts)
        .containsExactly("tenant-a", "tenant-a", "tenant-a", "tenant-b", "tenant-b", "tenant-b");
    assertThat(RlsTenantContextHolder.get()).isNull();
  }

  @Test
  @DisplayName("跨租户归档文件清理的检查、软删和审计均在文件租户上下文中执行")
  void shouldCleanupArchivedFilesWithinTheirTenantContexts() {
    List<String> tenantContexts = new ArrayList<>();
    when(repository.selectArchivedFilesForCleanup(any(), anyInt()))
        .thenReturn(List.of(
            new FileGovernanceMaintenanceViews.ArchivedFileCleanupView(
                9201L, "tenant-a", "archive/a.csv", "S3", FileStatus.ARCHIVED.code()),
            new FileGovernanceMaintenanceViews.ArchivedFileCleanupView(
                9202L, "tenant-b", "archive/b.csv", "S3", FileStatus.ARCHIVED.code())));
    when(repository.countActivePipelineInstances(anyString(), anyLong())).thenAnswer(invocation -> {
      tenantContexts.add(RlsTenantContextHolder.get());
      return 0L;
    });
    when(repository.countPendingDispatchRecords(anyString(), anyLong())).thenAnswer(invocation -> {
      tenantContexts.add(RlsTenantContextHolder.get());
      return 0L;
    });
    when(repository.updateFileStatus(anyString(), anyLong(), any(), any(), any()))
        .thenAnswer(invocation -> {
          tenantContexts.add(RlsTenantContextHolder.get());
          return 1;
        });
    doAnswer(invocation -> {
          tenantContexts.add(RlsTenantContextHolder.get());
          return null;
        })
        .when(storage)
        .removeObject(anyString());
    doAnswer(invocation -> {
          tenantContexts.add(RlsTenantContextHolder.get());
          return null;
        })
        .when(repository)
        .appendAudit(any());

    scheduler.cleanupArchivedFiles();

    assertThat(tenantContexts)
        .containsExactly(
            "tenant-a",
            "tenant-a",
            "tenant-a",
            "tenant-a",
            "tenant-a",
            "tenant-b",
            "tenant-b",
            "tenant-b",
            "tenant-b",
            "tenant-b");
    assertThat(RlsTenantContextHolder.get()).isNull();
  }

  @Test
  @DisplayName("对象对账在配置租户上下文中查询、创建记录并写审计")
  void shouldReconcileObjectsWithinConfiguredTenantContext() {
    when(properties.getReconcile().isEnabled()).thenReturn(true);
    when(properties.getReconcile().getDefaultTenantId()).thenReturn("tenant-configured");
    when(storage.listObjects(any(), anyInt(), anyBoolean()))
        .thenReturn(List.of(new S3GovernanceStorage.StorageObjectView(
            "batch-files",
            "incoming/new.csv",
            12L,
            "etag",
            Instant.parse("2026-10-17T00:00:00Z"))));
    List<String> tenantContexts = new ArrayList<>();
    when(repository.existsFileRecordByStoragePath(anyString(), anyString(), anyString()))
        .thenAnswer(invocation -> {
          tenantContexts.add(RlsTenantContextHolder.get());
          return false;
        });
    when(repository.createReconciledFileRecord(any())).thenAnswer(invocation -> {
      tenantContexts.add(RlsTenantContextHolder.get());
      return 9301L;
    });
    doAnswer(invocation -> {
          tenantContexts.add(RlsTenantContextHolder.get());
          return null;
        })
        .when(repository)
        .appendAudit(any());

    scheduler.reconcileObjectStorage();

    assertThat(tenantContexts)
        .containsExactly("tenant-configured", "tenant-configured", "tenant-configured");
    assertThat(RlsTenantContextHolder.get()).isNull();
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static FileGovernanceMaintenanceViews.OrphanUploadSessionView orphanSession(long id) {
    return orphanSession(id, "default-tenant");
  }

  private static FileGovernanceMaintenanceViews.OrphanUploadSessionView orphanSession(
      long id, String tenantId) {
    return new FileGovernanceMaintenanceViews.OrphanUploadSessionView(
        id, tenantId, "batch-files", "uploads/default-tenant/2026/06/" + id + "-upload.csv");
  }
}

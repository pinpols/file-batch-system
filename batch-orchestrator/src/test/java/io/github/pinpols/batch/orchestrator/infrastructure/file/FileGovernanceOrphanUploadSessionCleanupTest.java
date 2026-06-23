package io.github.pinpols.batch.orchestrator.infrastructure.file;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.storage.ObjectNotFoundException;
import io.github.pinpols.batch.orchestrator.config.FileGovernanceProperties;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.FileGovernanceMetricsCacheService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
class FileGovernanceOrphanUploadSessionCleanupTest {

  private FileGovernanceRepository repository;
  private S3GovernanceStorage storage;
  private FileGovernanceScheduler scheduler;

  @BeforeEach
  void setUp() {
    repository = mock(FileGovernanceRepository.class);
    storage = mock(S3GovernanceStorage.class);
    FileGovernanceProperties properties =
        mock(FileGovernanceProperties.class, Mockito.RETURNS_DEEP_STUBS);
    when(properties.getUploadSession().isCleanupEnabled()).thenReturn(true);
    when(properties.getUploadSession().getOrphanTtlSeconds()).thenReturn(86400L);
    when(properties.getUploadSession().getCleanupBatchSize()).thenReturn(100);

    scheduler =
        new FileGovernanceScheduler(
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
            eq("default-tenant"), eq(9001L), eq("RECEIVED"), eq("ARCHIVED"), any()))
        .thenReturn(1);

    // act
    scheduler.cleanupOrphanUploadSessions();

    // assert
    verify(repository, times(1))
        .updateFileStatus(eq("default-tenant"), eq(9001L), eq("RECEIVED"), eq("ARCHIVED"), any());
    verify(repository, times(1))
        .updateFileStatus(eq("default-tenant"), eq(9001L), eq("ARCHIVED"), eq("DELETED"), any());
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
            eq("default-tenant"), eq(9003L), eq("RECEIVED"), eq("ARCHIVED"), any()))
        .thenReturn(0);

    // act
    scheduler.cleanupOrphanUploadSessions();

    // assert
    verify(repository, never())
        .updateFileStatus(anyString(), anyLong(), eq("ARCHIVED"), eq("DELETED"), any());
    verify(repository, never()).appendAudit(any());
  }

  @Test
  @DisplayName("cleanup-enabled=false → 完全不查库")
  void shouldDoNothing_whenCleanupDisabled() {
    // arrange
    FileGovernanceProperties disabled =
        mock(FileGovernanceProperties.class, Mockito.RETURNS_DEEP_STUBS);
    when(disabled.getUploadSession().isCleanupEnabled()).thenReturn(false);
    FileGovernanceScheduler disabledScheduler =
        new FileGovernanceScheduler(
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

  // ── helpers ───────────────────────────────────────────────────────────────

  private static Map<String, Object> orphanSession(long id) {
    Map<String, Object> session = new LinkedHashMap<>();
    session.put("id", id);
    session.put("tenant_id", "default-tenant");
    session.put("file_name", "upload-" + id + ".csv");
    session.put("file_status", "RECEIVED");
    session.put("storage_bucket", "batch-files");
    session.put("storage_path", "uploads/default-tenant/2026/06/" + id + "-upload.csv");
    return session;
  }
}

package com.example.batch.orchestrator.infrastructure.file;

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

import com.example.batch.orchestrator.config.FileGovernanceProperties;
import com.example.batch.orchestrator.infrastructure.redis.FileGovernanceMetricsCacheService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * 2026-05-01 噪声治理:验证 FileGovernanceScheduler 三处死循环防御:
 *
 * <ol>
 *   <li>SQL 排除 {@code WAITING_MANUAL_CONFIRM} 已在 mapper 改,本类不覆盖
 *   <li>{@code requiredFileSet} 空时跳过 updateGroupState(避免每 30s 抖 db / INFO 日志)
 *   <li>state + reason 已与目标一致时 idempotent skip
 * </ol>
 */
class FileGovernanceArrivalGroupGuardTest {

  private FileGovernanceRepository repository;
  private FileGovernanceScheduler scheduler;

  @BeforeEach
  void setUp() {
    repository = mock(FileGovernanceRepository.class);
    FileGovernanceProperties properties =
        mock(FileGovernanceProperties.class, Mockito.RETURNS_DEEP_STUBS);
    when(properties.getArrival().isEnabled()).thenReturn(true);
    when(properties.getArrival().getBatchSize()).thenReturn(100);
    when(properties.getArrival().isTriggerOnComplete()).thenReturn(true);
    when(properties.getArrival().getDefaultTimeoutAction()).thenReturn("MANUAL_CONFIRM");

    scheduler =
        new FileGovernanceScheduler(
            repository,
            mock(S3GovernanceStorage.class),
            properties,
            mock(FileGovernanceMetricsCacheService.class),
            new SimpleMeterRegistry());
  }

  @Test
  void shouldSkipUpdateWhenRequiredFileSetIsEmpty() {
    // 5207 类场景:有 fileGroupCode 但 requiredFileSet 为空 → 不应每 tick 更新 state
    Map<String, Object> file =
        baseFile(5207L, "report.fw", "WAITING_ARRIVAL", "WAITING_REQUIRED_FILES");
    file.put("required_file_set", "");
    when(repository.selectArrivalGovernanceCandidates(anyInt())).thenReturn(List.of(file));

    scheduler.manageFileArrivalGroups();

    verify(repository, never()).updateFileMetadata(anyString(), anyLong(), any());
    verify(repository, never()).appendAudit(any());
  }

  @Test
  void shouldSkipUpdateWhenStateAndReasonAreUnchanged() {
    // 状态稳态:state + reason 已与目标一致(WAITING_ARRIVAL + WAITING_REQUIRED_FILES),
    // 即使 requiredFileSet 非空 + 文件未到齐,update 也应跳过避免 db / 日志噪声
    Map<String, Object> file =
        baseFile(5210L, "missing-trigger.csv", "WAITING_ARRIVAL", "WAITING_REQUIRED_FILES");
    file.put("required_file_set", "missing-trigger.csv,companion.csv");
    when(repository.selectArrivalGovernanceCandidates(anyInt())).thenReturn(List.of(file));

    scheduler.manageFileArrivalGroups();

    verify(repository, never()).updateFileMetadata(anyString(), anyLong(), any());
    verify(repository, never()).appendAudit(any());
  }

  @Test
  void shouldUpdateWhenStateChanges() {
    // 文件齐全 + triggerOnComplete=true → state 由 WAITING_ARRIVAL 升 TRIGGERED,update 必须发生
    Map<String, Object> file =
        baseFile(5300L, "ready.csv", "WAITING_ARRIVAL", "WAITING_REQUIRED_FILES");
    file.put("required_file_set", "ready.csv");
    when(repository.selectArrivalGovernanceCandidates(anyInt())).thenReturn(List.of(file));

    scheduler.manageFileArrivalGroups();

    verify(repository, times(1)).updateFileMetadata(eq("default-tenant"), eq(5300L), any());
    verify(repository, times(1)).appendAudit(any());
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static Map<String, Object> baseFile(
      long id, String fileName, String arrivalState, String arrivalReason) {
    Map<String, Object> file = new LinkedHashMap<>();
    file.put("id", id);
    file.put("tenant_id", "default-tenant");
    file.put("file_name", fileName);
    file.put("file_status", "RECEIVED");
    file.put("file_group_code", "test-group");
    file.put("wait_file_group_mode", "ALL_OF");
    file.put("arrival_timeout_action", "MANUAL_CONFIRM");
    file.put("arrival_state", arrivalState);
    file.put("arrival_reason", arrivalReason);
    file.put("trigger_on_complete", "true");
    return file;
  }
}

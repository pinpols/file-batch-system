package io.github.pinpols.batch.orchestrator.infrastructure.file;

import static org.assertj.core.api.Assertions.assertThat;
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

import io.github.pinpols.batch.common.enums.FileStatus;
import io.github.pinpols.batch.orchestrator.config.FileGovernanceProperties;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.FileGovernanceMetricsCacheService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * 2026-05-01 噪声治理:验证 FileGovernanceScheduler 三处无限循环防御:
 *
 * <ol>
 *   <li>SQL 排除 {@code WAITING_MANUAL_CONFIRM} 已在 mapper 改,本类不覆盖
 *   <li>{@code requiredFileSet} 空时跳过 updateGroupState(避免每 30s 抖 db / INFO 日志)
 *   <li>state + reason 已与目标一致时 idempotent skip
 * </ol>
 */
@DisplayName("文件到达分组治理流程,验证必需文件集为空,到达状态与原因未变,成员校验未通过及触发失败等分支的更新与跳过边界")
class FileGovernanceArrivalGroupGuardTest {

  private FileGovernanceRepository repository;
  private FileGovernanceProperties properties;
  private BundleArrivalLauncher bundleArrivalLauncher;
  private FileGovernanceScheduler scheduler;

  @BeforeEach
  void setUp() {
    repository = mock(FileGovernanceRepository.class);
    properties = mock(FileGovernanceProperties.class, Mockito.RETURNS_DEEP_STUBS);
    when(properties.getArrival().isEnabled()).thenReturn(true);
    when(properties.getArrival().getBatchSize()).thenReturn(100);
    when(properties.getArrival().isTriggerOnComplete()).thenReturn(true);
    when(properties.getArrival().getDefaultTimeoutAction()).thenReturn("MANUAL_CONFIRM");

    bundleArrivalLauncher = mock(BundleArrivalLauncher.class);
    scheduler = new FileGovernanceScheduler(
        repository,
        mock(S3GovernanceStorage.class),
        properties,
        mock(FileGovernanceMetricsCacheService.class),
        new SimpleMeterRegistry(),
        bundleArrivalLauncher);
  }

  @Test
  @DisplayName("必需文件集为空时既不改写到达状态也不写入审计,避免每轮空转")
  void shouldSkipUpdateWhenRequiredFileSetIsEmpty() {
    // 5207 类场景:有 fileGroupCode 但 requiredFileSet 为空 → 不应每 tick 更新 state
    Map<String, Object> file =
        baseFile(5207L, "report.fw", "WAITING_ARRIVAL", "WAITING_REQUIRED_FILES");
    file.put("required_file_set", "");
    when(repository.selectArrivalGovernanceCandidates(anyInt()))
        .thenReturn(candidateViews(List.of(file)));

    scheduler.manageFileArrivalGroups();

    verify(repository, never()).updateFileMetadata(anyString(), anyLong(), any());
    verify(repository, never()).appendAudit(any());
  }

  @Test
  @DisplayName("到达状态与原因已与目标一致时跳过改写与审计,避免稳态下重复写入")
  void shouldSkipUpdateWhenStateAndReasonAreUnchanged() {
    // 状态稳态:state + reason 已与目标一致(WAITING_ARRIVAL + WAITING_REQUIRED_FILES),
    // 即使 requiredFileSet 非空 + 文件未到齐,update 也应跳过避免 db / 日志噪声
    Map<String, Object> file =
        baseFile(5210L, "missing-trigger.csv", "WAITING_ARRIVAL", "WAITING_REQUIRED_FILES");
    file.put("required_file_set", "missing-trigger.csv,companion.csv");
    when(repository.selectArrivalGovernanceCandidates(anyInt()))
        .thenReturn(candidateViews(List.of(file)));

    scheduler.manageFileArrivalGroups();

    verify(repository, never()).updateFileMetadata(anyString(), anyLong(), any());
    verify(repository, never()).appendAudit(any());
  }

  @Test
  @DisplayName("必需文件齐备且到达即触发开启时,到达状态改写一次并写入一条审计")
  void shouldUpdateWhenStateChanges() {
    // 文件齐全 + triggerOnComplete=true → state 由 WAITING_ARRIVAL 升 TRIGGERED,update 必须发生
    Map<String, Object> file =
        baseFile(5300L, "ready.csv", "WAITING_ARRIVAL", "WAITING_REQUIRED_FILES");
    file.put("required_file_set", "ready.csv");
    when(repository.selectArrivalGovernanceCandidates(anyInt()))
        .thenReturn(candidateViews(List.of(file)));

    scheduler.manageFileArrivalGroups();

    verify(repository, times(1)).updateFileMetadata(eq("default-tenant"), eq(5300L), any());
    verify(repository, times(1)).appendAudit(any());
  }

  @Test
  @DisplayName("开启校验要求时,成员缺少校验值则不放行,保持等待并标记待校验")
  void shouldHoldWhenRequireVerifiedAndMemberMissingChecksum() {
    // require-verified=true:文件名虽齐,但成员 checksum_type=NONE(无 manifest 背书)→ 不放行,保持等待
    when(properties.getArrival().isRequireVerified()).thenReturn(true);
    Map<String, Object> file =
        baseFile(5400L, "ready.csv", "WAITING_ARRIVAL", "WAITING_REQUIRED_FILES");
    file.put("required_file_set", "ready.csv");
    file.put("checksum_type", "NONE");
    when(repository.selectArrivalGovernanceCandidates(anyInt()))
        .thenReturn(candidateViews(List.of(file)));

    scheduler.manageFileArrivalGroups();

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
    verify(repository).updateFileMetadata(eq("default-tenant"), eq(5400L), captor.capture());
    assertThat(captor.getValue()).containsEntry("arrivalState", "WAITING_ARRIVAL");
    assertThat(captor.getValue()).containsEntry("arrivalReason", "ARRIVED_PENDING_VERIFY");
  }

  @Test
  @DisplayName("开启校验要求时,成员均具备校验值则正常触发")
  void shouldTriggerWhenRequireVerifiedAndAllMembersHaveChecksum() {
    // require-verified=true + 成员 checksum_type 非 NONE(有 manifest 背书)→ 正常触发
    when(properties.getArrival().isRequireVerified()).thenReturn(true);
    Map<String, Object> file =
        baseFile(5401L, "ready.csv", "WAITING_ARRIVAL", "WAITING_REQUIRED_FILES");
    file.put("required_file_set", "ready.csv");
    file.put("checksum_type", "SHA-256");
    when(repository.selectArrivalGovernanceCandidates(anyInt()))
        .thenReturn(candidateViews(List.of(file)));

    scheduler.manageFileArrivalGroups();

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
    verify(repository).updateFileMetadata(eq("default-tenant"), eq(5401L), captor.capture());
    assertThat(captor.getValue()).containsEntry("arrivalState", "TRIGGERED");
  }

  @Test
  @DisplayName("同一到达组的触发过程抛出异常时不改写状态与审计,以便下一轮重试")
  void shouldKeepBundleGroupRetryableWhenLaunchFails() {
    Map<String, Object> file =
        baseFile(5500L, "ready.csv", "WAITING_ARRIVAL", "WAITING_REQUIRED_FILES");
    file.put("required_file_set", "ready.csv");
    when(repository.selectArrivalGovernanceCandidates(anyInt()))
        .thenReturn(candidateViews(List.of(file)));
    when(bundleArrivalLauncher.launchIfBundle(eq("default-tenant"), eq("test-group"), any()))
        .thenThrow(new IllegalStateException("launch failed"));

    scheduler.manageFileArrivalGroups();

    verify(repository, never()).updateFileMetadata(anyString(), anyLong(), any());
    verify(repository, never()).appendAudit(any());
  }

  @Test
  @DisplayName("同一到达组编码按业务日期分组,两个业务日期的文件各自独立推进")
  void shouldPartitionSameArrivalGroupCodeByBizDate() {
    Map<String, Object> today =
        baseFile(5600L, "ready.csv", "WAITING_ARRIVAL", "WAITING_REQUIRED_FILES");
    today.put("biz_date", "2026-06-21");
    today.put("required_file_set", "ready.csv");
    Map<String, Object> yesterday =
        baseFile(5601L, "ready.csv", "WAITING_ARRIVAL", "WAITING_REQUIRED_FILES");
    yesterday.put("biz_date", "2026-06-20");
    yesterday.put("required_file_set", "ready.csv");
    when(repository.selectArrivalGovernanceCandidates(anyInt()))
        .thenReturn(candidateViews(List.of(today, yesterday)));

    scheduler.manageFileArrivalGroups();

    verify(repository, times(1)).updateFileMetadata(eq("default-tenant"), eq(5600L), any());
    verify(repository, times(1)).updateFileMetadata(eq("default-tenant"), eq(5601L), any());
  }

  @Test
  @DisplayName("业务日期缺失时仍按同一分组推进到达状态")
  void shouldStillTriggerArrivalGroupWhenBizDateMissing() {
    Map<String, Object> file =
        baseFile(5700L, "ready.csv", "WAITING_ARRIVAL", "WAITING_REQUIRED_FILES");
    file.remove("biz_date");
    file.put("required_file_set", "ready.csv");
    when(repository.selectArrivalGovernanceCandidates(anyInt()))
        .thenReturn(candidateViews(List.of(file)));

    scheduler.manageFileArrivalGroups();

    verify(repository, times(1)).updateFileMetadata(eq("default-tenant"), eq(5700L), any());
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static List<FileGovernanceArrivalViews.ArrivalCandidateView> candidateViews(
      List<Map<String, Object>> rows) {
    return rows.stream().map(FileGovernanceArrivalViews::arrivalCandidate).toList();
  }

  private static Map<String, Object> baseFile(
      long id, String fileName, String arrivalState, String arrivalReason) {
    Map<String, Object> file = new LinkedHashMap<>();
    file.put("id", id);
    file.put("tenant_id", "default-tenant");
    file.put("biz_date", "2026-06-21");
    file.put("file_name", fileName);
    file.put("file_status", FileStatus.RECEIVED.code());
    file.put("file_group_code", "test-group");
    file.put("wait_file_group_mode", "ALL_OF");
    file.put("arrival_timeout_action", "MANUAL_CONFIRM");
    file.put("arrival_state", arrivalState);
    file.put("arrival_reason", arrivalReason);
    file.put("trigger_on_complete", "true");
    return file;
  }
}

package io.github.pinpols.batch.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.pinpols.batch.common.dto.DependencyResultReference;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.TriggerRequestStatus;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.persistence.entity.TriggerRequestEntity;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.application.service.asset.AssetPartitionService;
import io.github.pinpols.batch.orchestrator.application.service.asset.AssetPartitionSnapshot;
import io.github.pinpols.batch.orchestrator.application.service.workflow.BizDateArithmetic;
import io.github.pinpols.batch.orchestrator.domain.entity.BusinessCalendarEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobDependencyWaitingLaunchEntity;
import io.github.pinpols.batch.orchestrator.domain.query.JobDependencyRule;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayPlanCalendarMapper;
import io.github.pinpols.batch.orchestrator.mapper.BusinessCalendarMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobDependencyMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobDependencyWaitingLaunchMapper;
import io.github.pinpols.batch.orchestrator.mapper.TriggerRequestMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

@DisplayName("作业依赖等待启动: 保存原请求并在同业务日按 AND 语义检查")
class JobDependencyWaitingLaunchServiceTest {

  private JobDependencyMapper dependencyMapper;
  private JobDependencyWaitingLaunchMapper waitingMapper;
  private AssetPartitionService assetPartitionService;
  private TriggerRequestMapper triggerRequestMapper;
  private BatchDayPlanCalendarMapper planCalendarMapper;
  private BusinessCalendarMapper businessCalendarMapper;
  private JobDependencyWaitingLaunchService service;

  @BeforeEach
  void setUp() {
    dependencyMapper = mock(JobDependencyMapper.class);
    waitingMapper = mock(JobDependencyWaitingLaunchMapper.class);
    assetPartitionService = mock(AssetPartitionService.class);
    triggerRequestMapper = mock(TriggerRequestMapper.class);
    planCalendarMapper = mock(BatchDayPlanCalendarMapper.class);
    businessCalendarMapper = mock(BusinessCalendarMapper.class);
    BatchDateTimeSupport timeSupport = mock(BatchDateTimeSupport.class);
    when(timeSupport.nowInstant()).thenReturn(Instant.parse("2026-10-14T00:00:00Z"));
    service = new JobDependencyWaitingLaunchService(
        dependencyMapper,
        waitingMapper,
        new BizDateArithmetic(),
        assetPartitionService,
        triggerRequestMapper,
        planCalendarMapper,
        businessCalendarMapper,
        timeSupport,
        mock(PlatformTransactionManager.class));
  }

  @Test
  @DisplayName("部分上游未就绪时保存原始业务日、依赖快照和完整 LaunchRequest")
  void shouldPersistRequestWhenAnyUpstreamIsNotReady() {
    LocalDate bizDate = LocalDate.of(2026, 10, 13);
    LaunchRequest request = new LaunchRequest(
        "tenant-a",
        "EXPORT_JOB",
        bizDate,
        TriggerType.EVENT,
        "request-1",
        "trace-1",
        Map.of("arrivalGroup", "group-1"));
    when(waitingMapper.selectByTenantAndRequestId("tenant-a", "request-1")).thenReturn(null);
    when(dependencyMapper.selectUpstreamRules("tenant-a", "EXPORT_JOB"))
        .thenReturn(List.of(
            JobDependencyRule.sameDay("UPSTREAM_A"), JobDependencyRule.sameDay("UPSTREAM_B")));
    when(assetPartitionService.findEffectiveJobPartition("tenant-a", "UPSTREAM_A", bizDate))
        .thenReturn(Optional.of(partition("UPSTREAM_A", bizDate)));
    when(assetPartitionService.findEffectiveJobPartition("tenant-a", "UPSTREAM_B", bizDate))
        .thenReturn(Optional.empty());

    stubPendingCapture(request);
    assertThat(service.deferIfNotReady(request)).isTrue();

    ArgumentCaptor<JobDependencyWaitingLaunchEntity> captor =
        ArgumentCaptor.forClass(JobDependencyWaitingLaunchEntity.class);
    verify(waitingMapper).insertIfAbsent(captor.capture());
    JobDependencyWaitingLaunchEntity waiting = captor.getValue();
    assertThat(waiting.bizDate()).isEqualTo(bizDate);
    assertThat(JsonUtils.fromJson(
            waiting.dependencySnapshot(), new TypeReference<List<JobDependencyRule>>() {}))
        .containsExactly(
            JobDependencyRule.sameDay("UPSTREAM_A"), JobDependencyRule.sameDay("UPSTREAM_B"));
    LaunchRequest restored = JsonUtils.fromJson(waiting.launchPayload(), LaunchRequest.class);
    assertThat(restored).isEqualTo(request);
    verify(waitingMapper).recordCheck("tenant-a", "request-1", "UPSTREAM_NOT_EFFECTIVE:UPSTREAM_B");
    verify(triggerRequestMapper)
        .updateAcceptance("tenant-a", "request-1", TriggerRequestStatus.WAITING.code(), null);
  }

  @Test
  @DisplayName("ready gate 返回准入时观察到的上游结果版本身份")
  void shouldCaptureEffectiveUpstreamVersionForInstanceLineage() {
    LocalDate bizDate = LocalDate.of(2026, 10, 13);
    when(waitingMapper.selectByTenantAndRequestId("tenant-a", "request-lineage"))
        .thenReturn(null);
    when(dependencyMapper.selectUpstreamRules("tenant-a", "EXPORT_JOB"))
        .thenReturn(List.of(JobDependencyRule.sameDay("UPSTREAM_A")));
    when(assetPartitionService.findEffectiveJobPartition("tenant-a", "UPSTREAM_A", bizDate))
        .thenReturn(Optional.of(partition("UPSTREAM_A", bizDate)));
    LaunchRequest request = new LaunchRequest(
        "tenant-a", "EXPORT_JOB", bizDate, TriggerType.EVENT, "request-lineage", "trace", Map.of());

    stubPendingCapture(request);
    assertThat(service.captureOrDefer(request))
        .contains(List.of(new DependencyResultReference(
            "UPSTREAM_A",
            JobDependencyRule.SAME_DAY,
            bizDate,
            "job:UPSTREAM_A:" + bizDate,
            7,
            701L,
            6001L,
            Instant.parse("2026-10-13T09:00:00Z"))));
  }

  @Test
  @DisplayName("恢复前必须满足全部上游的 EFFECTIVE partition")
  void shouldRequireEveryUpstreamToBeEffective() {
    LocalDate bizDate = LocalDate.of(2026, 10, 13);
    when(assetPartitionService.isJobPartitionReady("tenant-a", "UPSTREAM_A", bizDate))
        .thenReturn(true);
    when(assetPartitionService.isJobPartitionReady("tenant-a", "UPSTREAM_B", bizDate))
        .thenReturn(false);
    String snapshot = JsonUtils.toJson(
        List.of(JobDependencyRule.sameDay("UPSTREAM_A"), JobDependencyRule.sameDay("UPSTREAM_B")));

    assertThat(service.findNotReadyReason("tenant-a", "EXPORT_JOB", bizDate, snapshot))
        .isEqualTo("UPSTREAM_NOT_EFFECTIVE:UPSTREAM_B");

    when(assetPartitionService.isJobPartitionReady("tenant-a", "UPSTREAM_B", bizDate))
        .thenReturn(true);
    assertThat(service.findNotReadyReason("tenant-a", "EXPORT_JOB", bizDate, snapshot))
        .isNull();
  }

  @Test
  @DisplayName("PREVIOUS_BIZ_DAY 跳过日历节假日并把解析后的业务日固定在等待快照")
  void shouldResolveCalendarAwarePreviousBusinessDateAndSnapshotIt() {
    LocalDate monday = LocalDate.of(2026, 10, 12);
    when(dependencyMapper.selectUpstreamRules("tenant-a", "EXPORT_JOB"))
        .thenReturn(
            List.of(new JobDependencyRule("UPSTREAM_A", JobDependencyRule.PREVIOUS_BIZ_DAY)));
    when(waitingMapper.selectByTenantAndRequestId("tenant-a", "request-prev-day"))
        .thenReturn(null);
    when(dependencyMapper.selectCalendarCodeByTenantAndJob("tenant-a", "EXPORT_JOB"))
        .thenReturn("CAL-A");
    when(businessCalendarMapper.selectFirstByTenantAndCodeAndEnabled("tenant-a", "CAL-A", true))
        .thenReturn(BusinessCalendarEntity.builder()
            .tenantId("tenant-a")
            .calendarCode("CAL-A")
            .enabled(true)
            .build());
    when(planCalendarMapper.selectPreviousBusinessDate(
            "tenant-a", "CAL-A", LocalDate.of(2026, 10, 12)))
        .thenReturn(LocalDate.of(2026, 10, 8));
    when(assetPartitionService.findEffectiveJobPartition(
            "tenant-a", "UPSTREAM_A", LocalDate.of(2026, 10, 8)))
        .thenReturn(Optional.empty());
    LaunchRequest request = new LaunchRequest(
        "tenant-a",
        "EXPORT_JOB",
        monday,
        TriggerType.EVENT,
        "request-prev-day",
        "trace-prev",
        Map.of());

    stubPendingCapture(request);
    assertThat(service.deferIfNotReady(request)).isTrue();

    verify(assetPartitionService)
        .findEffectiveJobPartition("tenant-a", "UPSTREAM_A", LocalDate.of(2026, 10, 8));
    verify(waitingMapper)
        .recordCheck(
            "tenant-a", "request-prev-day", "UPSTREAM_NOT_EFFECTIVE:UPSTREAM_A@2026-10-08");
    ArgumentCaptor<JobDependencyWaitingLaunchEntity> waitingCaptor =
        ArgumentCaptor.forClass(JobDependencyWaitingLaunchEntity.class);
    verify(waitingMapper).insertIfAbsent(waitingCaptor.capture());
    assertThat(waitingCaptor.getValue().dependencySnapshot()).contains("2026-10-08");
  }

  @Test
  @DisplayName("日历工作日覆盖可以将周六作为前一业务日")
  void shouldHonorWeekendWorkdayOverride() {
    LocalDate monday = LocalDate.of(2026, 10, 12);
    LocalDate saturday = LocalDate.of(2026, 10, 10);
    when(dependencyMapper.selectUpstreamRules("tenant-a", "EXPORT_JOB"))
        .thenReturn(
            List.of(new JobDependencyRule("UPSTREAM_A", JobDependencyRule.PREVIOUS_BIZ_DAY)));
    when(dependencyMapper.selectCalendarCodeByTenantAndJob("tenant-a", "EXPORT_JOB"))
        .thenReturn("CAL-A");
    when(businessCalendarMapper.selectFirstByTenantAndCodeAndEnabled("tenant-a", "CAL-A", true))
        .thenReturn(BusinessCalendarEntity.builder().enabled(true).build());
    when(planCalendarMapper.selectPreviousBusinessDate("tenant-a", "CAL-A", monday))
        .thenReturn(saturday);
    when(waitingMapper.selectByTenantAndRequestId("tenant-a", "request-weekend-override"))
        .thenReturn(null);
    when(assetPartitionService.findEffectiveJobPartition("tenant-a", "UPSTREAM_A", saturday))
        .thenReturn(Optional.empty());
    LaunchRequest request = new LaunchRequest(
        "tenant-a",
        "EXPORT_JOB",
        monday,
        TriggerType.EVENT,
        "request-weekend-override",
        "trace-weekend-override",
        Map.of());

    stubPendingCapture(request);
    assertThat(service.deferIfNotReady(request)).isTrue();

    verify(assetPartitionService).findEffectiveJobPartition("tenant-a", "UPSTREAM_A", saturday);
  }

  @ParameterizedTest
  @MethodSource("invalidDependencySnapshots")
  @DisplayName("缺失、损坏或旧格式的等待快照明确阻断恢复,不查询部分上游或将缺项当成无依赖")
  void shouldKeepWaiting_whenPersistedDependencySnapshotIsInvalid(String snapshot) {
    LocalDate bizDate = LocalDate.of(2026, 10, 13);

    assertThat(service.findNotReadyReason("tenant-a", "EXPORT_JOB", bizDate, snapshot))
        .isEqualTo("DEPENDENCY_SNAPSHOT_INVALID");
    assertThat(service.allDependenciesReady("tenant-a", "EXPORT_JOB", bizDate, snapshot))
        .isFalse();
    verifyNoInteractions(assetPartitionService, dependencyMapper, planCalendarMapper);
  }

  @ParameterizedTest
  @MethodSource("invalidDependencySnapshots")
  @DisplayName("已持久等待的损坏快照在再次接纳时保留原记录与等待状态,不给出空的已就绪版本集合")
  void shouldPreserveWaitAndRecordReason_whenCapturingInvalidPersistedSnapshot(String snapshot) {
    LocalDate bizDate = LocalDate.of(2026, 10, 13);
    LaunchRequest request = new LaunchRequest(
        "tenant-a",
        "EXPORT_JOB",
        bizDate,
        TriggerType.EVENT,
        "invalid-snapshot",
        "trace",
        Map.of());
    when(waitingMapper.selectByTenantAndRequestId("tenant-a", "invalid-snapshot"))
        .thenReturn(JobDependencyWaitingLaunchEntity.builder()
            .tenantId("tenant-a")
            .requestId("invalid-snapshot")
            .jobCode("EXPORT_JOB")
            .bizDate(bizDate)
            .dependencySnapshot(snapshot)
            .waitStatus("WAITING")
            .build());

    stubPendingCapture(request);
    assertThat(service.captureOrDefer(request)).isEmpty();

    verify(waitingMapper)
        .recordCheck("tenant-a", "invalid-snapshot", "DEPENDENCY_SNAPSHOT_INVALID");
    verify(triggerRequestMapper)
        .updateAcceptance(
            "tenant-a", "invalid-snapshot", TriggerRequestStatus.WAITING.code(), null);
    verify(waitingMapper, never()).insertIfAbsent(any());
    verifyNoInteractions(assetPartitionService, dependencyMapper, planCalendarMapper);
  }

  @Test
  @DisplayName("合法前一业务日快照恢复时只使用首次解析的日期,不重新查询已变化的日历")
  void shouldKeepFrozenPreviousDate_whenCalendarChangesAfterWait() {
    LocalDate bizDate = LocalDate.of(2026, 10, 13);
    String snapshot = """
        [{"upstreamJobCode":"UPSTREAM_A","bizDateAlignment":"PREVIOUS_BIZ_DAY",
          "upstreamBizDate":"2026-10-08","resolutionError":null}]
        """;
    when(assetPartitionService.isJobPartitionReady(
            "tenant-a", "UPSTREAM_A", LocalDate.of(2026, 10, 8)))
        .thenReturn(true);

    assertThat(service.findNotReadyReason("tenant-a", "EXPORT_JOB", bizDate, snapshot))
        .isNull();

    verify(assetPartitionService)
        .isJobPartitionReady("tenant-a", "UPSTREAM_A", LocalDate.of(2026, 10, 8));
    verifyNoInteractions(dependencyMapper, planCalendarMapper, businessCalendarMapper);
  }

  @Test
  @DisplayName("首次解析日期时已记录的日历不可用原因在恢复时保留")
  void shouldKeepOriginalResolutionError_whenCalendarWasUnavailable() {
    String snapshot = """
        [{"upstreamJobCode":"UPSTREAM_A","bizDateAlignment":"PREVIOUS_BIZ_DAY",
          "upstreamBizDate":null,"resolutionError":"DEPENDENCY_CALENDAR_UNAVAILABLE:CAL-A"}]
        """;

    assertThat(service.findNotReadyReason(
            "tenant-a", "EXPORT_JOB", LocalDate.of(2026, 10, 13), snapshot))
        .isEqualTo("DEPENDENCY_CALENDAR_UNAVAILABLE:CAL-A");
    verifyNoInteractions(assetPartitionService, dependencyMapper, planCalendarMapper);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"missing", "tenant", "job", "date", "dryRun", "LAUNCHED", "REJECTED", "GIVE_UP"})
  @DisplayName("等待准入拒绝不存在、错身份或已终结的原请求，不持久化等待记录")
  void shouldRejectInvalidOriginalRequestBeforePersistingWait(String scenario) {
    LaunchRequest request = admissionRequest();
    TriggerRequestEntity parent = pendingRequest(request);
    switch (scenario) {
      case "tenant" -> parent.setTenantId("tenant-b");
      case "job" -> parent.setJobCode("OTHER_JOB");
      case "date" -> parent.setBizDate(request.bizDate().plusDays(1));
      case "dryRun" -> parent.setDryRun(true);
      case "LAUNCHED", "REJECTED", "GIVE_UP" -> parent.setRequestStatus(scenario);
      default -> {}
    }
    when(triggerRequestMapper.selectByTenantAndRequestIdForUpdate("tenant-a", "admission-request"))
        .thenReturn("missing".equals(scenario) ? null : parent);
    when(dependencyMapper.selectUpstreamRules("tenant-a", "EXPORT_JOB"))
        .thenReturn(List.of(JobDependencyRule.sameDay("UPSTREAM_A")));

    assertThatThrownBy(() -> service.captureOrDefer(request)).isInstanceOf(BizException.class);

    verify(waitingMapper, never()).insertIfAbsent(any());
    verify(waitingMapper, never()).recordCheck(any(), any(), any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"insert", "record", "request"})
  @DisplayName("等待链任何必需写入为零行时抛出冲突，不能当作已安全延期")
  void shouldRejectZeroRowMandatoryWaitWrites(String write) {
    LaunchRequest request = admissionRequest();
    when(triggerRequestMapper.selectByTenantAndRequestIdForUpdate("tenant-a", "admission-request"))
        .thenReturn(pendingRequest(request));
    when(dependencyMapper.selectUpstreamRules("tenant-a", "EXPORT_JOB"))
        .thenReturn(List.of(JobDependencyRule.sameDay("UPSTREAM_A")));
    when(waitingMapper.insertIfAbsent(any())).thenReturn("insert".equals(write) ? 0 : 1);
    when(waitingMapper.recordCheck(any(), any(), any())).thenReturn("record".equals(write) ? 0 : 1);
    when(triggerRequestMapper.updateAcceptance("tenant-a", "admission-request", "WAITING", null))
        .thenReturn("request".equals(write) ? 0 : 1);

    assertThatThrownBy(() -> service.captureOrDefer(request)).isInstanceOf(BizException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"job", "date", "CANCELLED"})
  @DisplayName("同请求号的旧等待必须匹配目标与业务日且仍可等待，不得借用或复活")
  void shouldRejectMismatchedOrTerminalExistingWait(String scenario) {
    LaunchRequest request = admissionRequest();
    when(triggerRequestMapper.selectByTenantAndRequestIdForUpdate("tenant-a", "admission-request"))
        .thenReturn(pendingRequest(request));
    when(waitingMapper.selectByTenantAndRequestId("tenant-a", "admission-request"))
        .thenReturn(JobDependencyWaitingLaunchEntity.builder()
            .tenantId("tenant-a")
            .requestId("admission-request")
            .jobCode("job".equals(scenario) ? "OTHER_JOB" : "EXPORT_JOB")
            .bizDate("date".equals(scenario) ? request.bizDate().plusDays(1) : request.bizDate())
            .waitStatus("CANCELLED".equals(scenario) ? "CANCELLED" : "WAITING")
            .dependencySnapshot(
                "[{\"upstreamJobCode\":\"UPSTREAM_A\",\"bizDateAlignment\":\"SAME_DAY\"}]")
            .build());

    assertThatThrownBy(() -> service.captureOrDefer(request)).isInstanceOf(BizException.class);
    verify(waitingMapper, never()).recordCheck(any(), any(), any());
  }

  private void stubPendingCapture(LaunchRequest request) {
    when(triggerRequestMapper.selectByTenantAndRequestIdForUpdate(
            request.tenantId(), request.requestId()))
        .thenReturn(pendingRequest(request));
    when(waitingMapper.insertIfAbsent(any())).thenReturn(1);
    when(waitingMapper.recordCheck(any(), any(), any())).thenReturn(1);
    when(triggerRequestMapper.updateAcceptance(
            request.tenantId(), request.requestId(), "WAITING", null))
        .thenReturn(1);
  }

  private static LaunchRequest admissionRequest() {
    return new LaunchRequest(
        "tenant-a",
        "EXPORT_JOB",
        LocalDate.of(2026, 10, 18),
        TriggerType.EVENT,
        "admission-request",
        "trace",
        Map.of());
  }

  private static TriggerRequestEntity pendingRequest(LaunchRequest request) {
    TriggerRequestEntity parent = new TriggerRequestEntity();
    parent.setTenantId(request.tenantId());
    parent.setRequestId(request.requestId());
    parent.setJobCode(request.jobCode());
    parent.setWorkflowDefinitionId(request.workflowDefinitionId());
    parent.setBizDate(request.bizDate());
    parent.setDryRun(Boolean.TRUE.equals(request.dryRun()));
    parent.setRequestStatus("ACCEPTED");
    return parent;
  }

  private static Stream<String> invalidDependencySnapshots() {
    return Stream.of(
        null,
        "",
        " ",
        "null",
        "[]",
        "{}",
        "[null]",
        "[1]",
        "[{}]",
        "[\"UPSTREAM_A\"]",
        "[{",
        """
        [{"upstreamJobCode":"UPSTREAM_A"}]
        """,
        """
        [{"upstreamJobCode":"UPSTREAM_A","bizDateAlignment":"UNKNOWN"}]
        """,
        """
        [{"upstreamJobCode":" ","bizDateAlignment":"SAME_DAY"}]
        """,
        """
        [{"upstreamJobCode":123,"bizDateAlignment":"SAME_DAY"}]
        """,
        """
        [{"upstreamJobCode":"UPSTREAM_A","bizDateAlignment":"SAME_DAY","unknown":true}]
        """,
        """
        [{"upstreamJobCode":"UPSTREAM_A","bizDateAlignment":"SAME_DAY"},null]
        """,
        """
        [{"upstreamJobCode":"UPSTREAM_A","bizDateAlignment":"SAME_DAY","resolutionError":true}]
        """,
        """
        [{"upstreamJobCode":"UPSTREAM_A","bizDateAlignment":"SAME_DAY","upstreamBizDate":"2026-10-12"}]
        """,
        """
        [{"upstreamJobCode":"UPSTREAM_A","bizDateAlignment":"PREVIOUS_BIZ_DAY"}]
        """,
        """
        [{"upstreamJobCode":"UPSTREAM_A","bizDateAlignment":"PREVIOUS_BIZ_DAY","upstreamBizDate":123}]
        """,
        """
        [{"upstreamJobCode":"UPSTREAM_A","bizDateAlignment":"PREVIOUS_BIZ_DAY","upstreamBizDate":"invalid"}]
        """,
        """
        [{"upstreamJobCode":"UPSTREAM_A","bizDateAlignment":"PREVIOUS_BIZ_DAY","upstreamBizDate":"2026-10-14"}]
        """,
        """
        [{"upstreamJobCode":"UPSTREAM_A","bizDateAlignment":"PREVIOUS_BIZ_DAY",
          "upstreamBizDate":"2026-10-08","resolutionError":"inconsistent"}]
        """);
  }

  private AssetPartitionSnapshot partition(String jobCode, LocalDate bizDate) {
    return new AssetPartitionSnapshot(
        "tenant-a",
        jobCode,
        bizDate,
        bizDate.toString(),
        "job:" + jobCode + ":" + bizDate,
        "EFFECTIVE",
        7,
        6001L,
        Instant.parse("2026-10-13T09:00:00Z"),
        "INLINE_JSON",
        "{}",
        null,
        701L);
  }
}

package io.github.pinpols.batch.orchestrator.application.service.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.application.service.governance.JobMonitoringAlertEmissionService.Violation;
import io.github.pinpols.batch.orchestrator.config.SlaGovernanceProperties;
import io.github.pinpols.batch.orchestrator.controller.request.AlertEmitRequest;
import io.github.pinpols.batch.orchestrator.domain.query.JobMonitoringRecheckQuery;
import io.github.pinpols.batch.orchestrator.domain.view.JobMonitoringAlertCandidate;
import io.github.pinpols.batch.orchestrator.mapper.JobMonitoringMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("作业时限告警发射")
class JobMonitoringAlertEmissionServiceTest {

  private JobMonitoringMapper mapper;
  private AlertEventService alertEventService;
  private JobMonitoringAlertEmissionService emissionService;

  @BeforeEach
  void setUp() {
    mapper = mock(JobMonitoringMapper.class);
    alertEventService = mock(AlertEventService.class);
    when(mapper.tryAcquireInstanceAdvisoryLock("tenant-a", 42L)).thenReturn(true);
    emissionService = new JobMonitoringAlertEmissionService(
        mapper, alertEventService, new SlaGovernanceProperties());
  }

  @DisplayName("三类违规映射到稳定告警类型并携带实例上下文")
  @Test
  void shouldMapEveryViolationToAlertEvent() {
    JobMonitoringAlertCandidate candidate = candidate();
    when(mapper.selectCandidateForRecheck(any(JobMonitoringRecheckQuery.class)))
        .thenReturn(candidate);
    for (Violation violation : Violation.values()) {
      when(mapper.claimAlert("tenant-a", 42L, violation.name())).thenReturn(1);

      assertThat(emissionService.emitOnce(candidate, violation)).isTrue();
    }

    ArgumentCaptor<AlertEmitRequest> requests = ArgumentCaptor.forClass(AlertEmitRequest.class);
    verify(alertEventService, times(Violation.values().length)).emit(requests.capture());
    assertThat(requests.getAllValues())
        .extracting(AlertEmitRequest::alertType)
        .containsExactly(
            "JOB_RUNNING_TOO_LONG",
            "JOB_NOT_STARTED_BY_DEADLINE",
            "JOB_NOT_COMPLETED_BY_DEADLINE",
            "JOB_FINAL_PARTITION_FAILURE");
    assertThat(requests.getAllValues()).allSatisfy(request -> {
      assertThat(request.tenantId()).isEqualTo("tenant-a");
      assertThat(request.resourceKey()).isEqualTo("42");
      assertThat(request.traceId()).isEqualTo("trace-42");
      Map<String, Object> details =
          JsonUtils.fromJson(request.detailJson(), new TypeReference<>() {});
      assertThat(details)
          .containsEntry("jobInstanceId", "42")
          .containsEntry("bizDate", "2026-10-11")
          .containsEntry("workflowRunId", "84")
          .containsEntry("nodeCode", "IMPORT_NODE");
    });
  }

  @DisplayName("重复 claim 不重复写告警事件")
  @Test
  void shouldNotEmitWhenAnotherScanAlreadyClaimedViolation() {
    when(mapper.selectCandidateForRecheck(any(JobMonitoringRecheckQuery.class)))
        .thenReturn(candidate());
    when(mapper.claimAlert("tenant-a", 42L, Violation.RUNNING_TOO_LONG.name())).thenReturn(0);

    assertThat(emissionService.emitOnce(candidate(), Violation.RUNNING_TOO_LONG))
        .isFalse();

    verify(alertEventService, never()).emit(any());
  }

  @DisplayName("策略指定的告警级别写入告警事件")
  @Test
  void shouldUseConfiguredSeverity() {
    when(mapper.selectCandidateForRecheck(any(JobMonitoringRecheckQuery.class)))
        .thenReturn(candidate("CRITICAL"));
    when(mapper.claimAlert("tenant-a", 42L, Violation.RUNNING_TOO_LONG.name())).thenReturn(1);

    emissionService.emitOnce(candidate("CRITICAL"), Violation.RUNNING_TOO_LONG);

    ArgumentCaptor<AlertEmitRequest> request = ArgumentCaptor.forClass(AlertEmitRequest.class);
    verify(alertEventService).emit(request.capture());
    assertThat(request.getValue().severity()).isEqualTo("CRITICAL");
  }

  @Test
  @DisplayName("扫描后条件失效时不声明幂等键且不发送告警")
  void shouldDiscardStaleScannedCandidateBeforeClaim() {

    assertThat(emissionService.emitOnce(candidate(), Violation.RUNNING_TOO_LONG))
        .isFalse();

    verify(mapper, never()).claimAlert(any(), any(), any());
    verify(alertEventService, never()).emit(any());
  }

  @Test
  @DisplayName("发送使用重新读取的策略级别而非扫描快照")
  void shouldEmitUsingRecheckedPolicy() {
    when(mapper.selectCandidateForRecheck(any(JobMonitoringRecheckQuery.class)))
        .thenReturn(candidate("CRITICAL"));
    when(mapper.claimAlert("tenant-a", 42L, Violation.RUNNING_TOO_LONG.name())).thenReturn(1);

    assertThat(emissionService.emitOnce(candidate("WARN"), Violation.RUNNING_TOO_LONG))
        .isTrue();

    ArgumentCaptor<AlertEmitRequest> request = ArgumentCaptor.forClass(AlertEmitRequest.class);
    verify(alertEventService).emit(request.capture());
    assertThat(request.getValue().severity()).isEqualTo("CRITICAL");
  }

  @Test
  @DisplayName("实例事务繁忙时跳过本轮，不查询和声明告警幂等键")
  void shouldSkipBusyInstanceWithoutClaiming() {
    when(mapper.tryAcquireInstanceAdvisoryLock("tenant-a", 42L)).thenReturn(false);

    assertThat(emissionService.emitOnce(candidate(), Violation.RUNNING_TOO_LONG))
        .isFalse();

    verify(mapper, never()).selectCandidateForRecheck(any());
    verify(mapper, never()).claimAlert(any(), any(), any());
    verify(alertEventService, never()).emit(any());
  }

  @Test
  @DisplayName("未建实例的 Workflow 子作业按触发请求锁定并使用稳定资源身份")
  void shouldEmitWorkflowChildRequestWithoutClaimingAnInstance() {
    JobMonitoringAlertCandidate scanned = workflowChildRequestCandidate();
    when(mapper.tryAcquireRequestAdvisoryLock("tenant-a", 101L)).thenReturn(true);
    when(mapper.selectCandidateForRecheck(any(JobMonitoringRecheckQuery.class)))
        .thenReturn(scanned);

    assertThat(emissionService.emitOnce(scanned, Violation.NOT_STARTED)).isTrue();

    ArgumentCaptor<JobMonitoringRecheckQuery> query =
        ArgumentCaptor.forClass(JobMonitoringRecheckQuery.class);
    verify(mapper).selectCandidateForRecheck(query.capture());
    assertThat(query.getValue().jobInstanceId()).isNull();
    assertThat(query.getValue().triggerRequestId()).isEqualTo(101L);
    verify(mapper, never()).tryAcquireInstanceAdvisoryLock(any(), any());
    verify(mapper, never()).claimAlert(any(), any(), any());

    ArgumentCaptor<AlertEmitRequest> request = ArgumentCaptor.forClass(AlertEmitRequest.class);
    verify(alertEventService).emit(request.capture());
    assertThat(request.getValue().resourceKey()).isEqualTo("workflow_job_request:101");
    Map<String, Object> details =
        JsonUtils.fromJson(request.getValue().detailJson(), new TypeReference<>() {});
    assertThat(details)
        .containsEntry("triggerRequestId", "101")
        .containsEntry("workflowRunId", "84")
        .containsEntry("monitoringResourceKey", "workflow_job_request:101");
  }

  private static JobMonitoringAlertCandidate candidate() {
    return candidate("WARN");
  }

  private static JobMonitoringAlertCandidate candidate(String severity) {
    Instant now = Instant.now();
    return new JobMonitoringAlertCandidate(
        "tenant-a",
        42L,
        LocalDate.parse("2026-10-11"),
        "JOB_A",
        "instance-42",
        "RUNNING",
        "SCHEDULED",
        "trace-42",
        now.minusSeconds(120),
        now.minusSeconds(100),
        null,
        now.minusSeconds(50),
        30,
        2,
        severity,
        84L,
        "IMPORT_NODE");
  }

  private static JobMonitoringAlertCandidate workflowChildRequestCandidate() {
    return JobMonitoringAlertCandidate.builder()
        .tenantId("tenant-a")
        .bizDate(LocalDate.parse("2026-10-11"))
        .jobCode("CHILD_JOB")
        .instanceStatus("ACCEPTED")
        .triggerType("EVENT")
        .traceId("trace-child")
        .createdAt(Instant.parse("2026-10-11T00:00:00Z"))
        .monitoringDeadlineAt(Instant.parse("2026-10-11T00:00:30Z"))
        .thresholdSeconds(30)
        .severity("WARN")
        .workflowRunId(84L)
        .nodeCode("CHILD_NODE")
        .triggerRequestId(101L)
        .build();
  }
}

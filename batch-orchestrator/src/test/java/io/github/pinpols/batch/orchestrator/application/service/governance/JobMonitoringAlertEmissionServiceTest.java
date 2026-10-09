package io.github.pinpols.batch.orchestrator.application.service.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.orchestrator.application.service.governance.JobMonitoringAlertEmissionService.Violation;
import io.github.pinpols.batch.orchestrator.controller.request.AlertEmitRequest;
import io.github.pinpols.batch.orchestrator.domain.entity.JobMonitoringAlertCandidate;
import io.github.pinpols.batch.orchestrator.mapper.JobMonitoringMapper;
import java.time.Instant;
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
    emissionService = new JobMonitoringAlertEmissionService(mapper, alertEventService);
  }

  @DisplayName("三类违规映射到稳定告警类型并携带实例上下文")
  @Test
  void shouldMapEveryViolationToAlertEvent() {
    JobMonitoringAlertCandidate candidate = candidate();
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
    });
  }

  @DisplayName("重复 claim 不重复写告警事件")
  @Test
  void shouldNotEmitWhenAnotherScanAlreadyClaimedViolation() {
    when(mapper.claimAlert("tenant-a", 42L, Violation.RUNNING_TOO_LONG.name())).thenReturn(0);

    assertThat(emissionService.emitOnce(candidate(), Violation.RUNNING_TOO_LONG))
        .isFalse();

    verify(alertEventService, never()).emit(any());
  }

  @DisplayName("策略指定的告警级别写入告警事件")
  @Test
  void shouldUseConfiguredSeverity() {
    when(mapper.claimAlert("tenant-a", 42L, Violation.RUNNING_TOO_LONG.name())).thenReturn(1);

    emissionService.emitOnce(candidate("CRITICAL"), Violation.RUNNING_TOO_LONG);

    ArgumentCaptor<AlertEmitRequest> request = ArgumentCaptor.forClass(AlertEmitRequest.class);
    verify(alertEventService).emit(request.capture());
    assertThat(request.getValue().severity()).isEqualTo("CRITICAL");
  }

  private static JobMonitoringAlertCandidate candidate() {
    return candidate("WARN");
  }

  private static JobMonitoringAlertCandidate candidate(String severity) {
    Instant now = Instant.now();
    return new JobMonitoringAlertCandidate(
        "tenant-a",
        42L,
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
        severity);
  }
}

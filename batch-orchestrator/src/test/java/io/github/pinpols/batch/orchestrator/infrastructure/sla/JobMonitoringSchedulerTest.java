package io.github.pinpols.batch.orchestrator.infrastructure.sla;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.orchestrator.application.service.governance.JobMonitoringAlertEmissionService;
import io.github.pinpols.batch.orchestrator.application.service.governance.JobMonitoringAlertEmissionService.Violation;
import io.github.pinpols.batch.orchestrator.config.SlaGovernanceProperties;
import io.github.pinpols.batch.orchestrator.domain.entity.JobMonitoringAlertCandidate;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.mapper.JobMonitoringMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("作业监控旁路扫描隔离")
class JobMonitoringSchedulerTest {

  private JobMonitoringMapper mapper;
  private JobMonitoringAlertEmissionService emissionService;
  private OrchestratorGracefulShutdown gracefulShutdown;
  private SimpleMeterRegistry meterRegistry;
  private SlaGovernanceProperties properties;
  private JobMonitoringScheduler scheduler;

  @BeforeEach
  void setUp() {
    mapper = mock(JobMonitoringMapper.class);
    emissionService = mock(JobMonitoringAlertEmissionService.class);
    gracefulShutdown = mock(OrchestratorGracefulShutdown.class);
    meterRegistry = new SimpleMeterRegistry();
    properties = new SlaGovernanceProperties();
    properties.getJobMonitoring().setBatchSize(20);
    scheduler = new JobMonitoringScheduler(
        mapper, emissionService, gracefulShutdown, meterRegistry, properties);
    when(gracefulShutdown.isDraining()).thenReturn(false);
  }

  @Test
  @DisplayName("禁用或 drain 时不查询实例也不声明告警")
  void shouldSkipDatabaseWorkWhenDisabledOrDraining() {
    properties.getJobMonitoring().setEnabled(false);

    scheduler.scan();

    verify(mapper, never()).selectRunningTooLong(anyInt());
    verify(emissionService, never()).emitOnce(any(), any());
  }

  @Test
  @DisplayName("一个候选类别查询失败不阻止其他类别扫描")
  void shouldContinueOtherCategoriesWhenOneQueryFails() {
    when(mapper.selectRunningTooLong(anyInt()))
        .thenThrow(new IllegalStateException("query failed"));
    when(mapper.selectNotStarted(anyInt())).thenReturn(List.of());
    when(mapper.selectNotCompletedByDeadline(anyInt())).thenReturn(List.of());
    when(mapper.selectFinalPartitionFailures(anyInt(), anyInt())).thenReturn(List.of());

    scheduler.scan();

    verify(mapper).selectNotStarted(20);
    verify(mapper).selectNotCompletedByDeadline(20);
    verify(mapper).selectFinalPartitionFailures(20, 3600);
    assertThat(meterRegistry
            .get("batch.job.monitoring.scan.failures.total")
            .tag("violation_type", Violation.RUNNING_TOO_LONG.name())
            .counter()
            .count())
        .isEqualTo(1.0d);
    assertThat(meterRegistry
            .get("batch.job.monitoring.query.duration")
            .tag("violation_type", Violation.RUNNING_TOO_LONG.name())
            .tag("outcome", "failure")
            .timer()
            .count())
        .isEqualTo(1L);
  }

  @Test
  @DisplayName("单条告警失败不阻止同批后续告警")
  void shouldContinueCandidatesWhenEmissionFails() {
    JobMonitoringAlertCandidate first = candidate(1L);
    JobMonitoringAlertCandidate second = candidate(2L);
    when(mapper.selectRunningTooLong(anyInt())).thenReturn(List.of(first, second));
    when(mapper.selectNotStarted(anyInt())).thenReturn(List.of());
    when(mapper.selectNotCompletedByDeadline(anyInt())).thenReturn(List.of());
    when(emissionService.emitOnce(first, Violation.RUNNING_TOO_LONG))
        .thenThrow(new IllegalStateException("alert persistence failed"));

    scheduler.scan();

    verify(emissionService).emitOnce(first, Violation.RUNNING_TOO_LONG);
    verify(emissionService).emitOnce(second, Violation.RUNNING_TOO_LONG);
    assertThat(meterRegistry
            .get("batch.job.monitoring.alert.failures.total")
            .tag("violation_type", Violation.RUNNING_TOO_LONG.name())
            .counter()
            .count())
        .isEqualTo(1.0d);
  }

  @Test
  @DisplayName("三类时限违规都扫描并交给告警发射器")
  void shouldScanAndEmitEveryViolationCategory() {
    JobMonitoringAlertCandidate running = candidate(11L);
    JobMonitoringAlertCandidate notStarted = candidate(12L);
    JobMonitoringAlertCandidate notCompleted = candidate(13L);
    when(mapper.selectRunningTooLong(20)).thenReturn(List.of(running));
    when(mapper.selectNotStarted(20)).thenReturn(List.of(notStarted));
    when(mapper.selectNotCompletedByDeadline(20)).thenReturn(List.of(notCompleted));
    JobMonitoringAlertCandidate failedPartition = candidate(14L);
    when(mapper.selectFinalPartitionFailures(20, 3600)).thenReturn(List.of(failedPartition));

    scheduler.scan();

    verify(emissionService).emitOnce(running, Violation.RUNNING_TOO_LONG);
    verify(emissionService).emitOnce(notStarted, Violation.NOT_STARTED);
    verify(emissionService).emitOnce(notCompleted, Violation.NOT_COMPLETED_BY_DEADLINE);
    verify(emissionService).emitOnce(failedPartition, Violation.FAILED_PARTITION);
  }

  private static JobMonitoringAlertCandidate candidate(long id) {
    Instant now = Instant.now();
    return new JobMonitoringAlertCandidate(
        "tenant-a",
        id,
        "JOB_A",
        "instance-" + id,
        "RUNNING",
        "SCHEDULED",
        "trace-" + id,
        now.minusSeconds(120),
        now.minusSeconds(60),
        null,
        now.minusSeconds(10),
        50,
        2);
  }
}

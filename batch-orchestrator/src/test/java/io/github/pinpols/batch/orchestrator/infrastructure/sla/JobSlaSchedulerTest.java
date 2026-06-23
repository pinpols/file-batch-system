package io.github.pinpols.batch.orchestrator.infrastructure.sla;

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

import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.orchestrator.application.service.governance.AlertEventService;
import io.github.pinpols.batch.orchestrator.config.SlaGovernanceProperties;
import io.github.pinpols.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import io.github.pinpols.batch.orchestrator.controller.request.AlertEmitRequest;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.mapper.JobExecutionLogMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 单元测试：{@link JobSlaScheduler#scanViolations()} 各路径。 */
class JobSlaSchedulerTest {

  private JobInstanceMapper jobInstanceMapper;
  private JobExecutionLogMapper jobExecutionLogMapper;
  private SlaGovernanceProperties properties;
  private BatchOrchestratorGovernanceProperties governance;
  private AlertEventService alertEventService;
  private OrchestratorGracefulShutdown gracefulShutdown;
  private JobSlaScheduler scheduler;

  @BeforeEach
  void setUp() {
    jobInstanceMapper = mock(JobInstanceMapper.class);
    jobExecutionLogMapper = mock(JobExecutionLogMapper.class);
    alertEventService = mock(AlertEventService.class);
    governance = mock(BatchOrchestratorGovernanceProperties.class);
    gracefulShutdown = mock(OrchestratorGracefulShutdown.class);
    properties = new SlaGovernanceProperties();
    properties.setEnabled(true);
    properties.setBatchSize(200);
    when(governance.sla()).thenReturn(properties);

    scheduler =
        new JobSlaScheduler(
            jobInstanceMapper,
            jobExecutionLogMapper,
            governance,
            new SimpleMeterRegistry(),
            alertEventService,
            gracefulShutdown);
    scheduler.initializeMeters();
  }

  @Test
  void shouldSkipScanWhenDisabled() {
    properties.setEnabled(false);

    scheduler.scanViolations();

    verify(jobInstanceMapper, never()).selectSlaViolationCandidates(anyInt());
  }

  @Test
  void shouldDoNothingWhenNoCandidates() {
    when(jobInstanceMapper.countSlaViolationCandidates()).thenReturn(0L);
    when(jobInstanceMapper.selectSlaViolationCandidates(anyInt())).thenReturn(List.of());

    scheduler.scanViolations();

    verify(jobExecutionLogMapper, never()).insert(any());
    verify(alertEventService, never()).emit(any());
  }

  @Test
  void shouldSkipNullCandidateInList() {
    when(jobInstanceMapper.countSlaViolationCandidates()).thenReturn(0L);
    when(jobInstanceMapper.selectSlaViolationCandidates(anyInt()))
        .thenReturn(Arrays.asList(null, null));

    scheduler.scanViolations();

    verify(jobExecutionLogMapper, never()).insert(any());
  }

  @Test
  void shouldSkipCandidateWhenMarkSlaAlertedReturnZero() {
    JobInstanceEntity candidate =
        slaCandidate("t1", 1L, BatchDateTimeSupport.utcNow().minusSeconds(60));
    when(jobInstanceMapper.countSlaViolationCandidates()).thenReturn(1L);
    when(jobInstanceMapper.selectSlaViolationCandidates(anyInt())).thenReturn(List.of(candidate));
    when(jobInstanceMapper.markSlaAlerted(anyString(), anyLong(), any())).thenReturn(0);

    scheduler.scanViolations();

    verify(jobExecutionLogMapper, never()).insert(any());
    verify(alertEventService, never()).emit(any());
  }

  @Test
  void shouldLogAndEmitAlertWhenDeadlineExceeded() {
    JobInstanceEntity candidate =
        slaCandidate("t1", 2L, BatchDateTimeSupport.utcNow().minusSeconds(3600));
    when(jobInstanceMapper.countSlaViolationCandidates()).thenReturn(1L);
    when(jobInstanceMapper.selectSlaViolationCandidates(anyInt())).thenReturn(List.of(candidate));
    when(jobInstanceMapper.markSlaAlerted(eq("t1"), eq(2L), any())).thenReturn(1);
    when(jobExecutionLogMapper.insert(any())).thenReturn(1);

    scheduler.scanViolations();

    verify(jobExecutionLogMapper, times(2)).insert(any());
    verify(alertEventService).emit(any(AlertEmitRequest.class));
  }

  @Test
  void shouldLogAndEmitAlertForExpectedDurationViolation() {
    JobInstanceEntity candidate = new JobInstanceEntity();
    candidate.setTenantId("t1");
    candidate.setId(3L);
    candidate.setInstanceNo("INST-003");
    candidate.setJobCode("TEST_JOB");
    candidate.setInstanceStatus("RUNNING");
    candidate.setTraceId("trace-003");
    // 无截止时间，但 expectedDurationSeconds 已超出
    candidate.setExpectedDurationSeconds(60);
    candidate.setStartedAt(
        BatchDateTimeSupport.utcNow().minusSeconds(3600)); // started 1h ago, expected 60s

    when(jobInstanceMapper.countSlaViolationCandidates()).thenReturn(1L);
    when(jobInstanceMapper.selectSlaViolationCandidates(anyInt())).thenReturn(List.of(candidate));
    when(jobInstanceMapper.markSlaAlerted(eq("t1"), eq(3L), any())).thenReturn(1);
    when(jobExecutionLogMapper.insert(any())).thenReturn(1);

    scheduler.scanViolations();

    verify(jobExecutionLogMapper, times(2)).insert(any());
    verify(alertEventService).emit(any(AlertEmitRequest.class));
  }

  @Test
  void shouldProcessMultipleCandidates() {
    JobInstanceEntity c1 = slaCandidate("t1", 10L, BatchDateTimeSupport.utcNow().minusSeconds(100));
    JobInstanceEntity c2 = slaCandidate("t1", 11L, BatchDateTimeSupport.utcNow().minusSeconds(200));
    when(jobInstanceMapper.countSlaViolationCandidates()).thenReturn(2L);
    when(jobInstanceMapper.selectSlaViolationCandidates(anyInt())).thenReturn(List.of(c1, c2));
    when(jobInstanceMapper.markSlaAlerted(anyString(), anyLong(), any())).thenReturn(1);
    when(jobExecutionLogMapper.insert(any())).thenReturn(1);

    scheduler.scanViolations();

    verify(jobExecutionLogMapper, times(4)).insert(any());
    verify(alertEventService, times(2)).emit(any());
  }

  @Test
  void shouldEmitEscalatedAlertForLongRunningAlertedInstance() {
    properties.setEscalationDelaySeconds(300L);
    properties.setEscalationSeverity("ERROR");
    when(jobInstanceMapper.countSlaViolationCandidates()).thenReturn(0L);
    when(jobInstanceMapper.selectSlaViolationCandidates(anyInt())).thenReturn(List.of());

    JobInstanceEntity stale = slaCandidate("t1", 99L, BatchDateTimeSupport.utcNow());
    stale.setSlaAlertedAt(BatchDateTimeSupport.utcNow().minusSeconds(900));
    when(jobInstanceMapper.countSlaEscalationCandidates(any())).thenReturn(1L);
    when(jobInstanceMapper.selectSlaEscalationCandidates(any(), anyInt()))
        .thenReturn(List.of(stale));

    scheduler.scanViolations();

    ArgumentCaptor<AlertEmitRequest> captor = ArgumentCaptor.forClass(AlertEmitRequest.class);
    verify(alertEventService).emit(captor.capture());
    assertThat(captor.getValue().alertType()).isEqualTo("JOB_SLA_VIOLATION_ESCALATED");
    assertThat(captor.getValue().severity()).isEqualTo("ERROR");
  }

  @Test
  void shouldSkipEscalationWhenDelayIsZero() {
    properties.setEscalationDelaySeconds(0L);
    when(jobInstanceMapper.countSlaViolationCandidates()).thenReturn(0L);
    when(jobInstanceMapper.selectSlaViolationCandidates(anyInt())).thenReturn(List.of());

    scheduler.scanViolations();

    verify(jobInstanceMapper, never()).selectSlaEscalationCandidates(any(), anyInt());
    verify(alertEventService, never()).emit(any());
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static JobInstanceEntity slaCandidate(String tenantId, Long id, Instant deadlineAt) {
    JobInstanceEntity e = new JobInstanceEntity();
    e.setTenantId(tenantId);
    e.setId(id);
    e.setInstanceNo("INST-" + id);
    e.setJobCode("JOB-" + id);
    e.setInstanceStatus("RUNNING");
    e.setTraceId("trace-" + id);
    e.setDeadlineAt(deadlineAt);
    return e;
  }
}

package io.github.pinpols.batch.orchestrator.infrastructure.sla;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
import io.github.pinpols.batch.orchestrator.mapper.JobMonitoringMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 单元测试：{@link JobSlaScheduler#scanViolations()} 各路径。 */
@DisplayName("作业服务级别协议超时扫描的候选处理与告警升级行为")
class JobSlaSchedulerTest {

  private JobMonitoringMapper jobMonitoringMapper;
  private SlaGovernanceProperties properties;
  private BatchOrchestratorGovernanceProperties governance;
  private AlertEventService alertEventService;
  private JobSlaViolationProcessor violationProcessor;
  private OrchestratorGracefulShutdown gracefulShutdown;
  private JobSlaScheduler scheduler;

  @BeforeEach
  void setUp() {
    jobMonitoringMapper = mock(JobMonitoringMapper.class);
    alertEventService = mock(AlertEventService.class);
    violationProcessor = mock(JobSlaViolationProcessor.class);
    governance = mock(BatchOrchestratorGovernanceProperties.class);
    gracefulShutdown = mock(OrchestratorGracefulShutdown.class);
    properties = new SlaGovernanceProperties();
    properties.setEnabled(true);
    properties.setBatchSize(200);
    when(governance.sla()).thenReturn(properties);

    scheduler = new JobSlaScheduler(
        jobMonitoringMapper,
        governance,
        new SimpleMeterRegistry(),
        alertEventService,
        violationProcessor,
        gracefulShutdown);
    scheduler.initializeMeters();
  }

  @Test
  @DisplayName("扫描开关关闭时跳过本轮扫描,不读取任何违规候选")
  void shouldSkipScanWhenDisabled() {
    properties.setEnabled(false);

    scheduler.scanViolations();

    verify(jobMonitoringMapper, never()).selectSlaViolationCandidates(anyInt());
  }

  @Test
  @DisplayName("没有违规候选时既不写执行日志也不发送告警")
  void shouldDoNothingWhenNoCandidates() {
    when(jobMonitoringMapper.countSlaViolationCandidates()).thenReturn(0L);
    when(jobMonitoringMapper.selectSlaViolationCandidates(anyInt())).thenReturn(List.of());

    scheduler.scanViolations();

    verify(violationProcessor, never()).process(any(), any());
    verify(alertEventService, never()).emit(any());
  }

  @Test
  @DisplayName("候选列表混入空元素时逐个跳过且不写执行日志")
  void shouldSkipNullCandidateInList() {
    when(jobMonitoringMapper.countSlaViolationCandidates()).thenReturn(0L);
    when(jobMonitoringMapper.selectSlaViolationCandidates(anyInt()))
        .thenReturn(Arrays.asList(null, null));

    scheduler.scanViolations();

    verify(violationProcessor, never()).process(any(), any());
  }

  @Test
  @DisplayName("单个候选的持久化处理未成功时不重复处理其他副作用")
  void shouldSkipCandidateWhenMarkSlaAlertedReturnZero() {
    JobInstanceEntity candidate =
        slaCandidate("t1", 1L, BatchDateTimeSupport.utcNow().minusSeconds(60));
    when(jobMonitoringMapper.countSlaViolationCandidates()).thenReturn(1L);
    when(jobMonitoringMapper.selectSlaViolationCandidates(anyInt())).thenReturn(List.of(candidate));
    when(violationProcessor.process(eq(candidate), any())).thenReturn(false);

    scheduler.scanViolations();

    verify(violationProcessor).process(eq(candidate), any());
    verify(alertEventService, never()).emit(any());
  }

  @Test
  @DisplayName("候选已超过截止时间时标记成功并写入两条执行日志,同时发送一次告警")
  void shouldLogAndEmitAlertWhenDeadlineExceeded() {
    JobInstanceEntity candidate =
        slaCandidate("t1", 2L, BatchDateTimeSupport.utcNow().minusSeconds(3600));
    when(jobMonitoringMapper.countSlaViolationCandidates()).thenReturn(1L);
    when(jobMonitoringMapper.selectSlaViolationCandidates(anyInt())).thenReturn(List.of(candidate));
    when(violationProcessor.process(eq(candidate), any())).thenReturn(true);

    scheduler.scanViolations();

    verify(violationProcessor).process(eq(candidate), any());
  }

  @Test
  @DisplayName("无截止时间但实际运行时长超过预期耗时上限的候选同样写两条日志并发一次告警")
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

    when(jobMonitoringMapper.countSlaViolationCandidates()).thenReturn(1L);
    when(jobMonitoringMapper.selectSlaViolationCandidates(anyInt())).thenReturn(List.of(candidate));
    when(violationProcessor.process(eq(candidate), any())).thenReturn(true);

    scheduler.scanViolations();

    verify(violationProcessor).process(eq(candidate), any());
  }

  @Test
  @DisplayName("多个违规候选逐个处理,每个候选各产生两条日志与一次告警")
  void shouldProcessMultipleCandidates() {
    JobInstanceEntity c1 = slaCandidate("t1", 10L, BatchDateTimeSupport.utcNow().minusSeconds(100));
    JobInstanceEntity c2 = slaCandidate("t1", 11L, BatchDateTimeSupport.utcNow().minusSeconds(200));
    when(jobMonitoringMapper.countSlaViolationCandidates()).thenReturn(2L);
    when(jobMonitoringMapper.selectSlaViolationCandidates(anyInt())).thenReturn(List.of(c1, c2));
    when(violationProcessor.process(any(), any())).thenReturn(true);

    scheduler.scanViolations();

    verify(violationProcessor, times(2)).process(any(), any());
  }

  @Test
  @DisplayName("单个候选事务失败时继续处理本批其他候选")
  void shouldContinueAfterCandidatePersistenceFailure() {
    JobInstanceEntity failed = slaCandidate("t1", 20L, BatchDateTimeSupport.utcNow());
    JobInstanceEntity healthy = slaCandidate("t1", 21L, BatchDateTimeSupport.utcNow());
    when(jobMonitoringMapper.countSlaViolationCandidates()).thenReturn(2L);
    when(jobMonitoringMapper.selectSlaViolationCandidates(anyInt()))
        .thenReturn(List.of(failed, healthy));
    when(violationProcessor.process(eq(failed), any()))
        .thenThrow(new IllegalStateException("injected persistence failure"));
    when(violationProcessor.process(eq(healthy), any())).thenReturn(true);

    scheduler.scanViolations();

    verify(violationProcessor).process(eq(failed), any());
    verify(violationProcessor).process(eq(healthy), any());
  }

  @Test
  @DisplayName("已告警实例超过升级延迟后发送升级类型告警,并带上配置的严重级别")
  void shouldEmitEscalatedAlertForLongRunningAlertedInstance() {
    properties.setEscalationDelaySeconds(300L);
    properties.setEscalationSeverity("ERROR");
    when(jobMonitoringMapper.countSlaViolationCandidates()).thenReturn(0L);
    when(jobMonitoringMapper.selectSlaViolationCandidates(anyInt())).thenReturn(List.of());

    JobInstanceEntity stale = slaCandidate("t1", 99L, BatchDateTimeSupport.utcNow());
    stale.setSlaAlertedAt(BatchDateTimeSupport.utcNow().minusSeconds(900));
    when(jobMonitoringMapper.countSlaEscalationCandidates(any())).thenReturn(1L);
    when(jobMonitoringMapper.selectSlaEscalationCandidates(any(), anyInt()))
        .thenReturn(List.of(stale));

    scheduler.scanViolations();

    ArgumentCaptor<AlertEmitRequest> captor = ArgumentCaptor.forClass(AlertEmitRequest.class);
    verify(alertEventService).emit(captor.capture());
    assertThat(captor.getValue().alertType()).isEqualTo("JOB_SLA_VIOLATION_ESCALATED");
    assertThat(captor.getValue().severity()).isEqualTo("ERROR");
  }

  @Test
  @DisplayName("升级延迟配置为零时不查询升级候选也不发送告警")
  void shouldSkipEscalationWhenDelayIsZero() {
    properties.setEscalationDelaySeconds(0L);
    when(jobMonitoringMapper.countSlaViolationCandidates()).thenReturn(0L);
    when(jobMonitoringMapper.selectSlaViolationCandidates(anyInt())).thenReturn(List.of());

    scheduler.scanViolations();

    verify(jobMonitoringMapper, never()).selectSlaEscalationCandidates(any(), anyInt());
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

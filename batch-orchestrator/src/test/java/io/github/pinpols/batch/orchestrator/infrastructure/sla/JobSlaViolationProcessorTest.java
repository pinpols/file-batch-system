package io.github.pinpols.batch.orchestrator.infrastructure.sla;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.application.service.governance.AlertEventService;
import io.github.pinpols.batch.orchestrator.domain.command.AlertEmitCommand;
import io.github.pinpols.batch.orchestrator.domain.entity.JobExecutionLogEntity;
import io.github.pinpols.batch.orchestrator.domain.view.JobSlaCandidate;
import io.github.pinpols.batch.orchestrator.mapper.JobExecutionLogMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobMonitoringMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("SLA 违约候选处理器: 原子副作用与业务原因")
class JobSlaViolationProcessorTest {

  private JobMonitoringMapper jobMonitoringMapper;
  private JobExecutionLogMapper jobExecutionLogMapper;
  private AlertEventService alertEventService;
  private JobSlaViolationProcessor processor;

  @BeforeEach
  void setUp() {
    jobMonitoringMapper = mock(JobMonitoringMapper.class);
    jobExecutionLogMapper = mock(JobExecutionLogMapper.class);
    alertEventService = mock(AlertEventService.class);
    processor =
        new JobSlaViolationProcessor(jobMonitoringMapper, jobExecutionLogMapper, alertEventService);
  }

  @Test
  @DisplayName("截止时间超限时记录 DEADLINE_EXCEEDED 并写审计和告警")
  void shouldRecordDeadlineViolation() {
    Instant now = Instant.parse("2026-10-11T12:00:00Z");
    JobSlaCandidate candidate = candidate();
    candidate.setDeadlineAt(now.minusSeconds(90));
    when(jobMonitoringMapper.markSlaAlerted("tenant-a", 42L, now)).thenReturn(1);
    when(jobMonitoringMapper.selectSlaViolationCandidate("tenant-a", 42L)).thenReturn(candidate);

    assertThat(processor.process(candidate, now)).isTrue();

    ArgumentCaptor<JobExecutionLogEntity> logs =
        ArgumentCaptor.forClass(JobExecutionLogEntity.class);
    verify(jobExecutionLogMapper, times(2)).insert(logs.capture());
    assertThat(logs.getAllValues().getFirst().getMessage())
        .isEqualTo("job SLA violated: DEADLINE_EXCEEDED");
    Map<String, Object> alertExtra =
        JsonUtils.fromJson(logs.getAllValues().getFirst().getExtraJson(), new TypeReference<>() {});
    assertThat(alertExtra).containsEntry("violationReason", "DEADLINE_EXCEEDED");
    assertThat(logs.getAllValues().get(1).getMessage()).isEqualTo("SLA_ALERTED_AT_UPDATED");

    ArgumentCaptor<AlertEmitCommand> alert = ArgumentCaptor.forClass(AlertEmitCommand.class);
    verify(alertEventService).emit(alert.capture());
    assertThat(alert.getValue().alertType()).isEqualTo("JOB_SLA_VIOLATION");
    assertThat(alert.getValue().title()).isEqualTo("job SLA violated: DEADLINE_EXCEEDED");
    Map<String, Object> alertDetail =
        JsonUtils.fromJson(alert.getValue().detailJson(), new TypeReference<>() {});
    assertThat(alertDetail)
        .containsEntry("jobInstanceId", "42")
        .containsEntry("bizDate", "2026-10-11")
        .containsEntry("workflowRunId", "84")
        .containsEntry("nodeCode", "IMPORT_NODE");
  }

  @Test
  @DisplayName("运行时长超出预期时记录 EXPECTED_DURATION_EXCEEDED")
  void shouldRecordExpectedDurationViolation() {
    Instant now = Instant.parse("2026-10-11T12:00:00Z");
    JobSlaCandidate candidate = candidate();
    candidate.setStartedAt(now.minusSeconds(120));
    candidate.setExecutionStartedAt(now.minusSeconds(90));
    candidate.setExpectedDurationSeconds(60);
    when(jobMonitoringMapper.markSlaAlerted("tenant-a", 42L, now)).thenReturn(1);
    when(jobMonitoringMapper.selectSlaViolationCandidate("tenant-a", 42L)).thenReturn(candidate);

    assertThat(processor.process(candidate, now)).isTrue();

    ArgumentCaptor<AlertEmitCommand> alert = ArgumentCaptor.forClass(AlertEmitCommand.class);
    verify(alertEventService).emit(alert.capture());
    Map<String, Object> detail =
        JsonUtils.fromJson(alert.getValue().detailJson(), new TypeReference<>() {});
    assertThat(detail).containsEntry("violationReason", "EXPECTED_DURATION_EXCEEDED");
    assertThat(detail).containsEntry("durationDelaySeconds", 30);
  }

  @Test
  @DisplayName("已终态作业的完成超时按真实 finished_at 计算")
  void shouldMeasureTerminalDeadlineOverrunAtFinishedAt() {
    Instant now = Instant.parse("2026-10-11T12:00:00Z");
    Instant finishedAt = now.minusSeconds(20);
    JobSlaCandidate candidate = candidate();
    candidate.setInstanceStatus("SUCCESS");
    candidate.setFinishedAt(finishedAt);
    candidate.setDeadlineAt(now.minusSeconds(80));
    when(jobMonitoringMapper.markSlaAlerted("tenant-a", 42L, now)).thenReturn(1);
    when(jobMonitoringMapper.selectSlaViolationCandidate("tenant-a", 42L)).thenReturn(candidate);

    assertThat(processor.process(candidate, now)).isTrue();

    ArgumentCaptor<AlertEmitCommand> emitted = ArgumentCaptor.forClass(AlertEmitCommand.class);
    verify(alertEventService).emit(emitted.capture());
    Map<String, Object> detail =
        JsonUtils.fromJson(emitted.getValue().detailJson(), new TypeReference<>() {});
    assertThat(detail).containsEntry("deadlineDelaySeconds", 60);
    assertThat(((Number) detail.get("violationEvaluatedAt")).longValue())
        .isEqualTo(finishedAt.getEpochSecond());
    assertThat(((Number) detail.get("violationDetectedAt")).longValue())
        .isEqualTo(now.getEpochSecond());
  }

  @Test
  @DisplayName("已终态作业的运行时长超限按真实 finished_at 计算")
  void shouldMeasureTerminalRuntimeOverrunAtFinishedAt() {
    Instant now = Instant.parse("2026-10-11T12:00:00Z");
    Instant finishedAt = now.minusSeconds(30);
    JobSlaCandidate candidate = candidate();
    candidate.setInstanceStatus("FAILED");
    candidate.setExecutionStartedAt(now.minusSeconds(180));
    candidate.setExpectedDurationSeconds(60);
    candidate.setFinishedAt(finishedAt);
    when(jobMonitoringMapper.markSlaAlerted("tenant-a", 42L, now)).thenReturn(1);
    when(jobMonitoringMapper.selectSlaViolationCandidate("tenant-a", 42L)).thenReturn(candidate);

    assertThat(processor.process(candidate, now)).isTrue();

    ArgumentCaptor<AlertEmitCommand> emitted = ArgumentCaptor.forClass(AlertEmitCommand.class);
    verify(alertEventService).emit(emitted.capture());
    Map<String, Object> detail =
        JsonUtils.fromJson(emitted.getValue().detailJson(), new TypeReference<>() {});
    assertThat(detail).containsEntry("durationDelaySeconds", 90);
    assertThat(((Number) detail.get("violationEvaluatedAt")).longValue())
        .isEqualTo(finishedAt.getEpochSecond());
  }

  @Test
  @DisplayName("并发扫描未抢到 SLA 标记时不生成日志或告警")
  void shouldSkipSideEffectsWhenSlaMarkerWasClaimed() {
    Instant now = Instant.parse("2026-10-11T12:00:00Z");
    when(jobMonitoringMapper.markSlaAlerted("tenant-a", 42L, now)).thenReturn(0);

    assertThat(processor.process(candidate(), now)).isFalse();

    verify(jobExecutionLogMapper, never()).insert(any());
    verify(alertEventService, never()).emit(any());
  }

  @Test
  @DisplayName("升级前当前实例不再满足策略时跳过外部告警")
  void shouldSkipEscalationWhenCurrentInstanceIsNoLongerEligible() {
    Instant now = Instant.parse("2026-10-11T12:00:00Z");
    assertThat(processor.processEscalation(candidate(), now, 300, "ERROR")).isFalse();
    verify(alertEventService, never()).emit(any());
  }

  @Test
  @DisplayName("升级事件使用事务内锁定的当前 Job 状态与配置严重级别")
  void shouldBuildEscalationFromLockedCurrentJob() {
    Instant now = Instant.parse("2026-10-11T12:00:00Z");
    JobSlaCandidate current = candidate();
    current.setInstanceStatus("READY");
    current.setSlaAlertedAt(now.minusSeconds(900));
    when(jobMonitoringMapper.selectSlaEscalationCandidateForUpdate(
            "tenant-a", 42L, now.minusSeconds(300)))
        .thenReturn(current);
    assertThat(processor.processEscalation(candidate(), now, 300, "ERROR")).isTrue();
    ArgumentCaptor<AlertEmitCommand> emitted = ArgumentCaptor.forClass(AlertEmitCommand.class);
    verify(alertEventService).emit(emitted.capture());
    assertThat(emitted.getValue().alertType()).isEqualTo("JOB_SLA_VIOLATION_ESCALATED");
    assertThat(emitted.getValue().severity()).isEqualTo("ERROR");
    assertThat(emitted.getValue().resourceKey()).isEqualTo("42");
    Map<String, Object> extra =
        JsonUtils.fromJson(emitted.getValue().detailJson(), new TypeReference<>() {});
    assertThat(extra)
        .containsEntry("instanceStatus", "READY")
        .containsEntry("alertedElapsedSeconds", 900)
        .containsEntry("workflowRunId", "84")
        .containsEntry("nodeCode", "IMPORT_NODE");
  }

  private static JobSlaCandidate candidate() {
    JobSlaCandidate candidate = new JobSlaCandidate();
    candidate.setTenantId("tenant-a");
    candidate.setId(42L);
    candidate.setInstanceNo("INST-42");
    candidate.setJobCode("JOB-A");
    candidate.setInstanceStatus("RUNNING");
    candidate.setTraceId("trace-42");
    candidate.setBizDate(LocalDate.parse("2026-10-11"));
    candidate.setParentWorkflowRunId(84L);
    candidate.setParentNodeCode("IMPORT_NODE");
    return candidate;
  }
}

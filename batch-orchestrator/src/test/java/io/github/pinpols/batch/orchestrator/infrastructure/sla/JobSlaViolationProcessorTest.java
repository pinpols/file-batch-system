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
import io.github.pinpols.batch.orchestrator.controller.request.AlertEmitRequest;
import io.github.pinpols.batch.orchestrator.domain.entity.JobExecutionLogEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.mapper.JobExecutionLogMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobMonitoringMapper;
import java.time.Instant;
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
    JobInstanceEntity candidate = candidate();
    candidate.setDeadlineAt(now.minusSeconds(90));
    when(jobMonitoringMapper.markSlaAlerted("tenant-a", 42L, now)).thenReturn(1);

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

    ArgumentCaptor<AlertEmitRequest> alert = ArgumentCaptor.forClass(AlertEmitRequest.class);
    verify(alertEventService).emit(alert.capture());
    assertThat(alert.getValue().alertType()).isEqualTo("JOB_SLA_VIOLATION");
    assertThat(alert.getValue().title()).isEqualTo("job SLA violated: DEADLINE_EXCEEDED");
  }

  @Test
  @DisplayName("运行时长超出预期时记录 EXPECTED_DURATION_EXCEEDED")
  void shouldRecordExpectedDurationViolation() {
    Instant now = Instant.parse("2026-10-11T12:00:00Z");
    JobInstanceEntity candidate = candidate();
    candidate.setStartedAt(now.minusSeconds(120));
    candidate.setExpectedDurationSeconds(60);
    when(jobMonitoringMapper.markSlaAlerted("tenant-a", 42L, now)).thenReturn(1);

    assertThat(processor.process(candidate, now)).isTrue();

    ArgumentCaptor<AlertEmitRequest> alert = ArgumentCaptor.forClass(AlertEmitRequest.class);
    verify(alertEventService).emit(alert.capture());
    Map<String, Object> detail =
        JsonUtils.fromJson(alert.getValue().detailJson(), new TypeReference<>() {});
    assertThat(detail).containsEntry("violationReason", "EXPECTED_DURATION_EXCEEDED");
    assertThat(detail).containsEntry("durationDelaySeconds", 60);
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

  private static JobInstanceEntity candidate() {
    JobInstanceEntity candidate = new JobInstanceEntity();
    candidate.setTenantId("tenant-a");
    candidate.setId(42L);
    candidate.setInstanceNo("INST-42");
    candidate.setJobCode("JOB-A");
    candidate.setInstanceStatus("RUNNING");
    candidate.setTraceId("trace-42");
    return candidate;
  }
}

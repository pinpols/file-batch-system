package io.github.pinpols.batch.orchestrator.application.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import io.github.pinpols.batch.common.event.DomainEvent;
import io.github.pinpols.batch.common.event.DomainEventPublisher;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowRunMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("Job 终态 Outbox:持久快照、独立实体身份与修正事实")
@ExtendWith(MockitoExtension.class)
class JobTerminalOutboxServiceTest {
  @Mock
  private JobInstanceMapper jobs;

  @Mock
  private WorkflowRunMapper workflows;

  @Mock
  private DomainEventPublisher events;

  private JobTerminalOutboxService writer;

  @ParameterizedTest
  @ValueSource(
      strings = {
        "SUCCESS",
        "FAILED",
        "PARTIAL_FAILED",
        "CANCELLED",
        "TERMINATED",
        "SUCCESS_DRY_RUN",
        "FAILED_DRY_RUN"
      })
  @DisplayName("原 Job 终态保留状态、版本、业务日与所属根,不转成 Workflow 事件")
  @SuppressWarnings("unchecked")
  void shouldKeepJobIdentityForEveryTerminalStatus(String status) {
    var job = job(status);
    job.setOperatorId("alice");
    when(jobs.selectById("ta", 10L)).thenReturn(job);
    WorkflowRunEntity owner = new WorkflowRunEntity();
    owner.setId(99L);
    when(workflows.selectByNodeJobInstanceId("ta", 10L)).thenReturn(owner);
    writer.writeTerminalEvent("ta", 10L);
    ArgumentCaptor<DomainEvent> event = ArgumentCaptor.forClass(DomainEvent.class);
    verify(events).publish(event.capture());
    assertThat(event.getValue().aggregateType()).isEqualTo("JOB_INSTANCE");
    assertThat(event.getValue().eventType()).isEqualTo("JOB_TERMINAL");
    assertThat(event.getValue().eventKey()).isEqualTo("ta:job:10:terminal:" + status);
    assertThat((Map<String, Object>) event.getValue().payload())
        .containsEntry("operatorId", "alice");
    assertThat((Map<String, Object>) event.getValue().payload())
        .containsAllEntriesOf(Map.of(
            "jobInstanceId",
            10L,
            "workflowRunId",
            99L,
            "instanceStatus",
            status,
            "terminalVersion",
            5L,
            "bizDate",
            "2026-10-19",
            "finishedAt",
            "2026-10-20T01:00:00Z"));
  }

  @Test
  @DisplayName("非终态无事件,缺失终态快照拒绝提交")
  void shouldRejectMissingSnapshotAndIgnoreActiveJob() {
    when(jobs.selectById("ta", 10L)).thenReturn(job("RUNNING"));
    writer.writeTerminalEvent("ta", 10L);
    verifyNoInteractions(events);
    var missing = job("SUCCESS");
    missing.setFinishedAt(null);
    when(jobs.selectById("ta", 10L)).thenReturn(missing);
    assertThatThrownBy(() -> writer.writeTerminalEvent("ta", 10L))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(events);
  }

  private JobInstanceEntity job(String status) {
    var job = new JobInstanceEntity();
    job.setId(10L);
    job.setTenantId("ta");
    job.setInstanceStatus(status);
    job.setJobCode("IMPORT");
    job.setVersion(5L);
    job.setBizDate(LocalDate.of(2026, 10, 19));
    job.setFinishedAt(Instant.parse("2026-10-20T01:00:00Z"));
    return job;
  }

  @BeforeEach
  void initializeSubject() {
    writer = new JobTerminalOutboxService(jobs, workflows, events);
  }
}

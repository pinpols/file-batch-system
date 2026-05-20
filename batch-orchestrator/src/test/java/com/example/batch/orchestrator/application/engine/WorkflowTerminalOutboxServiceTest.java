package com.example.batch.orchestrator.application.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.example.batch.common.enums.OutboxPublishStatus;
import com.example.batch.common.enums.WorkflowRunStatus;
import com.example.batch.common.persistence.entity.WorkflowRunEntity;
import com.example.batch.orchestrator.domain.entity.OutboxEventEntity;
import com.example.batch.orchestrator.mapper.OutboxEventMapper;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * 守护 workflow_run 终态 outbox 写入:
 *
 * <ul>
 *   <li>isTerminal 白名单: SUCCESS / FAILED / TERMINATED / *_DRY_RUN 才算终态
 *   <li>非终态状态不会触发 outbox 写入
 *   <li>幂等键、event_type、aggregate_type、payload 内容齐全
 * </ul>
 */
class WorkflowTerminalOutboxServiceTest {

  @Mock private OutboxEventMapper outboxEventMapper;

  private WorkflowTerminalOutboxService service;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    service = new WorkflowTerminalOutboxService(outboxEventMapper);
  }

  // ===== isTerminal =====

  @Test
  @DisplayName("isTerminal: SUCCESS / FAILED / TERMINATED / *_DRY_RUN 都是终态")
  void terminal_whitelist() {
    assertThat(WorkflowTerminalOutboxService.isTerminal(WorkflowRunStatus.SUCCESS.code())).isTrue();
    assertThat(WorkflowTerminalOutboxService.isTerminal(WorkflowRunStatus.FAILED.code())).isTrue();
    assertThat(WorkflowTerminalOutboxService.isTerminal(WorkflowRunStatus.TERMINATED.code()))
        .isTrue();
    assertThat(WorkflowTerminalOutboxService.isTerminal(WorkflowRunStatus.SUCCESS_DRY_RUN.code()))
        .isTrue();
    assertThat(WorkflowTerminalOutboxService.isTerminal(WorkflowRunStatus.FAILED_DRY_RUN.code()))
        .isTrue();
  }

  @Test
  @DisplayName("isTerminal: RUNNING / CREATED / null / 未知 → 非终态")
  void non_terminal() {
    assertThat(WorkflowTerminalOutboxService.isTerminal(WorkflowRunStatus.RUNNING.code()))
        .isFalse();
    assertThat(WorkflowTerminalOutboxService.isTerminal(WorkflowRunStatus.CREATED.code()))
        .isFalse();
    assertThat(WorkflowTerminalOutboxService.isTerminal(null)).isFalse();
    assertThat(WorkflowTerminalOutboxService.isTerminal("UNKNOWN")).isFalse();
  }

  // ===== writeTerminalEvent =====

  @Test
  @DisplayName("workflowRun=null → 不写表")
  void skip_when_workflow_run_null() {
    service.writeTerminalEvent(null, WorkflowRunStatus.SUCCESS.code(), Instant.now());
    verify(outboxEventMapper, never()).insert(any());
  }

  @Test
  @DisplayName("非终态 status → 不写表(白名单守护)")
  void skip_when_status_non_terminal() {
    service.writeTerminalEvent(workflowRun(), WorkflowRunStatus.RUNNING.code(), Instant.now());
    verify(outboxEventMapper, never()).insert(any());
  }

  @Test
  @DisplayName("SUCCESS 终态 → 落 outbox + payload/aggregate/event_type 完整")
  void writes_outbox_for_success() {
    WorkflowRunEntity run = workflowRun();
    Instant finished = Instant.parse("2026-05-20T10:00:00Z");
    service.writeTerminalEvent(run, WorkflowRunStatus.SUCCESS.code(), finished);

    ArgumentCaptor<OutboxEventEntity> cap = ArgumentCaptor.forClass(OutboxEventEntity.class);
    verify(outboxEventMapper).insert(cap.capture());
    OutboxEventEntity event = cap.getValue();
    assertThat(event.getAggregateType()).isEqualTo("WORKFLOW_RUN");
    assertThat(event.getEventType()).isEqualTo("WORKFLOW_TERMINAL");
    assertThat(event.getAggregateId()).isEqualTo(100L);
    assertThat(event.getEventKey()).isEqualTo("ta:workflow:100:terminal");
    assertThat(event.getPublishStatus()).isEqualTo(OutboxPublishStatus.NEW.code());
    assertThat(event.getPublishAttempt()).isZero();
    assertThat(event.getTenantId()).isEqualTo("ta");
    assertThat(event.getPayloadJson())
        .contains("\"runStatus\":\"SUCCESS\"")
        .contains("\"workflowRunId\":100")
        .contains("\"finishedAt\":\"2026-05-20T10:00:00Z\"");
  }

  @Test
  @DisplayName("finishedAt=null → payload.finishedAt 为 null,不抛")
  void handles_null_finished_at() {
    WorkflowRunEntity run = workflowRun();
    service.writeTerminalEvent(run, WorkflowRunStatus.FAILED.code(), null);

    ArgumentCaptor<OutboxEventEntity> cap = ArgumentCaptor.forClass(OutboxEventEntity.class);
    verify(outboxEventMapper).insert(cap.capture());
    assertThat(cap.getValue().getPayloadJson()).contains("\"finishedAt\":null");
  }

  // ===== fixtures =====

  private WorkflowRunEntity workflowRun() {
    WorkflowRunEntity run = new WorkflowRunEntity();
    run.setId(100L);
    run.setTenantId("ta");
    run.setWorkflowDefinitionId(50L);
    run.setRelatedJobInstanceId(200L);
    run.setTraceId("trace-xx");
    return run;
  }
}

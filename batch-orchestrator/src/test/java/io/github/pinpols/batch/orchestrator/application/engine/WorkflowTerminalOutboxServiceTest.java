package io.github.pinpols.batch.orchestrator.application.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.github.pinpols.batch.common.enums.WorkflowRunStatus;
import io.github.pinpols.batch.common.event.DomainEvent;
import io.github.pinpols.batch.common.event.DomainEventPublisher;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.orchestrator.infrastructure.lineage.OpenLineageEmitter;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 守护 workflow_run 终态 outbox 写入:
 *
 * <ul>
 *   <li>isTerminal 白名单: SUCCESS / FAILED / TERMINATED / *_DRY_RUN 才算终态
 *   <li>非终态状态不会触发 outbox 写入
 *   <li>幂等键、event_type、aggregate_type、payload 内容齐全
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class WorkflowTerminalOutboxServiceTest {

  @Mock private DomainEventPublisher domainEventPublisher;

  @Mock private OpenLineageEmitter openLineageEmitter;

  private WorkflowTerminalOutboxService service;

  @BeforeEach
  void setUp() {
    service = new WorkflowTerminalOutboxService(domainEventPublisher, openLineageEmitter);
  }

  // ===== isTerminal =====

  @Test
  @DisplayName("isTerminal: SUCCESS / FAILED / TERMINATED / *_DRY_RUN 都是终态")
  void terminalWhitelist() {
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
  void nonTerminal() {
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
  void skipWhenWorkflowRunNull() {
    service.writeTerminalEvent(null, WorkflowRunStatus.SUCCESS.code(), Instant.now());
    verify(domainEventPublisher, never()).publish(any());
  }

  @Test
  @DisplayName("非终态 status → 不写表(白名单守护)")
  void skipWhenStatusNonTerminal() {
    service.writeTerminalEvent(workflowRun(), WorkflowRunStatus.RUNNING.code(), Instant.now());
    verify(domainEventPublisher, never()).publish(any());
  }

  @Test
  @DisplayName("SUCCESS 终态 → 落 outbox + payload/aggregate/event_type 完整")
  void writesOutboxForSuccess() {
    WorkflowRunEntity run = workflowRun();
    Instant finished = Instant.parse("2026-05-20T10:00:00Z");
    service.writeTerminalEvent(run, WorkflowRunStatus.SUCCESS.code(), finished);

    ArgumentCaptor<DomainEvent> cap = ArgumentCaptor.forClass(DomainEvent.class);
    verify(domainEventPublisher).publish(cap.capture());
    DomainEvent event = cap.getValue();
    assertThat(event.aggregateType()).isEqualTo("WORKFLOW_RUN");
    assertThat(event.eventType()).isEqualTo("WORKFLOW_TERMINAL");
    assertThat(event.aggregateId()).isEqualTo(100L);
    assertThat(event.eventKey()).isEqualTo("ta:workflow:100:terminal");
    assertThat(event.tenantId()).isEqualTo("ta");
    assertThat(event.payload())
        .containsEntry("runStatus", "SUCCESS")
        .containsEntry("workflowRunId", 100L)
        .containsEntry("finishedAt", "2026-05-20T10:00:00Z");
  }

  @Test
  @DisplayName("finishedAt=null → payload.finishedAt 为 null,不抛")
  void handlesNullFinishedAt() {
    WorkflowRunEntity run = workflowRun();
    service.writeTerminalEvent(run, WorkflowRunStatus.FAILED.code(), null);

    ArgumentCaptor<DomainEvent> cap = ArgumentCaptor.forClass(DomainEvent.class);
    verify(domainEventPublisher).publish(cap.capture());
    assertThat(cap.getValue().payload()).containsEntry("finishedAt", null);
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

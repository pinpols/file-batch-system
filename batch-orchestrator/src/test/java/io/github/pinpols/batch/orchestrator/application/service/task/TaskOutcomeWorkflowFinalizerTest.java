package io.github.pinpols.batch.orchestrator.application.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.orchestrator.application.engine.WorkflowTerminalOutboxService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.OrchestratorWorkflowMappers;
import io.github.pinpols.batch.orchestrator.domain.command.TaskOutcomeCommand;
import io.github.pinpols.batch.orchestrator.domain.statemachine.LifecycleEventMapper;
import io.github.pinpols.batch.orchestrator.domain.statemachine.StateTransition;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowRunMapper;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
@DisplayName("Workflow 结果收口诊断区分相同终态、暂停、冲突和缺失，不重复发终态事件")
class TaskOutcomeWorkflowFinalizerTest {
  @Test
  @DisplayName("DAG 已提交相同成功终态时，重复收口不误报取消竞争，也不重复发事件")
  void shouldTreatSameTerminalAsIdempotent_whenDagAlreadyCompleted(CapturedOutput output) {
    Fixture fixture = fixture("SUCCESS", "SUCCESS", 0);
    fixture.service().finalizeWorkflow(fixture.context());
    assertThat(output.getAll()).doesNotContain(" WARN ");
    verify(fixture.mapper()).selectById("tenant-a", 77L);
    verify(fixture.outbox(), never()).writeTerminalEvent(any(), any(), any());
  }

  @Test
  @DisplayName("暂停期间保留已暂停父运行，不把在途结果当成终态竞争")
  void shouldPreservePauseWithoutFalseWarning_whenInflightOutcomeArrives(CapturedOutput output) {
    Fixture fixture = fixture("PAUSED", "RUNNING", 0);
    fixture.service().finalizeWorkflow(fixture.context());
    assertThat(output.getAll()).doesNotContain(" WARN ");
    verify(fixture.outbox(), never()).writeTerminalEvent(any(), any(), any());
  }

  @Test
  @DisplayName("实际终态与候选状态不一致时仍告警，明确实际状态且不重复派发")
  void shouldWarnOnDifferentPersistedTerminal_whenTransitionIsRejected(CapturedOutput output) {
    Fixture fixture = fixture("FAILED", "SUCCESS", 0);
    fixture.service().finalizeWorkflow(fixture.context());
    assertThat(output.getAll()).contains(" WARN ").contains("observed=FAILED");
    verify(fixture.outbox(), never()).writeTerminalEvent(any(), any(), any());
  }

  @Test
  @DisplayName("父运行不存在时仍告警，不把缺失误当成已成功")
  void shouldWarnOnMissingParent_whenTransitionIsRejected(CapturedOutput output) {
    Fixture fixture = fixture(null, "SUCCESS", 0);
    fixture.service().finalizeWorkflow(fixture.context());
    assertThat(output.getAll()).contains(" WARN ").contains("observed=MISSING");
    verify(fixture.outbox(), never()).writeTerminalEvent(any(), any(), any());
  }

  @Test
  @DisplayName("有效终态转换沿用一次更新一次终态事件，不增加额外状态读取")
  void shouldKeepSuccessfulTransitionUnchanged_whenCompareAndSetWins() {
    Fixture fixture = fixture("SUCCESS", "SUCCESS", 1);
    fixture.service().finalizeWorkflow(fixture.context());
    verify(fixture.outbox()).writeTerminalEvent(any(), any(), any());
    verify(fixture.mapper(), never()).selectById(anyString(), anyLong());
  }

  @SuppressWarnings("unchecked")
  private Fixture fixture(String persistedStatus, String target, int updated) {
    WorkflowRunMapper mapper = mock(WorkflowRunMapper.class);
    WorkflowTerminalOutboxService outbox = mock(WorkflowTerminalOutboxService.class);
    LifecycleEventMapper<Object> lifecycle = mock(LifecycleEventMapper.class);
    when(mapper.updateStatus(any())).thenReturn(updated);
    if (persistedStatus != null) {
      WorkflowRunEntity persisted = new WorkflowRunEntity();
      persisted.setId(77L);
      persisted.setRunStatus(persistedStatus);
      when(mapper.selectById("tenant-a", 77L)).thenReturn(persisted);
    }
    when(lifecycle.map(any(), anyString()))
        .thenReturn(new StateTransition("RUNNING", target, target));
    TaskOutcomeWorkflowFinalizer service = new TaskOutcomeWorkflowFinalizer(
        new OrchestratorWorkflowMappers(null, mapper, null), lifecycle, outbox);
    WorkflowRunEntity snapshot = new WorkflowRunEntity();
    snapshot.setId(77L);
    snapshot.setTenantId("tenant-a");
    snapshot.setRunStatus("RUNNING");
    TaskOutcomeCommand command = TaskOutcomeCommand.builder().tenantId("tenant-a").build();
    return new Fixture(
        service,
        mapper,
        outbox,
        TaskOutcomeWorkflowFinalizer.Context.builder()
            .command(command)
            .workflowRun(snapshot)
            .allPartitionsFinished(true)
            .dagContinues("RUNNING".equals(target))
            .activeNodes(Set.of())
            .currentNodeCode("JOB")
            .finishedAt(Instant.parse("2026-10-16T00:00:00Z"))
            .build());
  }

  private record Fixture(
      TaskOutcomeWorkflowFinalizer service,
      WorkflowRunMapper mapper,
      WorkflowTerminalOutboxService outbox,
      TaskOutcomeWorkflowFinalizer.Context context) {}
}

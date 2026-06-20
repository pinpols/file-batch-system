package com.example.batch.orchestrator.application.service.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.exception.BizException;
import com.example.batch.common.persistence.entity.WorkflowRunEntity;
import com.example.batch.orchestrator.application.engine.WorkflowTerminalOutboxService;
import com.example.batch.orchestrator.application.service.governance.AlertEventService;
import com.example.batch.orchestrator.application.service.task.OrchestratorJobMappers;
import com.example.batch.orchestrator.domain.param.UpdateWorkflowRunStatusParam;
import com.example.batch.orchestrator.mapper.JobExecutionLogMapper;
import com.example.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import com.example.batch.orchestrator.mapper.WorkflowRunMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
@DisplayName("workflow_run pause/resume(ADR-044)")
class WorkflowRunPauseResumeTest {

  @Mock private WorkflowRunMapper workflowRunMapper;
  @Mock private WorkflowNodeRunMapper workflowNodeRunMapper;
  @Mock private WorkflowTerminalOutboxService workflowTerminalOutboxService;
  @Mock private WorkflowDagService workflowDagService;
  @Mock private ObjectProvider<WorkflowNodeDispatchService> dispatchProvider;
  @Mock private OrchestratorJobMappers jobMappers;
  @Mock private ObjectProvider<JobExecutionLogMapper> logProvider;
  @Mock private ObjectProvider<AlertEventService> alertProvider;

  private WorkflowRunManagementApplicationService service() {
    return new WorkflowRunManagementApplicationService(
        workflowRunMapper,
        workflowNodeRunMapper,
        workflowTerminalOutboxService,
        workflowDagService,
        dispatchProvider,
        jobMappers,
        logProvider,
        alertProvider);
  }

  private static WorkflowRunEntity run(String status) {
    WorkflowRunEntity e = new WorkflowRunEntity();
    e.setId(7L);
    e.setTenantId("t1");
    e.setRunStatus(status);
    e.setCurrentNodeCode("N2");
    return e;
  }

  @Test
  @DisplayName("RUNNING 可暂停 → PAUSED")
  void shouldPause_whenRunning() {
    when(workflowRunMapper.selectById("t1", 7L)).thenReturn(run("RUNNING"));
    when(workflowRunMapper.updateStatus(any(UpdateWorkflowRunStatusParam.class))).thenReturn(1);

    assertThat(service().pause("t1", 7L)).containsEntry("status", "PAUSED");
    verify(workflowRunMapper).updateStatus(any(UpdateWorkflowRunStatusParam.class));
  }

  @Test
  @DisplayName("非 RUNNING 暂停 → STATE_CONFLICT,不写库")
  void shouldReject_whenPauseNonRunning() {
    when(workflowRunMapper.selectById("t1", 7L)).thenReturn(run("SUCCESS"));

    assertThatThrownBy(() -> service().pause("t1", 7L)).isInstanceOf(BizException.class);
    verify(workflowRunMapper, never()).updateStatus(any(UpdateWorkflowRunStatusParam.class));
  }

  @Test
  @DisplayName("PAUSED 可恢复 → RUNNING")
  void shouldResume_whenPaused() {
    when(workflowRunMapper.selectById("t1", 7L)).thenReturn(run("PAUSED"));
    when(workflowRunMapper.updateStatus(any(UpdateWorkflowRunStatusParam.class))).thenReturn(1);

    assertThat(service().resume("t1", 7L)).containsEntry("status", "RUNNING");
  }

  @Test
  @DisplayName("非 PAUSED 恢复 → STATE_CONFLICT")
  void shouldReject_whenResumeNonPaused() {
    when(workflowRunMapper.selectById("t1", 7L)).thenReturn(run("RUNNING"));

    assertThatThrownBy(() -> service().resume("t1", 7L)).isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("CAS 落空(并发改动)→ STATE_CONFLICT")
  void shouldThrow_whenCasMissed() {
    when(workflowRunMapper.selectById("t1", 7L)).thenReturn(run("RUNNING"));
    when(workflowRunMapper.updateStatus(any(UpdateWorkflowRunStatusParam.class))).thenReturn(0);

    assertThatThrownBy(() -> service().pause("t1", 7L)).isInstanceOf(BizException.class);
  }
}

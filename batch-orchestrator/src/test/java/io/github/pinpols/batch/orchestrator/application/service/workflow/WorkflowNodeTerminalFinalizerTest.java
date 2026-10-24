package io.github.pinpols.batch.orchestrator.application.service.workflow;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.orchestrator.application.engine.WorkflowTerminalOutboxService;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import io.github.pinpols.batch.orchestrator.domain.param.UpdateWorkflowRunStatusParam;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowRunMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("Workflow 终态归并使用最新节点尝试")
class WorkflowNodeTerminalFinalizerTest {

  @Mock
  private WorkflowNodeRunMapper nodeRunMapper;

  @Mock
  private WorkflowRunMapper workflowRunMapper;

  @Mock
  private WorkflowTerminalOutboxService terminalOutboxService;

  private WorkflowNodeTerminalFinalizer finalizer;

  @BeforeEach
  void setUp() {
    finalizer = new WorkflowNodeTerminalFinalizer(
        new OrchestratorWorkflowMappers(
            org.mockito.Mockito.mock(WorkflowNodeMapper.class), workflowRunMapper, nodeRunMapper),
        terminalOutboxService);
  }

  @DisplayName("最新成功节点尝试优先于旧失败记录")
  @Test
  void shouldLatestSuccessfulNodeRunWinsOverOlderFailedAttempt() {
    WorkflowRunEntity workflowRun = workflowRun();
    when(nodeRunMapper.selectByWorkflowRunId(12L))
        .thenReturn(List.of(
            nodeRun("WAIT_FILE", 1, "FAILED"),
            nodeRun("WAIT_FILE", 2, "SUCCESS"),
            nodeRun("END", 1, "SUCCESS")));
    when(workflowRunMapper.updateStatus(any(UpdateWorkflowRunStatusParam.class)))
        .thenReturn(1);

    finalizer.finalizeIfAllNodesTerminal(workflowRun);

    verify(workflowRunMapper)
        .updateStatus(
            org.mockito.ArgumentMatchers.argThat(param -> "SUCCESS".equals(param.getRunStatus())
                && param.getExpectedStatuses().contains("RUNNING")));
    verify(terminalOutboxService)
        .writeTerminalEvent(
            any(WorkflowRunEntity.class), org.mockito.ArgumentMatchers.eq("SUCCESS"), any());
  }

  @DisplayName("最新节点仍活跃时不提前终结 Workflow")
  @Test
  void shouldActiveLatestNodePreventsPrematureWorkflowFinalization() {
    when(nodeRunMapper.selectByWorkflowRunId(12L))
        .thenReturn(List.of(nodeRun("WAIT_FILE", 1, "SUCCESS"), nodeRun("TASK", 1, "RUNNING")));

    finalizer.finalizeIfAllNodesTerminal(workflowRun());

    verify(workflowRunMapper, never()).updateStatus(any());
    verify(terminalOutboxService, never()).writeTerminalEvent(any(), any(), any());
  }

  private WorkflowRunEntity workflowRun() {
    WorkflowRunEntity workflowRun = new WorkflowRunEntity();
    workflowRun.setId(12L);
    workflowRun.setTenantId("tenant");
    workflowRun.setRunStatus("RUNNING");
    return workflowRun;
  }

  private WorkflowNodeRunEntity nodeRun(String code, int runSeq, String status) {
    WorkflowNodeRunEntity nodeRun = new WorkflowNodeRunEntity();
    nodeRun.setNodeCode(code);
    nodeRun.setRunSeq(runSeq);
    nodeRun.setNodeStatus(status);
    return nodeRun;
  }
}

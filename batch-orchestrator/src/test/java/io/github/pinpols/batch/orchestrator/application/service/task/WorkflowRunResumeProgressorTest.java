package io.github.pinpols.batch.orchestrator.application.service.task;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.WorkflowNodeRunStatus;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.common.service.SecretPayloadProtector;
import io.github.pinpols.batch.orchestrator.application.engine.CountContinuityOutboxService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.OrchestratorWorkflowMappers;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowDagService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowNodeDispatchService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowNodeTerminalFinalizer;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowRunMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Workflow 恢复时按已持久化节点结果推进 DAG")
class WorkflowRunResumeProgressorTest {

  @Test
  @DisplayName("失败节点恢复时派发 FAILURE 和 ALWAYS 后继")
  void shouldDispatchFailureAndAlwaysSuccessors_whenSourceNodeFailed() {
    Long workflowRunId = 701L;
    String tenantId = "tenant-a";
    WorkflowRunEntity run = new WorkflowRunEntity();
    run.setId(workflowRunId);
    run.setTenantId(tenantId);
    run.setTraceId("trace-701");

    WorkflowNodeRunEntity failedSource = new WorkflowNodeRunEntity();
    failedSource.setWorkflowRunId(workflowRunId);
    failedSource.setNodeCode("PROCESS");
    failedSource.setNodeStatus(WorkflowNodeRunStatus.FAILED.code());
    failedSource.setRunSeq(1);

    WorkflowRunMapper workflowRunMapper = mock(WorkflowRunMapper.class);
    when(workflowRunMapper.selectById(tenantId, workflowRunId)).thenReturn(run);
    WorkflowNodeRunMapper nodeRunMapper = mock(WorkflowNodeRunMapper.class);
    when(nodeRunMapper.selectByWorkflowRunId(workflowRunId)).thenReturn(List.of(failedSource));
    OrchestratorWorkflowMappers workflowMappers = new OrchestratorWorkflowMappers(
        mock(WorkflowNodeMapper.class), workflowRunMapper, nodeRunMapper);

    WorkflowDagService dagService = mock(WorkflowDagService.class);
    WorkflowDagService.DagNodeResolution failureHandler =
        new WorkflowDagService.DagNodeResolution("FAILURE_HANDLER", "JOB");
    WorkflowDagService.DagNodeResolution alwaysHandler =
        new WorkflowDagService.DagNodeResolution("ALWAYS_HANDLER", "JOB");
    when(dagService.resolveNextNodesForRun(run, "PROCESS", false, null))
        .thenReturn(List.of(failureHandler, alwaysHandler));
    when(dagService.isNodeReadyForRunDispatch(run, "FAILURE_HANDLER", null)).thenReturn(true);
    when(dagService.isNodeReadyForRunDispatch(run, "ALWAYS_HANDLER", null)).thenReturn(true);

    WorkflowNodeDispatchService dispatchService = mock(WorkflowNodeDispatchService.class);
    WorkflowRunResumeProgressor progressor = new WorkflowRunResumeProgressor(
        workflowRunMapper,
        workflowMappers,
        dagService,
        dispatchService,
        new TaskOutcomeNodeRunRecorder(workflowMappers),
        mock(CountContinuityOutboxService.class),
        mock(SecretPayloadProtector.class),
        mock(WorkflowNodeTerminalFinalizer.class));

    progressor.resume(tenantId, workflowRunId);

    verify(dagService).resolveNextNodesForRun(run, "PROCESS", false, null);
    verify(dispatchService).dispatchNode(null, run, failureHandler, null, "trace-701");
    verify(dispatchService).dispatchNode(null, run, alwaysHandler, null, "trace-701");
    verify(dagService).cascadeSkipDownstreamForRun(run, "PROCESS", null);
  }
}

package io.github.pinpols.batch.orchestrator.application.service.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.orchestrator.application.engine.WorkflowTerminalOutboxService;
import io.github.pinpols.batch.orchestrator.application.service.task.OrchestratorJobMappers;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import io.github.pinpols.batch.orchestrator.domain.param.UpdateNodeRunStatusParam;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowRunMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

@DisplayName("流程运行管理应用服务: 节点跳过入口与状态返回口径")
class WorkflowRunManagementApplicationServiceTest {

  @Test
  @DisplayName("两个跳过节点入口都可用, 分别返回跳过状态与操作人信息")
  void shouldSupportBothSkipEntrypoints_whenSkippingNode() {
    WorkflowRunMapper workflowRunMapper = mock(WorkflowRunMapper.class);
    WorkflowNodeRunMapper workflowNodeRunMapper = mock(WorkflowNodeRunMapper.class);
    WorkflowRunEntity run = new WorkflowRunEntity();
    run.setId(1L);
    run.setTenantId("tenant-A");
    run.setRunStatus("RUNNING");
    WorkflowNodeRunEntity node = new WorkflowNodeRunEntity();
    node.setId(2L);
    node.setNodeStatus("FAILED");
    when(workflowRunMapper.selectById("tenant-A", 1L)).thenReturn(run);
    when(workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(1L, "NODE"))
        .thenReturn(node);

    WorkflowRunManagementApplicationService service = new WorkflowRunManagementApplicationService(
        workflowRunMapper,
        workflowNodeRunMapper,
        mock(WorkflowTerminalOutboxService.class),
        mock(WorkflowDagService.class),
        new StaticListableBeanFactory().getBeanProvider(WorkflowNodeDispatchService.class),
        mock(OrchestratorJobMappers.class),
        null,
        null);

    assertThat(service.skipNode("tenant-A", 1L, "NODE").nodeStatus()).isEqualTo("SKIPPED");
    assertThat(service.skipNode("tenant-A", 1L, "NODE", "operator-1", "manual review"))
        .extracting(WorkflowManagementResults.NodeAction::nodeStatus)
        .isEqualTo("SKIPPED");
  }

  @Test
  @DisplayName("无包装 Job Instance 的 Workflow 手动跳过后仍派发后继节点并持久化操作原因")
  void shouldAdvanceRootlessWorkflowAndPersistManualSkipReason() {
    WorkflowRunMapper workflowRunMapper = mock(WorkflowRunMapper.class);
    WorkflowNodeRunMapper workflowNodeRunMapper = mock(WorkflowNodeRunMapper.class);
    WorkflowDagService dagService = mock(WorkflowDagService.class);
    WorkflowNodeDispatchService dispatchService = mock(WorkflowNodeDispatchService.class);
    WorkflowRunEntity run = new WorkflowRunEntity();
    run.setId(1L);
    run.setTenantId("tenant-A");
    run.setWorkflowDefinitionId(7L);
    run.setTraceId("trace-1");
    run.setRunStatus("RUNNING");
    WorkflowNodeRunEntity failedNode = new WorkflowNodeRunEntity();
    failedNode.setId(2L);
    failedNode.setNodeCode("FAILED_NODE");
    failedNode.setNodeStatus("FAILED");
    WorkflowDagService.DagNodeResolution nextNode =
        new WorkflowDagService.DagNodeResolution("NEXT", "JOB");
    when(workflowRunMapper.selectById("tenant-A", 1L)).thenReturn(run);
    when(workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(1L, "FAILED_NODE"))
        .thenReturn(failedNode);
    when(dagService.resolveNextNodesForRun(run, "FAILED_NODE", true, null))
        .thenReturn(List.of(nextNode));
    when(dagService.isNodeReadyForRunDispatch(run, "NEXT", null)).thenReturn(true);

    WorkflowRunManagementApplicationService service = new WorkflowRunManagementApplicationService(
        workflowRunMapper,
        workflowNodeRunMapper,
        mock(WorkflowTerminalOutboxService.class),
        dagService,
        new StaticListableBeanFactory(java.util.Map.of("dispatchService", dispatchService))
            .getBeanProvider(WorkflowNodeDispatchService.class),
        mock(OrchestratorJobMappers.class),
        null,
        null);

    service.skipNode("tenant-A", 1L, "FAILED_NODE", "operator-1", "approved bypass");

    verify(dispatchService).dispatchNode(null, run, nextNode, null, "trace-1");
    ArgumentCaptor<UpdateNodeRunStatusParam> update =
        ArgumentCaptor.forClass(UpdateNodeRunStatusParam.class);
    verify(workflowNodeRunMapper).updateStatus(update.capture());
    assertThat(update.getValue().getSkipReasonCode()).isEqualTo("OPERATOR_SKIPPED");
    assertThat(update.getValue().getSkipReason()).isEqualTo("approved bypass");
  }
}

package io.github.pinpols.batch.orchestrator.application.service.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.application.engine.WorkflowTerminalOutboxService;
import io.github.pinpols.batch.orchestrator.application.service.governance.AlertEventService;
import io.github.pinpols.batch.orchestrator.application.service.task.OrchestratorJobMappers;
import io.github.pinpols.batch.orchestrator.application.service.task.WorkflowRunResumeProgressor;
import io.github.pinpols.batch.orchestrator.domain.command.AlertEmitCommand;
import io.github.pinpols.batch.orchestrator.domain.entity.JobExecutionLogEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import io.github.pinpols.batch.orchestrator.domain.param.UpdateNodeRunStatusParam;
import io.github.pinpols.batch.orchestrator.mapper.JobExecutionLogMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowRunMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
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
    when(workflowNodeRunMapper.updateStatus(ArgumentMatchers.any())).thenReturn(1, 1);

    WorkflowRunManagementApplicationService service = new WorkflowRunManagementApplicationService(
        workflowRunMapper,
        workflowNodeRunMapper,
        mock(WorkflowTerminalOutboxService.class),
        mock(WorkflowDagService.class),
        new StaticListableBeanFactory().getBeanProvider(WorkflowNodeDispatchService.class),
        new StaticListableBeanFactory().getBeanProvider(WorkflowRunResumeProgressor.class),
        mock(OrchestratorJobMappers.class),
        null,
        null,
        mock(WorkflowNodeTerminalFinalizer.class));

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
    run.setBizDate(LocalDate.of(2026, 10, 22));
    run.setRunStatus("RUNNING");
    WorkflowNodeRunEntity failedNode = new WorkflowNodeRunEntity();
    failedNode.setId(2L);
    failedNode.setNodeCode("FAILED_NODE");
    failedNode.setNodeStatus("FAILED");
    failedNode.setJobInstanceId(43L);
    WorkflowDagService.DagNodeResolution nextNode =
        new WorkflowDagService.DagNodeResolution("NEXT", "JOB");
    when(workflowRunMapper.selectById("tenant-A", 1L)).thenReturn(run);
    when(workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(1L, "FAILED_NODE"))
        .thenReturn(failedNode);
    when(workflowNodeRunMapper.updateStatus(ArgumentMatchers.any())).thenReturn(1);
    when(dagService.resolveNextNodesForRun(run, "FAILED_NODE", true, null))
        .thenReturn(List.of(nextNode));
    when(dagService.isNodeReadyForRunDispatch(run, "NEXT", null)).thenReturn(true);
    JobExecutionLogMapper auditMapper = mock(JobExecutionLogMapper.class);
    AlertEventService alertService = mock(AlertEventService.class);

    WorkflowRunManagementApplicationService service = new WorkflowRunManagementApplicationService(
        workflowRunMapper,
        workflowNodeRunMapper,
        mock(WorkflowTerminalOutboxService.class),
        dagService,
        new StaticListableBeanFactory(Map.of("dispatchService", dispatchService))
            .getBeanProvider(WorkflowNodeDispatchService.class),
        new StaticListableBeanFactory().getBeanProvider(WorkflowRunResumeProgressor.class),
        mock(OrchestratorJobMappers.class),
        new StaticListableBeanFactory(Map.of("auditMapper", auditMapper))
            .getBeanProvider(JobExecutionLogMapper.class),
        new StaticListableBeanFactory(Map.of("alertService", alertService))
            .getBeanProvider(AlertEventService.class),
        mock(WorkflowNodeTerminalFinalizer.class));

    service.skipNode("tenant-A", 1L, "FAILED_NODE", "operator-1", "approved bypass");

    verify(dispatchService).dispatchNode(null, run, nextNode, null, "trace-1");
    ArgumentCaptor<UpdateNodeRunStatusParam> update =
        ArgumentCaptor.forClass(UpdateNodeRunStatusParam.class);
    verify(workflowNodeRunMapper).updateStatus(update.capture());
    assertThat(update.getValue().getSkipReasonCode()).isEqualTo("OPERATOR_SKIPPED");
    assertThat(update.getValue().getSkipReason()).isEqualTo("approved bypass");

    ArgumentCaptor<AlertEmitCommand> emittedAlert = ArgumentCaptor.forClass(AlertEmitCommand.class);
    verify(alertService).emit(emittedAlert.capture());
    Map<String, Object> alertDetail =
        JsonUtils.fromJson(emittedAlert.getValue().detailJson(), new TypeReference<>() {});
    assertThat(alertDetail)
        .containsEntry("workflowRunId", 1)
        .containsEntry("workflowDefinitionId", 7)
        .containsEntry("nodeCode", "FAILED_NODE")
        .containsEntry("nodeRunId", 2)
        .containsEntry("jobInstanceId", "43")
        .containsEntry("bizDate", "2026-10-22");
    assertThat(emittedAlert.getValue().traceId()).isEqualTo("trace-1");

    ArgumentCaptor<JobExecutionLogEntity> auditEntry =
        ArgumentCaptor.forClass(JobExecutionLogEntity.class);
    verify(auditMapper).insert(auditEntry.capture());
    assertThat(auditEntry.getValue().getTraceId()).isEqualTo("trace-1");
    assertThat(auditEntry.getValue().getJobInstanceId()).isEqualTo(43L);
  }

  @Test
  @DisplayName("节点 CAS 已失效时拒绝手动 skip，且不推进后继")
  void shouldRejectManualSkip_whenFailedNodeWasChangedConcurrently() {
    WorkflowRunMapper workflowRunMapper = mock(WorkflowRunMapper.class);
    WorkflowNodeRunMapper workflowNodeRunMapper = mock(WorkflowNodeRunMapper.class);
    WorkflowDagService dagService = mock(WorkflowDagService.class);
    WorkflowRunEntity run = new WorkflowRunEntity();
    run.setId(1L);
    run.setTenantId("tenant-A");
    run.setWorkflowDefinitionId(7L);
    run.setRunStatus("RUNNING");
    WorkflowNodeRunEntity failedNode = new WorkflowNodeRunEntity();
    failedNode.setId(2L);
    failedNode.setNodeCode("FAILED_NODE");
    failedNode.setNodeStatus("FAILED");
    when(workflowRunMapper.selectById("tenant-A", 1L)).thenReturn(run);
    when(workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(1L, "FAILED_NODE"))
        .thenReturn(failedNode);
    when(workflowNodeRunMapper.updateStatus(ArgumentMatchers.any())).thenReturn(0);

    WorkflowRunManagementApplicationService service = new WorkflowRunManagementApplicationService(
        workflowRunMapper,
        workflowNodeRunMapper,
        mock(WorkflowTerminalOutboxService.class),
        dagService,
        new StaticListableBeanFactory().getBeanProvider(WorkflowNodeDispatchService.class),
        new StaticListableBeanFactory().getBeanProvider(WorkflowRunResumeProgressor.class),
        mock(OrchestratorJobMappers.class),
        null,
        null,
        mock(WorkflowNodeTerminalFinalizer.class));

    assertThatThrownBy(
            () -> service.skipNode("tenant-A", 1L, "FAILED_NODE", "operator-1", "approved bypass"))
        .isInstanceOf(BizException.class)
        .satisfies(error ->
            assertThat(((BizException) error).getCode()).isEqualTo(ResultCode.STATE_CONFLICT));
    verifyNoInteractions(dagService);
  }
}

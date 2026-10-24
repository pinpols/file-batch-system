package io.github.pinpols.batch.orchestrator.application.service.workflow;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.application.engine.WorkflowTerminalOutboxService;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import io.github.pinpols.batch.orchestrator.domain.param.UpdateWorkflowRunStatusParam;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowRunMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
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
            Mockito.mock(WorkflowNodeMapper.class), workflowRunMapper, nodeRunMapper),
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
    when(workflowRunMapper.selectByTenantAndIdForUpdate("tenant", 12L)).thenReturn(workflowRun());
    when(workflowRunMapper.updateStatus(any(UpdateWorkflowRunStatusParam.class)))
        .thenReturn(1);

    finalizer.finalizeIfAllNodesTerminal(workflowRun);

    InOrder lockOrder = inOrder(workflowRunMapper, nodeRunMapper);
    lockOrder.verify(workflowRunMapper).selectByTenantAndIdForUpdate("tenant", 12L);
    lockOrder.verify(nodeRunMapper).selectByWorkflowRunId(12L);

    verify(workflowRunMapper)
        .updateStatus(ArgumentMatchers.argThat(param -> "SUCCESS".equals(param.getRunStatus())
            && param.getExpectedStatuses().contains("RUNNING")));
    verify(terminalOutboxService)
        .writeTerminalEvent(any(WorkflowRunEntity.class), ArgumentMatchers.eq("SUCCESS"), any());
  }

  @DisplayName("END 成功和未选分支跳过不能掩盖必需节点失败")
  @Test
  void shouldKeepWorkflowFailedWhenEndSucceededAfterRequiredNodeFailed() {
    WorkflowRunEntity workflowRun = workflowRun();
    when(nodeRunMapper.selectByWorkflowRunId(12L))
        .thenReturn(List.of(
            nodeRun("REQUIRED_JOB", 1, "FAILED"),
            nodeRun("UNSELECTED_BRANCH", 1, "SKIPPED"),
            nodeRun("END", 1, "SUCCESS")));
    when(workflowRunMapper.selectByTenantAndIdForUpdate("tenant", 12L)).thenReturn(workflowRun());
    when(workflowRunMapper.updateStatus(any(UpdateWorkflowRunStatusParam.class)))
        .thenReturn(1);

    finalizer.finalizeIfAllNodesTerminal(workflowRun);

    verify(workflowRunMapper)
        .updateStatus(ArgumentMatchers.argThat(param -> "FAILED".equals(param.getRunStatus())
            && param.getExpectedStatuses().contains("RUNNING")));
    verify(terminalOutboxService)
        .writeTerminalEvent(any(WorkflowRunEntity.class), ArgumentMatchers.eq("FAILED"), any());
  }

  @DisplayName("最新节点仍活跃时不提前终结 Workflow")
  @Test
  void shouldActiveLatestNodePreventsPrematureWorkflowFinalization() {
    when(workflowRunMapper.selectByTenantAndIdForUpdate("tenant", 12L)).thenReturn(workflowRun());
    when(nodeRunMapper.selectByWorkflowRunId(12L))
        .thenReturn(List.of(nodeRun("WAIT_FILE", 1, "SUCCESS"), nodeRun("TASK", 1, "RUNNING")));

    finalizer.finalizeIfAllNodesTerminal(workflowRun());

    verify(workflowRunMapper, never()).updateStatus(any());
    verify(terminalOutboxService, never()).writeTerminalEvent(any(), any(), any());
  }

  @DisplayName("已终态 RootRun 在重读加锁后不再扫描或重复写终态事件")
  @Test
  void shouldIgnoreAlreadyTerminalWorkflowRun() {
    WorkflowRunEntity terminalRun = workflowRun();
    terminalRun.setRunStatus("SUCCESS");
    when(workflowRunMapper.selectByTenantAndIdForUpdate("tenant", 12L)).thenReturn(terminalRun);

    finalizer.finalizeIfAllNodesTerminal(workflowRun());

    verify(nodeRunMapper, never()).selectByWorkflowRunId(12L);
    verify(workflowRunMapper, never()).updateStatus(any());
    verify(terminalOutboxService, never()).writeTerminalEvent(any(), any(), any());
  }

  @Test
  @DisplayName("冻结图中的并行分支尚无运行记录时，END 成功不能提前结束 Workflow")
  void shouldWaitForUndispatchedFrozenBranch() {
    WorkflowRunEntity run = frozenRun(true);
    when(workflowRunMapper.selectByTenantAndIdForUpdate("tenant", 12L)).thenReturn(run);
    when(nodeRunMapper.selectByWorkflowRunId(12L))
        .thenReturn(List.of(nodeRun("END", 1, "SUCCESS")));

    finalizer.finalizeIfAllNodesTerminal(run);

    verify(workflowRunMapper, never()).updateStatus(any());
    verify(terminalOutboxService, never()).writeTerminalEvent(any(), any(), any());
  }

  @Test
  @DisplayName("冻结图中禁用的分支不阻止已完成运行收口")
  void shouldIgnoreDisabledFrozenBranchAtCompletion() {
    WorkflowRunEntity run = frozenRun(false);
    when(workflowRunMapper.selectByTenantAndIdForUpdate("tenant", 12L)).thenReturn(run);
    when(nodeRunMapper.selectByWorkflowRunId(12L))
        .thenReturn(List.of(nodeRun("END", 1, "SUCCESS")));
    when(workflowRunMapper.updateStatus(any(UpdateWorkflowRunStatusParam.class))).thenReturn(1);

    finalizer.finalizeIfAllNodesTerminal(run);

    verify(terminalOutboxService)
        .writeTerminalEvent(any(), ArgumentMatchers.eq("SUCCESS"), any());
  }

  @Test
  @DisplayName("所有业务分支已跳过时允许 END 未激活，保持 WAIT 跳过下游的收口语义")
  void shouldCompleteSkippedBranchWithoutEndActivation() {
    WorkflowRunEntity run = frozenRun(true);
    when(workflowRunMapper.selectByTenantAndIdForUpdate("tenant", 12L)).thenReturn(run);
    when(nodeRunMapper.selectByWorkflowRunId(12L))
        .thenReturn(List.of(nodeRun("BRANCH", 1, "SKIPPED")));
    when(workflowRunMapper.updateStatus(any(UpdateWorkflowRunStatusParam.class))).thenReturn(1);

    finalizer.finalizeIfAllNodesTerminal(run);

    verify(terminalOutboxService)
        .writeTerminalEvent(any(), ArgumentMatchers.eq("SUCCESS"), any());
  }

  private WorkflowRunEntity frozenRun(boolean branchEnabled) {
    WorkflowRunEntity run = workflowRun();
    run.setWorkflowDefinitionId(50L);
    run.setWorkflowVersionSnapshot(1);
    Map<String, Object> end = Map.of("tenantId", "tenant", "workflowDefinitionId", 50L,
        "nodeCode", "END", "nodeType", "END", "enabled", true);
    Map<String, Object> branch = Map.of("tenantId", "tenant", "workflowDefinitionId", 50L,
        "nodeCode", "BRANCH", "nodeType", "GATEWAY", "enabled", branchEnabled);
    List<Map<String, Object>> nodes = List.of(end, branch);
    List<Object> edges = List.of();
    Map<String, Object> snapshot = Map.of("schemaVersion", 2, "workflowDefinitionId", 50L,
        "workflowVersion", 1, "nodes", nodes, "edges", edges);
    String snapshotJson = JsonUtils.toJson(snapshot);
    run.setExecutionSnapshot(snapshotJson);
    return run;
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

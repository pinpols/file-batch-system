package io.github.pinpols.batch.orchestrator.application.service.task;

import io.github.pinpols.batch.common.enums.WorkflowNodeCode;
import io.github.pinpols.batch.common.enums.WorkflowNodeRunStatus;
import io.github.pinpols.batch.common.enums.WorkflowNodeType;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.orchestrator.application.engine.CountContinuityOutboxService;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskOutcomeService.NodeRunFinishCommand;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskOutcomeService.NodeRunKey;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskOutcomeService.NodeRunOutcome;
import io.github.pinpols.batch.orchestrator.application.service.workflow.OrchestratorWorkflowMappers;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowDagService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowNodeDispatchService;
import io.github.pinpols.batch.orchestrator.domain.command.TaskOutcomeCommand;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 负责一个 workflow 节点完成后的 DAG 推进。
 *
 * <p>节点完成后必须先落 node_run 终态，再根据边条件决定是否创建 END 记录或派发后继节点；失败时还要
 * 把永远不会满足的 SUCCESS-edge 下游标记为 SKIPPED。该顺序和 {@code workflow_run.current_node_code}
 * 的活跃节点集合强相关，因此集中在这里，避免任务结果服务混入派发细节。
 *
 * <p>本类只协调节点记录、DAG 解析和下游派发，不声明事务边界。调用方在 report 事务中调用它，保证节点
 * 终态、下游任务和工作流游标一起提交。
 */
@Component
final class TaskOutcomeDagProgressor {

  private final OrchestratorWorkflowMappers workflowMappers;
  private final WorkflowDagService workflowDagService;
  private final ObjectProvider<WorkflowNodeDispatchService> dispatchServiceProvider;
  private final TaskOutcomeNodeRunRecorder nodeRunRecorder;
  private final CountContinuityOutboxService countContinuityOutboxService;

  TaskOutcomeDagProgressor(
      OrchestratorWorkflowMappers workflowMappers,
      WorkflowDagService workflowDagService,
      ObjectProvider<WorkflowNodeDispatchService> dispatchServiceProvider,
      TaskOutcomeNodeRunRecorder nodeRunRecorder,
      CountContinuityOutboxService countContinuityOutboxService) {
    this.workflowMappers = workflowMappers;
    this.workflowDagService = workflowDagService;
    this.dispatchServiceProvider = dispatchServiceProvider;
    this.nodeRunRecorder = nodeRunRecorder;
    this.countContinuityOutboxService = countContinuityOutboxService;
  }

  void advance(Context ctx) {
    ctx.activeNodes().remove(ctx.currentNodeCode());
    NodeRunOutcome currentOutcome = NodeRunOutcome.builder()
        .success(ctx.nodeProgress().failedCount() == 0)
        .errorCode(ctx.command().errorCode())
        .errorMessage(ctx.command().errorMessage())
        .errorKey(ctx.command().errorKey())
        .errorArgs(ctx.command().errorArgs())
        .startedAt(nodeRunRecorder.resolveStartedAt(
            ctx.workflowRun().getId(),
            ctx.currentNodeCode(),
            ctx.workflowRun().getStartedAt(),
            ctx.finishedAt()))
        .finishedAt(ctx.finishedAt())
        .outputJson(TaskOutcomeSummaryBuilder.serializeOutputs(ctx.nodeOutputs()))
        .build();
    NodeRunKey currentKey = new NodeRunKey(
        ctx.workflowRun().getId(), ctx.currentNodeCode(), resolveCurrentNodeType(ctx.task()));
    recordFinish(NodeRunFinishCommand.of(currentKey, currentOutcome));

    List<WorkflowDagService.DagNodeResolution> nextNodes = workflowDagService.resolveNextNodes(
        ctx.workflowRun().getWorkflowDefinitionId(),
        ctx.currentNodeCode(),
        ctx.nodeProgress().failedCount() == 0,
        ctx.task().getTaskPayload());
    for (WorkflowDagService.DagNodeResolution nextNode : nextNodes) {
      if (EmptyChecks.isNull(nextNode)) {
        continue;
      }
      if (WorkflowNodeCode.END.code().equals(nextNode.nodeCode())) {
        completeEndNodeIfReady(ctx, nextNode);
        continue;
      }
      int dispatched = dispatchServiceProvider
          .getObject()
          .dispatchNode(
              ctx.jobInstance(),
              ctx.workflowRun(),
              nextNode,
              ctx.task().getTaskPayload(),
              ctx.jobInstance().getTraceId());
      // 只有真正创建分区且节点仍处于 READY/RUNNING，才把节点写入活跃集合，避免游标残留。
      if (dispatched > 0 && isActiveNode(ctx.workflowRun().getId(), nextNode.nodeCode())) {
        ctx.activeNodes().add(nextNode.nodeCode());
      }
    }
    if (ctx.nodeProgress().failedCount() > 0) {
      // 失败节点无法再满足 SUCCESS-edge，显式跳过下游，避免 ALL-mode join 永久等待。
      workflowDagService.cascadeSkipDownstream(
          ctx.workflowRun().getId(),
          ctx.workflowRun().getWorkflowDefinitionId(),
          ctx.currentNodeCode());
    }
  }

  private void completeEndNodeIfReady(Context ctx, WorkflowDagService.DagNodeResolution nextNode) {
    if (!workflowDagService.isNodeReadyForDispatch(
        ctx.workflowRun().getId(),
        ctx.workflowRun().getWorkflowDefinitionId(),
        nextNode.nodeCode(),
        ctx.task().getTaskPayload())) {
      return;
    }
    nodeRunRecorder.recordStart(
        ctx.workflowRun().getId(), nextNode.nodeCode(), nextNode.nodeType(), ctx.finishedAt());
    NodeRunOutcome endOutcome = NodeRunOutcome.builder()
        .success(ctx.nodeProgress().failedCount() == 0)
        .errorCode(ctx.command().errorCode())
        .errorMessage(ctx.command().errorMessage())
        .errorKey(ctx.command().errorKey())
        .errorArgs(ctx.command().errorArgs())
        .startedAt(ctx.finishedAt())
        .finishedAt(ctx.finishedAt())
        .build();
    NodeRunKey endKey =
        new NodeRunKey(ctx.workflowRun().getId(), nextNode.nodeCode(), nextNode.nodeType());
    recordFinish(NodeRunFinishCommand.of(endKey, endOutcome));
  }

  private void recordFinish(NodeRunFinishCommand command) {
    nodeRunRecorder.recordFinish(command);
    if (command.success()) {
      countContinuityOutboxService.checkContinuity(
          command.workflowRunId(), command.nodeCode(), command.outputJson());
    }
  }

  private boolean isActiveNode(Long workflowRunId, String nodeCode) {
    WorkflowNodeRunEntity latestNodeRun =
        workflowMappers.workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(
            workflowRunId, nodeCode);
    if (EmptyChecks.isNull(latestNodeRun)) {
      return false;
    }
    return WorkflowNodeRunStatus.READY.code().equals(latestNodeRun.getNodeStatus())
        || WorkflowNodeRunStatus.RUNNING.code().equals(latestNodeRun.getNodeStatus());
  }

  private String resolveCurrentNodeType(JobTaskEntity task) {
    String nodeType = TaskOutcomePayloadSupport.payloadStringValue(
        EmptyChecks.isNull(task) ? null : task.getTaskPayload(), "workflowNodeType");
    return EmptyChecks.isBlank(nodeType) ? WorkflowNodeType.TASK.code() : nodeType;
  }

  @lombok.Builder
  record Context(
      TaskOutcomeCommand command,
      JobTaskEntity task,
      JobInstanceEntity jobInstance,
      WorkflowRunEntity workflowRun,
      String currentNodeCode,
      NodePartitionProgressCalculator.Result nodeProgress,
      Map<String, Object> nodeOutputs,
      Set<String> activeNodes,
      Instant finishedAt) {}
}

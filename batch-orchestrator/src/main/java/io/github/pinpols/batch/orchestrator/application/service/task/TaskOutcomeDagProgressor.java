package io.github.pinpols.batch.orchestrator.application.service.task;

import io.github.pinpols.batch.common.enums.WorkflowNodeCode;
import io.github.pinpols.batch.common.enums.WorkflowNodeRunStatus;
import io.github.pinpols.batch.common.enums.WorkflowNodeType;
import io.github.pinpols.batch.common.enums.WorkflowRunStatus;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.common.service.SecretPayloadProtector;
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
import lombok.Builder;
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
  private final SecretPayloadProtector secretPayloadProtector;

  TaskOutcomeDagProgressor(
      OrchestratorWorkflowMappers workflowMappers,
      WorkflowDagService workflowDagService,
      ObjectProvider<WorkflowNodeDispatchService> dispatchServiceProvider,
      TaskOutcomeNodeRunRecorder nodeRunRecorder,
      CountContinuityOutboxService countContinuityOutboxService,
      SecretPayloadProtector secretPayloadProtector) {
    this.workflowMappers = workflowMappers;
    this.workflowDagService = workflowDagService;
    this.dispatchServiceProvider = dispatchServiceProvider;
    this.nodeRunRecorder = nodeRunRecorder;
    this.countContinuityOutboxService = countContinuityOutboxService;
    this.secretPayloadProtector = secretPayloadProtector;
  }

  void advance(Context ctx) {
    String sourcePayload = resolveSourcePayload(ctx);
    ctx.activeNodes().remove(ctx.currentNodeCode());
    TaskOutcomeService.NodeRunOutcome.NodeRunOutcomeBuilder currentOutcomeBuilder =
        NodeRunOutcome.builder()
            .success(ctx.nodeProgress().unsuccessfulCount() == 0)
            .errorCode(ctx.command().errorCode())
            .errorMessage(ctx.command().errorMessage())
            .errorKey(ctx.command().errorKey())
            .errorArgs(ctx.command().errorArgs());
    Long startedAtWorkflowRunId = ctx.workflowRun().getId();
    String startedAtNodeCode = ctx.currentNodeCode();
    Instant workflowStartedAt = ctx.workflowRun().getStartedAt();
    Instant resolvedStartedAt = nodeRunRecorder.resolveStartedAt(
        startedAtWorkflowRunId, startedAtNodeCode, workflowStartedAt, ctx.finishedAt());
    currentOutcomeBuilder.startedAt(resolvedStartedAt).finishedAt(ctx.finishedAt());
    Map<String, Object> nodeOutputs = ctx.nodeOutputs();
    String outputJson = TaskOutcomeSummaryBuilder.serializeOutputs(nodeOutputs);
    NodeRunOutcome currentOutcome = currentOutcomeBuilder.outputJson(outputJson).build();
    Long workflowRunId = ctx.workflowRun().getId();
    String currentNodeCode = ctx.currentNodeCode();
    String currentNodeType = resolveCurrentNodeType(ctx.task());
    NodeRunKey currentKey = new NodeRunKey(workflowRunId, currentNodeCode, currentNodeType);
    recordFinish(NodeRunFinishCommand.of(currentKey, currentOutcome));

    // 暂停会保留进行中的工作并记录其结果，但不得推进任何边。
    // 恢复进度器会在 PAUSED → RUNNING 后重新处理此持久化的 SUCCESS/FAILED 节点。
    // 状态转换提交后再继续处理。
    if (WorkflowRunStatus.PAUSED.code().equals(ctx.workflowRun().getRunStatus())) {
      return;
    }

    List<WorkflowDagService.DagNodeResolution> nextNodes =
        workflowDagService.resolveNextNodesForRun(
            ctx.workflowRun(),
            ctx.currentNodeCode(),
            ctx.nodeProgress().unsuccessfulCount() == 0,
            sourcePayload);
    for (WorkflowDagService.DagNodeResolution nextNode : nextNodes) {
      if (EmptyChecks.isNull(nextNode)) {
        continue;
      }
      if (WorkflowNodeCode.END.code().equals(nextNode.nodeCode())) {
        completeEndNodeIfReady(ctx, nextNode, sourcePayload);
        continue;
      }
      int dispatched = dispatchServiceProvider
          .getObject()
          .dispatchNode(
              ctx.jobInstance(),
              ctx.workflowRun(),
              nextNode,
              sourcePayload,
              ctx.jobInstance().getTraceId());
      // 只有真正创建分区且节点仍处于 READY/RUNNING，才把节点写入活跃集合，避免游标残留。
      boolean dispatchedNodeRemainsActive =
          dispatched > 0 && isActiveNode(ctx.workflowRun().getId(), nextNode.nodeCode());
      if (dispatchedNodeRemainsActive) {
        ctx.activeNodes().add(nextNode.nodeCode());
      }
    }
    // 完成节点后统一记录再无机会触发的分支；失败边、未命中条件边和级联下游共用同一
    // terminal/match 谓词，避免未选分支没有状态而让运行时间线与日结算看不见它。
    workflowDagService.cascadeSkipDownstreamForRun(
        ctx.workflowRun(), ctx.currentNodeCode(), sourcePayload);
  }

  private String resolveSourcePayload(Context ctx) {
    WorkflowNodeRunEntity nodeRun =
        workflowMappers.workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(
            ctx.workflowRun().getId(), ctx.currentNodeCode());
    if (EmptyChecks.isNotNull(nodeRun)
        && !EmptyChecks.isBlank(nodeRun.getSourcePayloadProtected())) {
      return secretPayloadProtector.reveal(nodeRun.getSourcePayloadProtected());
    }
    return null;
  }

  private void completeEndNodeIfReady(
      Context ctx, WorkflowDagService.DagNodeResolution nextNode, String sourcePayload) {
    if (!workflowDagService.isNodeReadyForRunDispatch(
        ctx.workflowRun(), nextNode.nodeCode(), sourcePayload)) {
      return;
    }
    nodeRunRecorder.recordStart(
        ctx.workflowRun().getId(), nextNode.nodeCode(), nextNode.nodeType(), ctx.finishedAt());
    NodeRunOutcome endOutcome = NodeRunOutcome.builder()
        .success(ctx.nodeProgress().unsuccessfulCount() == 0)
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

  @Builder
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

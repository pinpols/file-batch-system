package io.github.pinpols.batch.orchestrator.application.service.task;

import io.github.pinpols.batch.common.enums.WorkflowNodeRunStatus;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskOutcomeService.NodeRunFinishCommand;
import io.github.pinpols.batch.orchestrator.application.service.workflow.OrchestratorWorkflowMappers;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import io.github.pinpols.batch.orchestrator.domain.param.UpdateNodeRunStatusParam;
import java.time.Duration;
import java.time.Instant;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * 负责 workflow node_run 的记录生命周期。
 *
 * <p>节点记录和 task/partition 状态属于同一条事务链，但它们是两个不同的写入职责。这里集中处理
 * READY、RUNNING、终态写入，以及并发插入时的唯一键兜底，避免任务结果服务同时承担节点记录的细节。
 *
 * <p>本类不声明事务边界。事务由 {@link DefaultTaskOutcomeService} 的公开入口统一管理，保证节点记录和
 * task、partition、workflow 状态仍在同一个事务中提交。
 */
@Component
final class TaskOutcomeNodeRunRecorder {

  private final OrchestratorWorkflowMappers workflowMappers;

  TaskOutcomeNodeRunRecorder(OrchestratorWorkflowMappers workflowMappers) {
    this.workflowMappers = workflowMappers;
  }

  WorkflowNodeRunEntity recordReady(Long workflowRunId, String nodeCode, String nodeType) {
    WorkflowNodeRunEntity entity =
        newEntity(workflowRunId, nodeCode, nodeType, WorkflowNodeRunStatus.READY.code(), null);
    return insertOrSelectLatest(entity);
  }

  WorkflowNodeRunEntity recordStart(
      Long workflowRunId, String nodeCode, String nodeType, Instant startedAt) {
    WorkflowNodeRunEntity entity = newEntity(
        workflowRunId, nodeCode, nodeType, WorkflowNodeRunStatus.RUNNING.code(), startedAt);
    return insertOrSelectLatest(entity);
  }

  WorkflowNodeRunEntity recordFinish(NodeRunFinishCommand command) {
    WorkflowNodeRunEntity current = workflowMappers.workflowNodeRunMapper.selectLatestForUpdate(
        command.workflowRunId(), command.nodeCode());
    if (EmptyChecks.isNull(current)) {
      current = recordStart(
          command.workflowRunId(), command.nodeCode(), command.nodeType(), command.startedAt());
    }
    long duration = resolveDuration(command.startedAt(), command.finishedAt());
    workflowMappers.workflowNodeRunMapper.updateStatus(UpdateNodeRunStatusParam.builder()
        .id(current.getId())
        .nodeStatus(resolveStatus(command))
        .errorCode(command.errorCode())
        .errorMessage(command.errorMessage())
        .errorKey(command.errorKey())
        .errorArgs(command.errorArgs())
        .durationMs(duration)
        .finishedAt(command.finishedAt())
        // 成功节点保存 worker 产出，失败节点不保留可能不完整的 output。
        .output(command.success() ? command.outputJson() : null)
        .build());
    return workflowMappers.workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(
        command.workflowRunId(), command.nodeCode());
  }

  Instant resolveStartedAt(
      Long workflowRunId, String nodeCode, Instant workflowStartedAt, Instant finishedAt) {
    WorkflowNodeRunEntity latestNodeRun =
        workflowMappers.workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(
            workflowRunId, nodeCode);
    if (EmptyChecks.isNotNull(latestNodeRun)
        && EmptyChecks.isNotNull(latestNodeRun.getStartedAt())) {
      return latestNodeRun.getStartedAt();
    }
    return EmptyChecks.isNotNull(workflowStartedAt) ? workflowStartedAt : finishedAt;
  }

  private WorkflowNodeRunEntity insertOrSelectLatest(WorkflowNodeRunEntity entity) {
    try {
      workflowMappers.workflowNodeRunMapper.insert(entity);
    } catch (DuplicateKeyException ignored) {
      SwallowedExceptionLogger.info(
          TaskOutcomeNodeRunRecorder.class, "catch:DuplicateKeyException", ignored);
      return workflowMappers.workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(
          entity.getWorkflowRunId(), entity.getNodeCode());
    }
    return entity;
  }

  private WorkflowNodeRunEntity newEntity(
      Long workflowRunId, String nodeCode, String nodeType, String nodeStatus, Instant startedAt) {
    WorkflowNodeRunEntity entity = new WorkflowNodeRunEntity();
    entity.setWorkflowRunId(workflowRunId);
    entity.setNodeCode(nodeCode);
    entity.setNodeType(nodeType);
    entity.setRunSeq(nextRunSeq(workflowRunId, nodeCode));
    entity.setNodeStatus(nodeStatus);
    entity.setRetryCount(0);
    entity.setDurationMs(0L);
    entity.setStartedAt(startedAt);
    return entity;
  }

  private int nextRunSeq(Long workflowRunId, String nodeCode) {
    WorkflowNodeRunEntity current =
        workflowMappers.workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(
            workflowRunId, nodeCode);
    return EmptyChecks.isNull(current) || EmptyChecks.isNull(current.getRunSeq())
        ? 1
        : current.getRunSeq() + 1;
  }

  private String resolveStatus(NodeRunFinishCommand command) {
    return command.success()
        ? WorkflowNodeRunStatus.SUCCESS.code()
        : WorkflowNodeRunStatus.FAILED.code();
  }

  private long resolveDuration(Instant startedAt, Instant finishedAt) {
    return EmptyChecks.isNull(startedAt) || EmptyChecks.isNull(finishedAt)
        ? 0L
        : Duration.between(startedAt, finishedAt).toMillis();
  }
}

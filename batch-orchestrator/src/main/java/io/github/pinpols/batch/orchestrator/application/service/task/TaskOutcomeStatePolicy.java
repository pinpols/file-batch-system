package io.github.pinpols.batch.orchestrator.application.service.task;

import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import io.github.pinpols.batch.common.enums.WorkflowNodeCode;
import io.github.pinpols.batch.common.enums.WorkflowNodeRunStatus;
import io.github.pinpols.batch.common.enums.WorkflowRunStatus;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import io.github.pinpols.batch.orchestrator.domain.statemachine.LifecycleStatusCatalog;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Worker 回报后的终态与活动节点纯决策策略。 */
final class TaskOutcomeStatePolicy {

  private TaskOutcomeStatePolicy() {}

  static boolean isTerminalJobInstanceStatus(String status) {
    return LifecycleStatusCatalog.isJobInstanceTerminal(status);
  }

  static Set<String> parseActiveNodes(String currentNodeCode) {
    Set<String> activeNodes = new LinkedHashSet<>();
    if (EmptyChecks.isBlank(currentNodeCode)) {
      return activeNodes;
    }
    for (String nodeCode : currentNodeCode.split(",")) {
      if (EmptyChecks.isBlank(nodeCode)) {
        continue;
      }
      activeNodes.add(nodeCode.trim());
    }
    return activeNodes;
  }

  static Set<String> resolveActiveNodeCodes(List<WorkflowNodeRunEntity> nodeRuns) {
    Map<String, WorkflowNodeRunEntity> latestByNode = new LinkedHashMap<>();
    for (WorkflowNodeRunEntity nodeRun : nodeRuns) {
      if (EmptyChecks.isNull(nodeRun) || EmptyChecks.isNull(nodeRun.getNodeCode())) {
        continue;
      }
      latestByNode.merge(
          nodeRun.getNodeCode(),
          nodeRun,
          (left, right) -> Optional.ofNullable(right.getRunSeq()).orElse(0)
                  >= Optional.ofNullable(left.getRunSeq()).orElse(0)
              ? right
              : left);
    }
    Set<String> activeNodes = new LinkedHashSet<>();
    latestByNode.forEach((nodeCode, nodeRun) -> {
      String status = nodeRun.getNodeStatus();
      if (WorkflowNodeRunStatus.READY.code().equals(status)
          || WorkflowNodeRunStatus.WAITING_DEPENDENCY.code().equals(status)
          || WorkflowNodeRunStatus.RUNNING.code().equals(status)) {
        activeNodes.add(nodeCode);
      }
    });
    return activeNodes;
  }

  static String resolveInstanceEvent(
      long successCount,
      long failedCount,
      boolean allPartitionsFinished,
      boolean dagContinues,
      boolean dryRun) {
    if (!allPartitionsFinished || dagContinues) {
      return JobInstanceStatus.RUNNING.code();
    }
    if (failedCount > 0 && successCount > 0) {
      return dryRun
          ? JobInstanceStatus.FAILED_DRY_RUN.code()
          : JobInstanceStatus.PARTIAL_FAILED.code();
    }
    if (failedCount > 0) {
      return dryRun ? JobInstanceStatus.FAILED_DRY_RUN.code() : JobInstanceStatus.FAILED.code();
    }
    return dryRun ? JobInstanceStatus.SUCCESS_DRY_RUN.code() : JobInstanceStatus.SUCCESS.code();
  }

  /**
   * 允许把陈旧的失败终态收敛为成功。
   *
   * <p>失败终态通常不可复活，但重试/补偿可能在旧失败写入后完成最后一个分区。只有当本次实时分区
   * 统计已经证明全部分区成功且没有 DAG 后继节点时，才允许修正；取消和终止永远不走此路径。
   */
  static boolean shouldPromoteTerminalFailure(
      String currentStatus,
      String resolvedStatus,
      long successCount,
      long failedCount,
      boolean allPartitionsFinished,
      boolean dagContinues) {
    boolean currentIsFailure = JobInstanceStatus.FAILED.code().equals(currentStatus)
        || JobInstanceStatus.PARTIAL_FAILED.code().equals(currentStatus)
        || JobInstanceStatus.FAILED_DRY_RUN.code().equals(currentStatus);
    boolean resolvedIsSuccess = JobInstanceStatus.SUCCESS.code().equals(resolvedStatus)
        || JobInstanceStatus.SUCCESS_DRY_RUN.code().equals(resolvedStatus);
    return currentIsFailure
        && resolvedIsSuccess
        && successCount > 0
        && failedCount == 0
        && allPartitionsFinished
        && !dagContinues;
  }

  static boolean isDryRun(JobInstanceEntity instance) {
    return EmptyChecks.isNotNull(instance) && Boolean.TRUE.equals(instance.getDryRun());
  }

  static String resolveWorkflowEvent(
      long failedCount, boolean allPartitionsFinished, boolean dagContinues, boolean dryRun) {
    if (!allPartitionsFinished || dagContinues) {
      return WorkflowRunStatus.RUNNING.code();
    }
    if (failedCount > 0) {
      return dryRun ? WorkflowRunStatus.FAILED_DRY_RUN.code() : WorkflowRunStatus.FAILED.code();
    }
    return dryRun ? WorkflowRunStatus.SUCCESS_DRY_RUN.code() : WorkflowRunStatus.SUCCESS.code();
  }

  static String resolveWorkflowCurrentNode(
      Set<String> activeNodes, String workflowStatus, String fallbackNodeCode) {
    if (EmptyChecks.isNotEmpty(activeNodes)) {
      return String.join(",", activeNodes);
    }
    if (isWorkflowTerminal(workflowStatus)) {
      return WorkflowNodeCode.END.code();
    }
    return fallbackNodeCode;
  }

  private static boolean isWorkflowTerminal(String workflowStatus) {
    return WorkflowRunStatus.SUCCESS.code().equals(workflowStatus)
        || WorkflowRunStatus.FAILED.code().equals(workflowStatus)
        || WorkflowRunStatus.TERMINATED.code().equals(workflowStatus)
        || WorkflowRunStatus.SUCCESS_DRY_RUN.code().equals(workflowStatus)
        || WorkflowRunStatus.FAILED_DRY_RUN.code().equals(workflowStatus);
  }
}

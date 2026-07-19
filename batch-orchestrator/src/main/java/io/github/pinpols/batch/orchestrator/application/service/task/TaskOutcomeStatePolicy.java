package io.github.pinpols.batch.orchestrator.application.service.task;

import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import io.github.pinpols.batch.common.enums.WorkflowNodeCode;
import io.github.pinpols.batch.common.enums.WorkflowNodeRunStatus;
import io.github.pinpols.batch.common.enums.WorkflowRunStatus;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
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
    return JobInstanceStatus.SUCCESS.code().equals(status)
        || JobInstanceStatus.FAILED.code().equals(status)
        || JobInstanceStatus.PARTIAL_FAILED.code().equals(status)
        || JobInstanceStatus.CANCELLED.code().equals(status)
        || JobInstanceStatus.TERMINATED.code().equals(status)
        || JobInstanceStatus.SUCCESS_DRY_RUN.code().equals(status)
        || JobInstanceStatus.FAILED_DRY_RUN.code().equals(status);
  }

  static Set<String> parseActiveNodes(String currentNodeCode) {
    Set<String> activeNodes = new LinkedHashSet<>();
    if (currentNodeCode == null || currentNodeCode.isBlank()) {
      return activeNodes;
    }
    for (String nodeCode : currentNodeCode.split(",")) {
      if (nodeCode == null || nodeCode.isBlank()) {
        continue;
      }
      activeNodes.add(nodeCode.trim());
    }
    return activeNodes;
  }

  static Set<String> resolveActiveNodeCodes(List<WorkflowNodeRunEntity> nodeRuns) {
    Map<String, WorkflowNodeRunEntity> latestByNode = new LinkedHashMap<>();
    for (WorkflowNodeRunEntity nodeRun : nodeRuns) {
      if (nodeRun == null || nodeRun.getNodeCode() == null) {
        continue;
      }
      latestByNode.merge(
          nodeRun.getNodeCode(),
          nodeRun,
          (left, right) ->
              Optional.ofNullable(right.getRunSeq()).orElse(0)
                      >= Optional.ofNullable(left.getRunSeq()).orElse(0)
                  ? right
                  : left);
    }
    Set<String> activeNodes = new LinkedHashSet<>();
    latestByNode.forEach(
        (nodeCode, nodeRun) -> {
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

  static boolean isDryRun(JobInstanceEntity instance) {
    return instance != null && Boolean.TRUE.equals(instance.getDryRun());
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
    if (activeNodes != null && !activeNodes.isEmpty()) {
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

package io.github.pinpols.batch.orchestrator.application.service.task;

import io.github.pinpols.batch.common.enums.PartitionStatus;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.orchestrator.domain.entity.NodePartitionAssignment;
import io.github.pinpols.batch.orchestrator.domain.entity.PartitionStatusRef;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure node-level partition progress calculation used by task outcome processing. */
final class NodePartitionProgressCalculator {

  private NodePartitionProgressCalculator() {}

  static Result calculate(
      List<PartitionStatusRef> statusRefs,
      List<NodePartitionAssignment> assignments,
      String nodeCode,
      WorkflowRunEntity workflowRun) {
    if (EmptyChecks.isBlank(nodeCode)) {
      return new Result(0, 0, 0, Set.of());
    }

    Map<Long, String> statusById = new LinkedHashMap<>();
    for (PartitionStatusRef ref : statusRefs) {
      if (EmptyChecks.isNotNull(ref) && EmptyChecks.isNotNull(ref.id())) {
        statusById.put(ref.id(), ref.partitionStatus());
      }
    }

    Set<Long> nodePartitionIds = new LinkedHashSet<>();
    for (NodePartitionAssignment assignment : assignments) {
      if (EmptyChecks.isNull(assignment) || EmptyChecks.isNull(assignment.jobPartitionId())) {
        continue;
      }
      String taskNodeCode = resolveTaskNodeCode(assignment.nodeCode(), workflowRun, nodeCode);
      if (nodeCode.equals(taskNodeCode)) {
        nodePartitionIds.add(assignment.jobPartitionId());
      }
    }

    long successCount = 0L;
    long failedCount = 0L;
    for (Long partitionId : nodePartitionIds) {
      String status = statusById.get(partitionId);
      if (PartitionStatus.SUCCESS.code().equals(status)) {
        successCount++;
      } else if (PartitionStatus.FAILED.code().equals(status)) {
        failedCount++;
      }
    }
    return new Result(nodePartitionIds.size(), successCount, failedCount, nodePartitionIds);
  }

  private static String resolveTaskNodeCode(
      String payloadNodeCode, WorkflowRunEntity workflowRun, String fallbackNodeCode) {
    if (EmptyChecks.isNotBlank(payloadNodeCode)) {
      return payloadNodeCode;
    }
    if (EmptyChecks.isNotNull(workflowRun)
        && EmptyChecks.isNotNull(workflowRun.getCurrentNodeCode())
        && EmptyChecks.isNotBlank(workflowRun.getCurrentNodeCode())) {
      Set<String> activeNodes =
          TaskOutcomeStatePolicy.parseActiveNodes(workflowRun.getCurrentNodeCode());
      if (activeNodes.size() == 1) {
        return activeNodes.iterator().next();
      }
    }
    return fallbackNodeCode;
  }

  record Result(int partitionCount, long successCount, long failedCount, Set<Long> partitionIds) {

    boolean allFinished() {
      return partitionCount > 0 && successCount + failedCount == partitionCount;
    }
  }
}

package io.github.pinpols.batch.orchestrator.application.service.task;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.enums.PartitionStatus;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.NodePartitionAssignment;
import io.github.pinpols.batch.orchestrator.domain.entity.PartitionStatusRef;
import java.util.List;
import org.junit.jupiter.api.Test;

class NodePartitionProgressCalculatorTest {

  @Test
  void countsOnlyPartitionsBelongingToCurrentNode() {
    NodePartitionProgressCalculator.Result result = NodePartitionProgressCalculator.calculate(
        List.of(
            new PartitionStatusRef(1L, PartitionStatus.SUCCESS.code()),
            new PartitionStatusRef(2L, PartitionStatus.FAILED.code()),
            new PartitionStatusRef(3L, PartitionStatus.SUCCESS.code())),
        List.of(
            new NodePartitionAssignment(1L, "LOAD"),
            new NodePartitionAssignment(2L, "LOAD"),
            new NodePartitionAssignment(3L, "TRANSFORM")),
        "LOAD",
        null);

    assertThat(result.partitionCount()).isEqualTo(2);
    assertThat(result.successCount()).isEqualTo(1);
    assertThat(result.failedCount()).isEqualTo(1);
    assertThat(result.partitionIds()).containsExactly(1L, 2L);
    assertThat(result.allFinished()).isTrue();
  }

  @Test
  void usesTheSingleActiveNodeWhenAssignmentPayloadIsMissing() {
    WorkflowRunEntity workflowRun = new WorkflowRunEntity();
    workflowRun.setCurrentNodeCode("LOAD");

    NodePartitionProgressCalculator.Result result = NodePartitionProgressCalculator.calculate(
        List.of(new PartitionStatusRef(1L, PartitionStatus.SUCCESS.code())),
        List.of(new NodePartitionAssignment(1L, null)),
        "LOAD",
        workflowRun);

    assertThat(result.partitionCount()).isEqualTo(1);
    assertThat(result.successCount()).isEqualTo(1);
    assertThat(result.allFinished()).isTrue();
  }
}

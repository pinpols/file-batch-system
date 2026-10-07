package io.github.pinpols.batch.orchestrator.application.service.task;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.enums.PartitionStatus;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.NodePartitionAssignment;
import io.github.pinpols.batch.orchestrator.domain.entity.PartitionStatusRef;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("节点分区进度计算器: 归属当前节点的分区统计口径")
class NodePartitionProgressCalculatorTest {

  @Test
  @DisplayName("只统计归属当前节点的分区, 汇总成功与失败数量并判定全部完成")
  void shouldCountOnlyPartitions_whenAssignedToCurrentNode() {
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
  @DisplayName("归属信息缺失时按唯一的活跃节点统计分区进度")
  void shouldUseSingleActiveNode_whenAssignmentInfoMissing() {
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

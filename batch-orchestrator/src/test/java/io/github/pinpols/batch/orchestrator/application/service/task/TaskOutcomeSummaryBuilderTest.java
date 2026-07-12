package io.github.pinpols.batch.orchestrator.application.service.task;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.enums.PartitionStatus;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.domain.command.TaskOutcomeCommand;
import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TaskOutcomeSummaryBuilderTest {

  @Test
  @DisplayName("buildOutputSummary: 持久化 worker outputs 和 verifierFailures")
  void buildOutputSummaryPersistsOutputsAndVerifierFailures() {
    TaskOutcomeCommand command =
        TaskOutcomeCommand.builder()
            .tenantId("ta")
            .taskId(10L)
            .success(true)
            .outputs(Map.of("rows", 100, "objectKey", "exports/a.csv"))
            .verifierFailures(List.of(Map.of("code", "COUNT_MISMATCH")))
            .build();

    Map<?, ?> summary =
        JsonUtils.fromJson(TaskOutcomeSummaryBuilder.buildOutputSummary(command, null), Map.class);

    assertThat(summary.get("outputs")).isEqualTo(Map.of("rows", 100, "objectKey", "exports/a.csv"));
    assertThat(summary.get("verifierFailures"))
        .isEqualTo(List.of(Map.of("code", "COUNT_MISMATCH")));
  }

  @Test
  @DisplayName("aggregateSuccessfulPartitionOutputs: 单分片保持旧 outputs 形状")
  void aggregateSinglePartitionKeepsLegacyOutputShape() {
    JobPartitionEntity partition =
        partition(1L, 1, "p1", PartitionStatus.SUCCESS.code(), Map.of("rows", 100));

    Map<String, Object> outputs =
        TaskOutcomeSummaryBuilder.aggregateSuccessfulPartitionOutputs(
            List.of(partition), commandOutputs(Map.of("rows", 999)));

    assertThat(outputs).isEqualTo(Map.of("rows", 100));
  }

  @Test
  @DisplayName("aggregateSuccessfulPartitionOutputs: 多分片包装为 partitionedOutputs")
  void aggregateMultiplePartitionsWrapsPartitionedOutputs() {
    JobPartitionEntity first =
        partition(1L, 1, "p1", PartitionStatus.SUCCESS.code(), Map.of("rows", 100));
    JobPartitionEntity second =
        partition(2L, 2, "p2", PartitionStatus.SUCCESS.code(), Map.of("rows", 200));
    JobPartitionEntity failed =
        partition(3L, 3, "p3", PartitionStatus.FAILED.code(), Map.of("rows", 300));

    Map<String, Object> outputs =
        TaskOutcomeSummaryBuilder.aggregateSuccessfulPartitionOutputs(
            List.of(first, second, failed), commandOutputs(Map.of("rows", 999)));

    assertThat(outputs)
        .containsEntry("partitioned", true)
        .containsEntry("partitionCount", 3)
        .containsEntry("successPartitionCount", 2)
        .containsEntry("failedPartitionCount", 1L);
    assertThat(outputs.get("partitionedOutputs"))
        .isEqualTo(
            List.of(
                Map.of(
                    "partitionId",
                    1L,
                    "partitionNo",
                    1,
                    "partitionKey",
                    "p1",
                    "outputs",
                    Map.of("rows", 100)),
                Map.of(
                    "partitionId",
                    2L,
                    "partitionNo",
                    2,
                    "partitionKey",
                    "p2",
                    "outputs",
                    Map.of("rows", 200))));
  }

  @Test
  @DisplayName("failed partition count: FAILED/CANCELLED/TERMINATED 都计入失败分片")
  void failedPartitionCountIncludesCancelledAndTerminated() {
    JobPartitionEntity success =
        partition(1L, 1, "p1", PartitionStatus.SUCCESS.code(), Map.of("rows", 100));
    JobPartitionEntity secondSuccess =
        partition(5L, 5, "p5", PartitionStatus.SUCCESS.code(), Map.of("rows", 200));
    JobPartitionEntity failed = partition(2L, 2, "p2", PartitionStatus.FAILED.code(), Map.of());
    JobPartitionEntity cancelled =
        partition(3L, 3, "p3", PartitionStatus.CANCELLED.code(), Map.of());
    JobPartitionEntity terminated =
        partition(4L, 4, "p4", PartitionStatus.TERMINATED.code(), Map.of());

    Map<String, Object> aggregated =
        TaskOutcomeSummaryBuilder.aggregateSuccessfulPartitionOutputs(
            List.of(success, secondSuccess, failed, cancelled, terminated),
            commandOutputs(Map.of()));

    assertThat(aggregated).containsEntry("failedPartitionCount", 3L);
  }

  @Test
  @DisplayName("perf(#5): 计数版 buildJobInstanceResultSummary 与列表版逐字段等价")
  void countBasedResultSummaryEqualsListBased() {
    io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity jobInstance =
        new io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity();
    jobInstance.setId(77L);
    List<JobPartitionEntity> partitions =
        List.of(
            partition(1L, 1, "p1", PartitionStatus.SUCCESS.code(), Map.of()),
            partition(2L, 2, "p2", PartitionStatus.SUCCESS.code(), Map.of()),
            partition(3L, 3, "p3", PartitionStatus.FAILED.code(), Map.of()),
            partition(4L, 4, "p4", PartitionStatus.CANCELLED.code(), Map.of()),
            partition(5L, 5, "p5", PartitionStatus.TERMINATED.code(), Map.of()),
            partition(6L, 6, "p6", PartitionStatus.RUNNING.code(), Map.of()));
    TaskOutcomeCommand command =
        TaskOutcomeCommand.builder()
            .tenantId("ta")
            .taskId(10L)
            .success(false)
            .errorCode("E_X")
            .errorMessage("boom")
            .build();

    // 轻量投影(与列表同一批分区状态)。
    List<io.github.pinpols.batch.orchestrator.domain.entity.PartitionStatusRef> statusRefs =
        partitions.stream()
            .map(
                p ->
                    new io.github.pinpols.batch.orchestrator.domain.entity.PartitionStatusRef(
                        p.getId(), p.getPartitionStatus()))
            .toList();
    long successCount =
        statusRefs.stream()
            .filter(r -> PartitionStatus.SUCCESS.code().equals(r.partitionStatus()))
            .count();

    Map<?, ?> listBased =
        JsonUtils.fromJson(
            TaskOutcomeSummaryBuilder.buildJobInstanceResultSummary(
                jobInstance, partitions, command),
            Map.class);
    Map<?, ?> countBased =
        JsonUtils.fromJson(
            TaskOutcomeSummaryBuilder.buildJobInstanceResultSummary(
                jobInstance,
                successCount,
                TaskOutcomeSummaryBuilder.countBroadFailed(statusRefs),
                command),
            Map.class);

    assertThat(countBased.get("successPartitions")).isEqualTo(listBased.get("successPartitions"));
    assertThat(countBased.get("failedPartitions")).isEqualTo(listBased.get("failedPartitions"));
    assertThat(countBased.get("successPartitions")).isEqualTo(2);
    assertThat(countBased.get("failedPartitions")).isEqualTo(3); // FAILED + CANCELLED + TERMINATED
    assertThat(countBased.get("jobInstanceId")).isEqualTo(listBased.get("jobInstanceId"));
    assertThat(countBased.get("lastErrorCode")).isEqualTo(listBased.get("lastErrorCode"));
  }

  @Test
  @DisplayName("filterPartitionsByIds: 只保留当前 workflow node 的分片")
  void filterPartitionsByIdsSelectsCurrentNodePartitions() {
    JobPartitionEntity first = partition(1L, 1, "p1", PartitionStatus.SUCCESS.code(), Map.of());
    JobPartitionEntity second = partition(2L, 2, "p2", PartitionStatus.SUCCESS.code(), Map.of());

    assertThat(TaskOutcomeSummaryBuilder.filterPartitionsByIds(List.of(first, second), Set.of(2L)))
        .containsExactly(second);
  }

  private TaskOutcomeCommand commandOutputs(Map<String, Object> outputs) {
    return TaskOutcomeCommand.builder()
        .tenantId("ta")
        .taskId(10L)
        .success(true)
        .outputs(outputs)
        .build();
  }

  private JobPartitionEntity partition(
      long id, int partitionNo, String partitionKey, String status, Map<String, Object> outputs) {
    JobPartitionEntity partition = new JobPartitionEntity();
    partition.setId(id);
    partition.setPartitionNo(partitionNo);
    partition.setPartitionKey(partitionKey);
    partition.setPartitionStatus(status);
    partition.setOutputSummary(JsonUtils.toJson(Map.of("outputs", outputs)));
    return partition;
  }
}

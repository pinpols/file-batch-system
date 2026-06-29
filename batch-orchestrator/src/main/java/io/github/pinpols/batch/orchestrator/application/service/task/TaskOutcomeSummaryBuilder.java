package io.github.pinpols.batch.orchestrator.application.service.task;

import io.github.pinpols.batch.common.enums.PartitionStatus;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.domain.command.TaskOutcomeCommand;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * P2-4 god-class-decomposition extract: 从 {@link DefaultTaskOutcomeService} 抽出的结果摘要构建器。
 *
 * <p>覆盖原 service 内 3 个 result-shaping helper:
 *
 * <ul>
 *   <li>{@code buildOutputSummary} — task 终态 result_summary JSON
 *   <li>{@code buildJobInstanceResultSummary} — job_instance result_summary JSON(含分区聚合)
 *   <li>{@code serializeOutputs} — workflow_node_run.output JSONB 序列化(ADR-009)
 * </ul>
 *
 * <p>纯函数,无 Spring 依赖。提取后 service 核心方法不再混杂"造 JSON"细节。
 */
final class TaskOutcomeSummaryBuilder {

  private TaskOutcomeSummaryBuilder() {}

  static String buildOutputSummary(TaskOutcomeCommand command, JobTaskEntity task) {
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("taskId", command == null ? null : command.taskId());
    summary.put("tenantId", command == null ? null : command.tenantId());
    summary.put("success", command != null && command.success());
    summary.put("resultSummary", command == null ? null : command.resultSummary());
    summary.put("errorCode", command == null ? null : command.errorCode());
    summary.put("errorMessage", command == null ? null : command.errorMessage());
    summary.put("outputs", command == null ? null : command.outputs());
    summary.put("verifierFailures", command == null ? null : command.verifierFailures());
    summary.put("taskPayload", task == null ? null : task.getTaskPayload());
    summary.put("recordedAt", BatchDateTimeSupport.utcNow().toString());
    return JsonUtils.toJson(summary);
  }

  static String buildJobInstanceResultSummary(
      JobInstanceEntity jobInstance,
      List<JobPartitionEntity> partitions,
      TaskOutcomeCommand command) {
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("jobInstanceId", jobInstance == null ? null : jobInstance.getId());
    summary.put("lastTaskId", command == null ? null : command.taskId());
    summary.put("successPartitions", countByStatus(partitions, PartitionStatus.SUCCESS.code()));
    summary.put("failedPartitions", countByStatus(partitions, PartitionStatus.FAILED.code()));
    summary.put("lastErrorCode", command == null ? null : command.errorCode());
    summary.put("lastErrorMessage", command == null ? null : command.errorMessage());
    summary.put("updatedAt", BatchDateTimeSupport.utcNow().toString());
    return JsonUtils.toJson(summary);
  }

  /**
   * ADR-009 Stage 1.2: 把 worker 上报的 outputs Map 序列化为 JSON 字符串,写入 workflow_node_run.output JSONB
   * 列。null/empty 直接返回 null(不写空对象),让 DSL 解析按"无产出"语义 fallback。
   */
  static String serializeOutputs(Map<String, Object> outputs) {
    if (outputs == null || outputs.isEmpty()) {
      return null;
    }
    return JsonUtils.toJson(outputs);
  }

  /**
   * 终态输出聚合契约：
   *
   * <ul>
   *   <li>单个成功分片：返回该分片原始 outputs，保持历史 payload 形状；
   *   <li>多个成功分片：返回 partitionedOutputs 包装，避免最后一个 report 覆盖其它分片产物；
   *   <li>历史数据没有 outputs 字段时，用当前 command.outputs 作为单分片 fallback，多分片则显式保留 null。
   * </ul>
   */
  static Map<String, Object> aggregateSuccessfulPartitionOutputs(
      List<JobPartitionEntity> partitions, TaskOutcomeCommand command) {
    List<JobPartitionEntity> successful = successfulPartitions(partitions);
    if (successful.isEmpty()) {
      return emptyMapIfBlank(command == null ? null : command.outputs());
    }
    if (successful.size() == 1) {
      Map<String, Object> outputs = extractOutputs(successful.get(0));
      if (outputs == null || outputs.isEmpty()) {
        return emptyMapIfBlank(command == null ? null : command.outputs());
      }
      return outputs;
    }

    Map<String, Object> aggregated = new LinkedHashMap<>();
    aggregated.put("partitioned", true);
    aggregated.put("partitionCount", partitions == null ? 0 : partitions.size());
    aggregated.put("successPartitionCount", successful.size());
    aggregated.put(
        "failedPartitionCount", countByStatus(partitions, PartitionStatus.FAILED.code()));
    List<Map<String, Object>> partitionedOutputs = new ArrayList<>(successful.size());
    for (JobPartitionEntity partition : successful) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("partitionId", partition.getId());
      item.put("partitionNo", partition.getPartitionNo());
      item.put("partitionKey", partition.getPartitionKey());
      item.put("outputs", extractOutputs(partition));
      partitionedOutputs.add(item);
    }
    aggregated.put("partitionedOutputs", partitionedOutputs);
    return aggregated;
  }

  static List<JobPartitionEntity> filterPartitionsByIds(
      List<JobPartitionEntity> partitions, Set<Long> partitionIds) {
    if (partitions == null
        || partitions.isEmpty()
        || partitionIds == null
        || partitionIds.isEmpty()) {
      return List.of();
    }
    Set<Long> selectedIds = new LinkedHashSet<>(partitionIds);
    List<JobPartitionEntity> selected = new ArrayList<>();
    for (JobPartitionEntity partition : partitions) {
      if (partition != null && selectedIds.contains(partition.getId())) {
        selected.add(partition);
      }
    }
    return selected;
  }

  private static long countByStatus(List<JobPartitionEntity> partitions, String status) {
    if (partitions == null) {
      return 0L;
    }
    return partitions.stream().filter(p -> status.equals(p.getPartitionStatus())).count();
  }

  private static List<JobPartitionEntity> successfulPartitions(
      List<JobPartitionEntity> partitions) {
    if (partitions == null || partitions.isEmpty()) {
      return List.of();
    }
    List<JobPartitionEntity> successful = new ArrayList<>();
    for (JobPartitionEntity partition : partitions) {
      if (partition != null
          && PartitionStatus.SUCCESS.code().equals(partition.getPartitionStatus())) {
        successful.add(partition);
      }
    }
    return successful;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> extractOutputs(JobPartitionEntity partition) {
    if (partition == null
        || partition.getOutputSummary() == null
        || partition.getOutputSummary().isBlank()) {
      return null;
    }
    Object parsed = JsonUtils.fromJson(partition.getOutputSummary(), Object.class);
    if (!(parsed instanceof Map<?, ?> summary)) {
      return null;
    }
    Object outputs = summary.get("outputs");
    if (outputs instanceof Map<?, ?> outputMap) {
      return new LinkedHashMap<>((Map<String, Object>) outputMap);
    }
    return null;
  }

  private static Map<String, Object> emptyMapIfBlank(Map<String, Object> outputs) {
    if (outputs == null || outputs.isEmpty()) {
      return Collections.emptyMap();
    }
    return outputs;
  }
}

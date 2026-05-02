package com.example.batch.orchestrator.application.service.task;

import com.example.batch.common.enums.PartitionStatus;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.orchestrator.domain.command.TaskOutcomeCommand;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    summary.put("taskPayload", task == null ? null : task.getTaskPayload());
    summary.put("recordedAt", Instant.now().toString());
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
    summary.put("updatedAt", Instant.now().toString());
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

  private static long countByStatus(List<JobPartitionEntity> partitions, String status) {
    if (partitions == null) {
      return 0L;
    }
    return partitions.stream().filter(p -> status.equals(p.getPartitionStatus())).count();
  }
}

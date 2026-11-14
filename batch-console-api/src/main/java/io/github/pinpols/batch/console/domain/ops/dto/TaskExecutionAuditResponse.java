package io.github.pinpols.batch.console.domain.ops.dto;

import java.time.Instant;

/**
 * 任务执行审计：派单时实际生效的参数（含 shell 命令、SQL 文本等）与执行结果，供取证查看。
 *
 * @param taskId 任务 id
 * @param jobInstanceId 所属作业实例 id
 * @param jobCode 作业编码
 * @param taskStatus 任务状态
 * @param effectiveParameters 派单期生效参数快照，已脱敏；未记录时为 null
 * @param outcome 执行者与执行结果
 */
public record TaskExecutionAuditResponse(
    Long taskId,
    Long jobInstanceId,
    String jobCode,
    String taskStatus,
    Object effectiveParameters,
    Outcome outcome) {

  /**
   * 执行者与执行结果。
   *
   * @param assignedWorkerCode 执行该任务的 Worker
   * @param startedAt 开始时间（UTC）
   * @param finishedAt 结束时间（UTC）
   * @param resultSummary Worker 回报的结果摘要，已脱敏
   * @param errorCode 失败码
   * @param errorMessage 失败信息，已脱敏
   */
  public record Outcome(
      String assignedWorkerCode,
      Instant startedAt,
      Instant finishedAt,
      Object resultSummary,
      String errorCode,
      String errorMessage) {}
}

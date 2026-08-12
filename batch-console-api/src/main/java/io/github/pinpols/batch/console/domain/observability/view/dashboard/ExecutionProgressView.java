package io.github.pinpols.batch.console.domain.observability.view.dashboard;

import java.time.Instant;

/** dashboard 单 job + bizDate 的执行进度投影 (job_instance 行级状态 + partition 计数)。 */
@SuppressWarnings("PMD.ExcessiveParameterList") // MyBatis 投影与响应派生字段必须保持同一 typed view
public record ExecutionProgressView(
    Long id,
    String jobCode,
    String instanceNo,
    String instanceStatus,
    Integer expectedPartitions,
    Integer successPartitions,
    Integer failedPartitions,
    Instant startedAt,
    Instant finishedAt,
    Integer completedPartitions,
    Long progressPercent) {

  /** MyBatis 查询只负责数据库投影，派生进度由 service 在同一 typed view 上补齐。 */
  public ExecutionProgressView(
      Long id,
      String jobCode,
      String instanceNo,
      String instanceStatus,
      Integer expectedPartitions,
      Integer successPartitions,
      Integer failedPartitions,
      Instant startedAt,
      Instant finishedAt) {
    this(
        id,
        jobCode,
        instanceNo,
        instanceStatus,
        expectedPartitions,
        successPartitions,
        failedPartitions,
        startedAt,
        finishedAt,
        null,
        null);
  }

  public ExecutionProgressView withDerivedProgress() {
    int expected = expectedPartitions == null ? 0 : expectedPartitions;
    int success = successPartitions == null ? 0 : successPartitions;
    int failed = failedPartitions == null ? 0 : failedPartitions;
    int completed = success + failed;
    long percent = expected > 0 ? Math.round(completed * 100.0 / expected) : 0L;
    return new ExecutionProgressView(
        id,
        jobCode,
        instanceNo,
        instanceStatus,
        expected,
        success,
        failed,
        startedAt,
        finishedAt,
        completed,
        percent);
  }
}

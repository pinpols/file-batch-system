package io.github.pinpols.batch.orchestrator.domain.entity;

import java.time.Instant;

/** 后台监控扫描命中的作业实例，不进入作业执行状态机。 */
@SuppressWarnings("PMD.ExcessiveParameterList") // 固定 SQL 投影 DTO；字段必须与告警扫描查询一一对应。
public record JobMonitoringAlertCandidate(
    String tenantId,
    Long jobInstanceId,
    String jobCode,
    String instanceNo,
    String instanceStatus,
    String triggerType,
    String traceId,
    Instant createdAt,
    Instant startedAt,
    Instant finishedAt,
    Instant monitoringDeadlineAt,
    Integer thresholdSeconds,
    Integer failedPartitionCount,
    String severity) {

  public JobMonitoringAlertCandidate(
      String tenantId,
      Long jobInstanceId,
      String jobCode,
      String instanceNo,
      String instanceStatus,
      String triggerType,
      String traceId,
      Instant createdAt,
      Instant startedAt,
      Instant finishedAt,
      Instant monitoringDeadlineAt,
      Integer thresholdSeconds,
      Integer failedPartitionCount) {
    this(
        tenantId,
        jobInstanceId,
        jobCode,
        instanceNo,
        instanceStatus,
        triggerType,
        traceId,
        createdAt,
        startedAt,
        finishedAt,
        monitoringDeadlineAt,
        thresholdSeconds,
        failedPartitionCount,
        "WARN");
  }
}

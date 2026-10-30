package io.github.pinpols.batch.orchestrator.domain.view;

import java.time.Instant;
import java.time.LocalDate;
import lombok.Builder;

/** 后台监控扫描命中的作业实例，不进入作业执行状态机。 */
@SuppressWarnings("PMD.ExcessiveParameterList") // 固定 SQL 投影 DTO；字段必须与告警扫描查询一一对应。
@Builder
public record JobMonitoringAlertCandidate(
    String tenantId,
    Long jobInstanceId,
    LocalDate bizDate,
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
    String severity,
    Long workflowRunId,
    String nodeCode,
    Instant executionStartedAt,
    Long triggerRequestId) {

  /** 兼容已存在的 SQL 投影调用方。 */
  public JobMonitoringAlertCandidate(
      String tenantId,
      Long jobInstanceId,
      LocalDate bizDate,
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
      String severity,
      Long workflowRunId,
      String nodeCode,
      Instant executionStartedAt) {
    this(
        tenantId,
        jobInstanceId,
        bizDate,
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
        severity,
        workflowRunId,
        nodeCode,
        executionStartedAt,
        null);
  }

  /** 兼容既有候选调用方；未提供实际入口时保持未知，不从派发时间推算。 */
  public JobMonitoringAlertCandidate(
      String tenantId,
      Long jobInstanceId,
      LocalDate bizDate,
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
      String severity,
      Long workflowRunId,
      String nodeCode) {
    this(
        tenantId,
        jobInstanceId,
        bizDate,
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
        severity,
        workflowRunId,
        nodeCode,
        null,
        null);
  }

  public JobMonitoringAlertCandidate(
      String tenantId,
      Long jobInstanceId,
      LocalDate bizDate,
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
    this(
        tenantId,
        jobInstanceId,
        bizDate,
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
        severity,
        null,
        null,
        null,
        null);
  }

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
      Integer failedPartitionCount,
      String severity) {
    this(
        tenantId,
        jobInstanceId,
        null,
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
        severity,
        null,
        null,
        null,
        null);
  }

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
        null,
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
        "WARN",
        null,
        null,
        null,
        null);
  }
}

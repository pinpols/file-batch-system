package io.github.pinpols.batch.orchestrator.domain.query;

/** 发送前按租户、实例和违约类型复核；分区失败沿用扫描回看窗口。 */
public record JobMonitoringRecheckQuery(
    String tenantId,
    Long jobInstanceId,
    Long triggerRequestId,
    String violationType,
    int lookbackSeconds) {

  public JobMonitoringRecheckQuery(
      String tenantId, Long jobInstanceId, String violationType, int lookbackSeconds) {
    this(tenantId, jobInstanceId, null, violationType, lookbackSeconds);
  }
}

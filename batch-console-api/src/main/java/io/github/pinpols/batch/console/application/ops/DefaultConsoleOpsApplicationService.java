package io.github.pinpols.batch.console.application.ops;

import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import io.github.pinpols.batch.common.enums.WorkerRegistryStatus;
import io.github.pinpols.batch.console.domain.job.mapper.JobInstanceMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.AlertEventMapper;
import io.github.pinpols.batch.console.domain.ops.mapper.ApprovalCommandMapper;
import io.github.pinpols.batch.console.domain.ops.mapper.OutboxDeliveryLogMapper;
import io.github.pinpols.batch.console.domain.ops.mapper.OutboxRetryLogMapper;
import io.github.pinpols.batch.console.domain.ops.mapper.WorkerRegistryMapper;
import io.github.pinpols.batch.console.shared.query.TenantIdResolver;
import io.github.pinpols.batch.console.shared.view.ConsoleOpsSummaryResponse;
import io.github.pinpols.batch.console.support.cache.ConsoleQueryCacheService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ConsoleOpsSummaryPort} 的默认实现：聚合多表计数形成运维摘要。
 *
 * <p>P1(2026-05-23 audit):{@link #summary} 内有 9 条独立 count 查询,原本无 tx 包裹, 多次走连接池可能跨多个隐式自动提交连接,read
 * 一致性无法保证。统一加 {@code @Transactional(readOnly = true)} 让全部查询在同一只读事务中,Spring 同时会路由到 readonly
 * 数据源(read-replica)。 (合并为单 SQL 用 FILTER (WHERE ...) 是更优做法,不属本批范围。)
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DefaultConsoleOpsApplicationService implements ConsoleOpsSummaryPort {

  private final TenantIdResolver tenantGuard;
  private final ApprovalCommandMapper approvalCommandMapper;
  private final JobInstanceMapper jobInstanceMapper;
  private final WorkerRegistryMapper workerRegistryMapper;
  private final OutboxRetryLogMapper outboxRetryLogMapper;
  private final OutboxDeliveryLogMapper outboxDeliveryLogMapper;
  private final AlertEventMapper alertEventMapper;
  private final ConsoleQueryCacheService cacheService;

  /** 按租户聚合运维摘要指标。 */
  @Override
  public ConsoleOpsSummaryResponse summary(String tenantId) {
    String resolvedTenantId = tenantGuard.resolveTenant(tenantId);
    return cacheService.getOrLoad(
        "dashboard:" + ConsoleQueryCacheService.keySegment(resolvedTenantId) + ":summary",
        ConsoleQueryCacheService.DASHBOARD_TTL,
        ConsoleOpsSummaryResponse.class,
        () -> loadSummary(resolvedTenantId));
  }

  private ConsoleOpsSummaryResponse loadSummary(String resolvedTenantId) {
    long pendingApprovals = approvalCommandMapper.countByStatus(resolvedTenantId, "PENDING");

    long openAlerts = alertEventMapper.countByStatus(resolvedTenantId, "OPEN");
    long criticalAlerts =
        alertEventMapper.countBySeverityAndStatus(resolvedTenantId, "CRITICAL", "OPEN");
    AlertMetrics alerts = new AlertMetrics(openAlerts, criticalAlerts);

    long runningJobs = jobInstanceMapper.countByStatuses(
        resolvedTenantId, List.of(JobInstanceStatus.RUNNING.code()));
    long failedJobs = jobInstanceMapper.countByStatuses(
        resolvedTenantId,
        List.of(JobInstanceStatus.FAILED.code(), JobInstanceStatus.PARTIAL_FAILED.code()));
    long slaBreaches = jobInstanceMapper.countSlaBreaches(
        resolvedTenantId,
        List.of(
            JobInstanceStatus.CREATED.code(),
            JobInstanceStatus.WAITING.code(),
            JobInstanceStatus.READY.code(),
            JobInstanceStatus.RUNNING.code(),
            JobInstanceStatus.PARTIAL_FAILED.code()));
    JobMetrics jobs = new JobMetrics(runningJobs, failedJobs, slaBreaches);

    long onlineWorkers =
        workerRegistryMapper.countByStatus(resolvedTenantId, WorkerRegistryStatus.ONLINE.code());
    long drainingWorkers =
        workerRegistryMapper.countByStatus(resolvedTenantId, WorkerRegistryStatus.DRAINING.code());
    long offlineWorkers =
        workerRegistryMapper.countByStatus(resolvedTenantId, WorkerRegistryStatus.OFFLINE.code());
    long decommissionedWorkers = workerRegistryMapper.countByStatus(
        resolvedTenantId, WorkerRegistryStatus.DECOMMISSIONED.code());
    WorkerMetrics workers =
        new WorkerMetrics(onlineWorkers, drainingWorkers, offlineWorkers + decommissionedWorkers);

    long outboxRetryBacklog = outboxRetryLogMapper.countByStatuses(
        resolvedTenantId, List.of("WAITING", "RUNNING", "FAILED"));
    long outboxDeliveryFailures = outboxDeliveryLogMapper.countByStatus(resolvedTenantId, "FAILED");
    OutboxMetrics outbox = new OutboxMetrics(outboxRetryBacklog, outboxDeliveryFailures);

    OpsSummaryMetrics metrics =
        new OpsSummaryMetrics(pendingApprovals, alerts, jobs, workers, outbox);
    return toResponse(resolvedTenantId, metrics);
  }

  private ConsoleOpsSummaryResponse toResponse(String tenantId, OpsSummaryMetrics metrics) {
    return ConsoleOpsSummaryResponse.builder()
        .tenantId(tenantId)
        .pendingApprovals(metrics.pendingApprovals())
        .openAlerts(metrics.alerts().openAlerts())
        .criticalAlerts(metrics.alerts().criticalAlerts())
        .runningJobs(metrics.jobs().runningJobs())
        .failedJobs(metrics.jobs().failedJobs())
        .slaBreaches(metrics.jobs().slaBreaches())
        .onlineWorkers(metrics.workers().onlineWorkers())
        .drainingWorkers(metrics.workers().drainingWorkers())
        .offlineWorkers(metrics.workers().offlineWorkers())
        .outboxRetryBacklog(metrics.outbox().outboxRetryBacklog())
        .outboxDeliveryFailures(metrics.outbox().outboxDeliveryFailures())
        .build();
  }

  private record OpsSummaryMetrics(
      long pendingApprovals,
      AlertMetrics alerts,
      JobMetrics jobs,
      WorkerMetrics workers,
      OutboxMetrics outbox) {}

  private record AlertMetrics(long openAlerts, long criticalAlerts) {}

  private record JobMetrics(long runningJobs, long failedJobs, long slaBreaches) {}

  private record WorkerMetrics(long onlineWorkers, long drainingWorkers, long offlineWorkers) {}

  private record OutboxMetrics(long outboxRetryBacklog, long outboxDeliveryFailures) {}
}

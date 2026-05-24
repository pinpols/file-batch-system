package com.example.batch.console.infrastructure.ops;

import com.example.batch.common.enums.JobInstanceStatus;
import com.example.batch.common.enums.WorkerRegistryStatus;
import com.example.batch.console.application.ops.ConsoleOpsApplicationService;
import com.example.batch.console.mapper.AlertEventMapper;
import com.example.batch.console.mapper.ApprovalCommandMapper;
import com.example.batch.console.mapper.JobInstanceMapper;
import com.example.batch.console.mapper.OutboxDeliveryLogMapper;
import com.example.batch.console.mapper.OutboxRetryLogMapper;
import com.example.batch.console.mapper.WorkerRegistryMapper;
import com.example.batch.console.support.auth.ConsoleTenantGuard;
import com.example.batch.console.web.response.ops.ConsoleOpsSummaryResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link com.example.batch.console.application.ConsoleOpsApplicationService} 的默认实现：聚合多表计数形成运维摘要。
 *
 * <p>P1(2026-05-23 audit):{@link #summary} 内有 9 条独立 count 查询,原本无 tx 包裹, 多次走连接池可能跨多个隐式自动提交连接,read
 * 一致性无法保证。统一加 {@code @Transactional(readOnly = true)} 让全部查询在同一只读事务中,Spring 同时会路由到 readonly
 * 数据源(read-replica)。 (合并为单 SQL 用 FILTER (WHERE ...) 是更优做法,不属本批范围。)
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DefaultConsoleOpsApplicationService implements ConsoleOpsApplicationService {

  private final ConsoleTenantGuard tenantGuard;
  private final ApprovalCommandMapper approvalCommandMapper;
  private final JobInstanceMapper jobInstanceMapper;
  private final WorkerRegistryMapper workerRegistryMapper;
  private final OutboxRetryLogMapper outboxRetryLogMapper;
  private final OutboxDeliveryLogMapper outboxDeliveryLogMapper;
  private final AlertEventMapper alertEventMapper;

  /** 按租户聚合运维摘要指标。 */
  @Override
  public ConsoleOpsSummaryResponse summary(String tenantId) {
    String resolvedTenantId = tenantGuard.resolveTenant(tenantId);
    OpsSummaryMetrics metrics =
        new OpsSummaryMetrics(
            approvalCommandMapper.countByStatus(resolvedTenantId, "PENDING"),
            new AlertMetrics(
                alertEventMapper.countByStatus(resolvedTenantId, "OPEN"),
                alertEventMapper.countBySeverityAndStatus(resolvedTenantId, "CRITICAL", "OPEN")),
            new JobMetrics(
                jobInstanceMapper.countByStatuses(
                    resolvedTenantId, List.of(JobInstanceStatus.RUNNING.code())),
                jobInstanceMapper.countByStatuses(
                    resolvedTenantId,
                    List.of(
                        JobInstanceStatus.FAILED.code(), JobInstanceStatus.PARTIAL_FAILED.code())),
                jobInstanceMapper.countSlaBreaches(
                    resolvedTenantId,
                    List.of(
                        JobInstanceStatus.CREATED.code(),
                        JobInstanceStatus.WAITING.code(),
                        JobInstanceStatus.READY.code(),
                        JobInstanceStatus.RUNNING.code(),
                        JobInstanceStatus.PARTIAL_FAILED.code()))),
            new WorkerMetrics(
                workerRegistryMapper.countByStatus(
                    resolvedTenantId, WorkerRegistryStatus.ONLINE.code()),
                workerRegistryMapper.countByStatus(
                    resolvedTenantId, WorkerRegistryStatus.DRAINING.code()),
                workerRegistryMapper.countByStatus(
                        resolvedTenantId, WorkerRegistryStatus.OFFLINE.code())
                    + workerRegistryMapper.countByStatus(
                        resolvedTenantId, WorkerRegistryStatus.DECOMMISSIONED.code())),
            new OutboxMetrics(
                outboxRetryLogMapper.countByStatuses(
                    resolvedTenantId, List.of("WAITING", "RUNNING", "FAILED")),
                outboxDeliveryLogMapper.countByStatus(resolvedTenantId, "FAILED")));
    return toResponse(resolvedTenantId, metrics);
  }

  private ConsoleOpsSummaryResponse toResponse(String tenantId, OpsSummaryMetrics metrics) {
    return new ConsoleOpsSummaryResponse(
        tenantId,
        metrics.pendingApprovals(),
        metrics.alerts().openAlerts(),
        metrics.alerts().criticalAlerts(),
        metrics.jobs().runningJobs(),
        metrics.jobs().failedJobs(),
        metrics.jobs().slaBreaches(),
        metrics.workers().onlineWorkers(),
        metrics.workers().drainingWorkers(),
        metrics.workers().offlineWorkers(),
        metrics.outbox().outboxRetryBacklog(),
        metrics.outbox().outboxDeliveryFailures());
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

package com.example.batch.orchestrator.infrastructure.quota;

import com.example.batch.orchestrator.application.scheduler.QuotaRuntimeStateService;
import com.example.batch.orchestrator.config.QuotaProperties;
import com.example.batch.orchestrator.domain.entity.QuotaRuntimeStateRecord;
import com.example.batch.orchestrator.domain.entity.ResourceQueueRecord;
import com.example.batch.orchestrator.domain.entity.TenantQuotaPolicyRecord;
import com.example.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import com.example.batch.orchestrator.repository.QuotaRuntimeStateRepository;
import com.example.batch.orchestrator.repository.ResourceQueueRepository;
import com.example.batch.orchestrator.repository.TenantQuotaPolicyRepository;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Redis 模式下的 quota 状态周期 snapshot：把 Redis Hash 里的活跃配额状态批量回写到 PG {@code quota_runtime_state}，让
 * console 历史/审计查询、{@code database} 模式回退仍能基于 PG 数据起步。
 *
 * <p>仅 {@code batch.quota.runtime-store=redis}（默认）+ {@code batch.quota.snapshot.enabled=true} 时启用；以
 * {@code tenant_quota_policy} / {@code resource_queue} 配置作为枚举源避免全库 SCAN。
 *
 * <p>每轮工作量 ~ O(enabled tenants × (policies + queues)) ≈ 千量级，5 分钟一次开销可忽略。
 */
@Slf4j
@Component
@ConditionalOnProperty(
    name = "batch.quota.runtime-store",
    havingValue = "redis",
    matchIfMissing = true)
@RequiredArgsConstructor
public class QuotaRuntimeStateSnapshotScheduler {

  private final QuotaRuntimeStateService quotaRuntimeStateService;
  private final QuotaRuntimeStateRepository quotaRuntimeStateRepository;
  private final TenantQuotaPolicyRepository tenantQuotaPolicyRepository;
  private final ResourceQueueRepository resourceQueueRepository;
  private final QuotaProperties quotaProperties;
  private final OrchestratorGracefulShutdown gracefulShutdown;

  @Scheduled(fixedDelayString = "${batch.quota.snapshot.interval-millis:300000}")
  @SchedulerLock(name = "quota_runtime_snapshot", lockAtMostFor = "PT5M", lockAtLeastFor = "PT1M")
  public void scheduledSnapshot() {
    snapshot();
  }

  public void snapshot() {
    if (gracefulShutdown.isDraining()) {
      return;
    }
    if (!quotaProperties.getSnapshot().isEnabled()) {
      return;
    }
    List<String> tenantIds = tenantQuotaPolicyRepository.findDistinctTenantIdsEnabled();
    int snapshotted = 0;
    for (String tenantId : tenantIds) {
      try {
        snapshotted += snapshotTenant(tenantId);
      } catch (DataAccessException ex) {
        // 单个租户失败不影响其他租户：下一轮自然重试
        log.warn("quota snapshot failed for tenant={}: {}", tenantId, ex.getMessage());
      }
    }
    if (snapshotted > 0) {
      log.debug(
          "quota snapshot tick wrote {} rows across {} tenants", snapshotted, tenantIds.size());
    }
  }

  private int snapshotTenant(String tenantId) {
    int written = 0;
    for (TenantQuotaPolicyRecord p :
        tenantQuotaPolicyRepository.findByTenantIdAndEnabled(tenantId, true)) {
      written +=
          writeIfActive(
              tenantId,
              "TENANT_JOBS",
              tenantId,
              p.quotaResetPolicy(),
              p.burstLimit() == null ? 0 : Math.max(0, p.burstLimit()));
      written +=
          writeIfActive(
              tenantId,
              "TENANT_PARTITIONS",
              tenantId,
              p.quotaResetPolicy(),
              p.partitionBurstLimit() == null ? 0 : Math.max(0, p.partitionBurstLimit()));
    }
    for (ResourceQueueRecord q : resourceQueueRepository.findByTenantIdAndEnabled(tenantId, true)) {
      int qburst = q.burstLimit() == null ? 0 : Math.max(0, q.burstLimit());
      written += writeIfActive(tenantId, "QUEUE_JOBS", q.queueCode(), q.quotaResetPolicy(), qburst);
      // 队列分区维度的 burst 当前与队列 burst 共用 burstLimit；如未来分离再追加 partition 列
      written +=
          writeIfActive(tenantId, "QUEUE_PARTITIONS", q.queueCode(), q.quotaResetPolicy(), qburst);
    }
    return written;
  }

  /** 读 Redis 当前快照，若窗口活跃且 peak > 0，则 upsert 一条 PG 记录。窗口已过期 / peak=0 的不写， 避免每轮在 PG 里产出大量空快照行。 */
  @Transactional
  protected int writeIfActive(
      String tenantId, String scope, String ownerCode, String policy, int burstLimit) {
    if (burstLimit <= 0) {
      return 0;
    }
    QuotaRuntimeStateService.QuotaRuntimeSnapshot snap =
        quotaRuntimeStateService.describe(
            new QuotaRuntimeStateService.QuotaDescribeRequest(
                new QuotaRuntimeStateService.QuotaReservationOwner(tenantId, scope, ownerCode),
                policy,
                burstLimit,
                quotaProperties.getSnapshot() == null ? 24 : 24));
    if (snap == null
        || snap.peakBorrowedCount() == null
        || snap.peakBorrowedCount() == 0
        || snap.windowExpiresAt() == null) {
      return 0;
    }
    Instant now = Instant.now();
    QuotaRuntimeStateRecord existing =
        quotaRuntimeStateRepository.findFirstByTenantIdAndQuotaScopeAndOwnerCode(
            tenantId, scope, ownerCode);
    QuotaRuntimeStateRecord toSave;
    if (existing == null) {
      toSave =
          new QuotaRuntimeStateRecord(
              null,
              tenantId,
              scope,
              ownerCode,
              snap.quotaResetPolicy(),
              snap.windowStartedAt(),
              snap.windowExpiresAt(),
              snap.peakBorrowedCount(),
              snap.lastResetAt(),
              now,
              now,
              null);
    } else {
      toSave =
          existing.withRefresh(
              snap.quotaResetPolicy(),
              snap.windowStartedAt(),
              snap.windowExpiresAt(),
              snap.peakBorrowedCount(),
              snap.lastResetAt());
    }
    quotaRuntimeStateRepository.save(toSave);
    return 1;
  }
}

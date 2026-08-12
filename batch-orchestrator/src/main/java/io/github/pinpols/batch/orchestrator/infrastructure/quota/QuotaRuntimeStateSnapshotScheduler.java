package io.github.pinpols.batch.orchestrator.infrastructure.quota;

import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.orchestrator.config.QuotaProperties;
import io.github.pinpols.batch.orchestrator.domain.entity.ResourceQueueEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.TenantQuotaPolicyEntity;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.mapper.ResourceQueueMapper;
import io.github.pinpols.batch.orchestrator.mapper.TenantQuotaPolicyMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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

  private final QuotaRuntimeStateSnapshotWriter snapshotWriter;
  private final TenantQuotaPolicyMapper tenantQuotaPolicyMapper;
  private final ResourceQueueMapper resourceQueueMapper;
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
    List<String> tenantIds = tenantQuotaPolicyMapper.selectDistinctEnabledTenantIds();
    int snapshotted = 0;
    for (String tenantId : tenantIds) {
      if (tenantId == null || tenantId.isBlank()) {
        continue;
      }
      try {
        // RLS Phase B 起 biz.* 表强制 app.tenant_id IS NOT NULL；per-tenant 循环必须绑租户上下文，
        // 否则 mapper SELECT/UPDATE/INSERT 在严格策略下静默 0 行。try/catch 必须包在 runWithTenant 外，
        // 保证 finally 清 ThreadLocal。
        int delta = RlsTenantContextHolder.runWithTenant(tenantId, () -> snapshotTenant(tenantId));
        snapshotted += delta;
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
    for (TenantQuotaPolicyEntity p :
        tenantQuotaPolicyMapper.selectByTenantAndEnabled(tenantId, true)) {
      written += snapshotWriter.writeIfActive(
          tenantId,
          "TENANT_JOBS",
          tenantId,
          p.quotaResetPolicy(),
          p.burstLimit() == null ? 0 : Math.max(0, p.burstLimit()));
      written += snapshotWriter.writeIfActive(
          tenantId,
          "TENANT_PARTITIONS",
          tenantId,
          p.quotaResetPolicy(),
          p.partitionBurstLimit() == null ? 0 : Math.max(0, p.partitionBurstLimit()));
    }
    for (ResourceQueueEntity q : resourceQueueMapper.selectByTenantAndEnabled(tenantId, true)) {
      int qburst = q.burstLimit() == null ? 0 : Math.max(0, q.burstLimit());
      written += snapshotWriter.writeIfActive(
          tenantId, "QUEUE_JOBS", q.queueCode(), q.quotaResetPolicy(), qburst);
      // 队列分区维度的 burst 当前与队列 burst 共用 burstLimit；如未来分离再追加 partition 列
      written += snapshotWriter.writeIfActive(
          tenantId, "QUEUE_PARTITIONS", q.queueCode(), q.quotaResetPolicy(), qburst);
    }
    return written;
  }
}

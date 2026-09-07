package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.orchestrator.config.ResultVersionRetentionProperties;
import io.github.pinpols.batch.orchestrator.domain.entity.ResultVersionEntity;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.mapper.ResultVersionMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * ADR-017 §GC / 保留策略 — 周期归档过期 SUPERSEDED，并清理已归档的 batch 热表行。
 *
 * <p>扫描入口：{@link ResultVersionMapper#selectSupersededOlderThan}（按 deactivated_at + cutoff 过滤）；
 * 每条命中行用 {@link ResultVersionMapper#archiveSuperseded} 先幂等写入 archive 镜像，再标记 ARCHIVED 并按配置清 payload。
 *
 * <p>只清理 batch 热表；archive 镜像保留给 lineage/replay 取证，并由独立 archive 生命周期治理。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResultVersionRetentionScheduler {

  private final ResultVersionMapper resultVersionMapper;
  private final ResultVersionRetentionProperties properties;
  private final OrchestratorGracefulShutdown gracefulShutdown;
  private final BatchDateTimeSupport dateTimeSupport;

  @Scheduled(fixedDelayString = "${batch.result-version.retention.poll-interval-millis:3600000}")
  @SchedulerLock(
      name = "result_version_retention",
      lockAtMostFor = "PT15M",
      lockAtLeastFor = "PT1M")
  public void scheduledScan() {
    if (!properties.isEnabled()) {
      return;
    }
    if (gracefulShutdown.isDraining()) {
      return;
    }
    Instant now = dateTimeSupport.nowInstant();
    int archived = demoteSupersededBatch(now);
    int deleted = purgeArchivedBatch(now);
    if (archived > 0 || deleted > 0) {
      log.info(
          "result_version retention completed: archived={}, deleted={}, at={}",
          archived,
          deleted,
          now);
    }
  }

  /**
   * 单轮把过期的 SUPERSEDED 行推到 ARCHIVED；每条 {@link ResultVersionMapper#archiveSuperseded} 都是单条 UPDATE，
   * 数据库层原子；本方法不再包裹外层事务（之前的 {@code @Transactional} 在 @Scheduled 自调用路径下根本不会生效， 且与
   * {@code @SchedulerLock} 嵌套顺序歧义）。返回真正成功 archived 的行数。
   */
  public int demoteSupersededBatch(Instant now) {
    Instant cutoff = now.minus(Duration.ofDays(properties.getSupersededDays()));
    List<ResultVersionEntity> stale =
        resultVersionMapper.selectSupersededOlderThan(cutoff, properties.getBatchSize());
    if (stale == null || stale.isEmpty()) {
      return 0;
    }
    int archived = 0;
    for (ResultVersionEntity row : stale) {
      if (row == null || row.id() == null || row.tenantId() == null) {
        continue;
      }
      String tenantId = row.tenantId();
      if (tenantId.isBlank()) {
        continue;
      }
      // RLS Phase B：archiveSuperseded 是 UPDATE batch.result_version SET status='ARCHIVED'，
      // 严格策略下必须显式绑定 app.tenant_id 才能命中行。
      int updated = RlsTenantContextHolder.runWithTenant(
          tenantId, () -> resultVersionMapper.archiveSuperseded(tenantId, row.id(), now, true));
      if (updated > 0) {
        archived++;
      }
    }
    return archived;
  }

  /** 删除超过 archived-days 的 batch 热表历史行；仍被 readiness 物化结果引用的行会保留。 */
  public int purgeArchivedBatch(Instant now) {
    Instant cutoff = now.minus(Duration.ofDays(properties.getArchivedDays()));
    List<ResultVersionEntity> stale =
        resultVersionMapper.selectArchivedOlderThan(cutoff, properties.getBatchSize());
    if (stale == null || stale.isEmpty()) {
      return 0;
    }
    int deleted = 0;
    for (ResultVersionEntity row : stale) {
      if (row == null
          || row.id() == null
          || row.tenantId() == null
          || row.tenantId().isBlank()) {
        continue;
      }
      int affected = RlsTenantContextHolder.runWithTenant(
          row.tenantId(), () -> resultVersionMapper.deleteArchived(row.tenantId(), row.id()));
      if (affected > 0) {
        deleted++;
      }
    }
    return deleted;
  }
}

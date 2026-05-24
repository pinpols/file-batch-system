package com.example.batch.orchestrator.infrastructure.scheduler;

import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.orchestrator.config.ResultVersionRetentionProperties;
import com.example.batch.orchestrator.domain.entity.ResultVersionEntity;
import com.example.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import com.example.batch.orchestrator.mapper.ResultVersionMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADR-017 §GC / 保留策略 — 周期把过期 SUPERSEDED 推到 ARCHIVED 并清空 payload_json。
 *
 * <p>扫描入口：{@link ResultVersionMapper#selectSupersededOlderThan}（按 deactivated_at + cutoff 过滤）；
 * 每条命中行用 {@link ResultVersionMapper#archiveSuperseded} 标记 ARCHIVED 并按配置清 payload。
 *
 * <p>当前 Stage 5 不做"ARCHIVED 物理 DELETE"路径——保留 archivedDays 字段做未来扩展点。
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
    int demoted = demoteSupersededBatch(now);
    if (demoted > 0) {
      log.info("result_version retention: demoted {} SUPERSEDED → ARCHIVED at {}", demoted, now);
    }
  }

  /** 单轮把过期的 SUPERSEDED 行推到 ARCHIVED；事务内逐条处理便于 partial 失败时其他行已成功保留。 返回真正成功 archived 的行数。 */
  @Transactional
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
      int updated = resultVersionMapper.archiveSuperseded(row.tenantId(), row.id(), now, true);
      if (updated > 0) {
        archived++;
      }
    }
    return archived;
  }
}

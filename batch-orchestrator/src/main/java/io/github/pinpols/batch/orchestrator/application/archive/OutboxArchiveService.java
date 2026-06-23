package io.github.pinpols.batch.orchestrator.application.archive;

import io.github.pinpols.batch.common.enums.OutboxPublishStatus;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.orchestrator.config.OutboxArchiveProperties;
import io.github.pinpols.batch.orchestrator.mapper.OutboxEventMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * P3-3 archive 系列：outbox_event 自动归档业务层。同删除语义对应 {@code cleanup-outbox-events.sql}。
 *
 * <p>策略：每次 tick 选一种状态归档（PUBLISHED 或 GIVE_UP，由调用方决定），先复制到 {@code archive} schema 冷表，再删热表中的 FK 子表和
 * {@code outbox_event}，单批上限。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxArchiveService {

  private final OutboxEventMapper outboxEventMapper;
  private final OutboxArchiveProperties properties;

  /**
   * 单次归档:按 status + retentionDays 计算 cutoff,单批上限 batchSize。
   *
   * <p>6 步 SQL(3 archive INSERT + 3 hot DELETE)**必须同事务**,否则进程中途崩溃会留下 数据同时存在于冷/热表的不一致态。所有 archive
   * INSERT 走 `on conflict do nothing` 保证 重跑幂等(ShedLock lockAtMost 到期 + 跨实例竞态下同批 ids 可能被两轮取到)。
   */
  @Transactional
  public ArchiveBatchResult archiveOnce(String publishStatus, int retentionDays) {
    if (!properties.isEnabled()) {
      return ArchiveBatchResult.disabled();
    }
    int retention = Math.max(1, retentionDays);
    int batchSize = Math.max(1, properties.getBatchSize());
    Instant cutoff = BatchDateTimeSupport.utcNow().minus(Duration.ofDays(retention));
    List<Long> ids = outboxEventMapper.selectArchivableIds(publishStatus, cutoff, batchSize);
    if (ids.isEmpty()) {
      return ArchiveBatchResult.empty(cutoff);
    }
    int deliveryLogsArchived = outboxEventMapper.archiveEventDeliveryLogsByOutboxIds(ids);
    int retriesArchived = outboxEventMapper.archiveEventOutboxRetriesByOutboxIds(ids);
    int outboxArchived = outboxEventMapper.archiveOutboxEventsByIds(ids);
    int deliveryLogsDeleted = outboxEventMapper.deleteEventDeliveryLogsByOutboxIds(ids);
    int retriesDeleted = outboxEventMapper.deleteEventOutboxRetriesByOutboxIds(ids);
    int outboxDeleted = outboxEventMapper.deleteByIds(ids);
    log.info(
        "outbox archive tick: status={}, cutoff={}, retention={}d, outboxArchived={},"
            + " deliveryLogArchived={}, retryArchived={}, outboxDeleted={}, deliveryLogDeleted={},"
            + " retryDeleted={}",
        publishStatus,
        cutoff,
        retention,
        outboxArchived,
        deliveryLogsArchived,
        retriesArchived,
        outboxDeleted,
        deliveryLogsDeleted,
        retriesDeleted);
    return new ArchiveBatchResult(true, cutoff, ids.size(), outboxDeleted, deliveryLogsDeleted);
  }

  /** 便捷：用 properties 里的 publishedRetentionDays 跑 PUBLISHED 归档。 */
  @Transactional
  public ArchiveBatchResult archivePublished() {
    return archiveOnce(
        OutboxPublishStatus.PUBLISHED.code(), properties.getPublishedRetentionDays());
  }

  /** 便捷：用 properties 里的 giveUpRetentionDays 跑 GIVE_UP 归档。 */
  @Transactional
  public ArchiveBatchResult archiveGiveUp() {
    return archiveOnce(OutboxPublishStatus.GIVE_UP.code(), properties.getGiveUpRetentionDays());
  }

  public record ArchiveBatchResult(
      boolean executed,
      Instant cutoff,
      int candidates,
      int outboxDeleted,
      int deliveryLogsDeleted) {

    public static ArchiveBatchResult disabled() {
      return new ArchiveBatchResult(false, null, 0, 0, 0);
    }

    public static ArchiveBatchResult empty(Instant cutoff) {
      return new ArchiveBatchResult(true, cutoff, 0, 0, 0);
    }

    public boolean hasMore(int batchSize) {
      return executed && candidates >= batchSize;
    }
  }
}

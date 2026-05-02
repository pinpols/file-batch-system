package com.example.batch.orchestrator.application.archive;

import com.example.batch.common.enums.OutboxPublishStatus;
import com.example.batch.orchestrator.config.OutboxArchiveProperties;
import com.example.batch.orchestrator.mapper.OutboxEventMapper;
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

  /** 单次归档：按 status + retentionDays 计算 cutoff，单批上限 batchSize。 */
  public ArchiveBatchResult archiveOnce(String publishStatus, int retentionDays) {
    if (!properties.isEnabled()) {
      return ArchiveBatchResult.disabled();
    }
    int retention = Math.max(1, retentionDays);
    int batchSize = Math.max(1, properties.getBatchSize());
    Instant cutoff = Instant.now().minus(Duration.ofDays(retention));
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

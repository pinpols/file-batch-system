package com.example.batch.orchestrator.infrastructure.archive;

import com.example.batch.orchestrator.application.archive.OutboxArchiveService;
import com.example.batch.orchestrator.application.archive.OutboxArchiveService.ArchiveBatchResult;
import com.example.batch.orchestrator.config.OutboxArchiveProperties;
import com.example.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * P3-3 archive 系列：outbox_event 自动归档调度器。默认每天 03:30 跑（早于 workflow 的 04:15）。
 *
 * <p>每次 tick 先清 PUBLISHED（保留 7 天），再清 GIVE_UP（保留 30 天）；单状态最多连刷 {@link #MAX_BATCHES_PER_TICK}
 * 批防止异常输入无限循环。
 */
@Slf4j
@Component
@ConditionalOnProperty(
    name = "batch.outbox.archive.enabled",
    havingValue = "true",
    matchIfMissing = true)
@RequiredArgsConstructor
public class OutboxArchiveScheduler {

  /** 单次 tick 单状态最多连刷多少批。 */
  private static final int MAX_BATCHES_PER_TICK = 10;

  private final OutboxArchiveService outboxArchiveService;
  private final OutboxArchiveProperties properties;
  private final OrchestratorGracefulShutdown gracefulShutdown;

  @Scheduled(
      cron = "${batch.outbox.archive.cron:0 30 3 * * *}",
      zone = "${batch.timezone.default-zone:Asia/Shanghai}")
  @SchedulerLock(name = "outbox_archive", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
  public void scheduledArchive() {
    archive();
  }

  public void archive() {
    if (gracefulShutdown.isDraining()) {
      return;
    }
    int batchSize = properties.getBatchSize();
    long publishedTotal = drain(outboxArchiveService::archivePublished, batchSize, "PUBLISHED");
    if (gracefulShutdown.isDraining()) {
      return;
    }
    long giveUpTotal = drain(outboxArchiveService::archiveGiveUp, batchSize, "GIVE_UP");
    if (publishedTotal > 0 || giveUpTotal > 0) {
      log.info(
          "outbox archive completed: PUBLISHED={}, GIVE_UP={}, publishedRetention={}d,"
              + " giveUpRetention={}d",
          publishedTotal,
          giveUpTotal,
          properties.getPublishedRetentionDays(),
          properties.getGiveUpRetentionDays());
    }
  }

  private long drain(Supplier<ArchiveBatchResult> archiver, int batchSize, String label) {
    long total = 0;
    for (int i = 0; i < MAX_BATCHES_PER_TICK; i++) {
      ArchiveBatchResult result = archiver.get();
      if (!result.executed()) {
        break;
      }
      total += result.outboxDeleted();
      if (!result.hasMore(batchSize)) {
        break;
      }
      if (gracefulShutdown.isDraining()) {
        break;
      }
    }
    return total;
  }
}

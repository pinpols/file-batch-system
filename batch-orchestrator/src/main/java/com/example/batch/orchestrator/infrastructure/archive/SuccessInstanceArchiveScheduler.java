package com.example.batch.orchestrator.infrastructure.archive;

import com.example.batch.orchestrator.application.archive.SuccessInstanceArchiveService;
import com.example.batch.orchestrator.application.archive.SuccessInstanceArchiveService.ArchiveBatchResult;
import com.example.batch.orchestrator.config.SuccessInstanceArchiveProperties;
import com.example.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * P3-3 archive 系列：SUCCESS / PARTIAL_FAILED job_instance 级联归档调度器。默认每周日 04:30 跑 （与
 * file-archive、quota-reset、workflow-archive 错峰；周频已足够 30 天保留窗口）。
 *
 * <p>单 tick 内最多连刷 {@link #MAX_BATCHES_PER_TICK} 批，每批清 batchSize（默认 1000）个 instance； 命中上限或抓不到候选自然停。
 */
@Slf4j
@Component
@ConditionalOnProperty(
    name = "batch.job-instance.archive.enabled",
    havingValue = "true",
    matchIfMissing = true)
@RequiredArgsConstructor
public class SuccessInstanceArchiveScheduler {

  /** 单次 tick 最多连刷批数：1000 instance × 50 = 50k instance 单次清完封顶。 */
  private static final int MAX_BATCHES_PER_TICK = 50;

  private final SuccessInstanceArchiveService archiveService;
  private final SuccessInstanceArchiveProperties properties;
  private final OrchestratorGracefulShutdown gracefulShutdown;

  @Scheduled(
      cron = "${batch.job-instance.archive.cron:0 30 4 * * SUN}",
      zone = "${batch.timezone.default-zone:Asia/Shanghai}")
  @SchedulerLock(name = "success_instance_archive", lockAtMostFor = "PT2H", lockAtLeastFor = "PT5M")
  public void scheduledArchive() {
    archive();
  }

  public void archive() {
    if (gracefulShutdown.isDraining()) {
      return;
    }
    int totalInstances = 0;
    int batches = 0;
    while (batches < MAX_BATCHES_PER_TICK) {
      ArchiveBatchResult result = archiveService.archiveOnce();
      if (!result.executed()) {
        break;
      }
      totalInstances += result.instancesDeleted();
      batches++;
      if (!result.hasMore(properties.getBatchSize())) {
        break;
      }
      if (gracefulShutdown.isDraining()) {
        break;
      }
    }
    if (totalInstances > 0) {
      log.info(
          "success-instance archive completed: batches={}, instances={}, retentionDays={}",
          batches,
          totalInstances,
          properties.getRetentionDays());
    }
  }
}

package com.example.batch.orchestrator.infrastructure.archive;

import com.example.batch.orchestrator.application.archive.WorkflowArchiveService;
import com.example.batch.orchestrator.application.archive.WorkflowArchiveService.ArchiveBatchResult;
import com.example.batch.orchestrator.config.WorkflowArchiveProperties;
import com.example.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * P3-3 workflow archive 调度器：默认每天 04:15 跑（{@code batch.workflow.archive.cron}），
 * 清理终结态 workflow_run / workflow_node_run。
 *
 * <p>单 tick 处理 {@code batchSize}（默认 5000）条上限，命中上限时立即再来一次直到清干净，
 * 由本调度器内的循环负责（带 ShedLock 内最多 5 次防止异常情况死循环）。
 *
 * <p>{@code @ConditionalOnProperty} 默认启用；运维侧不想让 orchestrator 自动跑可设
 * {@code batch.workflow.archive.enabled=false}（仍可用 SQL 脚本 cleanup-workflow-runs.sql 手工补刀）。
 */
@Slf4j
@Component
@ConditionalOnProperty(
    name = "batch.workflow.archive.enabled",
    havingValue = "true",
    matchIfMissing = true)
@RequiredArgsConstructor
public class WorkflowArchiveScheduler {

  /** 单次 tick 最多连刷多少批：防止异常输入下死循环。 */
  private static final int MAX_BATCHES_PER_TICK = 5;

  private final WorkflowArchiveService workflowArchiveService;
  private final WorkflowArchiveProperties properties;
  private final OrchestratorGracefulShutdown gracefulShutdown;

  @Scheduled(cron = "${batch.workflow.archive.cron:0 15 4 * * *}")
  @SchedulerLock(
      name = "workflow_archive",
      lockAtMostFor = "PT30M",
      lockAtLeastFor = "PT1M")
  public void scheduledArchive() {
    archive();
  }

  /** 业务入口：scheduled / 手工调用都走此方法；不在此处加 ShedLock 便于测试。 */
  public void archive() {
    if (gracefulShutdown.isDraining()) {
      return;
    }
    int totalRuns = 0;
    int totalNodeRuns = 0;
    int batches = 0;
    while (batches < MAX_BATCHES_PER_TICK) {
      ArchiveBatchResult result = workflowArchiveService.archiveOnce();
      if (!result.executed()) {
        break;
      }
      totalRuns += result.workflowRunsDeleted();
      totalNodeRuns += result.workflowNodeRunsDeleted();
      batches++;
      if (!result.hasMore(properties.getBatchSize())) {
        break;
      }
      if (gracefulShutdown.isDraining()) {
        break;
      }
    }
    if (totalRuns > 0 || totalNodeRuns > 0) {
      log.info(
          "workflow archive completed: batches={}, runs={}, nodeRuns={}, retentionDays={}",
          batches,
          totalRuns,
          totalNodeRuns,
          properties.getRetentionDays());
    }
  }
}

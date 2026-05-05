package com.example.batch.orchestrator.application.archive;

import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.orchestrator.config.WorkflowArchiveProperties;
import com.example.batch.orchestrator.mapper.WorkflowRunMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * P3-3 workflow archive 业务层：把 SQL 脚本 {@code cleanup-workflow-runs.sql} 的删除语义移到代码侧， 支持自动调度 + 单元测试覆盖
 * + 多实例 ShedLock 互斥。
 *
 * <p>批量上限设计：单批 {@link WorkflowArchiveProperties#getBatchSize()} 条 workflow_run，
 * 防止一次删几十万行长事务锁表；超出当前阈值剩余条目下 tick 继续清。这种 sweep 模式与 cleanup-historical-failures.sql /
 * OutboxPublishingResetScheduler 一致。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowArchiveService {

  private final WorkflowRunMapper workflowRunMapper;
  private final WorkflowArchiveProperties properties;

  /**
   * 执行一次归档。返回（候选数, 删除的 workflow_run 数, 删除的 workflow_node_run 数）。 候选 = 删除（候选不会比删除多——除非有并发；事务内不会丢删）。
   */
  @Transactional
  public ArchiveBatchResult archiveOnce() {
    if (!properties.isEnabled()) {
      return ArchiveBatchResult.disabled();
    }
    int retention = Math.max(1, properties.getRetentionDays());
    int batchSize = Math.max(1, properties.getBatchSize());
    Instant cutoff = BatchDateTimeSupport.utcNow().minus(Duration.ofDays(retention));
    List<Long> ids = workflowRunMapper.selectArchivableIds(cutoff, batchSize);
    if (ids.isEmpty()) {
      return ArchiveBatchResult.empty(cutoff);
    }
    int nodeRunsDeleted = workflowRunMapper.deleteNodeRunsByWorkflowRunIds(ids);
    int runsDeleted = workflowRunMapper.deleteByIds(ids);
    log.info(
        "workflow archive tick: cutoff={}, retention={}d, runs={}, nodeRuns={}",
        cutoff,
        retention,
        runsDeleted,
        nodeRunsDeleted);
    return new ArchiveBatchResult(true, cutoff, ids.size(), runsDeleted, nodeRunsDeleted);
  }

  public record ArchiveBatchResult(
      boolean executed,
      Instant cutoff,
      int candidates,
      int workflowRunsDeleted,
      int workflowNodeRunsDeleted) {

    public static ArchiveBatchResult disabled() {
      return new ArchiveBatchResult(false, null, 0, 0, 0);
    }

    public static ArchiveBatchResult empty(Instant cutoff) {
      return new ArchiveBatchResult(true, cutoff, 0, 0, 0);
    }

    public boolean hasMore(int batchSize) {
      // 候选数 == batchSize 暗示可能还有未清的（命中 limit）
      return executed && candidates >= batchSize;
    }
  }
}

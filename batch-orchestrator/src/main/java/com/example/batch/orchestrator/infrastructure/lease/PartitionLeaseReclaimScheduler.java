package com.example.batch.orchestrator.infrastructure.lease;

import com.example.batch.common.enums.PartitionStatus;
import com.example.batch.common.enums.TaskStatus;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import com.example.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import com.example.batch.orchestrator.mapper.JobPartitionMapper;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 任务分区租约回收调度器。
 *
 * <p>默认每 15 秒扫描全局 lease 已过期的 {@code READY}/{@code RUNNING} 分区，逐条委派给 {@link PartitionReclaimUnit}
 * 在独立事务（REQUIRES_NEW）内执行两步 CAS。
 *
 * <ul>
 *   <li>partition CAS（resetForDispatch）失败：另一并发流程已推进，安静跳过；
 *   <li>task CAS（resetForRetry）失败：unit 抛 {@link ReclaimRetryableException} 触发该单元事务回滚， partition
 *       状态恢复，下一轮 reclaim 仍能扫到该过期 lease 行重试。
 * </ul>
 *
 * <p>新增兜底 sweeper（{@code orphan-sweep-enabled}，默认开启）：清理升级前残留的 "partition READY + lease_expire_at
 * NULL + 关联 task RUNNING" 死态，避免历史半成功态永久长期停滞。
 *
 * <p>ShedLock 锁名 {@code partition_lease_reclaim} / {@code partition_orphan_sweep}， 同时使用 {@link
 * AtomicBoolean} 防止单节点重入。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "batch.partition-lease",
    name = "reclaim-scheduler-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class PartitionLeaseReclaimScheduler {

  private final JobPartitionMapper jobPartitionMapper;
  private final PartitionReclaimUnit reclaimUnit;
  private final BatchOrchestratorGovernanceProperties governance;
  private final OrchestratorGracefulShutdown gracefulShutdown;
  private final AtomicBoolean reclaimRunning = new AtomicBoolean(false);
  private final AtomicBoolean sweepRunning = new AtomicBoolean(false);

  // P1-5：lockAtMostFor 是 ShedLock 持锁上限。超过这个时间，二号实例可拿同名锁同时跑循环 →
  // partition 已被 reclaim 后 version 已 +1 → 二号实例算出的 eventKey 不同（含 version） →
  // 同 partition 真双 dispatch（unique 索引救不了）。lockAtMostFor 暂留 PT2M（与 publishing-timeout 对齐），
  // 但循环内主动设置时间预算 budgetMillis = lockAtMostFor × 75%，超时即 break 并由下个 tick 接力。
  private static final long LOCK_AT_MOST_MILLIS = 120_000L;
  private static final long LOOP_BUDGET_MILLIS = (long) (LOCK_AT_MOST_MILLIS * 0.75);

  @Scheduled(fixedDelayString = "${batch.partition-lease.reclaim-interval-millis:15000}")
  @SchedulerLock(name = "partition_lease_reclaim", lockAtMostFor = "PT2M", lockAtLeastFor = "PT10S")
  public void reclaimExpiredPartitions() {
    if (gracefulShutdown.isDraining()) {
      return;
    }
    if (!reclaimRunning.compareAndSet(false, true)) {
      return;
    }
    try {
      int batchSize = governance.partitionLease().getReclaimBatchSize();
      List<JobPartitionEntity> expiredPartitions =
          jobPartitionMapper.selectExpiredLeasesGlobal(
              PartitionStatus.READY.code(),
              PartitionStatus.RUNNING.code(),
              batchSize > 0 ? batchSize : null);
      if (batchSize > 0 && expiredPartitions.size() >= batchSize) {
        log.warn(
            "partition reclaim hit batch ceiling: scanned={}, batchSize={}; either lease window"
                + " too short, worker capacity insufficient, or scheduler lagging — investigate",
            expiredPartitions.size(),
            batchSize);
      }
      long deadlineNanos = System.nanoTime() + LOOP_BUDGET_MILLIS * 1_000_000L;
      int processed = 0;
      for (JobPartitionEntity partition : expiredPartitions) {
        // P1-5：占用持锁时间预算前主动让出，避免 lock 过期 → 第二实例并发 reclaim 双 dispatch
        if (System.nanoTime() > deadlineNanos) {
          log.warn(
              "partition reclaim yielded after {}/{}: lock budget {}ms exhausted, deferring"
                  + " {} partitions to next tick",
              processed,
              expiredPartitions.size(),
              LOOP_BUDGET_MILLIS,
              expiredPartitions.size() - processed);
          break;
        }
        try {
          reclaimUnit.reclaim(partition);
        } catch (ReclaimRetryableException retryable) {
          log.warn(
              "partition reclaim rolled back, will retry next cycle: {}", retryable.getMessage());
        } catch (RuntimeException unexpected) {
          // 单条 partition 异常不影响后续行处理：下一行继续。
          log.error(
              "partition reclaim unexpected error: tenantId={}, partitionId={}",
              partition.getTenantId(),
              partition.getId(),
              unexpected);
        }
        processed++;
      }
    } finally {
      reclaimRunning.set(false);
    }
  }

  /**
   * 兜底 sweeper：扫描 "partition_status=READY 且 lease_expire_at IS NULL 但仍有 RUNNING task" 的死态。
   *
   * <p>新代码已通过 REQUIRES_NEW + 抛异常回滚消除产生路径；本 sweeper 仅清理升级前残留的历史死态， 通过对仍在 RUNNING 的 task 调 {@code
   * resetForRetry}（不再触碰 partition）让其回到 READY，下一轮派发兜底走正常路径。
   */
  @Scheduled(fixedDelayString = "${batch.partition-lease.orphan-sweep-interval-millis:300000}")
  @SchedulerLock(name = "partition_orphan_sweep", lockAtMostFor = "PT2M", lockAtLeastFor = "PT30S")
  public void sweepOrphanRunningTasks() {
    if (!governance.partitionLease().isOrphanSweepEnabled()) {
      return;
    }
    if (gracefulShutdown.isDraining()) {
      return;
    }
    if (!sweepRunning.compareAndSet(false, true)) {
      return;
    }
    try {
      Instant olderThan =
          BatchDateTimeSupport.utcNow()
              .minusSeconds(governance.partitionLease().getOrphanSweepGraceSeconds());
      int batchSize = governance.partitionLease().getOrphanSweepBatchSize();
      List<JobPartitionEntity> orphans =
          jobPartitionMapper.selectOrphanReadyPartitionsWithRunningTask(
              PartitionStatus.READY.code(), TaskStatus.RUNNING.code(), olderThan, batchSize);
      if (orphans.isEmpty()) {
        return;
      }
      log.warn(
          "orphan-sweep found {} dead-state partitions (READY + RUNNING task)", orphans.size());
      for (JobPartitionEntity partition : orphans) {
        try {
          reclaimUnit.reclaim(partition);
        } catch (ReclaimRetryableException retryable) {
          log.warn("orphan-sweep retryable: {}", retryable.getMessage());
        } catch (RuntimeException unexpected) {
          log.error(
              "orphan-sweep unexpected error: tenantId={}, partitionId={}",
              partition.getTenantId(),
              partition.getId(),
              unexpected);
        }
      }
    } finally {
      sweepRunning.set(false);
    }
  }
}

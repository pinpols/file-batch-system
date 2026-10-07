package io.github.pinpols.batch.orchestrator.infrastructure.lease;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.PartitionStatus;
import io.github.pinpols.batch.common.enums.TaskStatus;
import io.github.pinpols.batch.orchestrator.config.PartitionLeaseProperties;
import io.github.pinpols.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 单元测试：{@link PartitionLeaseReclaimScheduler}. */
@DisplayName("分区租约回收调度器:验证过期分区回收与孤儿任务扫描的委派,跳过与异常容忍行为")
class PartitionLeaseReclaimSchedulerTest {

  private JobPartitionMapper jobPartitionMapper;
  private PartitionReclaimUnit reclaimUnit;
  private OrchestratorGracefulShutdown gracefulShutdown;
  private PartitionLeaseProperties props;
  private PartitionLeaseReclaimScheduler scheduler;

  @BeforeEach
  void setUp() {
    jobPartitionMapper = mock(JobPartitionMapper.class);
    reclaimUnit = mock(PartitionReclaimUnit.class);
    gracefulShutdown = mock(OrchestratorGracefulShutdown.class);

    props = new PartitionLeaseProperties();
    props.setExpireSeconds(60L);
    props.setReclaimBatchSize(500);
    props.setOrphanSweepEnabled(true);
    props.setOrphanSweepBatchSize(200);
    props.setOrphanSweepGraceSeconds(120L);
    BatchOrchestratorGovernanceProperties governance =
        mock(BatchOrchestratorGovernanceProperties.class);
    when(governance.partitionLease()).thenReturn(props);

    scheduler = new PartitionLeaseReclaimScheduler(
        jobPartitionMapper, reclaimUnit, governance, gracefulShutdown);
  }

  @Test
  @DisplayName("没有过期分区时不做任何回收动作")
  void shouldDoNothingWhenNoExpiredPartitions() {
    when(jobPartitionMapper.selectExpiredLeasesGlobal(
            PartitionStatus.READY.code(), PartitionStatus.RUNNING.code(), 500))
        .thenReturn(List.of());

    scheduler.reclaimExpiredPartitions();

    verify(reclaimUnit, never()).reclaim(any());
  }

  @Test
  @DisplayName("每个过期分区都单独委派给回收单元处理")
  void shouldDelegateEachExpiredPartitionToReclaimUnit() {
    JobPartitionEntity p1 = expiredPartition("t1", 1L);
    JobPartitionEntity p2 = expiredPartition("t1", 2L);
    when(jobPartitionMapper.selectExpiredLeasesGlobal(
            PartitionStatus.READY.code(), PartitionStatus.RUNNING.code(), 500))
        .thenReturn(List.of(p1, p2));

    scheduler.reclaimExpiredPartitions();

    verify(reclaimUnit).reclaim(p1);
    verify(reclaimUnit).reclaim(p2);
  }

  @Test
  @DisplayName("单个分区抛出可重试异常时仍继续回收其余分区")
  void shouldContinueProcessingWhenSinglePartitionThrowsRetryable() {
    JobPartitionEntity p1 = expiredPartition("t1", 1L);
    JobPartitionEntity p2 = expiredPartition("t1", 2L);
    when(jobPartitionMapper.selectExpiredLeasesGlobal(
            PartitionStatus.READY.code(), PartitionStatus.RUNNING.code(), 500))
        .thenReturn(List.of(p1, p2));
    doThrow(new ReclaimRetryableException("task version conflict"))
        .when(reclaimUnit)
        .reclaim(p1);
    doNothing().when(reclaimUnit).reclaim(p2);

    scheduler.reclaimExpiredPartitions();

    verify(reclaimUnit).reclaim(p1);
    verify(reclaimUnit).reclaim(p2);
  }

  @Test
  @DisplayName("单个分区抛出非预期异常时不中断后续分区的回收")
  void shouldContinueProcessingWhenSinglePartitionThrowsUnexpected() {
    JobPartitionEntity p1 = expiredPartition("t1", 1L);
    JobPartitionEntity p2 = expiredPartition("t1", 2L);
    when(jobPartitionMapper.selectExpiredLeasesGlobal(
            PartitionStatus.READY.code(), PartitionStatus.RUNNING.code(), 500))
        .thenReturn(List.of(p1, p2));
    doThrow(new RuntimeException("db down")).when(reclaimUnit).reclaim(p1);

    scheduler.reclaimExpiredPartitions();

    verify(reclaimUnit).reclaim(p2);
  }

  @Test
  @DisplayName("实例处于停机排空状态时既不查询也不回收")
  void shouldSkipReclaimWhenDraining() {
    when(gracefulShutdown.isDraining()).thenReturn(true);

    scheduler.reclaimExpiredPartitions();

    verify(jobPartitionMapper, never()).selectExpiredLeasesGlobal(anyString(), anyString(), any());
    verify(reclaimUnit, never()).reclaim(any());
  }

  @Test
  @DisplayName("开启孤儿清理时把任务仍在运行但分区已就绪的记录委派回收")
  void shouldRunSweeperAndDelegateOrphans() {
    JobPartitionEntity orphan = expiredPartition("t1", 99L);
    when(jobPartitionMapper.selectOrphanReadyPartitionsWithRunningTask(
            eq(PartitionStatus.READY.code()),
            eq(TaskStatus.RUNNING.code()),
            any(Instant.class),
            anyInt()))
        .thenReturn(List.of(orphan));

    scheduler.sweepOrphanRunningTasks();

    verify(reclaimUnit, atLeastOnce()).reclaim(orphan);
  }

  @Test
  @DisplayName("关闭孤儿清理时不再扫描遗留分区")
  void shouldSkipSweeperWhenDisabled() {
    props.setOrphanSweepEnabled(false);

    scheduler.sweepOrphanRunningTasks();

    verify(jobPartitionMapper, never())
        .selectOrphanReadyPartitionsWithRunningTask(anyString(), anyString(), any(), anyInt());
  }

  @Test
  @DisplayName("批大小配置为 0 时以空值查询,由数据库使用默认上限")
  void shouldPassNullBatchSizeWhenZero() {
    props.setReclaimBatchSize(0);
    when(jobPartitionMapper.selectExpiredLeasesGlobal(
            PartitionStatus.READY.code(), PartitionStatus.RUNNING.code(), null))
        .thenReturn(List.of());

    scheduler.reclaimExpiredPartitions();

    verify(jobPartitionMapper, atLeast(1))
        .selectExpiredLeasesGlobal(
            PartitionStatus.READY.code(), PartitionStatus.RUNNING.code(), null);
  }

  @Test
  @DisplayName("单轮返回数量达到批大小上限时仍逐个完成回收且流程不中断")
  void shouldWarnWhenBatchCeilingHit() {
    // 简单验证：返回数量 == batchSize 时仍能正常处理（warn 走日志，不可观察，但流程不应中断）。
    props.setReclaimBatchSize(2);
    JobPartitionEntity p1 = expiredPartition("t1", 1L);
    JobPartitionEntity p2 = expiredPartition("t1", 2L);
    when(jobPartitionMapper.selectExpiredLeasesGlobal(
            PartitionStatus.READY.code(), PartitionStatus.RUNNING.code(), 2))
        .thenReturn(List.of(p1, p2));

    scheduler.reclaimExpiredPartitions();

    verify(reclaimUnit, times(2)).reclaim(any());
  }

  // ── 辅助方法 ───────────────────────────────────────────────────────────────

  private static JobPartitionEntity expiredPartition(String tenantId, Long partitionId) {
    JobPartitionEntity p = new JobPartitionEntity();
    p.setTenantId(tenantId);
    p.setId(partitionId);
    p.setJobInstanceId(partitionId * 10);
    p.setVersion(0L);
    return p;
  }
}

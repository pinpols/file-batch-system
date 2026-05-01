package com.example.batch.orchestrator.infrastructure.lease;

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

import com.example.batch.common.enums.PartitionStatus;
import com.example.batch.common.enums.TaskStatus;
import com.example.batch.orchestrator.config.PartitionLeaseProperties;
import com.example.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import com.example.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import com.example.batch.orchestrator.mapper.JobPartitionMapper;
import com.example.batch.orchestrator.mapper.JobTaskMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 单元测试：{@link PartitionLeaseReclaimScheduler}. */
class PartitionLeaseReclaimSchedulerTest {

  private JobPartitionMapper jobPartitionMapper;
  private JobTaskMapper jobTaskMapper;
  private PartitionReclaimUnit reclaimUnit;
  private OrchestratorGracefulShutdown gracefulShutdown;
  private PartitionLeaseProperties props;
  private PartitionLeaseReclaimScheduler scheduler;

  @BeforeEach
  void setUp() {
    jobPartitionMapper = mock(JobPartitionMapper.class);
    jobTaskMapper = mock(JobTaskMapper.class);
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

    scheduler =
        new PartitionLeaseReclaimScheduler(
            jobPartitionMapper, jobTaskMapper, reclaimUnit, governance, gracefulShutdown);
  }

  @Test
  void shouldDoNothingWhenNoExpiredPartitions() {
    when(jobPartitionMapper.selectExpiredLeasesGlobal(
            PartitionStatus.READY.code(), PartitionStatus.RUNNING.code(), 500))
        .thenReturn(List.of());

    scheduler.reclaimExpiredPartitions();

    verify(reclaimUnit, never()).reclaim(any());
  }

  @Test
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
  void shouldContinueProcessingWhenSinglePartitionThrowsRetryable() {
    JobPartitionEntity p1 = expiredPartition("t1", 1L);
    JobPartitionEntity p2 = expiredPartition("t1", 2L);
    when(jobPartitionMapper.selectExpiredLeasesGlobal(
            PartitionStatus.READY.code(), PartitionStatus.RUNNING.code(), 500))
        .thenReturn(List.of(p1, p2));
    doThrow(new ReclaimRetryableException("task version conflict")).when(reclaimUnit).reclaim(p1);
    doNothing().when(reclaimUnit).reclaim(p2);

    scheduler.reclaimExpiredPartitions();

    verify(reclaimUnit).reclaim(p1);
    verify(reclaimUnit).reclaim(p2);
  }

  @Test
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
  void shouldSkipReclaimWhenDraining() {
    when(gracefulShutdown.isDraining()).thenReturn(true);

    scheduler.reclaimExpiredPartitions();

    verify(jobPartitionMapper, never()).selectExpiredLeasesGlobal(anyString(), anyString(), any());
    verify(reclaimUnit, never()).reclaim(any());
  }

  @Test
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
  void shouldSkipSweeperWhenDisabled() {
    props.setOrphanSweepEnabled(false);

    scheduler.sweepOrphanRunningTasks();

    verify(jobPartitionMapper, never())
        .selectOrphanReadyPartitionsWithRunningTask(anyString(), anyString(), any(), anyInt());
  }

  @Test
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

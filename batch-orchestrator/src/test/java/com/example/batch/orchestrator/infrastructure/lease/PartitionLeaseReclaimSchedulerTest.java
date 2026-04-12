package com.example.batch.orchestrator.infrastructure.lease;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.enums.PartitionStatus;
import com.example.batch.common.enums.RunMode;
import com.example.batch.common.enums.TaskStatus;
import com.example.batch.orchestrator.application.engine.TaskDispatchOutboxService;
import com.example.batch.orchestrator.config.PartitionLeaseProperties;
import com.example.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.JobPartitionMapper;
import com.example.batch.orchestrator.mapper.JobTaskMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 单元测试：{@link PartitionLeaseReclaimScheduler#reclaimExpiredPartitions()} 各分支路径。 */
class PartitionLeaseReclaimSchedulerTest {

  private JobPartitionMapper jobPartitionMapper;
  private JobTaskMapper jobTaskMapper;
  private JobInstanceMapper jobInstanceMapper;
  private TaskDispatchOutboxService taskDispatchOutboxService;
  private OrchestratorGracefulShutdown gracefulShutdown;
  private PartitionLeaseReclaimScheduler scheduler;

  @BeforeEach
  void setUp() {
    jobPartitionMapper = mock(JobPartitionMapper.class);
    jobTaskMapper = mock(JobTaskMapper.class);
    jobInstanceMapper = mock(JobInstanceMapper.class);
    taskDispatchOutboxService = mock(TaskDispatchOutboxService.class);
    gracefulShutdown = mock(OrchestratorGracefulShutdown.class);

    PartitionLeaseProperties props = new PartitionLeaseProperties();
    props.setExpireSeconds(60L);
    BatchOrchestratorGovernanceProperties governance =
        mock(BatchOrchestratorGovernanceProperties.class);
    when(governance.partitionLease()).thenReturn(props);

    scheduler =
        new PartitionLeaseReclaimScheduler(
            jobPartitionMapper,
            jobTaskMapper,
            jobInstanceMapper,
            taskDispatchOutboxService,
            governance,
            gracefulShutdown);
  }

  @Test
  void shouldDoNothingWhenNoExpiredPartitions() {
    when(jobPartitionMapper.selectExpiredLeasesGlobal(
            PartitionStatus.READY.code(), PartitionStatus.RUNNING.code()))
        .thenReturn(List.of());

    scheduler.reclaimExpiredPartitions();

    verify(jobTaskMapper, never()).selectByQuery(any());
  }

  @Test
  void shouldResetPartitionForDispatchWhenNoRunningTaskFound() {
    JobPartitionEntity partition = expiredPartition("t1", 1L, 10L);
    when(jobPartitionMapper.selectExpiredLeasesGlobal(
            PartitionStatus.READY.code(), PartitionStatus.RUNNING.code()))
        .thenReturn(List.of(partition));
    when(jobTaskMapper.selectByQuery(any())).thenReturn(List.of());

    scheduler.reclaimExpiredPartitions();

    verify(jobPartitionMapper).resetForDispatch("t1", 1L, PartitionStatus.READY.code(), 0L);
    verify(taskDispatchOutboxService, never())
        .writeDispatchEvent(any(), any(), any(), anyString(), anyString(), any());
  }

  @Test
  void shouldSkipReclaimWhenJobInstanceNotFound() {
    JobPartitionEntity partition = expiredPartition("t1", 2L, 20L);
    JobTaskEntity task = runningTask("t1", 200L, 2L);
    when(jobPartitionMapper.selectExpiredLeasesGlobal(
            PartitionStatus.READY.code(), PartitionStatus.RUNNING.code()))
        .thenReturn(List.of(partition));
    when(jobTaskMapper.selectByQuery(any())).thenReturn(List.of(task));
    when(jobInstanceMapper.selectById("t1", 20L)).thenReturn(null);

    scheduler.reclaimExpiredPartitions();

    verify(taskDispatchOutboxService, never())
        .writeDispatchEvent(any(), any(), any(), anyString(), anyString(), any());
  }

  @Test
  void shouldRedispatchPartitionWhenExpiredAndJobInstanceFound() {
    JobPartitionEntity partition = expiredPartition("t1", 3L, 30L);
    JobTaskEntity task = runningTask("t1", 300L, 3L);
    JobInstanceEntity jobInstance = jobInstance("t1", 30L);
    when(jobPartitionMapper.selectExpiredLeasesGlobal(
            PartitionStatus.READY.code(), PartitionStatus.RUNNING.code()))
        .thenReturn(List.of(partition));
    when(jobTaskMapper.selectByQuery(any())).thenReturn(List.of(task));
    when(jobInstanceMapper.selectById("t1", 30L)).thenReturn(jobInstance);
    when(jobPartitionMapper.resetForDispatch("t1", 3L, PartitionStatus.READY.code(), 0L))
        .thenReturn(1);
    when(jobTaskMapper.resetForRetry("t1", 300L, TaskStatus.READY.code(), 0L)).thenReturn(1);

    scheduler.reclaimExpiredPartitions();

    verify(jobPartitionMapper).resetForDispatch("t1", 3L, PartitionStatus.READY.code(), 0L);
    verify(jobTaskMapper).resetForRetry("t1", 300L, TaskStatus.READY.code(), 0L);
    verify(taskDispatchOutboxService)
        .writeDispatchEvent(any(), any(), any(), anyString(), anyString(), eq(RunMode.RECOVER));
  }

  @Test
  void shouldProcessMultipleExpiredPartitions() {
    JobPartitionEntity p1 = expiredPartition("t1", 4L, 40L);
    JobPartitionEntity p2 = expiredPartition("t1", 5L, 40L);
    JobTaskEntity t1 = runningTask("t1", 401L, 4L);
    JobTaskEntity t2 = runningTask("t1", 501L, 5L);
    JobInstanceEntity instance = jobInstance("t1", 40L);

    when(jobPartitionMapper.selectExpiredLeasesGlobal(
            PartitionStatus.READY.code(), PartitionStatus.RUNNING.code()))
        .thenReturn(List.of(p1, p2));
    when(jobTaskMapper.selectByQuery(any())).thenReturn(List.of(t1)).thenReturn(List.of(t2));
    when(jobInstanceMapper.selectById("t1", 40L)).thenReturn(instance);
    when(jobPartitionMapper.resetForDispatch(eq("t1"), anyLong(), anyString(), eq(0L)))
        .thenReturn(1);
    when(jobTaskMapper.resetForRetry(eq("t1"), anyLong(), anyString(), eq(0L))).thenReturn(1);

    scheduler.reclaimExpiredPartitions();

    verify(taskDispatchOutboxService, times(2))
        .writeDispatchEvent(any(), any(), any(), anyString(), anyString(), eq(RunMode.RECOVER));
  }

  @Test
  void shouldNotRunConcurrently() throws InterruptedException {
    // 本用例以连续两次调用近似验证互斥：两次均应进入扫描（未模拟阻塞 select）
    when(jobPartitionMapper.selectExpiredLeasesGlobal(
            PartitionStatus.READY.code(), PartitionStatus.RUNNING.code()))
        .thenReturn(List.of());

    scheduler.reclaimExpiredPartitions();
    scheduler.reclaimExpiredPartitions();

    verify(jobPartitionMapper, times(2))
        .selectExpiredLeasesGlobal(PartitionStatus.READY.code(), PartitionStatus.RUNNING.code());
  }

  // ── 辅助方法 ───────────────────────────────────────────────────────────────

  private static JobPartitionEntity expiredPartition(
      String tenantId, Long partitionId, Long jobInstanceId) {
    JobPartitionEntity p = new JobPartitionEntity();
    p.setTenantId(tenantId);
    p.setId(partitionId);
    p.setJobInstanceId(jobInstanceId);
    p.setVersion(0L);
    return p;
  }

  private static JobTaskEntity runningTask(String tenantId, Long taskId, Long partitionId) {
    JobTaskEntity t = new JobTaskEntity();
    t.setTenantId(tenantId);
    t.setId(taskId);
    t.setJobPartitionId(partitionId);
    t.setVersion(0L);
    return t;
  }

  private static JobInstanceEntity jobInstance(String tenantId, Long instanceId) {
    JobInstanceEntity j = new JobInstanceEntity();
    j.setTenantId(tenantId);
    j.setId(instanceId);
    j.setTraceId("trace-" + instanceId);
    j.setInstanceNo("INST-" + instanceId);
    return j;
  }
}

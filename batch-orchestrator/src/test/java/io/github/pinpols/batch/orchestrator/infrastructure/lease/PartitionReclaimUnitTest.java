package io.github.pinpols.batch.orchestrator.infrastructure.lease;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.PartitionStatus;
import io.github.pinpols.batch.common.enums.RunMode;
import io.github.pinpols.batch.common.enums.TaskStatus;
import io.github.pinpols.batch.orchestrator.application.engine.TaskDispatchOutboxService;
import io.github.pinpols.batch.orchestrator.config.PartitionLeaseProperties;
import io.github.pinpols.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobStepInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 单元测试：{@link PartitionReclaimUnit}.
 *
 * <p>重点覆盖 v6 hardening：第二步 task CAS 失败必须抛 {@link ReclaimRetryableException} 触发事务回滚， eventKey 必须包含
 * version 作为天然幂等键避免 outbox 唯一约束误吞重试。
 */
class PartitionReclaimUnitTest {

  private JobPartitionMapper jobPartitionMapper;
  private JobTaskMapper jobTaskMapper;
  private JobStepInstanceMapper jobStepInstanceMapper;
  private JobInstanceMapper jobInstanceMapper;
  private TaskDispatchOutboxService outboxService;
  private PartitionReclaimUnit unit;

  @BeforeEach
  void setUp() {
    jobPartitionMapper = mock(JobPartitionMapper.class);
    jobTaskMapper = mock(JobTaskMapper.class);
    jobStepInstanceMapper = mock(JobStepInstanceMapper.class);
    jobInstanceMapper = mock(JobInstanceMapper.class);
    outboxService = mock(TaskDispatchOutboxService.class);

    PartitionLeaseProperties props = new PartitionLeaseProperties();
    props.setExpireSeconds(60L);
    BatchOrchestratorGovernanceProperties governance =
        mock(BatchOrchestratorGovernanceProperties.class);
    when(governance.partitionLease()).thenReturn(props);

    unit =
        new PartitionReclaimUnit(
            jobPartitionMapper,
            jobTaskMapper,
            jobStepInstanceMapper,
            jobInstanceMapper,
            outboxService,
            governance);
  }

  @Test
  void shouldResetPartitionForDispatchWhenNoRunningTaskFound() {
    JobPartitionEntity partition = expiredPartition("t1", 1L, 10L, 5L);
    when(jobTaskMapper.selectByQuery(any())).thenReturn(List.of());

    unit.reclaim(partition);

    verify(jobPartitionMapper).resetForDispatch("t1", 1L, PartitionStatus.READY.code(), 5L);
    verify(outboxService, never())
        .writeDispatchEvent(any(), any(), any(), anyString(), anyString(), any());
  }

  @Test
  void shouldThrowRetryableWhenTaskCasFailsAfterPartitionReset() {
    JobPartitionEntity partition = expiredPartition("t1", 1L, 10L, 5L);
    JobTaskEntity task = runningTask("t1", 100L, 1L, 7L);
    JobInstanceEntity instance = jobInstance("t1", 10L);
    when(jobTaskMapper.selectByQuery(any())).thenReturn(List.of(task));
    when(jobInstanceMapper.selectById("t1", 10L)).thenReturn(instance);
    when(jobPartitionMapper.resetForDispatch("t1", 1L, PartitionStatus.READY.code(), 5L))
        .thenReturn(1);
    // 第二步 task CAS 失败
    when(jobTaskMapper.resetForRetry("t1", 100L, TaskStatus.READY.code(), 7L)).thenReturn(0);

    assertThatThrownBy(() -> unit.reclaim(partition))
        .isInstanceOf(ReclaimRetryableException.class)
        .hasMessageContaining("partitionId=1")
        .hasMessageContaining("taskId=100");

    verify(outboxService, never())
        .writeDispatchEvent(any(), any(), any(), anyString(), anyString(), any());
  }

  @Test
  void shouldSilentlyReturnWhenPartitionCasFails() {
    JobPartitionEntity partition = expiredPartition("t1", 1L, 10L, 5L);
    JobTaskEntity task = runningTask("t1", 100L, 1L, 7L);
    JobInstanceEntity instance = jobInstance("t1", 10L);
    when(jobTaskMapper.selectByQuery(any())).thenReturn(List.of(task));
    when(jobInstanceMapper.selectById("t1", 10L)).thenReturn(instance);
    // partition 第一步就失败，task 不被触发
    when(jobPartitionMapper.resetForDispatch("t1", 1L, PartitionStatus.READY.code(), 5L))
        .thenReturn(0);

    unit.reclaim(partition);

    verify(jobTaskMapper, never()).resetForRetry(anyString(), any(), anyString(), any());
    verify(outboxService, never())
        .writeDispatchEvent(any(), any(), any(), anyString(), anyString(), any());
  }

  @Test
  void shouldWriteOutboxWithVersionedEventKeyOnSuccess() {
    JobPartitionEntity partition = expiredPartition("t1", 1L, 10L, 5L);
    JobTaskEntity task = runningTask("t1", 100L, 1L, 7L);
    JobInstanceEntity instance = jobInstance("t1", 10L);
    when(jobTaskMapper.selectByQuery(any())).thenReturn(List.of(task));
    when(jobInstanceMapper.selectById("t1", 10L)).thenReturn(instance);
    when(jobPartitionMapper.resetForDispatch("t1", 1L, PartitionStatus.READY.code(), 5L))
        .thenReturn(1);
    when(jobTaskMapper.resetForRetry("t1", 100L, TaskStatus.READY.code(), 7L)).thenReturn(1);
    when(jobStepInstanceMapper.resetForRetryByJobTaskId("t1", 100L, 0, TaskStatus.READY.code()))
        .thenReturn(1);

    unit.reclaim(partition);

    ArgumentCaptor<String> eventKeyCaptor = ArgumentCaptor.forClass(String.class);
    verify(outboxService, times(1))
        .writeDispatchEvent(
            eq(instance),
            eq(task),
            eq(partition),
            anyString(),
            eventKeyCaptor.capture(),
            eq(RunMode.RECOVER));
    // eventKey 必须包含 version；保证多次 reclaim 不被 outbox UNIQUE(tenant_id, event_key) ON CONFLICT 静默捕获并抑制
    assertThat(eventKeyCaptor.getValue()).isEqualTo("t1:reclaim:1:v5");
  }

  @Test
  void shouldSkipWhenJobInstanceNotFound() {
    JobPartitionEntity partition = expiredPartition("t1", 1L, 10L, 5L);
    JobTaskEntity task = runningTask("t1", 100L, 1L, 7L);
    when(jobTaskMapper.selectByQuery(any())).thenReturn(List.of(task));
    when(jobInstanceMapper.selectById("t1", 10L)).thenReturn(null);

    unit.reclaim(partition);

    verify(jobPartitionMapper, never()).resetForDispatch(anyString(), any(), anyString(), any());
    verify(outboxService, never())
        .writeDispatchEvent(any(), any(), any(), anyString(), anyString(), any());
  }

  // ── 辅助方法 ──

  private static JobPartitionEntity expiredPartition(
      String tenantId, Long partitionId, Long jobInstanceId, Long version) {
    JobPartitionEntity p = new JobPartitionEntity();
    p.setTenantId(tenantId);
    p.setId(partitionId);
    p.setJobInstanceId(jobInstanceId);
    p.setVersion(version);
    return p;
  }

  private static JobTaskEntity runningTask(
      String tenantId, Long taskId, Long partitionId, Long version) {
    JobTaskEntity t = new JobTaskEntity();
    t.setTenantId(tenantId);
    t.setId(taskId);
    t.setJobPartitionId(partitionId);
    t.setVersion(version);
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

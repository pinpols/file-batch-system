package io.github.pinpols.batch.orchestrator.infrastructure.lease;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.PartitionStatus;
import io.github.pinpols.batch.common.enums.RunMode;
import io.github.pinpols.batch.common.enums.TaskErrorCodes;
import io.github.pinpols.batch.common.enums.TaskStatus;
import io.github.pinpols.batch.orchestrator.application.engine.TaskDispatchOutboxService;
import io.github.pinpols.batch.orchestrator.application.engine.command.TaskDispatchEventCommand;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskOutcomeService;
import io.github.pinpols.batch.orchestrator.config.PartitionLeaseProperties;
import io.github.pinpols.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import io.github.pinpols.batch.orchestrator.domain.command.TaskOutcomeCommand;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobStepInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

/**
 * 单元测试：{@link PartitionReclaimUnit}.
 *
 * <p>重点覆盖 v6 hardening：第二步 task CAS 失败必须抛 {@link ReclaimRetryableException} 触发事务回滚， eventKey 必须包含
 * version 作为天然幂等键避免 outbox 唯一约束误吞重试。
 */
@DisplayName("过期占位的租约回收单元,验证无运行任务时的状态重置,比较交换未命中时的静默放弃以及失败回滚与重试事件键的幂等语义")
class PartitionReclaimUnitTest {

  private JobPartitionMapper jobPartitionMapper;
  private JobTaskMapper jobTaskMapper;
  private JobStepInstanceMapper jobStepInstanceMapper;
  private JobInstanceMapper jobInstanceMapper;
  private TaskDispatchOutboxService outboxService;
  private TaskOutcomeService taskOutcomeService;
  private PartitionReclaimUnit unit;

  @BeforeEach
  void setUp() {
    jobPartitionMapper = mock(JobPartitionMapper.class);
    jobTaskMapper = mock(JobTaskMapper.class);
    jobStepInstanceMapper = mock(JobStepInstanceMapper.class);
    jobInstanceMapper = mock(JobInstanceMapper.class);
    outboxService = mock(TaskDispatchOutboxService.class);
    taskOutcomeService = mock(TaskOutcomeService.class);

    PartitionLeaseProperties props = new PartitionLeaseProperties();
    props.setExpireSeconds(60L);
    BatchOrchestratorGovernanceProperties governance =
        mock(BatchOrchestratorGovernanceProperties.class);
    when(governance.partitionLease()).thenReturn(props);

    unit = new PartitionReclaimUnit(
        jobPartitionMapper,
        jobTaskMapper,
        jobStepInstanceMapper,
        jobInstanceMapper,
        outboxService,
        taskOutcomeService,
        governance);
  }

  @Test
  @DisplayName("占位不存在运行中的任务时直接重置为可分派状态,后续可被重新认领且不写入派发事件")
  void shouldResetPartitionForDispatchWhenNoRunningTaskFound() {
    JobPartitionEntity partition = expiredPartition("t1", 1L, 10L, 5L);
    when(jobTaskMapper.selectByQuery(any())).thenReturn(List.of());

    unit.reclaim(partition);

    verify(jobPartitionMapper).resetForLeaseReclaim("t1", 1L, PartitionStatus.READY.code(), 5L);
    verify(outboxService, never())
        .writeDispatchEvent(ArgumentMatchers.argThat(
            command -> command != null && command.traceId() != null && command.eventKey() != null));
  }

  @Test
  @DisplayName("回收先取得实例围栏再读取和修改运行态行")
  void shouldAcquireInstanceFenceBeforeReadingAndChangingRuntimeRows() {
    JobPartitionEntity partition = expiredPartition("t1", 1L, 10L, 5L);
    when(jobTaskMapper.selectByQuery(any())).thenReturn(List.of());

    unit.reclaim(partition);

    var order = inOrder(jobInstanceMapper, jobTaskMapper, jobPartitionMapper);
    order.verify(jobInstanceMapper).acquireInstanceAdvisoryLock("t1", 10L);
    order.verify(jobTaskMapper).selectByQuery(any());
    order
        .verify(jobPartitionMapper)
        .resetForLeaseReclaim("t1", 1L, PartitionStatus.READY.code(), 5L);
  }

  @Test
  @DisplayName("占位重置成功后任务状态比较交换失败时抛出可重试异常,异常携带占位与任务标识且不写入派发事件")
  void shouldThrowRetryableWhenTaskCasFailsAfterPartitionReset() {
    JobPartitionEntity partition = expiredPartition("t1", 1L, 10L, 5L);
    JobTaskEntity task = runningTask("t1", 100L, 1L, 7L);
    JobInstanceEntity instance = jobInstance("t1", 10L);
    when(jobTaskMapper.selectByQuery(any())).thenReturn(List.of(task));
    when(jobInstanceMapper.selectById("t1", 10L)).thenReturn(instance);
    when(jobPartitionMapper.resetForLeaseReclaim("t1", 1L, PartitionStatus.READY.code(), 5L))
        .thenReturn(1);
    // 第二步 task CAS 失败
    when(jobTaskMapper.resetForLeaseReclaim("t1", 100L, TaskStatus.READY.code(), 7L))
        .thenReturn(0);

    assertThatThrownBy(() -> unit.reclaim(partition))
        .isInstanceOf(ReclaimRetryableException.class)
        .hasMessageContaining("partitionId=1")
        .hasMessageContaining("taskId=100");

    verify(outboxService, never())
        .writeDispatchEvent(ArgumentMatchers.argThat(
            command -> command != null && command.traceId() != null && command.eventKey() != null));
  }

  @Test
  @DisplayName("占位重置比较交换未命中时本轮静默放弃,不重置任务状态也不写入派发事件")
  void shouldSilentlyReturnWhenPartitionCasFails() {
    JobPartitionEntity partition = expiredPartition("t1", 1L, 10L, 5L);
    JobTaskEntity task = runningTask("t1", 100L, 1L, 7L);
    JobInstanceEntity instance = jobInstance("t1", 10L);
    when(jobTaskMapper.selectByQuery(any())).thenReturn(List.of(task));
    when(jobInstanceMapper.selectById("t1", 10L)).thenReturn(instance);
    // partition 第一步就失败，task 不被触发
    when(jobPartitionMapper.resetForLeaseReclaim("t1", 1L, PartitionStatus.READY.code(), 5L))
        .thenReturn(0);

    unit.reclaim(partition);

    verify(jobTaskMapper, never()).resetForLeaseReclaim(anyString(), any(), anyString(), any());
    verify(outboxService, never())
        .writeDispatchEvent(ArgumentMatchers.argThat(
            command -> command != null && command.traceId() != null && command.eventKey() != null));
  }

  @Test
  @DisplayName("两次重置均命中时写入一条恢复模式的派发事件,事件键携带占位版本以保证多轮回收不被唯一约束吞掉")
  void shouldWriteOutboxWithVersionedEventKeyOnSuccess() {
    JobPartitionEntity partition = expiredPartition("t1", 1L, 10L, 5L);
    JobTaskEntity task = runningTask("t1", 100L, 1L, 7L);
    task.setAssignedWorkerCode("worker-instance-1");
    JobInstanceEntity instance = jobInstance("t1", 10L);
    when(jobTaskMapper.selectByQuery(any())).thenReturn(List.of(task));
    when(jobInstanceMapper.selectById("t1", 10L)).thenReturn(instance);
    when(jobPartitionMapper.resetForLeaseReclaim("t1", 1L, PartitionStatus.READY.code(), 5L))
        .thenReturn(1);
    when(jobTaskMapper.resetForLeaseReclaim("t1", 100L, TaskStatus.READY.code(), 7L))
        .thenReturn(1);
    when(jobStepInstanceMapper.resetForRetryByJobTaskId("t1", 100L, 0, TaskStatus.READY.code()))
        .thenReturn(1);

    unit.reclaim(partition);

    verify(jobPartitionMapper, never()).resetForDispatch(anyString(), any(), anyString(), any());
    verify(jobTaskMapper, never()).resetForRetry(anyString(), any(), anyString(), any());
    ArgumentCaptor<TaskDispatchEventCommand> dispatchCaptor =
        ArgumentCaptor.forClass(TaskDispatchEventCommand.class);
    verify(outboxService, times(1)).writeDispatchEvent(dispatchCaptor.capture());
    assertThat(dispatchCaptor.getValue().jobInstance()).isEqualTo(instance);
    assertThat(dispatchCaptor.getValue().partition()).isEqualTo(partition);
    assertThat(dispatchCaptor.getValue().traceId()).isNotNull();
    assertThat(dispatchCaptor.getValue().runModeOverride()).isEqualTo(RunMode.RECOVER);
    assertThat(dispatchCaptor.getValue().task().getAssignedWorkerCode())
        .as(
            "reclaim resets the persisted assignment so the dispatch must not target the stale worker")
        .isNull();
    // eventKey 必须包含 version；保证多次 reclaim 不被 outbox UNIQUE(tenant_id, event_key) ON CONFLICT 静默捕获并抑制
    assertThat(dispatchCaptor.getValue().eventKey()).isEqualTo("t1:reclaim:1:v5");
  }

  @Test
  @DisplayName("存在运行中的任务但所属作业实例缺失时放弃回收,不重置占位也不写入派发事件")
  void shouldSkipWhenJobInstanceNotFound() {
    JobPartitionEntity partition = expiredPartition("t1", 1L, 10L, 5L);
    JobTaskEntity task = runningTask("t1", 100L, 1L, 7L);
    when(jobTaskMapper.selectByQuery(any())).thenReturn(List.of(task));
    when(jobInstanceMapper.selectById("t1", 10L)).thenReturn(null);

    unit.reclaim(partition);

    verify(jobPartitionMapper, never())
        .resetForLeaseReclaim(anyString(), any(), anyString(), any());
    verify(outboxService, never())
        .writeDispatchEvent(ArgumentMatchers.argThat(
            command -> command != null && command.traceId() != null && command.eventKey() != null));
  }

  @Test
  @DisplayName("已开始非幂等执行的过期任务以外部结果未知终结,不复位或重派")
  void shouldCloseNonIdempotentTaskAsUnknownInsteadOfReplaying() {
    JobPartitionEntity partition = expiredPartition("t1", 1L, 10L, 5L);
    partition.setCurrentInvocationId("inv-7");
    JobTaskEntity task = runningTask("t1", 100L, 1L, 7L);
    task.setTaskStatus(TaskStatus.RUNNING.code());
    task.setAssignedWorkerCode("worker-1");
    task.setNonIdempotentStartedAt(Instant.parse("2026-10-21T00:00:00Z"));
    JobInstanceEntity instance = jobInstance("t1", 10L);
    when(jobTaskMapper.selectByQuery(any())).thenReturn(List.of(task));
    when(jobInstanceMapper.selectById("t1", 10L)).thenReturn(instance);
    when(jobPartitionMapper.selectById("t1", 1L)).thenReturn(partition);
    when(taskOutcomeService.applyTaskOutcome(any())).thenReturn(task);

    unit.reclaim(partition);

    ArgumentCaptor<TaskOutcomeCommand> outcomeCaptor =
        ArgumentCaptor.forClass(TaskOutcomeCommand.class);
    verify(taskOutcomeService).applyTaskOutcome(outcomeCaptor.capture());
    assertThat(outcomeCaptor.getValue().errorCode())
        .isEqualTo(TaskErrorCodes.EXTERNAL_OUTCOME_UNKNOWN);
    assertThat(outcomeCaptor.getValue().partitionInvocationId()).isEqualTo("inv-7");
    assertThat(outcomeCaptor.getValue().workerId()).isEqualTo("worker-1");
    verify(jobPartitionMapper, never())
        .resetForLeaseReclaim(anyString(), any(), anyString(), any());
    verify(jobTaskMapper, never()).lockForReclaimNoWait(anyString(), any());
    verify(jobTaskMapper, never()).resetForLeaseReclaim(anyString(), any(), anyString(), any());
    verify(outboxService, never())
        .writeDispatchEvent(ArgumentMatchers.argThat(
            command -> command != null && command.traceId() != null && command.eventKey() != null));
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

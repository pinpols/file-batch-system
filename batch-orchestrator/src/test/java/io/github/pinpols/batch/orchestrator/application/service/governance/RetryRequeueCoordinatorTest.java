package io.github.pinpols.batch.orchestrator.application.service.governance;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.PartitionStatus;
import io.github.pinpols.batch.common.enums.RetryScheduleStatus;
import io.github.pinpols.batch.common.enums.RunMode;
import io.github.pinpols.batch.common.enums.StepInstanceStatus;
import io.github.pinpols.batch.common.enums.TaskStatus;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.orchestrator.application.engine.TaskDispatchOutboxService;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobStepInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.RetryScheduleEntity;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobStepInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import io.github.pinpols.batch.orchestrator.mapper.RetryScheduleMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RetryRequeueCoordinatorTest {

  private RetryScheduleMapper retryScheduleMapper;
  private JobTaskMapper jobTaskMapper;
  private JobPartitionMapper jobPartitionMapper;
  private JobInstanceMapper jobInstanceMapper;
  private JobStepInstanceMapper jobStepInstanceMapper;
  private TaskDispatchOutboxService taskDispatchOutboxService;
  private RetryRequeueCoordinator coordinator;

  @BeforeEach
  void setUp() {
    retryScheduleMapper = mock(RetryScheduleMapper.class);
    jobTaskMapper = mock(JobTaskMapper.class);
    jobPartitionMapper = mock(JobPartitionMapper.class);
    jobInstanceMapper = mock(JobInstanceMapper.class);
    jobStepInstanceMapper = mock(JobStepInstanceMapper.class);
    taskDispatchOutboxService = mock(TaskDispatchOutboxService.class);
    coordinator = new RetryRequeueCoordinator(
        retryScheduleMapper,
        jobTaskMapper,
        jobPartitionMapper,
        jobInstanceMapper,
        jobStepInstanceMapper,
        taskDispatchOutboxService);
  }

  @Test
  void shouldLeaveRetryScheduleWaitingWhenClaimIsLost() {
    RetryScheduleEntity schedule = retrySchedule(7L, 10L);
    when(retryScheduleMapper.markRunning(
            "tenant-a", 7L, RetryScheduleStatus.WAITING.code(), RetryScheduleStatus.RUNNING.code()))
        .thenReturn(0);

    coordinator.requeueRetry(schedule);

    verify(jobPartitionMapper, never()).selectById(anyString(), anyLong());
    verify(retryScheduleMapper, never())
        .markSuccess(anyString(), anyLong(), anyString(), anyString());
  }

  @Test
  void shouldResetFirstTaskAndEmitRetryDispatchForPartition() {
    JobPartitionEntity partition = partition(10L, 20L, 4L);
    JobInstanceEntity instance = instance(20L);
    JobTaskEntity firstTask = task(30L, 20L, 0, 8L);
    JobTaskEntity laterTask = task(31L, 20L, 1, 9L);
    JobStepInstanceEntity step = step(40L, 2);
    when(jobPartitionMapper.selectById("tenant-a", 10L)).thenReturn(partition);
    when(jobInstanceMapper.selectById("tenant-a", 20L)).thenReturn(instance);
    when(jobTaskMapper.selectByQuery(any())).thenReturn(List.of(laterTask, firstTask));
    when(jobStepInstanceMapper.selectByJobTaskId("tenant-a", 30L)).thenReturn(step);
    when(jobPartitionMapper.resetForDispatch("tenant-a", 10L, PartitionStatus.READY.code(), 4L))
        .thenReturn(1);
    when(jobTaskMapper.resetForRetry("tenant-a", 30L, TaskStatus.READY.code(), 8L))
        .thenReturn(1);

    coordinator.requeuePartition("tenant-a", 10L, "retry-event");

    verify(jobStepInstanceMapper)
        .resetForRetryByJobTaskId("tenant-a", 30L, 3, StepInstanceStatus.READY.code());
    verify(jobPartitionMapper).resetForDispatch("tenant-a", 10L, PartitionStatus.READY.code(), 4L);
    verify(jobTaskMapper).resetForRetry("tenant-a", 30L, TaskStatus.READY.code(), 8L);
    verify(taskDispatchOutboxService)
        .writeDispatchEvent(
            same(instance),
            same(firstTask),
            same(partition),
            eq("trace-20"),
            eq("retry-event"),
            eq(RunMode.RETRY));
  }

  @Test
  void shouldAbortPartitionRequeueOnVersionConflictBeforeDispatch() {
    JobPartitionEntity partition = partition(10L, 20L, 4L);
    JobInstanceEntity instance = instance(20L);
    JobTaskEntity task = task(30L, 20L, 0, 8L);
    when(jobPartitionMapper.selectById("tenant-a", 10L)).thenReturn(partition);
    when(jobInstanceMapper.selectById("tenant-a", 20L)).thenReturn(instance);
    when(jobTaskMapper.selectByQuery(any())).thenReturn(List.of(task));
    when(jobPartitionMapper.resetForDispatch("tenant-a", 10L, PartitionStatus.READY.code(), 4L))
        .thenReturn(0);

    assertThatThrownBy(() -> coordinator.requeuePartition("tenant-a", 10L, "retry-event"))
        .isInstanceOf(RetryRequeueCoordinator.TransientConflictException.class)
        .hasMessageContaining("partition version conflict");

    verify(jobTaskMapper, never()).resetForRetry(anyString(), anyLong(), anyString(), anyLong());
    verify(taskDispatchOutboxService, never())
        .writeDispatchEvent(any(), any(), any(), any(), any(), any());
  }

  @Test
  void shouldResetTaskAndEmitRetryDispatchWithoutPartition() {
    JobInstanceEntity instance = instance(20L);
    JobTaskEntity task = task(30L, 20L, 0, 8L);
    JobStepInstanceEntity step = step(40L, 0);
    when(jobInstanceMapper.selectById("tenant-a", 20L)).thenReturn(instance);
    when(jobStepInstanceMapper.selectByJobTaskId("tenant-a", 30L)).thenReturn(step);
    when(jobTaskMapper.resetForRetry("tenant-a", 30L, TaskStatus.READY.code(), 8L))
        .thenReturn(1);

    coordinator.requeueTaskWithoutPartition("tenant-a", task, "retry-task");

    verify(jobStepInstanceMapper)
        .resetForRetryByJobTaskId("tenant-a", 30L, 1, StepInstanceStatus.READY.code());
    verify(taskDispatchOutboxService)
        .writeDispatchEvent(
            same(instance),
            same(task),
            eq(null),
            eq("trace-20"),
            eq("retry-task"),
            eq(RunMode.RETRY));
  }

  @Test
  void shouldRejectPartitionRequeueWhenSourcePartitionIsMissing() {
    when(jobPartitionMapper.selectById("tenant-a", 10L)).thenReturn(null);

    assertThatThrownBy(() -> coordinator.requeuePartition("tenant-a", 10L, "retry-event"))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("error.partition.retry_not_found");

    verify(jobInstanceMapper, never()).selectById(anyString(), anyLong());
    verify(taskDispatchOutboxService, never())
        .writeDispatchEvent(any(), any(), any(), any(), any(), any());
  }

  private static RetryScheduleEntity retrySchedule(Long id, Long relatedId) {
    RetryScheduleEntity schedule = new RetryScheduleEntity();
    schedule.setId(id);
    schedule.setTenantId("tenant-a");
    schedule.setRelatedId(relatedId);
    return schedule;
  }

  private static JobPartitionEntity partition(Long id, Long instanceId, Long version) {
    JobPartitionEntity partition = new JobPartitionEntity();
    partition.setId(id);
    partition.setJobInstanceId(instanceId);
    partition.setVersion(version);
    return partition;
  }

  private static JobInstanceEntity instance(Long id) {
    JobInstanceEntity instance = new JobInstanceEntity();
    instance.setId(id);
    instance.setTenantId("tenant-a");
    instance.setTraceId("trace-" + id);
    return instance;
  }

  private static JobTaskEntity task(Long id, Long instanceId, Integer sequence, Long version) {
    JobTaskEntity task = new JobTaskEntity();
    task.setId(id);
    task.setTenantId("tenant-a");
    task.setJobInstanceId(instanceId);
    task.setTaskSeq(sequence);
    task.setVersion(version);
    return task;
  }

  private static JobStepInstanceEntity step(Long id, Integer retryCount) {
    JobStepInstanceEntity step = new JobStepInstanceEntity();
    step.setId(id);
    step.setRetryCount(retryCount);
    return step;
  }
}

package io.github.pinpols.batch.orchestrator.application.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.orchestrator.application.service.task.TaskAssignmentService.TaskHeartbeatResult;
import io.github.pinpols.batch.orchestrator.config.PartitionLeaseProperties;
import io.github.pinpols.batch.orchestrator.config.ResourceSchedulerProperties;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobExecutionLogMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobStepInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkerRegistryMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("Job 实际执行开始：续租身份、实例锁顺序与取消边界")
class DefaultTaskAssignmentExecutionStartTest {
  @Mock
  private JobTaskMapper taskMapper;

  @Mock
  private JobPartitionMapper partitionMapper;

  @Mock
  private JobInstanceMapper instanceMapper;

  @Mock
  private JobStepInstanceMapper stepMapper;

  @Mock
  private JobExecutionLogMapper logMapper;

  @Mock
  private WorkerRegistryMapper workerMapper;

  @Mock
  private JobDefinitionMapper definitionMapper;

  private DefaultTaskAssignmentService service;

  @BeforeEach
  void setUp() {
    PartitionLeaseProperties leaseProperties = new PartitionLeaseProperties();
    ResourceSchedulerProperties resourceProperties = new ResourceSchedulerProperties();
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    service = new DefaultTaskAssignmentService(
        taskMapper,
        partitionMapper,
        instanceMapper,
        stepMapper,
        logMapper,
        workerMapper,
        definitionMapper,
        leaseProperties,
        resourceProperties,
        registry);
  }

  @Test
  @DisplayName("执行开始确认先锁实例，再续租并持久化真实执行起点")
  void shouldRecordExecutionStartAfterOwnershipConfirmation() {
    runningTask(false);
    when(partitionMapper.renewLease(any())).thenReturn(1);
    when(instanceMapper.markExecutionStarted("ta", 90L)).thenReturn(1);
    String details = """
        {"executionStarted":true}
        """;

    TaskHeartbeatResult result = service.recordHeartbeat("ta", 100L, "w1", "inv-1", details);

    assertThat(result.leaseRenewed()).isTrue();
    InOrder order = inOrder(instanceMapper, partitionMapper);
    order.verify(instanceMapper).acquireInstanceAdvisoryLock("ta", 90L);
    order.verify(partitionMapper).renewLease(any());
    order.verify(instanceMapper).markExecutionStarted("ta", 90L);
    verify(taskMapper, never())
        .markNonIdempotentExecutionStarted(anyString(), anyLong(), anyString(), anyString());
  }

  @Test
  @DisplayName("租约 CAS 拒绝时不能记录开始，也不能运行后续动作")
  void shouldNotRecordStartWhenInvocationWasRejected() {
    runningTask(false);
    String details = """
        {"executionStarted":true}
        """;

    TaskHeartbeatResult result =
        service.recordHeartbeat("ta", 100L, "w1", "stale-invocation", details);

    assertThat(result.leaseRenewed()).isFalse();
    verify(instanceMapper, never()).markExecutionStarted(anyString(), anyLong());
  }

  @Test
  @DisplayName("任务已请求取消时返回取消指令，不记录尚未发生的执行开始")
  void shouldNotRecordStartForCancelledTask() {
    runningTask(true);
    when(partitionMapper.renewLease(any())).thenReturn(1);
    String details = """
        {"executionStarted":true}
        """;

    TaskHeartbeatResult result = service.recordHeartbeat("ta", 100L, "w1", "inv-1", details);

    assertThat(result.cancelRequested()).isTrue();
    verify(instanceMapper, never()).markExecutionStarted(anyString(), anyLong());
  }

  @Test
  @DisplayName("普通续租和非布尔的同名进度字段不能误触发执行开始")
  void shouldIgnoreNonBooleanProgressField() {
    runningTask(false);
    when(partitionMapper.renewLease(any())).thenReturn(1);
    String details = """
        {"executionStarted":"true","processed":42}
        """;

    service.recordHeartbeat("ta", 100L, "w1", "inv-1", details);

    verify(instanceMapper, never()).markExecutionStarted(anyString(), anyLong());
  }

  @Test
  @DisplayName("不带进度和执行开始标记的普通续租不额外取得实例锁")
  void shouldKeepOrdinaryRenewalIndependentOfExecutionTiming() {
    runningTask(false);
    when(partitionMapper.renewLease(any())).thenReturn(1);

    TaskHeartbeatResult result = service.recordHeartbeat("ta", 100L, "w1", "inv-1", null);

    assertThat(result.leaseRenewed()).isTrue();
    verify(instanceMapper, never()).acquireInstanceAdvisoryLock(anyString(), anyLong());
    verify(instanceMapper, never()).markExecutionStarted(anyString(), anyLong());
  }

  @Test
  @DisplayName("开始事实写入失败必须使当前续租事务失败，不能确认可以执行业务")
  void shouldPropagateStartPersistenceFailure() {
    runningTask(false);
    when(partitionMapper.renewLease(any())).thenReturn(1);
    when(instanceMapper.markExecutionStarted("ta", 90L))
        .thenThrow(new IllegalStateException("injected execution-start persistence failure"));
    String details = """
        {"executionStarted":true}
        """;

    assertThatThrownBy(() -> service.recordHeartbeat("ta", 100L, "w1", "inv-1", details))
        .isInstanceOf(IllegalStateException.class);
  }

  private void runningTask(boolean cancelled) {
    JobTaskEntity task = new JobTaskEntity();
    task.setId(100L);
    task.setTenantId("ta");
    task.setJobInstanceId(90L);
    task.setJobPartitionId(50L);
    task.setTaskStatus("RUNNING");
    task.setAssignedWorkerCode("w1");
    task.setCancelRequested(cancelled);
    when(taskMapper.selectById("ta", 100L)).thenReturn(task);
  }
}

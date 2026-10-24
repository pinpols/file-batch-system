package io.github.pinpols.batch.worker.processes.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.processes.domain.ProcessJobContext;
import io.github.pinpols.batch.worker.processes.domain.ProcessStage;
import io.github.pinpols.batch.worker.processes.domain.ProcessStageResult;
import io.github.pinpols.batch.worker.processes.mapper.platform.ProcessInvocationFenceMapper;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("PROCESS 阶段 invocation fence")
class PlatformProcessInvocationFenceTest {

  @Mock
  private ProcessInvocationFenceMapper mapper;

  @Mock
  private ProcessStagingGenerationCoordinator generationCoordinator;

  @Test
  @DisplayName("锁定实例后重读当前 claim,匹配 invocation 才执行阶段")
  void execute_matchesCurrentInvocation_beforeStageExecution() {
    PlatformProcessInvocationFence fence =
        new PlatformProcessInvocationFence(mapper, generationCoordinator);
    ProcessJobContext context = claimedContext();
    ProcessStageResult expected = ProcessStageResult.success(ProcessStage.COMPUTE);
    AtomicBoolean executed = new AtomicBoolean();
    String generationId = UUID.randomUUID().toString();
    when(mapper.selectCurrentInvocationId("tenant-a", 12L, 34L, 56L, "worker-a"))
        .thenReturn("invocation-a");
    when(generationCoordinator.startComputeGeneration(
            "tenant-a", 12L, 34L, 56L, "worker-a", "invocation-a", "process-12"))
        .thenReturn(generationId);
    context.setBatchKey("process-12");

    ProcessStageResult actual = fence.execute(context, ProcessStage.COMPUTE, () -> {
      executed.set(true);
      assertThat(context.getStagingGenerationId()).isEqualTo(generationId);
      return expected;
    });

    assertThat(actual).isSameAs(expected);
    assertThat(executed).isTrue();
    InOrder order = inOrder(mapper);
    order.verify(mapper).acquireInstanceFence("tenant-a", 34L);
    order.verify(mapper).selectCurrentInvocationId("tenant-a", 12L, 34L, 56L, "worker-a");
    verify(generationCoordinator)
        .startComputeGeneration(
            "tenant-a", 12L, 34L, 56L, "worker-a", "invocation-a", "process-12");
  }

  @Test
  @DisplayName("续跑非 COMPUTE 阶段读取 task 当前 generation,不重新分代")
  void execute_reusesCurrentGeneration_forCheckpointResume() {
    PlatformProcessInvocationFence fence =
        new PlatformProcessInvocationFence(mapper, generationCoordinator);
    ProcessJobContext context = claimedContext();
    String generationId = UUID.randomUUID().toString();
    when(mapper.selectCurrentInvocationId("tenant-a", 12L, 34L, 56L, "worker-a"))
        .thenReturn("invocation-a");
    when(generationCoordinator.currentGeneration(
            "tenant-a", 12L, 34L, 56L, "worker-a", "invocation-a"))
        .thenReturn(generationId);

    ProcessStageResult result = fence.execute(
        context, ProcessStage.VALIDATE, () -> ProcessStageResult.success(ProcessStage.VALIDATE));

    assertThat(result.success()).isTrue();
    assertThat(context.getStagingGenerationId()).isEqualTo(generationId);
    verify(generationCoordinator)
        .currentGeneration("tenant-a", 12L, 34L, 56L, "worker-a", "invocation-a");
    verify(generationCoordinator, never())
        .startComputeGeneration("tenant-a", 12L, 34L, 56L, "worker-a", "invocation-a", null);
  }

  @Test
  @DisplayName("claim 已被新 invocation 接管时拒绝执行阶段副作用")
  void execute_rejectsStaleInvocation_withoutExecutingStage() {
    PlatformProcessInvocationFence fence =
        new PlatformProcessInvocationFence(mapper, generationCoordinator);
    ProcessJobContext context = claimedContext();
    AtomicBoolean executed = new AtomicBoolean();
    when(mapper.selectCurrentInvocationId("tenant-a", 12L, 34L, 56L, "worker-a"))
        .thenReturn("invocation-b");

    assertThatThrownBy(() -> fence.execute(context, ProcessStage.PREPARE, () -> {
          executed.set(true);
          return ProcessStageResult.success(ProcessStage.PREPARE);
        }))
        .isInstanceOf(BizException.class)
        .satisfies(exception -> {
          BizException businessException = (BizException) exception;
          assertThat(businessException.getCode()).isEqualTo(ResultCode.CONFLICT);
          assertThat(businessException.getMessageKey()).isEqualTo("error.task.invocation_mismatch");
        });

    assertThat(executed).isFalse();
    verify(mapper).acquireInstanceFence("tenant-a", 34L);
  }

  @Test
  @DisplayName("claimed task 缺少有效分区身份时失败关闭")
  void execute_rejectsMalformedPartitionIdentity_beforeDatabaseLookup() {
    PlatformProcessInvocationFence fence =
        new PlatformProcessInvocationFence(mapper, generationCoordinator);
    ProcessJobContext context = claimedContext();
    context.getAttributes().put(PipelineRuntimeKeys.JOB_INSTANCE_ID, "not-a-number");
    AtomicBoolean executed = new AtomicBoolean();

    assertThatThrownBy(() -> fence.execute(context, ProcessStage.PREPARE, () -> {
          executed.set(true);
          return ProcessStageResult.success(ProcessStage.PREPARE);
        }))
        .isInstanceOf(BizException.class)
        .extracting(exception -> ((BizException) exception).getCode())
        .isEqualTo(ResultCode.CONFLICT);

    assertThat(executed).isFalse();
    verify(mapper, never()).acquireInstanceFence("tenant-a", 34L);
  }

  @Test
  @DisplayName("没有 Orchestrator task 的本地执行不要求 claim fence")
  void execute_withoutClaim_runsDirectly() {
    PlatformProcessInvocationFence fence =
        new PlatformProcessInvocationFence(mapper, generationCoordinator);
    ProcessJobContext context = new ProcessJobContext();
    ProcessStageResult expected = ProcessStageResult.success(ProcessStage.PREPARE);

    assertThat(fence.execute(context, ProcessStage.PREPARE, () -> expected)).isSameAs(expected);

    verify(mapper, never()).acquireInstanceFence("tenant-a", 34L);
  }

  private static ProcessJobContext claimedContext() {
    ProcessJobContext context = new ProcessJobContext();
    context.setTenantId("tenant-a");
    context.setWorkerId("worker-a");
    context.getAttributes().put(PipelineRuntimeKeys.TASK_ID, 12L);
    context.getAttributes().put(PipelineRuntimeKeys.JOB_INSTANCE_ID, 34L);
    context.getAttributes().put(PipelineRuntimeKeys.JOB_PARTITION_ID, 56L);
    context.getAttributes().put(PipelineRuntimeKeys.PARTITION_INVOCATION_ID, "invocation-a");
    return context;
  }
}

package io.github.pinpols.batch.worker.processes.stage;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues;
import io.github.pinpols.batch.worker.processes.domain.ProcessInvocationIdentity;
import io.github.pinpols.batch.worker.processes.domain.ProcessJobContext;
import io.github.pinpols.batch.worker.processes.domain.ProcessStage;
import io.github.pinpols.batch.worker.processes.domain.ProcessStageResult;
import io.github.pinpols.batch.worker.processes.mapper.platform.ProcessInvocationFenceMapper;
import io.github.pinpols.batch.worker.processes.query.ProcessInvocationFenceCurrentInvocationIdQuery;
import java.util.Map;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 将 PROCESS 阶段执行与当前任务及分区调用串行化。
 * COMPUTE 启动暂存代次；后续阶段接触数据前先解析同一代次。
 */
@Component
@RequiredArgsConstructor
public class PlatformProcessInvocationFence implements ProcessInvocationFence {

  private final ProcessInvocationFenceMapper mapper;
  private final ProcessStagingGenerationCoordinator generationCoordinator;

  @Override
  @Transactional(transactionManager = "transactionManager")
  public ProcessStageResult execute(
      ProcessJobContext context, ProcessStage stage, Supplier<ProcessStageResult> stageExecution) {
    Map<String, Object> attributes = context.getAttributes();
    Object taskValue = attributes.get(PipelineRuntimeKeys.TASK_ID);
    // 未持久化任务的本地直接执行没有可供校验的 Orchestrator 租约。
    if (EmptyChecks.isNull(taskValue)) {
      return stageExecution.get();
    }

    String tenantId = context.getTenantId();
    Long taskId = requiredId(taskValue);
    Long jobInstanceId = requiredId(attributes.get(PipelineRuntimeKeys.JOB_INSTANCE_ID));
    Long jobPartitionId = requiredId(attributes.get(PipelineRuntimeKeys.JOB_PARTITION_ID));
    Object invocationValue = attributes.get(PipelineRuntimeKeys.PARTITION_INVOCATION_ID);
    String invocationId =
        EmptyChecks.isNull(invocationValue) ? null : String.valueOf(invocationValue);
    String workerId = context.getWorkerId();
    boolean hasRequiredInvocationFenceIdentity =
        Texts.hasText(tenantId) && Texts.hasText(workerId) && Texts.hasText(invocationId);
    if (!hasRequiredInvocationFenceIdentity) {
      throw staleInvocation();
    }

    mapper.acquireInstanceFence(tenantId, jobInstanceId);
    ProcessInvocationFenceCurrentInvocationIdQuery processInvocationFenceCurrentInvocationIdQuery =
        ProcessInvocationFenceCurrentInvocationIdQuery.builder()
            .tenantId(tenantId)
            .taskId(taskId)
            .jobInstanceId(jobInstanceId)
            .jobPartitionId(jobPartitionId)
            .workerId(workerId)
            .build();
    String currentInvocationId =
        mapper.selectCurrentInvocationId(processInvocationFenceCurrentInvocationIdQuery);
    if (!invocationId.equals(currentInvocationId)) {
      throw staleInvocation();
    }
    ProcessInvocationIdentity identity = ProcessInvocationIdentity.builder()
        .tenantId(tenantId)
        .taskId(taskId)
        .jobInstanceId(jobInstanceId)
        .jobPartitionId(jobPartitionId)
        .workerId(workerId)
        .invocationId(invocationId)
        .build();
    if (stage == ProcessStage.COMPUTE) {
      String batchKey = context.getBatchKey();
      String generationId = generationCoordinator.startComputeGeneration(identity, batchKey);
      context.setStagingGenerationId(generationId);
    } else if (stage == ProcessStage.VALIDATE
        || stage == ProcessStage.COMMIT
        || stage == ProcessStage.FEEDBACK) {
      String generationId = generationCoordinator.currentGeneration(identity);
      context.setStagingGenerationId(generationId);
    }
    return stageExecution.get();
  }

  private static Long requiredId(Object value) {
    if (EmptyChecks.isNull(value)) {
      throw staleInvocation();
    }
    try {
      Long id = PlatformRuntimeValues.toLong(value);
      if (EmptyChecks.isNull(id)) {
        throw staleInvocation();
      }
      return id;
    } catch (NumberFormatException exception) {
      throw staleInvocation();
    }
  }

  private static BizException staleInvocation() {
    return BizException.of(ResultCode.CONFLICT, "error.task.invocation_mismatch");
  }
}

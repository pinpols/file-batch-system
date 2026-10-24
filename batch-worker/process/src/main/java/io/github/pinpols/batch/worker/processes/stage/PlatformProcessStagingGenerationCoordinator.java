package io.github.pinpols.batch.worker.processes.stage;

import io.github.pinpols.batch.worker.processes.domain.ProcessInvocationIdentity;


import io.github.pinpols.batch.worker.processes.param.ProcessInvocationFenceRotateCurrentStagingGenerationParam;
import io.github.pinpols.batch.worker.processes.query.ProcessInvocationFenceCurrentStagingGenerationQuery;


import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.worker.processes.mapper.platform.ProcessInvocationFenceMapper;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** resetForRetry 会清除已持久化的分配信息。让下方用于 */
@Component
public class PlatformProcessStagingGenerationCoordinator
    implements ProcessStagingGenerationCoordinator {

  private final ProcessInvocationFenceMapper mapper;
  private final TransactionTemplate requiresNew;

  public PlatformProcessStagingGenerationCoordinator(
      ProcessInvocationFenceMapper mapper,
      @Qualifier("transactionManager") PlatformTransactionManager transactionManager) {
    this.mapper = mapper;
    this.requiresNew = new TransactionTemplate(transactionManager);
    this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.requiresNew.setName("process-staging-generation");
  }

  @Override
  public String startComputeGeneration(ProcessInvocationIdentity identity, String batchKey) {
    String tenantId = identity.tenantId();
    Long taskId = identity.taskId();
    Long jobInstanceId = identity.jobInstanceId();
    Long jobPartitionId = identity.jobPartitionId();
    String workerId = identity.workerId();
    String invocationId = identity.invocationId();

    UUID generationUuid = UUID.randomUUID();
    String generationId = generationUuid.toString();
    Integer updated = requiresNew.execute(status -> {
      ProcessInvocationFenceRotateCurrentStagingGenerationParam processInvocationFenceRotateCurrentStagingGenerationParam = ProcessInvocationFenceRotateCurrentStagingGenerationParam.builder()
          .tenantId(tenantId)
          .taskId(taskId)
          .jobInstanceId(jobInstanceId)
          .jobPartitionId(jobPartitionId)
          .workerId(workerId)
          .invocationId(invocationId)
          .batchKey(batchKey)
          .generationId(generationId)
          .build();
      return mapper.rotateCurrentStagingGeneration(
        processInvocationFenceRotateCurrentStagingGenerationParam);
    });
    if (EmptyChecks.isNull(updated) || updated != 1) {
      throw stateConflict();
    }
    return generationId;
  }

  @Override
  public String currentGeneration(ProcessInvocationIdentity identity) {
    String tenantId = identity.tenantId();
    Long taskId = identity.taskId();
    Long jobInstanceId = identity.jobInstanceId();
    Long jobPartitionId = identity.jobPartitionId();
    String workerId = identity.workerId();
    String invocationId = identity.invocationId();

    String generationId = requiresNew.execute(status -> {
      ProcessInvocationFenceCurrentStagingGenerationQuery processInvocationFenceCurrentStagingGenerationQuery = ProcessInvocationFenceCurrentStagingGenerationQuery.builder()
          .tenantId(tenantId)
          .taskId(taskId)
          .jobInstanceId(jobInstanceId)
          .jobPartitionId(jobPartitionId)
          .workerId(workerId)
          .invocationId(invocationId)
          .build();
      return mapper.selectCurrentStagingGeneration(
        processInvocationFenceCurrentStagingGenerationQuery);
    });
    if (EmptyChecks.isNull(generationId) || EmptyChecks.isBlank(generationId)) {
      throw stateConflict();
    }
    return generationId;
  }

  private static BizException stateConflict() {
    return BizException.of(ResultCode.STATE_CONFLICT, "error.process.staging_generation_missing");
  }
}

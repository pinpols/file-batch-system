package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import io.github.pinpols.batch.common.enums.PartitionStatus;
import io.github.pinpols.batch.common.enums.TaskStatus;
import io.github.pinpols.batch.common.enums.WorkflowRunStatus;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.orchestrator.application.engine.OutboxEventKeyGenerator;
import io.github.pinpols.batch.orchestrator.application.engine.TaskDispatchOutboxService;
import io.github.pinpols.batch.orchestrator.application.ratelimit.RateLimitAction;
import io.github.pinpols.batch.orchestrator.application.ratelimit.TenantActionRateLimiter;
import io.github.pinpols.batch.orchestrator.application.service.task.OrchestratorJobMappers;
import io.github.pinpols.batch.orchestrator.application.service.task.PartitionLifecycleService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.OrchestratorWorkflowMappers;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.domain.param.MarkInstanceRunningParam;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceSchedulingDecision;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * WAITING 分片派发的原子写入单元。
 *
 * <p>派发前的资源判断可以在调度器中批量完成；一旦决定释放，分片/任务状态、outbox 和上层运行状态必须一起提交。
 * 这个协作者将该边界显式化，避免调度器通过自身代理间接获得事务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
class WaitingPartitionDispatchTransactionService {

  private final OrchestratorJobMappers jobMappers;
  private final OrchestratorWorkflowMappers workflowMappers;
  private final TaskDispatchOutboxService taskDispatchOutboxService;
  private final PartitionLifecycleService partitionLifecycleService;
  private final TenantActionRateLimiter tenantActionRateLimiter;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void executeDispatch(
      JobPartitionEntity partition,
      JobTaskEntity task,
      JobInstanceEntity jobInstance,
      ResourceSchedulingDecision decision) {
    if (!tenantActionRateLimiter.tryConsume(
        jobInstance.getTenantId(), RateLimitAction.DISPATCH_RELEASE)) {
      return;
    }
    if (!partitionLifecycleService.releaseForDispatch(
        partition, task, PartitionStatus.WAITING.code(), TaskStatus.CREATED.code())) {
      return;
    }
    taskDispatchOutboxService.writeDispatchEvent(
        jobInstance,
        task,
        partition,
        jobInstance.getTraceId(),
        OutboxEventKeyGenerator.forDispatch(task.getTenantId(), task.getId()));
    advanceJobInstance(jobInstance);
    advanceWorkflowRun(jobInstance);
    log.info(
        "waiting partition released: tenantId={}, partitionId={}, taskId={}, fairnessScore={},"
            + " tenantWeight={}, queueWeight={}",
        partition.getTenantId(),
        partition.getId(),
        task.getId(),
        decision.getFairnessScore(),
        decision.getTenantWeight(),
        decision.getQueueWeight());
  }

  private void advanceJobInstance(JobInstanceEntity jobInstance) {
    if (!JobInstanceStatus.WAITING.code().equals(jobInstance.getInstanceStatus())) {
      return;
    }
    int updated = jobMappers.jobInstanceMapper.markRunning(MarkInstanceRunningParam.builder()
        .tenantId(jobInstance.getTenantId())
        .id(jobInstance.getId())
        .instanceStatus(JobInstanceStatus.RUNNING.code())
        .expectedPartitionCount(jobInstance.getExpectedPartitionCount())
        .startedAt(BatchDateTimeSupport.utcNow())
        .expectedVersion(jobInstance.getVersion())
        .build());
    if (updated > 0) {
      jobInstance.setVersion(
          (jobInstance.getVersion() == null ? 0L : jobInstance.getVersion()) + 1);
    }
  }

  private void advanceWorkflowRun(JobInstanceEntity jobInstance) {
    WorkflowRunEntity workflowRun = workflowMappers.workflowRunMapper.selectByRelatedJobInstanceId(
        jobInstance.getTenantId(), jobInstance.getId());
    if (workflowRun == null
        || !WorkflowRunStatus.CREATED.code().equals(workflowRun.getRunStatus())) {
      return;
    }
    workflowMappers.workflowRunMapper.markRunning(
        workflowRun.getTenantId(),
        workflowRun.getId(),
        WorkflowRunStatus.RUNNING.code(),
        workflowRun.getCurrentNodeCode(),
        BatchDateTimeSupport.utcNow());
  }
}

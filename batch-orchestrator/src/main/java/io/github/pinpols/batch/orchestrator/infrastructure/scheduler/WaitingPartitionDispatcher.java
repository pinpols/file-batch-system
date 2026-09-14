package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import io.github.pinpols.batch.common.enums.PartitionStatus;
import io.github.pinpols.batch.common.enums.TaskStatus;
import io.github.pinpols.batch.common.enums.WorkflowRunStatus;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.orchestrator.application.engine.OutboxEventKeyGenerator;
import io.github.pinpols.batch.orchestrator.application.engine.TaskDispatchOutboxService;
import io.github.pinpols.batch.orchestrator.application.ratelimit.RateLimitAction;
import io.github.pinpols.batch.orchestrator.application.ratelimit.TenantActionRateLimiter;
import io.github.pinpols.batch.orchestrator.application.scheduler.GlobalJobAdmission;
import io.github.pinpols.batch.orchestrator.application.service.task.OrchestratorJobMappers;
import io.github.pinpols.batch.orchestrator.application.service.task.PartitionLifecycleService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.OrchestratorWorkflowMappers;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.domain.param.MarkInstanceRunningParam;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceSchedulingDecision;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
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
class WaitingPartitionDispatcher {

  private final OrchestratorJobMappers jobMappers;
  private final OrchestratorWorkflowMappers workflowMappers;
  private final TaskDispatchOutboxService taskDispatchOutboxService;
  private final PartitionLifecycleService partitionLifecycleService;
  private final TenantActionRateLimiter tenantActionRateLimiter;
  private final OrchestratorConfigCacheService configCacheService;
  private final GlobalJobAdmission globalJobAdmission;
  private final FairShareGroupAdmissionGuard fairShareGroupAdmissionGuard;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void executeDispatch(
      JobPartitionEntity partition,
      JobTaskEntity task,
      JobInstanceEntity jobInstance,
      ResourceSchedulingDecision decision) {
    JobInstanceEntity currentInstance =
        jobMappers.jobInstanceMapper.selectById(jobInstance.getTenantId(), jobInstance.getId());
    if (!isDispatchableParent(currentInstance)) {
      return;
    }
    if (!tenantActionRateLimiter.tryConsume(
        currentInstance.getTenantId(), RateLimitAction.DISPATCH_RELEASE)) {
      return;
    }
    if (JobInstanceStatus.WAITING.code().equals(currentInstance.getInstanceStatus())
        && !globalJobAdmission.hasCapacity()) {
      return;
    }
    // 候选排序时的 schedule() 发生在事务外，只能作为快速筛选。共享组硬上限必须在本
    // REQUIRES_NEW 事务里再次判定，并把 advisory lock 持有到 WAITING -> RUNNING 提交完成。
    if (!fairShareGroupAdmissionGuard.hasCapacity(
        configCacheService.findEnabledQuotaPolicy(currentInstance.getTenantId()))) {
      return;
    }
    if (!partitionLifecycleService.releaseForDispatch(
        partition, task, PartitionStatus.WAITING.code(), TaskStatus.CREATED.code())) {
      return;
    }
    taskDispatchOutboxService.writeDispatchEvent(
        currentInstance,
        task,
        partition,
        currentInstance.getTraceId(),
        OutboxEventKeyGenerator.forDispatch(task.getTenantId(), task.getId()));
    advanceJobInstance(currentInstance);
    advanceWorkflowRun(currentInstance);
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

  /**
   * 调度器传入的是事务外快照；真正释放前只接受数据库当前仍处于 WAITING/RUNNING 的父实例。
   * 这样既避免同一实例第二个分片继续按旧 WAITING 快照重复占全局槽位，也不会为已经取消的实例复活任务。
   */
  private boolean isDispatchableParent(JobInstanceEntity jobInstance) {
    if (EmptyChecks.isNull(jobInstance)) {
      return false;
    }
    return JobInstanceStatus.WAITING.code().equals(jobInstance.getInstanceStatus())
        || JobInstanceStatus.RUNNING.code().equals(jobInstance.getInstanceStatus());
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

package com.example.batch.orchestrator.application.service.task;

import com.example.batch.common.dto.EffectiveTaskConfig;
import com.example.batch.common.enums.PartitionStatus;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.enums.SchedulingPriorityBand;
import com.example.batch.common.enums.TaskStatus;
import com.example.batch.common.enums.WorkerRegistryStatus;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.IdGenerator;
import com.example.batch.common.utils.Texts;
import com.example.batch.orchestrator.config.PartitionLeaseProperties;
import com.example.batch.orchestrator.config.ResourceSchedulerProperties;
import com.example.batch.orchestrator.domain.entity.JobDefinitionEntity;
import com.example.batch.orchestrator.domain.entity.JobExecutionLogEntity;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import com.example.batch.orchestrator.domain.entity.JobStepInstanceEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.domain.entity.WorkerRegistryEntity;
import com.example.batch.orchestrator.domain.param.AssignWorkerParam;
import com.example.batch.orchestrator.domain.param.ClaimPartitionParam;
import com.example.batch.orchestrator.domain.param.MarkRunningParam;
import com.example.batch.orchestrator.domain.param.RenewLeaseParam;
import com.example.batch.orchestrator.domain.param.UpdateTaskStatusParam;
import com.example.batch.orchestrator.domain.query.JobExecutionLogQuery;
import com.example.batch.orchestrator.mapper.JobDefinitionMapper;
import com.example.batch.orchestrator.mapper.JobExecutionLogMapper;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.JobPartitionMapper;
import com.example.batch.orchestrator.mapper.JobStepInstanceMapper;
import com.example.batch.orchestrator.mapper.JobTaskMapper;
import com.example.batch.orchestrator.mapper.WorkerRegistryMapper;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

/**
 * Worker 认领（claim）与租约（lease）治理。
 *
 * <p>该类对外提供的语义是“worker 通过 HTTP 回调 orchestrator 完成 claim/renew”，对应接口为：
 *
 * <ul>
 *   <li>{@code POST /internal/tasks/{taskId}/claim}
 *   <li>{@code POST /internal/tasks/{taskId}/renew}
 * </ul>
 *
 * <p>关键约束：
 *
 * <ul>
 *   <li><strong>同一 task 只能被一个 worker 成功认领</strong>：靠 DB 条件更新（READY → RUNNING）保证并发一致性。
 *   <li><strong>partition 与 task 必须一致</strong>：task 认领成功后同步 claim partition，并写入 lease_expire_at。
 *   <li><strong>step 镜像跟随推进</strong>：若存在 {@code job_step_instance}，一并推进为 RUNNING，避免 UI/审计口径不一致。
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DefaultTaskAssignmentService implements TaskAssignmentService {

  private final JobTaskMapper jobTaskMapper;
  private final JobPartitionMapper jobPartitionMapper;
  private final JobInstanceMapper jobInstanceMapper;
  private final JobStepInstanceMapper jobStepInstanceMapper;
  private final JobExecutionLogMapper jobExecutionLogMapper;
  private final WorkerRegistryMapper workerRegistryMapper;
  private final JobDefinitionMapper jobDefinitionMapper;
  private final PartitionLeaseProperties partitionLeaseProperties;
  private final ResourceSchedulerProperties resourceSchedulerProperties;

  @Override
  @Transactional
  public JobTaskEntity assignWorker(String tenantId, Long taskId, String workerCode) {
    // 入口语义：如果不可认领（worker 不在线/组不匹配/状态不允许），返回 current（由 controller 转换为 409/404）。
    JobTaskEntity current = jobTaskMapper.selectById(tenantId, taskId);
    if (current == null) {
      return null;
    }
    if (!isWorkerClaimable(tenantId, workerCode, current)) {
      return current;
    }
    int updated =
        jobTaskMapper.assignWorker(
            AssignWorkerParam.builder()
                .tenantId(tenantId)
                .id(taskId)
                .assignedWorkerCode(workerCode)
                .taskStatus(TaskStatus.RUNNING.code())
                .readyStatus(TaskStatus.READY.code())
                .expectedVersion(current.getVersion())
                .build());
    if (updated <= 0) {
      return jobTaskMapper.selectById(tenantId, taskId);
    }
    if (current.getJobPartitionId() != null) {
      // task 与 partition 的 lease 绑定在一起：task 进入 RUNNING 后必须成功 claim partition，否则认为状态不一致。
      JobPartitionEntity partition =
          jobPartitionMapper.selectById(tenantId, current.getJobPartitionId());
      if (partition == null) {
        // 这里回滚而不是抛异常：语义上属于并发/状态漂移（可重试），不应该把 worker 侧认领请求打成”系统故障”。
        // 返回 current（回滚前的状态，即 READY）而非重读 DB（重读会看到事务内未提交的 RUNNING，
        // 与最终 DB 实际状态不符，会误导调用方认为认领已成功）。
        TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        return current;
      }
      String invocationId = IdGenerator.newInvocationId();
      Instant invocationStartedAt = Instant.now();
      int claimed =
          jobPartitionMapper.claimPartition(
              ClaimPartitionParam.builder()
                  .tenantId(tenantId)
                  .id(current.getJobPartitionId())
                  .workerCode(workerCode)
                  .leaseExpireAt(
                      Instant.now().plusSeconds(partitionLeaseProperties.getExpireSeconds()))
                  .fromStatus(PartitionStatus.READY.code())
                  .toStatus(PartitionStatus.RUNNING.code())
                  .expectedVersion(partition.getVersion())
                  .currentInvocationId(invocationId)
                  .invocationStartedAt(invocationStartedAt)
                  .build());
      if (claimed <= 0) {
        // 避免出现 “task 已 RUNNING 但 partition 未 RUNNING” 的中间态：回滚本事务，让下一次认领重试来收敛。
        // 同理返回 current（READY），不重读 DB。
        TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        return current;
      }
    }
    JobStepInstanceEntity stepInstance = jobStepInstanceMapper.selectByJobTaskId(tenantId, taskId);
    if (stepInstance != null
        && jobStepInstanceMapper.markRunning(
                MarkRunningParam.withDefaultStatuses()
                    .tenantId(tenantId)
                    .id(stepInstance.getId())
                    .startedAt(Instant.now())
                    .expectedVersion(stepInstance.getVersion())
                    .build())
            <= 0) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.job.step_claim_conflict");
    }
    return jobTaskMapper.selectById(tenantId, taskId);
  }

  @Override
  @Transactional
  public boolean renewTaskLease(
      String tenantId, Long taskId, String workerCode, String partitionInvocationId) {
    // 续租语义：只有 RUNNING 且 worker 匹配时允许续租；失败由 controller 统一转成 409。
    JobTaskEntity current = jobTaskMapper.selectById(tenantId, taskId);
    if (current == null || current.getJobPartitionId() == null) {
      return false;
    }
    if (!TaskStatus.RUNNING.code().equals(current.getTaskStatus())) {
      return false;
    }
    if (workerCode == null || !workerCode.equals(current.getAssignedWorkerCode())) {
      return false;
    }
    String expectedInvocation = Texts.hasText(partitionInvocationId) ? partitionInvocationId : null;
    return jobPartitionMapper.renewLease(
            RenewLeaseParam.builder()
                .tenantId(tenantId)
                .id(current.getJobPartitionId())
                .workerCode(workerCode)
                .leaseExpireAt(
                    Instant.now().plusSeconds(partitionLeaseProperties.getExpireSeconds()))
                .expectedInvocationId(expectedInvocation)
                .build())
        > 0;
  }

  @Override
  @Transactional
  public JobTaskEntity updateTaskStatus(
      String tenantId, Long taskId, String taskStatus, String errorCode, String errorMessage) {
    JobTaskEntity current = jobTaskMapper.selectById(tenantId, taskId);
    if (current == null) {
      return null;
    }
    jobTaskMapper.updateStatus(
        UpdateTaskStatusParam.withDefaultTerminals()
            .tenantId(tenantId)
            .id(taskId)
            .taskStatus(taskStatus)
            .resultSummary(null)
            .errorCode(errorCode)
            .errorMessage(errorMessage)
            .expectedVersion(current.getVersion())
            .build());
    return jobTaskMapper.selectById(tenantId, taskId);
  }

  @Override
  @Transactional
  public JobExecutionLogEntity appendLog(JobExecutionLogEntity log) {
    jobExecutionLogMapper.insert(log);
    return log;
  }

  @Override
  public List<JobExecutionLogEntity> listLogs(
      String tenantId, Long jobInstanceId, Long jobPartitionId) {
    return jobExecutionLogMapper.selectByQuery(
        JobExecutionLogQuery.ofPartition(tenantId, jobInstanceId, jobPartitionId));
  }

  @Override
  @Transactional
  public JobTaskEntity markRunning(String tenantId, Long taskId, Instant startedAt) {
    JobTaskEntity current = jobTaskMapper.selectById(tenantId, taskId);
    if (current == null) {
      return null;
    }
    current.setStartedAt(startedAt);
    current.setTaskStatus(TaskStatus.RUNNING.code());
    jobTaskMapper.updateStatus(
        UpdateTaskStatusParam.withDefaultTerminals()
            .tenantId(tenantId)
            .id(taskId)
            .taskStatus(TaskStatus.RUNNING.code())
            .resultSummary(null)
            .errorCode(null)
            .errorMessage(null)
            .expectedVersion(current.getVersion())
            .build());
    JobStepInstanceEntity stepInstance = jobStepInstanceMapper.selectByJobTaskId(tenantId, taskId);
    if (stepInstance != null
        && jobStepInstanceMapper.markRunning(
                MarkRunningParam.withDefaultStatuses()
                    .tenantId(tenantId)
                    .id(stepInstance.getId())
                    .startedAt(startedAt)
                    .expectedVersion(stepInstance.getVersion())
                    .build())
            <= 0) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.job.step_running_conflict");
    }
    return jobTaskMapper.selectById(tenantId, taskId);
  }

  @Override
  public EffectiveTaskConfig loadEffectiveConfig(String tenantId, Long taskId) {
    JobTaskEntity task = jobTaskMapper.selectById(tenantId, taskId);
    if (task == null) {
      return null;
    }
    JobInstanceEntity instance = jobInstanceMapper.selectById(tenantId, task.getJobInstanceId());
    if (instance == null) {
      return null;
    }
    JobPartitionEntity partition =
        task.getJobPartitionId() == null
            ? null
            : jobPartitionMapper.selectById(tenantId, task.getJobPartitionId());
    JobDefinitionEntity definition = jobDefinitionMapper.selectById(instance.getJobDefinitionId());
    String businessKey =
        partition != null && partition.getBusinessKey() != null ? partition.getBusinessKey() : null;
    String idempotencyKey =
        partition != null && partition.getIdempotencyKey() != null
            ? partition.getIdempotencyKey()
            : null;
    return new EffectiveTaskConfig(
        tenantId,
        taskId,
        task.getJobInstanceId(),
        task.getJobPartitionId(),
        instance.getInstanceNo(),
        instance.getJobCode(),
        task.getTaskType(),
        task.getTaskSeq(),
        task.getTaskType(),
        resolvePriorityBand(instance.getPriority()),
        businessKey,
        idempotencyKey,
        task.getTaskPayload(),
        instance.getTraceId(),
        definition == null ? null : definition.executionMode(),
        definition == null ? null : definition.watermarkField(),
        instance.getHighWaterMarkIn(),
        definition == null ? null : definition.retryPolicy(),
        definition == null ? null : definition.retryMaxCount(),
        definition == null ? null : definition.timeoutSeconds(),
        partition == null ? null : partition.getPartitionNo(),
        instance.getExpectedPartitionCount(),
        partition == null ? null : partition.getPartitionKey(),
        // V94: data_interval 透传 — 创建 instance 时已落到 job_instance, claim 时实时读
        instance.getDataIntervalStart(),
        instance.getDataIntervalEnd(),
        partition == null ? null : partition.getCurrentInvocationId());
  }

  private static String resolvePriorityBand(Integer priority) {
    if (priority == null || priority <= 3) {
      return SchedulingPriorityBand.HIGH.code();
    }
    if (priority <= 6) {
      return SchedulingPriorityBand.MEDIUM.code();
    }
    return SchedulingPriorityBand.LOW.code();
  }

  private boolean isWorkerClaimable(String tenantId, String workerCode, JobTaskEntity task) {
    if (workerCode == null || workerCode.isBlank()) {
      return false;
    }
    WorkerRegistryEntity workerRegistry = resolveClaimableWorker(tenantId, workerCode);
    if (workerRegistry == null
        || !WorkerRegistryStatus.ONLINE.code().equals(workerRegistry.status())) {
      return false;
    }
    if (task == null || task.getJobPartitionId() == null) {
      return true;
    }
    JobPartitionEntity partition =
        jobPartitionMapper.selectById(tenantId, task.getJobPartitionId());
    if (partition == null
        || partition.getWorkerGroup() == null
        || partition.getWorkerGroup().isBlank()) {
      return true;
    }
    return partition.getWorkerGroup().equalsIgnoreCase(workerRegistry.workerGroup());
  }

  /**
   * 认领侧的跨租户 fallback，与 {@code DefaultWorkerSelector} 的 {@code shared-tenant-fallback} 对称： 主租户下查不到该
   * {@code workerCode} 注册时，再按配置的 fallback 租户查一次。
   *
   * <p>本地联调 / 共享 dev 环境里任务的 tenantId 可能是 {@code ta/tb/tc}，但真实跑着的只有 {@code default-tenant} 的
   * worker；selector 做过 fallback 选中 default-tenant 的 workerCode 后 把 {@code selectedWorkerId} 塞进
   * outbox，worker 消费后 HTTP POST 回来 claim，如果这里不对称 处理，会卡在"主租户 worker_registry 查不到 →
   * isWorkerClaimable=false → claim 返 409"的死角。 生产 profile 不设置此配置，严格保留 §多租户隔离。
   */
  private WorkerRegistryEntity resolveClaimableWorker(String tenantId, String workerCode) {
    WorkerRegistryEntity primary =
        workerRegistryMapper.selectByTenantAndWorkerCode(tenantId, workerCode);
    if (primary != null) {
      return primary;
    }
    String fallbackTenant =
        resourceSchedulerProperties == null
            ? null
            : resourceSchedulerProperties.getSharedTenantFallback();
    if (!Texts.hasText(fallbackTenant) || fallbackTenant.equals(tenantId)) {
      return null;
    }
    WorkerRegistryEntity fallback =
        workerRegistryMapper.selectByTenantAndWorkerCode(fallbackTenant, workerCode);
    if (fallback != null) {
      log.info(
          "worker claim resolved via shared tenant fallback: tenantId={}, fallbackTenant={},"
              + " workerCode={}",
          tenantId,
          fallbackTenant,
          workerCode);
    }
    return fallback;
  }
}

package com.example.batch.orchestrator.application.service.task;

import com.example.batch.common.dto.EffectiveTaskConfig;
import com.example.batch.common.enums.PartitionStatus;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.enums.SchedulingPriorityBand;
import com.example.batch.common.enums.TaskStatus;
import com.example.batch.common.enums.WorkerRegistryStatus;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.time.BatchDateTimeSupport;
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
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
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
  private final MeterRegistry meterRegistry;

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
      int claimed = claimPartitionLeaseForTask(tenantId, current.getJobPartitionId(), workerCode);
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
                    .startedAt(BatchDateTimeSupport.utcNow())
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
    // R3-P1-10：invocationId 强制非空，避免多副本同 workerCode 续他人 lease。
    if (!Texts.hasText(partitionInvocationId)) {
      log.warn(
          "renewLease rejected: partitionInvocationId required (R3-P1-10): tenant={} taskId={}"
              + " workerCode={}",
          tenantId,
          taskId,
          workerCode);
      return false;
    }
    String expectedInvocation = partitionInvocationId;
    return jobPartitionMapper.renewLease(
            RenewLeaseParam.builder()
                .tenantId(tenantId)
                .id(current.getJobPartitionId())
                .workerCode(workerCode)
                .leaseExpireAt(
                    BatchDateTimeSupport.utcNow()
                        .plusSeconds(partitionLeaseProperties.getExpireSeconds()))
                .expectedInvocationId(expectedInvocation)
                .build())
        > 0;
  }

  @Override
  @Transactional
  public TaskHeartbeatResult recordHeartbeat(
      String tenantId,
      Long taskId,
      String workerCode,
      String partitionInvocationId,
      String detailsJson) {
    boolean renewed = renewTaskLease(tenantId, taskId, workerCode, partitionInvocationId);
    if (!renewed) {
      return new TaskHeartbeatResult(false, false);
    }
    if (Texts.hasText(detailsJson)) {
      jobTaskMapper.updateHeartbeatDetails(tenantId, taskId, detailsJson);
    }
    JobTaskEntity task = jobTaskMapper.selectById(tenantId, taskId);
    boolean cancelRequested = task != null && Boolean.TRUE.equals(task.getCancelRequested());
    return new TaskHeartbeatResult(true, cancelRequested);
  }

  @Override
  @Transactional
  public boolean requestCancel(String tenantId, Long taskId) {
    return jobTaskMapper.requestCancel(tenantId, taskId) > 0;
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
    recordLaunchToRunning(current, startedAt);
    return jobTaskMapper.selectById(tenantId, taskId);
  }

  /**
   * F P1：task NEW→RUNNING 启动延迟(派单到 worker 起跑的等待时长)。
   *
   * <p>语义:task 行 {@code created_at} 是 orchestrator 派单写入数据库时刻;入参 {@code startedAt} 是 worker CLAIM
   * 后实际起跑时刻。差值 = 调度队列等待 + worker 拉取 + CLAIM 往返。SLA 关键路径。
   *
   * <p>tag 受控:tenant_id + task_type(均低基数);**不**带 task_id / job_instance_id(高基数会爆 cardinality)。
   * createdAt 为 null(历史异常数据)直接跳过,不污染样本。
   */
  private void recordLaunchToRunning(JobTaskEntity task, Instant startedAt) {
    if (task.getCreatedAt() == null || startedAt == null) {
      return;
    }
    Duration wait = Duration.between(task.getCreatedAt(), startedAt);
    if (wait.isNegative()) {
      return;
    }
    Timer.builder("batch.task.launch_to_running.duration")
        .description("Task NEW→RUNNING wait time (orchestrator dispatch → worker start)")
        .tags(
            Tags.of(
                "tenant_id",
                task.getTenantId() == null ? "unknown" : task.getTenantId(),
                "task_type",
                task.getTaskType() == null ? "unknown" : task.getTaskType()))
        .publishPercentileHistogram()
        .register(meterRegistry)
        .record(wait);
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

  /**
   * task 已 CAS 为 RUNNING 后绑定 partition lease。若首次 claim 仅因 version 漂移失败，重读仍为 READY 时再试一轮， 避免长 IT
   * 套件中其它流程对 job_partition 的并发 touch 把整条认领事务打回 READY。
   */
  private int claimPartitionLeaseForTask(String tenantId, Long partitionId, String workerCode) {
    JobPartitionEntity partition = jobPartitionMapper.selectById(tenantId, partitionId);
    if (partition == null) {
      return 0;
    }
    int claimed = tryClaimPartitionLeaseOnce(tenantId, partitionId, workerCode, partition);
    if (claimed > 0) {
      return claimed;
    }
    partition = jobPartitionMapper.selectById(tenantId, partitionId);
    if (partition == null || !PartitionStatus.READY.code().equals(partition.getPartitionStatus())) {
      return 0;
    }
    return tryClaimPartitionLeaseOnce(tenantId, partitionId, workerCode, partition);
  }

  private int tryClaimPartitionLeaseOnce(
      String tenantId, Long partitionId, String workerCode, JobPartitionEntity partition) {
    String invocationId = IdGenerator.newInvocationId();
    Instant invocationStartedAt = BatchDateTimeSupport.utcNow();
    ClaimPartitionParam param =
        ClaimPartitionParam.builder()
            .tenantId(tenantId)
            .id(partitionId)
            .workerCode(workerCode)
            .leaseExpireAt(
                BatchDateTimeSupport.utcNow()
                    .plusSeconds(partitionLeaseProperties.getExpireSeconds()))
            .fromStatus(PartitionStatus.READY.code())
            .toStatus(PartitionStatus.RUNNING.code())
            .expectedVersion(partition.getVersion())
            .currentInvocationId(invocationId)
            .invocationStartedAt(invocationStartedAt)
            .build();
    return jobPartitionMapper.claimPartition(param);
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
   * worker；selector 做过 fallback 选中 default-tenant 的 workerCode 后 把 {@code selectedWorkerId} 写入
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

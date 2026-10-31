package io.github.pinpols.batch.orchestrator.application.service.task;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.pinpols.batch.common.dto.DependencyResultReference;
import io.github.pinpols.batch.common.dto.EffectiveTaskConfig;
import io.github.pinpols.batch.common.dto.ReplayExecutionGuard;
import io.github.pinpols.batch.common.enums.PartitionStatus;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.SchedulingPriorityBand;
import io.github.pinpols.batch.common.enums.TaskErrorCodes;
import io.github.pinpols.batch.common.enums.TaskStatus;
import io.github.pinpols.batch.common.enums.WorkerRegistryStatus;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.IdGenerator;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.config.PartitionLeaseProperties;
import io.github.pinpols.batch.orchestrator.config.ResourceSchedulerProperties;
import io.github.pinpols.batch.orchestrator.domain.entity.JobDefinitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobExecutionLogEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobStepInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkerRegistryEntity;
import io.github.pinpols.batch.orchestrator.domain.param.AssignWorkerParam;
import io.github.pinpols.batch.orchestrator.domain.param.ClaimPartitionParam;
import io.github.pinpols.batch.orchestrator.domain.param.ExternalOutcomeResolutionConsumeParam;
import io.github.pinpols.batch.orchestrator.domain.param.MarkRunningParam;
import io.github.pinpols.batch.orchestrator.domain.param.RenewLeaseBatchItem;
import io.github.pinpols.batch.orchestrator.domain.param.RenewLeaseBatchRow;
import io.github.pinpols.batch.orchestrator.domain.param.RenewLeaseParam;
import io.github.pinpols.batch.orchestrator.domain.param.UpdateTaskStatusParam;
import io.github.pinpols.batch.orchestrator.domain.query.JobExecutionLogQuery;
import io.github.pinpols.batch.orchestrator.domain.query.JobTaskQuery;
import io.github.pinpols.batch.orchestrator.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobExecutionLogMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobStepInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkerRegistryMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    return assignWorkerInternal(tenantId, taskId, workerCode, null);
  }

  @Override
  @Transactional
  public JobTaskEntity assignWorker(
      String tenantId, Long taskId, String workerCode, WorkerLookupMemo workerMemo) {
    return assignWorkerInternal(tenantId, taskId, workerCode, workerMemo);
  }

  private JobTaskEntity assignWorkerInternal(
      String tenantId, Long taskId, String workerCode, WorkerLookupMemo workerMemo) {
    // 入口语义：如果不可认领（worker 不在线/组不匹配/状态不允许），返回 current（由 controller 转换为 409/404）。
    JobTaskEntity current = jobTaskMapper.selectForWorkerClaim(tenantId, taskId);
    if (EmptyChecks.isNull(current)) {
      return null;
    }
    // PERF(5.2): 认领可行性评估与后续 partition lease claim 复用同一次 job_partition selectById。
    // 评估阶段（worker 在线 + 组匹配）已把 partition 读进内存，其间只发生 job_task 的 CAS（不触碰
    // job_partition），version 不会漂移，可直接把已加载的 partition 传给 claimPartitionLeaseForTask，
    // 省掉重复 selectById；CAS 失败时后者仍会自行重读并重试（保留原重试语义）。
    ClaimEval eval = evaluateClaim(tenantId, workerCode, current, workerMemo);
    if (!eval.claimable()) {
      return current;
    }
    JobTaskEntity claimedTask = jobTaskMapper.assignWorker(AssignWorkerParam.builder()
        .tenantId(tenantId)
        .id(taskId)
        .assignedWorkerCode(workerCode)
        .expectedAssignedWorkerCode(current.getAssignedWorkerCode())
        .taskStatus(TaskStatus.RUNNING.code())
        .readyStatus(TaskStatus.READY.code())
        .expectedVersion(current.getVersion())
        .build());
    if (EmptyChecks.isNull(claimedTask)) {
      return jobTaskMapper.selectForWorkerClaim(tenantId, taskId);
    }
    if (EmptyChecks.isNotNull(current.getJobPartitionId())) {
      // task 与 partition 的 lease 绑定在一起：task 进入 RUNNING 后必须成功 claim partition，否则认为状态不一致。
      int claimed = claimPartitionLeaseForTask(
          tenantId, current.getJobPartitionId(), workerCode, eval.partition());
      if (claimed <= 0) {
        // 避免出现 “task 已 RUNNING 但 partition 未 RUNNING” 的中间态：回滚本事务，让下一次认领重试来收敛。
        // 同理返回 current（READY），不重读 DB。
        TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        return current;
      }
    }
    JobStepInstanceEntity stepInstance = jobStepInstanceMapper.selectByJobTaskId(tenantId, taskId);
    if (EmptyChecks.isNotNull(stepInstance)
        && jobStepInstanceMapper.markRunning(MarkRunningParam.withDefaultStatuses()
                .tenantId(tenantId)
                .id(stepInstance.getId())
                .startedAt(BatchDateTimeSupport.utcNow())
                .expectedVersion(stepInstance.getVersion())
                .build())
            <= 0) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.job.step_claim_conflict");
    }
    return claimedTask;
  }

  @Override
  @Transactional
  public boolean renewTaskLease(
      String tenantId, Long taskId, String workerCode, String partitionInvocationId) {
    // 续租语义：只有 RUNNING 且 worker 匹配时允许续租；失败由 controller 统一转成 409。
    JobTaskEntity current = jobTaskMapper.selectById(tenantId, taskId);
    return renewTaskLease(current, tenantId, taskId, workerCode, partitionInvocationId);
  }

  @Override
  @Transactional
  public boolean markNonIdempotentExecutionStarted(
      String tenantId, Long taskId, String workerCode, String partitionInvocationId) {
    boolean executionIdentityIsIncomplete = EmptyChecks.isBlank(tenantId)
        || EmptyChecks.isNull(taskId)
        || EmptyChecks.isBlank(workerCode)
        || EmptyChecks.isBlank(partitionInvocationId);
    if (executionIdentityIsIncomplete) {
      return false;
    }
    JobTaskEntity current = jobTaskMapper.selectById(tenantId, taskId);
    if (EmptyChecks.isNull(current) || EmptyChecks.isNull(current.getJobInstanceId())) {
      return false;
    }
    // 将副作用前标记与 REPORT 和租约回收串行化。Worker 进入
    // 非幂等执行器前，必须先提交该标记。
    jobInstanceMapper.acquireInstanceAdvisoryLock(tenantId, current.getJobInstanceId());
    current = jobTaskMapper.selectById(tenantId, taskId);
    if (!isCurrentExecutionOwner(current, tenantId, workerCode, partitionInvocationId)) {
      return false;
    }
    JobPartitionEntity partition =
        jobPartitionMapper.selectById(tenantId, current.getJobPartitionId());
    if (EmptyChecks.isNull(partition)
        || !partitionInvocationId.equals(partition.getCurrentInvocationId())) {
      return false;
    }
    if (EmptyChecks.isNotNull(current.getNonIdempotentStartedAt())) {
      return true;
    }
    ExternalOutcomeAuthorization authorization = consumeExternalOutcomeAuthorization(current);
    if (authorization == ExternalOutcomeAuthorization.REJECTED) {
      return false;
    }
    if (jobTaskMapper.markNonIdempotentExecutionStarted(
            tenantId, taskId, workerCode, partitionInvocationId)
        > 0) {
      return true;
    }
    // 同一任务中其他非幂等步骤重复写入标记时按幂等处理。
    current = jobTaskMapper.selectById(tenantId, taskId);
    partition = jobPartitionMapper.selectById(tenantId, current.getJobPartitionId());
    boolean started = EmptyChecks.isNotNull(current.getNonIdempotentStartedAt())
        && isCurrentExecutionOwner(current, tenantId, workerCode, partitionInvocationId)
        && EmptyChecks.isNotNull(partition)
        && partitionInvocationId.equals(partition.getCurrentInvocationId());
    if (!started && authorization == ExternalOutcomeAuthorization.CONSUMED) {
      // 不允许开始标记失败却提交授权消费；调用方重试时仍须使用同一根请求。
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.task.claim_not_available");
    }
    return started;
  }

  private ExternalOutcomeAuthorization consumeExternalOutcomeAuthorization(JobTaskEntity task) {
    JobInstanceEntity instance =
        jobInstanceMapper.selectById(task.getTenantId(), task.getJobInstanceId());
    if (EmptyChecks.isNull(instance)) {
      return ExternalOutcomeAuthorization.REJECTED;
    }
    Map<String, Object> snapshot = parseInstanceSnapshot(instance);
    Object parameters = snapshot.get("effectiveParams");
    Map<?, ?> effective = parameters instanceof Map<?, ?> values ? values : Map.of();
    Object sourceId = effective.get("_externalOutcomeResolutionTaskId");
    Object sourceVersion = effective.get("_externalOutcomeResolutionVersion");
    if (EmptyChecks.isNull(sourceId) && EmptyChecks.isNull(sourceVersion)) {
      if (EmptyChecks.isNotNull(instance.getParentInstanceId())) {
        JobTaskQuery query =
            JobTaskQuery.ofJobInstance(task.getTenantId(), instance.getParentInstanceId());
        boolean unknownParent = jobTaskMapper.selectByQuery(query).stream()
            .anyMatch(
                source -> TaskErrorCodes.EXTERNAL_OUTCOME_UNKNOWN.equals(source.getErrorCode()));
        if (unknownParent) return ExternalOutcomeAuthorization.REJECTED;
      }
      return ExternalOutcomeAuthorization.NOT_REQUIRED;
    }
    Long sourceTaskId;
    Long version;
    try {
      sourceTaskId = longValue(sourceId);
      version = longValue(sourceVersion);
    } catch (IllegalArgumentException invalidIdentity) {
      return ExternalOutcomeAuthorization.REJECTED;
    }
    if (EmptyChecks.isNull(sourceTaskId)
        || sourceTaskId <= 0
        || EmptyChecks.isNull(version)
        || version < 0) {
      return ExternalOutcomeAuthorization.REJECTED;
    }
    ExternalOutcomeResolutionConsumeParam consumption = new ExternalOutcomeResolutionConsumeParam(
        task.getTenantId(), sourceTaskId, version, task.getId());
    return jobTaskMapper.consumeExternalOutcomeResolution(consumption) == 1
        ? ExternalOutcomeAuthorization.CONSUMED
        : ExternalOutcomeAuthorization.REJECTED;
  }

  private enum ExternalOutcomeAuthorization {
    NOT_REQUIRED,
    CONSUMED,
    REJECTED
  }

  private static boolean isCurrentExecutionOwner(
      JobTaskEntity task, String tenantId, String workerCode, String partitionInvocationId) {
    return EmptyChecks.isNotNull(task)
        && tenantId.equals(task.getTenantId())
        && TaskStatus.RUNNING.code().equals(task.getTaskStatus())
        && workerCode.equals(task.getAssignedWorkerCode())
        && EmptyChecks.isNotNull(task.getJobPartitionId())
        && EmptyChecks.isNotBlank(partitionInvocationId);
  }

  /**
   * 续租核心逻辑，作用在调用方已加载的 {@code current} 上。抽出私有重载让 {@link #recordHeartbeat} 复用同一次 job_task
   * selectById（PERF 5.3），避免续租前后各读一次。校验条件（存在性/RUNNING/worker 匹配/invocationId 非空）与租约 CAS 一字未改。
   */
  private boolean renewTaskLease(
      JobTaskEntity current,
      String tenantId,
      Long taskId,
      String workerCode,
      String partitionInvocationId) {
    if (EmptyChecks.isNull(current) || EmptyChecks.isNull(current.getJobPartitionId())) {
      return false;
    }
    if (!TaskStatus.RUNNING.code().equals(current.getTaskStatus())) {
      return false;
    }
    if (EmptyChecks.isNull(workerCode) || !workerCode.equals(current.getAssignedWorkerCode())) {
      return false;
    }
    // R3-P1-10：invocationId 强制非空，避免多副本同 workerCode 续他人 lease。
    if (EmptyChecks.isBlank(partitionInvocationId)) {
      log.warn(
          "renewLease rejected: partitionInvocationId required (R3-P1-10): tenant={} taskId={}"
              + " workerCode={}",
          tenantId,
          taskId,
          workerCode);
      return false;
    }
    String expectedInvocation = partitionInvocationId;
    RenewLeaseParam.RenewLeaseParamBuilder renewalBuilder = RenewLeaseParam.builder()
        .tenantId(tenantId)
        .id(current.getJobPartitionId())
        .workerCode(workerCode);
    Instant leaseBaseTime = BatchDateTimeSupport.utcNow();
    long leaseDurationSeconds = partitionLeaseProperties.getExpireSeconds();
    Instant leaseExpiresAt = leaseBaseTime.plusSeconds(leaseDurationSeconds);
    RenewLeaseParam renewalParam = renewalBuilder
        .leaseExpireAt(leaseExpiresAt)
        .expectedInvocationId(expectedInvocation)
        .build();
    return jobPartitionMapper.renewLease(renewalParam) > 0;
  }

  @Override
  @Transactional
  public TaskHeartbeatResult recordHeartbeat(
      String tenantId,
      Long taskId,
      String workerCode,
      String partitionInvocationId,
      String detailsJson) {
    // 普通续租只读一次任务；进度与执行入口写入先取得实例锁，与 REPORT 保持一致的锁顺序。
    JobTaskEntity current = jobTaskMapper.selectById(tenantId, taskId);
    boolean executionStarted = isExecutionStart(detailsJson);
    if (executionStarted
        && (EmptyChecks.isNull(current) || EmptyChecks.isNull(current.getJobInstanceId()))) {
      return new TaskHeartbeatResult(false, false);
    }
    if (EmptyChecks.isNotBlank(detailsJson)
        && EmptyChecks.isNotNull(current)
        && EmptyChecks.isNotNull(current.getJobInstanceId())) {
      Long instanceId = current.getJobInstanceId();
      jobInstanceMapper.acquireInstanceAdvisoryLock(tenantId, instanceId);
      current = jobTaskMapper.selectById(tenantId, taskId);
      if (EmptyChecks.isNull(current) || !instanceId.equals(current.getJobInstanceId())) {
        return new TaskHeartbeatResult(false, false);
      }
    }
    boolean renewed = renewTaskLease(current, tenantId, taskId, workerCode, partitionInvocationId);
    if (!renewed) {
      return new TaskHeartbeatResult(false, false);
    }
    boolean cancelRequested = Boolean.TRUE.equals(current.getCancelRequested());
    if (executionStarted && !cancelRequested) {
      int marked = jobInstanceMapper.markExecutionStarted(tenantId, current.getJobInstanceId());
      if (marked == 0) {
        throw BizException.of(ResultCode.STATE_CONFLICT, "error.task.claim_not_available");
      }
    }
    if (EmptyChecks.isNotBlank(detailsJson)) {
      jobTaskMapper.updateHeartbeatDetails(tenantId, taskId, detailsJson);
    }
    return new TaskHeartbeatResult(true, cancelRequested);
  }

  /** 保留任意进度载荷，只有明确的 JSON 布尔真值才能声明执行入口。 */
  private static boolean isExecutionStart(String detailsJson) {
    if (EmptyChecks.isBlank(detailsJson)) {
      return false;
    }
    JsonNode details = JsonUtils.fromJson(detailsJson, JsonNode.class);
    JsonNode marker = EmptyChecks.isNull(details) ? null : details.get("executionStarted");
    return EmptyChecks.isNotNull(marker) && marker.isBoolean() && marker.booleanValue();
  }

  /**
   * PERF(5.3): renewBatch set-based —— N 项批量续租从 O(N) 次(selectById + renewLease UPDATE)压成 1 条 {@code
   * UPDATE ... FROM VALUES JOIN job_task ... RETURNING}。
   *
   * <p>逐项语义与逐条 {@link #recordHeartbeat}(detailsJson=null)一致:
   *
   * <ul>
   *   <li>入参缺失(tenant/taskId/workerCode/invocationId 任一为空)→ Java 侧直接判 false,不进 SQL(对应单条链路的前置校验,
   *       R3-P1-10 invocationId 强制非空);
   *   <li>task 不存在/无 partition/非 RUNNING/worker 不匹配/lease CAS 未命中 → SQL 谓词不命中,RETURNING 缺席 → false;
   *   <li>命中项 RETURNING 一并带回 cancel_requested,不再二次 selectById。
   * </ul>
   */
  @Override
  @Transactional
  public List<TaskHeartbeatResult> renewLeaseBatch(List<LeaseRenewCommand> items) {
    if (EmptyChecks.isEmpty(items)) {
      return List.of();
    }
    Instant renewalTime = BatchDateTimeSupport.utcNow();
    long leaseDurationSeconds = partitionLeaseProperties.getExpireSeconds();
    Instant leaseExpireAt = renewalTime.plusSeconds(leaseDurationSeconds);
    List<RenewLeaseBatchItem> sqlItems = new ArrayList<>(items.size());
    for (LeaseRenewCommand item : items) {
      if (EmptyChecks.isNull(item)) {
        continue;
      }
      String tenantId = item.tenantId();
      if (EmptyChecks.isBlank(tenantId)) {
        continue;
      }
      Long taskId = item.taskId();
      if (EmptyChecks.isNull(taskId)) {
        continue;
      }
      String workerCode = item.workerCode();
      if (EmptyChecks.isBlank(workerCode)) {
        continue;
      }
      String invocationId = item.partitionInvocationId();
      if (EmptyChecks.isBlank(invocationId)) {
        continue;
      }
      sqlItems.add(RenewLeaseBatchItem.builder()
          .tenantId(tenantId)
          .taskId(taskId)
          .workerCode(workerCode)
          .invocationId(invocationId)
          .build());
    }
    Map<String, RenewLeaseBatchRow> renewedByKey = new HashMap<>();
    if (EmptyChecks.isNotEmpty(sqlItems)) {
      for (RenewLeaseBatchRow row :
          jobPartitionMapper.renewLeaseBatch(sqlItems, leaseExpireAt, TaskStatus.RUNNING.code())) {
        renewedByKey.put(row.getTenantId() + "\u0000" + row.getTaskId(), row);
      }
    }
    List<TaskHeartbeatResult> results = new ArrayList<>(items.size());
    for (LeaseRenewCommand item : items) {
      RenewLeaseBatchRow row = EmptyChecks.isNull(item)
          ? null
          : renewedByKey.get(item.tenantId() + "\u0000" + item.taskId());
      boolean taskFound = EmptyChecks.isNotNull(row);
      boolean cancelRequested = taskFound && Boolean.TRUE.equals(row.getCancelRequested());
      results.add(new TaskHeartbeatResult(taskFound, cancelRequested));
    }
    return results;
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
    if (EmptyChecks.isNull(current)) {
      return null;
    }
    jobTaskMapper.updateStatus(UpdateTaskStatusParam.withDefaultTerminals()
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
    JobExecutionLogQuery query =
        JobExecutionLogQuery.ofPartition(tenantId, jobInstanceId, jobPartitionId);
    return jobExecutionLogMapper.selectByQuery(query);
  }

  @Override
  @Transactional
  public JobTaskEntity markRunning(String tenantId, Long taskId, Instant startedAt) {
    JobTaskEntity current = jobTaskMapper.selectById(tenantId, taskId);
    if (EmptyChecks.isNull(current)) {
      return null;
    }
    current.setStartedAt(startedAt);
    current.setTaskStatus(TaskStatus.RUNNING.code());
    jobTaskMapper.updateStatus(UpdateTaskStatusParam.withDefaultTerminals()
        .tenantId(tenantId)
        .id(taskId)
        .taskStatus(TaskStatus.RUNNING.code())
        .resultSummary(null)
        .errorCode(null)
        .errorMessage(null)
        .expectedVersion(current.getVersion())
        .build());
    JobStepInstanceEntity stepInstance = jobStepInstanceMapper.selectByJobTaskId(tenantId, taskId);
    if (EmptyChecks.isNotNull(stepInstance)
        && jobStepInstanceMapper.markRunning(MarkRunningParam.withDefaultStatuses()
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
   * <p>tag 受控:tenant_id + task_type(均低基数);**不**带 task_id / job_instance_id(高基数会导致 cardinality 失控)。
   * createdAt 为 null(历史异常数据)直接跳过,不污染样本。
   */
  private void recordLaunchToRunning(JobTaskEntity task, Instant startedAt) {
    if (EmptyChecks.isNull(task.getCreatedAt()) || EmptyChecks.isNull(startedAt)) {
      return;
    }
    Duration wait = Duration.between(task.getCreatedAt(), startedAt);
    if (wait.isNegative()) {
      return;
    }
    Timer.Builder timerBuilder = Timer.builder("batch.task.launch_to_running.duration")
        .description("Task NEW→RUNNING wait time (orchestrator dispatch → worker start)");
    String tenantId = task.getTenantId();
    String tenantTag = EmptyChecks.isNull(tenantId) ? "unknown" : task.getTenantId();
    String taskType = task.getTaskType();
    String taskTypeTag = EmptyChecks.isNull(taskType) ? "unknown" : task.getTaskType();
    Tags tags = Tags.of("tenant_id", tenantTag, "task_type", taskTypeTag);
    timerBuilder.tags(tags).publishPercentileHistogram().register(meterRegistry).record(wait);
  }

  @Override
  public EffectiveTaskConfig loadEffectiveConfig(String tenantId, Long taskId) {
    JobTaskEntity task = jobTaskMapper.selectById(tenantId, taskId);
    return loadEffectiveConfig(tenantId, task);
  }

  /**
   * PERF(5.2b): 复用调用方已持有的 task 实体（claim 成功后 assignWorker 返回的最新行），省掉一次 job_task selectById。partition
   * 仍实时读 —— claim 刚写入的 current_invocation_id 必须取最新；instance/definition 本就只在此处读，无重复。
   */
  @Override
  public EffectiveTaskConfig loadEffectiveConfig(String tenantId, JobTaskEntity task) {
    if (EmptyChecks.isNull(task)) {
      return null;
    }
    Long taskId = task.getId();
    JobInstanceEntity instance = jobInstanceMapper.selectById(tenantId, task.getJobInstanceId());
    if (EmptyChecks.isNull(instance)) {
      return null;
    }
    JobPartitionEntity partition = EmptyChecks.isNull(task.getJobPartitionId())
        ? null
        : jobPartitionMapper.selectById(tenantId, task.getJobPartitionId());
    JobDefinitionEntity definition = jobDefinitionMapper.selectById(instance.getJobDefinitionId());
    Map<String, Object> instanceSnapshot = parseInstanceSnapshot(instance);
    Map<String, Object> executionPolicy = executionPolicySnapshot(instanceSnapshot);
    boolean hasExecutionPolicySnapshot = instanceSnapshot.containsKey("executionPolicy");
    String businessKey = partition == null // empty-check: allow - Sonar S2259
        ? null
        : partition.getBusinessKey();
    String idempotencyKey = partition == null // empty-check: allow - Sonar S2259
        ? null
        : partition.getIdempotencyKey();
    Map<String, Object> partitionSnapshot = parsePartitionSnapshot(partition);
    Integer partitionPlanVersion = intValue(partitionSnapshot.get("partitionPlanVersion"));
    Integer shardIndex = intValue(partitionSnapshot.get("shardIndex"));
    Integer shardTotal = intValue(partitionSnapshot.get("shardTotal"));
    Long rangeStartInclusive = longValue(partitionSnapshot.get("rangeStartInclusive"));
    Long rangeEndExclusive = longValue(partitionSnapshot.get("rangeEndExclusive"));
    Long expectedRows = longValue(partitionSnapshot.get("expectedRows"));
    Long jobInstanceId = task.getJobInstanceId();
    Long jobPartitionId = task.getJobPartitionId();
    String instanceNo = instance.getInstanceNo();
    String jobCode = instance.getJobCode();
    String taskType = task.getTaskType();
    Integer taskSeq = task.getTaskSeq();
    String workerType = task.getTaskType();
    String priorityBand = resolvePriorityBand(instance.getPriority());
    String payload = task.getTaskPayload();
    String traceId = instance.getTraceId();
    String executionMode = hasExecutionPolicySnapshot
        ? textValue(executionPolicy.get("executionMode"))
        : EmptyChecks.isNull(definition) ? null : definition.executionMode();
    String watermarkField = hasExecutionPolicySnapshot
        ? textValue(executionPolicy.get("watermarkField"))
        : EmptyChecks.isNull(definition) ? null : definition.watermarkField();
    String highWaterMarkIn = instance.getHighWaterMarkIn();
    String retryPolicy = hasExecutionPolicySnapshot
        ? textValue(executionPolicy.get("retryPolicy"))
        : EmptyChecks.isNull(definition) ? null : definition.retryPolicy();
    Integer retryMaxCount = hasExecutionPolicySnapshot
        ? intValue(executionPolicy.get("retryMaxCount"))
        : EmptyChecks.isNull(definition) ? null : definition.retryMaxCount();
    Integer timeoutSeconds = hasExecutionPolicySnapshot
        ? intValue(executionPolicy.get("timeoutSeconds"))
        : EmptyChecks.isNull(definition) ? null : definition.timeoutSeconds();
    Integer partitionNo = EmptyChecks.isNull(partition) ? null : partition.getPartitionNo();
    Integer partitionCount = instance.getExpectedPartitionCount();
    String partitionKey = EmptyChecks.isNull(partition) ? null : partition.getPartitionKey();
    Instant dataIntervalStart = instance.getDataIntervalStart();
    Instant dataIntervalEnd = instance.getDataIntervalEnd();
    String partitionInvocationId =
        EmptyChecks.isNull(partition) ? null : partition.getCurrentInvocationId();
    List<DependencyResultReference> dependencyResultSnapshot =
        dependencyResultSnapshot(instanceSnapshot);
    ReplayExecutionGuard replayExecutionGuard = replayExecutionGuard(instanceSnapshot);
    EffectiveTaskConfig effectiveConfig = EffectiveTaskConfig.builder()
        .tenantId(tenantId)
        .taskId(taskId)
        .jobInstanceId(jobInstanceId)
        .jobPartitionId(jobPartitionId)
        .instanceNo(instanceNo)
        .jobCode(jobCode)
        .taskType(taskType)
        .taskSeq(taskSeq)
        .workerType(workerType)
        .priorityBand(priorityBand)
        .businessKey(businessKey)
        .idempotencyKey(idempotencyKey)
        .payload(payload)
        .traceId(traceId)
        .executionMode(executionMode)
        .watermarkField(watermarkField)
        .highWaterMarkIn(highWaterMarkIn)
        .retryPolicy(retryPolicy)
        .retryMaxCount(retryMaxCount)
        .timeoutSeconds(timeoutSeconds)
        .partitionNo(partitionNo)
        .partitionCount(partitionCount)
        .partitionKey(partitionKey)
        .partitionPlanVersion(partitionPlanVersion)
        .shardIndex(shardIndex)
        .shardTotal(shardTotal)
        .rangeStartInclusive(rangeStartInclusive)
        .rangeEndExclusive(rangeEndExclusive)
        .expectedRows(expectedRows)
        // V94: data_interval 透传 — 创建 instance 时已落到 job_instance, claim 时实时读
        .dataIntervalStart(dataIntervalStart)
        .dataIntervalEnd(dataIntervalEnd)
        .partitionInvocationId(partitionInvocationId)
        .dependencyResultSnapshot(dependencyResultSnapshot)
        .replayExecutionGuard(replayExecutionGuard)
        .build();
    return effectiveConfig;
  }

  private List<DependencyResultReference> dependencyResultSnapshot(
      Map<String, Object> instanceSnapshot) {
    Object value = instanceSnapshot.get("dependencyResultSnapshot");
    if (!(value instanceof List<?> references) || EmptyChecks.isEmpty(references)) {
      return List.of();
    }
    try {
      String serializedReferences = JsonUtils.toJson(references);
      List<DependencyResultReference> parsedReferences =
          JsonUtils.fromJson(serializedReferences, new TypeReference<>() {});
      return parsedReferences;
    } catch (IllegalArgumentException malformedSnapshot) {
      throw new IllegalStateException(
          "dependency result snapshot is malformed; refusing worker claim", malformedSnapshot);
    }
  }

  private ReplayExecutionGuard replayExecutionGuard(Map<String, Object> instanceSnapshot) {
    Object value = instanceSnapshot.get("replayExecutionGuard");
    if (EmptyChecks.isNull(value)) {
      return null;
    }
    try {
      String serializedGuard = JsonUtils.toJson(value);
      ReplayExecutionGuard guard = JsonUtils.fromJson(serializedGuard, ReplayExecutionGuard.class);
      if (EmptyChecks.isNull(guard)) {
        throw new IllegalArgumentException("replay execution guard is empty");
      }
      return guard;
    } catch (RuntimeException malformedSnapshot) {
      throw new IllegalStateException(
          "replay execution guard snapshot is malformed; refusing worker claim", malformedSnapshot);
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> parseInstanceSnapshot(JobInstanceEntity instance) {
    if (EmptyChecks.isNull(instance)) {
      return Map.of();
    }
    String paramsSnapshot = instance.getParamsSnapshot();
    if (EmptyChecks.isBlank(paramsSnapshot)) {
      return Map.of();
    }
    try {
      Object parsed = JsonUtils.fromJson(paramsSnapshot, Object.class);
      if (parsed instanceof Map<?, ?> snapshot) {
        return (Map<String, Object>) snapshot;
      }
    } catch (IllegalArgumentException badSnapshot) {
      throw new IllegalStateException(
          "job instance params_snapshot is malformed; refusing worker claim", badSnapshot);
    }
    throw new IllegalStateException(
        "job instance params_snapshot is not an object; refusing worker claim");
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> executionPolicySnapshot(Map<String, Object> instanceSnapshot) {
    Object policy = instanceSnapshot.get("executionPolicy");
    if (policy instanceof Map<?, ?> values) {
      return (Map<String, Object>) values;
    }
    return Map.of();
  }

  private String textValue(Object value) {
    return EmptyChecks.isNull(value) ? null : String.valueOf(value);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> parsePartitionSnapshot(JobPartitionEntity partition) {
    if (EmptyChecks.isNull(partition)) {
      return Map.of();
    }
    String inputSnapshot = partition.getInputSnapshot();
    if (EmptyChecks.isBlank(inputSnapshot)) {
      return Map.of();
    }
    try {
      Object parsed = JsonUtils.fromJson(inputSnapshot, Object.class);
      if (parsed instanceof Map<?, ?> snapshot) {
        return (Map<String, Object>) snapshot;
      }
    } catch (IllegalArgumentException badSnapshot) {
      log.warn(
          "partition input_snapshot parse failed, skip typed partition plan fields: partitionId={}, cause={}",
          partition.getId(),
          SwallowedExceptionLogger.summary(badSnapshot));
    }
    return Map.of();
  }

  private Integer intValue(Object value) {
    if (value instanceof Number number) {
      return number.intValue();
    }
    if (value instanceof String text && EmptyChecks.isNotBlank(text)) {
      return Integer.valueOf(text);
    }
    return null;
  }

  private Long longValue(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value instanceof String text && EmptyChecks.isNotBlank(text)) {
      return Long.valueOf(text);
    }
    return null;
  }

  /**
   * task 已 CAS 为 RUNNING 后绑定 partition lease。若首次 claim 仅因 version 漂移失败，重读仍为 READY 时再试一轮， 避免长 IT
   * 套件中其它流程对 job_partition 的并发 touch 把整条认领事务打回 READY。
   */
  private int claimPartitionLeaseForTask(
      String tenantId, Long partitionId, String workerCode, JobPartitionEntity preloadedPartition) {
    // PERF(5.2): 优先复用认领评估阶段已读到的 partition（无 job_partition 写入介于其间，version 未漂移）；
    // 缺省（null）时回退到自读，保持与原实现完全一致的行为。
    JobPartitionEntity partition = EmptyChecks.isNotNull(preloadedPartition)
        ? preloadedPartition
        : jobPartitionMapper.selectById(tenantId, partitionId);
    if (EmptyChecks.isNull(partition)) {
      return 0;
    }
    JobPartitionEntity claimed =
        tryClaimPartitionLeaseOnce(tenantId, partitionId, workerCode, partition);
    if (EmptyChecks.isNotNull(claimed)) {
      return 1;
    }
    partition = jobPartitionMapper.selectById(tenantId, partitionId);
    if (EmptyChecks.isNull(partition)
        || !PartitionStatus.READY.code().equals(partition.getPartitionStatus())) {
      return 0;
    }
    JobPartitionEntity retriedClaim =
        tryClaimPartitionLeaseOnce(tenantId, partitionId, workerCode, partition);
    return EmptyChecks.isNotNull(retriedClaim) ? 1 : 0;
  }

  private JobPartitionEntity tryClaimPartitionLeaseOnce(
      String tenantId, Long partitionId, String workerCode, JobPartitionEntity partition) {
    String invocationId = IdGenerator.newInvocationId();
    Instant invocationStartedAt = BatchDateTimeSupport.utcNow();
    ClaimPartitionParam.ClaimPartitionParamBuilder claimBuilder =
        ClaimPartitionParam.builder().tenantId(tenantId).id(partitionId).workerCode(workerCode);
    Instant leaseBaseTime = BatchDateTimeSupport.utcNow();
    long leaseDurationSeconds = partitionLeaseProperties.getExpireSeconds();
    Instant leaseExpiresAt = leaseBaseTime.plusSeconds(leaseDurationSeconds);
    ClaimPartitionParam param = claimBuilder
        .leaseExpireAt(leaseExpiresAt)
        .fromStatus(PartitionStatus.READY.code())
        .toStatus(PartitionStatus.RUNNING.code())
        .expectedVersion(partition.getVersion())
        .currentInvocationId(invocationId)
        .invocationStartedAt(invocationStartedAt)
        .build();
    return jobPartitionMapper.claimPartition(param);
  }

  private static String resolvePriorityBand(Integer priority) {
    if (EmptyChecks.isNull(priority) || priority <= 3) {
      return SchedulingPriorityBand.HIGH.code();
    }
    if (priority <= 6) {
      return SchedulingPriorityBand.MEDIUM.code();
    }
    return SchedulingPriorityBand.LOW.code();
  }

  /**
   * 认领可行性评估结果：是否可认领 + 评估过程中已加载的 {@code job_partition}（可能为 null——task 无 partition 或 worker 不在线时不会读
   * partition）。partition 随结果一并返回，供后续 lease claim 复用，避免二次 selectById（PERF 5.2）。
   */
  private record ClaimEval(boolean claimable, JobPartitionEntity partition) {}

  private ClaimEval evaluateClaim(
      String tenantId, String workerCode, JobTaskEntity task, WorkerLookupMemo workerMemo) {
    if (EmptyChecks.isBlank(workerCode)) {
      return new ClaimEval(false, null);
    }
    // PERF(5.2c): memo 非空时同 (tenant,workerCode) 只查一次 worker_registry（含缓存 miss）。
    WorkerRegistryEntity workerRegistry = EmptyChecks.isNull(workerMemo)
        ? resolveClaimableWorker(tenantId, workerCode)
        : workerMemo.resolve(tenantId, workerCode, this::resolveClaimableWorker);
    if (EmptyChecks.isNull(workerRegistry)
        || !WorkerRegistryStatus.ONLINE.code().equals(workerRegistry.status())) {
      return new ClaimEval(false, null);
    }
    // 调度记录可以绑定具体实例，也可以绑定稳定资源池。资源池中的任一在线实例均可竞争
    // claim，但 CAS 成功后 task/partition 必须改记实际实例 ID，确保续租、取消和结果上报
    // 仍由 invocation fence 精确约束到单个执行实例。
    if (task == null) { // empty-check: allow - Sonar S2259
      return new ClaimEval(true, null);
    }
    String assignedWorkerCode = task.getAssignedWorkerCode();
    boolean assignedToClaimingWorker =
        assignedWorkerCode == null // empty-check: allow - Sonar S2259
            || assignedWorkerCode.isBlank() // empty-check: allow - Sonar S2259
            || assignedWorkerCode.equals(workerCode)
            || assignedWorkerCode.equals(workerRegistry.routingCode());
    if (!assignedToClaimingWorker) {
      return new ClaimEval(false, null);
    }
    if (task.getJobPartitionId() == null) { // empty-check: allow - Sonar S2259
      return new ClaimEval(true, null);
    }
    JobPartitionEntity partition =
        jobPartitionMapper.selectById(tenantId, task.getJobPartitionId());
    if (EmptyChecks.isNull(partition)) {
      return new ClaimEval(true, partition);
    }
    String partitionWorkerGroup = partition.getWorkerGroup();
    if (EmptyChecks.isBlank(partitionWorkerGroup)) {
      return new ClaimEval(true, partition);
    }
    String registeredWorkerGroup = workerRegistry.workerGroup();
    boolean sameWorkerGroup = partitionWorkerGroup.equalsIgnoreCase(registeredWorkerGroup);
    return new ClaimEval(sameWorkerGroup, partition);
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
    if (isOnlineWorker(primary)) {
      return primary;
    }
    String fallbackTenant = EmptyChecks.isNull(resourceSchedulerProperties)
        ? null
        : resourceSchedulerProperties.getSharedTenantFallback();
    if (EmptyChecks.isBlank(fallbackTenant) || fallbackTenant.equals(tenantId)) {
      return null;
    }
    WorkerRegistryEntity fallback =
        workerRegistryMapper.selectByTenantAndWorkerCode(fallbackTenant, workerCode);
    if (EmptyChecks.isNotNull(fallback)) {
      log.debug(
          "worker claim resolved via shared tenant fallback: tenantId={}, fallbackTenant={},"
              + " workerCode={}",
          tenantId,
          fallbackTenant,
          workerCode);
    }
    return fallback;
  }

  private static boolean isOnlineWorker(WorkerRegistryEntity worker) {
    return EmptyChecks.isNotNull(worker)
        && WorkerRegistryStatus.ONLINE.code().equals(worker.status());
  }
}

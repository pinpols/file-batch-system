package io.github.pinpols.batch.orchestrator.application.service.task;

import io.github.pinpols.batch.common.dto.EffectiveTaskConfig;
import io.github.pinpols.batch.common.enums.PartitionStatus;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.SchedulingPriorityBand;
import io.github.pinpols.batch.common.enums.TaskStatus;
import io.github.pinpols.batch.common.enums.WorkerRegistryStatus;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.IdGenerator;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.Texts;
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
import io.github.pinpols.batch.orchestrator.domain.param.MarkRunningParam;
import io.github.pinpols.batch.orchestrator.domain.param.RenewLeaseBatchItem;
import io.github.pinpols.batch.orchestrator.domain.param.RenewLeaseBatchRow;
import io.github.pinpols.batch.orchestrator.domain.param.RenewLeaseParam;
import io.github.pinpols.batch.orchestrator.domain.param.UpdateTaskStatusParam;
import io.github.pinpols.batch.orchestrator.domain.query.JobExecutionLogQuery;
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
    return assignWorker(tenantId, taskId, workerCode, null);
  }

  @Override
  @Transactional
  public JobTaskEntity assignWorker(
      String tenantId, Long taskId, String workerCode, WorkerLookupMemo workerMemo) {
    // 入口语义：如果不可认领（worker 不在线/组不匹配/状态不允许），返回 current（由 controller 转换为 409/404）。
    JobTaskEntity current = jobTaskMapper.selectById(tenantId, taskId);
    if (current == null) {
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
      int claimed =
          claimPartitionLeaseForTask(
              tenantId, current.getJobPartitionId(), workerCode, eval.partition());
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
    return renewTaskLease(current, tenantId, taskId, workerCode, partitionInvocationId);
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
    // PERF(5.3): 一次 job_task selectById 同时服务续租校验与 cancelRequested 回读，删掉原先续租后的第二次
    // selectById。cancel 是协作式（worker 下一拍即可感知），用续租前读到的标记读回可接受；租约 CAS 未改。
    JobTaskEntity current = jobTaskMapper.selectById(tenantId, taskId);
    boolean renewed = renewTaskLease(current, tenantId, taskId, workerCode, partitionInvocationId);
    if (!renewed) {
      return new TaskHeartbeatResult(false, false);
    }
    if (Texts.hasText(detailsJson)) {
      jobTaskMapper.updateHeartbeatDetails(tenantId, taskId, detailsJson);
    }
    boolean cancelRequested = Boolean.TRUE.equals(current.getCancelRequested());
    return new TaskHeartbeatResult(true, cancelRequested);
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
    if (items == null || items.isEmpty()) {
      return List.of();
    }
    Instant leaseExpireAt =
        BatchDateTimeSupport.utcNow().plusSeconds(partitionLeaseProperties.getExpireSeconds());
    List<RenewLeaseBatchItem> sqlItems = new ArrayList<>(items.size());
    for (LeaseRenewCommand item : items) {
      if (item == null
          || !Texts.hasText(item.tenantId())
          || item.taskId() == null
          || !Texts.hasText(item.workerCode())
          || !Texts.hasText(item.partitionInvocationId())) {
        continue;
      }
      sqlItems.add(
          RenewLeaseBatchItem.builder()
              .tenantId(item.tenantId())
              .taskId(item.taskId())
              .workerCode(item.workerCode())
              .invocationId(item.partitionInvocationId())
              .build());
    }
    Map<String, RenewLeaseBatchRow> renewedByKey = new HashMap<>();
    if (!sqlItems.isEmpty()) {
      for (RenewLeaseBatchRow row :
          jobPartitionMapper.renewLeaseBatch(sqlItems, leaseExpireAt, TaskStatus.RUNNING.code())) {
        renewedByKey.put(row.getTenantId() + "\u0000" + row.getTaskId(), row);
      }
    }
    List<TaskHeartbeatResult> results = new ArrayList<>(items.size());
    for (LeaseRenewCommand item : items) {
      RenewLeaseBatchRow row =
          item == null ? null : renewedByKey.get(item.tenantId() + "\u0000" + item.taskId());
      results.add(
          row == null
              ? new TaskHeartbeatResult(false, false)
              : new TaskHeartbeatResult(true, Boolean.TRUE.equals(row.getCancelRequested())));
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
    return loadEffectiveConfig(tenantId, task);
  }

  /**
   * PERF(5.2b): 复用调用方已持有的 task 实体（claim 成功后 assignWorker 返回的最新行），省掉一次 job_task selectById。partition
   * 仍实时读 —— claim 刚写入的 current_invocation_id 必须取最新；instance/definition 本就只在此处读，无重复。
   */
  @Override
  public EffectiveTaskConfig loadEffectiveConfig(String tenantId, JobTaskEntity task) {
    if (task == null) {
      return null;
    }
    Long taskId = task.getId();
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
    Map<String, Object> partitionSnapshot = parsePartitionSnapshot(partition);
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
        intValue(partitionSnapshot.get("partitionPlanVersion")),
        intValue(partitionSnapshot.get("shardIndex")),
        intValue(partitionSnapshot.get("shardTotal")),
        longValue(partitionSnapshot.get("rangeStartInclusive")),
        longValue(partitionSnapshot.get("rangeEndExclusive")),
        longValue(partitionSnapshot.get("expectedRows")),
        // V94: data_interval 透传 — 创建 instance 时已落到 job_instance, claim 时实时读
        instance.getDataIntervalStart(),
        instance.getDataIntervalEnd(),
        partition == null ? null : partition.getCurrentInvocationId());
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> parsePartitionSnapshot(JobPartitionEntity partition) {
    if (partition == null
        || partition.getInputSnapshot() == null
        || partition.getInputSnapshot().isBlank()) {
      return Map.of();
    }
    try {
      Object parsed = JsonUtils.fromJson(partition.getInputSnapshot(), Object.class);
      if (parsed instanceof Map<?, ?> snapshot) {
        return (Map<String, Object>) snapshot;
      }
    } catch (IllegalArgumentException badSnapshot) {
      log.warn(
          "partition input_snapshot parse failed, skip typed partition plan fields: partitionId={}",
          partition.getId(),
          badSnapshot);
    }
    return Map.of();
  }

  private Integer intValue(Object value) {
    if (value instanceof Number number) {
      return number.intValue();
    }
    if (value instanceof String text && !text.isBlank()) {
      return Integer.valueOf(text);
    }
    return null;
  }

  private Long longValue(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value instanceof String text && !text.isBlank()) {
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
    JobPartitionEntity partition =
        preloadedPartition != null
            ? preloadedPartition
            : jobPartitionMapper.selectById(tenantId, partitionId);
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

  /**
   * 认领可行性评估结果：是否可认领 + 评估过程中已加载的 {@code job_partition}（可能为 null——task 无 partition 或 worker 不在线时不会读
   * partition）。partition 随结果一并返回，供后续 lease claim 复用，避免二次 selectById（PERF 5.2）。
   */
  private record ClaimEval(boolean claimable, JobPartitionEntity partition) {}

  private ClaimEval evaluateClaim(
      String tenantId, String workerCode, JobTaskEntity task, WorkerLookupMemo workerMemo) {
    if (workerCode == null || workerCode.isBlank()) {
      return new ClaimEval(false, null);
    }
    // PERF(5.2c): memo 非空时同 (tenant,workerCode) 只查一次 worker_registry（含缓存 miss）。
    WorkerRegistryEntity workerRegistry =
        workerMemo == null
            ? resolveClaimableWorker(tenantId, workerCode)
            : workerMemo.resolve(tenantId, workerCode, this::resolveClaimableWorker);
    if (workerRegistry == null
        || !WorkerRegistryStatus.ONLINE.code().equals(workerRegistry.status())) {
      return new ClaimEval(false, null);
    }
    if (task == null || task.getJobPartitionId() == null) {
      return new ClaimEval(true, null);
    }
    JobPartitionEntity partition =
        jobPartitionMapper.selectById(tenantId, task.getJobPartitionId());
    if (partition == null
        || partition.getWorkerGroup() == null
        || partition.getWorkerGroup().isBlank()) {
      return new ClaimEval(true, partition);
    }
    return new ClaimEval(
        partition.getWorkerGroup().equalsIgnoreCase(workerRegistry.workerGroup()), partition);
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

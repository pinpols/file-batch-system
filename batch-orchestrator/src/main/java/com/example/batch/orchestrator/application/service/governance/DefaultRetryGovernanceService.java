package com.example.batch.orchestrator.application.service.governance;

import com.example.batch.common.enums.DeadLetterErrorClass;
import com.example.batch.common.enums.DeadLetterReplayStatus;
import com.example.batch.common.enums.PartitionStatus;
import com.example.batch.common.enums.RetryPolicyType;
import com.example.batch.common.enums.RetryScheduleStatus;
import com.example.batch.common.enums.RunMode;
import com.example.batch.common.enums.StepInstanceStatus;
import com.example.batch.common.enums.TaskStatus;
import com.example.batch.common.logging.BatchMdc;
import com.example.batch.common.logging.StructuredLogField;
import com.example.batch.orchestrator.application.engine.TaskDispatchOutboxService;
import com.example.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import com.example.batch.orchestrator.domain.entity.DeadLetterTaskEntity;
import com.example.batch.orchestrator.domain.entity.JobDefinitionEntity;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import com.example.batch.orchestrator.domain.entity.JobStepInstanceEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.domain.entity.RetryScheduleEntity;
import com.example.batch.orchestrator.domain.query.JobTaskQuery;
import com.example.batch.orchestrator.domain.query.RetryScheduleQuery;
import com.example.batch.orchestrator.mapper.DeadLetterTaskMapper;
import com.example.batch.orchestrator.mapper.JobDefinitionMapper;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.JobPartitionMapper;
import com.example.batch.orchestrator.mapper.JobStepInstanceMapper;
import com.example.batch.orchestrator.mapper.JobTaskMapper;
import com.example.batch.orchestrator.mapper.RetryScheduleMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 失败重试与死信治理（Orchestrator 侧）。
 *
 * <p>总体策略：
 *
 * <ul>
 *   <li>task 执行失败后（worker report）由治理层决定是否进入重试：写 {@code retry_schedule} 或写 {@code dead_letter_task}。
 *   <li>重试不是“原地把 task 置回 READY”这么简单，而是通过重排队（requeue）统一回到 outbox → Kafka 的派发链路。
 * </ul>
 *
 * <p>为什么要通过 outbox 回流：
 *
 * <ul>
 *   <li>避免多处（launch / retry / reclaim）各自拼 Kafka 消息协议，减少漂移风险。
 *   <li>事务边界清晰：DB 状态更新与 outbox 落库同事务，Kafka 投递由 forwarder 异步完成。
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultRetryGovernanceService implements RetryGovernanceService {

  /**
   * 一次性硬错——即使作业配置了 retry_policy 也不重试，直接进死信。 这类错误说明请求 payload 本身缺字段或引用的资源根本不存在，再等一等不会自愈， 指数 backoff
   * 只会把 dead_letter_task 灌满。
   */
  private static final Set<String> NON_RETRYABLE_ERROR_CODES =
      Set.of(
          "DISPATCH_PREPARE_FILE_MISSING",
          "DISPATCH_PREPARE_FILE_NOT_FOUND",
          "DISPATCH_PREPARE_CHANNEL_NOT_FOUND",
          "DISPATCH_PREPARE_INVALID",
          "DISPATCH_PREPARE_PARSE_FAILED",
          "EXPORT_GENERATE_NO_PAYLOAD",
          "STEP_NOT_FOUND");

  private final RetryScheduleMapper retryScheduleMapper;
  private final DeadLetterTaskMapper deadLetterTaskMapper;
  private final JobDefinitionMapper jobDefinitionMapper;
  private final JobTaskMapper jobTaskMapper;
  private final JobPartitionMapper jobPartitionMapper;
  private final JobInstanceMapper jobInstanceMapper;
  private final JobStepInstanceMapper jobStepInstanceMapper;
  private final TaskDispatchOutboxService taskDispatchOutboxService;
  private final BatchOrchestratorGovernanceProperties governance;

  @Override
  @Transactional
  public boolean scheduleRetryIfNecessary(
      JobTaskEntity task,
      JobPartitionEntity partition,
      JobInstanceEntity jobInstance,
      String errorCode,
      String errorMessage) {
    // 入口语义：返回 true 表示“已进入 RETRYING（有后续重排队）”，返回 false 表示“本次失败将进入终态（dead-letter 或直接失败）”。
    if (task == null || partition == null || jobInstance == null) {
      return false;
    }
    String tenantId = task.getTenantId();
    String traceId = jobInstance.getTraceId();
    boolean injectMdc = traceId != null && !traceId.isBlank();
    if (injectMdc) {
      BatchMdc.put(StructuredLogField.TENANT_ID, tenantId);
      BatchMdc.put(StructuredLogField.TRACE_ID, traceId);
      BatchMdc.put(
          StructuredLogField.JOB_INSTANCE_ID,
          jobInstance.getId() == null ? null : String.valueOf(jobInstance.getId()));
    }
    try {
      if (errorCode != null && NON_RETRYABLE_ERROR_CODES.contains(errorCode)) {
        // 硬错跳过重试——见 NON_RETRYABLE_ERROR_CODES 的语义说明。
        log.info(
            "skipping retry for non-retryable error: tenantId={}, partitionId={}, errorCode={}",
            task.getTenantId(),
            partition.getId(),
            errorCode);
        createDeadLetter(task, partition, jobInstance, errorCode, errorMessage);
        return false;
      }
      RetryPolicyPlan retryPolicyPlan = resolveRetryPolicy(jobInstance.getJobDefinitionId());
      if (RetryPolicyType.NONE.code().equals(retryPolicyPlan.retryPolicy())
          || retryPolicyPlan.maxRetryCount() <= 0) {
        // 无重试预算：直接进入死信，交由人工/审批/补偿处理。
        createDeadLetter(task, partition, jobInstance, errorCode, errorMessage);
        return false;
      }

      int nextRetryCount = Optional.ofNullable(partition.getRetryCount()).orElse(0) + 1;
      if (nextRetryCount > retryPolicyPlan.maxRetryCount()) {
        // 重试耗尽：落死信（不会回到正常 dispatch）。
        createDeadLetter(task, partition, jobInstance, errorCode, errorMessage);
        return false;
      }

      RetryScheduleEntity retrySchedule = new RetryScheduleEntity();
      retrySchedule.setTenantId(task.getTenantId());
      retrySchedule.setRelatedType("JOB_PARTITION");
      retrySchedule.setRelatedId(partition.getId());
      retrySchedule.setRetryPolicy(retryPolicyPlan.retryPolicy());
      retrySchedule.setRetryCount(nextRetryCount);
      retrySchedule.setMaxRetryCount(retryPolicyPlan.maxRetryCount());
      retrySchedule.setNextRetryAt(
          calculateNextRetryAt(retryPolicyPlan.retryPolicy(), nextRetryCount));
      retrySchedule.setRetryStatus(RetryScheduleStatus.WAITING.code());
      retrySchedule.setDedupKey(
          task.getTenantId() + ":" + partition.getId() + ":" + nextRetryCount);
      retrySchedule.setLastErrorCode(errorCode);
      retrySchedule.setLastErrorMessage(errorMessage);
      retryScheduleMapper.insert(retrySchedule);
      log.info(
          "retry scheduled: tenantId={}, partitionId={}, retryCount={}",
          task.getTenantId(),
          partition.getId(),
          nextRetryCount);
      return true;
    } finally {
      if (injectMdc) {
        BatchMdc.remove(StructuredLogField.JOB_INSTANCE_ID);
        BatchMdc.remove(StructuredLogField.TRACE_ID);
        BatchMdc.remove(StructuredLogField.TENANT_ID);
      }
    }
  }

  @Override
  @Transactional
  public void dispatchDueRetries() {
    // 定时扫描：把到期的 retry_schedule 记录重排队（requeue）回 outbox。
    List<RetryScheduleEntity> dueRetries =
        retryScheduleMapper.selectByQuery(
            new RetryScheduleQuery(
                null,
                RetryScheduleStatus.WAITING.code(),
                Instant.now(),
                governance.retry().getBatchSize()));
    for (RetryScheduleEntity retrySchedule : dueRetries) {
      if (retryScheduleMapper.markRunning(
              retrySchedule.getId(),
              RetryScheduleStatus.WAITING.code(),
              RetryScheduleStatus.RUNNING.code())
          <= 0) {
        continue;
      }
      try {
        requeuePartition(retrySchedule);
        retryScheduleMapper.markSuccess(retrySchedule.getId(), RetryScheduleStatus.SUCCESS.code());
      } catch (TransientConflictException conflict) {
        log.warn(
            "retry dispatch version conflict, will retry later: retryId={}, error={}",
            retrySchedule.getId(),
            conflict.getMessage());
        retryScheduleMapper.resetToWaiting(
            retrySchedule.getId(), RetryScheduleStatus.WAITING.code());
      } catch (Exception exception) {
        log.warn(
            "retry dispatch failed: retryId={}, error={}",
            retrySchedule.getId(),
            exception.getMessage(),
            exception);
        RetryScheduleMapper.MarkFailedParam markFailedParam =
            RetryScheduleMapper.MarkFailedParam.builder()
                .id(retrySchedule.getId())
                .retryStatus(RetryScheduleStatus.FAILED.code())
                .lastErrorCode("RETRY_DISPATCH_FAILED")
                .lastErrorMessage(exception.getMessage())
                .nextRetryAt(Instant.now())
                .build();
        retryScheduleMapper.markFailed(markFailedParam);
      }
    }
  }

  @Override
  public void autoRetryDueDeadLetters() {
    // V90: 不挂 @Transactional——每条死信走独立 REQUIRES_NEW (replayDeadLetter 内部已注解), 单条失败不影响整批扫描
    List<DeadLetterTaskEntity> dueRecords =
        deadLetterTaskMapper.selectDueAutoRetries(governance.retry().getBatchSize());
    for (DeadLetterTaskEntity record : dueRecords) {
      String tenantId = record.getTenantId();
      Long deadLetterId = record.getId();
      int currentReplayCount = record.getReplayCount() == null ? 0 : record.getReplayCount();
      int maxReplayCount = record.getMaxReplayCount() == null ? 0 : record.getMaxReplayCount();
      // 边界保护: scheduler 选出的应满足 replay_count < max_replay_count, 但配置 / 数据漂移可能让 max=0 漏进来
      if (currentReplayCount >= maxReplayCount) {
        deadLetterTaskMapper.markGiveUp(tenantId, deadLetterId);
        log.warn(
            "dead letter give up (max replay count reached on entry): tenantId={},"
                + " deadLetterId={}, replayCount={}, maxReplayCount={}",
            tenantId,
            deadLetterId,
            currentReplayCount,
            maxReplayCount);
        continue;
      }
      try {
        replayDeadLetter(tenantId, deadLetterId);
        log.info(
            "dead letter auto-retry succeeded: tenantId={}, deadLetterId={}," + " attempt={}",
            tenantId,
            deadLetterId,
            currentReplayCount + 1);
      } catch (Exception ex) {
        // markReplayFailure 已在 replayDeadLetter 内部被调用; 这里检查是否已用尽预算, 转 GIVE_UP
        int newReplayCount = currentReplayCount + 1;
        if (newReplayCount >= maxReplayCount) {
          deadLetterTaskMapper.markGiveUp(tenantId, deadLetterId);
          log.warn(
              "dead letter give up (max replay count exhausted): tenantId={},"
                  + " deadLetterId={}, replayCount={}, maxReplayCount={}, lastError={}",
              tenantId,
              deadLetterId,
              newReplayCount,
              maxReplayCount,
              ex.getMessage());
        } else {
          log.info(
              "dead letter auto-retry failed, will back off: tenantId={},"
                  + " deadLetterId={}, attempt={}, error={}",
              tenantId,
              deadLetterId,
              newReplayCount,
              ex.getMessage());
        }
      }
    }
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void retryPartition(String tenantId, Long partitionId, String eventKey) {
    requeuePartition(tenantId, partitionId, eventKey);
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void retryTask(String tenantId, Long taskId, String eventKey) {
    JobTaskEntity task = jobTaskMapper.selectById(tenantId, taskId);
    if (task == null) {
      throw new IllegalStateException("retry task not found");
    }
    String status = task.getStatus();
    if (!TaskStatus.FAILED.code().equals(status)
        && !TaskStatus.CANCELLED.code().equals(status)
        && !TaskStatus.TERMINATED.code().equals(status)) {
      throw new IllegalStateException(
          "retry only allowed from terminal state (FAILED/CANCELLED/TERMINATED), current"
              + " status: "
              + status);
    }
    if (task.getJobPartitionId() != null) {
      requeuePartition(tenantId, task.getJobPartitionId(), eventKey);
      return;
    }
    requeueTaskWithoutPartition(tenantId, task, eventKey);
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void reclaimTask(String tenantId, Long taskId, String eventKey) {
    JobTaskEntity task = jobTaskMapper.selectById(tenantId, taskId);
    if (task == null) {
      throw new IllegalStateException("reclaim task not found");
    }
    String status = task.getStatus();
    if (!TaskStatus.RUNNING.code().equals(status)
        && !TaskStatus.FAILED.code().equals(status)
        && !TaskStatus.CANCELLED.code().equals(status)
        && !TaskStatus.TERMINATED.code().equals(status)) {
      throw new IllegalStateException(
          "reclaim only allowed from RUNNING or terminal state, current status: " + status);
    }
    if (task.getJobPartitionId() != null) {
      requeuePartition(tenantId, task.getJobPartitionId(), eventKey);
      return;
    }
    requeueTaskWithoutPartition(tenantId, task, eventKey);
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void replayDeadLetter(String tenantId, Long deadLetterTaskId) {
    DeadLetterTaskEntity deadLetterTask =
        deadLetterTaskMapper.selectById(tenantId, deadLetterTaskId);
    if (deadLetterTask == null) {
      throw new IllegalStateException("dead letter task not found");
    }
    String traceId = deadLetterTask.getTraceId();
    boolean injectMdc = traceId != null && !traceId.isBlank();
    if (injectMdc) {
      BatchMdc.put(StructuredLogField.TENANT_ID, tenantId);
      BatchMdc.put(StructuredLogField.TRACE_ID, traceId);
      BatchMdc.put(StructuredLogField.JOB_INSTANCE_ID, null);
    }
    try {
      if (!DeadLetterReplayStatus.NEW.code().equals(deadLetterTask.getReplayStatus())
          && !DeadLetterReplayStatus.FAILED.code().equals(deadLetterTask.getReplayStatus())) {
        throw new IllegalStateException("dead letter task is not replayable");
      }
      if (deadLetterTaskMapper.markReplaying(
              tenantId,
              deadLetterTaskId,
              deadLetterTask.getReplayStatus(),
              DeadLetterReplayStatus.REPLAYING.code())
          <= 0) {
        throw new IllegalStateException("dead letter task replay conflict");
      }
      Instant replayAt = Instant.now();
      int replayCount = Optional.ofNullable(deadLetterTask.getReplayCount()).orElse(0) + 1;
      try {
        if (!"JOB_PARTITION".equals(deadLetterTask.getSourceType())) {
          throw new IllegalStateException(
              "unsupported dead letter source type: " + deadLetterTask.getSourceType());
        }
        requeuePartition(
            tenantId, deadLetterTask.getSourceId(), tenantId + ":dead-letter:" + deadLetterTaskId);
        deadLetterTaskMapper.markReplaySuccess(
            tenantId,
            deadLetterTaskId,
            DeadLetterReplayStatus.SUCCESS.code(),
            replayCount,
            replayAt,
            "REPLAY_ACCEPTED");
      } catch (Exception exception) {
        log.warn(
            "dead letter replay failed: deadLetterId={}, error={}",
            deadLetterTaskId,
            exception.getMessage(),
            exception);
        // V90: 失败时按指数退避算下次自动重放时间; BUSINESS / 已无自动重放预算的记录不安排（next_replay_at=null）
        Instant nextAuto =
            shouldScheduleAutoRetry(deadLetterTask, replayCount)
                ? calculateNextRetryAt(RetryPolicyType.EXPONENTIAL.code(), replayCount)
                : null;
        deadLetterTaskMapper.markReplayFailure(
            tenantId,
            deadLetterTaskId,
            DeadLetterReplayStatus.FAILED.code(),
            replayCount,
            replayAt,
            exception.getMessage(),
            nextAuto);
        throw exception;
      }
    } finally {
      if (injectMdc) {
        BatchMdc.remove(StructuredLogField.JOB_INSTANCE_ID);
        BatchMdc.remove(StructuredLogField.TRACE_ID);
        BatchMdc.remove(StructuredLogField.TENANT_ID);
      }
    }
  }

  private void requeuePartition(RetryScheduleEntity retrySchedule) {
    requeuePartition(
        retrySchedule.getTenantId(),
        retrySchedule.getRelatedId(),
        retrySchedule.getTenantId() + ":retry:" + retrySchedule.getId());
  }

  private void requeuePartition(String tenantId, Long partitionId, String eventKey) {
    JobPartitionEntity partition = jobPartitionMapper.selectById(tenantId, partitionId);
    if (partition == null) {
      throw new IllegalStateException("retry partition not found");
    }
    JobInstanceEntity jobInstance =
        jobInstanceMapper.selectById(tenantId, partition.getJobInstanceId());
    if (jobInstance == null) {
      throw new IllegalStateException("retry job instance not found");
    }
    String traceId = jobInstance.getTraceId();
    boolean injectMdc = traceId != null && !traceId.isBlank();
    if (injectMdc) {
      BatchMdc.put(StructuredLogField.TENANT_ID, tenantId);
      BatchMdc.put(StructuredLogField.TRACE_ID, traceId);
      BatchMdc.put(
          StructuredLogField.JOB_INSTANCE_ID,
          jobInstance.getId() == null ? null : String.valueOf(jobInstance.getId()));
    }
    try {
      List<JobTaskEntity> tasks =
          jobTaskMapper.selectByQuery(
              new JobTaskQuery(tenantId, jobInstance.getId(), partition.getId(), null, null));
      JobTaskEntity task =
          tasks.stream()
              .sorted(
                  (left, right) ->
                      Integer.compare(
                          left.getTaskSeq() == null ? 0 : left.getTaskSeq(),
                          right.getTaskSeq() == null ? 0 : right.getTaskSeq()))
              .findFirst()
              .orElseThrow(() -> new IllegalStateException("retry task not found"));

      JobStepInstanceEntity stepInstance =
          jobStepInstanceMapper.selectByJobTaskId(tenantId, task.getId());
      if (stepInstance != null) {
        int nextRetryCount = Optional.ofNullable(stepInstance.getRetryCount()).orElse(0) + 1;
        jobStepInstanceMapper.resetForRetryByJobTaskId(
            tenantId, task.getId(), nextRetryCount, StepInstanceStatus.READY.code());
      }
      if (jobPartitionMapper.resetForDispatch(
              tenantId, partition.getId(), PartitionStatus.READY.code(), partition.getVersion())
          <= 0) {
        throw new TransientConflictException(
            "partition version conflict, requeue aborted: partitionId=" + partition.getId());
      }
      if (jobTaskMapper.resetForRetry(
              tenantId, task.getId(), TaskStatus.READY.code(), task.getVersion())
          <= 0) {
        throw new TransientConflictException(
            "task version conflict, requeue aborted: taskId=" + task.getId());
      }
      taskDispatchOutboxService.writeDispatchEvent(
          jobInstance, task, partition, jobInstance.getTraceId(), eventKey, RunMode.RETRY);
    } finally {
      if (injectMdc) {
        BatchMdc.remove(StructuredLogField.JOB_INSTANCE_ID);
        BatchMdc.remove(StructuredLogField.TRACE_ID);
        BatchMdc.remove(StructuredLogField.TENANT_ID);
      }
    }
  }

  private void requeueTaskWithoutPartition(String tenantId, JobTaskEntity task, String eventKey) {
    JobInstanceEntity jobInstance = jobInstanceMapper.selectById(tenantId, task.getJobInstanceId());
    if (jobInstance == null) {
      throw new IllegalStateException("retry job instance not found");
    }
    JobStepInstanceEntity stepInstance =
        jobStepInstanceMapper.selectByJobTaskId(tenantId, task.getId());
    if (stepInstance != null) {
      int nextRetryCount = Optional.ofNullable(stepInstance.getRetryCount()).orElse(0) + 1;
      jobStepInstanceMapper.resetForRetryByJobTaskId(
          tenantId, task.getId(), nextRetryCount, StepInstanceStatus.READY.code());
    }
    if (jobTaskMapper.resetForRetry(
            tenantId, task.getId(), TaskStatus.READY.code(), task.getVersion())
        <= 0) {
      throw new TransientConflictException(
          "task version conflict, requeue aborted: taskId=" + task.getId());
    }
    taskDispatchOutboxService.writeDispatchEvent(
        jobInstance, task, null, jobInstance.getTraceId(), eventKey, RunMode.RETRY);
  }

  private RetryPolicyPlan resolveRetryPolicy(Long jobDefinitionId) {
    if (jobDefinitionId == null) {
      return new RetryPolicyPlan(
          RetryPolicyType.FIXED.code(), governance.retry().getDefaultMaxRetryCount());
    }
    JobDefinitionEntity jobDefinitionRecord = jobDefinitionMapper.selectById(jobDefinitionId);
    if (jobDefinitionRecord == null) {
      return new RetryPolicyPlan(
          RetryPolicyType.FIXED.code(), governance.retry().getDefaultMaxRetryCount());
    }
    String retryPolicy = jobDefinitionRecord.retryPolicy();
    Integer retryMaxCount = jobDefinitionRecord.retryMaxCount();
    if (retryPolicy == null || retryPolicy.isBlank()) {
      retryPolicy = RetryPolicyType.FIXED.code();
    }
    return new RetryPolicyPlan(
        retryPolicy,
        retryMaxCount == null ? governance.retry().getDefaultMaxRetryCount() : retryMaxCount);
  }

  /** 重试时间由治理层统一计算，避免调度器和业务处理各自散落一套 backoff 规则。 */
  private Instant calculateNextRetryAt(String retryPolicy, int retryCount) {
    long delaySeconds = governance.retry().getFixedDelaySeconds();
    if (RetryPolicyType.EXPONENTIAL.code().equalsIgnoreCase(retryPolicy)) {
      long multiplier = Math.max(1L, governance.retry().getExponentialMultiplier());
      long maxDelay = governance.retry().getMaxDelaySeconds();
      long candidate = delaySeconds;
      for (int i = 1; i < retryCount; i++) {
        if (candidate >= maxDelay) {
          candidate = maxDelay;
          break;
        }
        // L-1: 乘法前溢出保护，candidate * multiplier 可能溢出 long 范围
        if (candidate > maxDelay / multiplier) {
          candidate = maxDelay;
        } else {
          candidate = Math.min(maxDelay, candidate * multiplier);
        }
      }
      delaySeconds = candidate;
    }
    return Instant.now().plusSeconds(delaySeconds);
  }

  private void createDeadLetter(
      JobTaskEntity task,
      JobPartitionEntity partition,
      JobInstanceEntity jobInstance,
      String errorCode,
      String errorMessage) {
    DeadLetterTaskEntity deadLetterTask = new DeadLetterTaskEntity();
    deadLetterTask.setTenantId(task.getTenantId());
    deadLetterTask.setSourceType("JOB_PARTITION");
    deadLetterTask.setSourceId(partition.getId());
    deadLetterTask.setDeadLetterReason(buildDeadLetterReason(errorCode, errorMessage));
    deadLetterTask.setPayloadRef(jobInstance.getInstanceNo() + ":" + task.getId());
    deadLetterTask.setReplayStatus(DeadLetterReplayStatus.NEW.code());
    deadLetterTask.setReplayCount(0);
    deadLetterTask.setTraceId(jobInstance.getTraceId());
    // V90: 错误分类 + 自动重放预算
    DeadLetterErrorClass errorClass = classifyErrorClass(errorCode);
    deadLetterTask.setErrorClass(errorClass.code());
    if (errorClass == DeadLetterErrorClass.SYSTEM) {
      deadLetterTask.setMaxReplayCount(governance.retry().getDefaultMaxRetryCount());
      // 第 1 次自动重放: now + fixedDelaySeconds（与 retry_schedule FIXED 策略对齐）
      deadLetterTask.setNextReplayAt(
          Instant.now().plusSeconds(governance.retry().getFixedDelaySeconds()));
    } else {
      // BUSINESS 错误: 自动重放永远不会自愈, 仅人工触发
      deadLetterTask.setMaxReplayCount(0);
      deadLetterTask.setNextReplayAt(null);
    }
    deadLetterTaskMapper.insert(deadLetterTask);
    log.warn(
        "dead letter created: tenantId={}, partitionId={}, instanceNo={}, errorClass={}",
        task.getTenantId(),
        partition.getId(),
        jobInstance.getInstanceNo(),
        errorClass.code());
  }

  /**
   * V90: 错误代码分类。{@link #NON_RETRYABLE_ERROR_CODES} 内的错误是 BUSINESS（硬错，自动重放不会自愈）， 其他归
   * SYSTEM（瞬态/可恢复，自动重放 走指数退避）。
   */
  private static DeadLetterErrorClass classifyErrorClass(String errorCode) {
    if (errorCode != null && NON_RETRYABLE_ERROR_CODES.contains(errorCode)) {
      return DeadLetterErrorClass.BUSINESS;
    }
    return DeadLetterErrorClass.SYSTEM;
  }

  /**
   * V90: 是否还应安排下一次自动重放。null entity（人工 replay 时找不到原 entity）按 SYSTEM 处理；BUSINESS / 即将达到
   * max_replay_count 时 不再排自动重放（scheduler 后续会把 status 转 GIVE_UP）。
   */
  private static boolean shouldScheduleAutoRetry(DeadLetterTaskEntity entity, int newReplayCount) {
    if (entity == null) {
      return true;
    }
    if (DeadLetterErrorClass.BUSINESS.code().equals(entity.getErrorClass())) {
      return false;
    }
    int max = entity.getMaxReplayCount() == null ? 0 : entity.getMaxReplayCount();
    return newReplayCount < max;
  }

  private String buildDeadLetterReason(String errorCode, String errorMessage) {
    String code = errorCode == null ? "UNKNOWN" : errorCode;
    String message = errorMessage == null ? "retry exhausted" : errorMessage;
    return code + ": " + message;
  }

  private record RetryPolicyPlan(String retryPolicy, int maxRetryCount) {}

  /**
   * 乐观锁 CAS 失败时抛出，表示瞬态版本冲突，可在下次调度周期重试。 与 {@link IllegalStateException}（资源不存在等永久性错误）区分，避免
   * retry_schedule 被错误地置为 FAILED。
   */
  private static final class TransientConflictException extends RuntimeException {
    TransientConflictException(String message) {
      super(message);
    }
  }
}

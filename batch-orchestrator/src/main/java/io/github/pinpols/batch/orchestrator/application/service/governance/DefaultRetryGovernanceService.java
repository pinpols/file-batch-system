package io.github.pinpols.batch.orchestrator.application.service.governance;

import io.github.pinpols.batch.common.enums.DeadLetterErrorClass;
import io.github.pinpols.batch.common.enums.DeadLetterReplayStatus;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.RetryPolicyType;
import io.github.pinpols.batch.common.enums.RetryScheduleStatus;
import io.github.pinpols.batch.common.enums.TaskErrorCodes;
import io.github.pinpols.batch.common.enums.TaskStatus;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.logging.AuditLogConstants;
import io.github.pinpols.batch.common.logging.BatchMdc;
import io.github.pinpols.batch.common.logging.StructuredLogField;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import io.github.pinpols.batch.orchestrator.domain.entity.DeadLetterTaskEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobDefinitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobExecutionLogEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.RetryScheduleEntity;
import io.github.pinpols.batch.orchestrator.domain.param.DeadLetterTaskMarkReplayFailureParam;
import io.github.pinpols.batch.orchestrator.domain.param.DeadLetterTaskMarkReplaySuccessParam;
import io.github.pinpols.batch.orchestrator.domain.query.RetryScheduleQuery;
import io.github.pinpols.batch.orchestrator.mapper.DeadLetterTaskMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobExecutionLogMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import io.github.pinpols.batch.orchestrator.mapper.RetryScheduleMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

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
 *   <li>事务边界清晰：DB 状态更新与 outbox 写入数据库同事务，Kafka 投递由 forwarder 异步完成。
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
  private static final Set<String> NON_RETRYABLE_ERROR_CODES = Set.of(
      "DISPATCH_PREPARE_FILE_MISSING",
      "DISPATCH_PREPARE_FILE_NOT_FOUND",
      "DISPATCH_PREPARE_CHANNEL_NOT_FOUND",
      "DISPATCH_PREPARE_INVALID",
      "DISPATCH_PREPARE_PARSE_FAILED",
      "DISPATCH_SECRET_BINDING_MISSING",
      "DISPATCH_SECRET_VERSION_MISSING",
      "DISPATCH_SECRET_VERSION_INVALID",
      "DISPATCH_SECRET_REVOKED_OR_EXPIRED",
      "DISPATCH_SECRET_FIELD_NOT_ALLOWED",
      "DISPATCH_SECRET_PAYLOAD_EMPTY",
      "DISPATCH_SECRET_PAYLOAD_INVALID",
      "EXPORT_GENERATE_NO_PAYLOAD",
      // 已存在的导出路径 checksum 冲突是幂等目标与内容不一致；重放不会改变既有 file_record。
      "EXPORT_REGISTER_CHECKSUM_CONFLICT",
      // Worker WorkerConfigException → 模板/参数配置缺字段, 等多久也不自愈
      "IMPORT_LOAD_CONFIG_INVALID",
      "EXPORT_GENERATE_CONFIG_INVALID",
      "STEP_NOT_FOUND",
      // 永久性输入/数据错:畸形文件(XML/定长/分隔符)、空文件、坏 SQL/缺表 —— 同一输入重放永不自愈,
      // 自动重试只会让永久失败的任务在 dead_letter 无限循环(实测 3 个夹具 → 653 行死信、replay_count 恒 1)。
      // IMPORT_LOAD_FAILED 也含"load 时 DB 死锁"这类瞬时错,但那在 TaskController @Retryable 阶段已重试过,
      // 到死信层仍失败即视为永久,不再自动重试(与上面 TIMEOUT 同理:重放不自愈)。
      "IMPORT_PARSE_FAILED",
      "IMPORT_PARSE_EMPTY",
      "IMPORT_LOAD_FAILED",
      // 同一 payload / SQL 在相同 statement_timeout 下重放不会自愈；自动重试只会形成风暴。
      "TIMEOUT",
      "WORKER_EXECUTION_TIMEOUT");

  private final RetryScheduleMapper retryScheduleMapper;
  private final DeadLetterTaskMapper deadLetterTaskMapper;
  private final JobDefinitionMapper jobDefinitionMapper;
  private final JobTaskMapper jobTaskMapper;
  private final RetryRequeueCoordinator retryRequeueCoordinator;
  private final BatchOrchestratorGovernanceProperties governance;
  private final JobExecutionLogMapper jobExecutionLogMapper;
  private final PlatformTransactionManager transactionManager;

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
      if (TaskErrorCodes.EXTERNAL_OUTCOME_UNKNOWN.equals(errorCode)) {
        // 结果不明的非幂等外部动作不能按普通瞬态失败盲目重放,交由人工核对/补偿。
        log.warn(
            "automatic retry blocked for unknown external outcome: tenantId={}, jobCode={}, partitionId={}",
            tenantId,
            jobInstance.getJobCode(),
            partition.getId());
        createDeadLetter(task, partition, jobInstance, errorCode, errorMessage);
        return false;
      }
      RetryPolicyPlan retryPolicyPlan = resolveRetryPolicy(jobInstance);
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
      String retryPolicyForNextAttempt = retryPolicyPlan.retryPolicy();
      Instant nextRetryAt = calculateNextRetryAt(retryPolicyForNextAttempt, nextRetryCount);
      retrySchedule.setNextRetryAt(nextRetryAt);
      retrySchedule.setRetryStatus(RetryScheduleStatus.WAITING.code());
      retrySchedule.setDedupKey(
          task.getTenantId() + ":" + partition.getId() + ":" + nextRetryCount);
      retrySchedule.setLastErrorCode(errorCode);
      // retry_schedule.last_error_message 是 varchar(1024)。worker 链路错误消息有时含嵌套
      // SQL detail / stack 摘要,容易越界。Java 侧防御性截断到 1023 字节,加 "…" 标记,
      // 避免 INSERT 失败导致整条重试链长期停滞。
      retrySchedule.setLastErrorMessage(truncateErrorMessage(errorMessage, 1023));
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
  public void dispatchDueRetries() {
    // R2-P0-2：每条 retry 进独立 REQUIRES_NEW，避免外层单事务下某条 CAS 冲突回滚整批 outbox 写
    // （之前已经 markRunning 的 retry 会留在 RUNNING 永远长期停滞）。
    // 1) 扫描（只读，无事务）
    // 2) 每条独立 tx 走 requeueOne：markRunning + requeuePartition + markSuccess 同事务，
    //    抛 TransientConflictException → 整 tx 回滚 → markRunning 也撤销 → 状态自动留 WAITING 等下轮
    // 3) 非 transient 异常 → 在独立 tx 内 markFailed
    RetryScheduleQuery dueRetryQuery = new RetryScheduleQuery(
        null,
        RetryScheduleStatus.WAITING.code(),
        BatchDateTimeSupport.utcNow(),
        governance.retry().getBatchSize());
    List<RetryScheduleEntity> dueRetries = retryScheduleMapper.selectDueNotPaused(dueRetryQuery);
    for (RetryScheduleEntity retrySchedule : dueRetries) {
      try {
        RlsTenantContextHolder.runWithTenant(
            retrySchedule.getTenantId(),
            () -> inNewTransaction(() -> retryRequeueCoordinator.requeueRetry(retrySchedule)));
      } catch (RetryRequeueCoordinator.ExecutionPausedException paused) {
        log.debug(
            "retry dispatch deferred while execution is paused: retryId={}", retrySchedule.getId());
      } catch (RetryRequeueCoordinator.TransientConflictException conflict) {
        log.warn(
            "retry dispatch version conflict, will retry later: retryId={}, error={}",
            retrySchedule.getId(),
            SwallowedExceptionLogger.summary(conflict));
        // markRunning 已随 REQUIRES_NEW 一起回滚，状态留在 WAITING，无需额外 resetToWaiting
      } catch (Exception exception) {
        log.warn("retry dispatch failed: retryId={}", retrySchedule.getId(), exception);
        RetryScheduleMapper.MarkFailedParam markFailedParam =
            RetryScheduleMapper.MarkFailedParam.builder()
                .tenantId(retrySchedule.getTenantId())
                .id(retrySchedule.getId())
                .fromStatus(RetryScheduleStatus.WAITING.code())
                .retryStatus(RetryScheduleStatus.FAILED.code())
                .lastErrorCode("RETRY_DISPATCH_FAILED")
                .lastErrorMessage(exception.getMessage())
                .nextRetryAt(BatchDateTimeSupport.utcNow())
                .build();
        try {
          RlsTenantContextHolder.runWithTenant(
              retrySchedule.getTenantId(),
              () -> inNewTransaction(() -> markRetryFailed(markFailedParam)));
        } catch (RuntimeException markFailedEx) {
          log.error(
              "markFailed itself failed for retryId={}, manual intervention required",
              retrySchedule.getId(),
              markFailedEx);
        }
      }
    }
  }

  /** R2-P0-2：非 transient 失败的独立事务标记，避免与外层扫描状态混合。 */
  private void markRetryFailed(RetryScheduleMapper.MarkFailedParam param) {
    retryScheduleMapper.markFailed(param);
  }

  @Override
  public void autoRetryDueDeadLetters() {
    // 不挂 @Transactional：每条死信独立事务，避免单条失败回滚整批。
    List<DeadLetterTaskEntity> dueRecords =
        deadLetterTaskMapper.selectDueAutoRetries(governance.retry().getBatchSize());
    for (DeadLetterTaskEntity deadLetter : dueRecords) {
      String tenantId = deadLetter.getTenantId();
      Long deadLetterId = deadLetter.getId();
      int currentReplayCount =
          deadLetter.getReplayCount() == null ? 0 : deadLetter.getReplayCount();
      int maxReplayCount =
          deadLetter.getMaxReplayCount() == null ? 0 : deadLetter.getMaxReplayCount();
      // 边界保护: scheduler 选出的应满足 replay_count < max_replay_count, 但配置 / 数据漂移可能让 max=0 漏进来
      if (currentReplayCount >= maxReplayCount) {
        int changed = RlsTenantContextHolder.runWithTenant(
            tenantId, () -> deadLetterTaskMapper.markGiveUp(tenantId, deadLetterId));
        if (changed == 0) {
          continue;
        }
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
        RlsTenantContextHolder.runWithTenant(
            tenantId, () -> replayDeadLetterInNewTransaction(tenantId, deadLetterId));
        log.info(
            "dead letter auto-retry succeeded: tenantId={}, deadLetterId={}," + " attempt={}",
            tenantId,
            deadLetterId,
            currentReplayCount + 1);
      } catch (RetryRequeueCoordinator.ExecutionPausedException paused) {
        log.debug(
            "dead letter replay deferred while execution is paused: deadLetterId={}", deadLetterId);
      } catch (DeadLetterOrphanSourceException ex) {
        log.info(
            "dead letter auto-retry skipped (orphan source, marked GIVE_UP): tenantId={},"
                + " deadLetterId={}, detail={}",
            tenantId,
            deadLetterId,
            SwallowedExceptionLogger.summary(ex));
      } catch (Exception ex) {
        if (isClosedExecutionReplayFailure(ex)) {
          log.info(
              "dead letter auto-retry closed with terminal execution: tenantId={}, deadLetterId={}",
              tenantId,
              deadLetterId);
          continue;
        }
        // markReplayFailure 已在 replayDeadLetter 内部被调用; 这里检查是否已用尽预算, 转 GIVE_UP
        int newReplayCount = currentReplayCount + 1;
        if (newReplayCount >= maxReplayCount) {
          int changed = RlsTenantContextHolder.runWithTenant(
              tenantId, () -> deadLetterTaskMapper.markGiveUp(tenantId, deadLetterId));
          if (changed == 0) {
            continue;
          }
          log.warn(
              "dead letter give up (max replay count exhausted): tenantId={},"
                  + " deadLetterId={}, replayCount={}, maxReplayCount={}, lastError={}",
              tenantId,
              deadLetterId,
              newReplayCount,
              maxReplayCount,
              SwallowedExceptionLogger.summary(ex));
        } else {
          log.info(
              "dead letter auto-retry failed, will back off: tenantId={},"
                  + " deadLetterId={}, attempt={}, error={}",
              tenantId,
              deadLetterId,
              newReplayCount,
              SwallowedExceptionLogger.summary(ex));
        }
      }
    }
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void retryPartition(String tenantId, Long partitionId, String eventKey) {
    retryRequeueCoordinator.requeuePartition(tenantId, partitionId, eventKey);
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void retryTask(String tenantId, Long taskId, String eventKey) {
    JobTaskEntity task = jobTaskMapper.selectById(tenantId, taskId);
    if (task == null) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.task.retry_not_found");
    }
    String status = task.getStatus();
    boolean taskStatusDoesNotAllowRetry = !TaskStatus.FAILED.code().equals(status)
        && !TaskStatus.CANCELLED.code().equals(status)
        && !TaskStatus.TERMINATED.code().equals(status);
    if (taskStatusDoesNotAllowRetry) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.task.retry_not_terminal", status);
    }
    if (task.getJobPartitionId() != null) {
      retryRequeueCoordinator.requeuePartition(tenantId, task.getJobPartitionId(), eventKey);
      return;
    }
    retryRequeueCoordinator.requeueTaskWithoutPartition(tenantId, task, eventKey);
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void reclaimTask(String tenantId, Long taskId, String eventKey) {
    JobTaskEntity task = jobTaskMapper.selectById(tenantId, taskId);
    if (task == null) {
      throw new IllegalStateException("reclaim task not found");
    }
    String status = task.getStatus();
    boolean taskStatusIsNotReclaimable = !TaskStatus.RUNNING.code().equals(status)
        && !TaskStatus.FAILED.code().equals(status)
        && !TaskStatus.CANCELLED.code().equals(status)
        && !TaskStatus.TERMINATED.code().equals(status);
    if (taskStatusIsNotReclaimable) {
      throw new IllegalStateException(
          "reclaim only allowed from RUNNING or terminal state, current status: " + status);
    }
    if (task.getJobPartitionId() != null) {
      retryRequeueCoordinator.requeuePartition(tenantId, task.getJobPartitionId(), eventKey);
      return;
    }
    retryRequeueCoordinator.requeueTaskWithoutPartition(tenantId, task, eventKey);
  }

  @Override
  public void replayDeadLetter(
      String tenantId,
      Long deadLetterTaskId,
      String operatorId,
      String reason,
      String idempotencyKey) {
    DeadLetterReplayInput replayInput = DeadLetterReplayInput.builder()
        .tenantId(tenantId)
        .deadLetterTaskId(deadLetterTaskId)
        .operatorId(operatorId)
        .reason(reason)
        .idempotencyKey(idempotencyKey)
        .externalActionVerifiedNotExecuted(false)
        .externalOutcomeEvidenceReference(null)
        .build();
    replayDeadLetter(replayInput);
  }

  @Override
  public void replayDeadLetter(DeadLetterReplayInput input) {
    String tenantId = input.tenantId();
    Long deadLetterTaskId = input.deadLetterTaskId();
    String operatorId = input.operatorId();
    String reason = input.reason();
    String idempotencyKey = input.idempotencyKey();
    boolean externalActionVerifiedNotExecuted = input.externalActionVerifiedNotExecuted();
    String externalOutcomeEvidenceReference = input.externalOutcomeEvidenceReference();

    boolean hasEvidence = Texts.hasText(externalOutcomeEvidenceReference);
    boolean externalOutcomeEvidenceIncomplete = externalActionVerifiedNotExecuted
        && (!Texts.hasText(operatorId) || !Texts.hasText(reason) || !hasEvidence);
    if (externalOutcomeEvidenceIncomplete) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, "error.dead_letter.external_outcome_evidence_required");
    }
    if (!externalActionVerifiedNotExecuted && hasEvidence) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, "error.dead_letter.external_outcome_confirmation_required");
    }
    if (externalActionVerifiedNotExecuted) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT,
          "error.dead_letter.external_outcome_resolution_requires_compensation");
    }
    // P1-1: 人工触发的死信重放,先写 audit 再走主流程。
    // 审计和实际重放分别独立提交：审计必须在重放失败时保留，重放仍需满足 outbox 的事务要求。
    inNewTransaction(() -> appendDeadLetterReplayAudit(input));
    replayDeadLetterInNewTransaction(tenantId, deadLetterTaskId);
  }

  @Override
  public void auditVerifiedExternalOutcomeResolution(
      String tenantId,
      Long deadLetterTaskId,
      String operatorId,
      String reason,
      String evidenceReference) {
    boolean manualRecoveryEvidenceIsIncomplete =
        !Texts.hasText(operatorId) || !Texts.hasText(reason) || !Texts.hasText(evidenceReference);
    if (manualRecoveryEvidenceIsIncomplete) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, "error.dead_letter.external_outcome_evidence_required");
    }
    inNewTransaction(() -> {
      DeadLetterReplayInput auditInput = DeadLetterReplayInput.builder()
          .tenantId(tenantId)
          .deadLetterTaskId(deadLetterTaskId)
          .operatorId(operatorId)
          .reason(reason)
          .idempotencyKey(null)
          .externalActionVerifiedNotExecuted(true)
          .externalOutcomeEvidenceReference(evidenceReference)
          .build();
      appendDeadLetterReplayAudit(auditInput);
    });
  }

  /** 写一条 DEAD_LETTER_REPLAY 审计日志。 独立 REQUIRES_NEW 短事务,保证即使 replayDeadLetter 自身失败,audit 仍留痕。 */
  private void appendDeadLetterReplayAudit(DeadLetterReplayInput input) {
    String tenantId = input.tenantId();
    Long deadLetterTaskId = input.deadLetterTaskId();
    String operatorId = input.operatorId();
    String reason = input.reason();
    String idempotencyKey = input.idempotencyKey();
    boolean externalActionVerifiedNotExecuted = input.externalActionVerifiedNotExecuted();
    String externalOutcomeEvidenceReference = input.externalOutcomeEvidenceReference();

    if (jobExecutionLogMapper == null) {
      return;
    }
    JobExecutionLogEntity audit = new JobExecutionLogEntity();
    audit.setTenantId(tenantId);
    audit.setJobInstanceId(null);
    audit.setJobPartitionId(null);
    audit.setLogLevel("INFO");
    audit.setLogType(AuditLogConstants.LOG_TYPE_AUDIT);
    audit.setMessage(AuditLogConstants.AUDIT_OP_DEAD_LETTER_REPLAY);
    audit.setDetailRef(AuditLogConstants.DETAIL_REF_DEAD_LETTER_TASK);
    Map<String, Object> extra = new LinkedHashMap<>();
    extra.put("deadLetterTaskId", deadLetterTaskId);
    extra.put(
        "operatorId",
        Texts.hasText(operatorId) ? operatorId : AuditLogConstants.OPERATOR_ID_SYSTEM);
    extra.put(
        "operatorType",
        Texts.hasText(operatorId)
            ? AuditLogConstants.OPERATOR_TYPE_REQUEST
            : AuditLogConstants.OPERATOR_TYPE_SYSTEM);
    extra.put("reason", reason);
    extra.put("idempotencyKey", idempotencyKey);
    if (externalActionVerifiedNotExecuted) {
      extra.put("externalActionOutcome", "VERIFIED_NOT_EXECUTED");
      extra.put("externalOutcomeEvidenceReference", externalOutcomeEvidenceReference);
    }
    String extraJson = JsonUtils.toJson(extra);
    audit.setExtraJson(extraJson);
    jobExecutionLogMapper.insert(audit);
  }

  @Override
  public void replayDeadLetter(String tenantId, Long deadLetterTaskId) {
    replayDeadLetterInNewTransaction(tenantId, deadLetterTaskId);
  }

  @Override
  public void replayDeadLetterInCurrentTransaction(String tenantId, Long deadLetterTaskId) {
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
    RuntimeException failure = transaction.execute(
        status -> replayDeadLetterWithinTransaction(tenantId, deadLetterTaskId, status));
    if (EmptyChecks.isNotNull(failure)) {
      throw failure;
    }
  }

  private RuntimeException replayDeadLetterWithinTransaction(
      String tenantId, Long deadLetterTaskId, TransactionStatus status) {
    return replayDeadLetterWithinTransaction(tenantId, deadLetterTaskId, status, false);
  }

  private RuntimeException replayDeadLetterWithinTransaction(
      String tenantId,
      Long deadLetterTaskId,
      TransactionStatus status,
      boolean externalActionVerifiedNotExecuted) {
    DeadLetterTaskEntity deadLetterTask =
        deadLetterTaskMapper.selectById(tenantId, deadLetterTaskId);
    if (deadLetterTask == null) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.dead_letter.not_found");
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
        throw BizException.of(ResultCode.STATE_CONFLICT, "error.dead_letter.not_replayable");
      }
      if (deadLetterTaskMapper.markReplaying(
              tenantId,
              deadLetterTaskId,
              deadLetterTask.getReplayStatus(),
              DeadLetterReplayStatus.REPLAYING.code())
          <= 0) {
        throw BizException.of(ResultCode.CONFLICT, "error.dead_letter.replay_conflict");
      }
      Instant replayAt = BatchDateTimeSupport.utcNow();
      int replayCount = Optional.ofNullable(deadLetterTask.getReplayCount()).orElse(0) + 1;
      // 业务重排队失败只撤销此保存点之后的复位/Outbox，失败次数和原因仍在本事务提交。
      Object savepoint = status.createSavepoint();
      try {
        if (!"JOB_PARTITION".equals(deadLetterTask.getSourceType())) {
          throw BizException.of(
              ResultCode.INVALID_ARGUMENT,
              "error.dead_letter.unsupported_source_type",
              deadLetterTask.getSourceType());
        }
        if (externalActionVerifiedNotExecuted) {
          retryRequeueCoordinator.requeuePartitionAfterVerifiedUnknownOutcome(
              tenantId,
              deadLetterTask.getSourceId(),
              deadLetterTask.getPayloadRef(),
              tenantId + ":dead-letter:" + deadLetterTaskId);
        } else {
          retryRequeueCoordinator.requeuePartition(
              tenantId,
              deadLetterTask.getSourceId(),
              tenantId + ":dead-letter:" + deadLetterTaskId);
        }
        DeadLetterTaskMarkReplaySuccessParam deadLetterTaskMarkReplaySuccessParam =
            DeadLetterTaskMarkReplaySuccessParam.builder()
                .tenantId(tenantId)
                .id(deadLetterTaskId)
                .successStatus(DeadLetterReplayStatus.SUCCESS.code())
                .replayCount(replayCount)
                .lastReplayAt(replayAt)
                .lastReplayResult("REPLAY_ACCEPTED")
                .build();
        deadLetterTaskMapper.markReplaySuccess(deadLetterTaskMarkReplaySuccessParam);
        status.releaseSavepoint(savepoint);
        return null;
      } catch (RuntimeException exception) {
        status.rollbackToSavepoint(savepoint);
        status.releaseSavepoint(savepoint);
        if (exception instanceof RetryRequeueCoordinator.ExecutionPausedException) {
          // 连 REPLAYING 的认领也回滚，暂停恢复后保持原重放身份和预算。
          throw exception;
        }
        log.info(
            "dead letter replay rejected or failed: deadLetterId={}, reason={}",
            deadLetterTaskId,
            SwallowedExceptionLogger.summary(exception));
        if (isOrphanPartitionReplayFailure(exception)) {
          deadLetterTaskMapper.markGiveUp(tenantId, deadLetterTaskId);
          log.warn(
              "dead letter give up (partition or job_instance row missing): tenantId={},"
                  + " deadLetterId={}, sourcePartitionId={}",
              tenantId,
              deadLetterTaskId,
              deadLetterTask.getSourceId());
          return new DeadLetterOrphanSourceException(
              "dead letter source missing for replay: partition or job_instance deleted");
        }
        if (isClosedExecutionReplayFailure(exception)) {
          deadLetterTaskMapper.markGiveUp(tenantId, deadLetterTaskId);
          return exception;
        }
        // 已经显式登记的重放失败按原预算记账；不会创建新的自动预算。
        Instant nextAuto = shouldScheduleAutoRetry(deadLetterTask, replayCount)
            ? calculateNextRetryAt(RetryPolicyType.EXPONENTIAL.code(), replayCount)
            : null;
        DeadLetterTaskMarkReplayFailureParam deadLetterTaskMarkReplayFailureParam =
            DeadLetterTaskMarkReplayFailureParam.builder()
                .tenantId(tenantId)
                .id(deadLetterTaskId)
                .targetStatus(DeadLetterReplayStatus.FAILED.code())
                .replayCount(replayCount)
                .lastReplayAt(replayAt)
                .lastReplayResult(truncateReplayResultSummary(exception))
                .nextReplayAt(nextAuto)
                .build();
        deadLetterTaskMapper.markReplayFailure(deadLetterTaskMarkReplayFailureParam);
        return exception;
      }
    } finally {
      if (injectMdc) {
        BatchMdc.remove(StructuredLogField.JOB_INSTANCE_ID);
        BatchMdc.remove(StructuredLogField.TRACE_ID);
        BatchMdc.remove(StructuredLogField.TENANT_ID);
      }
    }
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

  private RetryPolicyPlan resolveRetryPolicy(JobInstanceEntity jobInstance) {
    Map<String, Object> snapshot = executionPolicySnapshot(jobInstance);
    if (EmptyChecks.isNotNull(snapshot)) {
      String retryPolicy = stringValue(snapshot.get("retryPolicy"));
      Integer retryMaxCount = integerValue(snapshot.get("retryMaxCount"));
      String resolvedRetryPolicy =
          Texts.hasText(retryPolicy) ? retryPolicy : RetryPolicyType.FIXED.code();
      Integer resolvedRetryMaxCount = EmptyChecks.isNull(retryMaxCount)
          ? governance.retry().getDefaultMaxRetryCount()
          : retryMaxCount;
      return new RetryPolicyPlan(resolvedRetryPolicy, resolvedRetryMaxCount);
    }
    return resolveRetryPolicy(jobInstance.getJobDefinitionId());
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> executionPolicySnapshot(JobInstanceEntity jobInstance) {
    if (EmptyChecks.isNull(jobInstance) || !Texts.hasText(jobInstance.getParamsSnapshot())) {
      return null;
    }
    try {
      Object parsed = JsonUtils.fromJson(jobInstance.getParamsSnapshot(), Object.class);
      if (!(parsed instanceof Map<?, ?> instanceSnapshot)) {
        return null;
      }
      Object policy = instanceSnapshot.get("executionPolicy");
      return policy instanceof Map<?, ?> policyMap ? (Map<String, Object>) policyMap : null;
    } catch (IllegalArgumentException malformedSnapshot) {
      log.warn(
          "job instance execution policy snapshot parse failed, using legacy definition:"
              + " tenantId={} instanceId={}",
          jobInstance.getTenantId(),
          jobInstance.getId(),
          malformedSnapshot);
      return null;
    }
  }

  private String stringValue(Object value) {
    return EmptyChecks.isNull(value) ? null : String.valueOf(value);
  }

  private Integer integerValue(Object value) {
    if (value instanceof Number number) {
      return number.intValue();
    }
    if (value instanceof String text && EmptyChecks.isNotEmpty(text)) {
      try {
        return Integer.valueOf(text);
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
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
    delaySeconds = applyJitter(delaySeconds);
    return BatchDateTimeSupport.utcNow().plusSeconds(delaySeconds);
  }

  /**
   * 在 backoff 上叠加 ±jitterRatio 的随机偏移，打散 thundering herd。
   *
   * <p>jitterRatio=0（默认）时直接返回原值，行为兼容现有逻辑。详见 {@code RetryGovernanceProperties.jitterRatio}。
   */
  private long applyJitter(long delaySeconds) {
    double jitterRatio = governance.retry().getJitterRatio();
    if (jitterRatio <= 0.0 || delaySeconds <= 0L) {
      return delaySeconds;
    }
    double range = delaySeconds * Math.min(jitterRatio, 1.0);
    double offset = (ThreadLocalRandom.current().nextDouble() * 2.0 - 1.0) * range;
    return Math.max(1L, delaySeconds + (long) offset);
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
    String deadLetterReason = buildDeadLetterReason(errorCode, errorMessage);
    deadLetterTask.setDeadLetterReason(deadLetterReason);
    deadLetterTask.setPayloadRef(jobInstance.getInstanceNo() + ":" + task.getId());
    deadLetterTask.setReplayStatus(DeadLetterReplayStatus.NEW.code());
    deadLetterTask.setReplayCount(0);
    deadLetterTask.setTraceId(jobInstance.getTraceId());
    // V90: 错误分类 + 自动重放预算
    DeadLetterErrorClass errorClass = classifyErrorClass(errorCode);
    deadLetterTask.setErrorClass(errorClass.code());
    // 自动执行预算只属于实例冻结的 retry policy。死信不能再次授予预算，否则每轮失败
    // 创建新死信会把 replay_count 归零，终态 Job/Workflow 的子任务可被无限复活。
    deadLetterTask.setMaxReplayCount(0);
    deadLetterTask.setNextReplayAt(null);
    deadLetterTaskMapper.insert(deadLetterTask);
    logDeadLetterAfterCommit(deadLetterTask, jobInstance);
  }

  private void logDeadLetterAfterCommit(
      DeadLetterTaskEntity deadLetter, JobInstanceEntity instance) {
    Runnable diagnostic = () -> log.warn(
        "dead letter created: tenantId={}, partitionId={}, instanceNo={}, errorClass={}",
        deadLetter.getTenantId(),
        deadLetter.getSourceId(),
        instance.getInstanceNo(),
        deadLetter.getErrorClass());
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          diagnostic.run();
        }
      });
    } else {
      diagnostic.run();
    }
  }

  /**
   * V90: 错误代码分类。{@link #NON_RETRYABLE_ERROR_CODES} 内的错误是 BUSINESS（硬错，自动重放不会自愈）， 其他归
   * SYSTEM（瞬态/可恢复，自动重放 走指数退避）。
   */
  private static DeadLetterErrorClass classifyErrorClass(String errorCode) {
    if (TaskErrorCodes.EXTERNAL_OUTCOME_UNKNOWN.equals(errorCode)) {
      // 该技术结果仍归 SYSTEM;自动任务重试已在入口单独阻断,死信不授予自动重放预算。
      return DeadLetterErrorClass.SYSTEM;
    }
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
    // dead_letter_task.dead_letter_reason 是 varchar(1024)。code 通常 ≤64 char,
    // 给 message 留 ~950 字节安全余量;超长一律截断+"…",避免 INSERT 失败把整个 retry
    // → DLQ 链路撑死。
    String reason = code + ": " + message;
    return truncateErrorMessage(reason, 1023);
  }

  /**
   * 限制错误消息长度,防止超长 worker 异常 stack / SQL 报错把 varchar(1024) 列超过, 进而把整个 retry insert 事务回滚成"无限循环"。多余字节用
   * "…" 单字符标记。
   */
  private static String truncateErrorMessage(String message, int maxLength) {
    if (message == null) {
      return null;
    }
    if (message.length() <= maxLength) {
      return message;
    }
    return message.substring(0, maxLength - 1) + "…";
  }

  private void replayDeadLetterInNewTransaction(String tenantId, Long deadLetterTaskId) {
    replayDeadLetterInNewTransaction(tenantId, deadLetterTaskId, false);
  }

  private void replayDeadLetterInNewTransaction(
      String tenantId, Long deadLetterTaskId, boolean externalActionVerifiedNotExecuted) {
    RuntimeException failure = newTransaction()
        .execute(status -> replayDeadLetterWithinTransaction(
            tenantId, deadLetterTaskId, status, externalActionVerifiedNotExecuted));
    if (EmptyChecks.isNotNull(failure)) {
      throw failure;
    }
  }

  private void inNewTransaction(Runnable action) {
    newTransaction().executeWithoutResult(status -> action.run());
  }

  private TransactionTemplate newTransaction() {
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return transaction;
  }

  private static String truncateReplayResultSummary(Throwable exception) {
    if (exception == null) {
      return "";
    }
    String raw = exception.getMessage();
    if (raw == null || raw.isBlank()) {
      raw = exception.getClass().getSimpleName();
    }
    final int max = 512;
    return raw.length() <= max ? raw : raw.substring(0, max);
  }

  /** 分区或作业实例已被清理（如脚本 sweep）但死信行仍在时,requeue 会失败；不应占用自动重放预算反复打 WARN。 */
  private static boolean isClosedExecutionReplayFailure(Throwable failure) {
    return failure instanceof BizException businessFailure
        && "error.partition.retry_execution_closed".equals(businessFailure.getMessageKey());
  }

  private static boolean isOrphanPartitionReplayFailure(Throwable exception) {
    // requeuePartition 对 partition / job_instance 行缺失抛 BizException(NOT_FOUND),其 getMessage()
    // 返回 i18n key(BizException 以 messageKey 作 super.message)。死信重放时据此判定 orphan → markGiveUp。
    if (!(exception instanceof BizException)) {
      return false;
    }
    String messageKey = exception.getMessage();
    return "error.partition.retry_not_found".equals(messageKey)
        || "error.partition.retry_instance_not_found".equals(messageKey);
  }

  private record RetryPolicyPlan(String retryPolicy, int maxRetryCount) {}
}

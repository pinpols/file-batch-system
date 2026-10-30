package io.github.pinpols.batch.orchestrator.application.service.governance;

import io.github.pinpols.batch.common.context.RunModeSupport;
import io.github.pinpols.batch.common.dto.LaunchEnvelope;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.CompensationCommandStatus;
import io.github.pinpols.batch.common.enums.DeadLetterReplayStatus;
import io.github.pinpols.batch.common.enums.ReplayOperationStatus;
import io.github.pinpols.batch.common.enums.ReplayOperationType;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.RunMode;
import io.github.pinpols.batch.common.enums.TaskErrorCodes;
import io.github.pinpols.batch.common.enums.TaskStatus;
import io.github.pinpols.batch.common.enums.TriggerRequestStatus;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.event.DomainEvent;
import io.github.pinpols.batch.common.event.DomainEventPublisher;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.persistence.entity.TriggerRequestEntity;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Guard;
import io.github.pinpols.batch.common.utils.IdGenerator;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.application.engine.OutboxEventKeyGenerator;
import io.github.pinpols.batch.orchestrator.application.service.task.OrchestratorJobMappers;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskAssignmentService;
import io.github.pinpols.batch.orchestrator.domain.command.CompensationSubmitCommand;
import io.github.pinpols.batch.orchestrator.domain.command.FileGovernanceCommand;
import io.github.pinpols.batch.orchestrator.domain.entity.ApprovalCommandEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.CompensationCommandEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.DeadLetterTaskEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobDefinitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobExecutionLogEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobStepInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.ReplayOperationEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowDefinitionEntity;
import io.github.pinpols.batch.orchestrator.domain.param.DeadLetterTaskMarkReplaySuccessParam;
import io.github.pinpols.batch.orchestrator.domain.param.ReplayOperationStartParam;
import io.github.pinpols.batch.orchestrator.domain.param.UpdateCompensationStatusParam;
import io.github.pinpols.batch.orchestrator.domain.query.JobTaskQuery;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import io.github.pinpols.batch.orchestrator.mapper.ApprovalCommandMapper;
import io.github.pinpols.batch.orchestrator.mapper.CompensationCommandMapper;
import io.github.pinpols.batch.orchestrator.mapper.DeadLetterTaskMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import io.github.pinpols.batch.orchestrator.mapper.ReplayOperationMapper;
import io.github.pinpols.batch.orchestrator.service.LaunchRequestIdentityGuard;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 补偿指令统一入口：按 {@code compensationType} 路由到 6 种内部操作（JOB / STEP / PARTITION / FILE / BATCH / DLQ），
 * 并把命令生命周期（RUNNING → SUCCESS/FAILED）与补偿日志写入串在同一事务内。
 *
 * <p>关键不变量：
 *
 * <ul>
 *   <li><b>防重双保险</b>：{@link #assertNoRunningConflict} 先查 RUNNING 计数，DB 还有唯一约束回退 （{@code
 *       DataIntegrityViolationException} 转为 {@code CONFLICT}），处理并发提交的 TOCTOU。
 *   <li><b>状态必达</b>：handler 抛异常时必须更新命令状态为 FAILED 并写 {@code job_execution_log}， 然后再
 *       rethrow；缺任何一步会让命令停留在 RUNNING 造成"残留补偿"。
 *   <li><b>路由表在构造期构建</b>：{@link #operationsByType} 是不可变 Map，避免分派时 if-chain 并保证 O(1)。
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultCompensationService implements CompensationService {

  // ── duplicate literal constants ─────────────────────────────────────────
  private static final String KEY_ACTION = "action";
  private static final String PARAM_EXTERNAL_OUTCOME_RESOLUTION_TASK_ID =
      "_externalOutcomeResolutionTaskId";
  private static final String PARAM_EXTERNAL_OUTCOME_RESOLUTION_VERSION =
      "_externalOutcomeResolutionVersion";

  private final CompensationCommandMapper compensationCommandMapper;
  private final ApprovalCommandMapper approvalCommandMapper;
  private final DeadLetterTaskMapper deadLetterTaskMapper;
  private final OrchestratorJobMappers jobMappers;
  private final RetryGovernanceService retryGovernanceService;
  private final FileGovernanceService fileGovernanceService;
  private final DomainEventPublisher domainEventPublisher;
  private final OrchestratorConfigCacheService configCacheService;
  private final TaskAssignmentService taskAssignmentService;
  private final CompensationTransactionExecutor transactionExecutor;
  private final RelatedFileReferenceValidator relatedFileReferenceValidator;
  private final WorkflowNodeReplayTargetResolver workflowNodeReplayTargetResolver;
  private final ReplayOperationMapper replayOperationMapper;

  /** 本类内部的路由函数，不对外伪装成可插拔 SPI；类型新增仍由统一事务入口维护。 */
  @FunctionalInterface
  private interface CompensationOperation {
    Map<String, Object> execute(
        CompensationSubmitCommand command,
        String commandNo,
        String traceId,
        CompensationCommandEntity entity);
  }

  /** 允许返回 null 的 ID 解析器；null 表示当前补偿类型没有可用于冲突判断的目标 ID。 */
  @FunctionalInterface
  private interface NullableLongResolver {
    Long apply(CompensationSubmitCommand command);
  }

  /** 路由表：compensationType → operation。构造时一次性构建；O(1) 查找。 */
  private final Map<String, CompensationOperation> operationsByType = Map.of(
      "JOB", this::rerunJob,
      "WORKFLOW_NODE", this::rerunWorkflowNode,
      "STEP", this::rerunStep,
      "PARTITION", this::retryPartition,
      "FILE", (cmd, cmdNo, traceId, entity) -> reprocessFile(cmd, traceId, entity),
      "BATCH", this::rerunBatch,
      "DLQ", this::replayDeadLetter);

  private record CompensationLaunchTarget(
      String tenantId, String jobCode, LocalDate bizDate, TriggerType triggerType) {}

  @Builder
  private record CompensationLaunchRequest(
      CompensationLaunchTarget target,
      Map<String, Object> params,
      String traceId,
      String commandNo,
      Long replaySessionId,
      Long replayEntryId,
      Boolean dryRun) {}

  /**
   * 提交补偿指令:写入数据库 command → 路由到 handler 执行 → 统一回写 SUCCESS/FAILED 状态与日志。
   *
   * <p><b>事务模型(2026-05-20 review 修正)</b>:之前用 {@code @Transactional(noRollbackFor =
   * Exception.class)} 让 handler 抛异常时整个外层事务**提交**(包括 handler
   * 已经做过的任务/分片/重放业务写入),导致**业务半截副作用泄漏到生产数据**。 修正后:
   *
   * <ul>
   *   <li>本 submit() 方法**不再有外层 @Transactional**(纯调度方法)
   *   <li>INSERT 命令行走显式 REQUIRES_NEW 模板独立提交 — handler 失败也留住命令行
   *   <li>handler 执行 + SUCCESS 状态/日志走默认 REQUIRED 模板 —
   *       handler 抛错就**回滚业务写入**,不留下无效记录
   *   <li>FAILED 状态/日志走 REQUIRES_NEW 模板；若参与外层事务则在回滚完成后写入，避免等待自身持有的命令锁。
   * </ul>
   *
   * <p>权衡:理论上 INSERT 后 / 业务事务成功提交前 JVM 崩溃,命令会卡 RUNNING。这通过 ops backlog 的
   * stale-RUNNING reconciler 回退(范围外,不在本方法处理)。
   */
  @Override
  public String submit(CompensationSubmitCommand command) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        && !TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("active transaction requires transaction synchronization");
    }
    // 2026-05-01 hardening: pre-insert 阶段(validate / 冲突检查 / insert 唯一约束)失败时
    // 也要落审计 trail。原实现只在 insert 成功后才 log,导致"提交即拒"的请求丢线索。
    String commandNo = null;
    String traceId = null;
    String requestFingerprint = null;
    try {
      validate(command);
      requestFingerprint = fingerprintApprovedRequest(command);
      CompensationCommandEntity priorCommand = findPriorApprovedCommand(command);
      if (EmptyChecks.isNotNull(priorCommand)) {
        return resolvePriorApprovedCommand(priorCommand, requestFingerprint);
      }
      commandNo = IdGenerator.newBusinessNo("cmp");
      String normalizedType = normalizeType(command.compensationType());
      String resolvedTraceId = resolveTraceIdFromTarget(command, normalizedType);
      traceId = Texts.hasText(resolvedTraceId)
          ? resolvedTraceId
          : (Texts.hasText(command.traceId()) ? command.traceId() : IdGenerator.newTraceId());
      assertNoRunningConflict(command);
    } catch (RuntimeException pre) {
      appendPreInsertFailureLogInNewTx(command, traceId, commandNo, pre);
      throw pre;
    }
    CompensationCommandEntity entity = buildCommandEntity(command, commandNo, traceId);
    entity.setRequestFingerprint(requestFingerprint);
    String resolvedCommandNo = commandNo;
    String resolvedTraceId = traceId;
    try {
      transactionExecutor.requiresNew(() -> compensationCommandMapper.insert(entity));
    } catch (DataIntegrityViolationException ex) {
      CompensationCommandEntity concurrentCommand = findPriorApprovedCommand(command);
      if (EmptyChecks.isNotNull(concurrentCommand)) {
        return resolvePriorApprovedCommand(concurrentCommand, requestFingerprint);
      }
      BizException wrapped =
          BizException.of(ResultCode.CONFLICT, "error.compensation.already_running", ex);
      appendPreInsertFailureLogInNewTx(command, traceId, commandNo, wrapped);
      throw wrapped;
    }
    boolean executed;
    try {
      executed = transactionExecutor.required(() -> {
        Long locked = compensationCommandMapper.lockRunningCommand(
            command.tenantId(), entity.getId(), CompensationCommandStatus.RUNNING.code());
        if (EmptyChecks.isNull(locked)) {
          return false;
        }
        executeAndMarkSuccess(command, resolvedCommandNo, resolvedTraceId, entity);
        return true;
      });
    } catch (Exception exception) {
      // 若 REQUIRED 参与调用方事务，命令行锁要等调用方事务回滚后才释放。
      recordFailureAfterTransaction(command, resolvedTraceId, entity, exception);
      throw exception;
    }
    if (!executed) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.compensation.command_not_running");
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      recordFailureAfterTransaction(
          command,
          resolvedTraceId,
          entity,
          new IllegalStateException("caller transaction rolled back after compensation"));
    }
    return resolvedCommandNo;
  }

  /** 参与外层事务时，等该事务释放命令行锁后才能以独立事务记录回滚。 */
  private void recordFailureAfterTransaction(
      CompensationSubmitCommand command,
      String traceId,
      CompensationCommandEntity entity,
      Exception exception) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      transactionExecutor.requiresNew(() -> markFailedAndLog(command, traceId, entity, exception));
      return;
    }
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("cannot register compensation rollback tracking", exception);
    }
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override
      public void afterCompletion(int status) {
        if (status == STATUS_ROLLED_BACK) {
          // 异常由事务同步器记录；不得在已完成的事务中吞掉失败审计异常。
          transactionExecutor.requiresNew(
              () -> markFailedAndLog(command, traceId, entity, exception));
        } else if (status == STATUS_UNKNOWN) {
          log.error(
              "compensation transaction outcome unknown: tenantId={}, commandNo={}",
              command.tenantId(),
              entity.getCommandNo());
        }
      }
    });
  }

  @Override
  @Transactional
  public void rejectWorkflowNodeReplayApproval(String tenantId, Long replayOperationId) {
    replayOperationMapper.setTenantContext(tenantId);
    ReplayOperationEntity operation =
        replayOperationMapper.selectForUpdate(tenantId, replayOperationId);
    if (EmptyChecks.isNull(operation)
        || !ReplayOperationType.WORKFLOW_NODE.code().equals(operation.operationType())) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.workflow.replay_operation_not_found");
    }
    if (ReplayOperationStatus.REJECTED.code().equals(operation.status())) {
      return;
    }
    if (!ReplayOperationStatus.PENDING_APPROVAL.code().equals(operation.status())) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT, "error.workflow.replay_approval_state_invalid");
    }
    Instant now = BatchDateTimeSupport.utcNow();
    if (replayOperationMapper.rejectPendingApproval(tenantId, replayOperationId, now) != 1) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT, "error.workflow.replay_approval_state_invalid");
    }
  }

  private CompensationCommandEntity findPriorApprovedCommand(CompensationSubmitCommand command) {
    if (!Texts.hasText(command.approvalId())) {
      return null;
    }
    return compensationCommandMapper.selectByTenantAndApprovalId(
        command.tenantId(), command.approvalId());
  }

  private String resolvePriorApprovedCommand(
      CompensationCommandEntity priorCommand, String requestFingerprint) {
    if (!requestFingerprint.equals(priorCommand.getRequestFingerprint())) {
      throw BizException.of(ResultCode.CONFLICT, "error.compensation.approval_request_mismatch");
    }
    if (CompensationCommandStatus.SUCCESS.code().equals(priorCommand.getCommandStatus())) {
      return priorCommand.getCommandNo();
    }
    throw BizException.of(ResultCode.CONFLICT, "error.compensation.approval_already_submitted");
  }

  private String fingerprintApprovedRequest(CompensationSubmitCommand command) {
    if (!Texts.hasText(command.approvalId())) {
      return null;
    }
    Map<String, Object> payload = JsonUtils.toMap(command);
    payload.remove("traceId");
    payload.remove("operatorId");
    try {
      MessageDigest sha256Digest = MessageDigest.getInstance("SHA-256");
      Object canonicalPayload = canonicalizePayload(payload);
      String canonicalJson = JsonUtils.toJson(canonicalPayload);
      byte[] canonicalPayloadBytes = canonicalJson.getBytes(StandardCharsets.UTF_8);
      byte[] digest = sha256Digest.digest(canonicalPayloadBytes);
      String fingerprint = HexFormat.of().formatHex(digest);
      return fingerprint;
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(
          "SHA-256 is required for approved compensation identity", exception);
    }
  }

  private Object canonicalizePayload(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> sorted = new TreeMap<>();
      map.forEach((key, entryValue) -> {
        String canonicalKey = String.valueOf(key);
        Object canonicalValue = canonicalizePayload(entryValue);
        sorted.put(canonicalKey, canonicalValue);
      });
      return sorted;
    }
    if (value instanceof Collection<?> collection) {
      return collection.stream().map(this::canonicalizePayload).toList();
    }
    return value;
  }

  /** INSERT command row in REQUIRES_NEW 独立提交;handler 失败也留住命令行(审计 + unblock 后续提交)。 */
  /** Handler 业务写 + SUCCESS 状态/日志,默认 @Transactional:handler 抛错回滚业务写入。 */
  private void executeAndMarkSuccess(
      CompensationSubmitCommand command,
      String commandNo,
      String traceId,
      CompensationCommandEntity entity) {
    Map<String, Object> result = execute(command, commandNo, traceId, entity);
    result.put("hitCount", 1);
    result.put("conflictCount", 0);
    UpdateCompensationStatusParam.UpdateCompensationStatusParamBuilder statusUpdateBuilder =
        UpdateCompensationStatusParam.builder();
    String statusTenantId = command.tenantId();
    statusUpdateBuilder.tenantId(statusTenantId);
    Long statusEntityId = entity.getId();
    statusUpdateBuilder.id(statusEntityId);
    String successStatus = CompensationCommandStatus.SUCCESS.code();
    statusUpdateBuilder.commandStatus(successStatus);
    statusUpdateBuilder.expectedStatus(CompensationCommandStatus.RUNNING.code());
    Long relatedJobInstanceId = entity.getRelatedJobInstanceId();
    statusUpdateBuilder.relatedJobInstanceId(relatedJobInstanceId);
    Long relatedFileId = entity.getRelatedFileId();
    statusUpdateBuilder.relatedFileId(relatedFileId);
    String resultSummary = JsonUtils.toJson(result);
    statusUpdateBuilder.resultSummary(resultSummary);
    statusUpdateBuilder.errorCode(null);
    statusUpdateBuilder.errorMessage(null);
    Instant finishedAt = BatchDateTimeSupport.utcNow();
    statusUpdateBuilder.finishedAt(finishedAt);
    UpdateCompensationStatusParam statusUpdate = statusUpdateBuilder.build();
    if (compensationCommandMapper.updateStatus(statusUpdate) != 1) {
      throw new IllegalStateException("compensation command lost running status");
    }
    CompensationLogContext logContext = new CompensationLogContext(
        command, traceId, entity, CompensationCommandStatus.SUCCESS.code(), result, null);
    appendCompensationLog(logContext);
  }

  /**
   * 业务事务失败后独立 NEW tx 写 FAILED + 日志。外层业务回滚不影响已独立提交的命令行；这里只是把命令状态从 RUNNING
   * 推进到 FAILED + 记录失败日志。
   */
  private void markFailedAndLog(
      CompensationSubmitCommand command,
      String traceId,
      CompensationCommandEntity entity,
      Exception exception) {
    UpdateCompensationStatusParam.UpdateCompensationStatusParamBuilder statusUpdateBuilder =
        UpdateCompensationStatusParam.builder();
    String statusTenantId = command.tenantId();
    statusUpdateBuilder.tenantId(statusTenantId);
    Long statusEntityId = entity.getId();
    statusUpdateBuilder.id(statusEntityId);
    String failedStatus = CompensationCommandStatus.FAILED.code();
    statusUpdateBuilder.commandStatus(failedStatus);
    statusUpdateBuilder.expectedStatus(CompensationCommandStatus.RUNNING.code());
    Long relatedJobInstanceId = entity.getRelatedJobInstanceId();
    statusUpdateBuilder.relatedJobInstanceId(relatedJobInstanceId);
    Long relatedFileId = entity.getRelatedFileId();
    statusUpdateBuilder.relatedFileId(relatedFileId);
    statusUpdateBuilder.resultSummary(null);
    String errorCode = resolveErrorCode(exception);
    statusUpdateBuilder.errorCode(errorCode);
    String errorMessage = exception.getMessage();
    statusUpdateBuilder.errorMessage(errorMessage);
    Instant finishedAt = BatchDateTimeSupport.utcNow();
    statusUpdateBuilder.finishedAt(finishedAt);
    UpdateCompensationStatusParam statusUpdate = statusUpdateBuilder.build();
    int updated = compensationCommandMapper.updateStatus(statusUpdate);
    if (updated == 0 && EmptyChecks.isNotNull(relatedJobInstanceId)) {
      // 作业可能在业务事务回滚后已归档；保留原目标信息，失败审计不再引用热作业。
      statusUpdateBuilder.relatedJobInstanceId(null);
      updated = compensationCommandMapper.updateStatus(statusUpdateBuilder.build());
      if (updated == 1) {
        entity.setRelatedJobInstanceId(null);
      }
    }
    if (updated != 1) {
      return;
    }
    CompensationLogContext logContext = new CompensationLogContext(
        command, traceId, entity, CompensationCommandStatus.FAILED.code(), null, exception);
    appendCompensationLog(logContext);
  }

  /**
   * R7-A5-P2 / docs/agent-baseline.md §分支消除：把 JOB/STEP/PARTITION 三种 target 类型抽成 Map 路由表， 每种类型只描述"如何拿到归属
   * jobInstanceId"，公共的"按 jobInstanceId → JobInstanceEntity → traceId"模板共用。 通过方法返回（而非 field
   * initializer），避免与 {@code @RequiredArgsConstructor} 注入字段 {@code jobMappers} 的初始化顺序冲突。
   */
  private Map<String, Function<CompensationSubmitCommand, Long>> jobInstanceIdResolvers() {
    return Map.of(
        "JOB", cmd -> cmd.targetId(),
        "STEP",
            cmd -> {
              JobStepInstanceEntity step =
                  jobMappers.jobStepInstanceMapper.selectById(cmd.tenantId(), cmd.targetId());
              return step == null ? null : step.getJobInstanceId();
            },
        "PARTITION",
            cmd -> {
              JobPartitionEntity partition =
                  jobMappers.jobPartitionMapper.selectById(cmd.tenantId(), cmd.targetId());
              return partition == null ? null : partition.getJobInstanceId();
            });
  }

  private Map<String, NullableLongResolver> conflictTargetResolvers() {
    return Map.of(
        "JOB", CompensationSubmitCommand::targetId,
        "WORKFLOW_NODE", CompensationSubmitCommand::targetId,
        "STEP", CompensationSubmitCommand::targetId,
        "PARTITION", CompensationSubmitCommand::targetId,
        "DLQ", CompensationSubmitCommand::targetId,
        "FILE", cmd -> firstNonNull(cmd.relatedFileId(), cmd.targetId()));
  }

  private String resolveTraceIdFromTarget(
      CompensationSubmitCommand command, String normalizedType) {
    if (!Texts.hasText(command.tenantId()) || command.targetId() == null) {
      return null;
    }
    Function<CompensationSubmitCommand, Long> resolver =
        jobInstanceIdResolvers().get(normalizedType);
    if (resolver == null) {
      return null;
    }
    Long jobInstanceId = resolver.apply(command);
    if (jobInstanceId == null) {
      return null;
    }
    JobInstanceEntity inst =
        jobMappers.jobInstanceMapper.selectById(command.tenantId(), jobInstanceId);
    return inst == null ? null : inst.getTraceId();
  }

  private Map<String, Object> execute(
      CompensationSubmitCommand command,
      String commandNo,
      String traceId,
      CompensationCommandEntity entity) {
    String compensationType = normalizeType(command.compensationType());
    CompensationOperation operation = operationsByType.get(compensationType);
    if (EmptyChecks.isNull(operation)) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          ResultCode.INVALID_ARGUMENT.detailKey(),
          "unsupported compensationType: " + command.compensationType());
    }
    return operation.execute(command, commandNo, traceId, entity);
  }

  private Map<String, Object> rerunJob(
      CompensationSubmitCommand command,
      String commandNo,
      String traceId,
      CompensationCommandEntity entity) {
    JobInstanceEntity sourceInstance = resolveJobInstance(command);
    boolean sourceHasUnknownExternalOutcome =
        jobMappers.jobTaskMapper.existsUnknownExternalOutcomeForJobInstance(
            command.tenantId(), sourceInstance.getId());
    requireNoUnknownExternalOutcome(sourceHasUnknownExternalOutcome);
    entity.setRelatedJobInstanceId(sourceInstance.getId());
    String sourceParamsSnapshot = sourceInstance.getParamsSnapshot();
    Map<String, Object> params = extractEffectiveParams(sourceParamsSnapshot);
    copyExternalOutcomeResolutionIdentity(params, command.launchParams());
    RunModeSupport.putCanonical(params, RunMode.RERUN);
    params.put("operationType", "JOB_RERUN");
    params.put("parentInstanceId", sourceInstance.getId());
    params.put("parentInstanceNo", sourceInstance.getInstanceNo());
    params.put("operatorId", command.operatorId());
    params.put("approvalId", command.approvalId());
    params.put("strategy", command.strategy());
    String rerunBatchNo = firstText(command.batchNo(), sourceInstance.getBatchNo());
    params.put("batchNo", rerunBatchNo);
    Long rerunRelatedFileId =
        firstNonNull(command.relatedFileId(), sourceInstance.getRelatedFileId());
    params.put("relatedFileId", rerunRelatedFileId);
    params.put("rerunFlag", true);
    params.put("retryFlag", false);
    params.put("reason", command.reason());
    if (EmptyChecks.isNotNull(command.sourceWorkflowRunId())
        && EmptyChecks.isNotNull(command.sourceWorkflowNodeRunId())) {
      params.put("sourceWorkflowRunId", command.sourceWorkflowRunId());
      params.put("sourceWorkflowNodeRunId", command.sourceWorkflowNodeRunId());
      params.put("sourceJobInstanceId", sourceInstance.getId());
    }
    applyRerunPolicyParams(params, command);
    CompensationLaunchTarget target = new CompensationLaunchTarget(
        command.tenantId(),
        sourceInstance.getJobCode(),
        sourceInstance.getBizDate(),
        TriggerType.CATCH_UP);
    CompensationLaunchRequest request = CompensationLaunchRequest.builder()
        .target(target)
        .params(params)
        .traceId(traceId)
        .commandNo(commandNo)
        .replaySessionId(command.replaySessionId())
        .replayEntryId(command.replayEntryId())
        .dryRun(command.dryRun())
        .build();
    CompensationLaunchResult launch = launchCompensation(request);
    JobInstanceEntity launched = launch.job();
    entity.setRelatedJobInstanceId(EmptyChecks.isNull(launched) ? null : launched.getId());
    Map<String, Object> result = new LinkedHashMap<>();
    result.put(KEY_ACTION, "JOB_RERUN");
    result.put("sourceInstanceNo", sourceInstance.getInstanceNo());
    result.put("launchRequestId", launch.requestId());
    result.put("newInstanceNo", EmptyChecks.isNull(launched) ? null : launched.getInstanceNo());
    result.put("newTraceId", launch.traceId());
    result.put("targetJobInstanceId", launched == null ? null : launched.getId());
    return result;
  }

  private Map<String, Object> rerunWorkflowNode(
      CompensationSubmitCommand command,
      String commandNo,
      String traceId,
      CompensationCommandEntity entity) {
    WorkflowNodeReplayTargetResolver.WorkflowNodeReplayTarget source =
        workflowNodeReplayTargetResolver.resolve(
            command.tenantId(), command.sourceWorkflowRunId(), command.targetId());
    JobInstanceEntity sourceInstance = source.sourceJobInstance();
    entity.setRelatedJobInstanceId(sourceInstance.getId());

    CompensationSubmitCommand jobRerun = CompensationSubmitCommand.builder()
        .tenantId(command.tenantId())
        .compensationType("JOB")
        .targetId(sourceInstance.getId())
        .jobCode(sourceInstance.getJobCode())
        .bizDate(source.sourceRun().getBizDate())
        .batchNo(sourceInstance.getBatchNo())
        .relatedFileId(sourceInstance.getRelatedFileId())
        .channelCode(command.channelCode())
        .reason(command.reason())
        .operatorId(command.operatorId())
        .approvalId(command.approvalId())
        .strategy(command.strategy())
        .traceId(traceId)
        .resultPolicy(command.resultPolicy())
        .configVersionPolicy(command.configVersionPolicy())
        .configVersion(command.configVersion())
        .replaySessionId(command.replaySessionId())
        .replayEntryId(command.replayEntryId())
        .dryRun(false)
        .sourceWorkflowRunId(source.sourceRun().getId())
        .sourceWorkflowNodeRunId(source.sourceNodeRun().getId())
        .build();
    Map<String, Object> result = rerunJob(jobRerun, commandNo, traceId, entity);
    String targetRequestId = String.valueOf(result.get("launchRequestId"));
    if (!Texts.hasText(targetRequestId) || "null".equals(targetRequestId)) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT, "error.workflow.replay_target_request_missing");
    }
    ReplayOperationEntity replayOperation =
        createWorkflowNodeReplayOperation(command, commandNo, traceId, source, targetRequestId);
    result.put("sourceWorkflowRunId", source.sourceRun().getId());
    result.put("sourceWorkflowNodeRunId", source.sourceNodeRun().getId());
    result.put("sourceNodeCode", source.sourceNodeRun().getNodeCode());
    result.put("sourceNodeRunSeq", source.sourceNodeRun().getRunSeq());
    result.put("sourceJobInstanceId", sourceInstance.getId());
    result.put("replayOperationId", replayOperation.id());
    return result;
  }

  private ReplayOperationEntity createWorkflowNodeReplayOperation(
      CompensationSubmitCommand command,
      String commandNo,
      String traceId,
      WorkflowNodeReplayTargetResolver.WorkflowNodeReplayTarget source,
      String targetRequestId) {
    Instant now = BatchDateTimeSupport.utcNow();
    ApprovalCommandEntity approval = findReplayApproval(command);
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("schemaVersion", 1);
    snapshot.put("sourceWorkflowRunId", source.sourceRun().getId());
    snapshot.put("sourceWorkflowNodeRunId", source.sourceNodeRun().getId());
    snapshot.put("sourceNodeCode", source.sourceNodeRun().getNodeCode());
    snapshot.put("sourceNodeRunSeq", source.sourceNodeRun().getRunSeq());
    snapshot.put("sourceJobInstanceId", source.sourceJobInstance().getId());
    snapshot.put("sourceBizDate", source.sourceJobInstance().getBizDate());
    snapshot.put("sourceExecutionSnapshot", source.sourceRun().getExecutionSnapshot());
    snapshot.put("sourceJobParamsSnapshot", source.sourceJobInstance().getParamsSnapshot());
    snapshot.put("resultPolicy", command.resultPolicy());
    snapshot.put("configVersionPolicy", command.configVersionPolicy());
    snapshot.put("configVersion", command.configVersion());
    String snapshotPayload = JsonUtils.toJson(snapshot);
    String requestHash = replayRequestHash(command);
    String snapshotHash = sha256(snapshotPayload);
    ReplayPreviewHashes previewHashes = new ReplayPreviewHashes(requestHash, snapshotHash);
    return startApprovedWorkflowNodeReplay(
        command, targetRequestId, source, previewHashes, approval, now);
  }

  /** 请求与冻结快照必须一起匹配，不能只凭审批状态启动。 */
  private record ReplayPreviewHashes(String requestHash, String snapshotHash) {
    private ReplayPreviewHashes {
      Objects.requireNonNull(requestHash, "requestHash");
      Objects.requireNonNull(snapshotHash, "snapshotHash");
    }
  }

  private ReplayOperationEntity startApprovedWorkflowNodeReplay(
      CompensationSubmitCommand command,
      String targetRequestId,
      WorkflowNodeReplayTargetResolver.WorkflowNodeReplayTarget source,
      ReplayPreviewHashes previewHashes,
      ApprovalCommandEntity approval,
      Instant now) {
    replayOperationMapper.setTenantContext(command.tenantId());
    ReplayOperationEntity pending =
        replayOperationMapper.selectForUpdate(command.tenantId(), command.replayOperationId());
    boolean exactSourceMatches = EmptyChecks.isNotNull(pending)
        && source.sourceRun().getId().equals(pending.sourceWorkflowRunId())
        && source.sourceNodeRun().getId().equals(pending.sourceWorkflowNodeRunId())
        && source.sourceJobInstance().getId().equals(pending.sourceJobInstanceId());
    boolean frozenPreviewMatches = exactSourceMatches
        && previewHashes.requestHash().equals(pending.requestHash())
        && previewHashes.snapshotHash().equals(pending.snapshotHash());
    boolean replayApprovalIsNoLongerValid = !frozenPreviewMatches
        || !ReplayOperationStatus.PENDING_APPROVAL.code().equals(pending.status())
        || EmptyChecks.isNull(approval);
    if (replayApprovalIsNoLongerValid) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.workflow.replay_preview_changed");
    }
    ReplayOperationStartParam startParam = ReplayOperationStartParam.builder()
        .tenantId(command.tenantId())
        .operationId(pending.id())
        .targetRequestId(targetRequestId)
        .approver(approval.getApproverId())
        .now(now)
        .build();
    if (replayOperationMapper.startApprovedWorkflowNodeReplay(startParam) != 1) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT, "error.workflow.replay_approval_state_invalid");
    }
    return replayOperationMapper.selectById(command.tenantId(), pending.id());
  }

  private ApprovalCommandEntity findReplayApproval(CompensationSubmitCommand command) {
    if (!Texts.hasText(command.approvalId())) {
      return null;
    }
    return approvalCommandMapper.selectByTenantAndApprovalNo(
        command.tenantId(), command.approvalId());
  }

  private String replayRequestHash(CompensationSubmitCommand command) {
    Map<String, Object> payload = JsonUtils.toMap(command);
    payload.remove("traceId");
    payload.remove("operatorId");
    payload.remove("approvalId");
    payload.remove("replayOperationId");
    Object canonicalPayload = canonicalizePayload(payload);
    String canonicalPayloadJson = JsonUtils.toJson(canonicalPayload);
    return sha256(canonicalPayloadJson);
  }

  private String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
      byte[] digestBytes = digest.digest(valueBytes);
      return HexFormat.of().formatHex(digestBytes);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private Map<String, Object> rerunStep(
      CompensationSubmitCommand command,
      String commandNo,
      String traceId,
      CompensationCommandEntity entity) {
    Long stepId = command.targetId();
    Guard.require(stepId != null, "step targetId is required");
    JobStepInstanceEntity loadedStepInstance =
        jobMappers.jobStepInstanceMapper.selectById(command.tenantId(), stepId);
    JobStepInstanceEntity stepInstance =
        Guard.requireFound(loadedStepInstance, "job step instance not found");
    entity.setRelatedJobInstanceId(stepInstance.getJobInstanceId());
    Long taskId = stepInstance.getJobTaskId();
    Guard.require(taskId != null, "step jobTaskId is required");
    String tenantId = command.tenantId();
    String compensationEventKey =
        OutboxEventKeyGenerator.forCompensation(tenantId, commandNo, taskId);
    retryGovernanceService.retryTask(tenantId, taskId, compensationEventKey);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put(KEY_ACTION, "STEP_RERUN");
    result.put("stepInstanceId", stepInstance.getId());
    result.put("jobInstanceId", stepInstance.getJobInstanceId());
    JobTaskEntity task = jobMappers.jobTaskMapper.selectById(command.tenantId(), taskId);
    if (task != null) {
      result.put("jobTaskId", task.getId());
      result.put("jobPartitionId", task.getJobPartitionId());
    }
    result.put("traceId", traceId);
    return result;
  }

  private Map<String, Object> retryPartition(
      CompensationSubmitCommand command,
      String commandNo,
      String traceId,
      CompensationCommandEntity entity) {
    Long partitionId = command.targetId();
    if (EmptyChecks.isNull(partitionId)) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.partition.target_id_required");
    }
    String tenantId = command.tenantId();
    String compensationEventKey =
        OutboxEventKeyGenerator.forCompensation(tenantId, commandNo, partitionId);
    retryGovernanceService.retryPartition(tenantId, partitionId, compensationEventKey);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put(KEY_ACTION, "PARTITION_RETRY");
    result.put("partitionId", partitionId);
    result.put("traceId", traceId);
    return result;
  }

  private Map<String, Object> reprocessFile(
      CompensationSubmitCommand command, String traceId, CompensationCommandEntity entity) {
    Long fileId = firstNonNull(command.relatedFileId(), command.targetId());
    Guard.require(fileId != null, "file targetId is required");
    FileGovernanceCommand redispatchCommand = FileGovernanceCommand.builder()
        .tenantId(command.tenantId())
        .fileId(fileId)
        .channelCode(command.channelCode())
        .operatorId(command.operatorId())
        .traceId(traceId)
        .reason(command.reason())
        .approvalId(command.approvalId())
        .build();
    String result = fileGovernanceService.redispatchFile(redispatchCommand);
    entity.setRelatedFileId(fileId);
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put(KEY_ACTION, "FILE_REPROCESS");
    summary.put("fileId", fileId);
    summary.put("channelCode", command.channelCode());
    summary.put("status", result);
    return summary;
  }

  private Map<String, Object> rerunBatch(
      CompensationSubmitCommand command,
      String commandNo,
      String traceId,
      CompensationCommandEntity entity) {
    if (!Texts.hasText(command.jobCode()) || command.bizDate() == null) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.job.batch_rerun_args_required");
    }
    boolean targetHasUnknownExternalOutcome =
        jobMappers.jobTaskMapper.existsUnknownExternalOutcomeForJobBizDate(
            command.tenantId(), command.jobCode(), command.bizDate());
    requireNoUnknownExternalOutcome(targetHasUnknownExternalOutcome);
    Map<String, Object> params = command.launchParams() == null
        ? new LinkedHashMap<>()
        : new LinkedHashMap<>(command.launchParams());
    params.put("operationType", "BATCH_RERUN");
    params.put("operatorId", command.operatorId());
    params.put("approvalId", command.approvalId());
    params.put("strategy", command.strategy());
    params.put("batchNo", command.batchNo());
    params.put("relatedFileId", command.relatedFileId());
    params.put("rerunFlag", true);
    params.put("retryFlag", false);
    params.put("reason", command.reason());
    applyRerunPolicyParams(params, command);
    CompensationLaunchTarget target = new CompensationLaunchTarget(
        command.tenantId(), command.jobCode(), command.bizDate(), TriggerType.CATCH_UP);
    CompensationLaunchRequest request = CompensationLaunchRequest.builder()
        .target(target)
        .params(params)
        .traceId(traceId)
        .commandNo(commandNo)
        .replaySessionId(command.replaySessionId())
        .replayEntryId(command.replayEntryId())
        .dryRun(command.dryRun())
        .build();
    CompensationLaunchResult launch = launchCompensation(request);
    JobInstanceEntity launched = launch.job();
    entity.setRelatedJobInstanceId(launched == null ? null : launched.getId());
    Map<String, Object> result = new LinkedHashMap<>();
    result.put(KEY_ACTION, "BATCH_RERUN");
    result.put("jobCode", command.jobCode());
    result.put("bizDate", command.bizDate());
    result.put("batchNo", command.batchNo());
    result.put("launchRequestId", launch.requestId());
    result.put("newInstanceNo", EmptyChecks.isNull(launched) ? null : launched.getInstanceNo());
    result.put("newTraceId", launch.traceId());
    return result;
  }

  private static void requireNoUnknownExternalOutcome(boolean hasUnknownOutcome) {
    if (hasUnknownOutcome) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT, "error.task.retry_unknown_outcome_requires_resolution");
    }
  }

  private Map<String, Object> replayDeadLetter(
      CompensationSubmitCommand command,
      String commandNo,
      String traceId,
      CompensationCommandEntity entity) {
    if (command.targetId() == null) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.dead_letter.target_id_required");
    }
    if (Boolean.TRUE.equals(command.externalActionVerifiedNotExecuted())
        || Texts.hasText(command.externalOutcomeEvidenceReference())) {
      boolean externalOutcomeResolutionRequestIsIncomplete =
          !Boolean.TRUE.equals(command.externalActionVerifiedNotExecuted())
              || !Texts.hasText(command.externalOutcomeEvidenceReference())
              || !Texts.hasText(command.operatorId())
              || !Texts.hasText(command.reason());
      if (externalOutcomeResolutionRequestIsIncomplete) {
        throw BizException.of(
            ResultCode.INVALID_ARGUMENT, "error.dead_letter.external_outcome_evidence_required");
      }
      retryGovernanceService.auditVerifiedExternalOutcomeResolution(
          command.tenantId(),
          command.targetId(),
          command.operatorId(),
          command.reason(),
          command.externalOutcomeEvidenceReference());
      return replayVerifiedUnknownExternalOutcome(command, commandNo, traceId, entity);
    } else {
      retryGovernanceService.replayDeadLetterInCurrentTransaction(
          command.tenantId(), command.targetId());
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put(KEY_ACTION, "DLQ_REPLAY");
    result.put("deadLetterId", command.targetId());
    result.put("commandNo", commandNo);
    result.put("traceId", traceId);
    return result;
  }

  private Map<String, Object> replayVerifiedUnknownExternalOutcome(
      CompensationSubmitCommand command,
      String commandNo,
      String traceId,
      CompensationCommandEntity entity) {
    String tenantId = command.tenantId();
    Long deadLetterId = command.targetId();
    DeadLetterTaskEntity deadLetter = deadLetterTaskMapper.selectById(tenantId, deadLetterId);
    if (EmptyChecks.isNull(deadLetter)) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.dead_letter.not_found");
    }
    boolean deadLetterCannotReplayJobPartition = !"JOB_PARTITION".equals(deadLetter.getSourceType())
        || (!DeadLetterReplayStatus.NEW.code().equals(deadLetter.getReplayStatus())
            && !DeadLetterReplayStatus.FAILED.code().equals(deadLetter.getReplayStatus()));
    if (deadLetterCannotReplayJobPartition) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.dead_letter.not_replayable");
    }
    JobPartitionEntity partition =
        jobMappers.jobPartitionMapper.selectById(tenantId, deadLetter.getSourceId());
    if (EmptyChecks.isNull(partition)) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.partition.retry_not_found");
    }
    JobInstanceEntity sourceInstance =
        jobMappers.jobInstanceMapper.selectById(tenantId, partition.getJobInstanceId());
    if (EmptyChecks.isNull(sourceInstance)) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.job.instance_not_found");
    }
    JobTaskMapper taskMapper = jobMappers.jobTaskMapper;
    Long sourceJobInstanceId = sourceInstance.getId();
    Long sourcePartitionId = partition.getId();
    JobTaskQuery partitionTaskQuery =
        JobTaskQuery.ofPartition(tenantId, sourceJobInstanceId, sourcePartitionId);
    List<JobTaskEntity> tasks = taskMapper.selectByQuery(partitionTaskQuery);
    List<JobTaskEntity> unknownTasks = tasks.stream()
        .filter(task -> TaskErrorCodes.EXTERNAL_OUTCOME_UNKNOWN.equals(task.getErrorCode()))
        .toList();
    if (unknownTasks.size() != 1) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT,
          "error.dead_letter.external_outcome_resolution_not_applicable");
    }
    JobTaskEntity task = unknownTasks.getFirst();
    String expectedSourceReference = sourceInstance.getInstanceNo() + ":" + task.getId();
    if (!expectedSourceReference.equals(deadLetter.getPayloadRef())) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT, "error.dead_letter.external_outcome_source_mismatch");
    }
    if (!TaskStatus.FAILED.code().equals(task.getTaskStatus())) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT,
          "error.dead_letter.external_outcome_resolution_not_applicable");
    }
    if (deadLetterTaskMapper.markReplaying(
            tenantId,
            deadLetterId,
            deadLetter.getReplayStatus(),
            DeadLetterReplayStatus.REPLAYING.code())
        <= 0) {
      throw BizException.of(ResultCode.CONFLICT, "error.dead_letter.replay_conflict");
    }
    if (jobMappers.jobTaskMapper.markExternalOutcomeResolution(
            tenantId, task.getId(), task.getVersion())
        <= 0) {
      throw BizException.of(ResultCode.CONFLICT, "error.dead_letter.replay_conflict");
    }

    CompensationSubmitCommand rerun = CompensationSubmitCommand.builder()
        .tenantId(tenantId)
        .compensationType("JOB")
        .targetId(sourceInstance.getId())
        .jobCode(sourceInstance.getJobCode())
        .bizDate(sourceInstance.getBizDate())
        .batchNo(sourceInstance.getBatchNo())
        .relatedFileId(sourceInstance.getRelatedFileId())
        .reason(command.reason())
        .operatorId(command.operatorId())
        .approvalId(command.approvalId())
        .strategy(command.strategy())
        .launchParams(externalOutcomeResolutionLaunchParams(
            command.launchParams(), task.getId(), task.getVersion()))
        .externalActionVerifiedNotExecuted(true)
        .externalOutcomeEvidenceReference(command.externalOutcomeEvidenceReference())
        .build();
    Map<String, Object> result = rerunJob(rerun, commandNo, traceId, entity);
    String launchRequestId = (String) result.get("launchRequestId");
    TriggerRequestEntity retryRequest =
        jobMappers.triggerRequestMapper.selectByTenantAndRequestId(tenantId, launchRequestId);
    boolean externalOutcomeResolutionWasNotBound = EmptyChecks.isNull(retryRequest)
        || EmptyChecks.isNull(retryRequest.getId())
        || jobMappers.jobTaskMapper.bindExternalOutcomeResolutionToWorkflowRequest(
                tenantId, task.getId(), task.getVersion(), retryRequest.getId())
            <= 0;
    if (externalOutcomeResolutionWasNotBound) {
      throw BizException.of(ResultCode.CONFLICT, "error.dead_letter.replay_conflict");
    }
    result.put(KEY_ACTION, "DLQ_VERIFIED_UNKNOWN_OUTCOME_NEW_WORKFLOW");
    result.put("deadLetterId", deadLetterId);
    result.put("sourceTaskId", task.getId());
    int replayCount = Optional.ofNullable(deadLetter.getReplayCount()).orElse(0) + 1;
    DeadLetterTaskMarkReplaySuccessParam deadLetterTaskMarkReplaySuccessParam =
        DeadLetterTaskMarkReplaySuccessParam.builder()
            .tenantId(tenantId)
            .id(deadLetterId)
            .successStatus(DeadLetterReplayStatus.SUCCESS.code())
            .replayCount(replayCount)
            .lastReplayAt(BatchDateTimeSupport.utcNow())
            .lastReplayResult("VERIFIED_NOT_EXECUTED_NEW_WORKFLOW")
            .build();
    if (deadLetterTaskMapper.markReplaySuccess(deadLetterTaskMarkReplaySuccessParam) <= 0) {
      throw BizException.of(ResultCode.CONFLICT, "error.dead_letter.replay_conflict");
    }
    return result;
  }

  private record CompensationLaunchResult(
      String requestId, String traceId, JobInstanceEntity job) {}

  private CompensationLaunchResult launchCompensation(CompensationLaunchRequest request) {
    boolean replayEntry = request.replaySessionId() != null && request.replayEntryId() != null;
    JobDefinitionEntity job = configCacheService.findEnabledJobDefinition(
        request.target().tenantId(), request.target().jobCode());
    if (EmptyChecks.isNull(job) || EmptyChecks.isNull(job.id())) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.job.definition_not_found");
    }
    WorkflowDefinitionEntity workflow = configCacheService.findEnabledSingleJobWorkflowDefinition(
        request.target().tenantId(), job.id());
    if (EmptyChecks.isNull(workflow) || EmptyChecks.isNull(workflow.id())) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.workflow.single_job_definition_not_found");
    }
    String requestId = replayEntry
        ? "replay-" + request.replaySessionId() + "-" + request.replayEntryId()
        : IdGenerator.newBusinessNo("req");
    TriggerRequestEntity triggerRequest = new TriggerRequestEntity();
    triggerRequest.setTenantId(request.target().tenantId());
    triggerRequest.setRequestId(requestId);
    triggerRequest.setTriggerType(request.target().triggerType().code());
    triggerRequest.setWorkflowDefinitionId(workflow.id());
    triggerRequest.setBizDate(request.target().bizDate());
    triggerRequest.setDedupKey(
        replayEntry
            ? request.target().tenantId() + ":" + requestId
            : request.target().tenantId() + ":compensation:" + request.commandNo() + ":"
                + requestId);
    triggerRequest.setRequestStatus(TriggerRequestStatus.ACCEPTED.code());
    triggerRequest.setTraceId(request.traceId());
    triggerRequest.setDryRun(Boolean.TRUE.equals(request.dryRun()));
    LaunchRequest launchRequest = LaunchRequest.builder()
        .tenantId(request.target().tenantId())
        .workflowDefinitionId(workflow.id())
        .bizDate(request.target().bizDate())
        .triggerType(request.target().triggerType())
        .requestId(requestId)
        .traceId(request.traceId())
        .params(request.params())
        .replaySessionId(request.replaySessionId())
        .dryRun(Boolean.TRUE.equals(request.dryRun()))
        .build();
    triggerRequest.setLaunchPayload(JsonUtils.toJson(launchRequest));
    triggerRequest.setCreatedAt(BatchDateTimeSupport.utcNow());
    if (replayEntry) jobMappers.triggerRequestMapper.insertIfAbsent(triggerRequest);
    else jobMappers.triggerRequestMapper.insert(triggerRequest);
    TriggerRequestEntity accepted = jobMappers.triggerRequestMapper.selectByTenantAndRequestId(
        request.target().tenantId(), requestId);
    boolean acceptedReplayLaunchIsIncomplete = EmptyChecks.isNull(accepted)
        || EmptyChecks.isNull(accepted.getId())
        || EmptyChecks.isBlank(accepted.getLaunchPayload());
    if (acceptedReplayLaunchIsIncomplete) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.workflow.trigger_target_mismatch");
    }
    LaunchRequest frozen = JsonUtils.fromJson(accepted.getLaunchPayload(), LaunchRequest.class);
    boolean acceptedReplayIdentityDoesNotMatch =
        !LaunchRequestIdentityGuard.matches(frozen, accepted)
            || !workflow.id().equals(frozen.workflowDefinitionId())
            || !request.target().bizDate().equals(frozen.bizDate());
    if (acceptedReplayIdentityDoesNotMatch) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.workflow.trigger_target_mismatch");
    }
    // 请求、冻结命令和 Outbox 在同一事务中提交。现有消费者稍后启动 Workflow。
    DomainEvent.Builder launchEvent = DomainEvent.builder(accepted.getTenantId());
    launchEvent
        .aggregate("TRIGGER_REQUEST", accepted.getId())
        .type("WORKFLOW_LAUNCH")
        .key(accepted.getTenantId() + ":workflow-launch:" + requestId);
    LaunchEnvelope launchEnvelope =
        LaunchEnvelope.of(frozen, accepted.getDedupKey(), accepted.getCreatedAt());
    Map<String, Object> launchPayload = JsonUtils.toMap(launchEnvelope);
    launchEvent.payload(launchPayload).traceId(frozen.traceId());
    domainEventPublisher.publish(launchEvent.build());
    JobInstanceEntity child = jobMappers.jobInstanceMapper.selectCurrentByWorkflowRequest(
        request.target().tenantId(), requestId, request.target().jobCode());
    return new CompensationLaunchResult(requestId, frozen.traceId(), child);
  }

  private CompensationCommandEntity buildCommandEntity(
      CompensationSubmitCommand command, String commandNo, String traceId) {
    CompensationCommandEntity entity = new CompensationCommandEntity();
    entity.setTenantId(command.tenantId());
    entity.setCommandNo(commandNo);
    String normalizedCompensationType = normalizeType(command.compensationType());
    entity.setCompensationType(normalizedCompensationType);
    entity.setTargetId(command.targetId());
    entity.setSourceWorkflowRunId(command.sourceWorkflowRunId());
    entity.setJobCode(command.jobCode());
    entity.setBizDate(command.bizDate());
    entity.setBatchNo(command.batchNo());
    entity.setRelatedFileId(command.relatedFileId());
    entity.setApprovalId(command.approvalId());
    entity.setOperatorId(command.operatorId());
    entity.setReason(command.reason());
    entity.setStrategy(command.strategy());
    entity.setCommandStatus(CompensationCommandStatus.RUNNING.code());
    entity.setTraceId(traceId);
    return entity;
  }

  private JobInstanceEntity resolveJobInstance(CompensationSubmitCommand command) {
    if (command.targetId() != null) {
      JobInstanceEntity entity =
          jobMappers.jobInstanceMapper.selectById(command.tenantId(), command.targetId());
      if (entity != null) {
        return validateRequestedJobInstance(command, entity);
      }
    }
    if (Texts.hasText(command.targetInstanceNo())) {
      JobInstanceEntity entity = jobMappers.jobInstanceMapper.selectByInstanceNo(
          command.tenantId(), command.targetInstanceNo());
      if (entity != null) {
        return validateRequestedJobInstance(command, entity);
      }
    }
    throw BizException.of(ResultCode.NOT_FOUND, "error.job.instance_not_found");
  }

  private JobInstanceEntity validateRequestedJobInstance(
      CompensationSubmitCommand command, JobInstanceEntity instance) {
    boolean jobCodeMismatch =
        Texts.hasText(command.jobCode()) && !command.jobCode().equals(instance.getJobCode());
    boolean bizDateMismatch = EmptyChecks.isNotNull(command.bizDate())
        && !command.bizDate().equals(instance.getBizDate());
    if (jobCodeMismatch || bizDateMismatch) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, "error.compensation.target_instance_mismatch");
    }
    return instance;
  }

  private record CompensationLogContext(
      CompensationSubmitCommand command,
      String traceId,
      CompensationCommandEntity entity,
      String outcome,
      Map<String, Object> result,
      Exception exception) {}

  private void appendCompensationLog(CompensationLogContext ctx) {
    JobExecutionLogEntity log = new JobExecutionLogEntity();
    log.setTenantId(ctx.command().tenantId());
    log.setJobInstanceId(ctx.entity().getRelatedJobInstanceId());
    log.setLogLevel("SUCCESS".equals(ctx.outcome()) ? "INFO" : "ERROR");
    log.setLogType("COMPENSATION");
    log.setTraceId(ctx.traceId());
    log.setMessage(ctx.entity().getCompensationType() + " compensation " + ctx.outcome());
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("commandNo", ctx.entity().getCommandNo());
    detail.put("compensationType", ctx.entity().getCompensationType());
    detail.put("targetId", ctx.entity().getTargetId());
    detail.put("reason", ctx.entity().getReason());
    detail.put("operatorId", ctx.entity().getOperatorId());
    detail.put("approvalId", ctx.entity().getApprovalId());
    detail.put("strategy", ctx.entity().getStrategy());
    if (ctx.result() != null) {
      detail.put("result", ctx.result());
    }
    if (ctx.exception() != null) {
      detail.put("error", ctx.exception().getMessage());
    }
    String extraJson = JsonUtils.toJson(detail);
    log.setExtraJson(extraJson);
    taskAssignmentService.appendLog(log);
  }

  /**
   * 2026-05-01 hardening: pre-insert 阶段(validate / 冲突检查 / insert 唯一约束)失败的审计 trail。
   *
   * <p>正常 happy/fail 路径用 {@link #appendCompensationLog} 需要 entity 已 insert(commandNo +
   * relatedJobInstanceId 已知)。本方法允许 entity 未 insert 时仍 log,关联键退化用 tenant + targetType + targetId。
   *
   * <p>使用 {@code Propagation.REQUIRES_NEW}:外层 {@code @Transactional} 因业务异常即将回滚,审计行必须独立提交才能留下。
   */
  private void appendPreInsertFailureLogInNewTx(
      CompensationSubmitCommand command, String traceId, String commandNo, Exception exception) {
    transactionExecutor.requiresNew(
        () -> appendPreInsertFailureLog(command, traceId, commandNo, exception));
  }

  private void appendPreInsertFailureLog(
      CompensationSubmitCommand command, String traceId, String commandNo, Exception exception) {
    try {
      JobExecutionLogEntity entry = new JobExecutionLogEntity();
      entry.setTenantId(command == null ? null : command.tenantId());
      entry.setJobInstanceId(null); // pre-insert 无 entity → jobInstanceId 未知
      entry.setLogLevel("ERROR");
      entry.setLogType("COMPENSATION_REJECTED");
      entry.setTraceId(traceId);
      String type = command == null ? null : command.compensationType();
      entry.setMessage("compensation submit rejected before insert: type="
          + type
          + ", reason="
          + (exception == null ? null : exception.getMessage()));
      Map<String, Object> detail = new LinkedHashMap<>();
      detail.put("commandNo", commandNo); // 可能 null,看 validate 是否已通过
      detail.put("compensationType", type);
      detail.put("targetId", command == null ? null : command.targetId());
      detail.put("targetInstanceNo", command == null ? null : command.targetInstanceNo());
      detail.put("operatorId", command == null ? null : command.operatorId());
      detail.put("error", exception == null ? null : exception.getMessage());
      detail.put("errorCode", exception == null ? null : resolveErrorCode(exception));
      String extraJson = JsonUtils.toJson(detail);
      entry.setExtraJson(extraJson);
      taskAssignmentService.appendLog(entry);
    } catch (RuntimeException loggingEx) {
      // 落 trail 是 best-effort,不能让审计失败掩盖原始业务异常
      log.warn(
          "failed to write compensation pre-insert failure trail: tenant={}, type={}, error={}",
          command == null ? null : command.tenantId(),
          command == null ? null : command.compensationType(),
          SwallowedExceptionLogger.summary(loggingEx));
    }
  }

  private void assertNoRunningConflict(CompensationSubmitCommand command) {
    Long targetId = resolveConflictTargetId(command);
    if (targetId == null) {
      return;
    }
    String type = normalizeType(command.compensationType());
    int running = compensationCommandMapper.countRunningByTarget(
        command.tenantId(), type, targetId, CompensationCommandStatus.RUNNING.code());
    if (running > 0) {
      throw BizException.of(ResultCode.CONFLICT, "error.compensation.already_running");
    }
  }

  private Long resolveConflictTargetId(CompensationSubmitCommand command) {
    String type = normalizeType(command.compensationType());
    if (EmptyChecks.isNull(type)) {
      return null;
    }
    NullableLongResolver resolver = conflictTargetResolvers().get(type);
    return EmptyChecks.isNull(resolver) ? null : resolver.apply(command);
  }

  private void validate(CompensationSubmitCommand command) {
    Guard.require(EmptyChecks.isNotNull(command), "compensation command is required");
    if (!Texts.hasText(command.tenantId())) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.tenant_id_required");
    }
    if (!Texts.hasText(command.compensationType())) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.compensation.type_required");
    }
    String normalizedCompensationType = normalizeType(command.compensationType());
    boolean workflowNodeReplaySourceIsIncomplete =
        "WORKFLOW_NODE".equals(normalizedCompensationType)
            && (EmptyChecks.isNull(command.targetId())
                || EmptyChecks.isNull(command.sourceWorkflowRunId())
                || EmptyChecks.isNull(command.sourceWorkflowNodeRunId())
                || EmptyChecks.isNull(command.replayOperationId())
                || !command.targetId().equals(command.sourceWorkflowNodeRunId()));
    if (workflowNodeReplaySourceIsIncomplete) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.workflow.replay_source_required");
    }
    boolean workflowNodeReplayApprovalContextIsIncomplete =
        "WORKFLOW_NODE".equals(normalizedCompensationType)
            && (!Texts.hasText(command.operatorId()) || !Texts.hasText(command.reason()));
    if (workflowNodeReplayApprovalContextIsIncomplete) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, "error.workflow.replay_operator_reason_required");
    }
    relatedFileReferenceValidator.validate(command.tenantId(), command.relatedFileId());
  }

  private String normalizeType(String compensationType) {
    if (EmptyChecks.isNull(compensationType)) {
      return null;
    }
    String trimmedType = compensationType.trim();
    return trimmedType.toUpperCase(Locale.ROOT);
  }

  private String resolveErrorCode(Exception exception) {
    return exception instanceof BizException bizException
        ? bizException.getCode().code()
        : ResultCode.SYSTEM_ERROR.code();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> extractEffectiveParams(String paramsSnapshot) {
    if (!Texts.hasText(paramsSnapshot)) {
      return new LinkedHashMap<>();
    }
    Object snapshotObject = JsonUtils.fromJson(paramsSnapshot, Object.class);
    if (!(snapshotObject instanceof Map<?, ?> snapshotMap)) {
      return new LinkedHashMap<>();
    }
    Object effective = snapshotMap.get("effectiveParams");
    if (effective instanceof Map<?, ?> effectiveMap) {
      return new LinkedHashMap<>((Map<String, Object>) effectiveMap);
    }
    return new LinkedHashMap<>();
  }

  private String firstText(String left, String right) {
    return Texts.hasText(left) ? left : right;
  }

  private Long firstNonNull(Long left, Long right) {
    return left != null ? left : right;
  }

  /**
   * 把 RerunRequest 暴露的 §5.5 策略字段透传到 LaunchRequest.params。 DefaultLaunchService 在
   * buildRerunPolicySnapshot 时会读取这些键并写到 job_instance.rerun_policy_snapshot。
   */
  private void applyRerunPolicyParams(
      Map<String, Object> params, CompensationSubmitCommand command) {
    if (Texts.hasText(command.resultPolicy())) {
      params.put("_rerunResultPolicy", command.resultPolicy());
    }
    if (Texts.hasText(command.configVersionPolicy())) {
      params.put("_rerunConfigVersionPolicy", command.configVersionPolicy());
    }
    if (command.configVersion() != null) {
      params.put("_rerunConfigVersion", command.configVersion());
    }
  }

  private Map<String, Object> externalOutcomeResolutionLaunchParams(
      Map<String, Object> suppliedParams, Long sourceTaskId, Long sourceVersion) {
    Map<String, Object> launchParams = new LinkedHashMap<>();
    if (EmptyChecks.isNotNull(suppliedParams)) {
      launchParams.putAll(suppliedParams);
    }
    launchParams.put(PARAM_EXTERNAL_OUTCOME_RESOLUTION_TASK_ID, sourceTaskId);
    launchParams.put(PARAM_EXTERNAL_OUTCOME_RESOLUTION_VERSION, sourceVersion);
    return launchParams;
  }

  private void copyExternalOutcomeResolutionIdentity(
      Map<String, Object> targetParams, Map<String, Object> launchParams) {
    if (EmptyChecks.isNull(launchParams)) {
      return;
    }
    Object sourceTaskId = launchParams.get(PARAM_EXTERNAL_OUTCOME_RESOLUTION_TASK_ID);
    Object sourceVersion = launchParams.get(PARAM_EXTERNAL_OUTCOME_RESOLUTION_VERSION);
    if (EmptyChecks.isNotNull(sourceTaskId) && EmptyChecks.isNotNull(sourceVersion)) {
      targetParams.put(PARAM_EXTERNAL_OUTCOME_RESOLUTION_TASK_ID, sourceTaskId);
      targetParams.put(PARAM_EXTERNAL_OUTCOME_RESOLUTION_VERSION, sourceVersion);
    }
  }
}

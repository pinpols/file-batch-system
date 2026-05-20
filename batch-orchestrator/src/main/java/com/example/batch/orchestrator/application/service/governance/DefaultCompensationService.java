package com.example.batch.orchestrator.application.service.governance;

import com.example.batch.common.constants.BatchStatusConstants;
import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.dto.LaunchResponse;
import com.example.batch.common.enums.CompensationCommandStatus;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.persistence.entity.TriggerRequestEntity;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.common.utils.Guard;
import com.example.batch.common.utils.IdGenerator;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.common.utils.Texts;
import com.example.batch.orchestrator.application.engine.OutboxEventKeyGenerator;
import com.example.batch.orchestrator.application.service.task.OrchestratorJobMappers;
import com.example.batch.orchestrator.application.service.task.TaskExecutionService;
import com.example.batch.orchestrator.domain.command.CompensationSubmitCommand;
import com.example.batch.orchestrator.domain.command.FileGovernanceCommand;
import com.example.batch.orchestrator.domain.entity.CompensationCommandEntity;
import com.example.batch.orchestrator.domain.entity.JobExecutionLogEntity;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import com.example.batch.orchestrator.domain.entity.JobStepInstanceEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.domain.param.UpdateCompensationStatusParam;
import com.example.batch.orchestrator.mapper.CompensationCommandMapper;
import com.example.batch.orchestrator.service.LaunchService;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 补偿指令统一入口：按 {@code compensationType} 路由到 6 种处理器（JOB / STEP / PARTITION / FILE / BATCH / DLQ），
 * 并把命令生命周期（RUNNING → SUCCESS/FAILED）与补偿日志写入串在同一事务内。
 *
 * <p>关键不变量：
 *
 * <ul>
 *   <li><b>防重双保险</b>：{@link #assertNoRunningConflict} 先查 RUNNING 计数，DB 还有唯一约束兜底 （{@code
 *       DataIntegrityViolationException} 转为 {@code CONFLICT}），处理并发提交的 TOCTOU。
 *   <li><b>状态必达</b>：handler 抛异常时必须更新命令状态为 FAILED 并写 {@code job_execution_log}， 然后再
 *       rethrow；缺任何一步会让命令停留在 RUNNING 造成"幽灵补偿"。
 *   <li><b>路由表在构造期构建</b>：{@link #handlersByType} 是不可变 Map，避免分派时 if-chain 并保证 O(1)。
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultCompensationService implements CompensationService {

  // ── duplicate literal constants ─────────────────────────────────────────
  private static final String KEY_ACTION = "action";

  private final CompensationCommandMapper compensationCommandMapper;
  private final OrchestratorJobMappers jobMappers;
  private final RetryGovernanceService retryGovernanceService;
  private final FileGovernanceService fileGovernanceService;
  private final ObjectProvider<LaunchService> launchServiceProvider;
  private final TaskExecutionService taskExecutionService;

  @Lazy @Autowired private DefaultCompensationService self;

  /** 路由表：compensationType → handler。构造时一次性构建；O(1) 查找。 */
  private final Map<String, CompensationHandler> handlersByType =
      Map.of(
          "JOB", this::rerunJob,
          "STEP", this::rerunStep,
          "PARTITION", this::retryPartition,
          "FILE", (cmd, cmdNo, traceId, entity) -> reprocessFile(cmd, traceId, entity),
          "BATCH", this::rerunBatch,
          "DLQ", this::replayDeadLetter);

  private record CompensationLaunchTarget(
      String tenantId, String jobCode, LocalDate bizDate, TriggerType triggerType) {}

  private record CompensationLaunchRequest(
      CompensationLaunchTarget target,
      Map<String, Object> params,
      String traceId,
      String commandNo,
      Long replaySessionId) {
    private static CompensationLaunchRequest of(
        CompensationLaunchTarget target,
        Map<String, Object> params,
        String traceId,
        String commandNo,
        Long replaySessionId) {
      return new CompensationLaunchRequest(target, params, traceId, commandNo, replaySessionId);
    }
  }

  /**
   * 提交补偿指令:落库 command → 路由到 handler 执行 → 统一回写 SUCCESS/FAILED 状态与日志。
   *
   * <p><b>事务模型(2026-05-20 review 修正)</b>:之前用 {@code @Transactional(noRollbackFor =
   * Exception.class)} 让 handler 抛异常时整个外层事务**提交**(包括 handler
   * 已经做过的任务/分片/重放业务写入),导致**业务半截副作用泄漏到生产数据**。 修正后:
   *
   * <ul>
   *   <li>本 submit() 方法**不再有外层 @Transactional**(纯调度方法)
   *   <li>INSERT 命令行走 {@link #insertCommandInNewTx} REQUIRES_NEW,独立提交 — handler 失败也留住命令行
   *   <li>handler 执行 + SUCCESS 状态/日志走 {@link #executeAndMarkSuccessInOwnTx} 默认 @Transactional —
   *       handler 抛错就**回滚业务写入**,不留垃圾
   *   <li>FAILED 状态/日志走 {@link #markFailedAndLogInNewTx} REQUIRES_NEW — 即使外层业务事务回滚也独立留痕, 且能 unblock
   *       后续补偿提交(避免命令卡 RUNNING)
   * </ul>
   *
   * <p>权衡:理论上 INSERT 后 / executeAndMarkSuccessInOwnTx 成功提交前 JVM 崩溃,命令会卡 RUNNING。这通过 ops backlog 的
   * stale-RUNNING reconciler 兜底(范围外,不在本方法处理)。
   */
  @Override
  public String submit(CompensationSubmitCommand command) {
    // 2026-05-01 hardening: pre-insert 阶段(validate / 冲突检查 / insert 唯一约束)失败时
    // 也要落审计 trail。原实现只在 insert 成功后才 log,导致"提交即拒"的请求丢线索。
    String commandNo = null;
    String traceId = null;
    try {
      validate(command);
      commandNo = IdGenerator.newBusinessNo("cmp");
      String normalizedType = normalizeType(command.compensationType());
      String resolvedTraceId = resolveTraceIdFromTarget(command, normalizedType);
      traceId =
          Texts.hasText(resolvedTraceId)
              ? resolvedTraceId
              : (Texts.hasText(command.traceId()) ? command.traceId() : IdGenerator.newTraceId());
      assertNoRunningConflict(command);
    } catch (RuntimeException pre) {
      self.appendPreInsertFailureLog(command, traceId, commandNo, pre);
      throw pre;
    }
    CompensationCommandEntity entity = buildCommandEntity(command, commandNo, traceId);
    try {
      self.insertCommandInNewTx(entity);
    } catch (DataIntegrityViolationException ex) {
      BizException wrapped =
          BizException.of(ResultCode.CONFLICT, "error.compensation.already_running", ex);
      self.appendPreInsertFailureLog(command, traceId, commandNo, wrapped);
      throw wrapped;
    }
    try {
      self.executeAndMarkSuccessInOwnTx(command, commandNo, traceId, entity);
      return commandNo;
    } catch (Exception exception) {
      // 业务事务已正常回滚 handler 的副作用(任务/分片/重放状态);此处独立 NEW tx 写 FAILED + 日志
      self.markFailedAndLogInNewTx(command, traceId, entity, exception);
      throw exception;
    }
  }

  /** INSERT command row in REQUIRES_NEW 独立提交;handler 失败也留住命令行(审计 + unblock 后续提交)。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void insertCommandInNewTx(CompensationCommandEntity entity) {
    compensationCommandMapper.insert(entity);
  }

  /** Handler 业务写 + SUCCESS 状态/日志,默认 @Transactional:handler 抛错回滚业务写入。 */
  @Transactional
  public void executeAndMarkSuccessInOwnTx(
      CompensationSubmitCommand command,
      String commandNo,
      String traceId,
      CompensationCommandEntity entity) {
    Map<String, Object> result = execute(command, commandNo, traceId, entity);
    result.put("hitCount", 1);
    result.put("conflictCount", 0);
    compensationCommandMapper.updateStatus(
        UpdateCompensationStatusParam.builder()
            .tenantId(command.tenantId())
            .id(entity.getId())
            .commandStatus(CompensationCommandStatus.SUCCESS.code())
            .relatedJobInstanceId(entity.getRelatedJobInstanceId())
            .relatedFileId(entity.getRelatedFileId())
            .resultSummary(JsonUtils.toJson(result))
            .errorCode(null)
            .errorMessage(null)
            .finishedAt(BatchDateTimeSupport.utcNow())
            .build());
    appendCompensationLog(
        new CompensationLogContext(
            command, traceId, entity, CompensationCommandStatus.SUCCESS.code(), result, null));
  }

  /**
   * 业务事务失败后独立 NEW tx 写 FAILED + 日志。外层业务回滚不影响命令行(走 insertCommandInNewTx 已独立提交); 这里只是把命令状态从 RUNNING
   * 推进到 FAILED + 记录失败日志。
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void markFailedAndLogInNewTx(
      CompensationSubmitCommand command,
      String traceId,
      CompensationCommandEntity entity,
      Exception exception) {
    compensationCommandMapper.updateStatus(
        UpdateCompensationStatusParam.builder()
            .tenantId(command.tenantId())
            .id(entity.getId())
            .commandStatus(CompensationCommandStatus.FAILED.code())
            .relatedJobInstanceId(entity.getRelatedJobInstanceId())
            .relatedFileId(entity.getRelatedFileId())
            .resultSummary(null)
            .errorCode(resolveErrorCode(exception))
            .errorMessage(exception.getMessage())
            .finishedAt(BatchDateTimeSupport.utcNow())
            .build());
    appendCompensationLog(
        new CompensationLogContext(
            command, traceId, entity, CompensationCommandStatus.FAILED.code(), null, exception));
  }

  /**
   * R7-A5-P2 / CLAUDE.md §分支消除：把 JOB/STEP/PARTITION 三种 target 类型抽成 Map 路由表， 每种类型只描述"如何拿到归属
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
    CompensationHandler handler = handlersByType.get(compensationType);
    if (handler == null) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          "error.common.invalid_argument_detail",
          "unsupported compensationType: " + command.compensationType());
    }
    return handler.handle(command, commandNo, traceId, entity);
  }

  private Map<String, Object> rerunJob(
      CompensationSubmitCommand command,
      String commandNo,
      String traceId,
      CompensationCommandEntity entity) {
    JobInstanceEntity sourceInstance = resolveJobInstance(command);
    entity.setRelatedJobInstanceId(sourceInstance.getId());
    Map<String, Object> params = extractEffectiveParams(sourceInstance.getParamsSnapshot());
    params.put("operationType", "JOB_RERUN");
    params.put("parentInstanceId", sourceInstance.getId());
    params.put("parentInstanceNo", sourceInstance.getInstanceNo());
    params.put("operatorId", command.operatorId());
    params.put("approvalId", command.approvalId());
    params.put("strategy", command.strategy());
    params.put("batchNo", firstText(command.batchNo(), sourceInstance.getBatchNo()));
    params.put(
        "relatedFileId", firstNonNull(command.relatedFileId(), sourceInstance.getRelatedFileId()));
    params.put("rerunFlag", true);
    params.put("retryFlag", false);
    params.put("reason", command.reason());
    applyRerunPolicyParams(params, command);
    LaunchResponse response =
        launchCompensation(
            CompensationLaunchRequest.of(
                new CompensationLaunchTarget(
                    command.tenantId(),
                    sourceInstance.getJobCode(),
                    sourceInstance.getBizDate(),
                    TriggerType.CATCH_UP),
                params,
                traceId,
                commandNo,
                command.replaySessionId()));
    JobInstanceEntity launched =
        jobMappers.jobInstanceMapper.selectByInstanceNo(command.tenantId(), response.instanceNo());
    entity.setRelatedJobInstanceId(launched == null ? sourceInstance.getId() : launched.getId());
    Map<String, Object> result = new LinkedHashMap<>();
    result.put(KEY_ACTION, "JOB_RERUN");
    result.put("sourceInstanceNo", sourceInstance.getInstanceNo());
    result.put("newInstanceNo", response.instanceNo());
    result.put("newTraceId", response.traceId());
    result.put("targetJobInstanceId", launched == null ? null : launched.getId());
    return result;
  }

  private Map<String, Object> rerunStep(
      CompensationSubmitCommand command,
      String commandNo,
      String traceId,
      CompensationCommandEntity entity) {
    Long stepId = command.targetId();
    Guard.require(stepId != null, "step targetId is required");
    JobStepInstanceEntity stepInstance =
        Guard.requireFound(
            jobMappers.jobStepInstanceMapper.selectById(command.tenantId(), stepId),
            "job step instance not found");
    entity.setRelatedJobInstanceId(stepInstance.getJobInstanceId());
    Long taskId = stepInstance.getJobTaskId();
    Guard.require(taskId != null, "step jobTaskId is required");
    retryGovernanceService.retryTask(
        command.tenantId(),
        taskId,
        OutboxEventKeyGenerator.forCompensation(command.tenantId(), commandNo, taskId));
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
    if (command.targetId() == null) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.partition.target_id_required");
    }
    retryGovernanceService.retryPartition(
        command.tenantId(),
        command.targetId(),
        OutboxEventKeyGenerator.forCompensation(command.tenantId(), commandNo, command.targetId()));
    Map<String, Object> result = new LinkedHashMap<>();
    result.put(KEY_ACTION, "PARTITION_RETRY");
    result.put("partitionId", command.targetId());
    result.put("traceId", traceId);
    return result;
  }

  private Map<String, Object> reprocessFile(
      CompensationSubmitCommand command, String traceId, CompensationCommandEntity entity) {
    Long fileId = firstNonNull(command.relatedFileId(), command.targetId());
    Guard.require(fileId != null, "file targetId is required");
    FileGovernanceCommand redispatchCommand =
        FileGovernanceCommand.builder()
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
    Map<String, Object> params = new LinkedHashMap<>();
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
    LaunchResponse response =
        launchCompensation(
            CompensationLaunchRequest.of(
                new CompensationLaunchTarget(
                    command.tenantId(), command.jobCode(), command.bizDate(), TriggerType.CATCH_UP),
                params,
                traceId,
                commandNo,
                command.replaySessionId()));
    JobInstanceEntity launched =
        jobMappers.jobInstanceMapper.selectByInstanceNo(command.tenantId(), response.instanceNo());
    entity.setRelatedJobInstanceId(launched == null ? null : launched.getId());
    Map<String, Object> result = new LinkedHashMap<>();
    result.put(KEY_ACTION, "BATCH_RERUN");
    result.put("jobCode", command.jobCode());
    result.put("bizDate", command.bizDate());
    result.put("batchNo", command.batchNo());
    result.put("newInstanceNo", response.instanceNo());
    result.put("newTraceId", response.traceId());
    return result;
  }

  private Map<String, Object> replayDeadLetter(
      CompensationSubmitCommand command,
      String commandNo,
      String traceId,
      CompensationCommandEntity entity) {
    if (command.targetId() == null) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.dead_letter.target_id_required");
    }
    retryGovernanceService.replayDeadLetter(command.tenantId(), command.targetId());
    Map<String, Object> result = new LinkedHashMap<>();
    result.put(KEY_ACTION, "DLQ_REPLAY");
    result.put("deadLetterId", command.targetId());
    result.put("commandNo", commandNo);
    result.put("traceId", traceId);
    return result;
  }

  private LaunchResponse launchCompensation(CompensationLaunchRequest request) {
    String requestId = IdGenerator.newBusinessNo("req");
    TriggerRequestEntity triggerRequest = new TriggerRequestEntity();
    triggerRequest.setTenantId(request.target().tenantId());
    triggerRequest.setRequestId(requestId);
    triggerRequest.setTriggerType(request.target().triggerType().code());
    triggerRequest.setJobCode(request.target().jobCode());
    triggerRequest.setBizDate(request.target().bizDate());
    triggerRequest.setDedupKey(
        request.target().tenantId() + ":compensation:" + request.commandNo() + ":" + requestId);
    triggerRequest.setRequestStatus(BatchStatusConstants.ACCEPTED);
    triggerRequest.setTraceId(request.traceId());
    jobMappers.triggerRequestMapper.insert(triggerRequest);
    LaunchRequest launchRequest =
        LaunchRequest.builder()
            .tenantId(request.target().tenantId())
            .jobCode(request.target().jobCode())
            .bizDate(request.target().bizDate())
            .triggerType(request.target().triggerType())
            .requestId(requestId)
            .traceId(request.traceId())
            .params(request.params())
            .replaySessionId(request.replaySessionId())
            .build();
    return launchServiceProvider.getObject().launch(launchRequest);
  }

  private CompensationCommandEntity buildCommandEntity(
      CompensationSubmitCommand command, String commandNo, String traceId) {
    CompensationCommandEntity entity = new CompensationCommandEntity();
    entity.setTenantId(command.tenantId());
    entity.setCommandNo(commandNo);
    entity.setCompensationType(normalizeType(command.compensationType()));
    entity.setTargetId(command.targetId());
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
        return entity;
      }
    }
    if (Texts.hasText(command.targetInstanceNo())) {
      JobInstanceEntity entity =
          jobMappers.jobInstanceMapper.selectByInstanceNo(
              command.tenantId(), command.targetInstanceNo());
      if (entity != null) {
        return entity;
      }
    }
    throw BizException.of(ResultCode.NOT_FOUND, "error.job.instance_not_found");
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
    log.setExtraJson(JsonUtils.toJson(detail));
    taskExecutionService.appendLog(log);
  }

  /**
   * 2026-05-01 hardening: pre-insert 阶段(validate / 冲突检查 / insert 唯一约束)失败的审计 trail。
   *
   * <p>正常 happy/fail 路径用 {@link #appendCompensationLog} 需要 entity 已 insert(commandNo +
   * relatedJobInstanceId 已知)。本方法允许 entity 未 insert 时仍 log,关联键退化用 tenant + targetType + targetId。
   *
   * <p>使用 {@code Propagation.REQUIRES_NEW}:外层 {@code @Transactional} 因业务异常即将回滚,审计行必须独立提交才能留下。
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  void appendPreInsertFailureLog(
      CompensationSubmitCommand command, String traceId, String commandNo, Exception exception) {
    try {
      JobExecutionLogEntity log = new JobExecutionLogEntity();
      log.setTenantId(command == null ? null : command.tenantId());
      log.setJobInstanceId(null); // pre-insert 无 entity → jobInstanceId 未知
      log.setLogLevel("ERROR");
      log.setLogType("COMPENSATION_REJECTED");
      log.setTraceId(traceId);
      String type = command == null ? null : command.compensationType();
      log.setMessage(
          "compensation submit rejected before insert: type="
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
      log.setExtraJson(JsonUtils.toJson(detail));
      taskExecutionService.appendLog(log);
    } catch (RuntimeException loggingEx) {
      // 落 trail 是 best-effort,不能让审计失败掩盖原始业务异常
      this.log.warn(
          "failed to write compensation pre-insert failure trail: tenant={}, type={}, error={}",
          command == null ? null : command.tenantId(),
          command == null ? null : command.compensationType(),
          loggingEx.getMessage());
    }
  }

  private void assertNoRunningConflict(CompensationSubmitCommand command) {
    Long targetId = resolveConflictTargetId(command);
    if (targetId == null) {
      return;
    }
    String type = normalizeType(command.compensationType());
    int running =
        compensationCommandMapper.countRunningByTarget(
            command.tenantId(), type, targetId, CompensationCommandStatus.RUNNING.code());
    if (running > 0) {
      throw BizException.of(ResultCode.CONFLICT, "error.compensation.already_running");
    }
  }

  private Long resolveConflictTargetId(CompensationSubmitCommand command) {
    String type = normalizeType(command.compensationType());
    if (type == null) {
      return null;
    }
    return switch (type) {
      case "JOB", "STEP", "PARTITION", "DLQ" -> command.targetId();
      case "FILE" -> firstNonNull(command.relatedFileId(), command.targetId());
      default -> null;
    };
  }

  private void validate(CompensationSubmitCommand command) {
    Guard.require(command != null, "compensation command is required");
    if (!Texts.hasText(command.tenantId())) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.tenant_id_required");
    }
    if (!Texts.hasText(command.compensationType())) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.compensation.type_required");
    }
  }

  private String normalizeType(String compensationType) {
    return compensationType == null ? null : compensationType.trim().toUpperCase();
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
}

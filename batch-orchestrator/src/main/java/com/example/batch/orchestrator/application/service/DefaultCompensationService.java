package com.example.batch.orchestrator.application.service;

import com.example.batch.common.constants.BatchStatusConstants;
import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.dto.LaunchResponse;
import com.example.batch.common.enums.CompensationCommandStatus;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.persistence.entity.TriggerRequestEntity;
import com.example.batch.common.utils.Guard;
import com.example.batch.common.utils.IdGenerator;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.orchestrator.domain.command.CompensationSubmitCommand;
import com.example.batch.orchestrator.domain.command.FileGovernanceCommand;
import com.example.batch.orchestrator.domain.entity.CompensationCommandEntity;
import com.example.batch.orchestrator.domain.entity.JobExecutionLogEntity;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import com.example.batch.orchestrator.domain.entity.JobStepInstanceEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.mapper.CompensationCommandMapper;
import com.example.batch.orchestrator.mapper.UpdateCompensationStatusParam;
import com.example.batch.orchestrator.service.LaunchService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 补偿指令统一入口：按 {@code compensationType} 路由到 6 种处理器（JOB / STEP / PARTITION / FILE / BATCH / DLQ），
 * 并把命令生命周期（RUNNING → SUCCESS/FAILED）与补偿日志写入串在同一事务内。
 *
 * <p>关键不变量：
 *
 * <ul>
 *   <li><b>防重双保险</b>：{@link #assertNoRunningConflict} 先查 RUNNING 计数，DB 还有唯一约束兜底
 *       （{@code DataIntegrityViolationException} 转为 {@code CONFLICT}），处理并发提交的 TOCTOU。
 *   <li><b>状态必达</b>：handler 抛异常时必须更新命令状态为 FAILED 并写 {@code job_execution_log}，
 *       然后再 rethrow；缺任何一步会让命令停留在 RUNNING 造成"幽灵补偿"。
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
      String commandNo) {
    private static CompensationLaunchRequest of(
        CompensationLaunchTarget target,
        Map<String, Object> params,
        String traceId,
        String commandNo) {
      return new CompensationLaunchRequest(target, params, traceId, commandNo);
    }
  }

  /**
   * 提交补偿指令：落库 command → 路由到 handler 执行 → 统一回写 SUCCESS/FAILED 状态与日志。
   *
   * <p>执行链上 handler 抛异常时，先把 FAILED 状态 + 日志写完再 rethrow——绝不能跳过，否则命令卡在 RUNNING
   * 将永久阻塞同目标的后续补偿提交（见 {@link #assertNoRunningConflict} 与 DB 唯一约束）。
   */
  @Override
  @Transactional
  public String submit(CompensationSubmitCommand command) {
    validate(command);
    String commandNo = IdGenerator.newBusinessNo("cmp");
    String normalizedType = normalizeType(command.compensationType());
    String resolvedTraceId = resolveTraceIdFromTarget(command, normalizedType);
    String traceId =
        StringUtils.hasText(resolvedTraceId)
            ? resolvedTraceId
            : (StringUtils.hasText(command.traceId())
                ? command.traceId()
                : IdGenerator.newTraceId());
    CompensationCommandEntity entity = buildCommandEntity(command, commandNo, traceId);
    assertNoRunningConflict(command);
    try {
      compensationCommandMapper.insert(entity);
    } catch (DataIntegrityViolationException ex) {
      throw new BizException(
          ResultCode.CONFLICT, "compensation command already running for this target", ex);
    }
    try {
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
              .finishedAt(Instant.now())
              .build());
      appendCompensationLog(
          new CompensationLogContext(
              command, traceId, entity, CompensationCommandStatus.SUCCESS.code(), result, null));
      return commandNo;
    } catch (Exception exception) {
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
              .finishedAt(Instant.now())
              .build());
      appendCompensationLog(
          new CompensationLogContext(
              command, traceId, entity, CompensationCommandStatus.FAILED.code(), null, exception));
      throw exception;
    }
  }

  private String resolveTraceIdFromTarget(
      CompensationSubmitCommand command, String normalizedType) {
    if (!StringUtils.hasText(command.tenantId()) || command.targetId() == null) {
      return null;
    }
    return switch (normalizedType) {
      case "JOB" -> {
        JobInstanceEntity sourceInstance = resolveJobInstance(command);
        yield sourceInstance == null ? null : sourceInstance.getTraceId();
      }
      case "STEP" -> {
        JobStepInstanceEntity stepInstance =
            jobMappers.jobStepInstanceMapper.selectById(command.tenantId(), command.targetId());
        if (stepInstance == null) {
          yield null;
        }
        JobInstanceEntity inst =
            jobMappers.jobInstanceMapper.selectById(
                command.tenantId(), stepInstance.getJobInstanceId());
        yield inst == null ? null : inst.getTraceId();
      }
      case "PARTITION" -> {
        JobPartitionEntity partition =
            jobMappers.jobPartitionMapper.selectById(command.tenantId(), command.targetId());
        if (partition == null) {
          yield null;
        }
        JobInstanceEntity inst =
            jobMappers.jobInstanceMapper.selectById(
                command.tenantId(), partition.getJobInstanceId());
        yield inst == null ? null : inst.getTraceId();
      }
      default -> null;
    };
  }

  private Map<String, Object> execute(
      CompensationSubmitCommand command,
      String commandNo,
      String traceId,
      CompensationCommandEntity entity) {
    String compensationType = normalizeType(command.compensationType());
    CompensationHandler handler = handlersByType.get(compensationType);
    if (handler == null) {
      throw new BizException(
          ResultCode.INVALID_ARGUMENT,
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
                commandNo));
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
        command.tenantId(), taskId, command.tenantId() + ":manual-step:" + commandNo);
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
      throw new BizException(ResultCode.INVALID_ARGUMENT, "partition targetId is required");
    }
    retryGovernanceService.retryPartition(
        command.tenantId(),
        command.targetId(),
        command.tenantId() + ":manual-partition:" + commandNo);
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
    String result =
        fileGovernanceService.redispatchFile(
            new FileGovernanceCommand(
                command.tenantId(),
                fileId,
                command.channelCode(),
                command.operatorId(),
                traceId,
                command.reason(),
                command.approvalId()));
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
    if (!StringUtils.hasText(command.jobCode()) || command.bizDate() == null) {
      throw new BizException(
          ResultCode.INVALID_ARGUMENT, "jobCode and bizDate are required for batch rerun");
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
    LaunchResponse response =
        launchCompensation(
            CompensationLaunchRequest.of(
                new CompensationLaunchTarget(
                    command.tenantId(), command.jobCode(), command.bizDate(), TriggerType.CATCH_UP),
                params,
                traceId,
                commandNo));
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
      throw new BizException(ResultCode.INVALID_ARGUMENT, "dead letter targetId is required");
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
    return launchServiceProvider
        .getObject()
        .launch(
            new LaunchRequest(
                request.target().tenantId(),
                request.target().jobCode(),
                request.target().bizDate(),
                request.target().triggerType(),
                requestId,
                request.traceId(),
                request.params()));
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
    if (StringUtils.hasText(command.targetInstanceNo())) {
      JobInstanceEntity entity =
          jobMappers.jobInstanceMapper.selectByInstanceNo(
              command.tenantId(), command.targetInstanceNo());
      if (entity != null) {
        return entity;
      }
    }
    throw new BizException(ResultCode.NOT_FOUND, "job instance not found");
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
      throw new BizException(
          ResultCode.CONFLICT, "compensation command already running for this target");
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
    if (!StringUtils.hasText(command.tenantId())) {
      throw new BizException(ResultCode.INVALID_ARGUMENT, "tenantId is required");
    }
    if (!StringUtils.hasText(command.compensationType())) {
      throw new BizException(ResultCode.INVALID_ARGUMENT, "compensationType is required");
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
    if (!StringUtils.hasText(paramsSnapshot)) {
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
    return StringUtils.hasText(left) ? left : right;
  }

  private Long firstNonNull(Long left, Long right) {
    return left != null ? left : right;
  }
}

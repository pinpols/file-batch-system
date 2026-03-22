package com.example.batch.orchestrator.application.service;

import com.example.batch.common.constants.BatchStatusConstants;
import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.dto.LaunchResponse;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.IdGenerator;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.orchestrator.domain.command.CompensationSubmitCommand;
import com.example.batch.orchestrator.domain.command.FileGovernanceCommand;
import com.example.batch.orchestrator.domain.entity.CompensationCommandEntity;
import com.example.batch.orchestrator.domain.entity.JobExecutionLogEntity;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.JobStepInstanceEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.domain.entity.TriggerRequestEntity;
import com.example.batch.orchestrator.mapper.CompensationCommandMapper;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.JobStepInstanceMapper;
import com.example.batch.orchestrator.mapper.JobTaskMapper;
import com.example.batch.orchestrator.mapper.TriggerRequestMapper;
import com.example.batch.orchestrator.service.LaunchService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class DefaultCompensationService implements CompensationService {

    private final CompensationCommandMapper compensationCommandMapper;
    private final JobInstanceMapper jobInstanceMapper;
    private final JobStepInstanceMapper jobStepInstanceMapper;
    private final JobTaskMapper jobTaskMapper;
    private final TriggerRequestMapper triggerRequestMapper;
    private final RetryGovernanceService retryGovernanceService;
    private final FileGovernanceService fileGovernanceService;
    private final LaunchService launchService;
    private final TaskExecutionService taskExecutionService;

    @Override
    @Transactional
    public String submit(CompensationSubmitCommand command) {
        validate(command);
        String commandNo = IdGenerator.newBusinessNo("cmp");
        String traceId = StringUtils.hasText(command.traceId()) ? command.traceId() : IdGenerator.newTraceId();
        CompensationCommandEntity entity = buildCommandEntity(command, commandNo, traceId);
        assertNoRunningConflict(command);
        try {
            compensationCommandMapper.insert(entity);
        } catch (DataIntegrityViolationException ex) {
            throw new BizException(ResultCode.CONFLICT, "compensation command already running for this target", ex);
        }
        try {
            Map<String, Object> result = execute(command, commandNo, traceId, entity);
            result.put("hitCount", 1);
            result.put("conflictCount", 0);
            compensationCommandMapper.updateStatus(
                    command.tenantId(),
                    entity.getId(),
                    "SUCCESS",
                    entity.getRelatedJobInstanceId(),
                    entity.getRelatedFileId(),
                    JsonUtils.toJson(result),
                    null,
                    null,
                    Instant.now()
            );
            appendCompensationLog(command, traceId, entity, "SUCCESS", result, null);
            return commandNo;
        } catch (Exception exception) {
            compensationCommandMapper.updateStatus(
                    command.tenantId(),
                    entity.getId(),
                    "FAILED",
                    entity.getRelatedJobInstanceId(),
                    entity.getRelatedFileId(),
                    null,
                    resolveErrorCode(exception),
                    exception.getMessage(),
                    Instant.now()
            );
            appendCompensationLog(command, traceId, entity, "FAILED", null, exception);
            throw exception;
        }
    }

    private Map<String, Object> execute(CompensationSubmitCommand command,
                                        String commandNo,
                                        String traceId,
                                        CompensationCommandEntity entity) {
        String compensationType = normalizeType(command.compensationType());
        return switch (compensationType) {
            case "JOB" -> rerunJob(command, commandNo, traceId, entity);
            case "STEP" -> rerunStep(command, commandNo, traceId, entity);
            case "PARTITION" -> retryPartition(command, commandNo, traceId, entity);
            case "FILE" -> reprocessFile(command, traceId, entity);
            case "BATCH" -> rerunBatch(command, commandNo, traceId, entity);
            case "DLQ" -> replayDeadLetter(command, commandNo, traceId, entity);
            default -> throw new BizException(ResultCode.INVALID_ARGUMENT, "unsupported compensationType: " + command.compensationType());
        };
    }

    private Map<String, Object> rerunJob(CompensationSubmitCommand command,
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
        params.put("relatedFileId", firstNonNull(command.relatedFileId(), sourceInstance.getRelatedFileId()));
        params.put("rerunFlag", true);
        params.put("retryFlag", false);
        params.put("reason", command.reason());
        LaunchResponse response = launchCompensation(
                command.tenantId(),
                sourceInstance.getJobCode(),
                sourceInstance.getBizDate(),
                TriggerType.CATCH_UP,
                params,
                traceId,
                commandNo
        );
        JobInstanceEntity launched = jobInstanceMapper.selectByInstanceNo(command.tenantId(), response.instanceNo());
        entity.setRelatedJobInstanceId(launched == null ? sourceInstance.getId() : launched.getId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("action", "JOB_RERUN");
        result.put("sourceInstanceNo", sourceInstance.getInstanceNo());
        result.put("newInstanceNo", response.instanceNo());
        result.put("newTraceId", response.traceId());
        result.put("targetJobInstanceId", launched == null ? null : launched.getId());
        return result;
    }

    private Map<String, Object> rerunStep(CompensationSubmitCommand command,
                                          String commandNo,
                                          String traceId,
                                          CompensationCommandEntity entity) {
        Long stepId = command.targetId();
        if (stepId == null) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "step targetId is required");
        }
        JobStepInstanceEntity stepInstance = jobStepInstanceMapper.selectById(command.tenantId(), stepId);
        if (stepInstance == null) {
            throw new BizException(ResultCode.NOT_FOUND, "job step instance not found");
        }
        entity.setRelatedJobInstanceId(stepInstance.getJobInstanceId());
        Long taskId = stepInstance.getJobTaskId();
        if (taskId == null) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "step jobTaskId is required");
        }
        retryGovernanceService.retryTask(
                command.tenantId(),
                taskId,
                command.tenantId() + ":manual-step:" + commandNo
        );
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("action", "STEP_RERUN");
        result.put("stepInstanceId", stepInstance.getId());
        result.put("jobInstanceId", stepInstance.getJobInstanceId());
        JobTaskEntity task = jobTaskMapper.selectById(command.tenantId(), taskId);
        if (task != null) {
            result.put("jobTaskId", task.getId());
            result.put("jobPartitionId", task.getJobPartitionId());
        }
        result.put("traceId", traceId);
        return result;
    }

    private Map<String, Object> retryPartition(CompensationSubmitCommand command,
                                               String commandNo,
                                               String traceId,
                                               CompensationCommandEntity entity) {
        if (command.targetId() == null) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "partition targetId is required");
        }
        retryGovernanceService.retryPartition(
                command.tenantId(),
                command.targetId(),
                command.tenantId() + ":manual-partition:" + commandNo
        );
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("action", "PARTITION_RETRY");
        result.put("partitionId", command.targetId());
        result.put("traceId", traceId);
        return result;
    }

    private Map<String, Object> reprocessFile(CompensationSubmitCommand command,
                                              String traceId,
                                              CompensationCommandEntity entity) {
        Long fileId = firstNonNull(command.relatedFileId(), command.targetId());
        if (fileId == null) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "file targetId is required");
        }
        String result = fileGovernanceService.redispatchFile(new FileGovernanceCommand(
                command.tenantId(),
                fileId,
                command.channelCode(),
                command.operatorId(),
                traceId,
                command.reason(),
                command.approvalId()
        ));
        entity.setRelatedFileId(fileId);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("action", "FILE_REPROCESS");
        summary.put("fileId", fileId);
        summary.put("channelCode", command.channelCode());
        summary.put("status", result);
        return summary;
    }

    private Map<String, Object> rerunBatch(CompensationSubmitCommand command,
                                           String commandNo,
                                           String traceId,
                                           CompensationCommandEntity entity) {
        if (!StringUtils.hasText(command.jobCode()) || command.bizDate() == null) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "jobCode and bizDate are required for batch rerun");
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
        LaunchResponse response = launchCompensation(
                command.tenantId(),
                command.jobCode(),
                command.bizDate(),
                TriggerType.CATCH_UP,
                params,
                traceId,
                commandNo
        );
        JobInstanceEntity launched = jobInstanceMapper.selectByInstanceNo(command.tenantId(), response.instanceNo());
        entity.setRelatedJobInstanceId(launched == null ? null : launched.getId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("action", "BATCH_RERUN");
        result.put("jobCode", command.jobCode());
        result.put("bizDate", command.bizDate());
        result.put("batchNo", command.batchNo());
        result.put("newInstanceNo", response.instanceNo());
        result.put("newTraceId", response.traceId());
        return result;
    }

    private Map<String, Object> replayDeadLetter(CompensationSubmitCommand command,
                                                 String commandNo,
                                                 String traceId,
                                                 CompensationCommandEntity entity) {
        if (command.targetId() == null) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "dead letter targetId is required");
        }
        retryGovernanceService.replayDeadLetter(command.tenantId(), command.targetId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("action", "DLQ_REPLAY");
        result.put("deadLetterId", command.targetId());
        result.put("commandNo", commandNo);
        result.put("traceId", traceId);
        return result;
    }

    private LaunchResponse launchCompensation(String tenantId,
                                              String jobCode,
                                              LocalDate bizDate,
                                              TriggerType triggerType,
                                              Map<String, Object> params,
                                              String traceId,
                                              String commandNo) {
        String requestId = IdGenerator.newBusinessNo("req");
        TriggerRequestEntity triggerRequest = new TriggerRequestEntity();
        triggerRequest.setTenantId(tenantId);
        triggerRequest.setRequestId(requestId);
        triggerRequest.setTriggerType(triggerType.code());
        triggerRequest.setJobCode(jobCode);
        triggerRequest.setBizDate(bizDate);
        triggerRequest.setDedupKey(tenantId + ":compensation:" + commandNo + ":" + requestId);
        triggerRequest.setRequestStatus(BatchStatusConstants.ACCEPTED);
        triggerRequest.setTraceId(traceId);
        triggerRequestMapper.insert(triggerRequest);
        return launchService.launch(new LaunchRequest(
                tenantId,
                jobCode,
                bizDate,
                triggerType,
                requestId,
                traceId,
                params
        ));
    }

    private CompensationCommandEntity buildCommandEntity(CompensationSubmitCommand command,
                                                         String commandNo,
                                                         String traceId) {
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
        entity.setCommandStatus("RUNNING");
        entity.setTraceId(traceId);
        return entity;
    }

    private JobInstanceEntity resolveJobInstance(CompensationSubmitCommand command) {
        if (command.targetId() != null) {
            JobInstanceEntity entity = jobInstanceMapper.selectById(command.tenantId(), command.targetId());
            if (entity != null) {
                return entity;
            }
        }
        if (StringUtils.hasText(command.targetInstanceNo())) {
            JobInstanceEntity entity = jobInstanceMapper.selectByInstanceNo(command.tenantId(), command.targetInstanceNo());
            if (entity != null) {
                return entity;
            }
        }
        throw new BizException(ResultCode.NOT_FOUND, "job instance not found");
    }

    private void appendCompensationLog(CompensationSubmitCommand command,
                                       String traceId,
                                       CompensationCommandEntity entity,
                                       String outcome,
                                       Map<String, Object> result,
                                       Exception exception) {
        JobExecutionLogEntity log = new JobExecutionLogEntity();
        log.setTenantId(command.tenantId());
        log.setJobInstanceId(entity.getRelatedJobInstanceId());
        log.setLogLevel("SUCCESS".equals(outcome) ? "INFO" : "ERROR");
        log.setLogType("COMPENSATION");
        log.setTraceId(traceId);
        log.setMessage(entity.getCompensationType() + " compensation " + outcome);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("commandNo", entity.getCommandNo());
        detail.put("compensationType", entity.getCompensationType());
        detail.put("targetId", entity.getTargetId());
        detail.put("reason", entity.getReason());
        detail.put("operatorId", entity.getOperatorId());
        detail.put("approvalId", entity.getApprovalId());
        detail.put("strategy", entity.getStrategy());
        if (result != null) {
            detail.put("result", result);
        }
        if (exception != null) {
            detail.put("error", exception.getMessage());
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
        int running = compensationCommandMapper.countRunningByTarget(command.tenantId(), type, targetId);
        if (running > 0) {
            throw new BizException(ResultCode.CONFLICT, "compensation command already running for this target");
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
        if (command == null) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "compensation command is required");
        }
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

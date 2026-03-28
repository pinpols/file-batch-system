package com.example.batch.console.infrastructure;

import com.example.batch.console.application.ConsoleQueryApplicationService;
import com.example.batch.common.persistence.entity.AlertEventEntity;
import com.example.batch.console.domain.entity.DeadLetterTaskEntity;
import com.example.batch.console.domain.entity.FileArrivalGroupEntity;
import com.example.batch.console.domain.entity.FileErrorRecordEntity;
import com.example.batch.console.domain.entity.FileRecordEntity;
import com.example.batch.console.domain.entity.JobDefinitionEntity;
import com.example.batch.console.domain.entity.JobInstanceEntity;
import com.example.batch.console.domain.entity.JobStepInstanceEntity;
import com.example.batch.console.domain.entity.PendingCatchUpEntity;
import com.example.batch.console.domain.entity.RetryScheduleEntity;
import com.example.batch.console.domain.entity.WorkerRegistryEntity;
import com.example.batch.console.domain.entity.WorkflowDefinitionEntity;
import com.example.batch.console.domain.entity.WorkflowEdgeEntity;
import com.example.batch.console.domain.entity.WorkflowNodeEntity;
import com.example.batch.console.domain.entity.WorkflowNodeRunEntity;
import com.example.batch.common.persistence.entity.WorkflowRunEntity;
import com.example.batch.console.domain.query.AlertEventQuery;
import com.example.batch.console.domain.query.AuditLogQuery;
import com.example.batch.console.domain.query.ApprovalCommandQuery;
import com.example.batch.console.domain.query.DeadLetterTaskQuery;
import com.example.batch.console.domain.query.FileErrorRecordQuery;
import com.example.batch.console.domain.query.FileArrivalGroupQuery;
import com.example.batch.console.domain.query.FileRecordQuery;
import com.example.batch.console.domain.query.JobInstanceQuery;
import com.example.batch.console.domain.query.JobStepInstanceQuery;
import com.example.batch.console.domain.query.JobDefinitionQuery;
import com.example.batch.console.domain.query.OutboxDeliveryLogQuery;
import com.example.batch.console.domain.query.OutboxRetryLogQuery;
import com.example.batch.console.domain.query.PendingCatchUpQuery;
import com.example.batch.console.domain.query.RetryScheduleQuery;
import com.example.batch.console.domain.query.WorkerRegistryQuery;
import com.example.batch.console.domain.query.WorkflowDefinitionQuery;
import com.example.batch.console.domain.query.WorkflowNodeQuery;
import com.example.batch.console.domain.query.WorkflowEdgeQuery;
import com.example.batch.console.domain.query.WorkflowRunQuery;
import com.example.batch.console.domain.query.WorkflowNodeRunQuery;
import com.example.batch.console.domain.query.ConsoleAiAuditLogQuery;
import com.example.batch.console.web.view.WorkflowTopologyView;
import com.example.batch.console.web.response.AiAuditLogResponse;
import com.example.batch.console.web.response.ConsoleAlertEventResponse;
import com.example.batch.console.web.response.ConsoleDeadLetterTaskResponse;
import com.example.batch.console.web.response.ConsoleFileArrivalGroupResponse;
import com.example.batch.console.web.response.ConsoleFileErrorRecordResponse;
import com.example.batch.console.web.response.ConsoleFileRecordResponse;
import com.example.batch.console.web.response.ConsoleJobDefinitionResponse;
import com.example.batch.console.web.response.ConsoleJobInstanceResponse;
import com.example.batch.console.web.response.ConsoleJobStepInstanceResponse;
import com.example.batch.console.web.response.ConsoleApprovalCommandResponse;
import com.example.batch.console.web.response.ConsoleAuditLogResponse;
import com.example.batch.console.web.response.ConsoleFileChannelResponse;
import com.example.batch.console.web.response.ConsoleFileDispatchRecordResponse;
import com.example.batch.console.web.response.ConsoleFilePipelineResponse;
import com.example.batch.console.web.response.ConsoleFilePipelineStepResponse;
import com.example.batch.console.web.response.ConsoleFileTemplateResponse;
import com.example.batch.console.web.response.ConsoleOutboxDeliveryLogResponse;
import com.example.batch.console.web.response.ConsoleOutboxRetryLogResponse;
import com.example.batch.console.web.response.ConsolePendingCatchUpResponse;
import com.example.batch.console.web.response.ConsoleRetryScheduleResponse;
import com.example.batch.console.web.response.ConsoleWorkflowDefinitionResponse;
import com.example.batch.console.web.response.ConsoleWorkflowEdgeResponse;
import com.example.batch.console.web.response.ConsoleWorkflowNodeResponse;
import com.example.batch.console.web.response.ConsoleWorkflowNodeRunResponse;
import com.example.batch.console.web.response.ConsoleWorkflowRunResponse;
import com.example.batch.console.web.response.ConsoleWorkerRegistryResponse;
import com.example.batch.console.mapper.AlertEventMapper;
import com.example.batch.console.mapper.AuditLogMapper;
import com.example.batch.console.mapper.ApprovalCommandMapper;
import com.example.batch.console.mapper.DeadLetterTaskMapper;
import com.example.batch.console.mapper.FileErrorRecordMapper;
import com.example.batch.console.mapper.FileArrivalGroupMapper;
import com.example.batch.console.mapper.FileChannelConfigMapper;
import com.example.batch.console.mapper.FileDispatchRecordMapper;
import com.example.batch.console.mapper.FilePipelineMapper;
import com.example.batch.console.mapper.FilePipelineStepRunMapper;
import com.example.batch.console.mapper.FileRecordMapper;
import com.example.batch.console.mapper.FileTemplateConfigMapper;
import com.example.batch.console.mapper.JobInstanceMapper;
import com.example.batch.console.mapper.JobStepInstanceMapper;
import com.example.batch.console.mapper.JobDefinitionMapper;
import com.example.batch.console.mapper.OutboxRetryLogMapper;
import com.example.batch.console.mapper.OutboxDeliveryLogMapper;
import com.example.batch.console.mapper.PendingCatchUpMapper;
import com.example.batch.console.mapper.RetryScheduleMapper;
import com.example.batch.console.mapper.WorkerRegistryMapper;
import com.example.batch.console.mapper.WorkflowDefinitionMapper;
import com.example.batch.console.mapper.WorkflowNodeMapper;
import com.example.batch.console.mapper.WorkflowEdgeMapper;
import com.example.batch.console.mapper.WorkflowRunMapper;
import com.example.batch.console.mapper.WorkflowNodeRunMapper;
import com.example.batch.console.mapper.ConsoleAiAuditLogMapper;
import com.example.batch.console.support.ConsoleTenantGuard;
import com.example.batch.console.web.query.AlertEventQueryRequest;
import com.example.batch.console.web.query.ApprovalCommandQueryRequest;
import com.example.batch.console.web.query.AuditLogQueryRequest;
import com.example.batch.console.web.query.DeadLetterQueryRequest;
import com.example.batch.console.web.query.FileChannelQueryRequest;
import com.example.batch.console.web.query.FileErrorRecordQueryRequest;
import com.example.batch.console.web.query.FileArrivalGroupQueryRequest;
import com.example.batch.console.web.query.FileChainQueryRequest;
import com.example.batch.console.web.query.FileDispatchRecordQueryRequest;
import com.example.batch.console.web.query.FilePipelineQueryRequest;
import com.example.batch.console.web.query.FilePipelineStepQueryRequest;
import com.example.batch.console.web.query.FileTemplateQueryRequest;
import com.example.batch.console.web.query.JobInstanceQueryRequest;
import com.example.batch.console.web.query.JobStepInstanceQueryRequest;
import com.example.batch.console.web.query.JobDefinitionQueryRequest;
import com.example.batch.console.web.query.OutboxRetryLogQueryRequest;
import com.example.batch.console.web.query.OutboxDeliveryLogQueryRequest;
import com.example.batch.console.web.query.PendingCatchUpQueryRequest;
import com.example.batch.console.web.query.RetryScheduleQueryRequest;
import com.example.batch.console.web.query.WorkerRegistryQueryRequest;
import com.example.batch.console.web.query.WorkflowDefinitionQueryRequest;
import com.example.batch.console.web.query.WorkflowNodeQueryRequest;
import com.example.batch.console.web.query.WorkflowEdgeQueryRequest;
import com.example.batch.console.web.query.WorkflowRunQueryRequest;
import com.example.batch.console.web.query.WorkflowNodeRunQueryRequest;
import com.example.batch.console.web.query.WorkflowTopologyQueryRequest;
import com.example.batch.console.web.query.ConsoleAiAuditLogQueryRequest;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.model.PageRequest;
import com.example.batch.common.utils.ContentMaskingUtils;
import com.example.batch.common.utils.ConsoleTextSanitizer;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class DefaultConsoleQueryApplicationService implements ConsoleQueryApplicationService {

    private static final int DEFAULT_QUERY_LIMIT = 100;

    private final ConsoleTenantGuard tenantGuard;
    private final AuditLogMapper auditLogMapper;
    private final AlertEventMapper alertEventMapper;
    private final ApprovalCommandMapper approvalCommandMapper;
    private final JobDefinitionMapper jobDefinitionMapper;
    private final JobInstanceMapper jobInstanceMapper;
    private final JobStepInstanceMapper jobStepInstanceMapper;
    private final OutboxRetryLogMapper outboxRetryLogMapper;
    private final OutboxDeliveryLogMapper outboxDeliveryLogMapper;
    private final FileRecordMapper fileRecordMapper;
    private final FileArrivalGroupMapper fileArrivalGroupMapper;
    private final FileErrorRecordMapper fileErrorRecordMapper;
    private final FilePipelineMapper filePipelineMapper;
    private final FilePipelineStepRunMapper filePipelineStepRunMapper;
    private final FileDispatchRecordMapper fileDispatchRecordMapper;
    private final FileChannelConfigMapper fileChannelConfigMapper;
    private final FileTemplateConfigMapper fileTemplateConfigMapper;
    private final DeadLetterTaskMapper deadLetterTaskMapper;
    private final RetryScheduleMapper retryScheduleMapper;
    private final PendingCatchUpMapper pendingCatchUpMapper;
    private final WorkerRegistryMapper workerRegistryMapper;
    private final WorkflowDefinitionMapper workflowDefinitionMapper;
    private final WorkflowNodeMapper workflowNodeMapper;
    private final WorkflowEdgeMapper workflowEdgeMapper;
    private final WorkflowRunMapper workflowRunMapper;
    private final WorkflowNodeRunMapper workflowNodeRunMapper;
    private final ConsoleAiAuditLogMapper consoleAiAuditLogMapper;
    private final BatchSecurityProperties batchSecurityProperties;

    @Override
    public List<ConsoleAuditLogResponse> auditLogs(AuditLogQueryRequest request) {
        AuditLogQuery query = new AuditLogQuery();
        query.setTenantId(resolveTenant(request.getTenantId()));
        query.setOperationType(request.getOperationType());
        query.setTraceId(request.getTraceId());
        query.setFromTime(parseInstant(request.getFromTime(), "fromTime"));
        query.setToTime(parseInstant(request.getToTime(), "toTime"));
        return auditLogMapper.selectByQuery(query).stream().map(this::toAuditLogResponse).toList();
    }

    @Override
    public List<ConsoleFileRecordResponse> fileChains(FileChainQueryRequest request) {
        FileRecordQuery query = new FileRecordQuery(
                resolveTenant(request.getTenantId()),
                request.getBizType() == null || request.getBizType().isBlank() ? request.getPipelineType() : request.getBizType(),
                request.getFileStatus(),
                parseLong(request.getFileId(), "fileId"),
                request.getFileName(),
                request.getTraceId(),
                parseInstant(request.getFromTime(), "fromTime"),
                parseInstant(request.getToTime(), "toTime"),
                new PageRequest(1, 20)
        );
        return fileRecordMapper.selectByQuery(query).stream().map(this::toFileRecordResponse).toList();
    }

    @Override
    public List<ConsoleFilePipelineResponse> filePipelines(FilePipelineQueryRequest request) {
        return filePipelineMapper.selectByQuery(
                resolveTenant(request.getTenantId()),
                request.getFileId(),
                request.getPipelineInstanceId(),
                request.getPipelineType(),
                request.getRunStatus(),
                request.getTraceId(),
                parseInstant(request.getFromTime(), "fromTime"),
                parseInstant(request.getToTime(), "toTime")
        ).stream().map(this::toFilePipelineResponse).toList();
    }

    @Override
    public List<ConsoleFilePipelineStepResponse> filePipelineSteps(FilePipelineStepQueryRequest request) {
        return filePipelineStepRunMapper.selectByQuery(
                request.getPipelineInstanceId(),
                request.getStepCode(),
                request.getStageCode(),
                request.getStepStatus()
        ).stream().map(this::toFilePipelineStepResponse).toList();
    }

    @Override
    public List<ConsoleFileDispatchRecordResponse> fileDispatchRecords(FileDispatchRecordQueryRequest request) {
        return fileDispatchRecordMapper.selectByQuery(
                resolveTenant(request.getTenantId()),
                request.getFileId(),
                request.getChannelCode(),
                request.getDispatchStatus(),
                request.getReceiptStatus(),
                parseInstant(request.getFromTime(), "fromTime"),
                parseInstant(request.getToTime(), "toTime")
        ).stream().map(this::toFileDispatchRecordResponse).toList();
    }

    @Override
    public List<ConsoleFileChannelResponse> fileChannels(FileChannelQueryRequest request) {
        return fileChannelConfigMapper.selectByQuery(
                resolveTenant(request.getTenantId()),
                request.getChannelCode(),
                request.getChannelType(),
                request.getEnabled()
        ).stream().map(this::toFileChannelResponse).toList();
    }

    @Override
    public List<ConsoleFileTemplateResponse> fileTemplates(FileTemplateQueryRequest request) {
        return fileTemplateConfigMapper.selectByQuery(
                resolveTenant(request.getTenantId()),
                request.getTemplateCode(),
                request.getTemplateType(),
                request.getEnabled()
        ).stream().map(this::toFileTemplateResponse).toList();
    }

    @Override
    public List<ConsoleJobDefinitionResponse> jobDefinitions(JobDefinitionQueryRequest request) {
        return jobDefinitionMapper.selectByQuery(new JobDefinitionQuery(
                resolveTenant(request.getTenantId()),
                request.getJobCode(),
                request.getJobType(),
                request.getEnabled(),
                null
        )).stream().map(this::toJobDefinitionResponse).toList();
    }

    @Override
    public List<ConsoleOutboxRetryLogResponse> outboxRetries(OutboxRetryLogQueryRequest request) {
        return outboxRetryLogMapper.selectByQuery(new OutboxRetryLogQuery(
                resolveTenant(request.getTenantId()),
                request.getRetryStatus(),
                request.getEventKey()
        )).stream().map(this::toOutboxRetryResponse).toList();
    }

    @Override
    public List<ConsoleOutboxDeliveryLogResponse> outboxDeliveries(OutboxDeliveryLogQueryRequest request) {
        return outboxDeliveryLogMapper.selectByQuery(new OutboxDeliveryLogQuery(
                resolveTenant(request.getTenantId()),
                request.getDeliveryStatus(),
                request.getEventType(),
                request.getEventKey()
        )).stream().map(this::toOutboxDeliveryResponse).toList();
    }

    @Override
    public List<ConsoleFileArrivalGroupResponse> fileArrivalGroups(FileArrivalGroupQueryRequest request) {
        return fileArrivalGroupMapper.selectByQuery(new FileArrivalGroupQuery(
                resolveTenant(request.getTenantId()),
                request.getFileGroupCode(),
                request.getArrivalState(),
                null,
                null
        )).stream().map(this::toFileArrivalGroupResponse).toList();
    }

    @Override
    public List<ConsoleFileErrorRecordResponse> fileErrorRecords(FileErrorRecordQueryRequest request) {
        List<FileErrorRecordEntity> rows = fileErrorRecordMapper.selectByQuery(new FileErrorRecordQuery(
                resolveTenant(request.getTenantId()),
                request.getFileId(),
                request.getErrorStage(),
                request.getErrorCode(),
                request.getSkipped()
        ));
        applyErrorLineMasking(resolveTenant(request.getTenantId()), request.getFileId(), rows);
        return rows.stream().map(this::toFileErrorRecordResponse).toList();
    }

    private boolean truthy(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return false;
        }
        return "true".equalsIgnoreCase(String.valueOf(value));
    }

    private void applyErrorLineMasking(String tenantId, Long fileId, List<FileErrorRecordEntity> rows) {
        if (batchSecurityProperties.isTestingOpen() || rows == null || rows.isEmpty() || fileId == null || !StringUtils.hasText(tenantId)) {
            return;
        }
        String templateCode = fileRecordMapper.selectTemplateCodeByFileId(tenantId, fileId);
        if (!StringUtils.hasText(templateCode)) {
            return;
        }
        Map<String, Object> sec = fileTemplateConfigMapper.selectSecurityFlagsByTemplateCode(tenantId, templateCode);
        if (sec == null || !truthy(sec.get("error_line_masking_enabled"))) {
            return;
        }
        String ruleSet = sec.get("masking_rule_set") == null ? null : String.valueOf(sec.get("masking_rule_set"));
        for (FileErrorRecordEntity row : rows) {
            if (row.getErrorMessage() != null) {
                row.setErrorMessage(ContentMaskingUtils.maskPlainText(row.getErrorMessage(), ruleSet));
            }
            if (row.getRawRecord() != null) {
                row.setRawRecord(ContentMaskingUtils.maskPlainText(row.getRawRecord(), ruleSet));
            }
        }
    }

    @Override
    public List<ConsoleJobInstanceResponse> jobInstances(JobInstanceQueryRequest request) {
        JobInstanceQuery query = new JobInstanceQuery(
                resolveTenant(request.getTenantId()),
                request.getJobCode(),
                request.getInstanceStatus(),
                request.getInstanceNo(),
                request.getBizDate(),
                request.getTraceId(),
                new PageRequest(1, 20)
        );
        return jobInstanceMapper.selectByQuery(query).stream().map(this::toJobInstanceResponse).toList();
    }

    @Override
    public List<ConsoleJobStepInstanceResponse> jobStepInstances(JobStepInstanceQueryRequest request) {
        return jobStepInstanceMapper.selectByQuery(new JobStepInstanceQuery(
                resolveTenant(request.getTenantId()),
                request.getJobInstanceId(),
                request.getJobPartitionId(),
                request.getStepCode(),
                request.getStepStatus()
        )).stream().map(this::toJobStepInstanceResponse).toList();
    }

    @Override
    public List<ConsoleWorkflowDefinitionResponse> workflowDefinitions(WorkflowDefinitionQueryRequest request) {
        return workflowDefinitionMapper.selectByQuery(new WorkflowDefinitionQuery(
                resolveTenant(request.getTenantId()),
                request.getWorkflowCode(),
                request.getWorkflowType(),
                request.getVersion(),
                request.getEnabled()
        )).stream().map(this::toWorkflowDefinitionResponse).toList();
    }

    @Override
    public List<ConsoleWorkflowNodeResponse> workflowNodes(WorkflowNodeQueryRequest request) {
        return workflowNodeMapper.selectByQuery(new WorkflowNodeQuery(
                resolveTenant(request.getTenantId()),
                request.getWorkflowDefinitionId(),
                request.getWorkflowCode(),
                request.getNodeCode(),
                request.getNodeType(),
                request.getEnabled()
        )).stream().map(this::toWorkflowNodeResponse).toList();
    }

    @Override
    public List<ConsoleWorkflowEdgeResponse> workflowEdges(WorkflowEdgeQueryRequest request) {
        return workflowEdgeMapper.selectByQuery(new WorkflowEdgeQuery(
                resolveTenant(request.getTenantId()),
                request.getWorkflowDefinitionId(),
                request.getWorkflowCode(),
                request.getFromNodeCode(),
                request.getToNodeCode(),
                request.getEdgeType(),
                request.getEnabled()
        )).stream().map(this::toWorkflowEdgeResponse).toList();
    }

    @Override
    public List<ConsoleWorkflowRunResponse> workflowRuns(WorkflowRunQueryRequest request) {
        return workflowRunMapper.selectByQuery(new WorkflowRunQuery(
                resolveTenant(request.getTenantId()),
                request.getWorkflowDefinitionId(),
                request.getRelatedJobInstanceId(),
                request.getRunStatus(),
                request.getCurrentNodeCode(),
                request.getTraceId()
        )).stream().map(this::toWorkflowRunResponse).toList();
    }

    @Override
    public List<ConsoleWorkflowNodeRunResponse> workflowNodeRuns(WorkflowNodeRunQueryRequest request) {
        return workflowNodeRunMapper.selectByQuery(new WorkflowNodeRunQuery(
                request.getWorkflowRunId(),
                request.getNodeCode(),
                request.getNodeStatus()
        )).stream().map(this::toWorkflowNodeRunResponse).toList();
    }

    @Override
    public WorkflowTopologyView workflowTopology(WorkflowTopologyQueryRequest request) {
        WorkflowTopologyView view = new WorkflowTopologyView();
        WorkflowDefinitionQuery definitionQuery = new WorkflowDefinitionQuery(
                resolveTenant(request.getTenantId()),
                request.getWorkflowCode(),
                null,
                request.getVersion(),
                true
        );
        List<WorkflowDefinitionEntity> definitions = workflowDefinitionMapper.selectByQuery(definitionQuery);
        WorkflowDefinitionEntity selectedDefinition = definitions.isEmpty() ? null : definitions.get(0);
        view.setWorkflowDefinition(selectedDefinition);
        if (selectedDefinition == null) {
            return view;
        }
        view.setNodes(workflowNodeMapper.selectByQuery(new WorkflowNodeQuery(
                resolveTenant(request.getTenantId()),
                selectedDefinition.getId(),
                null,
                null,
                null,
                true
        )));
        view.setEdges(workflowEdgeMapper.selectByQuery(new WorkflowEdgeQuery(
                resolveTenant(request.getTenantId()),
                selectedDefinition.getId(),
                null,
                null,
                null,
                null,
                true
        )));
        if (request.getWorkflowRunId() != null) {
            WorkflowRunEntity run = workflowRunMapper.selectByQuery(new WorkflowRunQuery(
                    resolveTenant(request.getTenantId()),
                    selectedDefinition.getId(),
                    null,
                    null,
                    null,
                    null
            )).stream()
                    .filter(item -> request.getWorkflowRunId().equals(item.getId()))
                    .findFirst()
                    .orElse(null);
            if (run != null) {
                view.getWorkflowRuns().add(run);
                view.getNodeRuns().addAll(workflowNodeRunMapper.selectByQuery(new WorkflowNodeRunQuery(
                        request.getWorkflowRunId(),
                        null,
                        null
                )));
            }
        }
        return view;
    }

    @Override
    public List<AiAuditLogResponse> aiAuditLogs(ConsoleAiAuditLogQueryRequest request) {
        return consoleAiAuditLogMapper.selectByQuery(new ConsoleAiAuditLogQuery(
                resolveTenant(request.getTenantId()),
                request.getSessionId(),
                request.getOperatorId(),
                request.getPromptCategory(),
                request.getPromptDecision(),
                parseInstant(request.getFromTime(), "fromTime"),
                parseInstant(request.getToTime(), "toTime")
        )).stream().map(entity -> {
            AiAuditLogResponse row = new AiAuditLogResponse();
            row.setId(entity.getId());
            row.setTenantId(entity.getTenantId());
            row.setRequestId(entity.getRequestId());
            row.setTraceId(entity.getTraceId());
            row.setSessionId(entity.getSessionId());
            row.setOperatorId(entity.getOperatorId());
            row.setPromptCategory(entity.getPromptCategory());
            row.setPromptDecision(entity.getPromptDecision());
            row.setModelName(entity.getModelName());
            row.setPromptPreview(ConsoleTextSanitizer.safeDisplay(entity.getPromptPreview(), 512));
            row.setResponsePreview(ConsoleTextSanitizer.safeDisplay(entity.getResponsePreview(), 512));
            row.setRefusalReason(ConsoleTextSanitizer.safeDisplay(entity.getRefusalReason(), 512));
            row.setCreatedAt(entity.getCreatedAt());
            return row;
        }).toList();
    }

    @Override
    public List<ConsoleDeadLetterTaskResponse> deadLetters(DeadLetterQueryRequest request) {
        return deadLetterTaskMapper.selectByQuery(new DeadLetterTaskQuery(
                resolveTenant(request.getTenantId()),
                request.getSourceType(),
                request.getReplayStatus(),
                request.getTraceId()
        )).stream().map(this::toDeadLetterTaskResponse).toList();
    }

    @Override
    public List<ConsoleRetryScheduleResponse> retries(RetryScheduleQueryRequest request) {
        return retryScheduleMapper.selectByQuery(new RetryScheduleQuery(
                resolveTenant(request.getTenantId()),
                request.getRelatedType(),
                request.getRetryPolicy(),
                request.getRetryStatus()
        )).stream().map(this::toRetryScheduleResponse).toList();
    }

    @Override
    public List<ConsolePendingCatchUpResponse> pendingCatchUps(PendingCatchUpQueryRequest request) {
        return pendingCatchUpMapper.selectByQuery(new PendingCatchUpQuery(
                resolveTenant(request.getTenantId()),
                request.getJobCode(),
                request.getRequestId()
        )).stream().map(this::toPendingCatchUpResponse).toList();
    }

    @Override
    public List<ConsoleWorkerRegistryResponse> workers(WorkerRegistryQueryRequest request) {
        return workerRegistryMapper.selectByQuery(new WorkerRegistryQuery(
                resolveTenant(request.getTenantId()),
                request.getWorkerGroup(),
                request.getStatus()
        )).stream().map(this::toWorkerRegistryResponse).toList();
    }

    @Override
    public List<ConsoleAlertEventResponse> alertEvents(AlertEventQueryRequest request) {
        int limit = request.getLimit() == null ? DEFAULT_QUERY_LIMIT : request.getLimit();
        return alertEventMapper.selectByQuery(new AlertEventQuery(
                resolveTenant(request.getTenantId()),
                request.getSeverity(),
                request.getStatus(),
                request.getAlertType(),
                limit
        )).stream().map(this::toAlertEventResponse).toList();
    }

    @Override
    public List<ConsoleApprovalCommandResponse> approvals(ApprovalCommandQueryRequest request) {
        int limit = request.getLimit() == null ? DEFAULT_QUERY_LIMIT : request.getLimit();
        ApprovalCommandQuery query = new ApprovalCommandQuery();
        query.setTenantId(resolveTenant(request.getTenantId()));
        query.setApprovalNo(request.getApprovalNo());
        query.setApprovalType(request.getApprovalType());
        query.setActionType(request.getActionType());
        query.setApprovalStatus(request.getApprovalStatus());
        query.setLimit(limit);
        return approvalCommandMapper.selectByQuery(query).stream().map(this::toApprovalResponse).toList();
    }

    private ConsoleAuditLogResponse toAuditLogResponse(Map<String, Object> row) {
        return new ConsoleAuditLogResponse(
                longValue(row, "id"),
                stringValue(row, "tenant_id"),
                longValue(row, "file_id"),
                stringValue(row, "operation_type"),
                stringValue(row, "operation_result"),
                stringValue(row, "operator_type"),
                stringValue(row, "operator_id"),
                stringValue(row, "trace_id"),
                stringValue(row, "evidence_ref"),
                stringValue(row, "detail_summary"),
                instantValue(row, "created_at")
        );
    }

    private ConsoleFilePipelineResponse toFilePipelineResponse(Map<String, Object> row) {
        return new ConsoleFilePipelineResponse(
                longValue(row, "id"),
                stringValue(row, "tenant_id"),
                longValue(row, "pipeline_definition_id"),
                stringValue(row, "pipeline_code"),
                stringValue(row, "pipeline_type"),
                longValue(row, "file_id"),
                longValue(row, "related_job_instance_id"),
                stringValue(row, "current_stage"),
                stringValue(row, "last_success_stage"),
                stringValue(row, "run_status"),
                stringValue(row, "trace_id"),
                instantValue(row, "started_at"),
                instantValue(row, "finished_at"),
                instantValue(row, "created_at"),
                instantValue(row, "updated_at")
        );
    }

    private ConsoleFilePipelineStepResponse toFilePipelineStepResponse(Map<String, Object> row) {
        return new ConsoleFilePipelineStepResponse(
                longValue(row, "id"),
                longValue(row, "pipeline_instance_id"),
                stringValue(row, "step_code"),
                stringValue(row, "stage_code"),
                intValue(row, "run_seq"),
                stringValue(row, "step_status"),
                stringValue(row, "input_summary"),
                stringValue(row, "output_summary"),
                stringValue(row, "error_code"),
                stringValue(row, "error_message"),
                intValue(row, "retry_count"),
                longValue(row, "duration_ms"),
                instantValue(row, "started_at"),
                instantValue(row, "finished_at")
        );
    }

    private ConsoleFileDispatchRecordResponse toFileDispatchRecordResponse(Map<String, Object> row) {
        return new ConsoleFileDispatchRecordResponse(
                longValue(row, "id"),
                stringValue(row, "tenant_id"),
                longValue(row, "file_id"),
                longValue(row, "pipeline_instance_id"),
                stringValue(row, "channel_code"),
                stringValue(row, "dispatch_target"),
                stringValue(row, "dispatch_status"),
                intValue(row, "dispatch_attempt"),
                stringValue(row, "receipt_code"),
                stringValue(row, "receipt_status"),
                stringValue(row, "external_request_id"),
                stringValue(row, "error_code"),
                stringValue(row, "error_message"),
                instantValue(row, "dispatched_at"),
                instantValue(row, "ack_at"),
                instantValue(row, "created_at"),
                instantValue(row, "updated_at")
        );
    }

    private ConsoleFileChannelResponse toFileChannelResponse(Map<String, Object> row) {
        return new ConsoleFileChannelResponse(
                longValue(row, "id"),
                stringValue(row, "tenant_id"),
                stringValue(row, "channel_code"),
                stringValue(row, "channel_name"),
                stringValue(row, "channel_type"),
                stringValue(row, "target_endpoint"),
                stringValue(row, "auth_type"),
                stringValue(row, "config_json"),
                stringValue(row, "receipt_policy"),
                intValue(row, "timeout_seconds"),
                booleanValue(row, "enabled"),
                instantValue(row, "created_at"),
                instantValue(row, "updated_at")
        );
    }

    private ConsoleFileTemplateResponse toFileTemplateResponse(Map<String, Object> row) {
        return new ConsoleFileTemplateResponse(
                longValue(row, "id"),
                stringValue(row, "tenant_id"),
                stringValue(row, "template_code"),
                stringValue(row, "template_name"),
                stringValue(row, "template_type"),
                stringValue(row, "biz_type"),
                stringValue(row, "file_format_type"),
                stringValue(row, "charset"),
                stringValue(row, "target_charset"),
                booleanValue(row, "with_bom"),
                stringValue(row, "line_separator"),
                stringValue(row, "delimiter"),
                stringValue(row, "quote_char"),
                stringValue(row, "escape_char"),
                intValue(row, "record_length"),
                intValue(row, "header_rows"),
                intValue(row, "footer_rows"),
                stringValue(row, "header_template"),
                stringValue(row, "trailer_template"),
                stringValue(row, "checksum_type"),
                stringValue(row, "compress_type"),
                stringValue(row, "encrypt_type"),
                stringValue(row, "naming_rule"),
                stringValue(row, "field_mappings"),
                stringValue(row, "validation_rule_set"),
                stringValue(row, "default_query_code"),
                stringValue(row, "default_query_sql"),
                stringValue(row, "query_param_schema"),
                booleanValue(row, "streaming_enabled"),
                intValue(row, "page_size"),
                intValue(row, "fetch_size"),
                intValue(row, "chunk_size"),
                booleanValue(row, "preview_masking_enabled"),
                booleanValue(row, "error_line_masking_enabled"),
                booleanValue(row, "log_masking_enabled"),
                booleanValue(row, "content_encryption_enabled"),
                stringValue(row, "encryption_key_ref"),
                booleanValue(row, "download_requires_approval"),
                stringValue(row, "masking_rule_set"),
                booleanValue(row, "enabled"),
                intValue(row, "version"),
                stringValue(row, "description"),
                stringValue(row, "created_by"),
                stringValue(row, "updated_by"),
                instantValue(row, "created_at"),
                instantValue(row, "updated_at")
        );
    }

    private ConsoleOutboxRetryLogResponse toOutboxRetryResponse(Map<String, Object> row) {
        return new ConsoleOutboxRetryLogResponse(
                longValue(row, "id"),
                stringValue(row, "tenant_id"),
                stringValue(row, "event_type"),
                stringValue(row, "event_key"),
                stringValue(row, "retry_status"),
                intValue(row, "retry_count"),
                stringValue(row, "retry_policy"),
                instantValue(row, "next_retry_at"),
                instantValue(row, "created_at"),
                instantValue(row, "updated_at")
        );
    }

    private ConsoleOutboxDeliveryLogResponse toOutboxDeliveryResponse(Map<String, Object> row) {
        return new ConsoleOutboxDeliveryLogResponse(
                longValue(row, "id"),
                stringValue(row, "tenant_id"),
                stringValue(row, "event_type"),
                stringValue(row, "event_key"),
                stringValue(row, "delivery_status"),
                stringValue(row, "target_topic"),
                intValue(row, "delivery_attempt"),
                stringValue(row, "error_message"),
                instantValue(row, "created_at"),
                instantValue(row, "updated_at")
        );
    }

    private ConsoleApprovalCommandResponse toApprovalResponse(com.example.batch.console.domain.entity.ApprovalCommandEntity entity) {
        return new ConsoleApprovalCommandResponse(
                entity.getId(),
                display(entity.getTenantId()),
                display(entity.getApprovalNo()),
                display(entity.getApprovalType()),
                display(entity.getActionType()),
                display(entity.getTargetType()),
                display(entity.getTargetId()),
                display(entity.getPayloadJson()),
                display(entity.getApprovalStatus()),
                display(entity.getRequesterId()),
                display(entity.getApproverId()),
                display(entity.getRejectionReason()),
                display(entity.getApprovalReason()),
                display(entity.getSourceTraceId()),
                display(entity.getSourceIdempotencyKey()),
                entity.getApprovedAt(),
                entity.getExecutedAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private ConsoleAlertEventResponse toAlertEventResponse(AlertEventEntity entity) {
        return new ConsoleAlertEventResponse(
                entity.getId(),
                display(entity.getTenantId()),
                display(entity.getServiceName()),
                display(entity.getAlertType()),
                display(entity.getSeverity()),
                display(entity.getTitle()),
                display(entity.getDetailJson()),
                display(entity.getDedupFingerprint()),
                entity.getOccurrenceCount(),
                entity.getFirstSeenAt(),
                entity.getLastSeenAt(),
                display(entity.getTraceId()),
                display(entity.getStatus()),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private ConsoleFileRecordResponse toFileRecordResponse(FileRecordEntity entity) {
        return new ConsoleFileRecordResponse(
                entity.getId(),
                display(entity.getTenantId()),
                display(entity.getBizType()),
                display(entity.getFileName()),
                display(entity.getFileStatus()),
                entity.getBizDate(),
                display(entity.getTraceId()),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private ConsoleJobDefinitionResponse toJobDefinitionResponse(JobDefinitionEntity entity) {
        return new ConsoleJobDefinitionResponse(
                entity.getId(),
                display(entity.getTenantId()),
                display(entity.getJobCode()),
                display(entity.getJobName()),
                display(entity.getJobType()),
                display(entity.getQueueCode()),
                display(entity.getWorkerGroup()),
                display(entity.getScheduleType()),
                display(entity.getScheduleExpr()),
                display(entity.getCalendarCode()),
                display(entity.getWindowCode()),
                display(entity.getRetryPolicy()),
                entity.getRetryMaxCount(),
                entity.getTimeoutSeconds(),
                display(entity.getShardStrategy()),
                display(entity.getExecutionHandler()),
                display(entity.getParamSchema()),
                display(entity.getDefaultParams()),
                entity.getEnabled(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private ConsoleJobInstanceResponse toJobInstanceResponse(JobInstanceEntity entity) {
        return new ConsoleJobInstanceResponse(
                entity.getId(),
                display(entity.getTenantId()),
                display(entity.getJobCode()),
                display(entity.getInstanceNo()),
                entity.getBizDate(),
                display(entity.getTriggerType()),
                display(entity.getInstanceStatus()),
                display(entity.getBatchNo()),
                display(entity.getOperatorId()),
                entity.getRerunFlag(),
                entity.getRetryFlag(),
                display(entity.getRerunReason()),
                entity.getRelatedFileId(),
                entity.getParentInstanceId(),
                display(entity.getQueueCode()),
                display(entity.getWorkerGroup()),
                entity.getPriority(),
                display(entity.getTraceId()),
                display(entity.getParamsSnapshot()),
                display(entity.getResultSummary()),
                entity.getDeadlineAt(),
                entity.getExpectedDurationSeconds(),
                entity.getSlaAlertedAt(),
                entity.getStartedAt(),
                entity.getFinishedAt()
        );
    }

    private ConsoleJobStepInstanceResponse toJobStepInstanceResponse(JobStepInstanceEntity entity) {
        return new ConsoleJobStepInstanceResponse(
                entity.getId(),
                display(entity.getTenantId()),
                entity.getJobInstanceId(),
                entity.getJobPartitionId(),
                entity.getJobTaskId(),
                display(entity.getStepCode()),
                display(entity.getStepType()),
                display(entity.getStepStatus()),
                entity.getRetryCount(),
                entity.getRelatedFileId(),
                display(entity.getResultSummary()),
                display(entity.getErrorCode()),
                display(entity.getErrorMessage()),
                entity.getStartedAt(),
                entity.getFinishedAt()
        );
    }

    private ConsoleWorkflowDefinitionResponse toWorkflowDefinitionResponse(WorkflowDefinitionEntity entity) {
        return new ConsoleWorkflowDefinitionResponse(
                entity.getId(),
                display(entity.getTenantId()),
                display(entity.getWorkflowCode()),
                display(entity.getWorkflowName()),
                display(entity.getWorkflowType()),
                entity.getVersion(),
                entity.getEnabled(),
                display(entity.getDescription()),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private ConsoleWorkflowNodeResponse toWorkflowNodeResponse(WorkflowNodeEntity entity) {
        return new ConsoleWorkflowNodeResponse(
                entity.getId(),
                entity.getWorkflowDefinitionId(),
                display(entity.getNodeCode()),
                display(entity.getNodeName()),
                display(entity.getNodeType()),
                display(entity.getRelatedJobCode()),
                display(entity.getRelatedPipelineCode()),
                display(entity.getWorkerGroup()),
                display(entity.getWindowCode()),
                entity.getNodeOrder(),
                display(entity.getRetryPolicy()),
                entity.getRetryMaxCount(),
                entity.getTimeoutSeconds(),
                display(entity.getNodeParams()),
                entity.getEnabled(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private ConsoleWorkflowEdgeResponse toWorkflowEdgeResponse(WorkflowEdgeEntity entity) {
        return new ConsoleWorkflowEdgeResponse(
                entity.getId(),
                entity.getWorkflowDefinitionId(),
                display(entity.getFromNodeCode()),
                display(entity.getToNodeCode()),
                display(entity.getEdgeType()),
                display(entity.getConditionExpr()),
                entity.getEnabled(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private ConsoleWorkflowRunResponse toWorkflowRunResponse(WorkflowRunEntity entity) {
        return new ConsoleWorkflowRunResponse(
                entity.getId(),
                display(entity.getTenantId()),
                entity.getWorkflowDefinitionId(),
                entity.getRelatedJobInstanceId(),
                entity.getBizDate(),
                display(entity.getRunStatus()),
                display(entity.getCurrentNodeCode()),
                display(entity.getTraceId()),
                entity.getStartedAt(),
                entity.getFinishedAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private ConsoleWorkflowNodeRunResponse toWorkflowNodeRunResponse(WorkflowNodeRunEntity entity) {
        return new ConsoleWorkflowNodeRunResponse(
                entity.getId(),
                entity.getWorkflowRunId(),
                display(entity.getNodeCode()),
                display(entity.getNodeType()),
                entity.getRunSeq(),
                display(entity.getNodeStatus()),
                entity.getRetryCount(),
                display(entity.getErrorCode()),
                display(entity.getErrorMessage()),
                entity.getStartedAt(),
                entity.getFinishedAt(),
                entity.getDurationMs()
        );
    }

    private ConsoleDeadLetterTaskResponse toDeadLetterTaskResponse(DeadLetterTaskEntity entity) {
        return new ConsoleDeadLetterTaskResponse(
                entity.getId(),
                display(entity.getTenantId()),
                display(entity.getSourceType()),
                entity.getSourceId(),
                display(entity.getDeadLetterReason()),
                display(entity.getPayloadRef()),
                display(entity.getReplayStatus()),
                entity.getReplayCount(),
                entity.getLastReplayAt(),
                display(entity.getLastReplayResult()),
                display(entity.getTraceId()),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private ConsoleRetryScheduleResponse toRetryScheduleResponse(RetryScheduleEntity entity) {
        return new ConsoleRetryScheduleResponse(
                entity.getId(),
                display(entity.getTenantId()),
                display(entity.getRelatedType()),
                entity.getRelatedId(),
                display(entity.getRetryPolicy()),
                entity.getRetryCount(),
                entity.getMaxRetryCount(),
                entity.getNextRetryAt(),
                display(entity.getRetryStatus()),
                display(entity.getDedupKey()),
                display(entity.getLastErrorCode()),
                display(entity.getLastErrorMessage()),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private ConsolePendingCatchUpResponse toPendingCatchUpResponse(PendingCatchUpEntity entity) {
        return new ConsolePendingCatchUpResponse(
                entity.getId(),
                display(entity.getTenantId()),
                display(entity.getRequestId()),
                display(entity.getJobCode()),
                entity.getBizDate(),
                display(entity.getRequestStatus()),
                display(entity.getTraceId()),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private ConsoleFileArrivalGroupResponse toFileArrivalGroupResponse(FileArrivalGroupEntity entity) {
        return new ConsoleFileArrivalGroupResponse(
                display(entity.getTenantId()),
                display(entity.getFileGroupCode()),
                display(entity.getWaitFileGroupMode()),
                display(entity.getRequiredFileSet()),
                display(entity.getArrivalTimeoutAction()),
                display(entity.getArrivalState()),
                entity.getExpectedArrivalTime(),
                entity.getLatestTolerableTime(),
                entity.getArrivedCount(),
                entity.getTriggeredCount(),
                entity.getTimeoutCount(),
                entity.getWaitingCount(),
                entity.getLastUpdatedAt()
        );
    }

    private ConsoleFileErrorRecordResponse toFileErrorRecordResponse(FileErrorRecordEntity entity) {
        return new ConsoleFileErrorRecordResponse(
                entity.getId(),
                display(entity.getTenantId()),
                entity.getFileId(),
                entity.getPipelineInstanceId(),
                entity.getPipelineStepRunId(),
                entity.getRecordNo(),
                display(entity.getErrorCode()),
                display(entity.getErrorMessage()),
                display(entity.getErrorStage()),
                entity.getSkipped(),
                display(entity.getSkipAction()),
                display(entity.getRawRecord()),
                entity.getCreatedAt()
        );
    }

    private ConsoleWorkerRegistryResponse toWorkerRegistryResponse(WorkerRegistryEntity entity) {
        return new ConsoleWorkerRegistryResponse(
                entity.getId(),
                display(entity.getTenantId()),
                display(entity.getWorkerCode()),
                display(entity.getWorkerGroup()),
                null,
                null,
                display(entity.getStatus()),
                entity.getHeartbeatAt(),
                null,
                entity.getDrainStartedAt(),
                entity.getDrainDeadlineAt()
        );
    }

    private String stringValue(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        return value == null ? null : ConsoleTextSanitizer.safeDisplay(String.valueOf(value));
    }

    private String display(String value) {
        return value == null ? null : ConsoleTextSanitizer.safeDisplay(value);
    }

    private Long longValue(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private Integer intValue(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private Boolean booleanValue(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private Instant instantValue(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof java.time.LocalDateTime localDateTime) {
            return localDateTime.toInstant(java.time.ZoneOffset.UTC);
        }
        if (value instanceof java.util.Date date) {
            return date.toInstant();
        }
        return Instant.parse(String.valueOf(value));
    }

    private Instant parseInstant(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, fieldName + " must be ISO-8601 datetime");
        }
    }

    private Long parseLong(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, fieldName + " must be a number");
        }
    }

    private String resolveTenant(String requestTenantId) {
        return tenantGuard.resolveTenant(requestTenantId);
    }
}

package com.example.batch.console.infrastructure;

import com.example.batch.console.application.ConsoleQueryApplicationService;
import com.example.batch.common.persistence.entity.AlertEventEntity;
import com.example.batch.console.domain.entity.DeadLetterTaskEntity;
import com.example.batch.console.domain.entity.FileErrorRecordEntity;
import com.example.batch.console.domain.entity.FileArrivalGroupEntity;
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
import com.example.batch.console.mapper.AlertEventMapper;
import com.example.batch.console.mapper.AuditLogMapper;
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
    public List<Map<String, Object>> auditLogs(AuditLogQueryRequest request) {
        AuditLogQuery query = new AuditLogQuery();
        query.setTenantId(resolveTenant(request.getTenantId()));
        query.setOperationType(request.getOperationType());
        query.setTraceId(request.getTraceId());
        query.setFromTime(parseInstant(request.getFromTime(), "fromTime"));
        query.setToTime(parseInstant(request.getToTime(), "toTime"));
        return auditLogMapper.selectByQuery(query);
    }

    @Override
    public List<FileRecordEntity> fileChains(FileChainQueryRequest request) {
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
        return fileRecordMapper.selectByQuery(query);
    }

    @Override
    public List<Map<String, Object>> filePipelines(FilePipelineQueryRequest request) {
        return filePipelineMapper.selectByQuery(
                resolveTenant(request.getTenantId()),
                request.getFileId(),
                request.getPipelineInstanceId(),
                request.getPipelineType(),
                request.getRunStatus(),
                request.getTraceId(),
                parseInstant(request.getFromTime(), "fromTime"),
                parseInstant(request.getToTime(), "toTime")
        );
    }

    @Override
    public List<Map<String, Object>> filePipelineSteps(FilePipelineStepQueryRequest request) {
        return filePipelineStepRunMapper.selectByQuery(
                request.getPipelineInstanceId(),
                request.getStepCode(),
                request.getStageCode(),
                request.getStepStatus()
        );
    }

    @Override
    public List<Map<String, Object>> fileDispatchRecords(FileDispatchRecordQueryRequest request) {
        return fileDispatchRecordMapper.selectByQuery(
                resolveTenant(request.getTenantId()),
                request.getFileId(),
                request.getChannelCode(),
                request.getDispatchStatus(),
                request.getReceiptStatus(),
                parseInstant(request.getFromTime(), "fromTime"),
                parseInstant(request.getToTime(), "toTime")
        );
    }

    @Override
    public List<Map<String, Object>> fileChannels(FileChannelQueryRequest request) {
        return fileChannelConfigMapper.selectByQuery(
                resolveTenant(request.getTenantId()),
                request.getChannelCode(),
                request.getChannelType(),
                request.getEnabled()
        );
    }

    @Override
    public List<Map<String, Object>> fileTemplates(FileTemplateQueryRequest request) {
        return fileTemplateConfigMapper.selectByQuery(
                resolveTenant(request.getTenantId()),
                request.getTemplateCode(),
                request.getTemplateType(),
                request.getEnabled()
        );
    }

    @Override
    public List<JobDefinitionEntity> jobDefinitions(JobDefinitionQueryRequest request) {
        return jobDefinitionMapper.selectByQuery(new JobDefinitionQuery(
                resolveTenant(request.getTenantId()),
                request.getJobCode(),
                request.getJobType(),
                request.getEnabled(),
                null
        ));
    }

    @Override
    public List<Map<String, Object>> outboxRetries(OutboxRetryLogQueryRequest request) {
        return outboxRetryLogMapper.selectByQuery(new OutboxRetryLogQuery(
                resolveTenant(request.getTenantId()),
                request.getRetryStatus(),
                request.getEventKey()
        ));
    }

    @Override
    public List<Map<String, Object>> outboxDeliveries(OutboxDeliveryLogQueryRequest request) {
        return outboxDeliveryLogMapper.selectByQuery(new OutboxDeliveryLogQuery(
                resolveTenant(request.getTenantId()),
                request.getDeliveryStatus(),
                request.getEventType(),
                request.getEventKey()
        ));
    }

    @Override
    public List<FileArrivalGroupEntity> fileArrivalGroups(FileArrivalGroupQueryRequest request) {
        return fileArrivalGroupMapper.selectByQuery(new FileArrivalGroupQuery(
                resolveTenant(request.getTenantId()),
                request.getFileGroupCode(),
                request.getArrivalState(),
                null,
                null
        ));
    }

    @Override
    public List<FileErrorRecordEntity> fileErrorRecords(FileErrorRecordQueryRequest request) {
        List<FileErrorRecordEntity> rows = fileErrorRecordMapper.selectByQuery(new FileErrorRecordQuery(
                resolveTenant(request.getTenantId()),
                request.getFileId(),
                request.getErrorStage(),
                request.getErrorCode(),
                request.getSkipped()
        ));
        applyErrorLineMasking(resolveTenant(request.getTenantId()), request.getFileId(), rows);
        return rows;
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
    public List<JobInstanceEntity> jobInstances(JobInstanceQueryRequest request) {
        JobInstanceQuery query = new JobInstanceQuery(
                resolveTenant(request.getTenantId()),
                request.getJobCode(),
                request.getInstanceStatus(),
                request.getInstanceNo(),
                request.getBizDate(),
                request.getTraceId(),
                new PageRequest(1, 20)
        );
        return jobInstanceMapper.selectByQuery(query);
    }

    @Override
    public List<JobStepInstanceEntity> jobStepInstances(JobStepInstanceQueryRequest request) {
        return jobStepInstanceMapper.selectByQuery(new JobStepInstanceQuery(
                resolveTenant(request.getTenantId()),
                request.getJobInstanceId(),
                request.getJobPartitionId(),
                request.getStepCode(),
                request.getStepStatus()
        ));
    }

    @Override
    public List<WorkflowDefinitionEntity> workflowDefinitions(WorkflowDefinitionQueryRequest request) {
        return workflowDefinitionMapper.selectByQuery(new WorkflowDefinitionQuery(
                resolveTenant(request.getTenantId()),
                request.getWorkflowCode(),
                request.getWorkflowType(),
                request.getVersion(),
                request.getEnabled()
        ));
    }

    @Override
    public List<WorkflowNodeEntity> workflowNodes(WorkflowNodeQueryRequest request) {
        return workflowNodeMapper.selectByQuery(new WorkflowNodeQuery(
                resolveTenant(request.getTenantId()),
                request.getWorkflowDefinitionId(),
                request.getWorkflowCode(),
                request.getNodeCode(),
                request.getNodeType(),
                request.getEnabled()
        ));
    }

    @Override
    public List<WorkflowEdgeEntity> workflowEdges(WorkflowEdgeQueryRequest request) {
        return workflowEdgeMapper.selectByQuery(new WorkflowEdgeQuery(
                resolveTenant(request.getTenantId()),
                request.getWorkflowDefinitionId(),
                request.getWorkflowCode(),
                request.getFromNodeCode(),
                request.getToNodeCode(),
                request.getEdgeType(),
                request.getEnabled()
        ));
    }

    @Override
    public List<WorkflowRunEntity> workflowRuns(WorkflowRunQueryRequest request) {
        return workflowRunMapper.selectByQuery(new WorkflowRunQuery(
                resolveTenant(request.getTenantId()),
                request.getWorkflowDefinitionId(),
                request.getRelatedJobInstanceId(),
                request.getRunStatus(),
                request.getCurrentNodeCode(),
                request.getTraceId()
        ));
    }

    @Override
    public List<WorkflowNodeRunEntity> workflowNodeRuns(WorkflowNodeRunQueryRequest request) {
        return workflowNodeRunMapper.selectByQuery(new WorkflowNodeRunQuery(
                request.getWorkflowRunId(),
                request.getNodeCode(),
                request.getNodeStatus()
        ));
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
    public List<Map<String, Object>> aiAuditLogs(ConsoleAiAuditLogQueryRequest request) {
        return consoleAiAuditLogMapper.selectByQuery(new ConsoleAiAuditLogQuery(
                resolveTenant(request.getTenantId()),
                request.getSessionId(),
                request.getOperatorId(),
                request.getPromptCategory(),
                request.getPromptDecision(),
                parseInstant(request.getFromTime(), "fromTime"),
                parseInstant(request.getToTime(), "toTime")
        )).stream().map(entity -> {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("id", entity.getId());
            row.put("tenantId", entity.getTenantId());
            row.put("requestId", entity.getRequestId());
            row.put("traceId", entity.getTraceId());
            row.put("sessionId", entity.getSessionId());
            row.put("operatorId", entity.getOperatorId());
            row.put("promptCategory", entity.getPromptCategory());
            row.put("promptDecision", entity.getPromptDecision());
            row.put("modelName", entity.getModelName());
            row.put("promptPreview", entity.getPromptPreview());
            row.put("responsePreview", entity.getResponsePreview());
            row.put("refusalReason", entity.getRefusalReason());
            row.put("createdAt", entity.getCreatedAt());
            return row;
        }).toList();
    }

    @Override
    public List<DeadLetterTaskEntity> deadLetters(DeadLetterQueryRequest request) {
        return deadLetterTaskMapper.selectByQuery(new DeadLetterTaskQuery(
                resolveTenant(request.getTenantId()),
                request.getSourceType(),
                request.getReplayStatus(),
                request.getTraceId()
        ));
    }

    @Override
    public List<RetryScheduleEntity> retries(RetryScheduleQueryRequest request) {
        return retryScheduleMapper.selectByQuery(new RetryScheduleQuery(
                resolveTenant(request.getTenantId()),
                request.getRelatedType(),
                request.getRetryPolicy(),
                request.getRetryStatus()
        ));
    }

    @Override
    public List<PendingCatchUpEntity> pendingCatchUps(PendingCatchUpQueryRequest request) {
        return pendingCatchUpMapper.selectByQuery(new PendingCatchUpQuery(
                resolveTenant(request.getTenantId()),
                request.getJobCode(),
                request.getRequestId()
        ));
    }

    @Override
    public List<WorkerRegistryEntity> workers(WorkerRegistryQueryRequest request) {
        return workerRegistryMapper.selectByQuery(new WorkerRegistryQuery(
                resolveTenant(request.getTenantId()),
                request.getWorkerGroup(),
                request.getStatus()
        ));
    }

    @Override
    public List<AlertEventEntity> alertEvents(AlertEventQueryRequest request) {
        int limit = request.getLimit() == null ? DEFAULT_QUERY_LIMIT : request.getLimit();
        return alertEventMapper.selectByQuery(new AlertEventQuery(
                resolveTenant(request.getTenantId()),
                request.getSeverity(),
                request.getStatus(),
                request.getAlertType(),
                limit
        ));
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

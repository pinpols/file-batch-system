package com.example.batch.console.infrastructure;

import com.example.batch.console.application.ConsoleTenantConfigInitApplicationService;
import com.example.batch.console.domain.entity.JobDefinitionEntity;
import com.example.batch.console.domain.entity.WorkflowDefinitionEntity;
import com.example.batch.console.mapper.FileChannelConfigMapper;
import com.example.batch.console.mapper.FileTemplateConfigMapper;
import com.example.batch.console.mapper.JobDefinitionMapper;
import com.example.batch.console.mapper.PipelineDefinitionMapper;
import com.example.batch.console.mapper.PipelineStepDefinitionMapper;
import com.example.batch.console.mapper.WorkflowDefinitionMapper;
import com.example.batch.console.mapper.WorkflowEdgeMapper;
import com.example.batch.console.mapper.WorkflowNodeMapper;
import com.example.batch.console.mapper.param.FileChannelConfigUpsertParam;
import com.example.batch.console.mapper.param.FileTemplateConfigUpsertParam;
import com.example.batch.console.mapper.param.WorkflowDefinitionUpsertParam;
import com.example.batch.console.mapper.param.WorkflowEdgeUpsertParam;
import com.example.batch.console.mapper.param.WorkflowNodeUpsertParam;
import com.example.batch.console.web.request.TenantConfigBatchInitRequest;
import com.example.batch.console.web.request.TenantConfigBatchInitRequest.FileChannelSpec;
import com.example.batch.console.web.request.TenantConfigBatchInitRequest.FileTemplateSpec;
import com.example.batch.console.web.request.TenantConfigBatchInitRequest.InitMode;
import com.example.batch.console.web.request.TenantConfigBatchInitRequest.JobDefinitionSpec;
import com.example.batch.console.web.request.TenantConfigBatchInitRequest.PipelineDefinitionSpec;
import com.example.batch.console.web.request.TenantConfigBatchInitRequest.WorkflowDefinitionSpec;
import com.example.batch.console.web.response.TenantConfigBatchInitResponse;
import com.example.batch.console.web.response.TenantConfigBatchInitResponse.ItemStats;
import com.example.batch.console.web.response.TenantConfigBatchInitResponse.TenantInitResult;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ConsoleTenantConfigInitApplicationService} 的默认实现。
 * <p>
 * 直接操作 Mapper 层，绕过租户守卫，适用于跨租户批量初始化场景。
 * 调用方须在 Controller 层做权限校验（ROLE_ADMIN）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultConsoleTenantConfigInitApplicationService implements ConsoleTenantConfigInitApplicationService {

    private final JobDefinitionMapper jobDefinitionMapper;
    private final WorkflowDefinitionMapper workflowDefinitionMapper;
    private final WorkflowNodeMapper workflowNodeMapper;
    private final WorkflowEdgeMapper workflowEdgeMapper;
    private final PipelineDefinitionMapper pipelineDefinitionMapper;
    private final PipelineStepDefinitionMapper pipelineStepDefinitionMapper;
    private final FileChannelConfigMapper fileChannelConfigMapper;
    private final FileTemplateConfigMapper fileTemplateConfigMapper;

    @Override
    public TenantConfigBatchInitResponse batchInit(TenantConfigBatchInitRequest request, String operator) {
        List<TenantInitResult> results = new ArrayList<>();
        int successCount = 0;
        int failureCount = 0;

        for (String tenantId : request.getTargetTenantIds()) {
            try {
                TenantInitResult result = initForTenant(tenantId, request, operator);
                results.add(result);
                if (result.success()) {
                    successCount++;
                } else {
                    failureCount++;
                }
            } catch (Exception ex) {
                log.error("[TenantConfigBatchInit] unexpected error for tenant={}", tenantId, ex);
                results.add(new TenantInitResult(tenantId, false, ex.getMessage(),
                        ItemStats.empty(), ItemStats.empty(), ItemStats.empty(),
                        ItemStats.empty(), ItemStats.empty()));
                failureCount++;
            }
        }

        return new TenantConfigBatchInitResponse(
                request.getTargetTenantIds().size(), successCount, failureCount, results);
    }

    @Transactional
    protected TenantInitResult initForTenant(String tenantId, TenantConfigBatchInitRequest request, String operator) {
        InitMode mode = request.getMode() != null ? request.getMode() : InitMode.SKIP_EXISTING;
        try {
            ItemStats jobStats = applyJobDefinitions(tenantId, request.getJobDefinitions(), mode, operator);
            ItemStats workflowStats = applyWorkflowDefinitions(tenantId, request.getWorkflowDefinitions(), mode, operator);
            ItemStats pipelineStats = applyPipelineDefinitions(tenantId, request.getPipelineDefinitions(), mode, operator);
            ItemStats channelStats = applyFileChannels(tenantId, request.getFileChannels(), mode, operator);
            ItemStats templateStats = applyFileTemplates(tenantId, request.getFileTemplates(), mode, operator);
            return new TenantInitResult(tenantId, true, null,
                    jobStats, workflowStats, pipelineStats, channelStats, templateStats);
        } catch (Exception ex) {
            log.warn("[TenantConfigBatchInit] failed for tenant={}: {}", tenantId, ex.getMessage());
            return new TenantInitResult(tenantId, false, ex.getMessage(),
                    ItemStats.empty(), ItemStats.empty(), ItemStats.empty(),
                    ItemStats.empty(), ItemStats.empty());
        }
    }

    // ------------------------------------------------------------------ job definitions

    private ItemStats applyJobDefinitions(String tenantId, List<JobDefinitionSpec> specs,
                                          InitMode mode, String operator) {
        if (specs == null || specs.isEmpty()) {
            return ItemStats.empty();
        }
        int created = 0, updated = 0, skipped = 0, failed = 0;
        for (JobDefinitionSpec spec : specs) {
            try {
                JobDefinitionEntity existing = jobDefinitionMapper.selectByUniqueKey(tenantId, spec.getJobCode());
                if (existing != null) {
                    if (mode == InitMode.UPSERT) {
                        updateJobDefinition(existing, spec, operator);
                        updated++;
                    } else {
                        skipped++;
                    }
                } else {
                    insertJobDefinition(tenantId, spec, operator);
                    created++;
                }
            } catch (Exception ex) {
                log.warn("[TenantConfigBatchInit] jobDef jobCode={} tenant={} failed: {}",
                        spec.getJobCode(), tenantId, ex.getMessage());
                failed++;
            }
        }
        return new ItemStats(created, updated, skipped, failed);
    }

    private void insertJobDefinition(String tenantId, JobDefinitionSpec spec, String operator) {
        JobDefinitionEntity entity = new JobDefinitionEntity();
        entity.setTenantId(tenantId);
        entity.setJobCode(spec.getJobCode());
        entity.setJobName(spec.getJobName());
        entity.setJobType(spec.getJobType());
        entity.setBizType(spec.getBizType());
        entity.setScheduleType(spec.getScheduleType());
        entity.setScheduleExpr(spec.getScheduleExpr());
        entity.setTimezone(spec.getTimezone() != null ? spec.getTimezone() : "Asia/Shanghai");
        entity.setTriggerMode(spec.getTriggerMode() != null ? spec.getTriggerMode() : "SCHEDULED");
        entity.setWorkerGroup(spec.getWorkerGroup());
        entity.setQueueCode(spec.getQueueCode());
        entity.setCalendarCode(spec.getCalendarCode());
        entity.setWindowCode(spec.getWindowCode());
        entity.setDagEnabled(spec.getDagEnabled() != null && spec.getDagEnabled());
        entity.setShardStrategy(spec.getShardStrategy() != null ? spec.getShardStrategy() : "NONE");
        entity.setRetryPolicy(spec.getRetryPolicy() != null ? spec.getRetryPolicy() : "NONE");
        entity.setRetryMaxCount(spec.getRetryMaxCount());
        entity.setTimeoutSeconds(spec.getTimeoutSeconds());
        entity.setExecutionHandler(spec.getExecutionHandler());
        entity.setParamSchema(spec.getParamSchema());
        entity.setDefaultParams(spec.getDefaultParams());
        entity.setPriority(spec.getPriority() != null ? spec.getPriority() : 5);
        entity.setEnabled(spec.getEnabled() != null && spec.getEnabled());
        entity.setDescription(spec.getDescription());
        entity.setCreatedBy(operator);
        entity.setUpdatedBy(operator);
        jobDefinitionMapper.insert(entity);
    }

    private void updateJobDefinition(JobDefinitionEntity existing, JobDefinitionSpec spec, String operator) {
        com.example.batch.console.mapper.param.JobDefinitionMaintenanceUpdateParam param =
                new com.example.batch.console.mapper.param.JobDefinitionMaintenanceUpdateParam();
        param.setTenantId(existing.getTenantId());
        param.setJobCode(existing.getJobCode());
        param.setJobName(spec.getJobName() != null ? spec.getJobName() : existing.getJobName());
        param.setQueueCode(spec.getQueueCode() != null ? spec.getQueueCode() : existing.getQueueCode());
        param.setWorkerGroup(spec.getWorkerGroup() != null ? spec.getWorkerGroup() : existing.getWorkerGroup());
        param.setScheduleExpr(spec.getScheduleExpr() != null ? spec.getScheduleExpr() : existing.getScheduleExpr());
        param.setCalendarCode(spec.getCalendarCode() != null ? spec.getCalendarCode() : existing.getCalendarCode());
        param.setWindowCode(spec.getWindowCode() != null ? spec.getWindowCode() : existing.getWindowCode());
        param.setRetryPolicy(spec.getRetryPolicy() != null ? spec.getRetryPolicy() : existing.getRetryPolicy());
        param.setRetryMaxCount(spec.getRetryMaxCount() != null ? spec.getRetryMaxCount() : existing.getRetryMaxCount());
        param.setTimeoutSeconds(spec.getTimeoutSeconds() != null ? spec.getTimeoutSeconds() : existing.getTimeoutSeconds());
        param.setShardStrategy(spec.getShardStrategy() != null ? spec.getShardStrategy() : existing.getShardStrategy());
        param.setEnabled(spec.getEnabled() != null ? spec.getEnabled() : existing.getEnabled());
        param.setDescription(spec.getDescription() != null ? spec.getDescription() : existing.getDescription());
        param.setUpdatedBy(operator);
        jobDefinitionMapper.updateJobDefinitionMaintenance(param);
    }

    // ------------------------------------------------------------------ workflow definitions

    private ItemStats applyWorkflowDefinitions(String tenantId, List<WorkflowDefinitionSpec> specs,
                                                InitMode mode, String operator) {
        if (specs == null || specs.isEmpty()) {
            return ItemStats.empty();
        }
        int created = 0, updated = 0, skipped = 0, failed = 0;
        for (WorkflowDefinitionSpec spec : specs) {
            try {
                WorkflowDefinitionEntity existing = workflowDefinitionMapper.selectByUniqueKey(
                        tenantId, spec.getWorkflowCode(), 1);
                if (existing != null) {
                    if (mode == InitMode.UPSERT) {
                        upsertWorkflowDefinition(tenantId, existing.getId(), spec, operator);
                        updated++;
                    } else {
                        skipped++;
                    }
                } else {
                    upsertWorkflowDefinition(tenantId, null, spec, operator);
                    created++;
                }
            } catch (Exception ex) {
                log.warn("[TenantConfigBatchInit] workflow workflowCode={} tenant={} failed: {}",
                        spec.getWorkflowCode(), tenantId, ex.getMessage());
                failed++;
            }
        }
        return new ItemStats(created, updated, skipped, failed);
    }

    @Transactional
    protected void upsertWorkflowDefinition(String tenantId, Long existingId,
                                             WorkflowDefinitionSpec spec, String operator) {
        WorkflowDefinitionUpsertParam param = new WorkflowDefinitionUpsertParam();
        param.setTenantId(tenantId);
        param.setWorkflowCode(spec.getWorkflowCode());
        param.setWorkflowName(spec.getWorkflowName());
        param.setWorkflowType(spec.getWorkflowType());
        param.setVersion(1);
        param.setEnabled(spec.getEnabled() != null ? spec.getEnabled() : true);
        param.setCreatedBy(operator);
        param.setUpdatedBy(operator);
        workflowDefinitionMapper.upsertWorkflowDefinition(param);

        WorkflowDefinitionEntity saved = workflowDefinitionMapper.selectByUniqueKey(tenantId, spec.getWorkflowCode(), 1);
        if (saved == null) {
            return;
        }
        Long defId = saved.getId();

        if (spec.getNodes() != null) {
            if (existingId != null) {
                workflowNodeMapper.deleteByWorkflowDefinitionId(defId);
                workflowEdgeMapper.deleteByWorkflowDefinitionId(defId);
            }
            for (WorkflowDefinitionSpec.NodeSpec nodeSpec : spec.getNodes()) {
                WorkflowNodeUpsertParam nodeParam = new WorkflowNodeUpsertParam();
                nodeParam.setWorkflowDefinitionId(defId);
                nodeParam.setNodeCode(nodeSpec.getNodeCode());
                nodeParam.setNodeName(nodeSpec.getNodeName());
                nodeParam.setNodeType(nodeSpec.getNodeType());
                nodeParam.setRelatedJobCode(nodeSpec.getRelatedJobCode());
                nodeParam.setRelatedPipelineCode(nodeSpec.getRelatedPipelineCode());
                nodeParam.setWorkerGroup(nodeSpec.getWorkerGroup());
                nodeParam.setWindowCode(nodeSpec.getWindowCode());
                nodeParam.setNodeOrder(nodeSpec.getNodeOrder());
                nodeParam.setRetryPolicy(nodeSpec.getRetryPolicy());
                nodeParam.setRetryMaxCount(nodeSpec.getRetryMaxCount());
                nodeParam.setTimeoutSeconds(nodeSpec.getTimeoutSeconds());
                nodeParam.setNodeParams(nodeSpec.getNodeParams());
                nodeParam.setEnabled(nodeSpec.getEnabled() != null ? nodeSpec.getEnabled() : true);
                workflowNodeMapper.upsertWorkflowNode(nodeParam);
            }
            if (spec.getEdges() != null) {
                for (WorkflowDefinitionSpec.EdgeSpec edgeSpec : spec.getEdges()) {
                    WorkflowEdgeUpsertParam edgeParam = new WorkflowEdgeUpsertParam();
                    edgeParam.setWorkflowDefinitionId(defId);
                    edgeParam.setFromNodeCode(edgeSpec.getFromNodeCode());
                    edgeParam.setToNodeCode(edgeSpec.getToNodeCode());
                    edgeParam.setEdgeType(edgeSpec.getEdgeType() != null ? edgeSpec.getEdgeType() : "NORMAL");
                    edgeParam.setConditionExpr(edgeSpec.getConditionExpr());
                    edgeParam.setEnabled(edgeSpec.getEnabled() != null ? edgeSpec.getEnabled() : true);
                    workflowEdgeMapper.upsertWorkflowEdge(edgeParam);
                }
            }
        }
    }

    // ------------------------------------------------------------------ pipeline definitions

    private ItemStats applyPipelineDefinitions(String tenantId, List<PipelineDefinitionSpec> specs,
                                               InitMode mode, String operator) {
        if (specs == null || specs.isEmpty()) {
            return ItemStats.empty();
        }
        int created = 0, updated = 0, skipped = 0, failed = 0;
        for (PipelineDefinitionSpec spec : specs) {
            try {
                List<Map<String, Object>> existing = pipelineDefinitionMapper.selectByQuery(
                        tenantId, spec.getJobCode(), spec.getPipelineType(), null,
                        new com.example.batch.common.model.PageRequest(1, 1));
                boolean exists = !existing.isEmpty();
                if (exists) {
                    if (mode == InitMode.UPSERT) {
                        Long existingId = ((Number) existing.get(0).get("id")).longValue();
                        updatePipelineDefinition(tenantId, existingId, spec, existing.get(0));
                        updated++;
                    } else {
                        skipped++;
                    }
                } else {
                    insertPipelineDefinition(tenantId, spec);
                    created++;
                }
            } catch (Exception ex) {
                log.warn("[TenantConfigBatchInit] pipeline jobCode={} type={} tenant={} failed: {}",
                        spec.getJobCode(), spec.getPipelineType(), tenantId, ex.getMessage());
                failed++;
            }
        }
        return new ItemStats(created, updated, skipped, failed);
    }

    @Transactional
    protected void insertPipelineDefinition(String tenantId, PipelineDefinitionSpec spec) {
        Map<String, Object> params = new HashMap<>();
        params.put("tenant_id", tenantId);
        params.put("job_code", spec.getJobCode());
        params.put("pipeline_name", spec.getPipelineName());
        params.put("pipeline_type", spec.getPipelineType());
        params.put("biz_type", spec.getBizType());
        params.put("worker_group", spec.getWorkerGroup());
        params.put("version", 1);
        params.put("enabled", spec.getEnabled() != null ? spec.getEnabled() : true);
        params.put("description", spec.getDescription());
        pipelineDefinitionMapper.insert(params);
        Long defId = ((Number) params.get("id")).longValue();
        insertPipelineSteps(defId, spec.getSteps());
    }

    @Transactional
    protected void updatePipelineDefinition(String tenantId, Long id,
                                             PipelineDefinitionSpec spec, Map<String, Object> existing) {
        Map<String, Object> params = new HashMap<>();
        params.put("tenant_id", tenantId);
        params.put("id", id);
        params.put("pipeline_name", spec.getPipelineName() != null ? spec.getPipelineName() : existing.get("pipeline_name"));
        params.put("pipeline_type", spec.getPipelineType() != null ? spec.getPipelineType() : existing.get("pipeline_type"));
        params.put("biz_type", spec.getBizType() != null ? spec.getBizType() : existing.get("biz_type"));
        params.put("worker_group", spec.getWorkerGroup() != null ? spec.getWorkerGroup() : existing.get("worker_group"));
        params.put("enabled", spec.getEnabled() != null ? spec.getEnabled() : existing.get("enabled"));
        params.put("description", spec.getDescription() != null ? spec.getDescription() : existing.get("description"));
        pipelineDefinitionMapper.update(params);
        if (spec.getSteps() != null) {
            pipelineStepDefinitionMapper.deleteByPipelineDefinitionId(id);
            insertPipelineSteps(id, spec.getSteps());
        }
    }

    private void insertPipelineSteps(Long pipelineDefinitionId, List<PipelineDefinitionSpec.StepSpec> steps) {
        if (steps == null) {
            return;
        }
        for (PipelineDefinitionSpec.StepSpec step : steps) {
            Map<String, Object> stepParams = new HashMap<>();
            stepParams.put("pipeline_definition_id", pipelineDefinitionId);
            stepParams.put("step_code", step.getStepCode());
            stepParams.put("step_name", step.getStepName());
            stepParams.put("stage_code", step.getStageCode());
            stepParams.put("step_order", step.getStepOrder() != null ? step.getStepOrder() : 0);
            stepParams.put("impl_code", step.getImplCode());
            stepParams.put("step_params", step.getStepParams());
            stepParams.put("timeout_seconds", step.getTimeoutSeconds() != null ? step.getTimeoutSeconds() : 0);
            stepParams.put("retry_policy", step.getRetryPolicy() != null ? step.getRetryPolicy() : "NONE");
            stepParams.put("retry_max_count", step.getRetryMaxCount() != null ? step.getRetryMaxCount() : 0);
            stepParams.put("enabled", step.getEnabled() != null ? step.getEnabled() : true);
            pipelineStepDefinitionMapper.insert(stepParams);
        }
    }

    // ------------------------------------------------------------------ file channels

    private ItemStats applyFileChannels(String tenantId, List<FileChannelSpec> specs,
                                        InitMode mode, String operator) {
        if (specs == null || specs.isEmpty()) {
            return ItemStats.empty();
        }
        int created = 0, updated = 0, skipped = 0, failed = 0;
        for (FileChannelSpec spec : specs) {
            try {
                Map<String, Object> existing = fileChannelConfigMapper.selectByUniqueKey(tenantId, spec.getChannelCode());
                if (existing != null) {
                    if (mode == InitMode.UPSERT) {
                        upsertFileChannel(tenantId, spec, operator);
                        updated++;
                    } else {
                        skipped++;
                    }
                } else {
                    upsertFileChannel(tenantId, spec, operator);
                    created++;
                }
            } catch (Exception ex) {
                log.warn("[TenantConfigBatchInit] channel channelCode={} tenant={} failed: {}",
                        spec.getChannelCode(), tenantId, ex.getMessage());
                failed++;
            }
        }
        return new ItemStats(created, updated, skipped, failed);
    }

    private void upsertFileChannel(String tenantId, FileChannelSpec spec, String operator) {
        FileChannelConfigUpsertParam param = new FileChannelConfigUpsertParam();
        param.setTenantId(tenantId);
        param.setChannelCode(spec.getChannelCode());
        param.setChannelName(spec.getChannelName());
        param.setChannelType(spec.getChannelType());
        param.setTargetEndpoint(spec.getTargetEndpoint());
        param.setAuthType(spec.getAuthType());
        param.setConfigJson(spec.getConfigJson());
        param.setReceiptPolicy(spec.getReceiptPolicy());
        param.setTimeoutSeconds(spec.getTimeoutSeconds());
        param.setEnabled(spec.getEnabled() != null ? spec.getEnabled() : true);
        param.setCreatedBy(operator);
        param.setUpdatedBy(operator);
        fileChannelConfigMapper.upsertFileChannelConfig(param);
    }

    // ------------------------------------------------------------------ file templates

    private ItemStats applyFileTemplates(String tenantId, List<FileTemplateSpec> specs,
                                         InitMode mode, String operator) {
        if (specs == null || specs.isEmpty()) {
            return ItemStats.empty();
        }
        int created = 0, updated = 0, skipped = 0, failed = 0;
        for (FileTemplateSpec spec : specs) {
            try {
                Map<String, Object> existing = fileTemplateConfigMapper.selectByUniqueKey(
                        tenantId, spec.getTemplateCode(), spec.getVersion() != null ? spec.getVersion() : 1);
                if (existing != null) {
                    if (mode == InitMode.UPSERT) {
                        upsertFileTemplate(tenantId, spec, operator);
                        updated++;
                    } else {
                        skipped++;
                    }
                } else {
                    upsertFileTemplate(tenantId, spec, operator);
                    created++;
                }
            } catch (Exception ex) {
                log.warn("[TenantConfigBatchInit] template templateCode={} tenant={} failed: {}",
                        spec.getTemplateCode(), tenantId, ex.getMessage());
                failed++;
            }
        }
        return new ItemStats(created, updated, skipped, failed);
    }

    private void upsertFileTemplate(String tenantId, FileTemplateSpec spec, String operator) {
        FileTemplateConfigUpsertParam p = new FileTemplateConfigUpsertParam();
        p.setTenantId(tenantId);
        p.setTemplateCode(spec.getTemplateCode());

        FileTemplateConfigUpsertParam.BasicInfo basicInfo = new FileTemplateConfigUpsertParam.BasicInfo();
        basicInfo.setTemplateName(spec.getTemplateName());
        basicInfo.setTemplateType(spec.getTemplateType());
        basicInfo.setBizType(spec.getBizType());
        basicInfo.setEnabled(spec.getEnabled() != null ? spec.getEnabled() : true);
        basicInfo.setVersion(spec.getVersion() != null ? spec.getVersion() : 1);
        basicInfo.setDescription(spec.getDescription());
        p.setBasicInfo(basicInfo);

        FileTemplateConfigUpsertParam.FormatOptions format = new FileTemplateConfigUpsertParam.FormatOptions();
        format.setFileFormatType(spec.getFileFormatType());
        format.setCharset(spec.getCharset());
        format.setTargetCharset(spec.getTargetCharset());
        format.setWithBom(spec.getWithBom());
        format.setLineSeparator(spec.getLineSeparator());
        format.setDelimiter(spec.getDelimiter());
        format.setQuoteChar(spec.getQuoteChar());
        format.setEscapeChar(spec.getEscapeChar());
        format.setRecordLength(spec.getRecordLength());
        format.setHeaderRows(spec.getHeaderRows());
        format.setFooterRows(spec.getFooterRows());
        format.setHeaderTemplateJson(spec.getHeaderTemplateJson());
        format.setTrailerTemplateJson(spec.getTrailerTemplateJson());
        format.setChecksumType(spec.getChecksumType());
        format.setCompressType(spec.getCompressType());
        format.setEncryptType(spec.getEncryptType());
        format.setNamingRule(spec.getNamingRule());
        format.setFieldMappingsJson(spec.getFieldMappingsJson());
        format.setValidationRuleSetJson(spec.getValidationRuleSetJson());
        p.setFormat(format);

        FileTemplateConfigUpsertParam.QueryOptions query = new FileTemplateConfigUpsertParam.QueryOptions();
        query.setDefaultQueryCode(spec.getDefaultQueryCode());
        query.setDefaultQuerySql(spec.getDefaultQuerySql());
        query.setQueryParamSchemaJson(spec.getQueryParamSchemaJson());
        p.setQuery(query);

        FileTemplateConfigUpsertParam.RuntimeOptions runtime = new FileTemplateConfigUpsertParam.RuntimeOptions();
        runtime.setStreamingEnabled(spec.getStreamingEnabled());
        runtime.setPageSize(spec.getPageSize());
        runtime.setFetchSize(spec.getFetchSize());
        runtime.setChunkSize(spec.getChunkSize());
        p.setRuntime(runtime);

        FileTemplateConfigUpsertParam.SecurityOptions security = new FileTemplateConfigUpsertParam.SecurityOptions();
        security.setPreviewMaskingEnabled(spec.getPreviewMaskingEnabled());
        security.setErrorLineMaskingEnabled(spec.getErrorLineMaskingEnabled());
        security.setLogMaskingEnabled(spec.getLogMaskingEnabled());
        security.setContentEncryptionEnabled(spec.getContentEncryptionEnabled());
        security.setEncryptionKeyRef(spec.getEncryptionKeyRef());
        security.setDownloadRequiresApproval(spec.getDownloadRequiresApproval());
        security.setMaskingRuleSet(spec.getMaskingRuleSet());
        p.setSecurity(security);

        FileTemplateConfigUpsertParam.AuditOptions audit = new FileTemplateConfigUpsertParam.AuditOptions();
        audit.setCreatedBy(operator);
        audit.setUpdatedBy(operator);
        p.setAudit(audit);

        fileTemplateConfigMapper.upsertFileTemplateConfig(p);
    }
}

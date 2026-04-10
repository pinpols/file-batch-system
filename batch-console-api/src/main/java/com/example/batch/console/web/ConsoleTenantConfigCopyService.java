package com.example.batch.console.web;

import com.example.batch.common.model.PageRequest;
import com.example.batch.console.application.ConsoleTenantConfigInitApplicationService;
import com.example.batch.console.domain.entity.JobDefinitionEntity;
import com.example.batch.console.domain.entity.WorkflowDefinitionEntity;
import com.example.batch.console.domain.entity.WorkflowEdgeEntity;
import com.example.batch.console.domain.entity.WorkflowNodeEntity;
import com.example.batch.console.domain.query.JobDefinitionQuery;
import com.example.batch.console.domain.query.WorkflowDefinitionQuery;
import com.example.batch.console.domain.query.WorkflowEdgeQuery;
import com.example.batch.console.domain.query.WorkflowNodeQuery;
import com.example.batch.console.mapper.FileChannelConfigMapper;
import com.example.batch.console.mapper.FileTemplateConfigMapper;
import com.example.batch.console.mapper.JobDefinitionMapper;
import com.example.batch.console.mapper.PipelineDefinitionMapper;
import com.example.batch.console.mapper.PipelineStepDefinitionMapper;
import com.example.batch.console.mapper.WorkflowDefinitionMapper;
import com.example.batch.console.mapper.WorkflowEdgeMapper;
import com.example.batch.console.mapper.WorkflowNodeMapper;
import com.example.batch.console.web.request.TenantConfigBatchInitRequest;
import com.example.batch.console.web.request.TenantConfigBatchInitRequest.FileChannelSpec;
import com.example.batch.console.web.request.TenantConfigBatchInitRequest.FileTemplateSpec;
import com.example.batch.console.web.request.TenantConfigBatchInitRequest.JobDefinitionSpec;
import com.example.batch.console.web.request.TenantConfigBatchInitRequest.PipelineDefinitionSpec;
import com.example.batch.console.web.request.TenantConfigBatchInitRequest.WorkflowDefinitionSpec;
import com.example.batch.console.web.request.ConfigSyncBundlePayload;
import com.example.batch.console.web.request.TenantConfigCopyRequest;
import com.example.batch.console.web.request.TenantConfigCopyRequest.ConfigType;
import com.example.batch.console.web.response.TenantConfigBatchInitResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 跨租户配置复制服务。
 * <p>
 * 从源租户读取配置，转换为 Spec 列表，然后委托给 batch-init 逻辑执行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConsoleTenantConfigCopyService {

    private static final int MAX_PAGE_SIZE = 5000;

    private final JobDefinitionMapper jobDefinitionMapper;
    private final WorkflowDefinitionMapper workflowDefinitionMapper;
    private final WorkflowNodeMapper workflowNodeMapper;
    private final WorkflowEdgeMapper workflowEdgeMapper;
    private final PipelineDefinitionMapper pipelineDefinitionMapper;
    private final PipelineStepDefinitionMapper pipelineStepDefinitionMapper;
    private final FileChannelConfigMapper fileChannelConfigMapper;
    private final FileTemplateConfigMapper fileTemplateConfigMapper;
    private final ConsoleTenantConfigInitApplicationService initService;

    public TenantConfigBatchInitResponse copy(TenantConfigCopyRequest request,
                                               String operator,
                                               String batchOperationId) {
        TenantConfigBatchInitRequest initRequest = new TenantConfigBatchInitRequest();
        initRequest.setTargetTenantIds(request.getTargetTenantIds());
        initRequest.setMode(request.getMode());
        initRequest.setDryRun(request.isDryRun());
        ConfigSyncBundlePayload bundle = buildBundle(request.getSourceTenantId(), request.getConfigTypes());
        initRequest.setJobDefinitions(bundle.getJobDefinitions());
        initRequest.setWorkflowDefinitions(bundle.getWorkflowDefinitions());
        initRequest.setPipelineDefinitions(bundle.getPipelineDefinitions());
        initRequest.setFileChannels(bundle.getFileChannels());
        initRequest.setFileTemplates(bundle.getFileTemplates());

        log.info("[TenantConfigCopy] source={} targets={} types={} dryRun={} batchOp={}",
                request.getSourceTenantId(), request.getTargetTenantIds(), request.getConfigTypes(), request.isDryRun(), batchOperationId);

        return initService.batchInit(initRequest, operator, batchOperationId);
    }

    public ConfigSyncBundlePayload buildBundle(String sourceTenantId, Set<ConfigType> configTypes) {
        boolean allTypes = configTypes == null || configTypes.isEmpty();
        ConfigSyncBundlePayload bundle = new ConfigSyncBundlePayload();
        if (allTypes || configTypes.contains(ConfigType.JOB_DEFINITION)) {
            bundle.setJobDefinitions(readJobDefinitions(sourceTenantId));
        }
        if (allTypes || configTypes.contains(ConfigType.WORKFLOW_DEFINITION)) {
            bundle.setWorkflowDefinitions(readWorkflowDefinitions(sourceTenantId));
        }
        if (allTypes || configTypes.contains(ConfigType.PIPELINE_DEFINITION)) {
            bundle.setPipelineDefinitions(readPipelineDefinitions(sourceTenantId));
        }
        if (allTypes || configTypes.contains(ConfigType.FILE_CHANNEL)) {
            bundle.setFileChannels(readFileChannels(sourceTenantId));
        }
        if (allTypes || configTypes.contains(ConfigType.FILE_TEMPLATE)) {
            bundle.setFileTemplates(readFileTemplates(sourceTenantId));
        }
        return bundle;
    }

    // ------------------------------------------------------------------ readers

    private List<JobDefinitionSpec> readJobDefinitions(String tenantId) {
        JobDefinitionQuery query = new JobDefinitionQuery(
                tenantId, null, null, null, null, null, null, null,
                new PageRequest(1, MAX_PAGE_SIZE));
        List<JobDefinitionEntity> entities = jobDefinitionMapper.selectByQuery(query);
        List<JobDefinitionSpec> specs = new ArrayList<>(entities.size());
        for (JobDefinitionEntity e : entities) {
            JobDefinitionSpec s = new JobDefinitionSpec();
            s.setJobCode(e.getJobCode());
            s.setJobName(e.getJobName());
            s.setJobType(e.getJobType());
            s.setBizType(e.getBizType());
            s.setScheduleType(e.getScheduleType());
            s.setScheduleExpr(e.getScheduleExpr());
            s.setTimezone(e.getTimezone());
            s.setTriggerMode(e.getTriggerMode());
            s.setWorkerGroup(e.getWorkerGroup());
            s.setQueueCode(e.getQueueCode());
            s.setCalendarCode(e.getCalendarCode());
            s.setWindowCode(e.getWindowCode());
            s.setDagEnabled(e.getDagEnabled());
            s.setShardStrategy(e.getShardStrategy());
            s.setRetryPolicy(e.getRetryPolicy());
            s.setRetryMaxCount(e.getRetryMaxCount());
            s.setTimeoutSeconds(e.getTimeoutSeconds());
            s.setExecutionHandler(e.getExecutionHandler());
            s.setParamSchema(e.getParamSchema());
            s.setDefaultParams(e.getDefaultParams());
            s.setPriority(e.getPriority());
            s.setEnabled(e.getEnabled());
            s.setDescription(e.getDescription());
            specs.add(s);
        }
        return specs;
    }

    private List<WorkflowDefinitionSpec> readWorkflowDefinitions(String tenantId) {
        WorkflowDefinitionQuery query = new WorkflowDefinitionQuery(
                tenantId, null, null, null, null, null,
                new PageRequest(1, MAX_PAGE_SIZE));
        List<WorkflowDefinitionEntity> entities = workflowDefinitionMapper.selectByQuery(query);
        List<WorkflowDefinitionSpec> specs = new ArrayList<>(entities.size());
        for (WorkflowDefinitionEntity e : entities) {
            WorkflowDefinitionSpec s = new WorkflowDefinitionSpec();
            s.setWorkflowCode(e.getWorkflowCode());
            s.setWorkflowName(e.getWorkflowName());
            s.setWorkflowType(e.getWorkflowType());
            s.setEnabled(e.getEnabled());

            // nodes
            WorkflowNodeQuery nodeQuery = new WorkflowNodeQuery(
                    null, e.getId(), null, null, null, null,
                    new PageRequest(1, MAX_PAGE_SIZE));
            List<WorkflowNodeEntity> nodes = workflowNodeMapper.selectByQuery(nodeQuery);
            List<WorkflowDefinitionSpec.NodeSpec> nodeSpecs = new ArrayList<>(nodes.size());
            for (WorkflowNodeEntity n : nodes) {
                WorkflowDefinitionSpec.NodeSpec ns = new WorkflowDefinitionSpec.NodeSpec();
                ns.setNodeCode(n.getNodeCode());
                ns.setNodeName(n.getNodeName());
                ns.setNodeType(n.getNodeType());
                ns.setRelatedJobCode(n.getRelatedJobCode());
                ns.setRelatedPipelineCode(n.getRelatedPipelineCode());
                ns.setWorkerGroup(n.getWorkerGroup());
                ns.setWindowCode(n.getWindowCode());
                ns.setNodeOrder(n.getNodeOrder());
                ns.setRetryPolicy(n.getRetryPolicy());
                ns.setRetryMaxCount(n.getRetryMaxCount());
                ns.setTimeoutSeconds(n.getTimeoutSeconds());
                ns.setNodeParams(n.getNodeParams());
                ns.setEnabled(n.getEnabled());
                nodeSpecs.add(ns);
            }
            s.setNodes(nodeSpecs);

            // edges
            WorkflowEdgeQuery edgeQuery = new WorkflowEdgeQuery(
                    null, e.getId(), null, null, null, null, null,
                    new PageRequest(1, MAX_PAGE_SIZE));
            List<WorkflowEdgeEntity> edges = workflowEdgeMapper.selectByQuery(edgeQuery);
            List<WorkflowDefinitionSpec.EdgeSpec> edgeSpecs = new ArrayList<>(edges.size());
            for (WorkflowEdgeEntity edge : edges) {
                WorkflowDefinitionSpec.EdgeSpec es = new WorkflowDefinitionSpec.EdgeSpec();
                es.setFromNodeCode(edge.getFromNodeCode());
                es.setToNodeCode(edge.getToNodeCode());
                es.setEdgeType(edge.getEdgeType());
                es.setConditionExpr(edge.getConditionExpr());
                es.setEnabled(edge.getEnabled());
                edgeSpecs.add(es);
            }
            s.setEdges(edgeSpecs);

            specs.add(s);
        }
        return specs;
    }

    private List<PipelineDefinitionSpec> readPipelineDefinitions(String tenantId) {
        List<Map<String, Object>> entities = pipelineDefinitionMapper.selectByQuery(
                tenantId, null, null, null,
                new PageRequest(1, MAX_PAGE_SIZE));
        List<PipelineDefinitionSpec> specs = new ArrayList<>(entities.size());
        for (Map<String, Object> e : entities) {
            PipelineDefinitionSpec s = new PipelineDefinitionSpec();
            s.setJobCode(str(e, "job_code"));
            s.setPipelineName(str(e, "pipeline_name"));
            s.setPipelineType(str(e, "pipeline_type"));
            s.setBizType(str(e, "biz_type"));
            s.setWorkerGroup(str(e, "worker_group"));
            s.setEnabled(bool(e, "enabled"));
            s.setDescription(str(e, "description"));

            Long defId = num(e, "id");
            if (defId != null) {
                List<Map<String, Object>> steps = pipelineStepDefinitionMapper.selectByPipelineDefinitionId(defId);
                if (steps != null && !steps.isEmpty()) {
                    List<PipelineDefinitionSpec.StepSpec> stepSpecs = new ArrayList<>(steps.size());
                    for (Map<String, Object> step : steps) {
                        PipelineDefinitionSpec.StepSpec ss = new PipelineDefinitionSpec.StepSpec();
                        ss.setStepCode(str(step, "step_code"));
                        ss.setStepName(str(step, "step_name"));
                        ss.setStageCode(str(step, "stage_code"));
                        ss.setStepOrder(intVal(step, "step_order"));
                        ss.setImplCode(str(step, "impl_code"));
                        ss.setStepParams(str(step, "step_params"));
                        ss.setTimeoutSeconds(intVal(step, "timeout_seconds"));
                        ss.setRetryPolicy(str(step, "retry_policy"));
                        ss.setRetryMaxCount(intVal(step, "retry_max_count"));
                        ss.setEnabled(bool(step, "enabled"));
                        stepSpecs.add(ss);
                    }
                    s.setSteps(stepSpecs);
                }
            }
            specs.add(s);
        }
        return specs;
    }

    private List<FileChannelSpec> readFileChannels(String tenantId) {
        List<Map<String, Object>> entities = fileChannelConfigMapper.selectByQuery(
                tenantId, null, null, null,
                new PageRequest(1, MAX_PAGE_SIZE));
        List<FileChannelSpec> specs = new ArrayList<>(entities.size());
        for (Map<String, Object> e : entities) {
            FileChannelSpec s = new FileChannelSpec();
            s.setChannelCode(str(e, "channel_code"));
            s.setChannelName(str(e, "channel_name"));
            s.setChannelType(str(e, "channel_type"));
            s.setTargetEndpoint(str(e, "target_endpoint"));
            s.setAuthType(str(e, "auth_type"));
            s.setConfigJson(str(e, "config_json"));
            s.setReceiptPolicy(str(e, "receipt_policy"));
            s.setTimeoutSeconds(intVal(e, "timeout_seconds"));
            s.setEnabled(bool(e, "enabled"));
            specs.add(s);
        }
        return specs;
    }

    private List<FileTemplateSpec> readFileTemplates(String tenantId) {
        List<Map<String, Object>> entities = fileTemplateConfigMapper.selectByQuery(
                tenantId, null, null, null, null, null, null,
                new PageRequest(1, MAX_PAGE_SIZE));
        List<FileTemplateSpec> specs = new ArrayList<>(entities.size());
        for (Map<String, Object> e : entities) {
            FileTemplateSpec s = new FileTemplateSpec();
            s.setTemplateCode(str(e, "template_code"));
            s.setTemplateName(str(e, "template_name"));
            s.setTemplateType(str(e, "template_type"));
            s.setBizType(str(e, "biz_type"));
            s.setFileFormatType(str(e, "file_format_type"));
            s.setCharset(str(e, "charset"));
            s.setTargetCharset(str(e, "target_charset"));
            s.setWithBom(bool(e, "with_bom"));
            s.setLineSeparator(str(e, "line_separator"));
            s.setDelimiter(str(e, "delimiter"));
            s.setQuoteChar(str(e, "quote_char"));
            s.setEscapeChar(str(e, "escape_char"));
            s.setRecordLength(intVal(e, "record_length"));
            s.setHeaderRows(intVal(e, "header_rows"));
            s.setFooterRows(intVal(e, "footer_rows"));
            s.setHeaderTemplateJson(str(e, "header_template_json"));
            s.setTrailerTemplateJson(str(e, "trailer_template_json"));
            s.setChecksumType(str(e, "checksum_type"));
            s.setCompressType(str(e, "compress_type"));
            s.setEncryptType(str(e, "encrypt_type"));
            s.setNamingRule(str(e, "naming_rule"));
            s.setFieldMappingsJson(str(e, "field_mappings_json"));
            s.setValidationRuleSetJson(str(e, "validation_rule_set_json"));
            s.setDefaultQueryCode(str(e, "default_query_code"));
            s.setDefaultQuerySql(str(e, "default_query_sql"));
            s.setQueryParamSchemaJson(str(e, "query_param_schema_json"));
            s.setStreamingEnabled(bool(e, "streaming_enabled"));
            s.setPageSize(intVal(e, "page_size"));
            s.setFetchSize(intVal(e, "fetch_size"));
            s.setChunkSize(intVal(e, "chunk_size"));
            s.setPreviewMaskingEnabled(bool(e, "preview_masking_enabled"));
            s.setErrorLineMaskingEnabled(bool(e, "error_line_masking_enabled"));
            s.setLogMaskingEnabled(bool(e, "log_masking_enabled"));
            s.setContentEncryptionEnabled(bool(e, "content_encryption_enabled"));
            s.setEncryptionKeyRef(str(e, "encryption_key_ref"));
            s.setDownloadRequiresApproval(bool(e, "download_requires_approval"));
            s.setMaskingRuleSet(str(e, "masking_rule_set"));
            s.setEnabled(bool(e, "enabled"));
            s.setVersion(intVal(e, "version"));
            s.setDescription(str(e, "description"));
            specs.add(s);
        }
        return specs;
    }

    // ------------------------------------------------------------------ helpers

    private static String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }

    private static Boolean bool(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof Boolean b) return b;
        return v != null ? Boolean.valueOf(v.toString()) : null;
    }

    private static Long num(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof Number n) return n.longValue();
        return null;
    }

    private static Integer intVal(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof Number n) return n.intValue();
        return null;
    }
}

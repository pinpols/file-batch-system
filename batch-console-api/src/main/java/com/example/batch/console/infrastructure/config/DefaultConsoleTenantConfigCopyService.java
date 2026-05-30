package com.example.batch.console.infrastructure.config;

import com.example.batch.common.model.PageRequest;
import com.example.batch.console.application.config.ConsoleTenantConfigCopyService;
import com.example.batch.console.application.config.ConsoleTenantConfigInitApplicationService;
import com.example.batch.console.domain.entity.JobDefinitionEntity;
import com.example.batch.console.domain.notification.mapper.AlertRoutingConfigMapper;
import com.example.batch.console.domain.query.FileTemplateConfigQuery;
import com.example.batch.console.domain.query.JobDefinitionQuery;
import com.example.batch.console.domain.workflow.entity.WorkflowDefinitionEntity;
import com.example.batch.console.domain.workflow.entity.WorkflowEdgeEntity;
import com.example.batch.console.domain.workflow.entity.WorkflowNodeEntity;
import com.example.batch.console.domain.workflow.mapper.PipelineDefinitionMapper;
import com.example.batch.console.domain.workflow.mapper.PipelineStepDefinitionMapper;
import com.example.batch.console.domain.workflow.mapper.WorkflowDefinitionMapper;
import com.example.batch.console.domain.workflow.mapper.WorkflowEdgeMapper;
import com.example.batch.console.domain.workflow.mapper.WorkflowNodeMapper;
import com.example.batch.console.domain.workflow.query.WorkflowDefinitionQuery;
import com.example.batch.console.domain.workflow.query.WorkflowEdgeQuery;
import com.example.batch.console.domain.workflow.query.WorkflowNodeQuery;
import com.example.batch.console.mapper.BatchWindowMapper;
import com.example.batch.console.mapper.BusinessCalendarMapper;
import com.example.batch.console.mapper.CalendarHolidayMapper;
import com.example.batch.console.mapper.FileChannelConfigMapper;
import com.example.batch.console.mapper.FileTemplateConfigMapper;
import com.example.batch.console.mapper.JobDefinitionMapper;
import com.example.batch.console.mapper.ResourceQueueMapper;
import com.example.batch.console.mapper.TenantQuotaPolicyMapper;
import com.example.batch.console.web.request.config.ConfigSyncBundlePayload;
import com.example.batch.console.web.request.config.TenantConfigBatchInitRequest;
import com.example.batch.console.web.request.config.TenantConfigBatchInitRequest.AlertRoutingSpec;
import com.example.batch.console.web.request.config.TenantConfigBatchInitRequest.BatchWindowSpec;
import com.example.batch.console.web.request.config.TenantConfigBatchInitRequest.BusinessCalendarSpec;
import com.example.batch.console.web.request.config.TenantConfigBatchInitRequest.FileChannelSpec;
import com.example.batch.console.web.request.config.TenantConfigBatchInitRequest.FileTemplateSpec;
import com.example.batch.console.web.request.config.TenantConfigBatchInitRequest.JobDefinitionSpec;
import com.example.batch.console.web.request.config.TenantConfigBatchInitRequest.PipelineDefinitionSpec;
import com.example.batch.console.web.request.config.TenantConfigBatchInitRequest.ResourceQueueSpec;
import com.example.batch.console.web.request.config.TenantConfigBatchInitRequest.TenantQuotaPolicySpec;
import com.example.batch.console.web.request.config.TenantConfigBatchInitRequest.WorkflowDefinitionSpec;
import com.example.batch.console.web.request.config.TenantConfigCopyRequest;
import com.example.batch.console.web.request.config.TenantConfigCopyRequest.ConfigType;
import com.example.batch.console.web.response.config.TenantConfigBatchInitResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 跨租户配置复制服务。
 *
 * <p>从源租户读取配置，转换为 Spec 列表，然后委托给 batch-init 逻辑执行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultConsoleTenantConfigCopyService implements ConsoleTenantConfigCopyService {

  // ── duplicate literal constants ─────────────────────────────────────────
  private static final String KEY_ENABLED = "enabled";
  private static final String KEY_DESCRIPTION = "description";

  private static final int MAX_PAGE_SIZE = 5000;

  /**
   * 单条配置类型的传输描述符。
   *
   * <p>将"从源租户读取 → 写入 bundle → 写入 initRequest"三个步骤封装为一个对象， 消除 buildBundle / copy 中重复的 if-block 和逐字段
   * setter。
   */
  private record ConfigTypeTransfer<T>(
      ConfigType type,
      Function<String, List<T>> reader,
      BiConsumer<ConfigSyncBundlePayload, List<T>> bundleSetter,
      Function<ConfigSyncBundlePayload, List<T>> bundleGetter,
      BiConsumer<TenantConfigBatchInitRequest, List<T>> requestSetter) {
    void fillBundle(String tenantId, ConfigSyncBundlePayload bundle) {
      bundleSetter.accept(bundle, reader.apply(tenantId));
    }

    void transferToRequest(ConfigSyncBundlePayload bundle, TenantConfigBatchInitRequest request) {
      requestSetter.accept(request, bundleGetter.apply(bundle));
    }
  }

  private final JobDefinitionMapper jobDefinitionMapper;
  private final WorkflowDefinitionMapper workflowDefinitionMapper;
  private final WorkflowNodeMapper workflowNodeMapper;
  private final WorkflowEdgeMapper workflowEdgeMapper;
  private final PipelineDefinitionMapper pipelineDefinitionMapper;
  private final PipelineStepDefinitionMapper pipelineStepDefinitionMapper;
  private final FileChannelConfigMapper fileChannelConfigMapper;
  private final FileTemplateConfigMapper fileTemplateConfigMapper;
  private final ResourceQueueMapper resourceQueueMapper;
  private final BatchWindowMapper batchWindowMapper;
  private final BusinessCalendarMapper businessCalendarMapper;
  private final CalendarHolidayMapper calendarHolidayMapper;
  private final TenantQuotaPolicyMapper tenantQuotaPolicyMapper;
  private final AlertRoutingConfigMapper alertRoutingConfigMapper;
  private final ConsoleTenantConfigInitApplicationService initService;

  @Override
  public TenantConfigBatchInitResponse copy(
      TenantConfigCopyRequest request, String operator, String batchOperationId) {
    TenantConfigBatchInitRequest initRequest = new TenantConfigBatchInitRequest();
    initRequest.setTargetTenantIds(request.getTargetTenantIds());
    initRequest.setMode(request.getMode());
    initRequest.setDryRun(request.isDryRun());

    ConfigSyncBundlePayload bundle =
        buildBundle(request.getSourceTenantId(), request.getConfigTypes());
    typeTransfers().forEach(t -> t.transferToRequest(bundle, initRequest));

    log.info(
        "[TenantConfigCopy] source={} targets={} types={} dryRun={} batchOp={}",
        request.getSourceTenantId(),
        request.getTargetTenantIds(),
        request.getConfigTypes(),
        request.isDryRun(),
        batchOperationId);

    return initService.batchInit(initRequest, operator, batchOperationId);
  }

  @Override
  public ConfigSyncBundlePayload buildBundle(String sourceTenantId, Set<ConfigType> configTypes) {
    boolean allTypes = configTypes == null || configTypes.isEmpty();
    ConfigSyncBundlePayload bundle = new ConfigSyncBundlePayload();
    for (ConfigTypeTransfer<?> t : typeTransfers()) {
      if (allTypes || configTypes.contains(t.type())) {
        t.fillBundle(sourceTenantId, bundle);
      }
    }
    return bundle;
  }

  @Override
  public ConfigSyncBundlePayload buildJobBundle(String sourceTenantId, String jobCode) {
    ConfigSyncBundlePayload all = buildBundle(sourceTenantId, Set.of());
    List<JobDefinitionSpec> jobs =
        filter(all.getJobDefinitions(), j -> jobCode.equals(j.getJobCode()));
    ConfigSyncBundlePayload bundle = new ConfigSyncBundlePayload();
    bundle.setJobDefinitions(jobs);
    if (jobs.isEmpty()) {
      return bundle;
    }

    JobDefinitionSpec job = jobs.get(0);
    bundle.setPipelineDefinitions(
        filter(all.getPipelineDefinitions(), p -> jobCode.equals(p.getJobCode())));
    bundle.setWorkflowDefinitions(
        filter(
            all.getWorkflowDefinitions(),
            w ->
                w.getNodes() != null
                    && w.getNodes().stream().anyMatch(n -> jobCode.equals(n.getRelatedJobCode()))));
    bundle.setResourceQueues(
        filter(all.getResourceQueues(), q -> equalsNullable(job.getQueueCode(), q.getQueueCode())));
    bundle.setBatchWindows(
        filter(all.getBatchWindows(), w -> equalsNullable(job.getWindowCode(), w.getWindowCode())));
    bundle.setBusinessCalendars(
        filter(
            all.getBusinessCalendars(),
            c -> equalsNullable(job.getCalendarCode(), c.getCalendarCode())));
    bundle.setFileTemplates(
        filter(all.getFileTemplates(), t -> equalsNullable(job.getBizType(), t.getBizType())));
    bundle.setFileChannels(
        filter(all.getFileChannels(), c -> equalsNullable(job.getBizType(), c.getChannelCode())));
    bundle.setQuotaPolicies(all.getQuotaPolicies());
    bundle.setAlertRoutings(all.getAlertRoutings());
    return bundle;
  }

  /** 构建 10 条传输描述符。每次按需创建；方法引用是懒求值，不会在此处调用 mapper。 */
  private List<ConfigTypeTransfer<?>> typeTransfers() {
    return List.of(
        new ConfigTypeTransfer<>(
            ConfigType.JOB_DEFINITION,
            this::readJobDefinitions,
            ConfigSyncBundlePayload::setJobDefinitions,
            ConfigSyncBundlePayload::getJobDefinitions,
            TenantConfigBatchInitRequest::setJobDefinitions),
        new ConfigTypeTransfer<>(
            ConfigType.WORKFLOW_DEFINITION,
            this::readWorkflowDefinitions,
            ConfigSyncBundlePayload::setWorkflowDefinitions,
            ConfigSyncBundlePayload::getWorkflowDefinitions,
            TenantConfigBatchInitRequest::setWorkflowDefinitions),
        new ConfigTypeTransfer<>(
            ConfigType.PIPELINE_DEFINITION,
            this::readPipelineDefinitions,
            ConfigSyncBundlePayload::setPipelineDefinitions,
            ConfigSyncBundlePayload::getPipelineDefinitions,
            TenantConfigBatchInitRequest::setPipelineDefinitions),
        new ConfigTypeTransfer<>(
            ConfigType.FILE_CHANNEL,
            this::readFileChannels,
            ConfigSyncBundlePayload::setFileChannels,
            ConfigSyncBundlePayload::getFileChannels,
            TenantConfigBatchInitRequest::setFileChannels),
        new ConfigTypeTransfer<>(
            ConfigType.FILE_TEMPLATE,
            this::readFileTemplates,
            ConfigSyncBundlePayload::setFileTemplates,
            ConfigSyncBundlePayload::getFileTemplates,
            TenantConfigBatchInitRequest::setFileTemplates),
        new ConfigTypeTransfer<>(
            ConfigType.RESOURCE_QUEUE,
            this::readResourceQueues,
            ConfigSyncBundlePayload::setResourceQueues,
            ConfigSyncBundlePayload::getResourceQueues,
            TenantConfigBatchInitRequest::setResourceQueues),
        new ConfigTypeTransfer<>(
            ConfigType.BATCH_WINDOW,
            this::readBatchWindows,
            ConfigSyncBundlePayload::setBatchWindows,
            ConfigSyncBundlePayload::getBatchWindows,
            TenantConfigBatchInitRequest::setBatchWindows),
        new ConfigTypeTransfer<>(
            ConfigType.BUSINESS_CALENDAR,
            this::readBusinessCalendars,
            ConfigSyncBundlePayload::setBusinessCalendars,
            ConfigSyncBundlePayload::getBusinessCalendars,
            TenantConfigBatchInitRequest::setBusinessCalendars),
        new ConfigTypeTransfer<>(
            ConfigType.QUOTA_POLICY,
            this::readQuotaPolicies,
            ConfigSyncBundlePayload::setQuotaPolicies,
            ConfigSyncBundlePayload::getQuotaPolicies,
            TenantConfigBatchInitRequest::setQuotaPolicies),
        new ConfigTypeTransfer<>(
            ConfigType.ALERT_ROUTING,
            this::readAlertRoutings,
            ConfigSyncBundlePayload::setAlertRoutings,
            ConfigSyncBundlePayload::getAlertRoutings,
            TenantConfigBatchInitRequest::setAlertRoutings));
  }

  private List<JobDefinitionSpec> readJobDefinitions(String tenantId) {
    JobDefinitionQuery query =
        JobDefinitionQuery.ofTenant(tenantId, new PageRequest(1, MAX_PAGE_SIZE));
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
    WorkflowDefinitionQuery query =
        WorkflowDefinitionQuery.ofTenant(tenantId, new PageRequest(1, MAX_PAGE_SIZE));
    List<WorkflowDefinitionEntity> entities = workflowDefinitionMapper.selectByQuery(query);
    List<WorkflowDefinitionSpec> specs = new ArrayList<>(entities.size());
    for (WorkflowDefinitionEntity e : entities) {
      WorkflowDefinitionSpec s = new WorkflowDefinitionSpec();
      s.setWorkflowCode(e.getWorkflowCode());
      s.setWorkflowName(e.getWorkflowName());
      s.setWorkflowType(e.getWorkflowType());
      s.setEnabled(e.getEnabled());

      // nodes
      WorkflowNodeQuery nodeQuery =
          WorkflowNodeQuery.ofDefinition(e.getId(), new PageRequest(1, MAX_PAGE_SIZE));
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
      WorkflowEdgeQuery edgeQuery =
          WorkflowEdgeQuery.ofDefinition(e.getId(), new PageRequest(1, MAX_PAGE_SIZE));
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
    List<Map<String, Object>> entities =
        pipelineDefinitionMapper.selectByQuery(
            tenantId, null, null, null, new PageRequest(1, MAX_PAGE_SIZE));
    List<PipelineDefinitionSpec> specs = new ArrayList<>(entities.size());
    for (Map<String, Object> e : entities) {
      PipelineDefinitionSpec s = new PipelineDefinitionSpec();
      s.setJobCode(str(e, "job_code"));
      s.setPipelineName(str(e, "pipeline_name"));
      s.setPipelineType(str(e, "pipeline_type"));
      s.setBizType(str(e, "biz_type"));
      s.setWorkerGroup(str(e, "worker_group"));
      s.setEnabled(bool(e, KEY_ENABLED));
      s.setDescription(str(e, KEY_DESCRIPTION));

      Long defId = num(e, "id");
      if (defId != null) {
        List<Map<String, Object>> steps =
            pipelineStepDefinitionMapper.selectByPipelineDefinitionId(defId);
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
            ss.setEnabled(bool(step, KEY_ENABLED));
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
    List<Map<String, Object>> entities =
        fileChannelConfigMapper.selectByQuery(
            tenantId, null, null, null, new PageRequest(1, MAX_PAGE_SIZE));
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
      s.setEnabled(bool(e, KEY_ENABLED));
      specs.add(s);
    }
    return specs;
  }

  private List<FileTemplateSpec> readFileTemplates(String tenantId) {
    List<Map<String, Object>> entities =
        fileTemplateConfigMapper.selectByQuery(
            FileTemplateConfigQuery.ofTenant(tenantId, new PageRequest(1, MAX_PAGE_SIZE)));
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
      s.setEnabled(bool(e, KEY_ENABLED));
      s.setVersion(intVal(e, "version"));
      s.setDescription(str(e, KEY_DESCRIPTION));
      specs.add(s);
    }
    return specs;
  }

  private List<ResourceQueueSpec> readResourceQueues(String tenantId) {
    List<Map<String, Object>> rows =
        resourceQueueMapper.selectByQuery(
            tenantId, null, null, null, new PageRequest(1, MAX_PAGE_SIZE));
    List<ResourceQueueSpec> specs = new ArrayList<>(rows.size());
    for (Map<String, Object> r : rows) {
      ResourceQueueSpec s = new ResourceQueueSpec();
      s.setQueueCode(str(r, "queue_code"));
      s.setQueueName(str(r, "queue_name"));
      s.setQueueType(str(r, "queue_type"));
      s.setMaxRunningJobs(intVal(r, "max_running_jobs"));
      s.setMaxRunningPartitions(intVal(r, "max_running_partitions"));
      s.setMaxQps(intVal(r, "max_qps"));
      s.setWorkerGroup(str(r, "worker_group"));
      s.setResourceTag(str(r, "resource_tag"));
      s.setPriorityPolicy(str(r, "priority_policy"));
      s.setFairShareWeight(intVal(r, "fair_share_weight"));
      s.setEnabled(bool(r, KEY_ENABLED));
      s.setDescription(str(r, KEY_DESCRIPTION));
      specs.add(s);
    }
    return specs;
  }

  private List<BatchWindowSpec> readBatchWindows(String tenantId) {
    List<Map<String, Object>> rows =
        batchWindowMapper.selectByQuery(tenantId, null, null, new PageRequest(1, MAX_PAGE_SIZE));
    List<BatchWindowSpec> specs = new ArrayList<>(rows.size());
    for (Map<String, Object> r : rows) {
      BatchWindowSpec s = new BatchWindowSpec();
      s.setWindowCode(str(r, "window_code"));
      s.setWindowName(str(r, "window_name"));
      s.setTimezone(str(r, "timezone"));
      s.setStartTime(str(r, "start_time"));
      s.setEndTime(str(r, "end_time"));
      s.setEndStrategy(str(r, "end_strategy"));
      s.setOutOfWindowAction(str(r, "out_of_window_action"));
      s.setAllowCrossDay(bool(r, "allow_cross_day"));
      s.setEnabled(bool(r, KEY_ENABLED));
      s.setDescription(str(r, KEY_DESCRIPTION));
      specs.add(s);
    }
    return specs;
  }

  private List<BusinessCalendarSpec> readBusinessCalendars(String tenantId) {
    List<Map<String, Object>> rows =
        businessCalendarMapper.selectByQuery(
            tenantId, null, null, new PageRequest(1, MAX_PAGE_SIZE));
    List<BusinessCalendarSpec> specs = new ArrayList<>(rows.size());
    for (Map<String, Object> r : rows) {
      BusinessCalendarSpec s = new BusinessCalendarSpec();
      s.setCalendarCode(str(r, "calendar_code"));
      s.setCalendarName(str(r, "calendar_name"));
      s.setTimezone(str(r, "timezone"));
      s.setHolidayRollRule(str(r, "holiday_roll_rule"));
      s.setCatchUpPolicy(str(r, "catch_up_policy"));
      s.setCatchUpMaxDays(intVal(r, "catch_up_max_days"));
      s.setEnabled(bool(r, KEY_ENABLED));
      Long calendarId = num(r, "id");
      if (calendarId != null) {
        List<Map<String, Object>> holidays = calendarHolidayMapper.selectByCalendarId(calendarId);
        if (holidays != null && !holidays.isEmpty()) {
          List<String> dates = new ArrayList<>(holidays.size());
          for (Map<String, Object> h : holidays) {
            String d = str(h, "holiday_date");
            if (d != null) {
              dates.add(d);
            }
          }
          s.setHolidays(dates);
        }
      }
      specs.add(s);
    }
    return specs;
  }

  private List<TenantQuotaPolicySpec> readQuotaPolicies(String tenantId) {
    List<Map<String, Object>> rows =
        tenantQuotaPolicyMapper.selectByQuery(
            tenantId, null, null, new PageRequest(1, MAX_PAGE_SIZE));
    List<TenantQuotaPolicySpec> specs = new ArrayList<>(rows.size());
    for (Map<String, Object> r : rows) {
      TenantQuotaPolicySpec s = new TenantQuotaPolicySpec();
      s.setPolicyCode(str(r, "policy_code"));
      s.setMaxRunningJobsPerTenant(intVal(r, "max_running_jobs_per_tenant"));
      s.setMaxPartitionsPerTenant(intVal(r, "max_partitions_per_tenant"));
      s.setMaxQpsPerTenant(intVal(r, "max_qps_per_tenant"));
      s.setFairShareWeight(intVal(r, "fair_share_weight"));
      s.setEnabled(bool(r, KEY_ENABLED));
      s.setDescription(str(r, KEY_DESCRIPTION));
      specs.add(s);
    }
    return specs;
  }

  private List<AlertRoutingSpec> readAlertRoutings(String tenantId) {
    List<Map<String, Object>> rows =
        alertRoutingConfigMapper.selectByQuery(
            tenantId, null, null, null, null, new PageRequest(1, MAX_PAGE_SIZE));
    List<AlertRoutingSpec> specs = new ArrayList<>(rows.size());
    for (Map<String, Object> r : rows) {
      AlertRoutingSpec s = new AlertRoutingSpec();
      s.setRouteCode(str(r, "route_code"));
      s.setRouteName(str(r, "route_name"));
      s.setTeam(str(r, "team"));
      s.setAlertGroup(str(r, "alert_group"));
      s.setSeverity(str(r, "severity"));
      s.setReceiver(str(r, "receiver"));
      s.setGroupBy(str(r, "group_by"));
      s.setGroupWaitSeconds(intVal(r, "group_wait_seconds"));
      s.setGroupIntervalSeconds(intVal(r, "group_interval_seconds"));
      s.setRepeatIntervalSeconds(intVal(r, "repeat_interval_seconds"));
      s.setEnabled(bool(r, KEY_ENABLED));
      s.setDescription(str(r, KEY_DESCRIPTION));
      specs.add(s);
    }
    return specs;
  }

  private static String str(Map<String, Object> map, String key) {
    Object v = map.get(key);
    return v != null ? v.toString() : null;
  }

  private static Boolean bool(Map<String, Object> map, String key) {
    Object v = map.get(key);
    if (v instanceof Boolean b) {
      return b;
    }
    return v != null ? Boolean.valueOf(v.toString()) : null;
  }

  private static Long num(Map<String, Object> map, String key) {
    Object v = map.get(key);
    if (v instanceof Number n) {
      return n.longValue();
    }
    return null;
  }

  private static Integer intVal(Map<String, Object> map, String key) {
    Object v = map.get(key);
    if (v instanceof Number n) {
      return n.intValue();
    }
    return null;
  }

  private static boolean equalsNullable(String left, String right) {
    return left != null && !left.isBlank() && left.equals(right);
  }

  private static <T> List<T> filter(List<T> source, java.util.function.Predicate<T> predicate) {
    if (source == null || source.isEmpty()) {
      return List.of();
    }
    return source.stream().filter(predicate).toList();
  }
}

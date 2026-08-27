package io.github.pinpols.batch.console.infrastructure.config;

import io.github.pinpols.batch.common.model.PageRequest;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.application.config.ConsoleTenantConfigCopyService;
import io.github.pinpols.batch.console.application.config.ConsoleTenantConfigInitApplicationService;
import io.github.pinpols.batch.console.domain.file.mapper.FileChannelConfigMapper;
import io.github.pinpols.batch.console.domain.file.mapper.FileTemplateConfigMapper;
import io.github.pinpols.batch.console.domain.file.query.FileTemplateConfigQuery;
import io.github.pinpols.batch.console.domain.job.entity.JobDefinitionEntity;
import io.github.pinpols.batch.console.domain.job.mapper.BatchWindowMapper;
import io.github.pinpols.batch.console.domain.job.mapper.BusinessCalendarMapper;
import io.github.pinpols.batch.console.domain.job.mapper.CalendarHolidayMapper;
import io.github.pinpols.batch.console.domain.job.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.console.domain.job.query.JobDefinitionQuery;
import io.github.pinpols.batch.console.domain.notification.mapper.AlertRoutingConfigMapper;
import io.github.pinpols.batch.console.domain.ops.mapper.ResourceQueueMapper;
import io.github.pinpols.batch.console.domain.rbac.mapper.TenantQuotaPolicyMapper;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowDefinitionEntity;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowEdgeEntity;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowNodeEntity;
import io.github.pinpols.batch.console.domain.workflow.mapper.PipelineDefinitionMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.PipelineStepDefinitionMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowDefinitionMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowEdgeMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowNodeMapper;
import io.github.pinpols.batch.console.domain.workflow.query.WorkflowDefinitionQuery;
import io.github.pinpols.batch.console.domain.workflow.query.WorkflowEdgeQuery;
import io.github.pinpols.batch.console.domain.workflow.query.WorkflowNodeQuery;
import io.github.pinpols.batch.console.web.request.config.ConfigSyncBundlePayload;
import io.github.pinpols.batch.console.web.request.config.TenantConfigBatchInitRequest;
import io.github.pinpols.batch.console.web.request.config.TenantConfigBatchInitRequest.AlertRoutingSpec;
import io.github.pinpols.batch.console.web.request.config.TenantConfigBatchInitRequest.BatchWindowSpec;
import io.github.pinpols.batch.console.web.request.config.TenantConfigBatchInitRequest.BusinessCalendarSpec;
import io.github.pinpols.batch.console.web.request.config.TenantConfigBatchInitRequest.FileChannelSpec;
import io.github.pinpols.batch.console.web.request.config.TenantConfigBatchInitRequest.FileTemplateSpec;
import io.github.pinpols.batch.console.web.request.config.TenantConfigBatchInitRequest.JobDefinitionSpec;
import io.github.pinpols.batch.console.web.request.config.TenantConfigBatchInitRequest.PipelineDefinitionSpec;
import io.github.pinpols.batch.console.web.request.config.TenantConfigBatchInitRequest.ResourceQueueSpec;
import io.github.pinpols.batch.console.web.request.config.TenantConfigBatchInitRequest.TenantQuotaPolicySpec;
import io.github.pinpols.batch.console.web.request.config.TenantConfigBatchInitRequest.WorkflowDefinitionSpec;
import io.github.pinpols.batch.console.web.request.config.TenantConfigCopyRequest;
import io.github.pinpols.batch.console.web.request.config.TenantConfigCopyRequest.ConfigType;
import io.github.pinpols.batch.console.web.request.config.TenantConfigMatrixRequest;
import io.github.pinpols.batch.console.web.request.config.TenantConfigPreviewRequest;
import io.github.pinpols.batch.console.web.response.config.TenantConfigBatchInitResponse;
import io.github.pinpols.batch.console.web.response.config.TenantConfigDiffPreviewResponse;
import io.github.pinpols.batch.console.web.response.config.TenantConfigDiffPreviewResponse.ConfigDiffItem;
import io.github.pinpols.batch.console.web.response.config.TenantConfigDiffPreviewResponse.ConfigImpactItem;
import io.github.pinpols.batch.console.web.response.config.TenantConfigDiffPreviewResponse.Summary;
import io.github.pinpols.batch.console.web.response.config.TenantConfigDiffPreviewResponse.TenantDiffResult;
import io.github.pinpols.batch.console.web.response.config.TenantConfigMatrixResponse;
import io.github.pinpols.batch.console.web.response.config.TenantConfigMatrixResponse.JobMatrixRow;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

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
  private final TenantConfigReferenceResolver referenceResolver;

  @Override
  public TenantConfigBatchInitResponse copy(
      TenantConfigCopyRequest request, String operator, String batchOperationId) {
    TenantConfigBatchInitRequest initRequest = new TenantConfigBatchInitRequest();
    initRequest.setTargetTenantIds(request.getTargetTenantIds());
    initRequest.setMode(request.getMode());
    initRequest.setDryRun(request.isDryRun());

    ConfigSyncBundlePayload bundle = requestedCopyBundle(request);
    typeTransfers().forEach(t -> t.transferToRequest(bundle, initRequest));

    log.info(
        "[TenantConfigCopy] source={} targets={} types={} jobCodes={} dryRun={} batchOp={}",
        request.getSourceTenantId(),
        request.getTargetTenantIds(),
        request.getConfigTypes(),
        request.getJobCodes(),
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
    List<PipelineDefinitionSpec> relatedPipelines =
        filter(all.getPipelineDefinitions(), p -> jobCode.equals(p.getJobCode()));
    List<WorkflowDefinitionSpec> relatedWorkflows = filter(
        all.getWorkflowDefinitions(),
        w -> w.getNodes() != null
            && w.getNodes().stream()
                .anyMatch(n -> jobCode.equals(n.getRelatedJobCode())
                    || jobCode.equals(n.getRelatedPipelineCode())));
    TenantConfigReferenceResolver.References refs =
        referenceResolver.resolve(job, relatedPipelines, relatedWorkflows);

    bundle.setPipelineDefinitions(relatedPipelines);
    bundle.setWorkflowDefinitions(relatedWorkflows);
    bundle.setResourceQueues(
        filter(all.getResourceQueues(), q -> equalsNullable(job.getQueueCode(), q.getQueueCode())));
    bundle.setBatchWindows(filter(
        all.getBatchWindows(),
        w -> equalsNullable(job.getWindowCode(), w.getWindowCode())
            || refs.windowCodes().contains(w.getWindowCode())));
    bundle.setBusinessCalendars(filter(
        all.getBusinessCalendars(),
        c -> equalsNullable(job.getCalendarCode(), c.getCalendarCode())));
    bundle.setFileTemplates(filter(
        all.getFileTemplates(),
        t -> refs.templateCodes().contains(t.getTemplateCode())
            || (EmptyChecks.isEmpty(refs.templateCodes())
                && equalsNullable(job.getBizType(), t.getBizType()))));
    bundle.setFileChannels(filter(
        all.getFileChannels(),
        c -> refs.channelCodes().contains(c.getChannelCode())
            || (EmptyChecks.isEmpty(refs.channelCodes())
                && equalsNullable(job.getBizType(), c.getChannelCode()))));
    bundle.setQuotaPolicies(all.getQuotaPolicies());
    bundle.setAlertRoutings(all.getAlertRoutings());
    return bundle;
  }

  @Override
  public TenantConfigDiffPreviewResponse preview(TenantConfigPreviewRequest request) {
    ConfigSyncBundlePayload sourceBundle = requestedBundle(request);
    return diffPreview(request, sourceBundle);
  }

  @Override
  public TenantConfigDiffPreviewResponse previewOverlay(TenantConfigPreviewRequest request) {
    ConfigSyncBundlePayload sourceBundle = requestedBundle(request);
    return diffPreview(request, sourceBundle);
  }

  @Override
  public TenantConfigMatrixResponse matrix(TenantConfigMatrixRequest request) {
    String baselineTenantId = resolveBaselineTenantId(request);
    Map<String, Map<String, JobMatrixRow>> rowsByTenantAndJob = new LinkedHashMap<>();
    List<JobMatrixRow> rows = new ArrayList<>();
    for (String tenantId : request.getTenantIds()) {
      ConfigSyncBundlePayload bundle = buildBundle(
          tenantId,
          Set.of(
              ConfigType.JOB_DEFINITION,
              ConfigType.WORKFLOW_DEFINITION,
              ConfigType.PIPELINE_DEFINITION,
              ConfigType.FILE_CHANNEL,
              ConfigType.FILE_TEMPLATE,
              ConfigType.RESOURCE_QUEUE,
              ConfigType.BATCH_WINDOW,
              ConfigType.BUSINESS_CALENDAR));
      Map<String, JobMatrixRow> tenantRows = new LinkedHashMap<>();
      for (String jobCode : request.getJobCodes()) {
        JobMatrixRow row = matrixRow(tenantId, jobCode, bundle);
        tenantRows.put(jobCode, row);
        rows.add(row);
      }
      rowsByTenantAndJob.put(tenantId, tenantRows);
    }
    rows = rows.stream()
        .map(row -> withDrift(
            row, rowsByTenantAndJob.getOrDefault(baselineTenantId, Map.of()).get(row.jobCode())))
        .toList();
    return new TenantConfigMatrixResponse(
        baselineTenantId,
        List.copyOf(request.getTenantIds()),
        List.copyOf(request.getJobCodes()),
        rows);
  }

  private ConfigSyncBundlePayload requestedBundle(TenantConfigPreviewRequest request) {
    if (EmptyChecks.isEmpty(request.getJobCodes())) {
      return buildBundle(request.getSourceTenantId(), request.getConfigTypes());
    }
    ConfigSyncBundlePayload bundle = new ConfigSyncBundlePayload();
    for (String jobCode : request.getJobCodes()) {
      mergeInto(bundle, buildJobBundle(request.getSourceTenantId(), jobCode));
    }
    return filterBundle(bundle, request.getConfigTypes());
  }

  private ConfigSyncBundlePayload requestedCopyBundle(TenantConfigCopyRequest request) {
    if (EmptyChecks.isEmpty(request.getJobCodes())) {
      return buildBundle(request.getSourceTenantId(), request.getConfigTypes());
    }
    ConfigSyncBundlePayload bundle = new ConfigSyncBundlePayload();
    for (String jobCode : request.getJobCodes()) {
      mergeInto(bundle, buildJobBundle(request.getSourceTenantId(), jobCode));
    }
    return filterBundle(bundle, request.getConfigTypes());
  }

  private ConfigSyncBundlePayload requestedBundleForTenant(
      String tenantId, TenantConfigPreviewRequest request) {
    if (EmptyChecks.isEmpty(request.getJobCodes())) {
      return buildBundle(tenantId, request.getConfigTypes());
    }
    ConfigSyncBundlePayload bundle = new ConfigSyncBundlePayload();
    for (String jobCode : request.getJobCodes()) {
      mergeInto(bundle, buildJobBundle(tenantId, jobCode));
    }
    return filterBundle(bundle, request.getConfigTypes());
  }

  private TenantConfigDiffPreviewResponse diffPreview(
      TenantConfigPreviewRequest request, ConfigSyncBundlePayload sourceBundle) {
    List<TenantDiffResult> tenants = new ArrayList<>();
    int addCount = 0;
    int updateCount = 0;
    int unchangedCount = 0;
    int deleteCandidateCount = 0;
    for (String targetTenantId : request.getTargetTenantIds()) {
      TenantDiffResult tenantResult = diffTenant(
          sourceBundle, requestedBundleForTenant(targetTenantId, request), targetTenantId, request);
      tenants.add(tenantResult);
      addCount += tenantResult.addCount();
      updateCount += tenantResult.updateCount();
      unchangedCount += tenantResult.unchangedCount();
      deleteCandidateCount += tenantResult.deleteCandidateCount();
    }
    return new TenantConfigDiffPreviewResponse(
        request.getSourceTenantId(),
        List.copyOf(request.getTargetTenantIds()),
        tenants,
        new Summary(
            request.getTargetTenantIds().size(),
            addCount,
            updateCount,
            unchangedCount,
            deleteCandidateCount));
  }

  private TenantDiffResult diffTenant(
      ConfigSyncBundlePayload source,
      ConfigSyncBundlePayload target,
      String targetTenantId,
      TenantConfigPreviewRequest request) {
    List<ConfigDiffItem> items = new ArrayList<>();
    ConfigSyncBundlePayload overlay = new ConfigSyncBundlePayload();
    for (ConfigType type : selectedTypes(request.getConfigTypes())) {
      appendDiffs(type, listOf(source, type), listOf(target, type), request, items, overlay);
    }
    int addCount = count(items, "ADD");
    int updateCount = count(items, "UPDATE");
    int unchangedCount = count(items, "UNCHANGED");
    int deleteCandidateCount = count(items, "DELETE_CANDIDATE");
    return new TenantDiffResult(
        targetTenantId,
        addCount,
        updateCount,
        unchangedCount,
        deleteCandidateCount,
        items,
        impacts(items),
        overlay);
  }

  private void appendDiffs(
      ConfigType type,
      List<?> sourceItems,
      List<?> targetItems,
      TenantConfigPreviewRequest request,
      List<ConfigDiffItem> items,
      ConfigSyncBundlePayload overlay) {
    Map<String, Object> sourceByKey = indexByKey(type, sourceItems);
    Map<String, Object> targetByKey = indexByKey(type, targetItems);
    List<Object> overlayItems = new ArrayList<>();
    for (Map.Entry<String, Object> sourceEntry : sourceByKey.entrySet()) {
      Object targetItem = targetByKey.get(sourceEntry.getKey());
      String action;
      String reason;
      if (targetItem == null) {
        action = "ADD";
        reason = "missing in target tenant";
        overlayItems.add(sourceEntry.getValue());
      } else if (sameSpec(sourceEntry.getValue(), targetItem)) {
        action = "UNCHANGED";
        reason = "same as source tenant";
      } else {
        action = "UPDATE";
        reason = "target tenant value differs from source tenant";
        overlayItems.add(sourceEntry.getValue());
      }
      if (request.isIncludeUnchanged() || !"UNCHANGED".equals(action)) {
        items.add(new ConfigDiffItem(
            type.name(),
            sourceEntry.getKey(),
            action,
            reason,
            specMap(sourceEntry.getValue()),
            specMap(targetItem)));
      }
    }
    if (request.isIncludeDeleteCandidates()) {
      for (Map.Entry<String, Object> targetEntry : targetByKey.entrySet()) {
        if (!sourceByKey.containsKey(targetEntry.getKey())) {
          items.add(new ConfigDiffItem(
              type.name(),
              targetEntry.getKey(),
              "DELETE_CANDIDATE",
              "exists only in target tenant; preview never deletes automatically",
              Map.of(),
              specMap(targetEntry.getValue())));
        }
      }
    }
    setList(overlay, type, overlayItems);
  }

  private List<ConfigImpactItem> impacts(List<ConfigDiffItem> items) {
    List<ConfigImpactItem> impacts = new ArrayList<>();
    for (ConfigDiffItem item : items) {
      if ("UNCHANGED".equals(item.action())) {
        continue;
      }
      if (ConfigType.FILE_CHANNEL.name().equals(item.configType())
          || ConfigType.RESOURCE_QUEUE.name().equals(item.configType())
          || ConfigType.BATCH_WINDOW.name().equals(item.configType())
          || ConfigType.BUSINESS_CALENDAR.name().equals(item.configType())) {
        impacts.add(new ConfigImpactItem(
            "ENV_SPECIFIC_REVIEW",
            item.configType() + ":" + item.configKey(),
            "review endpoint, capacity, calendar or batch window values before apply"));
      } else if (ConfigType.JOB_DEFINITION.name().equals(item.configType())) {
        impacts.add(new ConfigImpactItem(
            "JOB_CONFIG_CHANGE",
            item.configKey(),
            "schedule, queue, worker group or default parameters may affect future launches"));
      } else if (ConfigType.FILE_TEMPLATE.name().equals(item.configType())) {
        impacts.add(new ConfigImpactItem(
            "FILE_CONTRACT_CHANGE",
            item.configKey(),
            "template format, validation, mapping or SQL may affect import/export results"));
      }
    }
    return impacts;
  }

  private JobMatrixRow matrixRow(String tenantId, String jobCode, ConfigSyncBundlePayload bundle) {
    JobDefinitionSpec job =
        first(filter(bundle.getJobDefinitions(), j -> jobCode.equals(j.getJobCode())));
    if (job == null) {
      return new JobMatrixRow(
          tenantId,
          jobCode,
          false,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          List.of(),
          List.of(),
          List.of(),
          List.of(),
          List.of("missing"));
    }
    List<PipelineDefinitionSpec> relatedPipelines =
        filter(bundle.getPipelineDefinitions(), p -> jobCode.equals(p.getJobCode()));
    List<WorkflowDefinitionSpec> relatedWorkflows = filter(
        bundle.getWorkflowDefinitions(),
        w -> w.getNodes() != null
            && w.getNodes().stream()
                .anyMatch(n -> jobCode.equals(n.getRelatedJobCode())
                    || jobCode.equals(n.getRelatedPipelineCode())));
    TenantConfigReferenceResolver.References refs =
        referenceResolver.resolve(job, relatedPipelines, relatedWorkflows);
    List<String> templateCodes = EmptyChecks.isEmpty(refs.templateCodes())
        ? filter(bundle.getFileTemplates(), t -> equalsNullable(job.getBizType(), t.getBizType()))
            .stream()
            .map(FileTemplateSpec::getTemplateCode)
            .toList()
        : refs.templateCodes();
    return new JobMatrixRow(
        tenantId,
        jobCode,
        true,
        job.getEnabled(),
        job.getScheduleType(),
        job.getScheduleExpr(),
        job.getTimezone(),
        job.getQueueCode(),
        job.getCalendarCode(),
        job.getWindowCode(),
        job.getWorkerGroup(),
        refs.pipelineJobCodes(),
        refs.workflowCodes(),
        templateCodes,
        channelCodes(job, bundle, refs),
        List.of());
  }

  private JobMatrixRow withDrift(JobMatrixRow row, JobMatrixRow baseline) {
    if (baseline == null || Objects.equals(row.tenantId(), baseline.tenantId())) {
      return row;
    }
    List<String> fields = new ArrayList<>();
    addDrift(fields, "exists", row.exists(), baseline.exists());
    addDrift(fields, "enabled", row.enabled(), baseline.enabled());
    addDrift(fields, "scheduleType", row.scheduleType(), baseline.scheduleType());
    addDrift(fields, "scheduleExpr", row.scheduleExpr(), baseline.scheduleExpr());
    addDrift(fields, "timezone", row.timezone(), baseline.timezone());
    addDrift(fields, "queueCode", row.queueCode(), baseline.queueCode());
    addDrift(fields, "calendarCode", row.calendarCode(), baseline.calendarCode());
    addDrift(fields, "windowCode", row.windowCode(), baseline.windowCode());
    addDrift(fields, "workerGroup", row.workerGroup(), baseline.workerGroup());
    addDrift(fields, "templateCodes", row.templateCodes(), baseline.templateCodes());
    addDrift(fields, "channelCodes", row.channelCodes(), baseline.channelCodes());
    return new JobMatrixRow(
        row.tenantId(),
        row.jobCode(),
        row.exists(),
        row.enabled(),
        row.scheduleType(),
        row.scheduleExpr(),
        row.timezone(),
        row.queueCode(),
        row.calendarCode(),
        row.windowCode(),
        row.workerGroup(),
        row.pipelineJobCodes(),
        row.workflowCodes(),
        row.templateCodes(),
        row.channelCodes(),
        List.copyOf(fields));
  }

  private List<String> channelCodes(
      JobDefinitionSpec job,
      ConfigSyncBundlePayload bundle,
      TenantConfigReferenceResolver.References refs) {
    if (EmptyChecks.isNotEmpty(refs.channelCodes())) {
      return refs.channelCodes();
    }
    return filter(
            bundle.getFileChannels(), c -> equalsNullable(job.getBizType(), c.getChannelCode()))
        .stream()
        .map(FileChannelSpec::getChannelCode)
        .toList();
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

      // nodes —— definition-id 作用域:tenantId 故意为 null,靠已经过 ofTenant 过滤的
      // workflowDefinitionId(FK)收敛,非跨租扫描。此处**不**走 TenantScope.requireTenant。
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
    List<Map<String, Object>> entities = pipelineDefinitionMapper.selectByQuery(
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
    List<Map<String, Object>> entities = fileChannelConfigMapper.selectByQuery(
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
    List<Map<String, Object>> entities = fileTemplateConfigMapper.selectByQuery(
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
    List<Map<String, Object>> rows = resourceQueueMapper.selectByQuery(
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
    List<Map<String, Object>> rows = businessCalendarMapper.selectByQuery(
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
    List<Map<String, Object>> rows = tenantQuotaPolicyMapper.selectByQuery(
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
    List<Map<String, Object>> rows = alertRoutingConfigMapper.selectByQuery(
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

  private ConfigSyncBundlePayload filterBundle(
      ConfigSyncBundlePayload bundle, Set<ConfigType> configTypes) {
    if (EmptyChecks.isEmpty(configTypes)) {
      return bundle;
    }
    ConfigSyncBundlePayload filtered = new ConfigSyncBundlePayload();
    for (ConfigType type : configTypes) {
      setList(filtered, type, listOf(bundle, type));
    }
    return filtered;
  }

  private void mergeInto(ConfigSyncBundlePayload target, ConfigSyncBundlePayload source) {
    for (ConfigType type : ConfigType.values()) {
      setList(target, type, mergeByKey(type, listOf(target, type), listOf(source, type)));
    }
  }

  private List<ConfigType> selectedTypes(Set<ConfigType> configTypes) {
    if (EmptyChecks.isEmpty(configTypes)) {
      return List.of(ConfigType.values());
    }
    return List.copyOf(configTypes);
  }

  private List<?> mergeByKey(ConfigType type, List<?> left, List<?> right) {
    Map<String, Object> merged = indexByKey(type, left);
    for (Object item : right) {
      merged.putIfAbsent(configKey(type, item), item);
    }
    return List.copyOf(merged.values());
  }

  private Map<String, Object> indexByKey(ConfigType type, Collection<?> items) {
    Map<String, Object> byKey = new LinkedHashMap<>();
    if (EmptyChecks.isEmpty(items)) {
      return byKey;
    }
    for (Object item : items) {
      String key = configKey(type, item);
      if (StringUtils.hasText(key)) {
        byKey.put(key, item);
      }
    }
    return byKey;
  }

  private String configKey(ConfigType type, Object item) {
    if (item instanceof JobDefinitionSpec spec) {
      return spec.getJobCode();
    }
    if (item instanceof WorkflowDefinitionSpec spec) {
      return spec.getWorkflowCode();
    }
    if (item instanceof PipelineDefinitionSpec spec) {
      return spec.getJobCode();
    }
    if (item instanceof FileChannelSpec spec) {
      return spec.getChannelCode();
    }
    if (item instanceof FileTemplateSpec spec) {
      String version = spec.getVersion() == null ? "" : "#" + spec.getVersion();
      return spec.getTemplateCode() + version;
    }
    if (item instanceof ResourceQueueSpec spec) {
      return spec.getQueueCode();
    }
    if (item instanceof BatchWindowSpec spec) {
      return spec.getWindowCode();
    }
    if (item instanceof BusinessCalendarSpec spec) {
      return spec.getCalendarCode();
    }
    if (item instanceof TenantQuotaPolicySpec spec) {
      return spec.getPolicyCode();
    }
    if (item instanceof AlertRoutingSpec spec) {
      return spec.getRouteCode();
    }
    throw new IllegalArgumentException("Unsupported config type item: " + type);
  }

  @SuppressWarnings("unchecked")
  private <T> List<T> listOf(ConfigSyncBundlePayload bundle, ConfigType type) {
    List<?> values =
        switch (type) {
          case JOB_DEFINITION -> bundle.getJobDefinitions();
          case WORKFLOW_DEFINITION -> bundle.getWorkflowDefinitions();
          case PIPELINE_DEFINITION -> bundle.getPipelineDefinitions();
          case FILE_CHANNEL -> bundle.getFileChannels();
          case FILE_TEMPLATE -> bundle.getFileTemplates();
          case RESOURCE_QUEUE -> bundle.getResourceQueues();
          case BATCH_WINDOW -> bundle.getBatchWindows();
          case BUSINESS_CALENDAR -> bundle.getBusinessCalendars();
          case QUOTA_POLICY -> bundle.getQuotaPolicies();
          case ALERT_ROUTING -> bundle.getAlertRoutings();
        };
    return values == null ? List.of() : (List<T>) values;
  }

  @SuppressWarnings("unchecked")
  private void setList(ConfigSyncBundlePayload bundle, ConfigType type, List<?> values) {
    List<?> safeValues = EmptyChecks.isEmpty(values) ? null : values;
    switch (type) {
      case JOB_DEFINITION -> bundle.setJobDefinitions((List<JobDefinitionSpec>) safeValues);
      case WORKFLOW_DEFINITION ->
        bundle.setWorkflowDefinitions((List<WorkflowDefinitionSpec>) safeValues);
      case PIPELINE_DEFINITION ->
        bundle.setPipelineDefinitions((List<PipelineDefinitionSpec>) safeValues);
      case FILE_CHANNEL -> bundle.setFileChannels((List<FileChannelSpec>) safeValues);
      case FILE_TEMPLATE -> bundle.setFileTemplates((List<FileTemplateSpec>) safeValues);
      case RESOURCE_QUEUE -> bundle.setResourceQueues((List<ResourceQueueSpec>) safeValues);
      case BATCH_WINDOW -> bundle.setBatchWindows((List<BatchWindowSpec>) safeValues);
      case BUSINESS_CALENDAR ->
        bundle.setBusinessCalendars((List<BusinessCalendarSpec>) safeValues);
      case QUOTA_POLICY -> bundle.setQuotaPolicies((List<TenantQuotaPolicySpec>) safeValues);
      case ALERT_ROUTING -> bundle.setAlertRoutings((List<AlertRoutingSpec>) safeValues);
    }
  }

  private static boolean sameSpec(Object source, Object target) {
    return Objects.equals(specMap(source), specMap(target));
  }

  private static Map<String, Object> specMap(Object spec) {
    return spec == null ? Map.of() : JsonUtils.toMap(spec);
  }

  private static int count(List<ConfigDiffItem> items, String action) {
    return (int) items.stream().filter(item -> action.equals(item.action())).count();
  }

  private static String resolveBaselineTenantId(TenantConfigMatrixRequest request) {
    if (StringUtils.hasText(request.getBaselineTenantId())) {
      return request.getBaselineTenantId();
    }
    return request.getTenantIds().get(0);
  }

  private static <T> T first(List<T> values) {
    return EmptyChecks.isEmpty(values) ? null : values.get(0);
  }

  private static void addDrift(List<String> fields, String field, Object current, Object baseline) {
    if (!Objects.equals(current, baseline)) {
      fields.add(field);
    }
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

  private static <T> List<T> filter(List<T> source, Predicate<T> predicate) {
    if (source == null || source.isEmpty()) {
      return List.of();
    }
    return source.stream().filter(predicate).toList();
  }
}

package io.github.pinpols.batch.console.infrastructure.config;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.logging.LogSanitizer;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.SecretMasking;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.console.application.config.ConsoleTenantConfigCopyService;
import io.github.pinpols.batch.console.application.config.ConsoleTenantConfigInitApplicationService;
import io.github.pinpols.batch.console.application.contract.request.config.ConfigSyncBundlePayload;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.AlertRoutingSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.BatchWindowSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.BusinessCalendarSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.FileChannelSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.FileTemplateSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.JobDefinitionSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.ResourceQueueSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.TenantQuotaPolicySpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.WorkflowDefinitionSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigCopyRequest;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigCopyRequest.ConfigType;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigMatrixRequest;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigPreviewRequest;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigBatchInitResponse;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigDiffPreviewResponse;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigDiffPreviewResponse.ConfigDiffItem;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigDiffPreviewResponse.ConfigImpactItem;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigDiffPreviewResponse.Summary;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigDiffPreviewResponse.TenantDiffResult;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigMatrixResponse;
import io.github.pinpols.batch.console.domain.file.mapper.FileChannelConfigMapper;
import io.github.pinpols.batch.console.domain.file.mapper.FileTemplateConfigMapper;
import io.github.pinpols.batch.console.domain.file.query.FileChannelConfigQuery;
import io.github.pinpols.batch.console.domain.file.query.FileTemplateConfigQuery;
import io.github.pinpols.batch.console.domain.file.view.FileChannelConfigQueryView;
import io.github.pinpols.batch.console.domain.file.view.FileTemplateConfigQueryView;
import io.github.pinpols.batch.console.domain.job.application.contract.request.JobDependencyRuleRequest;
import io.github.pinpols.batch.console.domain.job.entity.JobDefinitionEntity;
import io.github.pinpols.batch.console.domain.job.mapper.BatchWindowMapper;
import io.github.pinpols.batch.console.domain.job.mapper.BusinessCalendarMapper;
import io.github.pinpols.batch.console.domain.job.mapper.CalendarHolidayMapper;
import io.github.pinpols.batch.console.domain.job.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.console.domain.job.query.JobDefinitionQuery;
import io.github.pinpols.batch.console.domain.job.view.ConsoleJobConfigViews.BatchWindowRow;
import io.github.pinpols.batch.console.domain.job.view.ConsoleJobConfigViews.CalendarHolidayRow;
import io.github.pinpols.batch.console.domain.job.view.ConsoleJobConfigViews.CalendarQueryRow;
import io.github.pinpols.batch.console.domain.notification.mapper.AlertRoutingConfigMapper;
import io.github.pinpols.batch.console.domain.notification.query.AlertRoutingConfigQuery;
import io.github.pinpols.batch.console.domain.notification.view.AlertRoutingConfigRow;
import io.github.pinpols.batch.console.domain.ops.mapper.ResourceQueueMapper;
import io.github.pinpols.batch.console.domain.ops.query.ResourceQueueQuery;
import io.github.pinpols.batch.console.domain.ops.view.ResourceQueueRow;
import io.github.pinpols.batch.console.domain.rbac.mapper.TenantQuotaPolicyMapper;
import io.github.pinpols.batch.console.domain.rbac.view.TenantQuotaPolicyRow;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowDefinitionEntity;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowEdgeEntity;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowNodeEntity;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowDefinitionMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowEdgeMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowNodeMapper;
import io.github.pinpols.batch.console.domain.workflow.query.WorkflowEdgeQuery;
import io.github.pinpols.batch.console.domain.workflow.query.WorkflowNodeQuery;
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
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 跨租户配置复制服务。
 *
 * <p>从源租户读取配置，转换为 Spec 列表，然后委托给 batch-init 逻辑执行。
 * 源配置的多次查询必须观察同一快照；独立事务避免继承调用方的 READ COMMITTED。
 * 同类内部构建完整包、单Job包及预览也共享该快照，各目标写入继续使用独立租户事务。
 * 配置包读取复用 Mapper 的空分页全量契约，避免交互列表的 200 条上限截断配置或 Workflow 图。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(
    readOnly = true,
    isolation = Isolation.REPEATABLE_READ,
    propagation = Propagation.REQUIRES_NEW)
public class DefaultConsoleTenantConfigCopyService implements ConsoleTenantConfigCopyService {

  // ── duplicate literal constants ─────────────────────────────────────────
  private static final String KEY_TIMEZONE = "timezone";
  private static final String KEY_ENABLED = "enabled";
  private static final String KEY_DESCRIPTION = "description";

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
    // 复制会剥离环境端点与凭据，禁止把处理后的包回写源租户。
    if (EmptyChecks.isNotEmpty(request.getTargetTenantIds())
        && request.getTargetTenantIds().contains(request.getSourceTenantId())) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.config_copy.source_target_overlap");
    }
    TenantConfigBatchInitRequest initRequest = new TenantConfigBatchInitRequest();
    initRequest.setTargetTenantIds(request.getTargetTenantIds());
    initRequest.setMode(request.getMode());
    initRequest.setDryRun(request.isDryRun());
    initRequest.setStrict(true);

    ConfigSyncBundlePayload bundle = requestedCopyBundle(request);
    typeTransfers().forEach(t -> t.transferToRequest(bundle, initRequest));
    initRequest.setFileChannels(channelsWithoutEnvironmentCredentials(bundle.getFileChannels()));

    String sourceTenantIdForLog = LogSanitizer.value(request.getSourceTenantId());
    String targetTenantIdsForLog = LogSanitizer.value(request.getTargetTenantIds());
    String configTypesForLog = LogSanitizer.value(request.getConfigTypes());
    String jobCodesForLog = LogSanitizer.value(request.getJobCodes());
    boolean dryRunForLog = request.isDryRun();
    String batchOperationIdForLog = LogSanitizer.value(batchOperationId);
    log.info(
        "[TenantConfigCopy] source={} targets={} types={} jobCodes={} dryRun={} batchOp={}",
        sourceTenantIdForLog,
        targetTenantIdsForLog,
        configTypesForLog,
        jobCodesForLog,
        dryRunForLog,
        batchOperationIdForLog);

    return initService.batchInit(initRequest, operator, batchOperationId);
  }

  private List<FileChannelSpec> channelsWithoutEnvironmentCredentials(
      List<FileChannelSpec> source) {
    if (EmptyChecks.isEmpty(source)) {
      return source;
    }
    return source.stream().map(this::copyChannelWithoutEnvironmentCredentials).toList();
  }

  private FileChannelSpec copyChannelWithoutEnvironmentCredentials(FileChannelSpec source) {
    FileChannelSpec target = new FileChannelSpec();
    target.setChannelCode(source.getChannelCode());
    target.setChannelName(source.getChannelName());
    target.setChannelType(source.getChannelType());
    // 不会复制环境专属端点和凭据。保留一个
    // 有效的空 JSON 对象，因为 file_channel_config.config_json 不允许为 NULL。
    target.setConfigJson("{}");
    target.setAuthType(source.getAuthType());
    target.setReceiptPolicy(source.getReceiptPolicy());
    target.setTimeoutSeconds(source.getTimeoutSeconds());
    target.setEnabled(source.getEnabled());
    return target;
  }

  @Override
  public ConfigSyncBundlePayload buildBundle(String sourceTenantId, Set<ConfigType> configTypes) {
    return RlsTenantContextHolder.runWithTenant(sourceTenantId, () -> {
      jobDefinitionMapper.setTenantContext(sourceTenantId);
      boolean allTypes = EmptyChecks.isEmpty(configTypes);
      ConfigSyncBundlePayload bundle = new ConfigSyncBundlePayload();
      for (ConfigTypeTransfer<?> t : typeTransfers()) {
        if (allTypes || configTypes.contains(t.type())) {
          t.fillBundle(sourceTenantId, bundle);
        }
      }
      return bundle;
    });
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
    Predicate<WorkflowDefinitionSpec> workflowIncludesJob = workflow -> EmptyChecks.isNotNull(
            workflow.getNodes())
        && workflow.getNodes().stream().anyMatch(node -> jobCode.equals(node.getRelatedJobCode()));
    List<WorkflowDefinitionSpec> relatedWorkflows =
        filter(all.getWorkflowDefinitions(), workflowIncludesJob);
    TenantConfigReferenceResolver.References refs =
        referenceResolver.resolve(job, relatedWorkflows);

    bundle.setWorkflowDefinitions(relatedWorkflows);
    bundle.setResourceQueues(
        filter(all.getResourceQueues(), q -> equalsNullable(job.getQueueCode(), q.getQueueCode())));
    Predicate<BatchWindowSpec> isJobOrReferencedWindow =
        window -> equalsNullable(job.getWindowCode(), window.getWindowCode())
            || hasReference(refs.windowCodes(), window.getWindowCode());
    bundle.setBatchWindows(filter(all.getBatchWindows(), isJobOrReferencedWindow));
    bundle.setBusinessCalendars(filter(
        all.getBusinessCalendars(), c -> hasReference(refs.calendarCodes(), c.getCalendarCode())));
    Predicate<FileTemplateSpec> isReferencedTemplateOrJobTypeFallback =
        template -> hasReference(refs.templateCodes(), template.getTemplateCode())
            || (EmptyChecks.isEmpty(refs.templateCodes())
                && equalsNullable(job.getBizType(), template.getBizType()));
    bundle.setFileTemplates(filter(all.getFileTemplates(), isReferencedTemplateOrJobTypeFallback));
    Predicate<FileChannelSpec> isReferencedChannelOrJobTypeFallback =
        channel -> hasReference(refs.channelCodes(), channel.getChannelCode())
            || (EmptyChecks.isEmpty(refs.channelCodes())
                && equalsNullable(job.getBizType(), channel.getChannelCode()));
    bundle.setFileChannels(filter(all.getFileChannels(), isReferencedChannelOrJobTypeFallback));
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
    return TenantConfigMatrixBuilder.build(request, this::buildBundle, referenceResolver);
  }

  private ConfigSyncBundlePayload requestedBundle(TenantConfigPreviewRequest request) {
    if (EmptyChecks.isEmpty(request.getJobCodes())) {
      return buildBundle(request.getSourceTenantId(), request.getConfigTypes());
    }
    ConfigSyncBundlePayload bundle = new ConfigSyncBundlePayload();
    for (String jobCode : request.getJobCodes()) {
      ConfigSyncBundlePayload jobBundle = buildJobBundle(request.getSourceTenantId(), jobCode);
      mergeInto(bundle, jobBundle);
    }
    return filterBundle(bundle, request.getConfigTypes());
  }

  private ConfigSyncBundlePayload requestedCopyBundle(TenantConfigCopyRequest request) {
    if (EmptyChecks.isEmpty(request.getJobCodes())) {
      return buildBundle(request.getSourceTenantId(), request.getConfigTypes());
    }
    ConfigSyncBundlePayload bundle = new ConfigSyncBundlePayload();
    for (String jobCode : request.getJobCodes()) {
      ConfigSyncBundlePayload jobBundle = buildJobBundle(request.getSourceTenantId(), jobCode);
      mergeInto(bundle, jobBundle);
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
      ConfigSyncBundlePayload jobBundle = buildJobBundle(tenantId, jobCode);
      mergeInto(bundle, jobBundle);
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
    String sourceTenantId = request.getSourceTenantId();
    List<String> targetTenantIdsForCopy = request.getTargetTenantIds();
    List<String> copiedTargetTenantIds = List.copyOf(targetTenantIdsForCopy);
    int targetTenantCount = request.getTargetTenantIds().size();
    Summary summary =
        new Summary(targetTenantCount, addCount, updateCount, unchangedCount, deleteCandidateCount);
    return new TenantConfigDiffPreviewResponse(
        sourceTenantId, copiedTargetTenantIds, tenants, summary);
  }

  private TenantDiffResult diffTenant(
      ConfigSyncBundlePayload source,
      ConfigSyncBundlePayload target,
      String targetTenantId,
      TenantConfigPreviewRequest request) {
    List<ConfigDiffItem> items = new ArrayList<>();
    ConfigSyncBundlePayload overlay = new ConfigSyncBundlePayload();
    for (ConfigType type : selectedTypes(request.getConfigTypes())) {
      List<?> sourceItems = listOf(source, type);
      List<?> targetItems = listOf(target, type);
      appendDiffs(type, sourceItems, targetItems, request, items, overlay);
    }
    int addCount = count(items, "ADD");
    int updateCount = count(items, "UPDATE");
    int unchangedCount = count(items, "UNCHANGED");
    int deleteCandidateCount = count(items, "DELETE_CANDIDATE");
    List<ConfigImpactItem> configImpacts = impacts(items);
    return new TenantDiffResult(
        targetTenantId,
        addCount,
        updateCount,
        unchangedCount,
        deleteCandidateCount,
        items,
        configImpacts,
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
        Map<String, Object> sourceSpec = specMap(sourceEntry.getValue());
        Map<String, Object> targetSpec = specMap(targetItem);
        items.add(new ConfigDiffItem(
            type.name(), sourceEntry.getKey(), action, reason, sourceSpec, targetSpec));
      }
    }
    if (request.isIncludeDeleteCandidates()) {
      for (Map.Entry<String, Object> targetEntry : targetByKey.entrySet()) {
        if (!sourceByKey.containsKey(targetEntry.getKey())) {
          Map<String, Object> targetSpec = specMap(targetEntry.getValue());
          items.add(new ConfigDiffItem(
              type.name(),
              targetEntry.getKey(),
              "DELETE_CANDIDATE",
              "exists only in target tenant; preview never deletes automatically",
              Map.of(),
              targetSpec));
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
      boolean configImpactRequiresEnvironmentReview =
          ConfigType.FILE_CHANNEL.name().equals(item.configType())
              || ConfigType.RESOURCE_QUEUE.name().equals(item.configType())
              || ConfigType.BATCH_WINDOW.name().equals(item.configType())
              || ConfigType.BUSINESS_CALENDAR.name().equals(item.configType());
      if (configImpactRequiresEnvironmentReview) {
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

  /** 构建传输描述符；历史 Pipeline 定义不再作为新的可复制配置资源。 */
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
    JobDefinitionQuery query = JobDefinitionQuery.ofTenant(tenantId, null);
    List<JobDefinitionEntity> entities = jobDefinitionMapper.selectByQuery(query);
    List<JobDefinitionSpec> specs = new ArrayList<>(entities.size());
    for (JobDefinitionEntity e : entities) {
      JobDefinitionSpec s = new JobDefinitionSpec();
      List<JobDependencyRuleRequest> dependencyRules = e.getJobDependencyRules().stream()
          .map(
              rule -> new JobDependencyRuleRequest(rule.upstreamJobCode(), rule.bizDateAlignment()))
          .toList();
      boolean legacyDependencyFallbackIsRequired =
          EmptyChecks.isEmpty(Objects.requireNonNull(dependencyRules))
              && Texts.hasText(e.getDependsOnJobCode());
      if (legacyDependencyFallbackIsRequired) {
        dependencyRules =
            List.of(new JobDependencyRuleRequest(e.getDependsOnJobCode(), "SAME_DAY"));
      }
      s.setJobCode(e.getJobCode());
      s.setJobName(e.getJobName());
      s.setJobType(e.getJobType());
      s.setBizType(e.getBizType());
      s.setScheduleType(e.getScheduleType());
      s.setScheduleExpr(e.getScheduleExpr());
      s.setTimezone(e.getTimezone());
      s.setTriggerMode(e.getTriggerMode());
      String primaryDependencyCode = EmptyChecks.isEmpty(Objects.requireNonNull(dependencyRules))
          ? null
          : dependencyRules.getFirst().upstreamJobCode();
      s.setDependsOnJobCode(primaryDependencyCode);
      s.setDependencyRules(dependencyRules);
      s.setWorkerGroup(e.getWorkerGroup());
      s.setQueueCode(e.getQueueCode());
      s.setCalendarCode(e.getCalendarCode());
      s.setWindowCode(e.getWindowCode());
      s.setDagEnabled(e.getDagEnabled());
      s.setShardStrategy(e.getShardStrategy());
      s.setRetryPolicy(e.getRetryPolicy());
      s.setRetryMaxCount(e.getRetryMaxCount());
      s.setTimeoutSeconds(e.getTimeoutSeconds());
      s.setSoftRuntimeSeconds(e.getSoftRuntimeSeconds());
      s.setSoftRuntimeSeverity(e.getSoftRuntimeSeverity());
      s.setStartGraceSeconds(e.getStartGraceSeconds());
      s.setStartGraceSeverity(e.getStartGraceSeverity());
      s.setCompletionDeadlineLocalTime(e.getCompletionDeadlineLocalTime());
      s.setCompletionDeadlineDayOffset(e.getCompletionDeadlineDayOffset());
      s.setCompletionDeadlineSeverity(e.getCompletionDeadlineSeverity());
      s.setDependencyCompletionWindowSeconds(e.getDependencyCompletionWindowSeconds());
      s.setExecutionHandler(e.getExecutionHandler());
      s.setParamSchema(e.getParamSchema());
      String sanitizedDefaultParams = SecretMasking.removeSensitiveJsonFields(e.getDefaultParams());
      s.setDefaultParams(sanitizedDefaultParams);
      s.setPriority(e.getPriority());
      s.setEnabled(e.getEnabled());
      s.setDescription(e.getDescription());
      specs.add(s);
    }
    return specs;
  }

  private List<WorkflowDefinitionSpec> readWorkflowDefinitions(String tenantId) {
    List<WorkflowDefinitionEntity> entities =
        workflowDefinitionMapper.selectLatestByTenant(tenantId);
    List<WorkflowDefinitionSpec> specs = new ArrayList<>(entities.size());
    for (WorkflowDefinitionEntity e : entities) {
      WorkflowDefinitionSpec s = new WorkflowDefinitionSpec();
      s.setWorkflowCode(e.getWorkflowCode());
      s.setWorkflowName(e.getWorkflowName());
      s.setWorkflowType(e.getWorkflowType());
      s.setEnabled(e.getEnabled());
      s.setScheduleEnabled(e.getScheduleEnabled());
      s.setScheduleType(e.getScheduleType());
      s.setScheduleExpr(e.getScheduleExpr());
      s.setTimezone(e.getTimezone());
      s.setTriggerMode(e.getTriggerMode());
      s.setCalendarCode(e.getCalendarCode());
      s.setDependsOnJobCode(e.getDependsOnJobCode());
      if (EmptyChecks.isNotNull(e.getSingleJobDefinitionId())) {
        JobDefinitionEntity singleJob =
            jobDefinitionMapper.selectById(tenantId, e.getSingleJobDefinitionId());
        if (EmptyChecks.isNull(singleJob)) {
          throw new IllegalStateException(
              "Managed single-job Workflow references a missing tenant Job definition");
        }
        s.setSingleJobCode(singleJob.getJobCode());
      }

      // nodes —— definition-id 作用域:tenantId 故意为 null,靠已经过 ofTenant 过滤的
      // workflowDefinitionId(FK)收敛,非跨租扫描。此处**不**走 TenantScope.requireTenant。
      WorkflowNodeQuery nodeQuery = WorkflowNodeQuery.ofDefinition(e.getId(), null);
      List<WorkflowNodeEntity> nodes = workflowNodeMapper.selectByQuery(nodeQuery);
      List<WorkflowDefinitionSpec.NodeSpec> nodeSpecs = new ArrayList<>(nodes.size());
      for (WorkflowNodeEntity n : nodes) {
        WorkflowDefinitionSpec.NodeSpec ns = new WorkflowDefinitionSpec.NodeSpec();
        ns.setNodeCode(n.getNodeCode());
        ns.setNodeName(n.getNodeName());
        ns.setNodeType(n.getNodeType());
        ns.setRelatedJobCode(n.getRelatedJobCode());
        ns.setWorkerGroup(n.getWorkerGroup());
        ns.setWindowCode(n.getWindowCode());
        ns.setNodeOrder(n.getNodeOrder());
        ns.setRetryPolicy(n.getRetryPolicy());
        ns.setRetryMaxCount(n.getRetryMaxCount());
        ns.setTimeoutSeconds(n.getTimeoutSeconds());
        String sanitizedNodeParams = SecretMasking.removeSensitiveJsonFields(n.getNodeParams());
        ns.setNodeParams(sanitizedNodeParams);
        ns.setCrossDayDependencies(n.getCrossDayDependencies());
        ns.setCrossDayDependencyTimeoutSeconds(n.getCrossDayDependencyTimeoutSeconds());
        ns.setEnabled(n.getEnabled());
        nodeSpecs.add(ns);
      }
      s.setNodes(nodeSpecs);

      // edges
      WorkflowEdgeQuery edgeQuery = WorkflowEdgeQuery.ofDefinition(e.getId(), null);
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

  private List<FileChannelSpec> readFileChannels(String tenantId) {
    FileChannelConfigQuery fileChannelConfigQuery = FileChannelConfigQuery.builder()
        .tenantId(tenantId)
        .channelCode(null)
        .channelType(null)
        .enabled(null)
        .pageRequest(null)
        .build();
    List<FileChannelConfigQueryView> entities =
        fileChannelConfigMapper.selectByQuery(fileChannelConfigQuery);
    List<FileChannelSpec> specs = new ArrayList<>(entities.size());
    for (FileChannelConfigQueryView e : entities) {
      FileChannelSpec s = new FileChannelSpec();
      s.setChannelCode(e.channelCode());
      s.setChannelName(e.channelName());
      s.setChannelType(e.channelType());
      s.setTargetEndpoint(e.targetEndpoint());
      s.setAuthType(e.authType());
      s.setConfigJson(EmptyChecks.isNull(e.configJson()) ? null : e.configJson().toString());
      s.setReceiptPolicy(e.receiptPolicy());
      s.setTimeoutSeconds(e.timeoutSeconds());
      s.setEnabled(e.enabled());
      specs.add(s);
    }
    return specs;
  }

  private List<FileTemplateSpec> readFileTemplates(String tenantId) {
    FileTemplateConfigQuery query = FileTemplateConfigQuery.ofTenant(tenantId, null);
    List<FileTemplateConfigQueryView> entities = fileTemplateConfigMapper.selectByQuery(query);
    List<FileTemplateSpec> specs = new ArrayList<>(entities.size());
    for (FileTemplateConfigQueryView e : entities) {
      FileTemplateSpec s = new FileTemplateSpec();
      s.setTemplateCode(e.templateCode());
      s.setTemplateName(e.templateName());
      s.setTemplateType(e.templateType());
      s.setBizType(e.bizType());
      s.setFileFormatType(e.fileFormatType());
      s.setCharset(e.charset());
      s.setTargetCharset(e.targetCharset());
      s.setWithBom(e.withBom());
      s.setLineSeparator(e.lineSeparator());
      s.setDelimiter(e.delimiter());
      s.setQuoteChar(e.quoteChar());
      s.setEscapeChar(e.escapeChar());
      s.setRecordLength(e.recordLength());
      s.setHeaderRows(e.headerRows());
      s.setFooterRows(e.footerRows());
      s.setHeaderTemplateJson(str(e.headerTemplate()));
      s.setTrailerTemplateJson(str(e.trailerTemplate()));
      s.setChecksumType(e.checksumType());
      s.setCompressType(e.compressType());
      s.setEncryptType(e.encryptType());
      s.setNamingRule(e.namingRule());
      s.setFieldMappingsJson(str(e.fieldMappings()));
      s.setValidationRuleSetJson(str(e.validationRuleSet()));
      s.setDefaultQueryCode(e.defaultQueryCode());
      s.setDefaultQuerySql(e.defaultQuerySql());
      s.setQueryParamSchemaJson(str(e.queryParamSchema()));
      s.setStreamingEnabled(e.streamingEnabled());
      s.setPageSize(e.pageSize());
      s.setFetchSize(e.fetchSize());
      s.setChunkSize(e.chunkSize());
      s.setPreviewMaskingEnabled(e.previewMaskingEnabled());
      s.setErrorLineMaskingEnabled(e.errorLineMaskingEnabled());
      s.setLogMaskingEnabled(e.logMaskingEnabled());
      s.setContentEncryptionEnabled(e.contentEncryptionEnabled());
      s.setEncryptionKeyRef(e.encryptionKeyRef());
      s.setDownloadRequiresApproval(e.downloadRequiresApproval());
      s.setMaskingRuleSet(e.maskingRuleSet());
      s.setEnabled(e.enabled());
      s.setVersion(e.version());
      s.setDescription(e.description());
      specs.add(s);
    }
    return specs;
  }

  private List<ResourceQueueSpec> readResourceQueues(String tenantId) {
    ResourceQueueQuery query = ResourceQueueQuery.allForTenant(tenantId);
    List<ResourceQueueRow> rows = resourceQueueMapper.selectByQuery(query);
    List<ResourceQueueSpec> specs = new ArrayList<>(rows.size());
    for (ResourceQueueRow r : rows) {
      ResourceQueueSpec s = new ResourceQueueSpec();
      s.setQueueCode(r.queueCode());
      s.setQueueName(r.queueName());
      s.setQueueType(r.queueType());
      s.setMaxRunningJobs(r.maxRunningJobs());
      s.setMaxRunningPartitions(r.maxRunningPartitions());
      s.setMaxQps(r.maxQps());
      s.setWorkerGroup(r.workerGroup());
      s.setResourceTag(r.resourceTag());
      s.setPriorityPolicy(r.priorityPolicy());
      s.setFairShareWeight(r.fairShareWeight());
      s.setEnabled(r.enabled());
      s.setDescription(r.description());
      specs.add(s);
    }
    return specs;
  }

  private List<BatchWindowSpec> readBatchWindows(String tenantId) {
    List<BatchWindowRow> rows = batchWindowMapper.selectByQuery(tenantId, null, null, null);
    List<BatchWindowSpec> specs = new ArrayList<>(rows.size());
    for (BatchWindowRow r : rows) {
      BatchWindowSpec s = new BatchWindowSpec();
      s.setWindowCode(r.windowCode());
      s.setWindowName(r.windowName());
      s.setTimezone(r.timezone());
      s.setStartTime(r.startTime());
      s.setEndTime(r.endTime());
      s.setEndStrategy(r.endStrategy());
      s.setOutOfWindowAction(r.outOfWindowAction());
      s.setAllowCrossDay(r.allowCrossDay());
      s.setEnabled(r.enabled());
      s.setDescription(r.description());
      specs.add(s);
    }
    return specs;
  }

  private List<BusinessCalendarSpec> readBusinessCalendars(String tenantId) {
    List<CalendarQueryRow> rows = businessCalendarMapper.selectByQuery(tenantId, null, null, null);
    List<BusinessCalendarSpec> specs = new ArrayList<>(rows.size());
    for (CalendarQueryRow r : rows) {
      BusinessCalendarSpec s = new BusinessCalendarSpec();
      s.setCalendarCode(r.calendarCode());
      s.setCalendarName(r.calendarName());
      s.setTimezone(r.timezone());
      s.setHolidayRollRule(r.holidayRollRule());
      s.setCatchUpPolicy(r.catchUpPolicy());
      s.setCatchUpMaxDays(r.catchUpMaxDays());
      s.setEnabled(r.enabled());
      Long calendarId = r.id();
      if (calendarId != null) {
        List<CalendarHolidayRow> holidays = calendarHolidayMapper.selectByCalendarId(calendarId);
        if (holidays != null && !holidays.isEmpty()) {
          List<String> dates = new ArrayList<>(holidays.size());
          for (CalendarHolidayRow h : holidays) {
            if (EmptyChecks.isNotNull(h.bizDate())) {
              dates.add(h.bizDate().toString());
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
    List<TenantQuotaPolicyRow> rows =
        tenantQuotaPolicyMapper.selectByQuery(tenantId, null, null, null);
    List<TenantQuotaPolicySpec> specs = new ArrayList<>(rows.size());
    for (TenantQuotaPolicyRow r : rows) {
      TenantQuotaPolicySpec s = new TenantQuotaPolicySpec();
      s.setPolicyCode(r.policyCode());
      s.setMaxRunningJobsPerTenant(r.maxRunningJobsPerTenant());
      s.setMaxPartitionsPerTenant(r.maxPartitionsPerTenant());
      s.setMaxQpsPerTenant(r.maxQpsPerTenant());
      s.setFairShareWeight(r.fairShareWeight());
      s.setEnabled(r.enabled());
      s.setDescription(r.description());
      specs.add(s);
    }
    return specs;
  }

  private List<AlertRoutingSpec> readAlertRoutings(String tenantId) {
    AlertRoutingConfigQuery alertRoutingConfigQuery = AlertRoutingConfigQuery.builder()
        .tenantId(tenantId)
        .keyword(null)
        .routeCode(null)
        .team(null)
        .severity(null)
        .enabled(null)
        .pageRequest(null)
        .build();
    List<AlertRoutingConfigRow> rows =
        alertRoutingConfigMapper.selectByQuery(alertRoutingConfigQuery);
    List<AlertRoutingSpec> specs = new ArrayList<>(rows.size());
    for (AlertRoutingConfigRow r : rows) {
      AlertRoutingSpec s = new AlertRoutingSpec();
      s.setRouteCode(r.routeCode());
      s.setRouteName(r.routeName());
      s.setTeam(r.team());
      s.setAlertGroup(r.alertGroup());
      s.setSeverity(r.severity());
      s.setReceiver(r.receiver());
      s.setGroupBy(r.groupBy());
      s.setGroupWaitSeconds(r.groupWaitSeconds());
      s.setGroupIntervalSeconds(r.groupIntervalSeconds());
      s.setRepeatIntervalSeconds(r.repeatIntervalSeconds());
      s.setEnabled(r.enabled());
      s.setDescription(r.description());
      specs.add(s);
    }
    return specs;
  }

  private static String str(Map<String, Object> map, String key) {
    return str(map.get(key));
  }

  private static String str(Object value) {
    return EmptyChecks.isNull(value) ? null : value.toString();
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
      List<?> targetItems = listOf(target, type);
      List<?> sourceItems = listOf(source, type);
      List<?> mergedItems = mergeByKey(type, targetItems, sourceItems);
      setList(target, type, mergedItems);
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
    List<?> values = (List<?>) bundleAccessors().get(type).get(bundle);
    return values == null ? List.of() : (List<T>) values;
  }

  private void setList(ConfigSyncBundlePayload bundle, ConfigType type, List<?> values) {
    List<?> safeValues = EmptyChecks.isEmpty(values) ? null : values;
    bundleAccessors().get(type).set(bundle, safeValues);
  }

  @SuppressWarnings("unchecked")
  private Map<ConfigType, BundleListAccessor> bundleAccessors() {
    return Map.of(
        ConfigType.JOB_DEFINITION,
        accessor(
            ConfigSyncBundlePayload::getJobDefinitions,
            (bundle, values) -> bundle.setJobDefinitions((List<JobDefinitionSpec>) values)),
        ConfigType.WORKFLOW_DEFINITION,
        accessor(
            ConfigSyncBundlePayload::getWorkflowDefinitions,
            (bundle, values) ->
                bundle.setWorkflowDefinitions((List<WorkflowDefinitionSpec>) values)),
        ConfigType.FILE_CHANNEL,
        accessor(
            ConfigSyncBundlePayload::getFileChannels,
            (bundle, values) -> bundle.setFileChannels((List<FileChannelSpec>) values)),
        ConfigType.FILE_TEMPLATE,
        accessor(
            ConfigSyncBundlePayload::getFileTemplates,
            (bundle, values) -> bundle.setFileTemplates((List<FileTemplateSpec>) values)),
        ConfigType.RESOURCE_QUEUE,
        accessor(
            ConfigSyncBundlePayload::getResourceQueues,
            (bundle, values) -> bundle.setResourceQueues((List<ResourceQueueSpec>) values)),
        ConfigType.BATCH_WINDOW,
        accessor(
            ConfigSyncBundlePayload::getBatchWindows,
            (bundle, values) -> bundle.setBatchWindows((List<BatchWindowSpec>) values)),
        ConfigType.BUSINESS_CALENDAR,
        accessor(
            ConfigSyncBundlePayload::getBusinessCalendars,
            (bundle, values) -> bundle.setBusinessCalendars((List<BusinessCalendarSpec>) values)),
        ConfigType.QUOTA_POLICY,
        accessor(
            ConfigSyncBundlePayload::getQuotaPolicies,
            (bundle, values) -> bundle.setQuotaPolicies((List<TenantQuotaPolicySpec>) values)),
        ConfigType.ALERT_ROUTING,
        accessor(
            ConfigSyncBundlePayload::getAlertRoutings,
            (bundle, values) -> bundle.setAlertRoutings((List<AlertRoutingSpec>) values)));
  }

  private BundleListAccessor accessor(
      Function<ConfigSyncBundlePayload, ? extends List<?>> getter,
      BiConsumer<ConfigSyncBundlePayload, List<?>> setter) {
    return new BundleListAccessor(
        getter::apply, (bundle, values) -> setter.accept(bundle, (List<?>) values));
  }

  private record BundleListAccessor(
      Function<ConfigSyncBundlePayload, Object> getter,
      BiConsumer<ConfigSyncBundlePayload, Object> setter) {
    Object get(ConfigSyncBundlePayload bundle) {
      return getter.apply(bundle);
    }

    void set(ConfigSyncBundlePayload bundle, Object values) {
      setter.accept(bundle, values);
    }
  }

  private static boolean sameSpec(Object source, Object target) {
    Map<String, Object> sourceSpec = specMap(source);
    Map<String, Object> targetSpec = specMap(target);
    return Objects.equals(sourceSpec, targetSpec);
  }

  private static Map<String, Object> specMap(Object spec) {
    return spec == null ? Map.of() : JsonUtils.toMap(spec);
  }

  private static int count(List<ConfigDiffItem> items, String action) {
    return (int) items.stream().filter(item -> action.equals(item.action())).count();
  }

  private static <T> T first(List<T> values) {
    return EmptyChecks.isEmpty(values) ? null : values.get(0);
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

  private static boolean hasReference(List<String> references, String code) {
    return Texts.hasText(code) && references.contains(code);
  }

  private static <T> List<T> filter(List<T> source, Predicate<T> predicate) {
    if (source == null || source.isEmpty()) {
      return List.of();
    }
    return source.stream().filter(predicate).toList();
  }
}

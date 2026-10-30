package io.github.pinpols.batch.console.domain.job.infrastructure;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.CodeNormalizer;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Guard;
import io.github.pinpols.batch.common.utils.SecretMasking;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.console.application.config.ConsoleConfigCacheInvalidationService;
import io.github.pinpols.batch.console.config.JobMonitoringDefaultsProperties;
import io.github.pinpols.batch.console.domain.job.application.ConsoleJobDefinitionApplicationService;
import io.github.pinpols.batch.console.domain.job.application.contract.request.JobDefinitionCopyRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.request.JobDefinitionCreateRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.request.JobDefinitionUpdateRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.request.JobDependencyRuleRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleJobDefinitionResponse;
import io.github.pinpols.batch.console.domain.job.application.contract.response.JobDependencyRuleResponse;
import io.github.pinpols.batch.console.domain.job.entity.JobDefinitionEntity;
import io.github.pinpols.batch.console.domain.job.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.console.domain.job.param.JobDefinitionMaintenanceUpdateParam;
import io.github.pinpols.batch.console.domain.job.param.JobMonitoringPolicyUpsertParam;
import io.github.pinpols.batch.console.domain.job.support.BuiltinTaskTypeGuard;
import io.github.pinpols.batch.console.domain.job.support.JobResourceAssignmentGuard;
import io.github.pinpols.batch.console.infrastructure.workflow.DefaultSingleJobWorkflowProvisioner;
import io.github.pinpols.batch.console.shared.query.TenantIdResolver;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Job 定义的 CRUD + 批量启停 + 克隆入口。
 *
 * <p><b>关键缓存一致性约束</b>：orchestrator 在 launch 热路径上走 Redis 缓存读 job_definition （见 {@code
 * OrchestratorConfigCacheService}），本类的每一个写操作都必须在提交后立即调 {@link
 * ConsoleConfigCacheInvalidationService#evictJobDefinition} 让缓存失效，否则下次 launch 会命中旧配置。
 *
 * <ul>
 *   <li><b>单条写</b>（create / update / toggle / copy / copyWithOverrides）：按 {@code (tenant, jobCode)}
 *       精准 evict。
 *   <li><b>批量写</b>（{@link #batchToggle}）：走 {@link
 *       ConsoleConfigCacheInvalidationService#evictAllJobDefinitions} 清整租户缓存——因为 mapper 返回 rows
 *       但不告知具体哪些 jobCode 被影响，全清比漏清安全。
 * </ul>
 *
 * <p>唯一键冲突（create / copy 时同 jobCode 已存在）一律 {@code CONFLICT} 而非覆盖，强制调用方显式选择 update 或换码。{@link
 * #copyWithOverrides} 是"copy + 部分字段覆盖"的原子合成——copy 后立刻 update，但 {@code shardStrategy}
 * 被强制沿用源（不可覆盖），避免克隆出语义漂移的分片策略。
 */
@Service
@RequiredArgsConstructor
public class DefaultConsoleJobDefinitionApplicationService
    implements ConsoleJobDefinitionApplicationService {

  private static final String INVALID_ARGUMENT_KEY = "error.common.invalid_argument";

  private final JobDefinitionMapper jobDefinitionMapper;
  private final JobDependencyRuleWriter dependencyRuleWriter;
  private final TenantIdResolver tenantGuard;
  private final ConsoleRequestMetadataResolver requestMetadataResolver;
  private final ConsoleConfigCacheInvalidationService cacheInvalidationService;
  private final JobMonitoringDefaultsProperties monitoringDefaults;
  private final BuiltinTaskTypeGuard builtinTaskTypeGuard;
  private final JobResourceAssignmentGuard resourceAssignmentGuard;
  private final DefaultSingleJobWorkflowProvisioner singleJobWorkflowProvisioner;

  @Override
  public ConsoleJobDefinitionResponse detail(Long id, String tenantId) {
    String resolvedTenant = tenantGuard.resolveTenant(tenantId);
    JobDefinitionEntity selectedEntity = jobDefinitionMapper.selectById(resolvedTenant, id);
    JobDefinitionEntity entity = Guard.requireFound(selectedEntity, "job definition not found");
    return toResponse(entity);
  }

  @Override
  @Transactional
  public ConsoleJobDefinitionResponse create(JobDefinitionCreateRequest request) {
    resourceAssignmentGuard.assertCreateAllowed(request.getQueueCode(), request.getWorkerGroup());
    // ADR-035 §使用边界:builtin SPI 4 件套(shell/sql/stored_proc/http)只许平台 ADMIN 引用,
    // 租户走 SDK 自托管。controller 类级 @PreAuthorize ROLE_ADMIN 已是第一道,本守门 defense in depth。
    builtinTaskTypeGuard.assertAllowed(request.getJobType());
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    List<JobDependencyRuleRequest> dependencyRules = resolveCreateDependencyRules(request);
    List<String> dependencyCodes =
        dependencyRules.stream().map(JobDependencyRuleRequest::upstreamJobCode).toList();
    validateDependencies(tenantId, request.getJobCode(), dependencyCodes);
    LocalTime validationCompletionDeadlineLocalTime =
        Boolean.FALSE.equals(request.getCompletionDeadlineEnabled())
            ? null
            : request.getCompletionDeadlineLocalTime();
    Integer validationDependencyWindowSeconds = request.getDependencyCompletionWindowSeconds();
    validateMonitoringCompletionMode(
        validationCompletionDeadlineLocalTime, validationDependencyWindowSeconds);
    JobDefinitionEntity existing =
        jobDefinitionMapper.selectByUniqueKey(tenantId, request.getJobCode());
    if (existing != null) {
      throw BizException.of(
          ResultCode.CONFLICT,
          ResultCode.CONFLICT.detailKey(),
          "job code already exists: " + request.getJobCode());
    }
    JobDefinitionEntity entity = new JobDefinitionEntity();
    entity.setTenantId(tenantId);
    entity.setJobCode(request.getJobCode());
    String entityDependencyCode =
        EmptyChecks.isEmpty(dependencyCodes) ? null : dependencyCodes.get(0);
    entity.setDependsOnJobCode(entityDependencyCode);
    applyCreateExecutionSettings(entity, request);
    int softRuntimeSeconds =
        resolveSoftRuntimeSeconds(request.getSoftRuntimeSeconds(), monitoringDefaults);
    entity.setSoftRuntimeSeconds(softRuntimeSeconds);
    entity.setSoftRuntimeSeverity(severityOrWarn(request.getSoftRuntimeSeverity()));
    String startGraceScheduleType = request.getScheduleType();
    String startGraceDependencyCode =
        EmptyChecks.isEmpty(dependencyCodes) ? null : dependencyCodes.get(0);
    Integer requestedStartGraceSeconds = request.getStartGraceSeconds();
    Integer startGraceSeconds = resolveStartGraceSeconds(
        startGraceScheduleType,
        startGraceDependencyCode,
        requestedStartGraceSeconds,
        monitoringDefaults);
    entity.setStartGraceSeconds(startGraceSeconds);
    entity.setStartGraceSeverity(severityOrWarn(request.getStartGraceSeverity()));
    entity.setCompletionDeadlineLocalTime(
        Boolean.FALSE.equals(request.getCompletionDeadlineEnabled())
            ? null
            : request.getCompletionDeadlineLocalTime());
    entity.setCompletionDeadlineDayOffset(
        EmptyChecks.isNull(entity.getCompletionDeadlineLocalTime())
            ? 0
            : valueOrZero(request.getCompletionDeadlineDayOffset()));
    entity.setCompletionDeadlineSeverity(severityOrWarn(request.getCompletionDeadlineSeverity()));
    entity.setDependencyCompletionWindowSeconds(
        valueOrZero(request.getDependencyCompletionWindowSeconds()));
    entity.setExecutionHandler(request.getExecutionHandler());
    entity.setParamSchema(request.getParamSchema());
    String defaultParams = resolveDefaultParams(request.getDefaultParams(), null);
    entity.setDefaultParams(defaultParams);
    entity.setPriority(request.getPriority() == null ? 5 : request.getPriority());
    entity.setEnabled(request.getEnabled() != null && request.getEnabled());
    entity.setDescription(request.getDescription());
    ConsoleRequestMetadata requestMetadata = requestMetadataResolver.current();
    String operator = requestMetadata.operatorId();
    entity.setCreatedBy(operator);
    entity.setUpdatedBy(operator);
    jobDefinitionMapper.insert(entity);
    replaceDependencyRules(tenantId, entity.getJobCode(), dependencyRules);
    cacheInvalidationService.evictJobDefinition(tenantId, entity.getJobCode());
    singleJobWorkflowProvisioner.provision(entity);
    upsertMonitoringPolicy(tenantId, entity.getId(), entity, softRuntimeSeconds, operator);
    JobDefinitionEntity createdDefinition =
        jobDefinitionMapper.selectById(tenantId, entity.getId());
    return toResponse(createdDefinition);
  }

  @Override
  @Transactional
  public ConsoleJobDefinitionResponse update(Long id, JobDefinitionUpdateRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    JobDefinitionEntity selectedExisting = jobDefinitionMapper.selectById(tenantId, id);
    JobDefinitionEntity existing = Guard.requireFound(selectedExisting, "job definition not found");
    assertWorkflowOwnedScheduleUnchanged(request, existing);
    resourceAssignmentGuard.assertUpdateAllowed(
        request.getQueueCode(),
        request.getWorkerGroup(),
        existing.getQueueCode(),
        existing.getWorkerGroup());
    List<JobDependencyRuleRequest> dependencyRules =
        resolveUpdateDependencyRules(request, existing);
    List<String> dependencyCodes =
        dependencyRules.stream().map(JobDependencyRuleRequest::upstreamJobCode).toList();
    validateDependencies(tenantId, existing.getJobCode(), dependencyCodes);
    String dependsOnJobCode = EmptyChecks.isEmpty(dependencyCodes) ? null : dependencyCodes.get(0);
    LocalTime completionDeadlineLocalTime = resolveCompletionDeadlineLocalTime(request, existing);
    boolean enabledCompletionDeadlineIsMissing =
        Boolean.TRUE.equals(request.getCompletionDeadlineEnabled())
            && EmptyChecks.isNull(completionDeadlineLocalTime);
    if (enabledCompletionDeadlineIsMissing) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, INVALID_ARGUMENT_KEY);
    }

    boolean useExistingDependencyCompletionWindow =
        EmptyChecks.isNull(request.getDependencyCompletionWindowSeconds());
    Integer effectiveDependencyCompletionWindowSeconds = useExistingDependencyCompletionWindow
        ? existing.getDependencyCompletionWindowSeconds()
        : request.getDependencyCompletionWindowSeconds();
    validateMonitoringCompletionMode(
        completionDeadlineLocalTime, effectiveDependencyCompletionWindowSeconds);
    ConsoleRequestMetadata requestMetadata = requestMetadataResolver.current();
    String operator = requestMetadata.operatorId();
    JobDefinitionMaintenanceUpdateParam param = new JobDefinitionMaintenanceUpdateParam();
    param.setTenantId(tenantId);
    param.setJobCode(existing.getJobCode());
    param.setDependsOnJobCode(dependsOnJobCode);
    applyUpdateSchedulingAndRetrySettings(param, request, existing);
    applyMonitoringUpdate(param, request, existing, dependsOnJobCode, completionDeadlineLocalTime);
    applyUpdateExecutionSettings(param, request, existing);
    param.setUpdatedBy(operator);
    jobDefinitionMapper.updateJobDefinitionMaintenance(param);
    replaceDependencyRules(tenantId, existing.getJobCode(), dependencyRules);
    cacheInvalidationService.evictJobDefinition(tenantId, existing.getJobCode());
    JobMonitoringPolicyUpsertParam.JobMonitoringPolicyUpsertParamBuilder monitoringPolicyBuilder =
        JobMonitoringPolicyUpsertParam.builder();
    monitoringPolicyBuilder.tenantId(tenantId);
    monitoringPolicyBuilder.jobDefinitionId(id);
    monitoringPolicyBuilder.softRuntimeSeconds(param.getSoftRuntimeSeconds());
    monitoringPolicyBuilder.softRuntimeSeverity(param.getSoftRuntimeSeverity());
    monitoringPolicyBuilder.startGraceSeconds(param.getStartGraceSeconds());
    monitoringPolicyBuilder.startGraceSeverity(param.getStartGraceSeverity());
    monitoringPolicyBuilder.completionDeadlineLocalTime(param.getCompletionDeadlineLocalTime());
    monitoringPolicyBuilder.completionDeadlineDayOffset(param.getCompletionDeadlineDayOffset());
    monitoringPolicyBuilder.dependencyCompletionWindowSeconds(
        param.getDependencyCompletionWindowSeconds());
    monitoringPolicyBuilder.completionDeadlineSeverity(param.getCompletionDeadlineSeverity());
    monitoringPolicyBuilder.updatedBy(operator);
    JobMonitoringPolicyUpsertParam monitoringPolicy = monitoringPolicyBuilder.build();
    jobDefinitionMapper.upsertJobMonitoringPolicy(monitoringPolicy);
    JobDefinitionEntity updatedDefinition = jobDefinitionMapper.selectById(tenantId, id);
    return toResponse(updatedDefinition);
  }

  /** 更新前已核实 Workflow 调度所有权；这里只保留原有字段缺省与规范化规则。 */
  private void applyUpdateSchedulingAndRetrySettings(
      JobDefinitionMaintenanceUpdateParam param,
      JobDefinitionUpdateRequest request,
      JobDefinitionEntity existing) {
    param.setTriggerMode(
        EmptyChecks.isNotNull(request.getTriggerMode())
            ? request.getTriggerMode()
            : existing.getTriggerMode());
    param.setJobName(request.getJobName() != null ? request.getJobName() : existing.getJobName());
    param.setQueueCode(
        request.getQueueCode() != null
            ? CodeNormalizer.toConfigFormOrNull(request.getQueueCode())
            : existing.getQueueCode());
    param.setWorkerGroup(
        request.getWorkerGroup() != null
            ? CodeNormalizer.toUpperOrNull(request.getWorkerGroup())
            : existing.getWorkerGroup());
    param.setScheduleExpr(
        request.getScheduleExpr() != null ? request.getScheduleExpr() : existing.getScheduleExpr());
    param.setCalendarCode(
        request.getCalendarCode() != null
            ? CodeNormalizer.toConfigFormOrNull(request.getCalendarCode())
            : existing.getCalendarCode());
    param.setWindowCode(
        request.getWindowCode() != null
            ? CodeNormalizer.toConfigFormOrNull(request.getWindowCode())
            : existing.getWindowCode());
    param.setRetryPolicy(
        request.getRetryPolicy() != null ? request.getRetryPolicy() : existing.getRetryPolicy());
    param.setRetryMaxCount(
        request.getRetryMaxCount() != null
            ? request.getRetryMaxCount()
            : existing.getRetryMaxCount());
    param.setTimeoutSeconds(
        request.getTimeoutSeconds() != null
            ? request.getTimeoutSeconds()
            : existing.getTimeoutSeconds());
  }

  /** 默认参数仍在监控值装配后解析，保留未提交、脱敏回传和显式覆盖的区别。 */
  private void applyUpdateExecutionSettings(
      JobDefinitionMaintenanceUpdateParam param,
      JobDefinitionUpdateRequest request,
      JobDefinitionEntity existing) {
    param.setShardStrategy(
        request.getShardStrategy() != null
            ? request.getShardStrategy()
            : existing.getShardStrategy());
    param.setExecutionMode(
        request.getExecutionMode() != null
            ? request.getExecutionMode()
            : existing.getExecutionMode());
    param.setWatermarkField(
        request.getWatermarkField() != null
            ? request.getWatermarkField()
            : existing.getWatermarkField());
    param.setExecutionHandler(
        EmptyChecks.isNotNull(request.getExecutionHandler())
            ? request.getExecutionHandler()
            : existing.getExecutionHandler());
    param.setParamSchema(
        EmptyChecks.isNotNull(request.getParamSchema())
            ? request.getParamSchema()
            : existing.getParamSchema());
    param.setPriority(
        EmptyChecks.isNotNull(request.getPriority())
            ? request.getPriority()
            : existing.getPriority());
    if (EmptyChecks.isNotNull(request.getDefaultParams())) {
      String defaultParams =
          resolveDefaultParams(request.getDefaultParams(), existing.getDefaultParams());
      param.setDefaultParams(defaultParams);
      param.setDefaultParamsSpecified(true);
    }
    param.setEnabled(request.getEnabled() != null ? request.getEnabled() : existing.getEnabled());
    param.setDescription(
        request.getDescription() != null ? request.getDescription() : existing.getDescription());
  }

  private void applyCreateExecutionSettings(
      JobDefinitionEntity entity, JobDefinitionCreateRequest request) {
    entity.setJobName(request.getJobName());
    entity.setJobType(request.getJobType());
    entity.setBizType(request.getBizType());
    entity.setScheduleType(request.getScheduleType());
    entity.setScheduleExpr(request.getScheduleExpr());
    entity.setTimezone(
        request.getTimezone() == null
            ? CommonConstants.DEFAULT_TIMEZONE_ID
            : request.getTimezone());
    entity.setTriggerMode(
        request.getTriggerMode() == null ? "SCHEDULED" : request.getTriggerMode());
    entity.setWorkerGroup(CodeNormalizer.toUpperOrNull(request.getWorkerGroup()));
    entity.setQueueCode(CodeNormalizer.toConfigFormOrNull(request.getQueueCode()));
    entity.setCalendarCode(CodeNormalizer.toConfigFormOrNull(request.getCalendarCode()));
    entity.setWindowCode(CodeNormalizer.toConfigFormOrNull(request.getWindowCode()));
    entity.setDagEnabled(request.getDagEnabled() != null && request.getDagEnabled());
    entity.setShardStrategy(
        request.getShardStrategy() == null ? "NONE" : request.getShardStrategy());
    entity.setExecutionMode(
        request.getExecutionMode() == null ? "FULL" : request.getExecutionMode());
    entity.setWatermarkField(request.getWatermarkField());
    entity.setRetryPolicy(request.getRetryPolicy() == null ? "NONE" : request.getRetryPolicy());
    entity.setRetryMaxCount(request.getRetryMaxCount());
    entity.setTimeoutSeconds(request.getTimeoutSeconds());
  }

  /** 创建沿用先前解析的阈值；复制沿用已复制定义的监控值，避免在写入间重算默认值。 */
  private void upsertMonitoringPolicy(
      String tenantId,
      Long jobDefinitionId,
      JobDefinitionEntity entity,
      Integer softRuntimeSeconds,
      String operator) {
    JobMonitoringPolicyUpsertParam.JobMonitoringPolicyUpsertParamBuilder monitoringPolicyBuilder =
        JobMonitoringPolicyUpsertParam.builder();
    monitoringPolicyBuilder.tenantId(tenantId);
    monitoringPolicyBuilder.jobDefinitionId(jobDefinitionId);
    monitoringPolicyBuilder.softRuntimeSeconds(softRuntimeSeconds);
    String softRuntimeSeverity = severityOrWarn(entity.getSoftRuntimeSeverity());
    monitoringPolicyBuilder.softRuntimeSeverity(softRuntimeSeverity);
    Integer startGraceSecondsValue = valueOrZero(entity.getStartGraceSeconds());
    monitoringPolicyBuilder.startGraceSeconds(startGraceSecondsValue);
    String startGraceSeverity = severityOrWarn(entity.getStartGraceSeverity());
    monitoringPolicyBuilder.startGraceSeverity(startGraceSeverity);
    monitoringPolicyBuilder.completionDeadlineLocalTime(entity.getCompletionDeadlineLocalTime());
    Integer completionDeadlineDayOffset = valueOrZero(entity.getCompletionDeadlineDayOffset());
    monitoringPolicyBuilder.completionDeadlineDayOffset(completionDeadlineDayOffset);
    Integer dependencyCompletionWindowSeconds =
        valueOrZero(entity.getDependencyCompletionWindowSeconds());
    monitoringPolicyBuilder.dependencyCompletionWindowSeconds(dependencyCompletionWindowSeconds);
    String completionDeadlineSeverity = severityOrWarn(entity.getCompletionDeadlineSeverity());
    monitoringPolicyBuilder.completionDeadlineSeverity(completionDeadlineSeverity);
    monitoringPolicyBuilder.updatedBy(operator);
    JobMonitoringPolicyUpsertParam monitoringPolicy = monitoringPolicyBuilder.build();
    jobDefinitionMapper.upsertJobMonitoringPolicy(monitoringPolicy);
    // 监控策略属于 jobDefinition 聚合，写后必须失效 orchestrator 读热点的配置缓存。
    cacheInvalidationService.evictAllJobDefinitions(tenantId);
  }

  private void applyMonitoringUpdate(
      JobDefinitionMaintenanceUpdateParam param,
      JobDefinitionUpdateRequest request,
      JobDefinitionEntity existing,
      String dependsOnJobCode,
      LocalTime completionDeadlineLocalTime) {
    Integer softRuntimeSeconds =
        resolveMonitoringValue(request.getSoftRuntimeSeconds(), existing.getSoftRuntimeSeconds());
    param.setSoftRuntimeSeconds(softRuntimeSeconds);
    String softRuntimeSeverity = resolveMonitoringSeverity(
        request.getSoftRuntimeSeverity(), existing.getSoftRuntimeSeverity());
    param.setSoftRuntimeSeverity(softRuntimeSeverity);
    Integer startGraceSeconds =
        resolveMonitoringValue(request.getStartGraceSeconds(), existing.getStartGraceSeconds());
    param.setStartGraceSeconds(startGraceSeconds);
    String startGraceSeverity = resolveMonitoringSeverity(
        request.getStartGraceSeverity(), existing.getStartGraceSeverity());
    param.setStartGraceSeverity(startGraceSeverity);
    param.setCompletionDeadlineLocalTime(completionDeadlineLocalTime);
    Integer completionDeadlineDayOffset = resolveCompletionDeadlineDayOffset(
        completionDeadlineLocalTime,
        request.getCompletionDeadlineDayOffset(),
        existing.getCompletionDeadlineDayOffset());
    param.setCompletionDeadlineDayOffset(completionDeadlineDayOffset);
    String completionDeadlineSeverity = resolveMonitoringSeverity(
        request.getCompletionDeadlineSeverity(), existing.getCompletionDeadlineSeverity());
    param.setCompletionDeadlineSeverity(completionDeadlineSeverity);
    Integer dependencyCompletionWindowSeconds = Texts.hasText(dependsOnJobCode)
        ? resolveMonitoringValue(
            request.getDependencyCompletionWindowSeconds(),
            existing.getDependencyCompletionWindowSeconds())
        : 0;
    param.setDependencyCompletionWindowSeconds(dependencyCompletionWindowSeconds);
  }

  private static void assertWorkflowOwnedScheduleUnchanged(
      JobDefinitionUpdateRequest request, JobDefinitionEntity existing) {
    boolean workflowManagedScheduleWasChanged =
        changed(request.getScheduleType(), existing.getScheduleType())
            || changed(request.getScheduleExpr(), existing.getScheduleExpr())
            || changed(request.getTimezone(), existing.getTimezone())
            || changed(request.getTriggerMode(), existing.getTriggerMode())
            || changed(request.getCalendarCode(), existing.getCalendarCode())
            || changed(request.getWindowCode(), existing.getWindowCode());
    if (workflowManagedScheduleWasChanged) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.job.schedule_managed_by_workflow");
    }
  }

  private static boolean changed(String requested, String existing) {
    if (EmptyChecks.isNull(requested)) {
      return false;
    }
    String normalizedRequest = Texts.hasText(requested) ? requested.trim() : null;
    String normalizedExisting = Texts.hasText(existing) ? existing.trim() : null;
    return !Objects.equals(normalizedRequest, normalizedExisting);
  }

  @Override
  public void toggle(Long id, String tenantId, Boolean enabled) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    ConsoleRequestMetadata requestMetadata = requestMetadataResolver.current();
    String operator = requestMetadata.operatorId();
    int rows = jobDefinitionMapper.toggleEnabled(resolved, id, enabled, operator);
    if (rows == 0) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.job.definition_not_found");
    }
    JobDefinitionEntity entity = jobDefinitionMapper.selectById(resolved, id);
    if (entity != null) {
      cacheInvalidationService.evictJobDefinition(resolved, entity.getJobCode());
    }
  }

  @Override
  public int batchToggle(String tenantId, List<Long> ids, Boolean enabled) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    ConsoleRequestMetadata requestMetadata = requestMetadataResolver.current();
    String operator = requestMetadata.operatorId();
    int rows = jobDefinitionMapper.batchToggleEnabled(resolved, ids, enabled, operator);
    cacheInvalidationService.evictAllJobDefinitions(resolved);
    return rows;
  }

  @Override
  @Transactional
  public ConsoleJobDefinitionResponse copy(Long id, String tenantId, String newJobCode) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    JobDefinitionEntity selectedSource = jobDefinitionMapper.selectById(resolved, id);
    JobDefinitionEntity source =
        Guard.requireFound(selectedSource, "source job definition not found");
    boolean resolveRouting = resourceAssignmentGuard.shouldResolveRoutingForCopy();
    JobDefinitionEntity existing = jobDefinitionMapper.selectByUniqueKey(resolved, newJobCode);
    if (existing != null) {
      throw BizException.of(ResultCode.CONFLICT, "error.job.code_already_exists", newJobCode);
    }
    ConsoleRequestMetadata requestMetadata = requestMetadataResolver.current();
    String operator = requestMetadata.operatorId();
    jobDefinitionMapper.copyJobDefinition(resolved, id, newJobCode, operator);
    if (resolveRouting) {
      jobDefinitionMapper.clearPhysicalResourceAssignment(resolved, newJobCode, operator);
    }
    JobDefinitionEntity selectedCopy = jobDefinitionMapper.selectByUniqueKey(resolved, newJobCode);
    JobDefinitionEntity copied =
        Guard.requireFound(selectedCopy, "source job definition not found");
    List<JobDependencyRuleRequest> sourceDependencyRules = resolveExistingDependencyRules(source);
    replaceDependencyRules(resolved, newJobCode, sourceDependencyRules);
    cacheInvalidationService.evictJobDefinition(resolved, newJobCode);
    singleJobWorkflowProvisioner.provision(copied);
    jobDefinitionMapper.copyJobMonitoringPolicy(resolved, id, copied.getId(), operator);
    JobDefinitionEntity refreshedCopy = jobDefinitionMapper.selectById(resolved, copied.getId());
    copied = Guard.requireFound(refreshedCopy, "copied job definition not found");
    return toResponse(copied);
  }

  @Override
  @Transactional
  public ConsoleJobDefinitionResponse copyWithOverrides(Long id, JobDefinitionCopyRequest request) {
    resourceAssignmentGuard.assertCopyOverridesAllowed(
        request.getQueueCode(), request.getWorkerGroup());
    String resolved = tenantGuard.resolveTenant(request.getTenantId());
    JobDefinitionEntity selectedSource = jobDefinitionMapper.selectById(resolved, id);
    JobDefinitionEntity source =
        Guard.requireFound(selectedSource, "source job definition not found");
    boolean resolveRouting = resourceAssignmentGuard.shouldResolveRoutingForCopy();
    JobDefinitionEntity existing =
        jobDefinitionMapper.selectByUniqueKey(resolved, request.getNewJobCode());
    if (existing != null) {
      throw BizException.of(
          ResultCode.CONFLICT,
          ResultCode.CONFLICT.detailKey(),
          "job code already exists: " + request.getNewJobCode());
    }
    ConsoleRequestMetadata requestMetadata = requestMetadataResolver.current();
    String operator = requestMetadata.operatorId();
    jobDefinitionMapper.copyJobDefinition(resolved, id, request.getNewJobCode(), operator);
    if (resolveRouting) {
      jobDefinitionMapper.clearPhysicalResourceAssignment(
          resolved, request.getNewJobCode(), operator);
    }
    JobDefinitionEntity selectedCopy =
        jobDefinitionMapper.selectByUniqueKey(resolved, request.getNewJobCode());
    JobDefinitionEntity copied =
        Guard.requireFound(selectedCopy, "source job definition not found");
    String newJobCode = request.getNewJobCode();
    List<JobDependencyRuleRequest> sourceDependencyRules = resolveExistingDependencyRules(source);
    replaceDependencyRules(resolved, newJobCode, sourceDependencyRules);
    cacheInvalidationService.evictJobDefinition(resolved, request.getNewJobCode());
    singleJobWorkflowProvisioner.provision(copied);
    jobDefinitionMapper.copyJobMonitoringPolicy(resolved, id, copied.getId(), operator);
    JobDefinitionEntity refreshedCopy = jobDefinitionMapper.selectById(resolved, copied.getId());
    copied = Guard.requireFound(refreshedCopy, "copied job definition not found");
    // Apply overrides
    JobDefinitionMaintenanceUpdateParam param = new JobDefinitionMaintenanceUpdateParam();
    param.setTenantId(resolved);
    param.setJobCode(request.getNewJobCode());
    param.setDependsOnJobCode(copied.getDependsOnJobCode());
    param.setJobName(request.getJobName() != null ? request.getJobName() : copied.getJobName());
    param.setQueueCode(
        request.getQueueCode() != null
            ? CodeNormalizer.toConfigFormOrNull(request.getQueueCode())
            : copied.getQueueCode());
    param.setWorkerGroup(
        request.getWorkerGroup() != null
            ? CodeNormalizer.toUpperOrNull(request.getWorkerGroup())
            : copied.getWorkerGroup());
    param.setScheduleExpr(
        request.getScheduleExpr() != null ? request.getScheduleExpr() : copied.getScheduleExpr());
    param.setCalendarCode(
        request.getCalendarCode() != null
            ? CodeNormalizer.toConfigFormOrNull(request.getCalendarCode())
            : copied.getCalendarCode());
    param.setWindowCode(
        request.getWindowCode() != null
            ? CodeNormalizer.toConfigFormOrNull(request.getWindowCode())
            : copied.getWindowCode());
    param.setRetryPolicy(
        request.getRetryPolicy() != null ? request.getRetryPolicy() : copied.getRetryPolicy());
    param.setRetryMaxCount(
        request.getRetryMaxCount() != null
            ? request.getRetryMaxCount()
            : copied.getRetryMaxCount());
    param.setTimeoutSeconds(
        request.getTimeoutSeconds() != null
            ? request.getTimeoutSeconds()
            : copied.getTimeoutSeconds());
    param.setShardStrategy(copied.getShardStrategy());
    param.setExecutionMode(copied.getExecutionMode());
    param.setWatermarkField(copied.getWatermarkField());
    param.setDefaultParams(copied.getDefaultParams());
    param.setEnabled(request.getEnabled() != null ? request.getEnabled() : copied.getEnabled());
    param.setDescription(
        request.getDescription() != null ? request.getDescription() : copied.getDescription());
    param.setUpdatedBy(operator);
    jobDefinitionMapper.updateJobDefinitionMaintenance(param);
    Integer copiedSoftRuntimeSeconds = valueOrZero(copied.getSoftRuntimeSeconds());
    upsertMonitoringPolicy(resolved, copied.getId(), copied, copiedSoftRuntimeSeconds, operator);
    JobDefinitionEntity copiedDefinition =
        jobDefinitionMapper.selectByUniqueKey(resolved, request.getNewJobCode());
    return toResponse(copiedDefinition);
  }

  private ConsoleJobDefinitionResponse toResponse(JobDefinitionEntity e) {
    List<String> existingDependencies = resolveExistingDependencies(e);
    List<JobDependencyRuleResponse> existingDependencyResponses =
        resolveExistingDependencyResponses(e);
    boolean monitoringEnabled = ("CRON".equals(e.getScheduleType())
            && !Texts.hasText(e.getDependsOnJobCode())
            && EmptyChecks.isNotNull(e.getCompletionDeadlineLocalTime()))
        || (Texts.hasText(e.getDependsOnJobCode())
            && valueOrZero(e.getDependencyCompletionWindowSeconds()) > 0);
    String maskedDefaultParams = SecretMasking.maskSensitiveJson(e.getDefaultParams());
    ConsoleJobDefinitionResponse response = ConsoleJobDefinitionResponse.builder()
        .id(e.getId())
        .tenantId(e.getTenantId())
        .jobCode(e.getJobCode())
        .dependsOnJobCode(e.getDependsOnJobCode())
        .dependsOnJobCodes(existingDependencies)
        .jobDependencyRules(existingDependencyResponses)
        .jobName(e.getJobName())
        .jobType(e.getJobType())
        .bizType(e.getBizType())
        .queueCode(e.getQueueCode())
        .workerGroup(e.getWorkerGroup())
        .scheduleType(e.getScheduleType())
        .scheduleExpr(e.getScheduleExpr())
        .timezone(e.getTimezone())
        .calendarCode(e.getCalendarCode())
        .windowCode(e.getWindowCode())
        .triggerMode(e.getTriggerMode())
        .dagEnabled(e.getDagEnabled())
        .retryPolicy(e.getRetryPolicy())
        .retryMaxCount(e.getRetryMaxCount())
        .timeoutSeconds(e.getTimeoutSeconds())
        .softRuntimeSeconds(valueOrZero(e.getSoftRuntimeSeconds()))
        .softRuntimeSeverity(severityOrWarn(e.getSoftRuntimeSeverity()))
        .startGraceSeconds(valueOrZero(e.getStartGraceSeconds()))
        .startGraceSeverity(severityOrWarn(e.getStartGraceSeverity()))
        .completionDeadlineEnabled(monitoringEnabled)
        .completionDeadlineLocalTime(e.getCompletionDeadlineLocalTime())
        .completionDeadlineDayOffset(valueOrZero(e.getCompletionDeadlineDayOffset()))
        .completionDeadlineSeverity(severityOrWarn(e.getCompletionDeadlineSeverity()))
        .dependencyCompletionWindowSeconds(valueOrZero(e.getDependencyCompletionWindowSeconds()))
        .shardStrategy(e.getShardStrategy())
        .executionMode(e.getExecutionMode())
        .watermarkField(e.getWatermarkField())
        .executionHandler(e.getExecutionHandler())
        .paramSchema(e.getParamSchema())
        .defaultParams(maskedDefaultParams)
        .priority(e.getPriority())
        .version(e.getVersion())
        .enabled(e.getEnabled())
        .description(e.getDescription())
        .createdAt(e.getCreatedAt())
        .updatedAt(e.getUpdatedAt())
        .defaultWorkflowDefinitionId(e.getDefaultWorkflowDefinitionId())
        .build();
    return response;
  }

  private List<JobDependencyRuleRequest> resolveCreateDependencyRules(
      JobDefinitionCreateRequest request) {
    if (EmptyChecks.isNotNull(request.getJobDependencyRules())) {
      List<JobDependencyRuleRequest> rules =
          normalizeDependencyRules(request.getJobDependencyRules());
      List<String> legacyCodes =
          normalizeDependencies(request.getDependsOnJobCodes(), request.getDependsOnJobCode());
      if (EmptyChecks.isNotEmpty(legacyCodes)) {
        List<String> ruleUpstreamCodes =
            rules.stream().map(JobDependencyRuleRequest::upstreamJobCode).toList();
        if (!legacyCodes.equals(ruleUpstreamCodes)) {
          throw BizException.of(ResultCode.INVALID_ARGUMENT, INVALID_ARGUMENT_KEY);
        }
      }
      return rules;
    }
    List<String> dependencyCodes =
        normalizeDependencies(request.getDependsOnJobCodes(), request.getDependsOnJobCode());
    return dependencyCodes.stream()
        .map(code -> new JobDependencyRuleRequest(code, "SAME_DAY"))
        .toList();
  }

  private List<JobDependencyRuleRequest> resolveUpdateDependencyRules(
      JobDefinitionUpdateRequest request, JobDefinitionEntity existing) {
    if (EmptyChecks.isNotNull(request.getJobDependencyRules())) {
      List<JobDependencyRuleRequest> rules =
          normalizeDependencyRules(request.getJobDependencyRules());
      List<String> legacyCodes =
          normalizeDependencies(request.getDependsOnJobCodes(), request.getDependsOnJobCode());
      if (EmptyChecks.isNotEmpty(legacyCodes)) {
        List<String> ruleUpstreamCodes =
            rules.stream().map(JobDependencyRuleRequest::upstreamJobCode).toList();
        if (!legacyCodes.equals(ruleUpstreamCodes)) {
          throw BizException.of(ResultCode.INVALID_ARGUMENT, INVALID_ARGUMENT_KEY);
        }
      }
      return rules;
    }
    if (EmptyChecks.isNotNull(request.getDependsOnJobCodes())) {
      List<String> requestedCodes = normalizeDependencies(request.getDependsOnJobCodes(), null);
      return preserveAlignments(requestedCodes, existing);
    }
    if (EmptyChecks.isNotNull(request.getDependsOnJobCode())) {
      List<String> requestedCodes = normalizeDependencies(null, request.getDependsOnJobCode());
      return preserveAlignments(requestedCodes, existing);
    }
    return resolveExistingDependencyRules(existing);
  }

  private static List<JobDependencyRuleRequest> normalizeDependencyRules(
      List<JobDependencyRuleRequest> rules) {
    LinkedHashSet<String> seen = new LinkedHashSet<>();
    if (rules.stream()
        .anyMatch(rule -> EmptyChecks.isNull(rule)
            || EmptyChecks.isNull(CodeNormalizer.trimToNull(rule.upstreamJobCode()))
            || !Set.of("SAME_DAY", "PREVIOUS_BIZ_DAY").contains(rule.bizDateAlignment()))) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, INVALID_ARGUMENT_KEY);
    }
    return rules.stream()
        .map(rule -> {
          String upstreamJobCode = CodeNormalizer.trimToNull(rule.upstreamJobCode());
          String trimmedBizDateAlignment = rule.bizDateAlignment().trim();
          String normalizedBizDateAlignment = trimmedBizDateAlignment.toUpperCase(Locale.ROOT);
          return new JobDependencyRuleRequest(upstreamJobCode, normalizedBizDateAlignment);
        })
        .filter(rule ->
            EmptyChecks.isNotNull(rule.upstreamJobCode()) && seen.add(rule.upstreamJobCode()))
        .toList();
  }

  private static List<JobDependencyRuleRequest> preserveAlignments(
      List<String> codes, JobDefinitionEntity existing) {
    Map<String, String> alignments = new HashMap<>();
    for (JobDependencyRuleResponse rule : resolveExistingDependencyResponses(existing)) {
      alignments.put(rule.upstreamJobCode(), rule.bizDateAlignment());
    }
    return codes.stream()
        .map(code -> new JobDependencyRuleRequest(code, alignments.getOrDefault(code, "SAME_DAY")))
        .toList();
  }

  private static List<String> normalizeDependencies(List<String> codes, String legacyCode) {
    LinkedHashSet<String> normalized = new LinkedHashSet<>();
    if (EmptyChecks.isNotNull(codes)) {
      for (String code : codes) {
        String value = CodeNormalizer.trimToNull(code);
        if (EmptyChecks.isNotNull(value)) normalized.add(value);
      }
    } else {
      String value = CodeNormalizer.trimToNull(legacyCode);
      if (EmptyChecks.isNotNull(value)) normalized.add(value);
    }
    if (normalized.size() > 32) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, INVALID_ARGUMENT_KEY);
    }
    return List.copyOf(normalized);
  }

  private static List<String> resolveExistingDependencies(JobDefinitionEntity entity) {
    if (EmptyChecks.isNotNull(entity.getDependsOnJobCodes())
        && EmptyChecks.isNotEmpty(entity.getDependsOnJobCodes())) {
      return entity.getDependsOnJobCodes();
    }
    String legacy = CodeNormalizer.trimToNull(entity.getDependsOnJobCode());
    return EmptyChecks.isNull(legacy) ? List.of() : List.of(legacy);
  }

  private static List<JobDependencyRuleRequest> resolveExistingDependencyRules(
      JobDefinitionEntity entity) {
    List<JobDependencyRuleResponse> existingDependencyRules =
        resolveExistingDependencyResponses(entity);
    return existingDependencyRules.stream()
        .map(rule -> new JobDependencyRuleRequest(rule.upstreamJobCode(), rule.bizDateAlignment()))
        .toList();
  }

  private static List<JobDependencyRuleResponse> resolveExistingDependencyResponses(
      JobDefinitionEntity entity) {
    if (EmptyChecks.isNotNull(entity.getJobDependencyRules())
        && EmptyChecks.isNotEmpty(entity.getJobDependencyRules())) {
      return entity.getJobDependencyRules();
    }
    List<String> existingDependencyCodes = resolveExistingDependencies(entity);
    return existingDependencyCodes.stream()
        .map(code -> new JobDependencyRuleResponse(code, "SAME_DAY"))
        .toList();
  }

  private void validateDependencies(String tenantId, String downstream, List<String> upstreams) {
    jobDefinitionMapper.lockDependencyGraph(tenantId);
    Map<String, Set<String>> graph = new HashMap<>();
    for (JobDefinitionMapper.JobDependencyEdge edge :
        jobDefinitionMapper.selectAllDependencies(tenantId)) {
      if (!edge.downstreamJobCode().equals(downstream)) {
        graph
            .computeIfAbsent(edge.downstreamJobCode(), ignored -> new LinkedHashSet<>())
            .add(edge.upstreamJobCode());
      }
    }
    for (String upstream : upstreams) {
      boolean upstreamIsSelfOrUnknown = downstream.equals(upstream)
          || EmptyChecks.isNull(jobDefinitionMapper.selectByUniqueKey(tenantId, upstream));
      if (upstreamIsSelfOrUnknown) {
        throw BizException.of(ResultCode.INVALID_ARGUMENT, INVALID_ARGUMENT_KEY);
      }
      if (reaches(graph, upstream, downstream, new LinkedHashSet<>())) {
        throw BizException.of(ResultCode.INVALID_ARGUMENT, INVALID_ARGUMENT_KEY);
      }
    }
  }

  private static boolean reaches(
      Map<String, Set<String>> graph, String current, String target, Set<String> visited) {
    if (current.equals(target)) return true;
    if (!visited.add(current)) return false;
    for (String next : graph.getOrDefault(current, Set.of())) {
      if (reaches(graph, next, target, visited)) return true;
    }
    return false;
  }

  private void replaceDependencyRules(
      String tenantId, String downstream, List<JobDependencyRuleRequest> rules) {
    dependencyRuleWriter.replace(tenantId, downstream, rules);
  }

  private static String resolveDefaultParams(String submitted, String stored) {
    if (!Texts.hasText(submitted)) {
      return null;
    }
    String resolved = EmptyChecks.isNull(stored)
        ? submitted
        : SecretMasking.preserveMaskedJson(submitted, stored);
    if (SecretMasking.containsMaskedSensitiveValue(resolved)) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, "error.config.masked_secret_requires_value");
    }
    return resolved;
  }

  private static Integer resolveCompletionDeadlineDayOffset(
      LocalTime deadlineTime, Integer requestedOffset, Integer existingOffset) {
    if (EmptyChecks.isNull(deadlineTime)) {
      return 0;
    }
    return EmptyChecks.isNull(requestedOffset) ? valueOrZero(existingOffset) : requestedOffset;
  }

  private static Integer resolveMonitoringValue(Integer requested, Integer existing) {
    return valueOrZero(EmptyChecks.isNull(requested) ? existing : requested);
  }

  private static LocalTime resolveCompletionDeadlineLocalTime(
      JobDefinitionUpdateRequest request, JobDefinitionEntity existing) {
    if (Boolean.FALSE.equals(request.getCompletionDeadlineEnabled())) {
      return null;
    }
    return EmptyChecks.isNull(request.getCompletionDeadlineLocalTime())
        ? existing.getCompletionDeadlineLocalTime()
        : request.getCompletionDeadlineLocalTime();
  }

  private static Integer valueOrZero(Integer value) {
    return EmptyChecks.isNull(value) ? 0 : value;
  }

  private static int resolveStartGraceSeconds(
      String scheduleType,
      String dependsOnJobCode,
      Integer requested,
      JobMonitoringDefaultsProperties defaults) {
    if (EmptyChecks.isNotNull(requested)) {
      return requested;
    }
    return "CRON".equals(scheduleType) || Texts.hasText(dependsOnJobCode)
        ? defaults.getStartGraceSeconds()
        : 0;
  }

  private static int resolveSoftRuntimeSeconds(
      Integer requested, JobMonitoringDefaultsProperties defaults) {
    return EmptyChecks.isNull(requested) ? defaults.getSoftRuntimeSeconds() : requested;
  }

  /** 策略资格由运行事实判定；配置侧只校验两种完成期限不能同时启用。 */
  private static void validateMonitoringCompletionMode(
      LocalTime completionDeadlineLocalTime, Integer dependencyCompletionWindowSeconds) {
    boolean localDeadlineConfigured = EmptyChecks.isNotNull(completionDeadlineLocalTime);
    boolean relativeWindowConfigured = valueOrZero(dependencyCompletionWindowSeconds) > 0;
    if (localDeadlineConfigured && relativeWindowConfigured) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, "error.job.monitoring_completion_modes_exclusive");
    }
  }

  private static String resolveMonitoringSeverity(String requested, String existing) {
    return severityOrWarn(EmptyChecks.isNull(requested) ? existing : requested);
  }

  private static String severityOrWarn(String severity) {
    return EmptyChecks.isBlank(severity) ? "WARN" : severity;
  }
}

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
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
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

  private final JobDefinitionMapper jobDefinitionMapper;
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
    JobDefinitionEntity entity = Guard.requireFound(
        jobDefinitionMapper.selectById(resolvedTenant, id), "job definition not found");
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
    validateScheduledMonitoring(
        request.getScheduleType(),
        dependencyCodes.isEmpty() ? null : dependencyCodes.get(0),
        request.getStartGraceSeconds(),
        request.getCompletionDeadlineLocalTime(),
        request.getDependencyCompletionWindowSeconds());
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
    entity.setDependsOnJobCode(dependencyCodes.isEmpty() ? null : dependencyCodes.get(0));
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
    entity.setSoftRuntimeSeconds(
        resolveSoftRuntimeSeconds(request.getSoftRuntimeSeconds(), monitoringDefaults));
    entity.setSoftRuntimeSeverity(severityOrWarn(request.getSoftRuntimeSeverity()));
    entity.setStartGraceSeconds(resolveStartGraceSeconds(
        request.getScheduleType(),
        dependencyCodes.isEmpty() ? null : dependencyCodes.get(0),
        request.getStartGraceSeconds(),
        monitoringDefaults));
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
    entity.setDefaultParams(resolveDefaultParams(request.getDefaultParams(), null));
    entity.setPriority(request.getPriority() == null ? 5 : request.getPriority());
    entity.setEnabled(request.getEnabled() != null && request.getEnabled());
    entity.setDescription(request.getDescription());
    String operator = requestMetadataResolver.current().operatorId();
    entity.setCreatedBy(operator);
    entity.setUpdatedBy(operator);
    jobDefinitionMapper.insert(entity);
    replaceDependencyRules(tenantId, entity.getJobCode(), dependencyRules);
    singleJobWorkflowProvisioner.provision(entity);
    jobDefinitionMapper.upsertJobMonitoringPolicy(JobMonitoringPolicyUpsertParam.builder()
        .tenantId(tenantId)
        .jobDefinitionId(entity.getId())
        .softRuntimeSeconds(valueOrZero(entity.getSoftRuntimeSeconds()))
        .softRuntimeSeverity(severityOrWarn(entity.getSoftRuntimeSeverity()))
        .startGraceSeconds(valueOrZero(entity.getStartGraceSeconds()))
        .startGraceSeverity(severityOrWarn(entity.getStartGraceSeverity()))
        .completionDeadlineLocalTime(entity.getCompletionDeadlineLocalTime())
        .completionDeadlineDayOffset(valueOrZero(entity.getCompletionDeadlineDayOffset()))
        .dependencyCompletionWindowSeconds(
            valueOrZero(entity.getDependencyCompletionWindowSeconds()))
        .completionDeadlineSeverity(severityOrWarn(entity.getCompletionDeadlineSeverity()))
        .updatedBy(operator)
        .build());
    cacheInvalidationService.evictJobDefinition(tenantId, entity.getJobCode());
    return toResponse(jobDefinitionMapper.selectById(tenantId, entity.getId()));
  }

  @Override
  @Transactional
  public ConsoleJobDefinitionResponse update(Long id, JobDefinitionUpdateRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    JobDefinitionEntity existing = Guard.requireFound(
        jobDefinitionMapper.selectById(tenantId, id), "job definition not found");
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
    String dependsOnJobCode = dependencyCodes.isEmpty() ? null : dependencyCodes.get(0);
    LocalTime completionDeadlineLocalTime = resolveCompletionDeadlineLocalTime(request, existing);
    if (Boolean.TRUE.equals(request.getCompletionDeadlineEnabled())
        && EmptyChecks.isNull(completionDeadlineLocalTime)) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.invalid_argument");
    }
    validateScheduledMonitoring(
        existing.getScheduleType(),
        dependsOnJobCode,
        EmptyChecks.isNull(request.getStartGraceSeconds())
            ? existing.getStartGraceSeconds()
            : request.getStartGraceSeconds(),
        completionDeadlineLocalTime,
        EmptyChecks.isNull(request.getDependencyCompletionWindowSeconds())
            ? existing.getDependencyCompletionWindowSeconds()
            : request.getDependencyCompletionWindowSeconds());
    String operator = requestMetadataResolver.current().operatorId();
    JobDefinitionMaintenanceUpdateParam param = new JobDefinitionMaintenanceUpdateParam();
    param.setTenantId(tenantId);
    param.setJobCode(existing.getJobCode());
    param.setDependsOnJobCode(dependsOnJobCode);
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
    param.setSoftRuntimeSeconds(
        resolveMonitoringValue(request.getSoftRuntimeSeconds(), existing.getSoftRuntimeSeconds()));
    param.setSoftRuntimeSeverity(resolveMonitoringSeverity(
        request.getSoftRuntimeSeverity(), existing.getSoftRuntimeSeverity()));
    param.setStartGraceSeconds(
        resolveMonitoringValue(request.getStartGraceSeconds(), existing.getStartGraceSeconds()));
    param.setStartGraceSeverity(resolveMonitoringSeverity(
        request.getStartGraceSeverity(), existing.getStartGraceSeverity()));
    param.setCompletionDeadlineLocalTime(completionDeadlineLocalTime);
    param.setCompletionDeadlineDayOffset(resolveCompletionDeadlineDayOffset(
        completionDeadlineLocalTime,
        request.getCompletionDeadlineDayOffset(),
        existing.getCompletionDeadlineDayOffset()));
    param.setCompletionDeadlineSeverity(resolveMonitoringSeverity(
        request.getCompletionDeadlineSeverity(), existing.getCompletionDeadlineSeverity()));
    param.setDependencyCompletionWindowSeconds(
        Texts.hasText(dependsOnJobCode)
            ? resolveMonitoringValue(
                request.getDependencyCompletionWindowSeconds(),
                existing.getDependencyCompletionWindowSeconds())
            : 0);
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
    if (EmptyChecks.isNotNull(request.getDefaultParams())) {
      param.setDefaultParams(
          resolveDefaultParams(request.getDefaultParams(), existing.getDefaultParams()));
      param.setDefaultParamsSpecified(true);
    }
    param.setEnabled(request.getEnabled() != null ? request.getEnabled() : existing.getEnabled());
    param.setDescription(
        request.getDescription() != null ? request.getDescription() : existing.getDescription());
    param.setUpdatedBy(operator);
    jobDefinitionMapper.updateJobDefinitionMaintenance(param);
    replaceDependencyRules(tenantId, existing.getJobCode(), dependencyRules);
    jobDefinitionMapper.upsertJobMonitoringPolicy(JobMonitoringPolicyUpsertParam.builder()
        .tenantId(tenantId)
        .jobDefinitionId(id)
        .softRuntimeSeconds(valueOrZero(param.getSoftRuntimeSeconds()))
        .softRuntimeSeverity(severityOrWarn(param.getSoftRuntimeSeverity()))
        .startGraceSeconds(valueOrZero(param.getStartGraceSeconds()))
        .startGraceSeverity(severityOrWarn(param.getStartGraceSeverity()))
        .completionDeadlineLocalTime(param.getCompletionDeadlineLocalTime())
        .completionDeadlineDayOffset(valueOrZero(param.getCompletionDeadlineDayOffset()))
        .dependencyCompletionWindowSeconds(
            valueOrZero(param.getDependencyCompletionWindowSeconds()))
        .completionDeadlineSeverity(severityOrWarn(param.getCompletionDeadlineSeverity()))
        .updatedBy(operator)
        .build());
    cacheInvalidationService.evictJobDefinition(tenantId, existing.getJobCode());
    return toResponse(jobDefinitionMapper.selectById(tenantId, id));
  }

  private static void assertWorkflowOwnedScheduleUnchanged(
      JobDefinitionUpdateRequest request, JobDefinitionEntity existing) {
    if (changed(request.getScheduleType(), existing.getScheduleType())
        || changed(request.getScheduleExpr(), existing.getScheduleExpr())
        || changed(request.getTimezone(), existing.getTimezone())
        || changed(request.getTriggerMode(), existing.getTriggerMode())
        || changed(request.getCalendarCode(), existing.getCalendarCode())
        || changed(request.getWindowCode(), existing.getWindowCode())) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.job.schedule_managed_by_workflow");
    }
  }

  private static boolean changed(String requested, String existing) {
    if (requested == null) {
      return false;
    }
    String normalizedRequest = Texts.hasText(requested) ? requested.trim() : null;
    String normalizedExisting = Texts.hasText(existing) ? existing.trim() : null;
    return !Objects.equals(normalizedRequest, normalizedExisting);
  }

  @Override
  public void toggle(Long id, String tenantId, Boolean enabled) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    String operator = requestMetadataResolver.current().operatorId();
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
    String operator = requestMetadataResolver.current().operatorId();
    int rows = jobDefinitionMapper.batchToggleEnabled(resolved, ids, enabled, operator);
    cacheInvalidationService.evictAllJobDefinitions(resolved);
    return rows;
  }

  @Override
  @Transactional
  public ConsoleJobDefinitionResponse copy(Long id, String tenantId, String newJobCode) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    JobDefinitionEntity source = Guard.requireFound(
        jobDefinitionMapper.selectById(resolved, id), "source job definition not found");
    boolean resolveRouting = resourceAssignmentGuard.shouldResolveRoutingForCopy();
    JobDefinitionEntity existing = jobDefinitionMapper.selectByUniqueKey(resolved, newJobCode);
    if (existing != null) {
      throw BizException.of(ResultCode.CONFLICT, "error.job.code_already_exists", newJobCode);
    }
    String operator = requestMetadataResolver.current().operatorId();
    jobDefinitionMapper.copyJobDefinition(resolved, id, newJobCode, operator);
    if (resolveRouting) {
      jobDefinitionMapper.clearPhysicalResourceAssignment(resolved, newJobCode, operator);
    }
    JobDefinitionEntity copied = Guard.requireFound(
        jobDefinitionMapper.selectByUniqueKey(resolved, newJobCode),
        "source job definition not found");
    replaceDependencyRules(resolved, newJobCode, resolveExistingDependencyRules(source));
    singleJobWorkflowProvisioner.provision(copied);
    jobDefinitionMapper.copyJobMonitoringPolicy(resolved, id, copied.getId(), operator);
    copied = Guard.requireFound(
        jobDefinitionMapper.selectById(resolved, copied.getId()),
        "copied job definition not found");
    cacheInvalidationService.evictJobDefinition(resolved, newJobCode);
    return toResponse(copied);
  }

  @Override
  @Transactional
  public ConsoleJobDefinitionResponse copyWithOverrides(Long id, JobDefinitionCopyRequest request) {
    resourceAssignmentGuard.assertCopyOverridesAllowed(
        request.getQueueCode(), request.getWorkerGroup());
    String resolved = tenantGuard.resolveTenant(request.getTenantId());
    JobDefinitionEntity source = Guard.requireFound(
        jobDefinitionMapper.selectById(resolved, id), "source job definition not found");
    boolean resolveRouting = resourceAssignmentGuard.shouldResolveRoutingForCopy();
    JobDefinitionEntity existing =
        jobDefinitionMapper.selectByUniqueKey(resolved, request.getNewJobCode());
    if (existing != null) {
      throw BizException.of(
          ResultCode.CONFLICT,
          ResultCode.CONFLICT.detailKey(),
          "job code already exists: " + request.getNewJobCode());
    }
    String operator = requestMetadataResolver.current().operatorId();
    jobDefinitionMapper.copyJobDefinition(resolved, id, request.getNewJobCode(), operator);
    if (resolveRouting) {
      jobDefinitionMapper.clearPhysicalResourceAssignment(
          resolved, request.getNewJobCode(), operator);
    }
    JobDefinitionEntity copied = Guard.requireFound(
        jobDefinitionMapper.selectByUniqueKey(resolved, request.getNewJobCode()),
        "source job definition not found");
    replaceDependencyRules(
        resolved, request.getNewJobCode(), resolveExistingDependencyRules(source));
    singleJobWorkflowProvisioner.provision(copied);
    jobDefinitionMapper.copyJobMonitoringPolicy(resolved, id, copied.getId(), operator);
    copied = Guard.requireFound(
        jobDefinitionMapper.selectById(resolved, copied.getId()),
        "copied job definition not found");
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
    jobDefinitionMapper.upsertJobMonitoringPolicy(JobMonitoringPolicyUpsertParam.builder()
        .tenantId(resolved)
        .jobDefinitionId(copied.getId())
        .softRuntimeSeconds(valueOrZero(copied.getSoftRuntimeSeconds()))
        .softRuntimeSeverity(severityOrWarn(copied.getSoftRuntimeSeverity()))
        .startGraceSeconds(valueOrZero(copied.getStartGraceSeconds()))
        .startGraceSeverity(severityOrWarn(copied.getStartGraceSeverity()))
        .completionDeadlineLocalTime(copied.getCompletionDeadlineLocalTime())
        .completionDeadlineDayOffset(valueOrZero(copied.getCompletionDeadlineDayOffset()))
        .dependencyCompletionWindowSeconds(
            valueOrZero(copied.getDependencyCompletionWindowSeconds()))
        .completionDeadlineSeverity(severityOrWarn(copied.getCompletionDeadlineSeverity()))
        .updatedBy(operator)
        .build());
    cacheInvalidationService.evictJobDefinition(resolved, request.getNewJobCode());
    return toResponse(jobDefinitionMapper.selectByUniqueKey(resolved, request.getNewJobCode()));
  }

  private ConsoleJobDefinitionResponse toResponse(JobDefinitionEntity e) {
    return new ConsoleJobDefinitionResponse(
        e.getId(),
        e.getTenantId(),
        e.getJobCode(),
        e.getDependsOnJobCode(),
        resolveExistingDependencies(e),
        resolveExistingDependencyResponses(e),
        e.getJobName(),
        e.getJobType(),
        e.getBizType(),
        e.getQueueCode(),
        e.getWorkerGroup(),
        e.getScheduleType(),
        e.getScheduleExpr(),
        e.getTimezone(),
        e.getCalendarCode(),
        e.getWindowCode(),
        e.getTriggerMode(),
        e.getDagEnabled(),
        e.getRetryPolicy(),
        e.getRetryMaxCount(),
        e.getTimeoutSeconds(),
        valueOrZero(e.getSoftRuntimeSeconds()),
        severityOrWarn(e.getSoftRuntimeSeverity()),
        valueOrZero(e.getStartGraceSeconds()),
        severityOrWarn(e.getStartGraceSeverity()),
        ("CRON".equals(e.getScheduleType())
                && !Texts.hasText(e.getDependsOnJobCode())
                && EmptyChecks.isNotNull(e.getCompletionDeadlineLocalTime()))
            || (Texts.hasText(e.getDependsOnJobCode())
                && valueOrZero(e.getDependencyCompletionWindowSeconds()) > 0),
        e.getCompletionDeadlineLocalTime(),
        valueOrZero(e.getCompletionDeadlineDayOffset()),
        severityOrWarn(e.getCompletionDeadlineSeverity()),
        valueOrZero(e.getDependencyCompletionWindowSeconds()),
        e.getShardStrategy(),
        e.getExecutionMode(),
        e.getWatermarkField(),
        e.getExecutionHandler(),
        e.getParamSchema(),
        SecretMasking.maskSensitiveJson(e.getDefaultParams()),
        e.getPriority(),
        e.getVersion(),
        e.getEnabled(),
        e.getDescription(),
        e.getCreatedAt(),
        e.getUpdatedAt(),
        e.getDefaultWorkflowDefinitionId());
  }

  private List<JobDependencyRuleRequest> resolveCreateDependencyRules(
      JobDefinitionCreateRequest request) {
    if (request.getJobDependencyRules() != null) {
      List<JobDependencyRuleRequest> rules =
          normalizeDependencyRules(request.getJobDependencyRules());
      List<String> legacyCodes =
          normalizeDependencies(request.getDependsOnJobCodes(), request.getDependsOnJobCode());
      if (!legacyCodes.isEmpty()
          && !legacyCodes.equals(
              rules.stream().map(JobDependencyRuleRequest::upstreamJobCode).toList())) {
        throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.invalid_argument");
      }
      return rules;
    }
    return normalizeDependencies(request.getDependsOnJobCodes(), request.getDependsOnJobCode())
        .stream()
        .map(code -> new JobDependencyRuleRequest(code, "SAME_DAY"))
        .toList();
  }

  private List<JobDependencyRuleRequest> resolveUpdateDependencyRules(
      JobDefinitionUpdateRequest request, JobDefinitionEntity existing) {
    if (request.getJobDependencyRules() != null) {
      List<JobDependencyRuleRequest> rules =
          normalizeDependencyRules(request.getJobDependencyRules());
      List<String> legacyCodes =
          normalizeDependencies(request.getDependsOnJobCodes(), request.getDependsOnJobCode());
      if (!legacyCodes.isEmpty()
          && !legacyCodes.equals(
              rules.stream().map(JobDependencyRuleRequest::upstreamJobCode).toList())) {
        throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.invalid_argument");
      }
      return rules;
    }
    if (request.getDependsOnJobCodes() != null) {
      return preserveAlignments(
          normalizeDependencies(request.getDependsOnJobCodes(), null), existing);
    }
    if (request.getDependsOnJobCode() != null) {
      return preserveAlignments(
          normalizeDependencies(null, request.getDependsOnJobCode()), existing);
    }
    return resolveExistingDependencyRules(existing);
  }

  private static List<JobDependencyRuleRequest> normalizeDependencyRules(
      List<JobDependencyRuleRequest> rules) {
    LinkedHashSet<String> seen = new LinkedHashSet<>();
    if (rules.stream()
        .anyMatch(rule -> rule == null
            || CodeNormalizer.trimToNull(rule.upstreamJobCode()) == null
            || !Set.of("SAME_DAY", "PREVIOUS_BIZ_DAY").contains(rule.bizDateAlignment()))) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.invalid_argument");
    }
    return rules.stream()
        .map(rule -> new JobDependencyRuleRequest(
            CodeNormalizer.trimToNull(rule.upstreamJobCode()),
            rule.bizDateAlignment().trim().toUpperCase(java.util.Locale.ROOT)))
        .filter(rule -> rule.upstreamJobCode() != null && seen.add(rule.upstreamJobCode()))
        .toList();
  }

  private static List<JobDependencyRuleRequest> preserveAlignments(
      List<String> codes, JobDefinitionEntity existing) {
    Map<String, String> alignments = new java.util.HashMap<>();
    for (JobDependencyRuleResponse rule : resolveExistingDependencyResponses(existing)) {
      alignments.put(rule.upstreamJobCode(), rule.bizDateAlignment());
    }
    return codes.stream()
        .map(code -> new JobDependencyRuleRequest(code, alignments.getOrDefault(code, "SAME_DAY")))
        .toList();
  }

  private static List<String> normalizeDependencies(List<String> codes, String legacyCode) {
    LinkedHashSet<String> normalized = new LinkedHashSet<>();
    if (codes != null) {
      for (String code : codes) {
        String value = CodeNormalizer.trimToNull(code);
        if (value != null) normalized.add(value);
      }
    } else {
      String value = CodeNormalizer.trimToNull(legacyCode);
      if (value != null) normalized.add(value);
    }
    if (normalized.size() > 32) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.invalid_argument");
    }
    return List.copyOf(normalized);
  }

  private static List<String> resolveExistingDependencies(JobDefinitionEntity entity) {
    if (entity.getDependsOnJobCodes() != null && !entity.getDependsOnJobCodes().isEmpty()) {
      return entity.getDependsOnJobCodes();
    }
    String legacy = CodeNormalizer.trimToNull(entity.getDependsOnJobCode());
    return legacy == null ? List.of() : List.of(legacy);
  }

  private static List<JobDependencyRuleRequest> resolveExistingDependencyRules(
      JobDefinitionEntity entity) {
    return resolveExistingDependencyResponses(entity).stream()
        .map(rule -> new JobDependencyRuleRequest(rule.upstreamJobCode(), rule.bizDateAlignment()))
        .toList();
  }

  private static List<JobDependencyRuleResponse> resolveExistingDependencyResponses(
      JobDefinitionEntity entity) {
    if (entity.getJobDependencyRules() != null
        && !entity.getJobDependencyRules().isEmpty()) {
      return entity.getJobDependencyRules();
    }
    return resolveExistingDependencies(entity).stream()
        .map(code -> new JobDependencyRuleResponse(code, "SAME_DAY"))
        .toList();
  }

  private void validateDependencies(String tenantId, String downstream, List<String> upstreams) {
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
      if (downstream.equals(upstream)
          || jobDefinitionMapper.selectByUniqueKey(tenantId, upstream) == null) {
        throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.invalid_argument");
      }
      if (reaches(graph, upstream, downstream, new LinkedHashSet<>())) {
        throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.invalid_argument");
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
    jobDefinitionMapper.deleteDependencies(tenantId, downstream);
    if (!rules.isEmpty()) {
      jobDefinitionMapper.insertDependencyRules(tenantId, downstream, rules);
    }
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

  private static void validateScheduledMonitoring(
      String scheduleType,
      String dependsOnJobCode,
      Integer startGraceSeconds,
      LocalTime completionDeadlineLocalTime,
      Integer dependencyCompletionWindowSeconds) {
    boolean cron = "CRON".equals(scheduleType);
    boolean dependency = Texts.hasText(dependsOnJobCode);
    boolean startGraceConfigured = valueOrZero(startGraceSeconds) > 0;
    boolean completionDeadlineConfigured = EmptyChecks.isNotNull(completionDeadlineLocalTime);
    boolean dependencyWindowConfigured = valueOrZero(dependencyCompletionWindowSeconds) > 0;
    boolean startGraceSupported = cron || dependency;
    boolean invalidDeadlineCombination = completionDeadlineConfigured && (!cron || dependency)
        || dependencyWindowConfigured && !dependency
        || completionDeadlineConfigured && dependencyWindowConfigured;
    if ((!startGraceSupported && startGraceConfigured) || invalidDeadlineCombination) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.invalid_argument");
    }
  }

  private static String resolveMonitoringSeverity(String requested, String existing) {
    return severityOrWarn(EmptyChecks.isNull(requested) ? existing : requested);
  }

  private static String severityOrWarn(String severity) {
    return EmptyChecks.isBlank(severity) ? "WARN" : severity;
  }
}

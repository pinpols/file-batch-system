package io.github.pinpols.batch.console.domain.job.infrastructure;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.CodeNormalizer;
import io.github.pinpols.batch.common.utils.Guard;
import io.github.pinpols.batch.console.domain.job.application.ConsoleJobDefinitionApplicationService;
import io.github.pinpols.batch.console.domain.job.entity.JobDefinitionEntity;
import io.github.pinpols.batch.console.domain.job.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.console.domain.job.param.JobDefinitionMaintenanceUpdateParam;
import io.github.pinpols.batch.console.domain.job.support.BuiltinTaskTypeGuard;
import io.github.pinpols.batch.console.domain.job.web.request.JobDefinitionCopyRequest;
import io.github.pinpols.batch.console.domain.job.web.request.JobDefinitionCreateRequest;
import io.github.pinpols.batch.console.domain.job.web.request.JobDefinitionUpdateRequest;
import io.github.pinpols.batch.console.domain.job.web.response.ConsoleJobDefinitionResponse;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.infrastructure.config.ConsoleConfigCacheInvalidationService;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

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
  private final ConsoleTenantGuard tenantGuard;
  private final ConsoleRequestMetadataResolver requestMetadataResolver;
  private final ConsoleConfigCacheInvalidationService cacheInvalidationService;
  private final BuiltinTaskTypeGuard builtinTaskTypeGuard;

  @Override
  public ConsoleJobDefinitionResponse detail(Long id, String tenantId) {
    String resolvedTenant = tenantGuard.resolveTenant(tenantId);
    JobDefinitionEntity entity =
        Guard.requireFound(
            jobDefinitionMapper.selectById(resolvedTenant, id), "job definition not found");
    return toResponse(entity);
  }

  @Override
  public ConsoleJobDefinitionResponse create(JobDefinitionCreateRequest request) {
    // ADR-035 §使用边界:builtin SPI 4 件套(shell/sql/stored_proc/http)只许平台 ADMIN 引用,
    // 租户走 SDK 自托管。controller 类级 @PreAuthorize ROLE_ADMIN 已是第一道,本守门 defense in depth。
    builtinTaskTypeGuard.assertAllowed(request.getJobType());
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    JobDefinitionEntity existing =
        jobDefinitionMapper.selectByUniqueKey(tenantId, request.getJobCode());
    if (existing != null) {
      throw BizException.of(
          ResultCode.CONFLICT,
          "error.common.conflict_detail",
          "job code already exists: " + request.getJobCode());
    }
    JobDefinitionEntity entity = new JobDefinitionEntity();
    entity.setTenantId(tenantId);
    entity.setJobCode(request.getJobCode());
    entity.setDependsOnJobCode(CodeNormalizer.trimToNull(request.getDependsOnJobCode()));
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
    entity.setExecutionHandler(request.getExecutionHandler());
    entity.setParamSchema(request.getParamSchema());
    entity.setDefaultParams(request.getDefaultParams());
    entity.setPriority(request.getPriority() == null ? 5 : request.getPriority());
    entity.setEnabled(request.getEnabled() != null && request.getEnabled());
    entity.setDescription(request.getDescription());
    String operator = requestMetadataResolver.current().operatorId();
    entity.setCreatedBy(operator);
    entity.setUpdatedBy(operator);
    jobDefinitionMapper.insert(entity);
    cacheInvalidationService.evictJobDefinition(tenantId, entity.getJobCode());
    return toResponse(jobDefinitionMapper.selectById(tenantId, entity.getId()));
  }

  @Override
  public ConsoleJobDefinitionResponse update(Long id, JobDefinitionUpdateRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    JobDefinitionEntity existing =
        Guard.requireFound(
            jobDefinitionMapper.selectById(tenantId, id), "job definition not found");
    String operator = requestMetadataResolver.current().operatorId();
    JobDefinitionMaintenanceUpdateParam param = new JobDefinitionMaintenanceUpdateParam();
    param.setTenantId(tenantId);
    param.setJobCode(existing.getJobCode());
    param.setDependsOnJobCode(
        request.getDependsOnJobCode() != null
            ? CodeNormalizer.trimToNull(request.getDependsOnJobCode())
            : existing.getDependsOnJobCode());
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
    param.setEnabled(request.getEnabled() != null ? request.getEnabled() : existing.getEnabled());
    param.setDescription(
        request.getDescription() != null ? request.getDescription() : existing.getDescription());
    param.setUpdatedBy(operator);
    jobDefinitionMapper.updateJobDefinitionMaintenance(param);
    cacheInvalidationService.evictJobDefinition(tenantId, existing.getJobCode());
    return toResponse(jobDefinitionMapper.selectById(tenantId, id));
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
  public ConsoleJobDefinitionResponse copy(Long id, String tenantId, String newJobCode) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    JobDefinitionEntity existing = jobDefinitionMapper.selectByUniqueKey(resolved, newJobCode);
    if (existing != null) {
      throw BizException.of(ResultCode.CONFLICT, "error.job.code_already_exists", newJobCode);
    }
    String operator = requestMetadataResolver.current().operatorId();
    jobDefinitionMapper.copyJobDefinition(resolved, id, newJobCode, operator);
    JobDefinitionEntity copied =
        Guard.requireFound(
            jobDefinitionMapper.selectByUniqueKey(resolved, newJobCode),
            "source job definition not found");
    cacheInvalidationService.evictJobDefinition(resolved, newJobCode);
    return toResponse(copied);
  }

  @Override
  public ConsoleJobDefinitionResponse copyWithOverrides(Long id, JobDefinitionCopyRequest request) {
    String resolved = tenantGuard.resolveTenant(request.getTenantId());
    JobDefinitionEntity existing =
        jobDefinitionMapper.selectByUniqueKey(resolved, request.getNewJobCode());
    if (existing != null) {
      throw BizException.of(
          ResultCode.CONFLICT,
          "error.common.conflict_detail",
          "job code already exists: " + request.getNewJobCode());
    }
    String operator = requestMetadataResolver.current().operatorId();
    jobDefinitionMapper.copyJobDefinition(resolved, id, request.getNewJobCode(), operator);
    JobDefinitionEntity copied =
        Guard.requireFound(
            jobDefinitionMapper.selectByUniqueKey(resolved, request.getNewJobCode()),
            "source job definition not found");
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
    param.setEnabled(request.getEnabled() != null ? request.getEnabled() : copied.getEnabled());
    param.setDescription(
        request.getDescription() != null ? request.getDescription() : copied.getDescription());
    param.setUpdatedBy(operator);
    jobDefinitionMapper.updateJobDefinitionMaintenance(param);
    cacheInvalidationService.evictJobDefinition(resolved, request.getNewJobCode());
    return toResponse(jobDefinitionMapper.selectByUniqueKey(resolved, request.getNewJobCode()));
  }

  private ConsoleJobDefinitionResponse toResponse(JobDefinitionEntity e) {
    return new ConsoleJobDefinitionResponse(
        e.getId(),
        e.getTenantId(),
        e.getJobCode(),
        e.getDependsOnJobCode(),
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
        e.getShardStrategy(),
        e.getExecutionMode(),
        e.getWatermarkField(),
        e.getExecutionHandler(),
        e.getParamSchema(),
        e.getDefaultParams(),
        e.getPriority(),
        e.getVersion(),
        e.getEnabled(),
        e.getDescription(),
        e.getCreatedAt(),
        e.getUpdatedAt());
  }
}

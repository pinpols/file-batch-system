package com.example.batch.console.infrastructure;

import com.example.batch.console.application.ConsoleJobDefinitionApplicationService;
import com.example.batch.console.domain.entity.JobDefinitionEntity;
import com.example.batch.console.mapper.JobDefinitionMapper;
import com.example.batch.console.mapper.param.JobDefinitionMaintenanceUpdateParam;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import com.example.batch.console.support.ConsoleTenantGuard;
import com.example.batch.console.web.request.JobDefinitionCopyRequest;
import com.example.batch.console.web.request.JobDefinitionCreateRequest;
import com.example.batch.console.web.request.JobDefinitionUpdateRequest;
import com.example.batch.console.web.response.ConsoleJobDefinitionResponse;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * {@link ConsoleJobDefinitionApplicationService} 的默认实现。
 */
@Service
@RequiredArgsConstructor
public class DefaultConsoleJobDefinitionApplicationService implements ConsoleJobDefinitionApplicationService {

    private final JobDefinitionMapper jobDefinitionMapper;
    private final ConsoleTenantGuard tenantGuard;
    private final ConsoleRequestMetadataResolver requestMetadataResolver;
    private final ConsoleConfigCacheInvalidationService cacheInvalidationService;

    @Override
    public ConsoleJobDefinitionResponse detail(Long id, String tenantId) {
        String resolvedTenant = tenantGuard.resolveTenant(tenantId);
        JobDefinitionEntity entity = jobDefinitionMapper.selectById(resolvedTenant, id);
        if (entity == null) {
            throw new BizException(ResultCode.NOT_FOUND, "job definition not found");
        }
        return toResponse(entity);
    }

    @Override
    public ConsoleJobDefinitionResponse create(JobDefinitionCreateRequest request) {
        String tenantId = tenantGuard.resolveTenant(request.getTenantId());
        JobDefinitionEntity existing = jobDefinitionMapper.selectByUniqueKey(tenantId, request.getJobCode());
        if (existing != null) {
            throw new BizException(ResultCode.CONFLICT, "job code already exists: " + request.getJobCode());
        }
        JobDefinitionEntity entity = new JobDefinitionEntity();
        entity.setTenantId(tenantId);
        entity.setJobCode(request.getJobCode());
        entity.setJobName(request.getJobName());
        entity.setJobType(request.getJobType());
        entity.setBizType(request.getBizType());
        entity.setScheduleType(request.getScheduleType());
        entity.setScheduleExpr(request.getScheduleExpr());
        entity.setTimezone(request.getTimezone() == null ? "Asia/Shanghai" : request.getTimezone());
        entity.setTriggerMode(request.getTriggerMode() == null ? "SCHEDULED" : request.getTriggerMode());
        entity.setWorkerGroup(request.getWorkerGroup());
        entity.setQueueCode(request.getQueueCode());
        entity.setCalendarCode(request.getCalendarCode());
        entity.setWindowCode(request.getWindowCode());
        entity.setDagEnabled(request.getDagEnabled() != null && request.getDagEnabled());
        entity.setShardStrategy(request.getShardStrategy() == null ? "NONE" : request.getShardStrategy());
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
        JobDefinitionEntity existing = jobDefinitionMapper.selectById(tenantId, id);
        if (existing == null) {
            throw new BizException(ResultCode.NOT_FOUND, "job definition not found");
        }
        String operator = requestMetadataResolver.current().operatorId();
        JobDefinitionMaintenanceUpdateParam param = new JobDefinitionMaintenanceUpdateParam();
        param.setTenantId(tenantId);
        param.setJobCode(existing.getJobCode());
        param.setJobName(request.getJobName() != null ? request.getJobName() : existing.getJobName());
        param.setQueueCode(request.getQueueCode() != null ? request.getQueueCode() : existing.getQueueCode());
        param.setWorkerGroup(request.getWorkerGroup() != null ? request.getWorkerGroup() : existing.getWorkerGroup());
        param.setScheduleExpr(request.getScheduleExpr() != null ? request.getScheduleExpr() : existing.getScheduleExpr());
        param.setCalendarCode(request.getCalendarCode() != null ? request.getCalendarCode() : existing.getCalendarCode());
        param.setWindowCode(request.getWindowCode() != null ? request.getWindowCode() : existing.getWindowCode());
        param.setRetryPolicy(request.getRetryPolicy() != null ? request.getRetryPolicy() : existing.getRetryPolicy());
        param.setRetryMaxCount(request.getRetryMaxCount() != null ? request.getRetryMaxCount() : existing.getRetryMaxCount());
        param.setTimeoutSeconds(request.getTimeoutSeconds() != null ? request.getTimeoutSeconds() : existing.getTimeoutSeconds());
        param.setShardStrategy(request.getShardStrategy() != null ? request.getShardStrategy() : existing.getShardStrategy());
        param.setEnabled(request.getEnabled() != null ? request.getEnabled() : existing.getEnabled());
        param.setDescription(request.getDescription() != null ? request.getDescription() : existing.getDescription());
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
            throw new BizException(ResultCode.NOT_FOUND, "job definition not found");
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
    public void delete(Long id, String tenantId) {
        String resolved = tenantGuard.resolveTenant(tenantId);
        JobDefinitionEntity existing = jobDefinitionMapper.selectById(resolved, id);
        if (existing == null) {
            throw new BizException(ResultCode.NOT_FOUND, "job definition not found");
        }
        int rows = jobDefinitionMapper.deleteByTenantAndId(resolved, id);
        if (rows == 0) {
            throw new BizException(ResultCode.NOT_FOUND, "job definition not found");
        }
        cacheInvalidationService.evictJobDefinition(resolved, existing.getJobCode());
    }

    @Override
    public ConsoleJobDefinitionResponse copy(Long id, String tenantId, String newJobCode) {
        String resolved = tenantGuard.resolveTenant(tenantId);
        JobDefinitionEntity existing = jobDefinitionMapper.selectByUniqueKey(resolved, newJobCode);
        if (existing != null) {
            throw new BizException(ResultCode.CONFLICT, "job code already exists: " + newJobCode);
        }
        String operator = requestMetadataResolver.current().operatorId();
        jobDefinitionMapper.copyJobDefinition(resolved, id, newJobCode, operator);
        JobDefinitionEntity copied = jobDefinitionMapper.selectByUniqueKey(resolved, newJobCode);
        if (copied == null) {
            throw new BizException(ResultCode.NOT_FOUND, "source job definition not found");
        }
        cacheInvalidationService.evictJobDefinition(resolved, newJobCode);
        return toResponse(copied);
    }

    @Override
    public ConsoleJobDefinitionResponse copyWithOverrides(Long id, JobDefinitionCopyRequest request) {
        String resolved = tenantGuard.resolveTenant(request.getTenantId());
        JobDefinitionEntity existing = jobDefinitionMapper.selectByUniqueKey(resolved, request.getNewJobCode());
        if (existing != null) {
            throw new BizException(ResultCode.CONFLICT, "job code already exists: " + request.getNewJobCode());
        }
        String operator = requestMetadataResolver.current().operatorId();
        jobDefinitionMapper.copyJobDefinition(resolved, id, request.getNewJobCode(), operator);
        JobDefinitionEntity copied = jobDefinitionMapper.selectByUniqueKey(resolved, request.getNewJobCode());
        if (copied == null) {
            throw new BizException(ResultCode.NOT_FOUND, "source job definition not found");
        }
        // Apply overrides
        JobDefinitionMaintenanceUpdateParam param = new JobDefinitionMaintenanceUpdateParam();
        param.setTenantId(resolved);
        param.setJobCode(request.getNewJobCode());
        param.setJobName(request.getJobName() != null ? request.getJobName() : copied.getJobName());
        param.setQueueCode(request.getQueueCode() != null ? request.getQueueCode() : copied.getQueueCode());
        param.setWorkerGroup(request.getWorkerGroup() != null ? request.getWorkerGroup() : copied.getWorkerGroup());
        param.setScheduleExpr(request.getScheduleExpr() != null ? request.getScheduleExpr() : copied.getScheduleExpr());
        param.setCalendarCode(request.getCalendarCode() != null ? request.getCalendarCode() : copied.getCalendarCode());
        param.setWindowCode(request.getWindowCode() != null ? request.getWindowCode() : copied.getWindowCode());
        param.setRetryPolicy(request.getRetryPolicy() != null ? request.getRetryPolicy() : copied.getRetryPolicy());
        param.setRetryMaxCount(request.getRetryMaxCount() != null ? request.getRetryMaxCount() : copied.getRetryMaxCount());
        param.setTimeoutSeconds(request.getTimeoutSeconds() != null ? request.getTimeoutSeconds() : copied.getTimeoutSeconds());
        param.setShardStrategy(copied.getShardStrategy());
        param.setEnabled(request.getEnabled() != null ? request.getEnabled() : copied.getEnabled());
        param.setDescription(request.getDescription() != null ? request.getDescription() : copied.getDescription());
        param.setUpdatedBy(operator);
        jobDefinitionMapper.updateJobDefinitionMaintenance(param);
        cacheInvalidationService.evictJobDefinition(resolved, request.getNewJobCode());
        return toResponse(jobDefinitionMapper.selectByUniqueKey(resolved, request.getNewJobCode()));
    }

    private ConsoleJobDefinitionResponse toResponse(JobDefinitionEntity e) {
        return new ConsoleJobDefinitionResponse(
                e.getId(), e.getTenantId(), e.getJobCode(), e.getJobName(), e.getJobType(),
                e.getBizType(), e.getQueueCode(), e.getWorkerGroup(), e.getScheduleType(),
                e.getScheduleExpr(), e.getTimezone(), e.getCalendarCode(), e.getWindowCode(),
                e.getTriggerMode(), e.getDagEnabled(), e.getRetryPolicy(), e.getRetryMaxCount(),
                e.getTimeoutSeconds(), e.getShardStrategy(), e.getExecutionHandler(),
                e.getParamSchema(), e.getDefaultParams(), e.getPriority(), e.getVersion(),
                e.getEnabled(), e.getDescription(), e.getCreatedAt(), e.getUpdatedAt()
        );
    }
}

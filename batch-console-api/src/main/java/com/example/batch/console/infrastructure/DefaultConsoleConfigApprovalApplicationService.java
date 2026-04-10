package com.example.batch.console.infrastructure;

import com.example.batch.common.enums.ConfigLifecycleStatus;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.ConsoleTextSanitizer;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.console.application.ConsoleConfigApprovalApplicationService;
import com.example.batch.console.domain.entity.ConfigReleaseEntity;
import com.example.batch.console.mapper.ConfigApprovalMapper;
import com.example.batch.console.mapper.ConfigChangeLogMapper;
import com.example.batch.console.mapper.ConfigReleaseMapper;
import com.example.batch.console.support.ConsoleTenantGuard;
import com.example.batch.console.web.request.ConfigApprovalActionRequest;
import com.example.batch.console.web.request.ConfigReleaseApprovalSubmitRequest;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class DefaultConsoleConfigApprovalApplicationService implements ConsoleConfigApprovalApplicationService {

    private static final String PENDING_APPROVAL = "PENDING_APPROVAL";

    private final ConsoleTenantGuard tenantGuard;
    private final ConfigReleaseMapper configReleaseMapper;
    private final ConfigApprovalMapper configApprovalMapper;
    private final ConfigChangeLogMapper configChangeLogMapper;

    @Override
    @Transactional
    public Map<String, Object> submit(Long releaseId, ConfigReleaseApprovalSubmitRequest request) {
        String tenantId = tenantGuard.resolveTenant(request.getTenantId());
        ConfigReleaseEntity release = loadRelease(tenantId, releaseId);
        if (!ConfigLifecycleStatus.DRAFT.code().equals(release.getConfigStatus())) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "only DRAFT release can submit approval");
        }
        Map<String, Object> latest = configApprovalMapper.selectLatestByRelease(tenantId, releaseId);
        if (latest != null && "PENDING".equals(String.valueOf(latest.get("approvalStatus")))) {
            throw new BizException(ResultCode.CONFLICT, "config approval already pending");
        }
        configApprovalMapper.insert(mapOf(
                "tenantId", tenantId,
                "releaseId", releaseId,
                "approvalStatus", "PENDING",
                "requestedBy", ConsoleTextSanitizer.safeInput(request.getOperatorId(), 64),
                "reviewComment", ConsoleTextSanitizer.safeInput(request.getReason(), 1024),
                "expiredAt", parseInstant(request.getExpiredAt())
        ));
        configReleaseMapper.updateConfigReleaseStatus(mapOf(
                "tenantId", tenantId,
                "releaseId", releaseId,
                "nextStatus", PENDING_APPROVAL,
                "publishedAt", null,
                "rolledBackAt", null,
                "updatedBy", ConsoleTextSanitizer.safeInput(request.getOperatorId(), 64)
        ));
        logChange(tenantId, release, "SUBMIT_APPROVAL", request.getOperatorId(), request.getReason(), Map.of(
                "releaseId", releaseId,
                "nextStatus", PENDING_APPROVAL
        ));
        return detail(tenantId, releaseId);
    }

    @Override
    public Map<String, Object> detail(String tenantId, Long releaseId) {
        String resolved = tenantGuard.resolveTenant(tenantId);
        ConfigReleaseEntity release = loadRelease(resolved, releaseId);
        Map<String, Object> approval = configApprovalMapper.selectLatestByRelease(resolved, releaseId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("releaseId", release.getId());
        result.put("tenantId", release.getTenantId());
        result.put("configType", release.getConfigType());
        result.put("configKey", release.getConfigKey());
        result.put("configStatus", release.getConfigStatus());
        result.put("approval", approval);
        return result;
    }

    @Override
    @Transactional
    public Map<String, Object> approve(Long approvalId, ConfigApprovalActionRequest request) {
        String tenantId = tenantGuard.resolveTenant(request.getTenantId());
        Map<String, Object> approval = requireApproval(tenantId, approvalId);
        if (!"PENDING".equals(String.valueOf(approval.get("approvalStatus")))) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "config approval is not pending");
        }
        Long releaseId = longValue(approval.get("releaseId"));
        ConfigReleaseEntity release = loadRelease(tenantId, releaseId);
        int rows = configApprovalMapper.approve(mapOf(
                "tenantId", tenantId,
                "id", approvalId,
                "reviewedBy", ConsoleTextSanitizer.safeInput(request.getOperatorId(), 64),
                "reviewComment", ConsoleTextSanitizer.safeInput(request.getReason(), 1024)
        ));
        if (rows == 0) {
            throw new BizException(ResultCode.CONFLICT, "config approval already processed");
        }
        configReleaseMapper.updateConfigReleaseStatus(mapOf(
                "tenantId", tenantId,
                "releaseId", releaseId,
                "nextStatus", ConfigLifecycleStatus.PUBLISHED.code(),
                "publishedAt", Instant.now(),
                "rolledBackAt", null,
                "updatedBy", ConsoleTextSanitizer.safeInput(request.getOperatorId(), 64)
        ));
        logChange(tenantId, release, "APPROVE", request.getOperatorId(), request.getReason(), Map.of(
                "approvalId", approvalId,
                "nextStatus", ConfigLifecycleStatus.PUBLISHED.code()
        ));
        return detail(tenantId, releaseId);
    }

    @Override
    @Transactional
    public Map<String, Object> reject(Long approvalId, ConfigApprovalActionRequest request) {
        String tenantId = tenantGuard.resolveTenant(request.getTenantId());
        Map<String, Object> approval = requireApproval(tenantId, approvalId);
        if (!"PENDING".equals(String.valueOf(approval.get("approvalStatus")))) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "config approval is not pending");
        }
        Long releaseId = longValue(approval.get("releaseId"));
        ConfigReleaseEntity release = loadRelease(tenantId, releaseId);
        int rows = configApprovalMapper.reject(mapOf(
                "tenantId", tenantId,
                "id", approvalId,
                "reviewedBy", ConsoleTextSanitizer.safeInput(request.getOperatorId(), 64),
                "reviewComment", ConsoleTextSanitizer.safeInput(request.getReason(), 1024)
        ));
        if (rows == 0) {
            throw new BizException(ResultCode.CONFLICT, "config approval already processed");
        }
        configReleaseMapper.updateConfigReleaseStatus(mapOf(
                "tenantId", tenantId,
                "releaseId", releaseId,
                "nextStatus", ConfigLifecycleStatus.DRAFT.code(),
                "publishedAt", null,
                "rolledBackAt", null,
                "updatedBy", ConsoleTextSanitizer.safeInput(request.getOperatorId(), 64)
        ));
        logChange(tenantId, release, "REJECT", request.getOperatorId(), request.getReason(), Map.of(
                "approvalId", approvalId,
                "nextStatus", ConfigLifecycleStatus.DRAFT.code()
        ));
        return detail(tenantId, releaseId);
    }

    private Map<String, Object> requireApproval(String tenantId, Long approvalId) {
        Map<String, Object> approval = configApprovalMapper.selectById(tenantId, approvalId);
        if (approval == null) {
            throw new BizException(ResultCode.NOT_FOUND, "config approval not found");
        }
        return approval;
    }

    private ConfigReleaseEntity loadRelease(String tenantId, Long releaseId) {
        ConfigReleaseEntity release = configReleaseMapper.selectById(mapOf("tenantId", tenantId, "releaseId", releaseId));
        if (release == null) {
            throw new BizException(ResultCode.NOT_FOUND, "config release not found");
        }
        return release;
    }

    private void logChange(String tenantId,
                           ConfigReleaseEntity release,
                           String action,
                           String operatorId,
                           String reason,
                           Map<String, Object> detail) {
        configChangeLogMapper.insertConfigChangeLog(mapOf(
                "tenantId", tenantId,
                "configType", release.getConfigType(),
                "configKey", release.getConfigKey(),
                "versionNo", release.getVersionNo(),
                "changeAction", action,
                "changeResult", "SUCCESS",
                "operatorType", "API",
                "operatorId", ConsoleTextSanitizer.safeInput(operatorId, 64),
                "traceId", null,
                "changeSummaryJson", JsonUtils.toJson(mapOf(
                        "reason", ConsoleTextSanitizer.safeInput(reason, 512),
                        "detail", detail
                ))
        ));
    }

    private Instant parseInstant(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException ex) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "expiredAt must be ISO-8601 instant");
        }
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? null : Long.valueOf(String.valueOf(value));
    }

    private Map<String, Object> mapOf(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            result.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return result;
    }
}

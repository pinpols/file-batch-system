package com.example.batch.console.infrastructure;

import com.example.batch.common.enums.ConfigLifecycleStatus;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.console.application.ConsoleConfigApplicationService;
import com.example.batch.console.domain.entity.ConfigChangeLogEntity;
import com.example.batch.console.domain.entity.ConfigReleaseEntity;
import com.example.batch.console.domain.entity.SecretVersionEntity;
import com.example.batch.console.domain.query.ConfigChangeLogQuery;
import com.example.batch.console.domain.query.ConfigReleaseQuery;
import com.example.batch.console.domain.query.SecretVersionQuery;
import com.example.batch.console.mapper.ConfigChangeLogMapper;
import com.example.batch.console.mapper.ConfigReleaseMapper;
import com.example.batch.console.mapper.SecretVersionMapper;
import com.example.batch.console.support.ConsoleTenantGuard;
import com.example.batch.console.web.query.ConfigChangeLogQueryRequest;
import com.example.batch.console.web.query.ConfigReleaseQueryRequest;
import com.example.batch.console.web.query.SecretVersionQueryRequest;
import com.example.batch.console.web.request.ConfigReleaseActionRequest;
import com.example.batch.console.web.request.ConfigReleaseUpsertRequest;
import com.example.batch.console.web.request.SecretVersionRotateRequest;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class DefaultConsoleConfigApplicationService implements ConsoleConfigApplicationService {

    private final ConsoleTenantGuard tenantGuard;
    private final ConfigReleaseMapper configReleaseMapper;
    private final SecretVersionMapper secretVersionMapper;
    private final ConfigChangeLogMapper configChangeLogMapper;

    @Override
    public List<ConfigReleaseEntity> configReleases(ConfigReleaseQueryRequest request) {
        ConfigReleaseQuery query = new ConfigReleaseQuery();
        query.setTenantId(resolveTenant(request.getTenantId()));
        query.setConfigType(request.getConfigType());
        query.setConfigKey(request.getConfigKey());
        query.setConfigStatus(request.getConfigStatus());
        query.setVersionNo(request.getVersionNo());
        return configReleaseMapper.selectByQuery(query);
    }

    @Override
    @Transactional
    public Long createConfigRelease(ConfigReleaseUpsertRequest request) {
        String tenantId = resolveTenant(request.getTenantId());
        validateJson(request.getConfigPayloadJson(), "configPayloadJson");
        validateJson(request.getGrayScopeJson(), "grayScopeJson");
        Integer latestVersionNo = configReleaseMapper.selectLatestVersionNo(mapOf(
                "tenantId", tenantId,
                "configType", request.getConfigType(),
                "configKey", request.getConfigKey()
        ));
        int nextVersionNo = latestVersionNo == null ? 1 : latestVersionNo + 1;
        configReleaseMapper.insertConfigRelease(mapOf(
                "tenantId", tenantId,
                "configType", request.getConfigType(),
                "configKey", request.getConfigKey(),
                "configName", request.getConfigName(),
                "configStatus", ConfigLifecycleStatus.DRAFT.code(),
                "versionNo", nextVersionNo,
                "grayScopeJson", request.getGrayScopeJson(),
                "configPayloadJson", request.getConfigPayloadJson(),
                "effectiveFromAt", parseInstant(request.getEffectiveFromAt(), "effectiveFromAt"),
                "effectiveToAt", parseInstant(request.getEffectiveToAt(), "effectiveToAt"),
                "createdBy", request.getOperatorId(),
                "updatedBy", request.getOperatorId()
        ));
        logChange(tenantId, request.getConfigType(), request.getConfigKey(), nextVersionNo, "CREATE", "SUCCESS", request.getOperatorId(), request.getTraceId(), request.getReason(), Map.of(
                "configName", request.getConfigName(),
                "configStatus", ConfigLifecycleStatus.DRAFT.code()
        ));
        return Long.valueOf(nextVersionNo);
    }

    @Override
    @Transactional
    public String publishConfigRelease(Long releaseId, ConfigReleaseActionRequest request) {
        return changeReleaseStatus(releaseId, request, ConfigLifecycleStatus.PUBLISHED.code(), "PUBLISH");
    }

    @Override
    @Transactional
    public String grayConfigRelease(Long releaseId, ConfigReleaseActionRequest request) {
        String tenantId = resolveTenant(request.getTenantId());
        ConfigReleaseEntity release = loadRelease(tenantId, releaseId);
        validateJson(request.getGrayScopeJson(), "grayScopeJson");
        configReleaseMapper.updateGrayScope(mapOf(
                "tenantId", tenantId,
                "releaseId", releaseId,
                "grayScopeJson", request.getGrayScopeJson()
        ));
        return changeReleaseStatus(releaseId, request, ConfigLifecycleStatus.GRAY.code(), "GRAY");
    }

    @Override
    @Transactional
    public String rollbackConfigRelease(Long releaseId, ConfigReleaseActionRequest request) {
        return changeReleaseStatus(releaseId, request, ConfigLifecycleStatus.ROLLED_BACK.code(), "ROLLBACK");
    }

    @Override
    public List<SecretVersionEntity> secretVersions(SecretVersionQueryRequest request) {
        SecretVersionQuery query = new SecretVersionQuery();
        query.setTenantId(resolveTenant(request.getTenantId()));
        query.setSecretRef(request.getSecretRef());
        query.setSecretStatus(request.getSecretStatus());
        query.setCurrentVersion(request.getCurrentVersion());
        return secretVersionMapper.selectByQuery(query);
    }

    @Override
    @Transactional
    public Long rotateSecretVersion(SecretVersionRotateRequest request) {
        String tenantId = resolveTenant(request.getTenantId());
        validateJson(request.getSecretPayloadJson(), "secretPayloadJson");
        Integer latestVersionNo = secretVersionMapper.selectLatestVersionNo(mapOf(
                "tenantId", tenantId,
                "secretRef", request.getSecretRef()
        ));
        int nextVersionNo = latestVersionNo == null ? 1 : latestVersionNo + 1;
        secretVersionMapper.deactivateCurrentVersion(mapOf(
                "tenantId", tenantId,
                "secretRef", request.getSecretRef()
        ));
        String nextStatus = StringUtils.hasText(request.getSecretStatus())
                ? request.getSecretStatus().trim().toUpperCase()
                : ConfigLifecycleStatus.PUBLISHED.code();
        secretVersionMapper.insertSecretVersion(mapOf(
                "tenantId", tenantId,
                "secretRef", request.getSecretRef(),
                "secretName", request.getSecretName(),
                "versionNo", nextVersionNo,
                "secretStatus", nextStatus,
                "currentVersion", true,
                "rotationWindowStartAt", parseInstant(request.getRotationWindowStartAt(), "rotationWindowStartAt"),
                "rotationWindowEndAt", parseInstant(request.getRotationWindowEndAt(), "rotationWindowEndAt"),
                "effectiveFromAt", parseInstant(request.getEffectiveFromAt(), "effectiveFromAt"),
                "effectiveToAt", parseInstant(request.getEffectiveToAt(), "effectiveToAt"),
                "secretPayloadJson", request.getSecretPayloadJson(),
                "rotationReason", request.getReason(),
                "createdBy", request.getOperatorId(),
                "updatedBy", request.getOperatorId()
        ));
        logChange(tenantId, "SECRET", request.getSecretRef(), nextVersionNo, "ROTATE", "SUCCESS", request.getOperatorId(), request.getTraceId(), request.getReason(), Map.of(
                "secretName", request.getSecretName(),
                "secretStatus", nextStatus
        ));
        return Long.valueOf(nextVersionNo);
    }

    @Override
    public List<ConfigChangeLogEntity> configChangeLogs(ConfigChangeLogQueryRequest request) {
        ConfigChangeLogQuery query = new ConfigChangeLogQuery();
        query.setTenantId(resolveTenant(request.getTenantId()));
        query.setConfigType(request.getConfigType());
        query.setConfigKey(request.getConfigKey());
        query.setChangeAction(request.getChangeAction());
        return configChangeLogMapper.selectByQuery(query);
    }

    private String changeReleaseStatus(Long releaseId,
                                       ConfigReleaseActionRequest request,
                                       String nextStatus,
                                       String changeAction) {
        String tenantId = resolveTenant(request.getTenantId());
        ConfigReleaseEntity release = loadRelease(tenantId, releaseId);
        Map<String, Object> params = mapOf(
                "tenantId", tenantId,
                "releaseId", releaseId,
                "nextStatus", nextStatus,
                "publishedAt", ConfigLifecycleStatus.PUBLISHED.code().equals(nextStatus) ? Instant.now() : null,
                "rolledBackAt", ConfigLifecycleStatus.ROLLED_BACK.code().equals(nextStatus) ? Instant.now() : null,
                "updatedBy", request.getOperatorId()
        );
        configReleaseMapper.updateConfigReleaseStatus(params);
        if (ConfigLifecycleStatus.GRAY.code().equals(nextStatus) && StringUtils.hasText(request.getGrayScopeJson())) {
            validateJson(request.getGrayScopeJson(), "grayScopeJson");
            configReleaseMapper.updateGrayScope(mapOf(
                    "tenantId", tenantId,
                    "releaseId", releaseId,
                    "grayScopeJson", request.getGrayScopeJson()
            ));
        }
        logChange(tenantId, release.getConfigType(), release.getConfigKey(), release.getVersionNo(), changeAction, "SUCCESS", request.getOperatorId(), request.getTraceId(), request.getReason(), Map.of(
                "nextStatus", nextStatus
        ));
        return nextStatus;
    }

    private ConfigReleaseEntity loadRelease(String tenantId, Long releaseId) {
        ConfigReleaseEntity release = configReleaseMapper.selectById(mapOf(
                "tenantId", tenantId,
                "releaseId", releaseId
        ));
        if (release == null) {
            throw new BizException(ResultCode.NOT_FOUND, "config release not found");
        }
        return release;
    }

    private void logChange(String tenantId,
                           String configType,
                           String configKey,
                           Integer versionNo,
                           String action,
                           String result,
                           String operatorId,
                           String traceId,
                           String reason,
                           Object detail) {
        configChangeLogMapper.insertConfigChangeLog(mapOf(
                "tenantId", tenantId,
                "configType", configType,
                "configKey", configKey,
                "versionNo", versionNo,
                "changeAction", action,
                "changeResult", result,
                "operatorType", "API",
                "operatorId", operatorId,
                "traceId", traceId,
                "changeSummaryJson", JsonUtils.toJson(detailOf(reason, detail))
        ));
    }

    private String resolveTenant(String requestTenantId) {
        return tenantGuard.resolveTenant(requestTenantId);
    }

    private void validateJson(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        Object parsed = JsonUtils.fromJson(value, Object.class);
        if (parsed == null) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, fieldName + " must be valid JSON");
        }
    }

    private Instant parseInstant(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, fieldName + " must be ISO-8601 datetime");
        }
    }

    private Map<String, Object> mapOf(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            values.put(String.valueOf(pairs[index]), pairs[index + 1]);
        }
        return values;
    }

    private Map<String, Object> detailOf(String reason, Object detail) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("reason", reason);
        values.put("detail", detail);
        return values;
    }
}

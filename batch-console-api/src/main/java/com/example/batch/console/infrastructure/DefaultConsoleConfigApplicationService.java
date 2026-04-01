package com.example.batch.console.infrastructure;

import com.example.batch.common.enums.ConfigLifecycleStatus;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.ConsoleTextSanitizer;
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
import com.example.batch.console.web.response.ConsoleConfigChangeLogResponse;
import com.example.batch.console.web.response.ConsoleConfigReleaseResponse;
import com.example.batch.console.web.response.ConsoleSecretVersionResponse;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * {@link com.example.batch.console.application.ConsoleConfigApplicationService} 的默认实现：
 * 基于本地 Mapper 维护配置发布单、密钥版本与变更日志。
 */
@Service
@RequiredArgsConstructor
public class DefaultConsoleConfigApplicationService implements ConsoleConfigApplicationService {

    private final ConsoleTenantGuard tenantGuard;
    private final ConfigReleaseMapper configReleaseMapper;
    private final SecretVersionMapper secretVersionMapper;
    private final ConfigChangeLogMapper configChangeLogMapper;

    @Override
    public List<ConsoleConfigReleaseResponse> configReleases(ConfigReleaseQueryRequest request) {
        ConfigReleaseQuery query = new ConfigReleaseQuery();
        query.setTenantId(resolveTenant(request.getTenantId()));
        query.setConfigType(request.getConfigType());
        query.setConfigKey(request.getConfigKey());
        query.setConfigStatus(request.getConfigStatus());
        query.setVersionNo(request.getVersionNo());
        return configReleaseMapper.selectByQuery(query).stream().map(this::toConfigReleaseResponse).toList();
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
                "configType", ConsoleTextSanitizer.safeInput(request.getConfigType(), 64),
                "configKey", ConsoleTextSanitizer.safeInput(request.getConfigKey(), 128),
                "configName", ConsoleTextSanitizer.safeInput(request.getConfigName(), 256),
                "configStatus", ConfigLifecycleStatus.DRAFT.code(),
                "versionNo", nextVersionNo,
                "grayScopeJson", request.getGrayScopeJson(),
                "configPayloadJson", request.getConfigPayloadJson(),
                "effectiveFromAt", parseInstant(request.getEffectiveFromAt(), "effectiveFromAt"),
                "effectiveToAt", parseInstant(request.getEffectiveToAt(), "effectiveToAt"),
                "createdBy", ConsoleTextSanitizer.safeInput(request.getOperatorId(), 64),
                "updatedBy", ConsoleTextSanitizer.safeInput(request.getOperatorId(), 64)
        ));
        logChange(new ChangeLogCommand(
                new ChangeLogContext(tenantId, request.getOperatorId(), request.getTraceId(), request.getReason()),
                new ChangeLogTarget(request.getConfigType(), request.getConfigKey(), nextVersionNo),
                new ChangeLogChange("CREATE", "SUCCESS", Map.of(
                        "configName", ConsoleTextSanitizer.safeInput(request.getConfigName(), 256),
                        "configStatus", ConfigLifecycleStatus.DRAFT.code()
                ))));
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
    public List<ConsoleSecretVersionResponse> secretVersions(SecretVersionQueryRequest request) {
        SecretVersionQuery query = new SecretVersionQuery();
        query.setTenantId(resolveTenant(request.getTenantId()));
        query.setSecretRef(request.getSecretRef());
        query.setSecretStatus(request.getSecretStatus());
        query.setCurrentVersion(request.getCurrentVersion());
        return secretVersionMapper.selectByQuery(query).stream().map(this::toSecretVersionResponse).toList();
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
                "secretRef", ConsoleTextSanitizer.safeInput(request.getSecretRef(), 128),
                "secretName", ConsoleTextSanitizer.safeInput(request.getSecretName(), 256),
                "versionNo", nextVersionNo,
                "secretStatus", nextStatus,
                "currentVersion", true,
                "rotationWindowStartAt", parseInstant(request.getRotationWindowStartAt(), "rotationWindowStartAt"),
                "rotationWindowEndAt", parseInstant(request.getRotationWindowEndAt(), "rotationWindowEndAt"),
                "effectiveFromAt", parseInstant(request.getEffectiveFromAt(), "effectiveFromAt"),
                "effectiveToAt", parseInstant(request.getEffectiveToAt(), "effectiveToAt"),
                "secretPayloadJson", request.getSecretPayloadJson(),
                "rotationReason", ConsoleTextSanitizer.safeInput(request.getReason(), 512),
                "createdBy", ConsoleTextSanitizer.safeInput(request.getOperatorId(), 64),
                "updatedBy", ConsoleTextSanitizer.safeInput(request.getOperatorId(), 64)
        ));
        logChange(new ChangeLogCommand(
                new ChangeLogContext(tenantId, request.getOperatorId(), request.getTraceId(), request.getReason()),
                new ChangeLogTarget("SECRET", request.getSecretRef(), nextVersionNo),
                new ChangeLogChange("ROTATE", "SUCCESS", Map.of(
                        "secretName", ConsoleTextSanitizer.safeInput(request.getSecretName(), 256),
                        "secretStatus", nextStatus
                ))));
        return Long.valueOf(nextVersionNo);
    }

    @Override
    public List<ConsoleConfigChangeLogResponse> configChangeLogs(ConfigChangeLogQueryRequest request) {
        ConfigChangeLogQuery query = new ConfigChangeLogQuery();
        query.setTenantId(resolveTenant(request.getTenantId()));
        query.setConfigType(request.getConfigType());
        query.setConfigKey(request.getConfigKey());
        query.setChangeAction(request.getChangeAction());
        return configChangeLogMapper.selectByQuery(query).stream().map(this::toConfigChangeLogResponse).toList();
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
                "updatedBy", ConsoleTextSanitizer.safeInput(request.getOperatorId(), 64)
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
        logChange(new ChangeLogCommand(
                new ChangeLogContext(tenantId, request.getOperatorId(), request.getTraceId(), request.getReason()),
                new ChangeLogTarget(release.getConfigType(), release.getConfigKey(), release.getVersionNo()),
                new ChangeLogChange(changeAction, "SUCCESS", Map.of("nextStatus", nextStatus))
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

    private void logChange(ChangeLogCommand command) {
        configChangeLogMapper.insertConfigChangeLog(mapOf(
                "tenantId", command.context().tenantId(),
                "configType", command.target().configType(),
                "configKey", command.target().configKey(),
                "versionNo", command.target().versionNo(),
                "changeAction", command.change().action(),
                "changeResult", command.change().result(),
                "operatorType", "API",
                "operatorId", ConsoleTextSanitizer.safeInput(command.context().operatorId(), 64),
                "traceId", ConsoleTextSanitizer.safeInput(command.context().traceId(), 128),
                "changeSummaryJson", JsonUtils.toJson(detailOf(
                        ConsoleTextSanitizer.safeInput(command.context().reason(), 512),
                        command.change().detail()
                ))
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

    private record ChangeLogContext(String tenantId,
                                    String operatorId,
                                    String traceId,
                                    String reason) {
    }

    private record ChangeLogCommand(ChangeLogContext context,
                                    ChangeLogTarget target,
                                    ChangeLogChange change) {
    }

    private record ChangeLogTarget(String configType,
                                   String configKey,
                                   Integer versionNo) {
    }

    private record ChangeLogChange(String action,
                                   String result,
                                   Object detail) {
    }

    @Override
    public ConsoleConfigReleaseResponse configReleaseDetail(String tenantId, Long releaseId) {
        String resolved = resolveTenant(tenantId);
        ConfigReleaseEntity entity = configReleaseMapper.selectById(mapOf(
                "tenantId", resolved,
                "releaseId", releaseId
        ));
        if (entity == null) {
            throw new BizException(ResultCode.NOT_FOUND, "config release not found: " + releaseId);
        }
        return toConfigReleaseResponse(entity);
    }

    @Override
    public ConsoleSecretVersionResponse secretVersionDetail(String tenantId, Long secretVersionId) {
        String resolved = resolveTenant(tenantId);
        SecretVersionEntity entity = secretVersionMapper.selectById(mapOf(
                "tenantId", resolved,
                "secretVersionId", secretVersionId
        ));
        if (entity == null) {
            throw new BizException(ResultCode.NOT_FOUND, "secret version not found: " + secretVersionId);
        }
        return toSecretVersionResponse(entity);
    }

    private ConsoleConfigReleaseResponse toConfigReleaseResponse(ConfigReleaseEntity entity) {
        return new ConsoleConfigReleaseResponse(
                entity.getId(),
                ConsoleTextSanitizer.safeDisplay(entity.getTenantId()),
                ConsoleTextSanitizer.safeDisplay(entity.getConfigType()),
                ConsoleTextSanitizer.safeDisplay(entity.getConfigKey()),
                ConsoleTextSanitizer.safeDisplay(entity.getConfigName()),
                ConsoleTextSanitizer.safeDisplay(entity.getConfigStatus()),
                entity.getVersionNo(),
                ConsoleTextSanitizer.safeDisplay(entity.getGrayScope()),
                ConsoleTextSanitizer.safeDisplay(entity.getConfigPayload()),
                entity.getEffectiveFromAt(),
                entity.getEffectiveToAt(),
                entity.getPublishedAt(),
                entity.getRolledBackAt(),
                ConsoleTextSanitizer.safeDisplay(entity.getCreatedBy()),
                ConsoleTextSanitizer.safeDisplay(entity.getUpdatedBy()),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private ConsoleSecretVersionResponse toSecretVersionResponse(SecretVersionEntity entity) {
        return new ConsoleSecretVersionResponse(
                entity.getId(),
                ConsoleTextSanitizer.safeDisplay(entity.getTenantId()),
                ConsoleTextSanitizer.safeDisplay(entity.getSecretRef()),
                ConsoleTextSanitizer.safeDisplay(entity.getSecretName()),
                entity.getVersionNo(),
                ConsoleTextSanitizer.safeDisplay(entity.getSecretStatus()),
                entity.getCurrentVersion(),
                entity.getRotationWindowStartAt(),
                entity.getRotationWindowEndAt(),
                entity.getEffectiveFromAt(),
                entity.getEffectiveToAt(),
                ConsoleTextSanitizer.safeDisplay(entity.getSecretPayload()),
                ConsoleTextSanitizer.safeDisplay(entity.getRotationReason()),
                ConsoleTextSanitizer.safeDisplay(entity.getCreatedBy()),
                ConsoleTextSanitizer.safeDisplay(entity.getUpdatedBy()),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private ConsoleConfigChangeLogResponse toConfigChangeLogResponse(ConfigChangeLogEntity entity) {
        return new ConsoleConfigChangeLogResponse(
                entity.getId(),
                ConsoleTextSanitizer.safeDisplay(entity.getTenantId()),
                ConsoleTextSanitizer.safeDisplay(entity.getConfigType()),
                ConsoleTextSanitizer.safeDisplay(entity.getConfigKey()),
                entity.getVersionNo(),
                ConsoleTextSanitizer.safeDisplay(entity.getChangeAction()),
                ConsoleTextSanitizer.safeDisplay(entity.getChangeResult()),
                ConsoleTextSanitizer.safeDisplay(entity.getOperatorType()),
                ConsoleTextSanitizer.safeDisplay(entity.getOperatorId()),
                ConsoleTextSanitizer.safeDisplay(entity.getTraceId()),
                ConsoleTextSanitizer.safeDisplay(entity.getChangeSummary()),
                entity.getCreatedAt()
        );
    }
}

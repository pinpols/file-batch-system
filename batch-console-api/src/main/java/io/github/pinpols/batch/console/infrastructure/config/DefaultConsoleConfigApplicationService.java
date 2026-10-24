package io.github.pinpols.batch.console.infrastructure.config;

import io.github.pinpols.batch.common.enums.ConfigLifecycleStatus;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.logging.AuditLogConstants;
import io.github.pinpols.batch.common.service.SecretPayloadProtector;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.ConsoleTextSanitizer;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Guard;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.SecretMasking;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.console.application.config.ConfigReleaseApplyService;
import io.github.pinpols.batch.console.application.config.ConsoleConfigApplicationService;
import io.github.pinpols.batch.console.application.contract.query.ConfigChangeLogQueryRequest;
import io.github.pinpols.batch.console.application.contract.query.ConfigReleaseQueryRequest;
import io.github.pinpols.batch.console.application.contract.query.SecretVersionQueryRequest;
import io.github.pinpols.batch.console.application.contract.request.config.ConfigReleaseActionRequest;
import io.github.pinpols.batch.console.application.contract.request.config.ConfigReleaseUpsertRequest;
import io.github.pinpols.batch.console.application.contract.response.config.ConfigDependenciesResponse;
import io.github.pinpols.batch.console.application.contract.response.config.ConfigGovernanceItemResponse;
import io.github.pinpols.batch.console.application.contract.response.config.ConfigReleaseDiffResponse;
import io.github.pinpols.batch.console.application.contract.response.config.ConsoleConfigChangeLogResponse;
import io.github.pinpols.batch.console.application.contract.response.config.ConsoleConfigReleaseResponse;
import io.github.pinpols.batch.console.domain.entity.ConfigChangeLogEntity;
import io.github.pinpols.batch.console.domain.entity.ConfigReleaseEntity;
import io.github.pinpols.batch.console.domain.observability.mapper.ConsoleDashboardQueryMapper;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.ConfigDependentView;
import io.github.pinpols.batch.console.domain.ops.application.contract.request.SecretVersionRevokeRequest;
import io.github.pinpols.batch.console.domain.ops.application.contract.request.SecretVersionRotateRequest;
import io.github.pinpols.batch.console.domain.query.ConfigChangeLogQuery;
import io.github.pinpols.batch.console.domain.query.ConfigReleaseQuery;
import io.github.pinpols.batch.console.domain.query.SecretVersionQuery;
import io.github.pinpols.batch.console.domain.rbac.entity.SecretVersionEntity;
import io.github.pinpols.batch.console.domain.rbac.mapper.SecretVersionMapper;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.mapper.ConfigChangeLogMapper;
import io.github.pinpols.batch.console.mapper.ConfigReleaseMapper;
import io.github.pinpols.batch.console.shared.view.ConsoleSecretVersionResponse;
import io.github.pinpols.batch.console.support.ConfigChangeLogBuilder;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 配置发布单 + 密钥版本治理服务：通过本地 Mapper 维护租户作用域下的 config_release、secret_version 与 config_change_log 三张表。
 *
 * <p>ConfigRelease 状态机（{@link io.github.pinpols.batch.common.enums.ConfigLifecycleStatus}）： {@code
 * DRAFT → PENDING_APPROVAL → PUBLISHED → ROLLED_BACK}。发布只能由审批服务完成。
 *
 * <ul>
 *   <li><b>版本号自增</b>：同 {@code (tenantId, configType, configKey)} 下 {@code selectLatestVersionNo +
 *       1}， 发布新版本不覆盖旧版本——历史版本保留便于回滚比对。
 *   <li><b>版本分配</b>：同一配置键先获取 PostgreSQL 事务级 advisory lock，再计算下一版本号。
 *   <li><b>ROLLBACK 时间戳</b>：由 {@link #changeReleaseStatus} 写入 {@code rolledBackAt}。
 * </ul>
 *
 * <p>SecretVersion rotation：发布新版本前先 {@code deactivateCurrentVersion}；草稿版本不成为当前版本。
 *
 * <p>所有写操作（create / rollback / rotate）都调 {@link #logChange} 落 {@code
 * config_change_log}（operatorId / traceId / reason / 变更摘要），提供完整审计轨迹。
 *
 * <p>JSON 字段（configPayloadJson / secretPayloadJson）入库前经 {@link #validateJson}
 * 解析校验格式合法性；配置发布载荷在创建时和提交审批时都会执行类型级校验。
 */
@Service
@RequiredArgsConstructor
public class DefaultConsoleConfigApplicationService implements ConsoleConfigApplicationService {

  // ── 重复使用的字段名常量 ───────────────────────────────────────────────
  private static final String KEY_CONFIG_TYPE = "configType";
  private static final String KEY_EFFECTIVE_FROM_AT = "effectiveFromAt";
  private static final String KEY_EFFECTIVE_TO_AT = "effectiveToAt";
  private static final String KEY_TENANT_ID = "tenantId";
  private static final String KEY_GRAY_SCOPE_JSON = "grayScopeJson";
  private static final String KEY_RELEASE_ID = "releaseId";
  private static final String KEY_SECRET_STATUS = "secretStatus";
  private static final String KEY_SECRET_VERSION_ID = "secretVersionId";
  private static final String EMPTY_JSON_OBJECT = "{}";
  private static final String REDACTED_SECRET_PAYLOAD = "{\"redacted\":true}";

  /** 配置发布状态机合法转换（nextStatus → 允许的当前状态集合）。 */
  private static final Map<String, Set<String>> ALLOWED_RELEASE_TRANSITIONS = Map.of(
      ConfigLifecycleStatus.ROLLED_BACK.code(),
      Set.of(ConfigLifecycleStatus.PUBLISHED.code(), ConfigLifecycleStatus.GRAY.code()));

  private final ConsoleTenantGuard tenantGuard;
  private final ConfigReleaseMapper configReleaseMapper;
  private final SecretVersionMapper secretVersionMapper;
  private final ConfigChangeLogMapper configChangeLogMapper;
  private final ConsoleDashboardQueryMapper dashboardQueryMapper;
  private final ConfigurationGovernanceCatalog governanceCatalog;
  private final ConfigReleaseApplyService configReleaseApplyService;
  private final SecretPayloadProtector secretPayloadProtector;
  private final ConsoleRequestMetadataResolver requestMetadataResolver;

  @Override
  public List<ConfigGovernanceItemResponse> configGovernanceCatalog() {
    return governanceCatalog.items();
  }

  @Override
  public List<ConsoleConfigReleaseResponse> configReleases(ConfigReleaseQueryRequest request) {
    ConfigReleaseQuery query = new ConfigReleaseQuery();
    String tenantId = resolveTenant(request.getTenantId());
    query.setTenantId(tenantId);
    query.setConfigType(request.getConfigType());
    query.setConfigKey(request.getConfigKey());
    query.setConfigStatus(request.getConfigStatus());
    query.setVersionNo(request.getVersionNo());
    List<ConfigReleaseEntity> releases = configReleaseMapper.selectByQuery(query);
    List<ConsoleConfigReleaseResponse> responses =
        releases.stream().map(this::toConfigReleaseResponse).toList();
    return responses;
  }

  @Override
  @Transactional
  public Long createConfigRelease(ConfigReleaseUpsertRequest request) {
    String tenantId = resolveTenant(request.getTenantId());
    String configType = configReleaseApplyService.canonicalType(request.getConfigType());
    validateJson(request.getConfigPayloadJson(), "configPayloadJson");
    configReleaseApplyService.validate(
        configType, request.getConfigKey(), request.getConfigPayloadJson());
    Map<String, Object> versionLockParameters = mapOf(
        KEY_TENANT_ID, tenantId, KEY_CONFIG_TYPE, configType, "configKey", request.getConfigKey());
    configReleaseMapper.acquireVersionLock(versionLockParameters);
    Map<String, Object> latestVersionQuery = mapOf(
        KEY_TENANT_ID, tenantId, KEY_CONFIG_TYPE, configType, "configKey", request.getConfigKey());
    Integer latestVersionNo = configReleaseMapper.selectLatestVersionNo(latestVersionQuery);
    int nextVersionNo = EmptyChecks.isNull(latestVersionNo) ? 1 : latestVersionNo + 1;
    String safeConfigKey = ConsoleTextSanitizer.safeInput(request.getConfigKey(), 128);
    String safeConfigName = ConsoleTextSanitizer.safeInput(request.getConfigName(), 256);
    String draftStatus = ConfigLifecycleStatus.DRAFT.code();
    String configPayloadJson = request.getConfigPayloadJson();
    Instant effectiveFrom = parseInstant(request.getEffectiveFromAt(), KEY_EFFECTIVE_FROM_AT);
    Instant effectiveTo = parseInstant(request.getEffectiveToAt(), KEY_EFFECTIVE_TO_AT);
    String createdBy = currentOperator();
    String updatedBy = currentOperator();
    Map<String, Object> releaseParameters = mapOf(
        KEY_TENANT_ID,
        tenantId,
        KEY_CONFIG_TYPE,
        configType,
        "configKey",
        safeConfigKey,
        "configName",
        safeConfigName,
        "configStatus",
        draftStatus,
        "versionNo",
        nextVersionNo,
        KEY_GRAY_SCOPE_JSON,
        null,
        "configPayloadJson",
        configPayloadJson,
        KEY_EFFECTIVE_FROM_AT,
        effectiveFrom,
        KEY_EFFECTIVE_TO_AT,
        effectiveTo,
        "createdBy",
        createdBy,
        "updatedBy",
        updatedBy);
    configReleaseMapper.insertConfigRelease(releaseParameters);
    String changeOperator = currentOperator();
    String changeTraceId = currentTraceId();
    String changeReason = request.getReason();
    ChangeLogContext changeContext =
        new ChangeLogContext(tenantId, changeOperator, changeTraceId, changeReason);
    ChangeLogTarget changeTarget =
        new ChangeLogTarget(configType, request.getConfigKey(), nextVersionNo);
    String safeChangeLogConfigName = ConsoleTextSanitizer.safeInput(request.getConfigName(), 256);
    Map<String, Object> changeDetails = Map.of(
        "configName", safeChangeLogConfigName, "configStatus", ConfigLifecycleStatus.DRAFT.code());
    ChangeLogChange change = new ChangeLogChange("CREATE", "SUCCESS", changeDetails);
    ChangeLogCommand changeLogCommand = new ChangeLogCommand(changeContext, changeTarget, change);
    logChange(changeLogCommand);
    return Long.valueOf(nextVersionNo);
  }

  @Override
  @Transactional
  public String rollbackConfigRelease(Long releaseId, ConfigReleaseActionRequest request) {
    String tenantId = resolveTenant(request.getTenantId());
    ConfigReleaseEntity release = loadRelease(tenantId, releaseId);
    acquireReleaseLock(release);
    release = loadRelease(tenantId, releaseId);
    validateExpectedVersion(request.getExpectedVersionNo(), release);
    validateLatestVersion(tenantId, release);
    validateReleaseTransition(release.getConfigStatus(), ConfigLifecycleStatus.ROLLED_BACK.code());
    Map<String, Object> previousEffectiveQuery = mapOf(
        KEY_TENANT_ID,
        tenantId,
        KEY_CONFIG_TYPE,
        release.getConfigType(),
        "configKey",
        release.getConfigKey(),
        "versionNo",
        release.getVersionNo());
    ConfigReleaseEntity previousEffectiveRelease =
        configReleaseMapper.selectPreviousEffective(previousEffectiveQuery);
    ConfigReleaseEntity previous =
        Guard.requireFound(previousEffectiveRelease, "previous effective config release not found");
    configReleaseApplyService.apply(
        previous, currentOperator(), "config-rollback-" + release.getId());
    return changeReleaseStatus(
        release, request, ConfigLifecycleStatus.ROLLED_BACK.code(), "ROLLBACK");
  }

  @Override
  public List<ConsoleSecretVersionResponse> secretVersions(SecretVersionQueryRequest request) {
    SecretVersionQuery query = new SecretVersionQuery();
    String tenantId = resolveTenant(request.getTenantId());
    query.setTenantId(tenantId);
    query.setSecretRef(request.getSecretRef());
    query.setSecretStatus(request.getSecretStatus());
    query.setCurrentVersion(request.getCurrentVersion());
    List<SecretVersionEntity> versions = secretVersionMapper.selectByQuery(query);
    List<ConsoleSecretVersionResponse> responses =
        versions.stream().map(this::toSecretVersionResponse).toList();
    return responses;
  }

  @Override
  @Transactional
  public Long rotateSecretVersion(SecretVersionRotateRequest request) {
    String tenantId = resolveTenant(request.getTenantId());
    String secretPayloadJson = resolveSecretPayload(request);
    validateJson(secretPayloadJson, "secretPayloadJson");
    secretVersionMapper.acquireVersionLock(
        mapOf(KEY_TENANT_ID, tenantId, "secretRef", request.getSecretRef()));
    Integer latestVersionNo = secretVersionMapper.selectLatestVersionNo(
        mapOf(KEY_TENANT_ID, tenantId, "secretRef", request.getSecretRef()));
    int nextVersionNo = EmptyChecks.isNull(latestVersionNo) ? 1 : latestVersionNo + 1;
    boolean hasRequestedSecretStatus = Texts.hasText(request.getSecretStatus());
    String nextStatus;
    if (hasRequestedSecretStatus) {
      String requestedSecretStatus = request.getSecretStatus();
      String trimmedSecretStatus = requestedSecretStatus.trim();
      nextStatus = trimmedSecretStatus.toUpperCase();
    } else {
      nextStatus = ConfigLifecycleStatus.PUBLISHED.code();
    }
    if (!Set.of(
            ConfigLifecycleStatus.DRAFT.code(),
            ConfigLifecycleStatus.PUBLISHED.code(),
            ConfigLifecycleStatus.GRAY.code())
        .contains(nextStatus)) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, ResultCode.INVALID_ARGUMENT.detailKey(), KEY_SECRET_STATUS);
    }
    boolean currentVersion = !ConfigLifecycleStatus.DRAFT.code().equals(nextStatus);
    if (currentVersion) {
      secretVersionMapper.deactivateCurrentVersion(
          mapOf(KEY_TENANT_ID, tenantId, "secretRef", request.getSecretRef()));
    }
    String protectedPayload;
    try {
      protectedPayload = secretPayloadProtector.protect(secretPayloadJson);
    } catch (IllegalArgumentException exception) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          ResultCode.INVALID_ARGUMENT.detailKey(),
          exception.getMessage());
    }
    String safeSecretRef = ConsoleTextSanitizer.safeInput(request.getSecretRef(), 128);
    String safeSecretName = ConsoleTextSanitizer.safeInput(request.getSecretName(), 256);
    Instant rotationWindowStart =
        parseInstant(request.getRotationWindowStartAt(), "rotationWindowStartAt");
    Instant rotationWindowEnd =
        parseInstant(request.getRotationWindowEndAt(), "rotationWindowEndAt");
    Instant effectiveFrom = parseInstant(request.getEffectiveFromAt(), KEY_EFFECTIVE_FROM_AT);
    Instant effectiveTo = parseInstant(request.getEffectiveToAt(), KEY_EFFECTIVE_TO_AT);
    String safeRotationReason = ConsoleTextSanitizer.safeInput(request.getReason(), 512);
    String createdBy = currentOperator();
    String updatedBy = currentOperator();
    Map<String, Object> secretVersionParameters = mapOf(
        KEY_TENANT_ID,
        tenantId,
        "secretRef",
        safeSecretRef,
        "secretName",
        safeSecretName,
        "versionNo",
        nextVersionNo,
        KEY_SECRET_STATUS,
        nextStatus,
        "currentVersion",
        currentVersion,
        "rotationWindowStartAt",
        rotationWindowStart,
        "rotationWindowEndAt",
        rotationWindowEnd,
        KEY_EFFECTIVE_FROM_AT,
        effectiveFrom,
        KEY_EFFECTIVE_TO_AT,
        effectiveTo,
        "secretPayloadJson",
        protectedPayload,
        "rotationReason",
        safeRotationReason,
        "createdBy",
        createdBy,
        "updatedBy",
        updatedBy);
    secretVersionMapper.insertSecretVersion(secretVersionParameters);
    String changeOperator = currentOperator();
    String changeTraceId = currentTraceId();
    String changeReason = request.getReason();
    ChangeLogContext changeContext =
        new ChangeLogContext(tenantId, changeOperator, changeTraceId, changeReason);
    ChangeLogTarget changeTarget =
        new ChangeLogTarget("SECRET", request.getSecretRef(), nextVersionNo);
    String safeChangeLogSecretName = ConsoleTextSanitizer.safeInput(request.getSecretName(), 256);
    Map<String, Object> changeDetails =
        Map.of("secretName", safeChangeLogSecretName, KEY_SECRET_STATUS, nextStatus);
    ChangeLogChange change = new ChangeLogChange("ROTATE", "SUCCESS", changeDetails);
    ChangeLogCommand changeLogCommand = new ChangeLogCommand(changeContext, changeTarget, change);
    logChange(changeLogCommand);
    return Long.valueOf(nextVersionNo);
  }

  @Override
  @Transactional
  public void revokeSecretVersion(Long secretVersionId, SecretVersionRevokeRequest request) {
    String tenantId = resolveTenant(request.getTenantId());
    Map<String, Object> secretVersionQuery =
        mapOf(KEY_TENANT_ID, tenantId, KEY_SECRET_VERSION_ID, secretVersionId);
    SecretVersionEntity selectedVersion = secretVersionMapper.selectById(secretVersionQuery);
    SecretVersionEntity version =
        Guard.requireFound(selectedVersion, "secret version not found: " + secretVersionId);
    if (ConfigLifecycleStatus.DRAFT.code().equals(version.getSecretStatus())) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, ResultCode.INVALID_ARGUMENT.detailKey());
    }
    if (EmptyChecks.isNotNull(version.getRevokedAt())) {
      return;
    }
    String operator = currentOperator();
    String reason = ConsoleTextSanitizer.safeInput(request.getReason(), 512);
    int updated = secretVersionMapper.revokeSecretVersion(mapOf(
        KEY_TENANT_ID,
        tenantId,
        KEY_SECRET_VERSION_ID,
        secretVersionId,
        "revokedBy",
        operator,
        "revokeReason",
        reason));
    if (updated == 0) {
      return;
    }
    String traceId = currentTraceId();
    ChangeLogContext changeContext = new ChangeLogContext(tenantId, operator, traceId, reason);
    ChangeLogTarget changeTarget =
        new ChangeLogTarget("SECRET", version.getSecretRef(), version.getVersionNo());
    Map<String, Object> changeDetails = Map.of(KEY_SECRET_VERSION_ID, secretVersionId);
    ChangeLogChange change = new ChangeLogChange("REVOKE", "SUCCESS", changeDetails);
    ChangeLogCommand changeCommand = new ChangeLogCommand(changeContext, changeTarget, change);
    logChange(changeCommand);
  }

  @Override
  public List<ConsoleConfigChangeLogResponse> configChangeLogs(
      ConfigChangeLogQueryRequest request) {
    ConfigChangeLogQuery query = new ConfigChangeLogQuery();
    String tenantId = resolveTenant(request.getTenantId());
    query.setTenantId(tenantId);
    query.setConfigType(request.getConfigType());
    query.setConfigKey(request.getConfigKey());
    query.setChangeAction(request.getChangeAction());
    List<ConfigChangeLogEntity> changeLogs = configChangeLogMapper.selectByQuery(query);
    List<ConsoleConfigChangeLogResponse> responses =
        changeLogs.stream().map(this::toConfigChangeLogResponse).toList();
    return responses;
  }

  private String changeReleaseStatus(
      ConfigReleaseEntity release,
      ConfigReleaseActionRequest request,
      String nextStatus,
      String changeAction) {
    String tenantId = release.getTenantId();
    Map<String, Object> params = mapOf(
        KEY_TENANT_ID,
        tenantId,
        KEY_RELEASE_ID,
        release.getId(),
        "nextStatus",
        nextStatus,
        "expectedStatus",
        release.getConfigStatus(),
        "expectedVersionNo",
        release.getVersionNo(),
        "publishedAt",
        ConfigLifecycleStatus.PUBLISHED.code().equals(nextStatus)
            ? BatchDateTimeSupport.utcNow()
            : null,
        "rolledBackAt",
        ConfigLifecycleStatus.ROLLED_BACK.code().equals(nextStatus)
            ? BatchDateTimeSupport.utcNow()
            : null,
        "updatedBy",
        currentOperator());
    int updated = configReleaseMapper.updateConfigReleaseStatus(params);
    if (updated != 1) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.config.release_concurrent_change");
    }
    String operator = currentOperator();
    String traceId = currentTraceId();
    String reason = request.getReason();
    ChangeLogContext changeContext = new ChangeLogContext(tenantId, operator, traceId, reason);
    String configType = release.getConfigType();
    String configKey = release.getConfigKey();
    Integer versionNo = release.getVersionNo();
    ChangeLogTarget changeTarget = new ChangeLogTarget(configType, configKey, versionNo);
    Map<String, Object> changeDetails = Map.of("nextStatus", nextStatus);
    ChangeLogChange change = new ChangeLogChange(changeAction, "SUCCESS", changeDetails);
    ChangeLogCommand changeLogCommand = new ChangeLogCommand(changeContext, changeTarget, change);
    logChange(changeLogCommand);
    return nextStatus;
  }

  /** 校验配置发布状态转换合法性，非法转换抛 STATE_CONFLICT(409),避免绕过审批门禁/复活已回滚/重复发布。 */
  private void validateReleaseTransition(String current, String next) {
    if (!ALLOWED_RELEASE_TRANSITIONS.getOrDefault(next, Set.of()).contains(current)) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT, "error.config.release_transition_illegal", current, next);
    }
  }

  private void validateExpectedVersion(Integer expectedVersionNo, ConfigReleaseEntity release) {
    if (EmptyChecks.isNotNull(expectedVersionNo)
        && !Objects.equals(expectedVersionNo, release.getVersionNo())) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT,
          "error.config.release_version_stale",
          expectedVersionNo,
          release.getVersionNo());
    }
  }

  private void validateLatestVersion(String tenantId, ConfigReleaseEntity release) {
    Integer latestVersionNo = configReleaseMapper.selectLatestVersionNo(mapOf(
        KEY_TENANT_ID,
        tenantId,
        KEY_CONFIG_TYPE,
        release.getConfigType(),
        "configKey",
        release.getConfigKey()));
    if (EmptyChecks.isNotNull(latestVersionNo)
        && !Objects.equals(latestVersionNo, release.getVersionNo())) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT,
          "error.config.release_not_latest",
          release.getVersionNo(),
          latestVersionNo);
    }
  }

  private ConfigReleaseEntity loadRelease(String tenantId, Long releaseId) {
    Map<String, Object> releaseQuery = mapOf(
        KEY_TENANT_ID, tenantId,
        KEY_RELEASE_ID, releaseId);
    ConfigReleaseEntity release = configReleaseMapper.selectById(releaseQuery);
    return Guard.requireFound(release, "config release not found");
  }

  private void acquireReleaseLock(ConfigReleaseEntity release) {
    String tenantId = release.getTenantId();
    String configType = release.getConfigType();
    String canonicalConfigType = configReleaseApplyService.canonicalType(configType);
    String configKey = release.getConfigKey();
    Map<String, Object> lockParameters = mapOf(
        KEY_TENANT_ID, tenantId, KEY_CONFIG_TYPE, canonicalConfigType, "configKey", configKey);
    configReleaseMapper.acquireVersionLock(lockParameters);
  }

  private void logChange(ChangeLogCommand command) {
    ConfigChangeLogBuilder builder = ConfigChangeLogBuilder.create(
            command.context().tenantId(),
            command.context().operatorId(),
            command.context().traceId())
        .forType(command.target().configType())
        .withKey(command.target().configKey())
        .versionNo(command.target().versionNo())
        .action(command.change().action())
        .result(command.change().result())
        .operatorType(AuditLogConstants.OPERATOR_TYPE_API);
    String safeReason = ConsoleTextSanitizer.safeInput(command.context().reason(), 512);
    Object changeDetails = command.change().detail();
    Map<String, Object> summaryDetails = detailOf(safeReason, changeDetails);
    String summaryJson = JsonUtils.toJson(summaryDetails);
    configChangeLogMapper.insertConfigChangeLog(builder.summary(summaryJson).build());
  }

  private String resolveTenant(String requestTenantId) {
    return tenantGuard.resolveTenant(requestTenantId);
  }

  private void validateJson(String value, String fieldName) {
    if (!Texts.hasText(value)) {
      return;
    }
    Object parsedJson = safeParseJson(value);
    if (EmptyChecks.isNull(parsedJson)) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, "error.field.must_be_valid_json", fieldName);
    }
  }

  /**
   * 容错解析 JSON:畸形 / 字面量 "null" / 空白 一律返回 null,不抛异常。
   *
   * <p>调用方按需翻译为 BizException(入参写路径)或当 null 处理(diff 读路径,容忍历史坏数据)。 旧实现直接调用 {@code JsonUtils.fromJson}
   * 在畸形 JSON 时抛 {@link IllegalArgumentException}, 穿透 ControllerAdvice 变 500;统一收敛在此。
   */
  private static Object safeParseJson(String value) {
    if (!Texts.hasText(value)) {
      return null;
    }
    try {
      return JsonUtils.fromJson(value, Object.class);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private Instant parseInstant(String value, String fieldName) {
    if (!Texts.hasText(value)) {
      return null;
    }
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException exception) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, "error.field.iso_datetime_required", fieldName);
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

  private String currentOperator() {
    ConsoleRequestMetadata metadata = requestMetadataResolver.current();
    String operatorId = Texts.hasText(metadata.operatorId()) ? metadata.operatorId() : "system";
    return ConsoleTextSanitizer.safeInput(operatorId, 64);
  }

  private String currentTraceId() {
    ConsoleRequestMetadata metadata = requestMetadataResolver.current();
    String traceId = metadata.traceId();
    return ConsoleTextSanitizer.safeInput(traceId, 128);
  }

  private String resolveSecretPayload(SecretVersionRotateRequest request) {
    if (Texts.hasText(request.getSecretPayloadJson())) {
      return request.getSecretPayloadJson();
    }
    if (EmptyChecks.isNotNull(request.getSecretPayload())) {
      Object secretPayload = request.getSecretPayload();
      return JsonUtils.toJson(secretPayload);
    }
    throw BizException.of(
        ResultCode.INVALID_ARGUMENT, ResultCode.INVALID_ARGUMENT.detailKey(), "secretPayloadJson");
  }

  private record ChangeLogContext(
      String tenantId, String operatorId, String traceId, String reason) {}

  private record ChangeLogCommand(
      ChangeLogContext context, ChangeLogTarget target, ChangeLogChange change) {}

  private record ChangeLogTarget(String configType, String configKey, Integer versionNo) {}

  private record ChangeLogChange(String action, String result, Object detail) {}

  @Override
  public ConsoleConfigReleaseResponse configReleaseDetail(String tenantId, Long releaseId) {
    String resolved = resolveTenant(tenantId);
    Map<String, Object> releaseQuery = mapOf(
        KEY_TENANT_ID, resolved,
        KEY_RELEASE_ID, releaseId);
    ConfigReleaseEntity release = configReleaseMapper.selectById(releaseQuery);
    ConfigReleaseEntity entity =
        Guard.requireFound(release, "config release not found: " + releaseId);
    return toConfigReleaseResponse(entity);
  }

  @Override
  public ConsoleSecretVersionResponse secretVersionDetail(String tenantId, Long secretVersionId) {
    String resolved = resolveTenant(tenantId);
    Map<String, Object> secretVersionQuery =
        mapOf(KEY_TENANT_ID, resolved, KEY_SECRET_VERSION_ID, secretVersionId);
    SecretVersionEntity secretVersion = secretVersionMapper.selectById(secretVersionQuery);
    SecretVersionEntity entity =
        Guard.requireFound(secretVersion, "secret version not found: " + secretVersionId);
    return toSecretVersionResponse(entity);
  }

  private ConsoleConfigReleaseResponse toConfigReleaseResponse(ConfigReleaseEntity entity) {
    Long id = entity.getId();
    String tenantId = ConsoleTextSanitizer.safeDisplay(entity.getTenantId());
    String configType = ConsoleTextSanitizer.safeDisplay(entity.getConfigType());
    String configKey = ConsoleTextSanitizer.safeDisplay(entity.getConfigKey());
    String configName = ConsoleTextSanitizer.safeDisplay(entity.getConfigName());
    String configStatus = ConsoleTextSanitizer.safeDisplay(entity.getConfigStatus());
    Integer versionNo = entity.getVersionNo();
    String confirmationStatus = applyConfirmationStatus(entity.getConfigStatus());
    String grayScope = jsonResponse(entity.getGrayScope());
    String configPayload = jsonResponse(entity.getConfigPayload());
    Instant effectiveFromAt = entity.getEffectiveFromAt();
    Instant effectiveToAt = entity.getEffectiveToAt();
    Instant publishedAt = entity.getPublishedAt();
    Instant rolledBackAt = entity.getRolledBackAt();
    String createdBy = ConsoleTextSanitizer.safeDisplay(entity.getCreatedBy());
    String updatedBy = ConsoleTextSanitizer.safeDisplay(entity.getUpdatedBy());
    Instant createdAt = entity.getCreatedAt();
    Instant updatedAt = entity.getUpdatedAt();
    return ConsoleConfigReleaseResponse.builder()
        .id(id)
        .tenantId(tenantId)
        .configType(configType)
        .configKey(configKey)
        .configName(configName)
        .configStatus(configStatus)
        .versionNo(versionNo)
        .configSource("DYNAMIC_DB")
        .activationMode("IMMEDIATE_AFTER_CONFIRMATION")
        .restartRequired(false)
        .applyConfirmationStatus(confirmationStatus)
        .grayScopeJson(grayScope)
        .configPayloadJson(configPayload)
        .effectiveFromAt(effectiveFromAt)
        .effectiveToAt(effectiveToAt)
        .publishedAt(publishedAt)
        .rolledBackAt(rolledBackAt)
        .createdBy(createdBy)
        .updatedBy(updatedBy)
        .createdAt(createdAt)
        .updatedAt(updatedAt)
        .build();
  }

  private String applyConfirmationStatus(String configStatus) {
    if (ConfigLifecycleStatus.PUBLISHED.code().equals(configStatus)
        || ConfigLifecycleStatus.GRAY.code().equals(configStatus)) {
      return "CONFIRMATION_REQUIRED";
    }
    if (ConfigLifecycleStatus.ROLLED_BACK.code().equals(configStatus)) {
      return "ROLLED_BACK";
    }
    return "NOT_RELEASED";
  }

  /** JSON 字符串由 Jackson 负责传输转义；HTML 转义会把引号改成实体并破坏客户端解析。 */
  private static String jsonResponse(String value) {
    return Texts.hasText(value) ? value : EMPTY_JSON_OBJECT;
  }

  private ConsoleSecretVersionResponse toSecretVersionResponse(SecretVersionEntity entity) {
    Long id = entity.getId();
    String tenantId = ConsoleTextSanitizer.safeDisplay(entity.getTenantId());
    String secretRef = ConsoleTextSanitizer.safeDisplay(entity.getSecretRef());
    String secretName = ConsoleTextSanitizer.safeDisplay(entity.getSecretName());
    Integer versionNo = entity.getVersionNo();
    String secretStatus = ConsoleTextSanitizer.safeDisplay(entity.getSecretStatus());
    Boolean currentVersion = entity.getCurrentVersion();
    Instant rotationWindowStartAt = entity.getRotationWindowStartAt();
    Instant rotationWindowEndAt = entity.getRotationWindowEndAt();
    Instant effectiveFromAt = entity.getEffectiveFromAt();
    Instant effectiveToAt = entity.getEffectiveToAt();
    String rotationReason = ConsoleTextSanitizer.safeDisplay(entity.getRotationReason());
    Instant revokedAt = entity.getRevokedAt();
    String revokedBy = ConsoleTextSanitizer.safeDisplay(entity.getRevokedBy());
    String revokeReason = ConsoleTextSanitizer.safeDisplay(entity.getRevokeReason());
    String createdBy = ConsoleTextSanitizer.safeDisplay(entity.getCreatedBy());
    String updatedBy = ConsoleTextSanitizer.safeDisplay(entity.getUpdatedBy());
    Instant createdAt = entity.getCreatedAt();
    Instant updatedAt = entity.getUpdatedAt();
    return ConsoleSecretVersionResponse.builder()
        .id(id)
        .tenantId(tenantId)
        .secretRef(secretRef)
        .secretName(secretName)
        .versionNo(versionNo)
        .secretStatus(secretStatus)
        .currentVersion(currentVersion)
        .rotationWindowStartAt(rotationWindowStartAt)
        .rotationWindowEndAt(rotationWindowEndAt)
        .effectiveFromAt(effectiveFromAt)
        .effectiveToAt(effectiveToAt)
        .secretPayloadJson(REDACTED_SECRET_PAYLOAD)
        .rotationReason(rotationReason)
        .revokedAt(revokedAt)
        .revokedBy(revokedBy)
        .revokeReason(revokeReason)
        .createdBy(createdBy)
        .updatedBy(updatedBy)
        .createdAt(createdAt)
        .updatedAt(updatedAt)
        .build();
  }

  @Override
  public ConfigDependenciesResponse configDependencies(
      String tenantId, String configType, String configCode) {
    String resolved = resolveTenant(tenantId);
    Map<String, DependencyQuery> dependencyQueryByType = dependencyQueries();
    String canonicalType = canonicalDependencyType(configType);
    DependencyQuery dependencyQuery = dependencyQueryByType.get(canonicalType);
    List<ConfigDependentView> dependentJobs = EmptyChecks.isNull(dependencyQuery)
        ? List.of()
        : dependencyQuery.find(resolved, configCode);
    List<ConfigDependenciesResponse.DependentJobResponse> dependentJobResponses =
        dependentJobs.stream()
            .map(job -> {
              Long jobId = job.id();
              String jobCode = job.code();
              String jobName = EmptyChecks.isNull(job.name()) ? "" : job.name();
              return new ConfigDependenciesResponse.DependentJobResponse(
                  jobId, jobCode, jobName);
            })
            .toList();
    return new ConfigDependenciesResponse(
        configType,
        configCode,
        dependentJobResponses,
        dependentJobs.size());
  }

  private Map<String, DependencyQuery> dependencyQueries() {
    return Map.of(
        "RESOURCE_QUEUE",
        dashboardQueryMapper::jobsByQueueCode,
        "BUSINESS_CALENDAR",
        dashboardQueryMapper::jobsByCalendarCode,
        "BATCH_WINDOW",
        dashboardQueryMapper::jobsByWindowCode,
        "WORKER_GROUP",
        dashboardQueryMapper::jobsByWorkerGroup);
  }

  private String canonicalDependencyType(String raw) {
    if (!Texts.hasText(raw)) {
      return "";
    }
    String trimmedRaw = raw.trim();
    String normalized = trimmedRaw.toUpperCase(Locale.ROOT);
    return Map.of(
            "QUEUE", "RESOURCE_QUEUE", "CALENDAR", "BUSINESS_CALENDAR", "WINDOW", "BATCH_WINDOW")
        .getOrDefault(normalized, normalized);
  }

  @FunctionalInterface
  private interface DependencyQuery {
    List<ConfigDependentView> find(String tenantId, String configCode);
  }

  @Override
  public ConfigReleaseDiffResponse diffConfigReleases(
      String tenantId, Long releaseIdA, Long releaseIdB) {
    String resolved = resolveTenant(tenantId);
    ConfigReleaseEntity a = loadRelease(resolved, releaseIdA);
    ConfigReleaseEntity b = loadRelease(resolved, releaseIdB);
    // JSON payload diff:容忍历史坏 JSON,坏数据按 null 比较(否则穿透 500)。
    Object payloadA = safeParseJson(a.getConfigPayload());
    Object payloadB = safeParseJson(b.getConfigPayload());
    boolean payloadChanged = !Objects.equals(payloadA, payloadB);
    // 灰度范围差异
    Object grayA = safeParseJson(a.getGrayScope());
    Object grayB = safeParseJson(b.getGrayScope());
    boolean grayChanged = !Objects.equals(grayA, grayB);
    ConsoleConfigReleaseResponse releaseA = toConfigReleaseResponse(a);
    ConsoleConfigReleaseResponse releaseB = toConfigReleaseResponse(b);
    boolean statusChanged = !Objects.equals(a.getConfigStatus(), b.getConfigStatus());
    return new ConfigReleaseDiffResponse(
        releaseA,
        releaseB,
        payloadChanged,
        payloadChanged ? payloadA : null,
        payloadChanged ? payloadB : null,
        grayChanged,
        statusChanged);
  }

  private ConsoleConfigChangeLogResponse toConfigChangeLogResponse(ConfigChangeLogEntity entity) {
    Long changeLogId = entity.getId();
    String tenantId = ConsoleTextSanitizer.safeDisplay(entity.getTenantId());
    String configType = ConsoleTextSanitizer.safeDisplay(entity.getConfigType());
    String configKey = ConsoleTextSanitizer.safeDisplay(entity.getConfigKey());
    Integer versionNo = entity.getVersionNo();
    String changeAction = ConsoleTextSanitizer.safeDisplay(entity.getChangeAction());
    String changeResult = ConsoleTextSanitizer.safeDisplay(entity.getChangeResult());
    String operatorType = ConsoleTextSanitizer.safeDisplay(entity.getOperatorType());
    String operatorId = ConsoleTextSanitizer.safeDisplay(entity.getOperatorId());
    String traceId = ConsoleTextSanitizer.safeDisplay(entity.getTraceId());
    String rawChangeSummary = entity.getChangeSummary();
    String maskedChangeSummary = SecretMasking.maskSensitiveJson(rawChangeSummary);
    String safeChangeSummary = ConsoleTextSanitizer.safeDisplay(maskedChangeSummary);
    Instant createdAt = entity.getCreatedAt();
    return ConsoleConfigChangeLogResponse.builder()
        .id(changeLogId)
        .tenantId(tenantId)
        .configType(configType)
        .configKey(configKey)
        .versionNo(versionNo)
        .changeAction(changeAction)
        .changeResult(changeResult)
        .operatorType(operatorType)
        .operatorId(operatorId)
        .traceId(traceId)
        .changeSummaryJson(safeChangeSummary)
        .createdAt(createdAt)
        .build();
  }
}

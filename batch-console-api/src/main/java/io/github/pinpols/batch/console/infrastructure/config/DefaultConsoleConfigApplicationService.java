package io.github.pinpols.batch.console.infrastructure.config;

import io.github.pinpols.batch.common.enums.ConfigLifecycleStatus;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.service.SecretPayloadProtector;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.ConsoleTextSanitizer;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Guard;
import io.github.pinpols.batch.common.utils.JsonUtils;
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
    query.setTenantId(resolveTenant(request.getTenantId()));
    query.setConfigType(request.getConfigType());
    query.setConfigKey(request.getConfigKey());
    query.setConfigStatus(request.getConfigStatus());
    query.setVersionNo(request.getVersionNo());
    return configReleaseMapper.selectByQuery(query).stream()
        .map(this::toConfigReleaseResponse)
        .toList();
  }

  @Override
  @Transactional
  public Long createConfigRelease(ConfigReleaseUpsertRequest request) {
    String tenantId = resolveTenant(request.getTenantId());
    String configType = configReleaseApplyService.canonicalType(request.getConfigType());
    validateJson(request.getConfigPayloadJson(), "configPayloadJson");
    configReleaseApplyService.validate(
        configType, request.getConfigKey(), request.getConfigPayloadJson());
    configReleaseMapper.acquireVersionLock(mapOf(
        KEY_TENANT_ID, tenantId, KEY_CONFIG_TYPE, configType, "configKey", request.getConfigKey()));
    Integer latestVersionNo = configReleaseMapper.selectLatestVersionNo(mapOf(
        KEY_TENANT_ID, tenantId, KEY_CONFIG_TYPE, configType, "configKey", request.getConfigKey()));
    int nextVersionNo = EmptyChecks.isNull(latestVersionNo) ? 1 : latestVersionNo + 1;
    configReleaseMapper.insertConfigRelease(mapOf(
        KEY_TENANT_ID,
        tenantId,
        KEY_CONFIG_TYPE,
        configType,
        "configKey",
        ConsoleTextSanitizer.safeInput(request.getConfigKey(), 128),
        "configName",
        ConsoleTextSanitizer.safeInput(request.getConfigName(), 256),
        "configStatus",
        ConfigLifecycleStatus.DRAFT.code(),
        "versionNo",
        nextVersionNo,
        KEY_GRAY_SCOPE_JSON,
        null,
        "configPayloadJson",
        request.getConfigPayloadJson(),
        KEY_EFFECTIVE_FROM_AT,
        parseInstant(request.getEffectiveFromAt(), KEY_EFFECTIVE_FROM_AT),
        KEY_EFFECTIVE_TO_AT,
        parseInstant(request.getEffectiveToAt(), KEY_EFFECTIVE_TO_AT),
        "createdBy",
        currentOperator(),
        "updatedBy",
        currentOperator()));
    ChangeLogCommand changeLogCommand = new ChangeLogCommand(
        new ChangeLogContext(tenantId, currentOperator(), currentTraceId(), request.getReason()),
        new ChangeLogTarget(configType, request.getConfigKey(), nextVersionNo),
        new ChangeLogChange(
            "CREATE",
            "SUCCESS",
            Map.of(
                "configName", ConsoleTextSanitizer.safeInput(request.getConfigName(), 256),
                "configStatus", ConfigLifecycleStatus.DRAFT.code())));
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
    ConfigReleaseEntity previous = Guard.requireFound(
        configReleaseMapper.selectPreviousEffective(mapOf(
            KEY_TENANT_ID,
            tenantId,
            KEY_CONFIG_TYPE,
            release.getConfigType(),
            "configKey",
            release.getConfigKey(),
            "versionNo",
            release.getVersionNo())),
        "previous effective config release not found");
    configReleaseApplyService.apply(
        previous, currentOperator(), "config-rollback-" + release.getId());
    return changeReleaseStatus(
        release, request, ConfigLifecycleStatus.ROLLED_BACK.code(), "ROLLBACK");
  }

  @Override
  public List<ConsoleSecretVersionResponse> secretVersions(SecretVersionQueryRequest request) {
    SecretVersionQuery query = new SecretVersionQuery();
    query.setTenantId(resolveTenant(request.getTenantId()));
    query.setSecretRef(request.getSecretRef());
    query.setSecretStatus(request.getSecretStatus());
    query.setCurrentVersion(request.getCurrentVersion());
    return secretVersionMapper.selectByQuery(query).stream()
        .map(this::toSecretVersionResponse)
        .toList();
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
    String nextStatus = Texts.hasText(request.getSecretStatus())
        ? request.getSecretStatus().trim().toUpperCase()
        : ConfigLifecycleStatus.PUBLISHED.code();
    if (!Set.of(
            ConfigLifecycleStatus.DRAFT.code(),
            ConfigLifecycleStatus.PUBLISHED.code(),
            ConfigLifecycleStatus.GRAY.code())
        .contains(nextStatus)) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, "error.common.invalid_argument_detail", "secretStatus");
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
          "error.common.invalid_argument_detail",
          exception.getMessage());
    }
    secretVersionMapper.insertSecretVersion(mapOf(
        KEY_TENANT_ID,
        tenantId,
        "secretRef",
        ConsoleTextSanitizer.safeInput(request.getSecretRef(), 128),
        "secretName",
        ConsoleTextSanitizer.safeInput(request.getSecretName(), 256),
        "versionNo",
        nextVersionNo,
        "secretStatus",
        nextStatus,
        "currentVersion",
        currentVersion,
        "rotationWindowStartAt",
        parseInstant(request.getRotationWindowStartAt(), "rotationWindowStartAt"),
        "rotationWindowEndAt",
        parseInstant(request.getRotationWindowEndAt(), "rotationWindowEndAt"),
        KEY_EFFECTIVE_FROM_AT,
        parseInstant(request.getEffectiveFromAt(), KEY_EFFECTIVE_FROM_AT),
        KEY_EFFECTIVE_TO_AT,
        parseInstant(request.getEffectiveToAt(), KEY_EFFECTIVE_TO_AT),
        "secretPayloadJson",
        protectedPayload,
        "rotationReason",
        ConsoleTextSanitizer.safeInput(request.getReason(), 512),
        "createdBy",
        currentOperator(),
        "updatedBy",
        currentOperator()));
    ChangeLogCommand changeLogCommand = new ChangeLogCommand(
        new ChangeLogContext(tenantId, currentOperator(), currentTraceId(), request.getReason()),
        new ChangeLogTarget("SECRET", request.getSecretRef(), nextVersionNo),
        new ChangeLogChange(
            "ROTATE",
            "SUCCESS",
            Map.of(
                "secretName",
                ConsoleTextSanitizer.safeInput(request.getSecretName(), 256),
                "secretStatus",
                nextStatus)));
    logChange(changeLogCommand);
    return Long.valueOf(nextVersionNo);
  }

  @Override
  public List<ConsoleConfigChangeLogResponse> configChangeLogs(
      ConfigChangeLogQueryRequest request) {
    ConfigChangeLogQuery query = new ConfigChangeLogQuery();
    query.setTenantId(resolveTenant(request.getTenantId()));
    query.setConfigType(request.getConfigType());
    query.setConfigKey(request.getConfigKey());
    query.setChangeAction(request.getChangeAction());
    return configChangeLogMapper.selectByQuery(query).stream()
        .map(this::toConfigChangeLogResponse)
        .toList();
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
    ChangeLogCommand changeLogCommand = new ChangeLogCommand(
        new ChangeLogContext(tenantId, currentOperator(), currentTraceId(), request.getReason()),
        new ChangeLogTarget(
            release.getConfigType(), release.getConfigKey(), release.getVersionNo()),
        new ChangeLogChange(changeAction, "SUCCESS", Map.of("nextStatus", nextStatus)));
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
    return Guard.requireFound(
        configReleaseMapper.selectById(mapOf(
            KEY_TENANT_ID, tenantId,
            KEY_RELEASE_ID, releaseId)),
        "config release not found");
  }

  private void acquireReleaseLock(ConfigReleaseEntity release) {
    configReleaseMapper.acquireVersionLock(mapOf(
        KEY_TENANT_ID,
        release.getTenantId(),
        KEY_CONFIG_TYPE,
        configReleaseApplyService.canonicalType(release.getConfigType()),
        "configKey",
        release.getConfigKey()));
  }

  private void logChange(ChangeLogCommand command) {
    configChangeLogMapper.insertConfigChangeLog(ConfigChangeLogBuilder.create(
            command.context().tenantId(),
            command.context().operatorId(),
            command.context().traceId())
        .forType(command.target().configType())
        .withKey(command.target().configKey())
        .versionNo(command.target().versionNo())
        .action(command.change().action())
        .result(command.change().result())
        .operatorType("API")
        .summary(JsonUtils.toJson(detailOf(
            ConsoleTextSanitizer.safeInput(command.context().reason(), 512),
            command.change().detail())))
        .build());
  }

  private String resolveTenant(String requestTenantId) {
    return tenantGuard.resolveTenant(requestTenantId);
  }

  private void validateJson(String value, String fieldName) {
    if (!Texts.hasText(value)) {
      return;
    }
    if (EmptyChecks.isNull(safeParseJson(value))) {
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
    return ConsoleTextSanitizer.safeInput(requestMetadataResolver.current().traceId(), 128);
  }

  private String resolveSecretPayload(SecretVersionRotateRequest request) {
    if (Texts.hasText(request.getSecretPayloadJson())) {
      return request.getSecretPayloadJson();
    }
    if (EmptyChecks.isNotNull(request.getSecretPayload())) {
      return JsonUtils.toJson(request.getSecretPayload());
    }
    throw BizException.of(
        ResultCode.INVALID_ARGUMENT, "error.common.invalid_argument_detail", "secretPayloadJson");
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
    ConfigReleaseEntity entity = Guard.requireFound(
        configReleaseMapper.selectById(mapOf(
            KEY_TENANT_ID, resolved,
            KEY_RELEASE_ID, releaseId)),
        "config release not found: " + releaseId);
    return toConfigReleaseResponse(entity);
  }

  @Override
  public ConsoleSecretVersionResponse secretVersionDetail(String tenantId, Long secretVersionId) {
    String resolved = resolveTenant(tenantId);
    SecretVersionEntity entity = Guard.requireFound(
        secretVersionMapper.selectById(
            mapOf(KEY_TENANT_ID, resolved, "secretVersionId", secretVersionId)),
        "secret version not found: " + secretVersionId);
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
        "DYNAMIC_DB",
        "IMMEDIATE_AFTER_CONFIRMATION",
        false,
        applyConfirmationStatus(entity.getConfigStatus()),
        jsonResponse(entity.getGrayScope()),
        jsonResponse(entity.getConfigPayload()),
        entity.getEffectiveFromAt(),
        entity.getEffectiveToAt(),
        entity.getPublishedAt(),
        entity.getRolledBackAt(),
        ConsoleTextSanitizer.safeDisplay(entity.getCreatedBy()),
        ConsoleTextSanitizer.safeDisplay(entity.getUpdatedBy()),
        entity.getCreatedAt(),
        entity.getUpdatedAt());
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
        REDACTED_SECRET_PAYLOAD,
        ConsoleTextSanitizer.safeDisplay(entity.getRotationReason()),
        ConsoleTextSanitizer.safeDisplay(entity.getCreatedBy()),
        ConsoleTextSanitizer.safeDisplay(entity.getUpdatedBy()),
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }

  @Override
  public ConfigDependenciesResponse configDependencies(
      String tenantId, String configType, String configCode) {
    String resolved = resolveTenant(tenantId);
    List<ConfigDependentView> dependentJobs =
        switch (configType.toUpperCase()) {
          case "QUEUE", "RESOURCE_QUEUE" ->
            dashboardQueryMapper.jobsByQueueCode(resolved, configCode);
          case "CALENDAR", "BUSINESS_CALENDAR" ->
            dashboardQueryMapper.jobsByCalendarCode(resolved, configCode);
          case "WINDOW", "BATCH_WINDOW" ->
            dashboardQueryMapper.jobsByWindowCode(resolved, configCode);
          case "WORKER_GROUP" -> dashboardQueryMapper.jobsByWorkerGroup(resolved, configCode);
          default -> List.of();
        };
    return new ConfigDependenciesResponse(
        configType,
        configCode,
        dependentJobs.stream()
            .map(job -> new ConfigDependenciesResponse.DependentJobResponse(
                job.id(), job.code(), EmptyChecks.isNull(job.name()) ? "" : job.name()))
            .toList(),
        dependentJobs.size());
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
    return new ConfigReleaseDiffResponse(
        toConfigReleaseResponse(a),
        toConfigReleaseResponse(b),
        payloadChanged,
        payloadChanged ? payloadA : null,
        payloadChanged ? payloadB : null,
        grayChanged,
        !Objects.equals(a.getConfigStatus(), b.getConfigStatus()));
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
        entity.getCreatedAt());
  }
}

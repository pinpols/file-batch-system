package io.github.pinpols.batch.orchestrator.service;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.common.context.RunModeSupport;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.dto.ReplayExecutionGuard;
import io.github.pinpols.batch.common.enums.RunMode;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.domain.entity.CustomTaskTypeRegistryEntity;
import io.github.pinpols.batch.orchestrator.domain.view.JobDefinitionView;
import io.github.pinpols.batch.orchestrator.mapper.CustomTaskTypeRegistryMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 启动参数解析与合并工具：从请求参数、作业定义中提取/转换各类运行态字段。 */
@Service
@RequiredArgsConstructor
@SuppressWarnings("java:S2583")
public class LaunchParamResolver {

  /** 模板占位符 {@code ${var}}(SDK Phase 3 M3.1 — descriptor.defaults 中的运行态变量)。 */
  private static final Pattern TEMPLATE_TOKEN = Pattern.compile("\\$\\{([a-zA-Z0-9_.]+)}");

  private final BatchTimezoneProvider timezoneProvider;
  private final BatchDateTimeSupport dateTimeSupport;
  private final CustomTaskTypeRegistryMapper customTaskTypeRegistryMapper;

  /**
   * 派单参数合并(SDK Phase 3 M3.1 任务 3.1.5),优先级 低→高:
   *
   * <ol>
   *   <li>SDK 声明的自定义 taskType {@code descriptor.defaults}(模板替换后)—— 最低优先级,只回退未显式给值的 key
   *   <li>{@code job_definition.default_params}
   *   <li>请求 / node 运行态参数({@code request.params()})
   * </ol>
   *
   * <p>descriptor.defaults 仅在 {@code job_definition.job_type} 命中 {@code custom_task_type_registry}
   * 时注入;模板变量见 {@link #templateVariables}。敏感凭据禁止走 defaults(走环境变量,roadmap §5.5)。
   */
  Map<String, Object> mergeLaunchParams(JobDefinitionView jobDefinition, LaunchRequest request) {
    Map<String, Object> merged = new LinkedHashMap<>();
    applyDescriptorDefaults(merged, jobDefinition, request);
    if (jobDefinition != null && jobDefinition.defaultParams() != null) {
      merged.putAll(jobDefinition.defaultParams());
    }
    if (request != null && request.params() != null) {
      merged.putAll(request.params());
    }
    // 文件束身份只能由分区绑定生成，不能从调用方或作业默认参数继承。
    merged.remove("bundleSourceFileId");
    merged.remove("bundleTargetRef");
    TriggerType triggerType = request == null ? null : request.triggerType();
    RunMode resolvedRunMode = resolveRunMode(triggerType, merged);
    return RunModeSupport.copyWithDefault(merged, resolvedRunMode);
  }

  /**
   * 把命中的自定义 taskType {@code descriptor.defaults} 作为最低优先级层注入 {@code merged};对 String 值做模板替换 ({@code
   * ${bizDate}} 等)。无命中 / 无 defaults / 解析失败均静默跳过,不阻断派单。
   */
  private void applyDescriptorDefaults(
      Map<String, Object> merged, JobDefinitionView jobDefinition, LaunchRequest request) {
    if (jobDefinition == null
        || jobDefinition.tenantId() == null
        || jobDefinition.jobType() == null) {
      return;
    }
    CustomTaskTypeRegistryEntity entity = customTaskTypeRegistryMapper.selectByTenantAndCode(
        jobDefinition.tenantId(), jobDefinition.jobType());
    if (EmptyChecks.isNull(entity)) {
      return;
    }
    String descriptorJson = entity.descriptor();
    if (EmptyChecks.isBlank(descriptorJson)) {
      return;
    }
    Map<String, Object> defaults = parseDescriptorDefaults(descriptorJson);
    if (defaults.isEmpty()) {
      return;
    }
    Map<String, String> variables = templateVariables(request);
    for (Map.Entry<String, Object> entry : defaults.entrySet()) {
      String key = entry.getKey();
      Object defaultValue = entry.getValue();
      Object resolvedValue = substituteTemplates(defaultValue, variables);
      merged.put(key, resolvedValue);
    }
  }

  private Map<String, Object> parseDescriptorDefaults(String descriptorJson) {
    try {
      Map<String, Object> descriptor =
          JsonUtils.fromJson(descriptorJson, new TypeReference<Map<String, Object>>() {});
      Object defaults = descriptor == null ? null : descriptor.get("defaults");
      if (defaults instanceof Map<?, ?> defaultsMap) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : defaultsMap.entrySet()) {
          out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
      }
    } catch (RuntimeException ex) {
      SwallowedExceptionLogger.warn(LaunchParamResolver.class, "catch:descriptorParse", ex);
    }
    return Map.of();
  }

  /** 派单期可用的模板变量(由 {@link LaunchRequest} 推导);仅替换 descriptor.defaults 的 String 值。 */
  private Map<String, String> templateVariables(LaunchRequest request) {
    Map<String, String> vars = new LinkedHashMap<>();
    if (request == null) {
      return vars;
    }
    if (request.bizDate() != null) {
      vars.put("bizDate", request.bizDate().toString());
    }
    if (request.dataIntervalStart() != null) {
      vars.put("dataIntervalStart", request.dataIntervalStart().toString());
    }
    if (request.dataIntervalEnd() != null) {
      vars.put("dataIntervalEnd", request.dataIntervalEnd().toString());
    }
    return vars;
  }

  /** 只对 String 值做 {@code ${var}} 替换;未知变量原样保留(交由 worker / 下游识别)。 */
  private Object substituteTemplates(Object value, Map<String, String> variables) {
    if (!(value instanceof String text) || text.indexOf("${") < 0 || variables.isEmpty()) {
      return value;
    }
    Matcher matcher = TEMPLATE_TOKEN.matcher(text);
    StringBuilder sb = new StringBuilder();
    while (matcher.find()) {
      String replacement = variables.get(matcher.group(1));
      String replacementText = EmptyChecks.isNotNull(replacement) ? replacement : matcher.group();
      String quotedReplacement = Matcher.quoteReplacement(replacementText);
      matcher.appendReplacement(sb, quotedReplacement);
    }
    matcher.appendTail(sb);
    return sb.toString();
  }

  String resolveBatchNo(LocalDate bizDate, Map<String, Object> params) {
    Object v = firstNonNull(params.get("batchNo"), params.get("batch_no"), params.get("batchCode"));
    if (EmptyChecks.isNotNull(v)) {
      String checkedText = String.valueOf(v);
      if (!EmptyChecks.isBlank(Objects.requireNonNull(checkedText))) {
        String returnedText = String.valueOf(v);
        return returnedText.trim();
      }
    }
    return bizDate == null ? null : bizDate.toString();
  }

  static String resolveOperatorId(Map<String, Object> params) {
    Object v = firstNonNull(params.get("operatorId"), params.get("operator"), params.get("userId"));
    if (EmptyChecks.isNull(v)) {
      return null;
    }
    String rawText = String.valueOf(v);
    return rawText.trim();
  }

  boolean resolveRerunFlag(TriggerType triggerType, Map<String, Object> params) {
    if (toBoolean(params.get("rerunFlag"))) {
      return true;
    }
    String operationType = textValue(params.get("operationType"));
    return TriggerType.CATCH_UP == triggerType
        || "RERUN".equalsIgnoreCase(operationType)
        || "JOB_RERUN".equalsIgnoreCase(operationType)
        || "BATCH_RERUN".equalsIgnoreCase(operationType);
  }

  boolean resolveRetryFlag(Map<String, Object> params) {
    if (toBoolean(params.get("retryFlag"))) {
      return true;
    }
    String operationType = textValue(params.get("operationType"));
    return "RETRY".equalsIgnoreCase(operationType)
        || "PARTITION_RETRY".equalsIgnoreCase(operationType)
        || "DLQ_REPLAY".equalsIgnoreCase(operationType);
  }

  RunMode resolveRunMode(TriggerType triggerType, Map<String, Object> params) {
    RunMode explicit = RunModeSupport.resolve(params);
    if (explicit != null) {
      return explicit;
    }
    String operationType = textValue(params.get("operationType"));
    if ("COMPENSATE".equalsIgnoreCase(operationType)
        || "COMPENSATION".equalsIgnoreCase(operationType)) {
      return RunMode.COMPENSATE;
    }
    if ("RECOVER".equalsIgnoreCase(operationType)
        || "FAILOVER_RECOVER".equalsIgnoreCase(operationType)) {
      return RunMode.RECOVER;
    }
    if (resolveRetryFlag(params)) {
      return RunMode.RETRY;
    }
    if (resolveRerunFlag(triggerType, params)) {
      return RunMode.RERUN;
    }
    return RunMode.NORMAL;
  }

  String resolveRerunReason(Map<String, Object> params) {
    Object v = firstNonNull(params.get("rerunReason"), params.get("reason"));
    if (EmptyChecks.isNull(v)) {
      return null;
    }
    String rawText = String.valueOf(v);
    return rawText.trim();
  }

  Long resolveRelatedFileId(Map<String, Object> params) {
    Object relatedFileIdValue =
        firstNonNull(params.get("relatedFileId"), params.get("fileId"), params.get("sourceFileId"));
    return toPositiveLong(relatedFileIdValue);
  }

  public Long resolveParentInstanceId(Map<String, Object> params) {
    Object parentInstanceIdValue =
        firstNonNull(params.get("parentInstanceId"), params.get("targetInstanceId"));
    return toPositiveLong(parentInstanceIdValue);
  }

  Instant resolveDeadlineAt(
      Instant createdAt,
      LocalDate bizDate,
      JobDefinitionView jobDefinition,
      Map<String, Object> params,
      Instant batchDaySlaDeadlineAt) {
    Object requestedDeadlineValue =
        firstNonNull(params.get("deadlineAt"), params.get("deadline"), params.get("slaDeadlineAt"));
    Instant explicit = parseDeadlineInstant(requestedDeadlineValue, bizDate);
    Instant deadlineTime = parseDeadlineInstant(params.get("deadlineTime"), bizDate);
    Instant jobDeadlineAt = resolveJobDeadlineAt(createdAt, jobDefinition);
    return earliest(explicit, deadlineTime, jobDeadlineAt, batchDaySlaDeadlineAt);
  }

  Instant resolveJobDeadlineAt(Instant createdAt, JobDefinitionView jobDefinition) {
    if (EmptyChecks.isNull(createdAt)) {
      return null;
    }
    if (EmptyChecks.isNull(jobDefinition)) {
      return null;
    }
    Integer timeoutSeconds = jobDefinition.timeoutSeconds();
    if (EmptyChecks.isNull(timeoutSeconds) || timeoutSeconds <= 0) {
      return null;
    }
    return createdAt.plusSeconds(timeoutSeconds);
  }

  Integer resolveExpectedDurationSeconds(
      JobDefinitionView jobDefinition, Map<String, Object> params) {
    Integer explicitValue = firstPositiveInt(
        params.get("expectedDurationSeconds"),
        params.get("expected_duration_seconds"),
        params.get("expectedDuration"),
        params.get("slaExpectedDurationSeconds"));
    if (explicitValue != null) {
      return explicitValue;
    }
    if (EmptyChecks.isNotNull(jobDefinition)) {
      Integer timeoutSeconds = jobDefinition.timeoutSeconds();
      if (EmptyChecks.isNotNull(timeoutSeconds) && timeoutSeconds > 0) {
        return timeoutSeconds;
      }
    }
    return 0;
  }

  String buildParamsSnapshot(
      @Nullable JobDefinitionView jobDefinition,
      LaunchRequest request,
      Map<String, Object> effectiveParams,
      String traceId,
      ReplayExecutionGuard replayExecutionGuard) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("jobDefinitionId", jobDefinition == null ? null : jobDefinition.id());
    snapshot.put("jobDefinitionVersion", jobDefinition == null ? null : jobDefinition.version());
    snapshot.put("executionPolicy", executionPolicySnapshot(jobDefinition));
    snapshot.put("jobCode", request.jobCode());
    snapshot.put(
        "triggerType",
        request.triggerType() == null ? null : request.triggerType().code());
    snapshot.put("traceId", traceId);
    snapshot.put("priorityOrder", List.of("defaultParams", "requestParams", "effectiveParams"));
    snapshot.put(
        "paramSchema",
        jobDefinition == null || jobDefinition.paramSchema() == null
            ? Map.of()
            : jobDefinition.paramSchema());
    snapshot.put(
        "defaultParams",
        jobDefinition == null || jobDefinition.defaultParams() == null
            ? Map.of()
            : jobDefinition.defaultParams());
    snapshot.put("requestParams", request.params() == null ? Map.of() : request.params());
    snapshot.put("effectiveParams", effectiveParams == null ? Map.of() : effectiveParams);
    if (EmptyChecks.isNotEmpty(request.dependencyResultSnapshot())) {
      snapshot.put("dependencyResultSnapshot", request.dependencyResultSnapshot());
    }
    if (EmptyChecks.isNotNull(replayExecutionGuard)) {
      snapshot.put("replayExecutionGuard", replayExecutionGuard);
    }
    return JsonUtils.toJson(snapshot);
  }

  private Map<String, Object> executionPolicySnapshot(@Nullable JobDefinitionView jobDefinition) {
    if (jobDefinition == null) { // empty-check: allow - Sonar S2259
      return Map.of();
    }
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("executionMode", jobDefinition.executionMode());
    snapshot.put("watermarkField", jobDefinition.watermarkField());
    snapshot.put("retryPolicy", jobDefinition.retryPolicy());
    snapshot.put("retryMaxCount", jobDefinition.retryMaxCount());
    snapshot.put("timeoutSeconds", jobDefinition.timeoutSeconds());
    return snapshot;
  }

  String buildPayloadJson(Map<String, Object> params) {
    Map<String, Object> resolvedParams = EmptyChecks.isNull(params) ? Map.of() : params;
    return JsonUtils.toJson(resolvedParams);
  }

  Instant parseDeadlineInstant(Object value, LocalDate bizDate) {
    if (value == null) {
      return null;
    }
    if (value instanceof Instant instant) {
      return instant;
    }
    if (value instanceof LocalDateTime ldt) {
      ZoneId defaultZone = timezoneProvider.defaultZone();
      ZonedDateTime zonedDeadline = ldt.atZone(defaultZone);
      return zonedDeadline.toInstant();
    }
    if (value instanceof LocalTime lt) {
      LocalDate d = resolveDateOrTodayInDefaultZone(bizDate);
      LocalDateTime deadline = d.atTime(lt);
      ZoneId defaultZone = timezoneProvider.defaultZone();
      ZonedDateTime zonedDeadline = deadline.atZone(defaultZone);
      return zonedDeadline.toInstant();
    }
    String rawText = String.valueOf(value);
    String text = rawText.trim();
    if (text.isEmpty()) {
      return null;
    }
    try {
      return Instant.parse(text);
    } catch (Exception ignored) {
      SwallowedExceptionLogger.warn(LaunchParamResolver.class, "catch:Exception", ignored);
    }
    try {
      LocalDateTime localDeadline = LocalDateTime.parse(text);
      ZoneId defaultZone = timezoneProvider.defaultZone();
      ZonedDateTime zonedDeadline = localDeadline.atZone(defaultZone);
      return zonedDeadline.toInstant();
    } catch (Exception ignored) {
      SwallowedExceptionLogger.warn(LaunchParamResolver.class, "catch:Exception", ignored);
    }
    try {
      LocalDate d = resolveDateOrTodayInDefaultZone(bizDate);
      LocalTime parsedDeadline = LocalTime.parse(text);
      LocalDateTime localDeadline = d.atTime(parsedDeadline);
      ZoneId defaultZone = timezoneProvider.defaultZone();
      ZonedDateTime zonedDeadline = localDeadline.atZone(defaultZone);
      return zonedDeadline.toInstant();
    } catch (Exception ignored) {
      SwallowedExceptionLogger.warn(LaunchParamResolver.class, "catch:Exception", ignored);
    }
    return null;
  }

  private LocalDate resolveDateOrTodayInDefaultZone(LocalDate bizDate) {
    return bizDate == null ? dateTimeSupport.todayInDefaultBusinessZone() : bizDate;
  }

  Instant earliest(Instant... candidates) {
    Instant result = null;
    for (Instant candidate : candidates) {
      if (candidate == null) {
        continue;
      }
      if (result == null || candidate.isBefore(result)) {
        result = candidate;
      }
    }
    return result;
  }

  static Object firstNonNull(Object... candidates) {
    for (Object c : candidates) {
      if (c != null) {
        return c;
      }
    }
    return null;
  }

  Integer firstPositiveInt(Object... candidates) {
    for (Object c : candidates) {
      Integer v = toPositiveInt(c);
      if (v != null) {
        return v;
      }
    }
    return null;
  }

  Integer toPositiveInt(Object candidate) {
    if (candidate instanceof Number n) {
      int v = n.intValue();
      return v > 0 ? v : null;
    }
    if (candidate == null) {
      return null;
    }
    String rawText = String.valueOf(candidate);
    String text = rawText.trim();
    if (text.isEmpty()) {
      return null;
    }
    try {
      int v = Integer.parseInt(text);
      return v > 0 ? v : null;
    } catch (NumberFormatException ignored) {
      SwallowedExceptionLogger.info(
          LaunchParamResolver.class, "catch:NumberFormatException", ignored);

      return null;
    }
  }

  Long toPositiveLong(Object candidate) {
    if (candidate == null) {
      return null;
    }
    String rawText = String.valueOf(candidate);
    String text = rawText.trim();
    if (text.isEmpty()) {
      return null;
    }
    try {
      long v = Long.parseLong(text);
      return v > 0 ? v : null;
    } catch (NumberFormatException ignored) {
      SwallowedExceptionLogger.info(
          LaunchParamResolver.class, "catch:NumberFormatException", ignored);

      return null;
    }
  }

  static boolean toBoolean(Object candidate) {
    if (candidate instanceof Boolean b) {
      return b;
    }
    return "true".equalsIgnoreCase(textValue(candidate))
        || "1".equals(textValue(candidate))
        || "Y".equalsIgnoreCase(textValue(candidate));
  }

  static String textValue(Object candidate) {
    if (candidate == null) {
      return null;
    }
    String rawText = String.valueOf(candidate);
    String text = rawText.trim();
    return text.isEmpty() ? null : text;
  }

  static Integer safeIncrement(Integer value) {
    return value == null ? 1 : value + 1;
  }
}

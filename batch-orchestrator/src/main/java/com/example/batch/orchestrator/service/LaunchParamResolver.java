package com.example.batch.orchestrator.service;

import com.example.batch.common.config.BatchTimezoneProvider;
import com.example.batch.common.context.RunModeSupport;
import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.enums.RunMode;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.orchestrator.domain.entity.JobDefinitionRecord;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 启动参数解析与合并工具：从请求参数、作业定义中提取/转换各类运行态字段。 */
@Service
@RequiredArgsConstructor
public class LaunchParamResolver {

  private final BatchTimezoneProvider timezoneProvider;

  Map<String, Object> mergeLaunchParams(
      JobDefinitionRecord jobDefinition,
      TriggerType triggerType,
      Map<String, Object> runtimeParams) {
    Map<String, Object> merged = new LinkedHashMap<>();
    if (jobDefinition != null && jobDefinition.defaultParams() != null) {
      merged.putAll(jobDefinition.defaultParams());
    }
    if (runtimeParams != null) {
      merged.putAll(runtimeParams);
    }
    return RunModeSupport.copyWithDefault(merged, resolveRunMode(triggerType, merged));
  }

  String resolveBatchNo(LocalDate bizDate, Map<String, Object> params) {
    Object v = firstNonNull(params.get("batchNo"), params.get("batch_no"), params.get("batchCode"));
    if (v != null && !String.valueOf(v).isBlank()) {
      return String.valueOf(v).trim();
    }
    return bizDate == null ? null : bizDate.toString();
  }

  static String resolveOperatorId(Map<String, Object> params) {
    Object v = firstNonNull(params.get("operatorId"), params.get("operator"), params.get("userId"));
    return v == null ? null : String.valueOf(v).trim();
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
    return v == null ? null : String.valueOf(v).trim();
  }

  Long resolveRelatedFileId(Map<String, Object> params) {
    return toPositiveLong(
        firstNonNull(
            params.get("relatedFileId"), params.get("fileId"), params.get("sourceFileId")));
  }

  Long resolveParentInstanceId(Map<String, Object> params) {
    return toPositiveLong(
        firstNonNull(params.get("parentInstanceId"), params.get("targetInstanceId")));
  }

  Instant resolveDeadlineAt(
      Instant createdAt,
      LocalDate bizDate,
      JobDefinitionRecord jobDefinition,
      Map<String, Object> params,
      Instant batchDaySlaDeadlineAt) {
    Instant explicit =
        parseDeadlineInstant(
            firstNonNull(
                params.get("deadlineAt"), params.get("deadline"), params.get("slaDeadlineAt")),
            bizDate);
    Instant deadlineTime = parseDeadlineInstant(params.get("deadlineTime"), bizDate);
    Instant jobDeadlineAt = resolveJobDeadlineAt(createdAt, jobDefinition);
    return earliest(explicit, deadlineTime, jobDeadlineAt, batchDaySlaDeadlineAt);
  }

  Instant resolveJobDeadlineAt(Instant createdAt, JobDefinitionRecord jobDefinition) {
    if (createdAt == null
        || jobDefinition == null
        || jobDefinition.timeoutSeconds() == null
        || jobDefinition.timeoutSeconds() <= 0) {
      return null;
    }
    return createdAt.plusSeconds(jobDefinition.timeoutSeconds());
  }

  Integer resolveExpectedDurationSeconds(
      JobDefinitionRecord jobDefinition, Map<String, Object> params) {
    Integer explicitValue =
        firstPositiveInt(
            params.get("expectedDurationSeconds"),
            params.get("expected_duration_seconds"),
            params.get("expectedDuration"),
            params.get("slaExpectedDurationSeconds"));
    if (explicitValue != null) {
      return explicitValue;
    }
    if (jobDefinition != null
        && jobDefinition.timeoutSeconds() != null
        && jobDefinition.timeoutSeconds() > 0) {
      return jobDefinition.timeoutSeconds();
    }
    return 0;
  }

  String buildParamsSnapshot(
      JobDefinitionRecord jobDefinition,
      LaunchRequest request,
      Map<String, Object> effectiveParams,
      String traceId) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("jobDefinitionId", jobDefinition == null ? null : jobDefinition.id());
    snapshot.put("jobCode", request.jobCode());
    snapshot.put(
        "triggerType", request.triggerType() == null ? null : request.triggerType().code());
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
    return JsonUtils.toJson(snapshot);
  }

  String buildPayloadJson(Map<String, Object> params) {
    return JsonUtils.toJson(params == null ? Map.of() : params);
  }

  Instant parseDeadlineInstant(Object value, LocalDate bizDate) {
    if (value == null) {
      return null;
    }
    if (value instanceof Instant instant) {
      return instant;
    }
    if (value instanceof LocalDateTime ldt) {
      return ldt.atZone(timezoneProvider.defaultZone()).toInstant();
    }
    if (value instanceof LocalTime lt) {
      LocalDate d = bizDate == null ? LocalDate.now() : bizDate;
      return d.atTime(lt).atZone(timezoneProvider.defaultZone()).toInstant();
    }
    String text = String.valueOf(value).trim();
    if (text.isEmpty()) {
      return null;
    }
    try {
      return Instant.parse(text);
    } catch (Exception ignored) {
    }
    try {
      return LocalDateTime.parse(text).atZone(timezoneProvider.defaultZone()).toInstant();
    } catch (Exception ignored) {
    }
    try {
      LocalDate d = bizDate == null ? LocalDate.now() : bizDate;
      return d.atTime(LocalTime.parse(text)).atZone(timezoneProvider.defaultZone()).toInstant();
    } catch (Exception ignored) {
    }
    return null;
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
    String text = String.valueOf(candidate).trim();
    if (text.isEmpty()) {
      return null;
    }
    try {
      int v = Integer.parseInt(text);
      return v > 0 ? v : null;
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  Long toPositiveLong(Object candidate) {
    if (candidate instanceof Number n) {
      long v = n.longValue();
      return v > 0 ? v : null;
    }
    if (candidate == null) {
      return null;
    }
    String text = String.valueOf(candidate).trim();
    if (text.isEmpty()) {
      return null;
    }
    try {
      long v = Long.parseLong(text);
      return v > 0 ? v : null;
    } catch (NumberFormatException ignored) {
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
    String text = String.valueOf(candidate).trim();
    return text.isEmpty() ? null : text;
  }

  static Integer safeIncrement(Integer value) {
    return value == null ? 1 : value + 1;
  }
}

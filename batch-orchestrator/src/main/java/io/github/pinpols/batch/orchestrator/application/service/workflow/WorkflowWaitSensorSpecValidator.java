package io.github.pinpols.batch.orchestrator.application.service.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.pinpols.batch.common.enums.DictEnum;
import io.github.pinpols.batch.common.enums.SensorTimeoutAction;
import io.github.pinpols.batch.common.enums.SensorType;
import io.github.pinpols.batch.common.enums.WorkflowNodeType;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowValidationResult.ValidationIssue;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeEntity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** 校验工作流 WAIT 节点携带的传感器专属配置。 */
final class WorkflowWaitSensorSpecValidator {

  private static final String V16C = "V16-c";
  private static final String V16D = "V16-d";
  private static final TypeReference<Map<String, Object>> SENSOR_MAP_TYPE =
      new TypeReference<>() {};
  private static final Set<String> SENSOR_TYPES = DictEnum.codes(SensorType.class);
  private static final Set<String> SENSOR_TIMEOUT_ACTIONS =
      DictEnum.codes(SensorTimeoutAction.class);

  List<ValidationIssue> validate(WorkflowNodeEntity node) {
    if (!WorkflowNodeType.WAIT.code().equalsIgnoreCase(node.getNodeType())) {
      return List.of();
    }
    List<ValidationIssue> errors = new ArrayList<>();
    validateSensorSpec(node, errors);
    return List.copyOf(errors);
  }

  private void validateSensorSpec(WorkflowNodeEntity node, List<ValidationIssue> errors) {
    Map<String, Object> params;
    try {
      params = Texts.hasText(node.getNodeParams())
          ? JsonUtils.fromJson(node.getNodeParams(), SENSOR_MAP_TYPE)
          : Map.of();
    } catch (Exception e) {
      errors.add(issue(
          "V16", "WAIT node_params JSON parse failed: " + e.getMessage(), node.getNodeCode()));
      return;
    }
    if (EmptyChecks.isNull(params)) params = Map.of();

    String sensorType = asString(params.get("sensor_type"));
    if (!Texts.hasText(sensorType)) {
      errors.add(issue("V16-a", "WAIT node missing sensor_type", node.getNodeCode()));
      return;
    }
    if (!SENSOR_TYPES.contains(sensorType)) {
      errors.add(issue(
          "V16-b",
          "WAIT sensor_type invalid: " + sensorType + " (allowed: " + SENSOR_TYPES + ")",
          node.getNodeCode()));
      return;
    }

    Optional<Map<String, Object>> spec = stringKeyedMap(params.get("sensor_spec"));
    if (!spec.isPresent()) {
      errors.add(issue(V16C, "WAIT sensor_spec missing or not an object", node.getNodeCode()));
    } else {
      validateSensorSpecByType(sensorType, spec.orElseThrow(), node.getNodeCode(), errors);
    }

    long timeout = Objects.requireNonNullElse(asLong(params.get("timeout_seconds")), 0L);
    long pollInterval = Objects.requireNonNullElse(asLong(params.get("poll_interval_seconds")), 0L);
    if (timeout <= 0) {
      errors.add(issue(V16D, "WAIT timeout_seconds missing or <=0", node.getNodeCode()));
    }
    if (pollInterval <= 0) {
      errors.add(issue(V16D, "WAIT poll_interval_seconds missing or <=0", node.getNodeCode()));
    }
    if (timeout > 0 && pollInterval > 0 && timeout <= pollInterval) {
      errors.add(issue(
          V16D,
          "WAIT timeout_seconds must be greater than poll_interval_seconds",
          node.getNodeCode()));
    }

    String onTimeout = asString(params.get("on_timeout"));
    if (!Texts.hasText(onTimeout)) {
      errors.add(issue("V16-e", "WAIT on_timeout missing", node.getNodeCode()));
    } else if (!SENSOR_TIMEOUT_ACTIONS.contains(onTimeout)) {
      errors.add(issue(
          "V16-e",
          "WAIT on_timeout invalid: " + onTimeout + " (allowed: " + SENSOR_TIMEOUT_ACTIONS + ")",
          node.getNodeCode()));
    }
  }

  private void validateSensorSpecByType(
      String sensorType, Map<String, Object> spec, String nodeCode, List<ValidationIssue> errors) {
    SensorSpecValidator validator = sensorSpecValidators().get(sensorType);
    if (EmptyChecks.isNotNull(validator)) {
      validator.validate(spec, nodeCode, errors);
    }
  }

  private Map<String, SensorSpecValidator> sensorSpecValidators() {
    return Map.of(
        SensorType.FILE_ARRIVAL.code(), this::validateFileArrivalSensorSpec,
        SensorType.HTTP_POLL.code(), this::validateHttpPollSensorSpec,
        SensorType.KAFKA_OFFSET.code(), this::validateKafkaOffsetSensorSpec,
        SensorType.DB_ROW_EXISTS.code(), this::validateDbRowExistsSensorSpec);
  }

  private void validateFileArrivalSensorSpec(
      Map<String, Object> spec, String nodeCode, List<ValidationIssue> errors) {
    if (!Texts.hasText(asString(spec.get("pattern")))) {
      errors.add(issue(V16C, "FILE_ARRIVAL sensor_spec.pattern required", nodeCode));
    }
    long age = Objects.requireNonNullElse(asLong(spec.get("maxAgeSeconds")), 0L);
    if (age <= 0) {
      errors.add(issue(V16C, "FILE_ARRIVAL sensor_spec.maxAgeSeconds required", nodeCode));
    }
  }

  private void validateHttpPollSensorSpec(
      Map<String, Object> spec, String nodeCode, List<ValidationIssue> errors) {
    if (!Texts.hasText(asString(spec.get("url")))) {
      errors.add(issue(V16C, "HTTP_POLL sensor_spec.url required", nodeCode));
    }
    if (!Texts.hasText(asString(spec.get("matchExpr")))) {
      errors.add(issue(V16C, "HTTP_POLL sensor_spec.matchExpr required", nodeCode));
    }
  }

  private void validateKafkaOffsetSensorSpec(
      Map<String, Object> spec, String nodeCode, List<ValidationIssue> errors) {
    if (!Texts.hasText(asString(spec.get("topic")))) {
      errors.add(issue(V16C, "KAFKA_OFFSET sensor_spec.topic required", nodeCode));
    }
    if (EmptyChecks.isNull(asLong(spec.get("partition")))) {
      errors.add(issue(V16C, "KAFKA_OFFSET sensor_spec.partition required", nodeCode));
    }
    if (EmptyChecks.isNull(asLong(spec.get("minOffset")))) {
      errors.add(issue(V16C, "KAFKA_OFFSET sensor_spec.minOffset required", nodeCode));
    }
  }

  private void validateDbRowExistsSensorSpec(
      Map<String, Object> spec, String nodeCode, List<ValidationIssue> errors) {
    if (!Texts.hasText(asString(spec.get("schema")))) {
      errors.add(issue(V16C, "DB_ROW_EXISTS sensor_spec.schema required", nodeCode));
    }
    if (!Texts.hasText(asString(spec.get("sql")))) {
      errors.add(issue(V16C, "DB_ROW_EXISTS sensor_spec.sql required", nodeCode));
    }
  }

  @FunctionalInterface
  private interface SensorSpecValidator {
    void validate(Map<String, Object> spec, String nodeCode, List<ValidationIssue> errors);
  }

  private static String asString(Object value) {
    return EmptyChecks.isNull(value) ? null : value.toString();
  }

  private static Optional<Map<String, Object>> stringKeyedMap(Object value) {
    if (!(value instanceof Map<?, ?> map)) {
      return Optional.empty();
    }
    Map<String, Object> result = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (!(entry.getKey() instanceof String key)) {
        return Optional.empty();
      }
      result.put(key, entry.getValue());
    }
    return Optional.of(result);
  }

  private static Long asLong(Object value) {
    if (EmptyChecks.isNull(value)) return null;
    if (value instanceof Number number) return number.longValue();
    try {
      return Long.parseLong(value.toString().trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static ValidationIssue issue(String code, String message, String nodeCode) {
    return ValidationIssue.builder()
        .code(code)
        .severity(ValidationIssue.SEVERITY_ERROR)
        .nodeCode(nodeCode)
        .message(message)
        .build();
  }
}

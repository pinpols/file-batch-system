package com.example.batch.worker.imports.infrastructure;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.plugin.WorkerPluginIds;
import com.example.batch.common.utils.ContentMaskingUtils;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.imports.domain.CustomerImportPayload;
import com.example.batch.worker.imports.domain.ImportJobContext;
import com.example.batch.worker.imports.domain.ImportPayload;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 导入数据质量校验服务：对数据集级别和记录级别分别执行规则校验。
 *
 * <p><b>数据集级别校验</b>（{@link #validateDataset}）：行数检查、摘要校验（checksum_check）、
 * schema 字段必选/允许列表。
 *
 * <p><b>记录级别校验</b>（{@link #validateChunkRows}）：null 字段检查、字段规则（required/
 * minLength/maxLength/regex/allowedValues/min/max）、唯一值检查；支持流式分块调用，
 * {@link ValidationSession} 在分块间共享 {@code seenValues} 以实现跨分块唯一性校验。
 *
 * <p><b>规则集合并</b>：默认规则集（customerNo/customerName 必填，customerType/status 枚举校验）
 * 与模板配置中的 {@code validation_rule_set} 深度合并，模板配置优先。
 * 若模板启用了 {@code IMPORT_LOAD_JDBC_MAPPED} 插件，则跳过默认规则集。
 *
 * <p><b>脱敏</b>：模板配置 {@code log_masking_enabled=true} 时，{@link #outcome} 在返回前
 * 对 {@link ValidationIssue} 的错误信息和原始记录按 {@code masking_rule_set} 脱敏处理；
 * {@code BatchSecurityProperties#isTestingOpen()} 为 true 时强制关闭脱敏。
 */
@Service
@RequiredArgsConstructor
public class ImportDataQualityService {

  // ── duplicate literal constants ─────────────────────────────────────────
  private static final String KEY_MIN = "min";
  private static final String KEY_MAX = "max";
  private static final String MSG_ACTUAL_SUFFIX = ", actual=";
  private static final String KEY_ACTUAL = "actual";
  private static final String KEY_REQUIRED = "required";
  private static final String KEY_ALLOWED_VALUES = "allowedValues";
  private static final String KEY_ERROR_CODE = "errorCode";

  private final ObjectMapper objectMapper;
  private final BatchSecurityProperties batchSecurityProperties;

  public ValidationOutcome validate(
      ImportJobContext context, List<CustomerImportPayload> payloads) {
    List<String> schemaFields =
        stringList(context == null ? null : context.getAttributes().get("schemaFields"));
    ValidationSession session =
        beginValidation(context, payloads == null ? 0L : payloads.size(), schemaFields);
    validateDataset(session);
    validateChunk(session, payloads, 1L);
    return outcome(session);
  }

  public ValidationSession beginValidation(
      ImportJobContext context, long totalCount, List<String> schemaFields) {
    Map<String, Object> ruleSet =
        mergedRuleSet(
            context == null
                ? null
                : context.getAttributes().get(PipelineRuntimeKeys.TEMPLATE_CONFIG));
    ImportPayload importPayload =
        context != null
                && context.getAttributes().get("importPayload") instanceof ImportPayload payload
            ? payload
            : null;
    String normalizedPayload =
        context == null ? null : stringValue(context.getAttributes().get("normalizedPayload"));
    return new ValidationSession(
        context,
        ruleSet,
        totalCount,
        normalizedPayload,
        importPayload,
        schemaFields == null ? List.of() : List.copyOf(schemaFields),
        new LinkedHashMap<>(),
        new ArrayList<>(),
        new LinkedHashMap<>(),
        new LinkedHashSet<>());
  }

  public void validateDataset(ValidationSession session) {
    if (session == null) {
      return;
    }
    evaluateRowCount(
        session.totalCount(), session.ruleSet(), session.datasetIssues(), session.appliedChecks());
    evaluateChecksum(
        session.normalizedPayload(),
        session.importPayload(),
        session.ruleSet(),
        session.datasetIssues(),
        session.appliedChecks());
    evaluateSchema(
        session.schemaFields(),
        session.ruleSet(),
        session.datasetIssues(),
        session.appliedChecks());
  }

  public Map<Long, ValidationIssue> validateChunk(
      ValidationSession session, List<CustomerImportPayload> payloads, long recordBase) {
    if (session == null || payloads == null || payloads.isEmpty()) {
      return Map.of();
    }
    List<Map<String, Object>> rows = new ArrayList<>(payloads.size());
    for (CustomerImportPayload payload : payloads) {
      rows.add(payloadToMap(payload));
    }
    return validateChunkRows(session, rows, recordBase);
  }

  public Map<Long, ValidationIssue> validateChunkRows(
      ValidationSession session, List<Map<String, Object>> rows, long recordBase) {
    if (session == null || rows == null || rows.isEmpty()) {
      return Map.of();
    }
    return evaluateRecordRules(
        rows,
        session.ruleSet(),
        session.recordIssues(),
        session.appliedChecks(),
        session.seenValues(),
        recordBase);
  }

  public ValidationOutcome outcome(ValidationSession session) {
    if (session == null) {
      return new ValidationOutcome(Map.of(), List.of(), List.of());
    }
    boolean logMask = logMaskingEnabled(session) && !batchSecurityProperties.isTestingOpen();
    String ruleSet = maskingRuleSet(session);
    return new ValidationOutcome(
        maskRecordIssues(session.recordIssues(), logMask, ruleSet),
        maskDatasetIssues(session.datasetIssues(), logMask, ruleSet),
        List.copyOf(session.appliedChecks()));
  }

  private boolean logMaskingEnabled(ValidationSession session) {
    Object cfg =
        session.context() == null
            ? null
            : session.context().getAttributes().get(PipelineRuntimeKeys.TEMPLATE_CONFIG);
    if (!(cfg instanceof Map<?, ?> map)) {
      return false;
    }
    Object flag = map.get("log_masking_enabled");
    return Boolean.TRUE.equals(flag) || "true".equalsIgnoreCase(String.valueOf(flag));
  }

  private String maskingRuleSet(ValidationSession session) {
    Object cfg =
        session.context() == null
            ? null
            : session.context().getAttributes().get(PipelineRuntimeKeys.TEMPLATE_CONFIG);
    if (!(cfg instanceof Map<?, ?> map)) {
      return null;
    }
    Object rule = map.get("masking_rule_set");
    return rule == null ? null : String.valueOf(rule);
  }

  private Map<Long, ValidationIssue> maskRecordIssues(
      Map<Long, ValidationIssue> issues, boolean mask, String ruleSet) {
    if (!mask || issues == null || issues.isEmpty()) {
      return new LinkedHashMap<>(issues);
    }
    Map<Long, ValidationIssue> masked = new LinkedHashMap<>();
    for (Map.Entry<Long, ValidationIssue> entry : issues.entrySet()) {
      ValidationIssue issue = entry.getValue();
      masked.put(
          entry.getKey(),
          new ValidationIssue(
              issue.recordNo(),
              issue.errorCode(),
              ContentMaskingUtils.maskPlainText(issue.errorMessage(), ruleSet),
              maskIssueRaw(issue.rawRecord(), ruleSet)));
    }
    return masked;
  }

  private List<ValidationIssue> maskDatasetIssues(
      List<ValidationIssue> issues, boolean mask, String ruleSet) {
    if (!mask || issues == null || issues.isEmpty()) {
      return new ArrayList<>(issues == null ? List.of() : issues);
    }
    List<ValidationIssue> masked = new ArrayList<>();
    for (ValidationIssue issue : issues) {
      masked.add(
          new ValidationIssue(
              issue.recordNo(),
              issue.errorCode(),
              ContentMaskingUtils.maskPlainText(issue.errorMessage(), ruleSet),
              maskIssueRaw(issue.rawRecord(), ruleSet)));
    }
    return masked;
  }

  private Object maskIssueRaw(Object raw, String ruleSet) {
    if (raw == null) {
      return null;
    }
    if (raw instanceof String text) {
      return ContentMaskingUtils.maskPlainText(text, ruleSet);
    }
    return ContentMaskingUtils.maskPlainText(JsonUtils.toJson(raw), ruleSet);
  }

  private void evaluateRowCount(
      long actualCount,
      Map<String, Object> ruleSet,
      List<ValidationIssue> datasetIssues,
      Set<String> appliedChecks) {
    Map<String, Object> rule = firstMap(ruleSet, "rowCountCheck", "row_count_check");
    if (rule.isEmpty() || !enabled(rule)) {
      return;
    }
    appliedChecks.add("row_count_check");
    Integer exactCount = integerValue(rule.get("exact"));
    Integer minCount = integerValue(firstNonNull(rule.get(KEY_MIN), rule.get("minimum")));
    Integer maxCount = integerValue(firstNonNull(rule.get(KEY_MAX), rule.get("maximum")));
    if (exactCount != null && actualCount != exactCount) {
      datasetIssues.add(
          new ValidationIssue(
              null,
              "IMPORT_VALIDATE_ROW_COUNT",
              "row count mismatch, expected=" + exactCount + MSG_ACTUAL_SUFFIX + actualCount,
              Map.of("expected", exactCount, KEY_ACTUAL, actualCount)));
      return;
    }
    if (minCount != null && actualCount < minCount) {
      datasetIssues.add(
          new ValidationIssue(
              null,
              "IMPORT_VALIDATE_ROW_COUNT",
              "row count below minimum, min=" + minCount + MSG_ACTUAL_SUFFIX + actualCount,
              Map.of(KEY_MIN, minCount, KEY_ACTUAL, actualCount)));
      return;
    }
    if (maxCount != null && actualCount > maxCount) {
      datasetIssues.add(
          new ValidationIssue(
              null,
              "IMPORT_VALIDATE_ROW_COUNT",
              "row count exceeds maximum, max=" + maxCount + MSG_ACTUAL_SUFFIX + actualCount,
              Map.of(KEY_MAX, maxCount, KEY_ACTUAL, actualCount)));
    }
  }

  private void evaluateChecksum(
      String normalizedPayload,
      ImportPayload importPayload,
      Map<String, Object> ruleSet,
      List<ValidationIssue> datasetIssues,
      Set<String> appliedChecks) {
    Map<String, Object> rule = firstMap(ruleSet, "checksumCheck", "checksum_check");
    if (rule.isEmpty() || !enabled(rule)) {
      return;
    }
    String configuredAlgorithm =
        stringValue(
            firstNonNull(
                rule.get("algorithm"),
                rule.get("checksumType"),
                importPayload == null ? null : importPayload.checksumType()));
    String algorithm =
        !StringUtils.hasText(configuredAlgorithm) || "NONE".equalsIgnoreCase(configuredAlgorithm)
            ? "SHA-256"
            : configuredAlgorithm;
    String expectedChecksum =
        stringValue(
            firstNonNull(
                rule.get("expected"),
                rule.get("expectedValue"),
                importPayload == null ? null : importPayload.checksumValue()));
    if (!StringUtils.hasText(expectedChecksum) || normalizedPayload == null) {
      return;
    }
    appliedChecks.add("checksum_check");
    String actualChecksum = digest(algorithm, normalizedPayload);
    if (!expectedChecksum.equalsIgnoreCase(actualChecksum)) {
      datasetIssues.add(
          new ValidationIssue(
              null,
              "IMPORT_VALIDATE_CHECKSUM",
              "checksum mismatch, expected="
                  + expectedChecksum
                  + MSG_ACTUAL_SUFFIX
                  + actualChecksum,
              Map.of(
                  "algorithm",
                  algorithm,
                  "expected",
                  expectedChecksum,
                  KEY_ACTUAL,
                  actualChecksum)));
    }
  }

  private void evaluateSchema(
      List<String> schemaFields,
      Map<String, Object> ruleSet,
      List<ValidationIssue> datasetIssues,
      Set<String> appliedChecks) {
    Map<String, Object> rule = firstMap(ruleSet, "schemaCheck", "schema_check");
    if (rule.isEmpty() || !enabled(rule)) {
      return;
    }
    if (schemaFields.isEmpty()) {
      return;
    }
    appliedChecks.add("schema_check");
    Set<String> actualFields = new LinkedHashSet<>();
    for (String schemaField : schemaFields) {
      if (StringUtils.hasText(schemaField)) {
        actualFields.add(schemaField);
      }
    }
    List<String> requiredFields =
        stringList(firstNonNull(rule.get("requiredFields"), rule.get("required_fields")));
    List<String> allowedFields =
        stringList(firstNonNull(rule.get("allowedFields"), rule.get("allowed_fields")));
    List<String> missingFields = new ArrayList<>();
    for (String requiredField : requiredFields) {
      if (StringUtils.hasText(requiredField) && !actualFields.contains(requiredField)) {
        missingFields.add(requiredField);
      }
    }
    if (!missingFields.isEmpty()) {
      datasetIssues.add(
          new ValidationIssue(
              null,
              "IMPORT_VALIDATE_SCHEMA",
              "schema missing required fields: " + String.join(",", missingFields),
              Map.of("requiredFields", requiredFields, "actualFields", schemaFields)));
      return;
    }
    if (!allowedFields.isEmpty()) {
      Set<String> allowedFieldSet = new LinkedHashSet<>(allowedFields);
      List<String> unexpectedFields = new ArrayList<>();
      for (String actualField : actualFields) {
        if (!allowedFieldSet.contains(actualField)) {
          unexpectedFields.add(actualField);
        }
      }
      if (!unexpectedFields.isEmpty()) {
        datasetIssues.add(
            new ValidationIssue(
                null,
                "IMPORT_VALIDATE_SCHEMA",
                "schema contains unexpected fields: " + String.join(",", unexpectedFields),
                Map.of("allowedFields", allowedFields, "actualFields", schemaFields)));
      }
    }
  }

  private Map<Long, ValidationIssue> evaluateRecordRules(
      List<Map<String, Object>> rows,
      Map<String, Object> ruleSet,
      Map<Long, ValidationIssue> recordIssues,
      Set<String> appliedChecks,
      Map<String, Set<String>> seenValues,
      long recordBase) {
    if (rows == null || rows.isEmpty()) {
      return Map.of();
    }
    Map<String, Object> nullCheck = firstMap(ruleSet, "nullCheck", "null_check");
    Map<String, Object> fieldRules = firstMap(ruleSet, "fieldRules", "field_rules");
    List<String> uniqueFields = uniqueFields(ruleSet);
    if (!nullCheck.isEmpty() && enabled(nullCheck)) {
      appliedChecks.add("null_check");
    }
    if (!fieldRules.isEmpty()) {
      appliedChecks.add("field_rule_check");
    }
    if (!uniqueFields.isEmpty()) {
      appliedChecks.add("unique_check");
    }
    LinkedHashMap<Long, ValidationIssue> chunkIssues = new LinkedHashMap<>();
    for (int index = 0; index < rows.size(); index++) {
      long recordNo = recordBase + index;
      Map<String, Object> row = rows.get(index);
      ValidationIssue issue = validateNullFields(nullCheck, recordNo, row);
      if (issue == null) {
        issue = validateFieldRules(recordNo, row, fieldRules);
      }
      if (issue == null) {
        issue = validateUniqueFields(recordNo, row, uniqueFields, seenValues);
      }
      if (issue != null) {
        recordIssues.putIfAbsent(recordNo, issue);
        chunkIssues.putIfAbsent(recordNo, issue);
      }
    }
    return chunkIssues;
  }

  private ValidationIssue validateNullFields(
      Map<String, Object> nullCheck, long recordNo, Map<String, Object> row) {
    if (nullCheck.isEmpty() || !enabled(nullCheck)) {
      return null;
    }
    for (String field :
        stringList(firstNonNull(nullCheck.get("fields"), nullCheck.get("requiredFields")))) {
      if (!StringUtils.hasText(stringValue(row.get(field)))) {
        return new ValidationIssue(
            recordNo, "IMPORT_VALIDATE_NULL", field + " must not be null", row);
      }
    }
    return null;
  }

  private ValidationIssue validateFieldRules(
      long recordNo, Map<String, Object> row, Map<String, Object> fieldRules) {
    if (fieldRules.isEmpty()) {
      return null;
    }
    for (Map.Entry<String, Object> entry : fieldRules.entrySet()) {
      String field = entry.getKey();
      Map<String, Object> rule = toMap(entry.getValue());
      if (rule.isEmpty()) {
        continue;
      }
      String value = stringValue(row.get(field));
      String errorCode =
          defaultText(stringValue(rule.get(KEY_ERROR_CODE)), defaultErrorCode(field, rule));
      String explicitMessage = stringValue(rule.get("errorMessage"));
      if (booleanValue(firstNonNull(rule.get(KEY_REQUIRED), rule.get("notNull")), false)
          && !StringUtils.hasText(value)) {
        return new ValidationIssue(
            recordNo, errorCode, defaultText(explicitMessage, field + " is required"), row);
      }
      if (!StringUtils.hasText(value)) {
        continue;
      }
      Integer minLength = integerValue(rule.get("minLength"));
      if (minLength != null && value.length() < minLength) {
        return new ValidationIssue(
            recordNo,
            errorCode,
            defaultText(explicitMessage, field + " length must be >= " + minLength),
            row);
      }
      Integer maxLength = integerValue(rule.get("maxLength"));
      if (maxLength != null && value.length() > maxLength) {
        return new ValidationIssue(
            recordNo,
            errorCode,
            defaultText(explicitMessage, field + " length must be <= " + maxLength),
            row);
      }
      String pattern = stringValue(firstNonNull(rule.get("regex"), rule.get("pattern")));
      if (StringUtils.hasText(pattern) && !value.matches(pattern)) {
        return new ValidationIssue(
            recordNo,
            errorCode,
            defaultText(explicitMessage, field + " does not match pattern " + pattern),
            row);
      }
      List<String> allowedValues =
          stringList(firstNonNull(rule.get(KEY_ALLOWED_VALUES), rule.get("enum")));
      if (!allowedValues.isEmpty() && !containsIgnoreCase(allowedValues, value)) {
        return new ValidationIssue(
            recordNo,
            errorCode,
            defaultText(explicitMessage, field + " value is not allowed: " + value),
            row);
      }
      BigDecimal numberValue = decimalValue(value);
      BigDecimal minValue = decimalValue(rule.get(KEY_MIN));
      if (minValue != null && numberValue != null && numberValue.compareTo(minValue) < 0) {
        return new ValidationIssue(
            recordNo,
            errorCode,
            defaultText(explicitMessage, field + " must be >= " + minValue),
            row);
      }
      BigDecimal maxValue = decimalValue(rule.get(KEY_MAX));
      if (maxValue != null && numberValue != null && numberValue.compareTo(maxValue) > 0) {
        return new ValidationIssue(
            recordNo,
            errorCode,
            defaultText(explicitMessage, field + " must be <= " + maxValue),
            row);
      }
    }
    return null;
  }

  private ValidationIssue validateUniqueFields(
      long recordNo,
      Map<String, Object> row,
      List<String> uniqueFields,
      Map<String, Set<String>> seenValues) {
    for (String field : uniqueFields) {
      String value = stringValue(row.get(field));
      if (!StringUtils.hasText(value)) {
        continue;
      }
      Set<String> seen = seenValues.computeIfAbsent(field, ignored -> new LinkedHashSet<>());
      String normalized = value.toUpperCase(Locale.ROOT);
      if (!seen.add(normalized)) {
        String errorCode =
            "customerNo".equals(field) ? "IMPORT_VALIDATE_DUPLICATE" : "IMPORT_VALIDATE_UNIQUE";
        return new ValidationIssue(recordNo, errorCode, "duplicate " + field + ": " + value, row);
      }
    }
    return null;
  }

  private Map<String, Object> mergedRuleSet(Object templateConfigObject) {
    Map<String, Object> templateConfig = toMap(templateConfigObject);
    Map<String, Object> merged =
        usesGenericJdbcMapped(templateConfig)
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(defaultRuleSet());
    deepMerge(
        merged,
        toMap(
            firstNonNull(
                templateConfig.get("validation_rule_set"),
                templateConfig.get("validationRuleSet"))));
    return merged;
  }

  private Map<String, Object> defaultRuleSet() {
    Map<String, Object> fieldRules = new LinkedHashMap<>();
    fieldRules.put(
        "customerNo", Map.of(KEY_REQUIRED, true, KEY_ERROR_CODE, "IMPORT_VALIDATE_REQUIRED"));
    fieldRules.put(
        "customerName", Map.of(KEY_REQUIRED, true, KEY_ERROR_CODE, "IMPORT_VALIDATE_REQUIRED"));
    fieldRules.put(
        "customerType",
        Map.of(
            KEY_ALLOWED_VALUES,
            List.of("PERSONAL", "ENTERPRISE"),
            KEY_ERROR_CODE,
            "IMPORT_VALIDATE_TYPE_INVALID"));
    fieldRules.put(
        "status",
        Map.of(
            KEY_ALLOWED_VALUES,
            List.of("ACTIVE", "INACTIVE", "FROZEN"),
            KEY_ERROR_CODE,
            "IMPORT_VALIDATE_STATUS_INVALID"));
    Map<String, Object> defaults = new LinkedHashMap<>();
    defaults.put("fieldRules", fieldRules);
    defaults.put("uniqueFields", List.of("customerNo"));
    return defaults;
  }

  @SuppressWarnings("unchecked")
  private void deepMerge(Map<String, Object> target, Map<String, Object> source) {
    if (source == null || source.isEmpty()) {
      return;
    }
    for (Map.Entry<String, Object> entry : source.entrySet()) {
      Object existing = target.get(entry.getKey());
      Object incoming = entry.getValue();
      if (existing instanceof Map<?, ?> existingMap && incoming instanceof Map<?, ?> incomingMap) {
        Map<String, Object> nested = new LinkedHashMap<>((Map<String, Object>) existingMap);
        deepMerge(nested, (Map<String, Object>) incomingMap);
        target.put(entry.getKey(), nested);
        continue;
      }
      target.put(entry.getKey(), incoming);
    }
  }

  private Map<String, Object> payloadToMap(CustomerImportPayload payload) {
    return objectMapper.convertValue(payload, new TypeReference<>() {});
  }

  private boolean usesGenericJdbcMapped(Map<String, Object> templateConfig) {
    if (templateConfig == null || templateConfig.isEmpty()) {
      return false;
    }
    Object direct = templateConfig.get("load_target_ref");
    if (direct != null
        && WorkerPluginIds.IMPORT_LOAD_JDBC_MAPPED.equalsIgnoreCase(
            String.valueOf(direct).trim())) {
      return true;
    }
    if (templateConfig.get("jdbc_mapped_import") != null) {
      return true;
    }
    Map<String, Object> querySchema = toMap(templateConfig.get("query_param_schema"));
    return querySchema.get("jdbcMappedImport") instanceof Map<?, ?>;
  }

  private Map<String, Object> firstMap(Map<String, Object> container, String... keys) {
    for (String key : keys) {
      Map<String, Object> rule = toMap(container.get(key));
      if (!rule.isEmpty()) {
        return rule;
      }
    }
    return Map.of();
  }

  private Map<String, Object> toMap(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> converted = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        converted.put(String.valueOf(entry.getKey()), entry.getValue());
      }
      return converted;
    }
    if (value == null) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(String.valueOf(value), new TypeReference<>() {});
    } catch (Exception ignored) {
      return Map.of();
    }
  }

  private List<String> uniqueFields(Map<String, Object> ruleSet) {
    Map<String, Object> uniqueCheck = firstMap(ruleSet, "uniqueCheck", "unique_check");
    List<String> directFields =
        stringList(firstNonNull(ruleSet.get("uniqueFields"), ruleSet.get("unique_fields")));
    if (!uniqueCheck.isEmpty()) {
      directFields = stringList(firstNonNull(uniqueCheck.get("fields"), directFields));
    }
    return directFields;
  }

  private List<String> stringList(Object value) {
    if (value instanceof Collection<?> collection) {
      List<String> items = new ArrayList<>();
      for (Object item : collection) {
        String text = stringValue(item);
        if (StringUtils.hasText(text)) {
          items.add(text);
        }
      }
      return items;
    }
    if (value == null) {
      return List.of();
    }
    String text = String.valueOf(value);
    if (!StringUtils.hasText(text)) {
      return List.of();
    }
    List<String> items = new ArrayList<>();
    for (String item : text.split(",")) {
      if (StringUtils.hasText(item)) {
        items.add(item.trim());
      }
    }
    return items;
  }

  private boolean enabled(Map<String, Object> rule) {
    return booleanValue(rule.get("enabled"), true);
  }

  private boolean booleanValue(Object value, boolean defaultValue) {
    if (value instanceof Boolean bool) {
      return bool;
    }
    if (value == null) {
      return defaultValue;
    }
    return Boolean.parseBoolean(String.valueOf(value));
  }

  private Integer integerValue(Object value) {
    if (value instanceof Number number) {
      return number.intValue();
    }
    if (value == null) {
      return null;
    }
    String text = String.valueOf(value).trim();
    if (text.isEmpty()) {
      return null;
    }
    try {
      return Integer.valueOf(text);
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private BigDecimal decimalValue(Object value) {
    if (value instanceof BigDecimal bigDecimal) {
      return bigDecimal;
    }
    if (value instanceof Number number) {
      return BigDecimal.valueOf(number.doubleValue());
    }
    if (value == null) {
      return null;
    }
    String text = String.valueOf(value).trim();
    if (text.isEmpty()) {
      return null;
    }
    try {
      return new BigDecimal(text);
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private boolean containsIgnoreCase(List<String> candidates, String value) {
    for (String candidate : candidates) {
      if (candidate != null && candidate.equalsIgnoreCase(value)) {
        return true;
      }
    }
    return false;
  }

  private Object firstNonNull(Object... values) {
    for (Object value : values) {
      if (value != null) {
        return value;
      }
    }
    return null;
  }

  private String defaultErrorCode(String field, Map<String, Object> rule) {
    if (booleanValue(firstNonNull(rule.get(KEY_REQUIRED), rule.get("notNull")), false)) {
      return "IMPORT_VALIDATE_NULL";
    }
    if (firstNonNull(rule.get("minLength"), rule.get("maxLength")) != null) {
      return "IMPORT_VALIDATE_LENGTH";
    }
    if (firstNonNull(rule.get("regex"), rule.get("pattern")) != null) {
      return "IMPORT_VALIDATE_REGEX";
    }
    if (firstNonNull(rule.get(KEY_MIN), rule.get(KEY_MAX)) != null) {
      return "IMPORT_VALIDATE_RANGE";
    }
    if (firstNonNull(rule.get(KEY_ALLOWED_VALUES), rule.get("enum")) != null) {
      if ("customerType".equals(field)) {
        return "IMPORT_VALIDATE_TYPE_INVALID";
      }
      if ("status".equals(field)) {
        return "IMPORT_VALIDATE_STATUS_INVALID";
      }
      return "IMPORT_VALIDATE_ALLOWED_VALUES";
    }
    return "IMPORT_VALIDATE_RULE";
  }

  private String digest(String algorithm, String content) {
    try {
      MessageDigest messageDigest = MessageDigest.getInstance(defaultText(algorithm, "SHA-256"));
      byte[] hash = messageDigest.digest(content.getBytes(StandardCharsets.UTF_8));
      StringBuilder builder = new StringBuilder();
      for (byte item : hash) {
        builder.append(String.format("%02x", item));
      }
      return builder.toString();
    } catch (Exception exception) {
      throw new IllegalStateException("unsupported checksum algorithm: " + algorithm, exception);
    }
  }

  private String stringValue(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private String defaultText(String value, String fallback) {
    return StringUtils.hasText(value) ? value : fallback;
  }

  public record ValidationIssue(
      Long recordNo, String errorCode, String errorMessage, Object rawRecord) {}

  public record ValidationOutcome(
      Map<Long, ValidationIssue> recordIssues,
      List<ValidationIssue> datasetIssues,
      List<String> appliedChecks) {}

  public record ValidationSession(
      ImportJobContext context,
      Map<String, Object> ruleSet,
      long totalCount,
      String normalizedPayload,
      ImportPayload importPayload,
      List<String> schemaFields,
      Map<String, Set<String>> seenValues,
      List<ValidationIssue> datasetIssues,
      Map<Long, ValidationIssue> recordIssues,
      Set<String> appliedChecks) {}
}

package io.github.pinpols.batch.worker.imports.infrastructure.quality;

import static io.github.pinpols.batch.worker.imports.infrastructure.quality.ValidationCoercions.KEY_ALLOWED_VALUES;
import static io.github.pinpols.batch.worker.imports.infrastructure.quality.ValidationCoercions.KEY_ERROR_CODE;
import static io.github.pinpols.batch.worker.imports.infrastructure.quality.ValidationCoercions.KEY_MAX;
import static io.github.pinpols.batch.worker.imports.infrastructure.quality.ValidationCoercions.KEY_MIN;
import static io.github.pinpols.batch.worker.imports.infrastructure.quality.ValidationCoercions.KEY_REQUIRED;
import static io.github.pinpols.batch.worker.imports.infrastructure.quality.ValidationCoercions.booleanValue;
import static io.github.pinpols.batch.worker.imports.infrastructure.quality.ValidationCoercions.containsIgnoreCase;
import static io.github.pinpols.batch.worker.imports.infrastructure.quality.ValidationCoercions.decimalValue;
import static io.github.pinpols.batch.worker.imports.infrastructure.quality.ValidationCoercions.defaultErrorCode;
import static io.github.pinpols.batch.worker.imports.infrastructure.quality.ValidationCoercions.defaultText;
import static io.github.pinpols.batch.worker.imports.infrastructure.quality.ValidationCoercions.enabled;
import static io.github.pinpols.batch.worker.imports.infrastructure.quality.ValidationCoercions.firstNonNull;
import static io.github.pinpols.batch.worker.imports.infrastructure.quality.ValidationCoercions.integerValue;
import static io.github.pinpols.batch.worker.imports.infrastructure.quality.ValidationCoercions.stringList;
import static io.github.pinpols.batch.worker.imports.infrastructure.quality.ValidationCoercions.stringValue;

import io.github.pinpols.batch.common.utils.Texts;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 记录级别校验:null 字段 / 字段规则(required, length, regex, allowed, range)/ 唯一字段。 */
@Component
@RequiredArgsConstructor
public class RecordRuleEvaluator {

  private final ValidationConfigSupport configSupport;

  public Map<Long, ValidationIssue> evaluate(
      ValidationSession session, List<Map<String, Object>> rows, long recordBase) {
    if (rows == null || rows.isEmpty()) {
      return Map.of();
    }
    Map<String, Object> nullCheck =
        configSupport.firstMap(session.ruleSet(), "nullCheck", "null_check");
    Map<String, Object> fieldRules =
        configSupport.firstMap(session.ruleSet(), "fieldRules", "field_rules");
    List<String> uniqueFields = uniqueFields(session.ruleSet());
    Set<String> appliedChecks = session.appliedChecks();
    if (!nullCheck.isEmpty() && enabled(nullCheck)) {
      appliedChecks.add("null_check");
    }
    if (!fieldRules.isEmpty()) {
      appliedChecks.add("field_rule_check");
    }
    if (!uniqueFields.isEmpty()) {
      appliedChecks.add("unique_check");
    }
    Map<Long, ValidationIssue> recordIssues = session.recordIssues();
    Map<String, Set<String>> seenValues = session.seenValues();
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
      if (!Texts.hasText(stringValue(row.get(field)))) {
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
      Map<String, Object> rule = configSupport.toMap(entry.getValue());
      if (rule.isEmpty()) {
        continue;
      }
      ValidationIssue issue = validateOneField(field, rule, recordNo, row);
      if (issue != null) {
        return issue;
      }
    }
    return null;
  }

  private ValidationIssue validateOneField(
      String field, Map<String, Object> rule, long recordNo, Map<String, Object> row) {
    String value = stringValue(row.get(field));
    String errorCode =
        defaultText(stringValue(rule.get(KEY_ERROR_CODE)), defaultErrorCode(field, rule));
    String explicitMessage = stringValue(rule.get("errorMessage"));
    if (booleanValue(firstNonNull(rule.get(KEY_REQUIRED), rule.get("notNull")), false)
        && !Texts.hasText(value)) {
      return new ValidationIssue(
          recordNo, errorCode, defaultText(explicitMessage, field + " is required"), row);
    }
    if (!Texts.hasText(value)) {
      return null;
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
    if (Texts.hasText(pattern) && !value.matches(pattern)) {
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
    return null;
  }

  private ValidationIssue validateUniqueFields(
      long recordNo,
      Map<String, Object> row,
      List<String> uniqueFields,
      Map<String, Set<String>> seenValues) {
    for (String field : uniqueFields) {
      String value = stringValue(row.get(field));
      if (!Texts.hasText(value)) {
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

  private List<String> uniqueFields(Map<String, Object> ruleSet) {
    Map<String, Object> uniqueCheck =
        configSupport.firstMap(ruleSet, "uniqueCheck", "unique_check");
    List<String> directFields =
        stringList(firstNonNull(ruleSet.get("uniqueFields"), ruleSet.get("unique_fields")));
    if (!uniqueCheck.isEmpty()) {
      directFields = stringList(firstNonNull(uniqueCheck.get("fields"), directFields));
    }
    return directFields;
  }
}

package com.example.batch.worker.imports.infrastructure.quality;

import static com.example.batch.worker.imports.infrastructure.quality.ValidationCoercions.KEY_ACTUAL;
import static com.example.batch.worker.imports.infrastructure.quality.ValidationCoercions.KEY_MAX;
import static com.example.batch.worker.imports.infrastructure.quality.ValidationCoercions.KEY_MIN;
import static com.example.batch.worker.imports.infrastructure.quality.ValidationCoercions.MSG_ACTUAL_SUFFIX;
import static com.example.batch.worker.imports.infrastructure.quality.ValidationCoercions.digest;
import static com.example.batch.worker.imports.infrastructure.quality.ValidationCoercions.enabled;
import static com.example.batch.worker.imports.infrastructure.quality.ValidationCoercions.firstNonNull;
import static com.example.batch.worker.imports.infrastructure.quality.ValidationCoercions.integerValue;
import static com.example.batch.worker.imports.infrastructure.quality.ValidationCoercions.stringList;
import static com.example.batch.worker.imports.infrastructure.quality.ValidationCoercions.stringValue;

import com.example.batch.common.utils.Texts;
import com.example.batch.worker.imports.domain.ImportPayload;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 数据集级别校验:行数/checksum/schema 字段。 */
@Component
@RequiredArgsConstructor
public class DatasetRuleEvaluator {

  private final ValidationConfigSupport configSupport;

  public void evaluate(ValidationSession session) {
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

  private void evaluateRowCount(
      long actualCount,
      Map<String, Object> ruleSet,
      List<ValidationIssue> datasetIssues,
      Set<String> appliedChecks) {
    Map<String, Object> rule = configSupport.firstMap(ruleSet, "rowCountCheck", "row_count_check");
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
    Map<String, Object> rule = configSupport.firstMap(ruleSet, "checksumCheck", "checksum_check");
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
        !Texts.hasText(configuredAlgorithm) || "NONE".equalsIgnoreCase(configuredAlgorithm)
            ? "SHA-256"
            : configuredAlgorithm;
    String expectedChecksum =
        stringValue(
            firstNonNull(
                rule.get("expected"),
                rule.get("expectedValue"),
                importPayload == null ? null : importPayload.checksumValue()));
    if (!Texts.hasText(expectedChecksum) || normalizedPayload == null) {
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
    Map<String, Object> rule = configSupport.firstMap(ruleSet, "schemaCheck", "schema_check");
    if (rule.isEmpty() || !enabled(rule)) {
      return;
    }
    if (schemaFields.isEmpty()) {
      return;
    }
    appliedChecks.add("schema_check");
    Set<String> actualFields = new LinkedHashSet<>();
    for (String schemaField : schemaFields) {
      if (Texts.hasText(schemaField)) {
        actualFields.add(schemaField);
      }
    }
    List<String> requiredFields =
        stringList(firstNonNull(rule.get("requiredFields"), rule.get("required_fields")));
    List<String> allowedFields =
        stringList(firstNonNull(rule.get("allowedFields"), rule.get("allowed_fields")));
    List<String> missingFields = new ArrayList<>();
    for (String requiredField : requiredFields) {
      if (Texts.hasText(requiredField) && !actualFields.contains(requiredField)) {
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
}

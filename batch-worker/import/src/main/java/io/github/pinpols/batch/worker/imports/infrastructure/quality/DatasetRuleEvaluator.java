package io.github.pinpols.batch.worker.imports.infrastructure.quality;

import static io.github.pinpols.batch.worker.imports.infrastructure.quality.ValidationCoercions.KEY_ACTUAL;
import static io.github.pinpols.batch.worker.imports.infrastructure.quality.ValidationCoercions.KEY_MAX;
import static io.github.pinpols.batch.worker.imports.infrastructure.quality.ValidationCoercions.KEY_MIN;
import static io.github.pinpols.batch.worker.imports.infrastructure.quality.ValidationCoercions.MSG_ACTUAL_SUFFIX;
import static io.github.pinpols.batch.worker.imports.infrastructure.quality.ValidationCoercions.digest;
import static io.github.pinpols.batch.worker.imports.infrastructure.quality.ValidationCoercions.enabled;
import static io.github.pinpols.batch.worker.imports.infrastructure.quality.ValidationCoercions.firstNonNull;
import static io.github.pinpols.batch.worker.imports.infrastructure.quality.ValidationCoercions.integerValue;
import static io.github.pinpols.batch.worker.imports.infrastructure.quality.ValidationCoercions.stringList;
import static io.github.pinpols.batch.worker.imports.infrastructure.quality.ValidationCoercions.stringValue;

import io.github.pinpols.batch.common.security.CryptoAlgorithms;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.imports.domain.ImportJobContext;
import io.github.pinpols.batch.worker.imports.domain.ImportPayload;
import io.github.pinpols.batch.worker.imports.domain.ImportValidationErrorCode;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 数据集级别校验:行数/checksum/schema 字段 + trailer 控制记录笔数(ADR-041 Phase1.1)。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatasetRuleEvaluator {

  private final ValidationConfigSupport configSupport;

  public void evaluate(ValidationSession session) {
    long totalCount = session.totalCount();
    Map<String, Object> ruleSet = session.ruleSet();
    List<ValidationIssue> datasetIssues = session.datasetIssues();
    Set<String> appliedChecks = session.appliedChecks();
    ImportJobContext context = session.context();
    Integer manifestExpectedCount = manifestExpectedCount(context);
    evaluateRowCount(totalCount, ruleSet, datasetIssues, appliedChecks, manifestExpectedCount);
    evaluateControlRecord(session);
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

  /**
   * file_record.metadata_json 里 sidecar(.chk manifest)声明的期望记录数,由 ValidateStep 在打开校验会话前 写入 context
   * 属性。校验侧据此对账"声明行数 vs 实际累加行数"。
   */
  public static final String ATTR_EXPECTED_RECORD_COUNT = "expectedRecordCount";

  /** 从 context 属性读 sidecar 声明的期望记录数(ValidateStep 塞入);缺失/非数字 → null。 */
  private Integer manifestExpectedCount(ImportJobContext context) {
    if (context == null) {
      return null;
    }
    Object expectedRecordCount = context.getAttributes().get(ATTR_EXPECTED_RECORD_COUNT);
    return integerValue(expectedRecordCount);
  }

  private void evaluateRowCount(
      long actualCount,
      Map<String, Object> ruleSet,
      List<ValidationIssue> datasetIssues,
      Set<String> appliedChecks,
      Integer manifestExpectedCount) {
    Map<String, Object> rule = configSupport.firstMap(ruleSet, "rowCountCheck", "row_count_check");
    boolean ruleEnabled = !rule.isEmpty() && enabled(rule);
    // sidecar 声明的期望行数:模板未显式 pin exact 时,用 manifest 声明值回退对账(ADR-040)。
    // 模板已显式给 exact → 模板优先(template wins),不被 manifest 覆盖。
    Integer ruleExact = ruleEnabled ? integerValue(rule.get("exact")) : null;
    boolean fromManifest = ruleExact == null && manifestExpectedCount != null;
    Integer exactCount =
        ruleExact != null ? ruleExact : (fromManifest ? manifestExpectedCount : null);
    Integer minCount = null;
    Integer maxCount = null;
    if (ruleEnabled) {
      Object minCountSetting = firstNonNull(rule.get(KEY_MIN), rule.get("minimum"));
      minCount = integerValue(minCountSetting);
      Object maxCountSetting = firstNonNull(rule.get(KEY_MAX), rule.get("maximum"));
      maxCount = integerValue(maxCountSetting);
    }
    // 既无模板 rowCountCheck、又无 manifest 声明 → 无可对账,直接跳过。
    if (!ruleEnabled && exactCount == null) {
      return;
    }
    appliedChecks.add("row_count_check");
    if (exactCount != null && actualCount != exactCount) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("expected", exactCount);
      details.put(KEY_ACTUAL, actualCount);
      if (fromManifest) {
        details.put("source", "manifest");
      }
      String rowCountErrorCode = ImportValidationErrorCode.ROW_COUNT.code();
      String rowCountMismatchMessage = "row count mismatch, expected="
          + exactCount
          + (fromManifest ? " (manifest)" : "")
          + MSG_ACTUAL_SUFFIX
          + actualCount;
      Map<String, Object> rowCountMismatchDetails = Map.copyOf(details);
      ValidationIssue rowCountMismatchIssue = new ValidationIssue(
          null, rowCountErrorCode, rowCountMismatchMessage, rowCountMismatchDetails);
      datasetIssues.add(rowCountMismatchIssue);
      return;
    }
    if (minCount != null && actualCount < minCount) {
      String rowCountErrorCode = ImportValidationErrorCode.ROW_COUNT.code();
      String minimumRowCountMessage =
          "row count below minimum, min=" + minCount + MSG_ACTUAL_SUFFIX + actualCount;
      Map<String, Object> minimumRowCountDetails =
          Map.of(KEY_MIN, minCount, KEY_ACTUAL, actualCount);
      ValidationIssue minimumRowCountIssue = new ValidationIssue(
          null, rowCountErrorCode, minimumRowCountMessage, minimumRowCountDetails);
      datasetIssues.add(minimumRowCountIssue);
      return;
    }
    if (maxCount != null && actualCount > maxCount) {
      String rowCountErrorCode = ImportValidationErrorCode.ROW_COUNT.code();
      String maximumRowCountMessage =
          "row count exceeds maximum, max=" + maxCount + MSG_ACTUAL_SUFFIX + actualCount;
      Map<String, Object> maximumRowCountDetails =
          Map.of(KEY_MAX, maxCount, KEY_ACTUAL, actualCount);
      ValidationIssue maximumRowCountIssue = new ValidationIssue(
          null, rowCountErrorCode, maximumRowCountMessage, maximumRowCountDetails);
      datasetIssues.add(maximumRowCountIssue);
    }
  }

  // ADR-041 Phase1.1:trailer 声明笔数 vs 实际解析记录数对账。
  // 声明笔数由 ParseStep 剥离 trailer 后写入 context attributes;未配 trailer / 解析不出笔数则不参与。
  // 规则启用后必须能与 trailer 声明值比较;缺少声明或笔数不一致均阻断交付。
  private void evaluateControlRecord(ValidationSession session) {
    Map<String, Object> rule =
        configSupport.firstMap(session.ruleSet(), "controlRecordCheck", "control_record_check");
    boolean ruleIsNotConfiguredOrDisabled =
        EmptyChecks.isEmpty(Objects.requireNonNull(rule)) || !enabled(rule);
    if (ruleIsNotConfiguredOrDisabled) {
      return;
    }
    Object declared =
        session.context().getAttributes().get(TrailerControlRecord.ATTR_DECLARED_RECORD_COUNT);
    if (!(declared instanceof Number declaredNumber)) {
      log.warn(
          "control-record check enabled but no declared count from trailer: tenantId={}, fileId={}",
          session.context().getTenantId(),
          session.context().getFileId());
      session.appliedChecks().add("control_record_check");
      List<ValidationIssue> datasetIssues = session.datasetIssues();
      String controlRecordErrorCode = ImportValidationErrorCode.CONTROL_RECORD.code();
      Map<String, Object> missingDeclaredCountDetails = Map.of("reason", "MISSING_DECLARED_COUNT");
      ValidationIssue missingDeclaredCountIssue = new ValidationIssue(
          null,
          controlRecordErrorCode,
          "control-record check requires a declared count",
          missingDeclaredCountDetails);
      datasetIssues.add(missingDeclaredCountIssue);
      return;
    }
    session.appliedChecks().add("control_record_check");
    long declaredCount = declaredNumber.longValue();
    long actualCount = session.totalCount();
    if (declaredCount == actualCount) {
      return;
    }
    List<ValidationIssue> datasetIssues = session.datasetIssues();
    String controlRecordErrorCode = ImportValidationErrorCode.CONTROL_RECORD.code();
    String controlRecordCountMismatchMessage = "control-record count mismatch, declared="
        + declaredCount
        + MSG_ACTUAL_SUFFIX
        + actualCount;
    Map<String, Object> controlRecordCountMismatchDetails =
        Map.of("declared", declaredCount, KEY_ACTUAL, actualCount);
    ValidationIssue controlRecordCountMismatchIssue = new ValidationIssue(
        null,
        controlRecordErrorCode,
        controlRecordCountMismatchMessage,
        controlRecordCountMismatchDetails);
    datasetIssues.add(controlRecordCountMismatchIssue);
  }

  private void evaluateChecksum(
      String normalizedPayload,
      ImportPayload importPayload,
      Map<String, Object> ruleSet,
      List<ValidationIssue> datasetIssues,
      Set<String> appliedChecks) {
    Map<String, Object> rule = configSupport.firstMap(ruleSet, "checksumCheck", "checksum_check");
    boolean ruleIsNotConfiguredOrDisabled =
        EmptyChecks.isEmpty(Objects.requireNonNull(rule)) || !enabled(rule);
    if (ruleIsNotConfiguredOrDisabled) {
      return;
    }
    Object configuredAlgorithmValue = firstNonNull(
        rule.get("algorithm"),
        rule.get(PipelineRuntimeKeys.CHECKSUM_TYPE),
        EmptyChecks.isNull(importPayload) ? null : importPayload.checksumType());
    String configuredAlgorithm = stringValue(configuredAlgorithmValue);
    String algorithm =
        !Texts.hasText(configuredAlgorithm) || "NONE".equalsIgnoreCase(configuredAlgorithm)
            ? CryptoAlgorithms.SHA_256
            : configuredAlgorithm;
    Object expectedChecksumValue = firstNonNull(
        rule.get("expected"),
        rule.get("expectedValue"),
        EmptyChecks.isNull(importPayload) ? null : importPayload.checksumValue());
    String expectedChecksum = stringValue(expectedChecksumValue);
    if (!Texts.hasText(expectedChecksum) || normalizedPayload == null) {
      return;
    }
    appliedChecks.add("checksum_check");
    String actualChecksum = digest(algorithm, normalizedPayload);
    if (!expectedChecksum.equalsIgnoreCase(actualChecksum)) {
      String checksumErrorCode = ImportValidationErrorCode.CHECKSUM.code();
      String checksumMismatchMessage =
          "checksum mismatch, expected=" + expectedChecksum + MSG_ACTUAL_SUFFIX + actualChecksum;
      Map<String, Object> checksumMismatchDetails =
          Map.of("algorithm", algorithm, "expected", expectedChecksum, KEY_ACTUAL, actualChecksum);
      ValidationIssue checksumMismatchIssue = new ValidationIssue(
          null, checksumErrorCode, checksumMismatchMessage, checksumMismatchDetails);
      datasetIssues.add(checksumMismatchIssue);
    }
  }

  private void evaluateSchema(
      List<String> schemaFields,
      Map<String, Object> ruleSet,
      List<ValidationIssue> datasetIssues,
      Set<String> appliedChecks) {
    Map<String, Object> rule = configSupport.firstMap(ruleSet, "schemaCheck", "schema_check");
    boolean ruleIsNotConfiguredOrDisabled =
        EmptyChecks.isEmpty(Objects.requireNonNull(rule)) || !enabled(rule);
    if (ruleIsNotConfiguredOrDisabled) {
      return;
    }
    if (schemaFields.isEmpty()) {
      return;
    }
    appliedChecks.add("schema_check");
    Set<String> actualFields = schemaFields.stream()
        .filter(Texts::hasText)
        .collect(Collectors.toCollection(LinkedHashSet::new));
    Object requiredFieldsSetting =
        firstNonNull(rule.get("requiredFields"), rule.get("required_fields"));
    List<String> requiredFields = stringList(requiredFieldsSetting);
    Object allowedFieldsSetting =
        firstNonNull(rule.get("allowedFields"), rule.get("allowed_fields"));
    List<String> allowedFields = stringList(allowedFieldsSetting);
    List<String> missingFields = requiredFields.stream()
        .filter(Texts::hasText)
        .filter(requiredField -> !actualFields.contains(requiredField))
        .toList();
    if (!missingFields.isEmpty()) {
      String errorCode = ImportValidationErrorCode.SCHEMA.code();
      String missingFieldsText = String.join(",", missingFields);
      String message = "schema missing required fields: " + missingFieldsText;
      Map<String, Object> details =
          Map.of("requiredFields", requiredFields, "actualFields", schemaFields);
      ValidationIssue issue = new ValidationIssue(null, errorCode, message, details);
      datasetIssues.add(issue);
      return;
    }
    if (!allowedFields.isEmpty()) {
      Set<String> allowedFieldSet = new LinkedHashSet<>(allowedFields);
      List<String> unexpectedFields = actualFields.stream()
          .filter(actualField -> !allowedFieldSet.contains(actualField))
          .toList();
      if (!unexpectedFields.isEmpty()) {
        String errorCode = ImportValidationErrorCode.SCHEMA.code();
        String unexpectedFieldsText = String.join(",", unexpectedFields);
        String message = "schema contains unexpected fields: " + unexpectedFieldsText;
        Map<String, Object> details =
            Map.of("allowedFields", allowedFields, "actualFields", schemaFields);
        ValidationIssue issue = new ValidationIssue(null, errorCode, message, details);
        datasetIssues.add(issue);
      }
    }
  }
}

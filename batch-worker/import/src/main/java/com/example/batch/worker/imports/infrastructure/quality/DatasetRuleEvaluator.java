package com.example.batch.worker.imports.infrastructure.quality;

import static com.example.batch.worker.imports.infrastructure.quality.ValidationCoercions.KEY_ACTUAL;
import static com.example.batch.worker.imports.infrastructure.quality.ValidationCoercions.KEY_MAX;
import static com.example.batch.worker.imports.infrastructure.quality.ValidationCoercions.KEY_MIN;
import static com.example.batch.worker.imports.infrastructure.quality.ValidationCoercions.MSG_ACTUAL_SUFFIX;
import static com.example.batch.worker.imports.infrastructure.quality.ValidationCoercions.booleanValue;
import static com.example.batch.worker.imports.infrastructure.quality.ValidationCoercions.digest;
import static com.example.batch.worker.imports.infrastructure.quality.ValidationCoercions.enabled;
import static com.example.batch.worker.imports.infrastructure.quality.ValidationCoercions.firstNonNull;
import static com.example.batch.worker.imports.infrastructure.quality.ValidationCoercions.integerValue;
import static com.example.batch.worker.imports.infrastructure.quality.ValidationCoercions.stringList;
import static com.example.batch.worker.imports.infrastructure.quality.ValidationCoercions.stringValue;

import com.example.batch.common.utils.Texts;
import com.example.batch.worker.imports.domain.ImportJobContext;
import com.example.batch.worker.imports.domain.ImportPayload;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    evaluateRowCount(
        session.totalCount(),
        session.ruleSet(),
        session.datasetIssues(),
        session.appliedChecks(),
        manifestExpectedCount(session.context()));
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
    return integerValue(context.getAttributes().get(ATTR_EXPECTED_RECORD_COUNT));
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
    Integer minCount =
        ruleEnabled ? integerValue(firstNonNull(rule.get(KEY_MIN), rule.get("minimum"))) : null;
    Integer maxCount =
        ruleEnabled ? integerValue(firstNonNull(rule.get(KEY_MAX), rule.get("maximum"))) : null;
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
      datasetIssues.add(
          new ValidationIssue(
              null,
              "IMPORT_VALIDATE_ROW_COUNT",
              "row count mismatch, expected="
                  + exactCount
                  + (fromManifest ? " (manifest)" : "")
                  + MSG_ACTUAL_SUFFIX
                  + actualCount,
              Map.copyOf(details)));
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

  // ADR-041 Phase1.1:trailer 声明笔数 vs 实际解析记录数对账。
  // 声明笔数由 ParseStep 剥离 trailer 后写入 context attributes;未配 trailer / 解析不出笔数则不参与。
  // 默认告警(log.warn),blocker=true 才升级为阻断性 ValidationIssue。控制总额(金额合计)对账留待 Phase1.2。
  private void evaluateControlRecord(ValidationSession session) {
    Map<String, Object> rule =
        configSupport.firstMap(session.ruleSet(), "controlRecordCheck", "control_record_check");
    if (rule.isEmpty() || !enabled(rule)) {
      return;
    }
    Object declared =
        session.context().getAttributes().get(TrailerControlRecord.ATTR_DECLARED_RECORD_COUNT);
    if (!(declared instanceof Number declaredNumber)) {
      // 配了 controlRecordCheck 但 trailer 没带出笔数:告警提示配置/文件不一致,但不阻断。
      log.warn(
          "control-record check enabled but no declared count from trailer: tenantId={}, fileId={}",
          session.context().getTenantId(),
          session.context().getFileId());
      return;
    }
    session.appliedChecks().add("control_record_check");
    long declaredCount = declaredNumber.longValue();
    long actualCount = session.totalCount();
    if (declaredCount == actualCount) {
      return;
    }
    if (booleanValue(rule.get("blocker"), false)) {
      session
          .datasetIssues()
          .add(
              new ValidationIssue(
                  null,
                  "IMPORT_VALIDATE_CONTROL_RECORD",
                  "control-record count mismatch, declared="
                      + declaredCount
                      + MSG_ACTUAL_SUFFIX
                      + actualCount,
                  Map.of("declared", declaredCount, KEY_ACTUAL, actualCount)));
      return;
    }
    log.warn(
        "control-record count mismatch (alert-only): declared={}, actual={}, tenantId={},"
            + " fileId={}",
        declaredCount,
        actualCount,
        session.context().getTenantId(),
        session.context().getFileId());
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

package io.github.pinpols.batch.worker.imports.infrastructure.quality;

import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.utils.ContentMaskingUtils;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 按 {@code log_masking_enabled=true + masking_rule_set} 对 {@link ValidationIssue} 的错误信息和原始记录脱敏。
 *
 * <p>安全配置启用 bypass mode 时强制关闭脱敏(本地/E2E 调试)。
 */
@Component
@RequiredArgsConstructor
public class ValidationIssueMasker {

  private final BatchSecurityProperties batchSecurityProperties;

  public ValidationOutcome maskOutcome(ValidationSession session) {
    boolean logMask = logMaskingEnabled(session) && !batchSecurityProperties.isBypassMode();
    String ruleSet = maskingRuleSet(session);
    Map<Long, ValidationIssue> maskedRecordIssues =
        maskRecordIssues(session.recordIssues(), logMask, ruleSet);
    List<ValidationIssue> maskedDatasetIssues =
        maskDatasetIssues(session.datasetIssues(), logMask, ruleSet);
    List<String> appliedChecks = List.copyOf(session.appliedChecks());
    return new ValidationOutcome(maskedRecordIssues, maskedDatasetIssues, appliedChecks);
  }

  private boolean logMaskingEnabled(ValidationSession session) {
    Object cfg = session.context() == null
        ? null
        : session.context().getAttributes().get(PipelineRuntimeKeys.TEMPLATE_CONFIG);
    if (!(cfg instanceof Map<?, ?> map)) {
      return false;
    }
    Object flag = map.get("log_masking_enabled");
    if (Boolean.TRUE.equals(flag)) {
      return true;
    }
    String flagText = String.valueOf(flag);
    return "true".equalsIgnoreCase(flagText);
  }

  private String maskingRuleSet(ValidationSession session) {
    Object cfg = session.context() == null
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
    boolean issueMaskingIsNotApplicable = !mask || issues == null || issues.isEmpty();
    if (issueMaskingIsNotApplicable) {
      return new LinkedHashMap<>(issues == null ? Map.of() : issues);
    }
    return issues.entrySet().stream()
        .collect(Collectors.toMap(
            Map.Entry::getKey,
            entry -> maskedIssue(entry.getValue(), ruleSet),
            (previous, next) -> next,
            LinkedHashMap::new));
  }

  private List<ValidationIssue> maskDatasetIssues(
      List<ValidationIssue> issues, boolean mask, String ruleSet) {
    boolean issueMaskingIsNotApplicable = !mask || issues == null || issues.isEmpty();
    if (issueMaskingIsNotApplicable) {
      return new ArrayList<>(issues == null ? List.of() : issues);
    }
    return issues.stream().map(issue -> maskedIssue(issue, ruleSet)).toList();
  }

  private ValidationIssue maskedIssue(ValidationIssue issue, String ruleSet) {
    Long recordNo = issue.recordNo();
    String errorCode = issue.errorCode();
    String maskedErrorMessage = ContentMaskingUtils.maskPlainText(issue.errorMessage(), ruleSet);
    Object maskedRawRecord = maskIssueRaw(issue.rawRecord(), ruleSet);
    return new ValidationIssue(recordNo, errorCode, maskedErrorMessage, maskedRawRecord);
  }

  private Object maskIssueRaw(Object raw, String ruleSet) {
    if (raw == null) {
      return null;
    }
    if (raw instanceof String text) {
      return ContentMaskingUtils.maskPlainText(text, ruleSet);
    }
    String serializedRawRecord = JsonUtils.toJson(raw);
    return ContentMaskingUtils.maskPlainText(serializedRawRecord, ruleSet);
  }
}

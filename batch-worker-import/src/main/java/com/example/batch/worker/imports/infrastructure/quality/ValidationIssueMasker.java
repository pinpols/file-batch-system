package com.example.batch.worker.imports.infrastructure.quality;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.utils.ContentMaskingUtils;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 按 {@code log_masking_enabled=true + masking_rule_set} 对 {@link ValidationIssue} 的错误信息和原始记录脱敏。
 *
 * <p>{@code BatchSecurityProperties#isBypassMode()} = true 时强制关闭脱敏(本地/E2E 调试)。
 */
@Component
@RequiredArgsConstructor
public class ValidationIssueMasker {

  private final BatchSecurityProperties batchSecurityProperties;

  public ValidationOutcome maskOutcome(ValidationSession session) {
    boolean logMask = logMaskingEnabled(session) && !batchSecurityProperties.isBypassMode();
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
}

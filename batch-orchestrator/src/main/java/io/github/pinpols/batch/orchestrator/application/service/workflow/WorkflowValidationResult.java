package io.github.pinpols.batch.orchestrator.application.service.workflow;

import java.util.List;
import java.util.Map;
import lombok.Builder;

/**
 * ADR-025 工作流静态校验结果。
 *
 * <p>{@link #errors} 非空时拒绝 enable；{@link #warnings} 仅展示，不阻断。
 */
@Builder
public record WorkflowValidationResult(
    List<ValidationIssue> errors, List<ValidationIssue> warnings) {

  public boolean hasErrors() {
    return errors != null && !errors.isEmpty();
  }

  public boolean hasWarnings() {
    return warnings != null && !warnings.isEmpty();
  }

  public static WorkflowValidationResult clean() {
    return WorkflowValidationResult.builder().errors(List.of()).warnings(List.of()).build();
  }

  /** 单条校验问题。 */
  @Builder
  public record ValidationIssue(
      String code, String severity, String nodeCode, String message, Map<String, Object> detail) {

    public static final String SEVERITY_ERROR = "ERROR";
    public static final String SEVERITY_WARN = "WARN";
  }
}

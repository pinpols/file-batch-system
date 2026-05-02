package com.example.batch.console.service;

import com.example.batch.common.constants.CommonErrorMessages;
import com.example.batch.common.enums.AiPromptCategory;
import com.example.batch.common.enums.AiPromptDecision;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.Texts;
import com.example.batch.console.config.ConsoleAiProperties;
import com.example.batch.console.support.auth.AiPromptGateResult;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ConsoleAiPromptGuard {

  private final ConsoleAiProperties properties;

  public AiPromptGateResult check(String prompt) {
    if (!properties.isEnabled()) {
      return AiPromptGateResult.rejected(
          AiPromptDecision.REJECTED_DISABLED,
          AiPromptCategory.OUT_OF_SCOPE,
          CommonErrorMessages.AI_ASSISTANT_DISABLED);
    }
    if (!Texts.hasText(prompt)) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.prompt.required");
    }
    if (prompt.length() > properties.getMaxPromptLength()) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.prompt.too_long");
    }
    String normalized = prompt.trim();
    String lower = normalized.toLowerCase(Locale.ROOT);
    for (String blockedKeyword : properties.getBlockedKeywords()) {
      if (contains(normalized, lower, blockedKeyword)) {
        return AiPromptGateResult.rejected(
            AiPromptDecision.REJECTED_SAFETY,
            AiPromptCategory.OUT_OF_SCOPE,
            CommonErrorMessages.PROMPT_VIOLATES_SAFETY_POLICY);
      }
    }
    for (String keyword : properties.getDomainKeywords()) {
      if (contains(normalized, lower, keyword)) {
        return AiPromptGateResult.approved(resolveCategory(keyword), normalized);
      }
    }
    return AiPromptGateResult.rejected(
        AiPromptDecision.REJECTED_SCOPE,
        AiPromptCategory.OUT_OF_SCOPE,
        CommonErrorMessages.PROMPT_OUT_OF_SCOPE);
  }

  private boolean contains(String normalized, String lower, String keyword) {
    if (!Texts.hasText(keyword)) {
      return false;
    }
    String needle = keyword.toLowerCase(Locale.ROOT);
    return normalized.contains(keyword) || lower.contains(needle);
  }

  private AiPromptCategory resolveCategory(String keyword) {
    String normalized = keyword.toLowerCase(Locale.ROOT);
    if (normalized.contains("file")
        || normalized.contains("文件")
        || normalized.contains("archive")
        || normalized.contains("dispatch")
        || normalized.contains("reconcile")
        || normalized.contains("归档")
        || normalized.contains("重分发")) {
      return AiPromptCategory.FILE_GOVERNANCE;
    }
    if (normalized.contains("workflow")
        || normalized.contains("dag")
        || normalized.contains("节点")
        || normalized.contains("工作流")) {
      return AiPromptCategory.WORKFLOW;
    }
    if (normalized.contains("worker")
        || normalized.contains("trigger")
        || normalized.contains("orchestrator")
        || normalized.contains("job")
        || normalized.contains("partition")
        || normalized.contains("task")
        || normalized.contains("调度")
        || normalized.contains("实例")
        || normalized.contains("分片")) {
      return AiPromptCategory.PLATFORM;
    }
    return AiPromptCategory.OPERATIONS;
  }
}

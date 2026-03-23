package com.example.batch.console.service;

import com.example.batch.common.enums.AiPromptCategory;
import com.example.batch.console.support.AiPromptGateResult;
import com.example.batch.common.enums.AiPromptDecision;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.console.config.ConsoleAiProperties;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class ConsoleAiPromptGuard {

    private final ConsoleAiProperties properties;

    public AiPromptGateResult check(String prompt) {
        if (!properties.isEnabled()) {
            return AiPromptGateResult.rejected(AiPromptDecision.REJECTED_DISABLED, AiPromptCategory.OUT_OF_SCOPE, "ai assistant is disabled");
        }
        if (!StringUtils.hasText(prompt)) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "prompt is required");
        }
        if (prompt.length() > properties.getMaxPromptLength()) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "prompt is too long");
        }
        String normalized = prompt.trim();
        String lower = normalized.toLowerCase(Locale.ROOT);
        for (String blockedKeyword : properties.getBlockedKeywords()) {
            if (contains(normalized, lower, blockedKeyword)) {
                return AiPromptGateResult.rejected(
                        AiPromptDecision.REJECTED_SAFETY,
                        AiPromptCategory.OUT_OF_SCOPE,
                        "prompt violates safety policy"
                );
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
                "prompt is outside the platform scope"
        );
    }

    private boolean contains(String normalized, String lower, String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return false;
        }
        String needle = keyword.toLowerCase(Locale.ROOT);
        return normalized.contains(keyword) || lower.contains(needle);
    }

    private AiPromptCategory resolveCategory(String keyword) {
        String normalized = keyword.toLowerCase(Locale.ROOT);
        if (normalized.contains("file") || normalized.contains("文件") || normalized.contains("archive")
                || normalized.contains("dispatch") || normalized.contains("reconcile") || normalized.contains("归档")
                || normalized.contains("重分发")) {
            return AiPromptCategory.FILE_GOVERNANCE;
        }
        if (normalized.contains("workflow") || normalized.contains("dag") || normalized.contains("节点") || normalized.contains("工作流")) {
            return AiPromptCategory.WORKFLOW;
        }
        if (normalized.contains("worker") || normalized.contains("trigger") || normalized.contains("orchestrator")
                || normalized.contains("job") || normalized.contains("partition") || normalized.contains("task")
                || normalized.contains("调度") || normalized.contains("实例") || normalized.contains("分片")) {
            return AiPromptCategory.PLATFORM;
        }
        return AiPromptCategory.OPERATIONS;
    }
}
